package com.mkurt.productcompositeservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mkurt.api.core.product.Product;
import com.mkurt.api.core.product.ProductService;
import com.mkurt.api.core.recommendation.Recommendation;
import com.mkurt.api.core.recommendation.RecommendationService;
import com.mkurt.api.core.review.Review;
import com.mkurt.api.core.review.ReviewService;
import com.mkurt.api.event.Event;
import com.mkurt.api.exception.InvalidInputException;
import com.mkurt.api.exception.NotFoundException;
import com.mkurt.util.http.HttpErrorInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.io.IOException;

import static com.mkurt.api.event.Event.Type.CREATE;
import static com.mkurt.api.event.Event.Type.DELETE;
import static java.util.logging.Level.FINE;
import static reactor.core.publisher.Flux.empty;

@Service
public class ProductCompositeIntegration implements ProductService, RecommendationService, ReviewService {

    private static final Logger LOG = LoggerFactory.getLogger(ProductCompositeIntegration.class);

    private final WebClient webClient;
    private final ObjectMapper mapper;

    private static final String PRODUCT_SERVICE_URL = "http://product";
    private static final String RECOMMENDATION_SERVICE_URL = "http://recommendation";
    private static final String REVIEW_SERVICE_URL = "http://review";

    private final StreamBridge streamBridge;

    private final Scheduler publishEventScheduler;

    @Autowired
    public ProductCompositeIntegration(
            @Qualifier("publishEventScheduler") Scheduler publishEventScheduler,

            WebClient.Builder webClient,
            ObjectMapper mapper,
            StreamBridge streamBridge
    ) {
        this.publishEventScheduler = publishEventScheduler;
        this.webClient = webClient.build();
        this.mapper = mapper;
        this.streamBridge = streamBridge;
    }

    @Override
    public Mono<Product> createProduct(Product body) {

        return Mono.fromCallable(() -> {
            sendMessage("products-out-0", new Event(CREATE, body.getProductId(), body));
            return body;
        }).subscribeOn(publishEventScheduler);
        /** Since the sendMessage() uses blocking code, when calling streamBridge, it is executed on a thread provided
         * by a dedicated scheduler, publishEventScheduler. This is the same approach as for handling blocking JPA code
         * in the review microservice. See the section on Dealing with blocking code for details. */
    }

    @Override
    public Mono<Product> getProduct(int productId) {
        String url = PRODUCT_SERVICE_URL + "/product/" + productId;
        LOG.debug("Will call the getProduct API on URL: {}", url);

        return webClient
                .get()
                .uri(url)
                .retrieve()
                .bodyToMono(Product.class)
                .log(LOG.getName(), FINE)
                .onErrorMap(WebClientResponseException.class, this::handleException);
    }

    @Override
    public Mono<Void> deleteProduct(int productId) {

        return Mono.fromRunnable(() -> sendMessage("products-out-0", new Event(DELETE, productId, null)))
                .subscribeOn(publishEventScheduler).then();
    }

    @Override
    public Mono<Recommendation> createRecommendation(Recommendation body) {

        return Mono.fromCallable(() -> {
            sendMessage("recommendations-out-0", new Event(CREATE, body.getProductId(), body));
            return body;
        }).subscribeOn(publishEventScheduler);
    }

    @Override
    public Flux<Recommendation> getRecommendations(int productId) {

        String url = RECOMMENDATION_SERVICE_URL + "/recommendation?productId=" + productId;

        LOG.debug("Will call the getRecommendations API on URL: {}", url);

        // Return an empty result if something goes wrong to make it possible for the composite service to return partial responses
        return webClient
                .get()
                .uri(url)
                .retrieve()
                .bodyToFlux(Recommendation.class)
                .log(LOG.getName(), FINE)
                .onErrorResume(error -> empty());
    }

    @Override
    public Mono<Void> deleteRecommendations(int productId) {

        return Mono.fromRunnable(() -> sendMessage("recommendations-out-0", new Event(DELETE, productId, null)))
                .subscribeOn(publishEventScheduler)
                .then();
    }

    @Override
    public Mono<Review> createReview(Review body) {

        return Mono.fromCallable(() -> {
            sendMessage("reviews-out-0", new Event(CREATE, body.getProductId(), body));
            return body;
        }).subscribeOn(publishEventScheduler);
    }

    @Override
    public Flux<Review> getReviews(int productId) {

        String url = REVIEW_SERVICE_URL + "/review?productId=" + productId;

        LOG.debug("Will call the getReviews API on URL: {}", url);

        // Return an empty result if something goes wrong to make it possible for the composite service to return partial responses
        return webClient
                .get()
                .uri(url)
                .retrieve()
                .bodyToFlux(Review.class)
                .log(LOG.getName(), FINE)
                .onErrorResume(error -> empty());
    }

    @Override
    public Mono<Void> deleteReviews(int productId) {

        return Mono.fromRunnable(() -> sendMessage("reviews-out-0", new Event(DELETE, productId, null)))
                .subscribeOn(publishEventScheduler)
                .then();
    }

    public Mono<Health> getProductHealth() {
        return getHealth(PRODUCT_SERVICE_URL);
    }

    public Mono<Health> getRecommendationHealth() {
        return getHealth(RECOMMENDATION_SERVICE_URL);
    }

    public Mono<Health> getReviewHealth() {
        return getHealth(REVIEW_SERVICE_URL);
    }

    private Mono<Health> getHealth(String url) {
        url += "/actuator/health";
        LOG.debug("Will call the Health API on URL: {}", url);
        return webClient
                .get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .map(s -> new Health.Builder().up().build())
                .onErrorResume(ex -> Mono.just(new Health.Builder().down(ex).build()))
                .log(LOG.getName(), FINE);
    }

    private void sendMessage(String bindingName, Event event) {
        LOG.debug("Sending a {} message to {}", event.getEventType(), bindingName);

        Message message = MessageBuilder.withPayload(event)
                .setHeader("partitionKey", event.getKey())
                .build();

        streamBridge.send(bindingName, message);
    }

    private Throwable handleException(Throwable ex) {

        if (!(ex instanceof WebClientResponseException)) {
            LOG.warn("Got a unexpected error: {}, will rethrow it", ex.toString());
            return ex;
        }

        WebClientResponseException wcre = (WebClientResponseException)ex;

        switch (wcre.getStatusCode()) {

            case NOT_FOUND:
                return new NotFoundException(getErrorMessage(wcre));

            case UNPROCESSABLE_ENTITY :
                return new InvalidInputException(getErrorMessage(wcre));

            default:
                LOG.warn("Got an unexpected HTTP error: {}, will rethrow it", wcre.getStatusCode());
                LOG.warn("Error body: {}", wcre.getResponseBodyAsString());
                return ex;
        }
    }

    private String getErrorMessage(WebClientResponseException ex) {
        try {
            return mapper.readValue(ex.getResponseBodyAsString(), HttpErrorInfo.class).getMessage();
        } catch (IOException ioex) {
            return ex.getMessage();
        }
    }

}