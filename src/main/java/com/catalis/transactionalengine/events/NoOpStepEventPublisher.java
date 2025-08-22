package com.catalis.transactionalengine.events;

import reactor.core.publisher.Mono;

/** Default no-op publisher used when engine is constructed without a publisher (e.g., in tests). */
public class NoOpStepEventPublisher implements StepEventPublisher {
    @Override
    public Mono<Void> publish(StepEventEnvelope event) {
        return Mono.empty();
    }
}
