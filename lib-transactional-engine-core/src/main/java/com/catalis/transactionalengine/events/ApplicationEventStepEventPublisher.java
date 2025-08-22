package com.catalis.transactionalengine.events;

import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;

/**
 * Default adapter that publishes StepEventEnvelope as a Spring ApplicationEvent.
 */
public class ApplicationEventStepEventPublisher implements StepEventPublisher {
    private final ApplicationEventPublisher delegate;

    public ApplicationEventStepEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<Void> publish(StepEventEnvelope event) {
        return Mono.fromRunnable(() -> delegate.publishEvent(event));
    }
}
