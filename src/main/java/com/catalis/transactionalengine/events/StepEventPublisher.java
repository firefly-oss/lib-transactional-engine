package com.catalis.transactionalengine.events;

import reactor.core.publisher.Mono;

/**
 * Port interface for publishing saga step events.
 * 
 * Microservices must implement this interface to handle event publishing
 * through their chosen messaging infrastructure (Kafka, SQS, RabbitMQ, etc.).
 * 
 * The library provides a default no-op implementation when no custom
 * publisher is configured.
 */
public interface StepEventPublisher {
    
    /**
     * Publishes a step event to the configured messaging infrastructure.
     * 
     * @param event The step event to publish
     * @return A Mono that completes when the event is successfully published
     */
    Mono<Void> publish(StepEventEnvelope event);
}
