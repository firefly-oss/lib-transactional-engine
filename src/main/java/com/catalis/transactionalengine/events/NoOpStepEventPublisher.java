package com.catalis.transactionalengine.events;

import reactor.core.publisher.Mono;

/**
 * Default no-op implementation of StepEventPublisher.
 * 
 * Used when no custom event publisher is configured by the microservice.
 * Events are silently discarded.
 */
public class NoOpStepEventPublisher implements StepEventPublisher {
    
    @Override
    public Mono<Void> publish(StepEventEnvelope event) {
        return Mono.empty();
    }
}
