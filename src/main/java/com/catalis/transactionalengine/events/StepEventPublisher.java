package com.catalis.transactionalengine.events;

import reactor.core.publisher.Mono;

/**
 * Hexagonal port for publishing step events to external MQs (Kafka, SQS, etc.).
 * A default Spring in-memory implementation is provided to publish as ApplicationEvents
 * when no custom publisher bean is present.
 */
public interface StepEventPublisher {
    Mono<Void> publish(StepEventEnvelope event);
}
