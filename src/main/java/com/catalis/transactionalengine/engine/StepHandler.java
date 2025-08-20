package com.catalis.transactionalengine.engine;

import com.catalis.transactionalengine.core.SagaContext;
import reactor.core.publisher.Mono;

/**
 * Functional step handler allowing programmatic saga step execution without reflection.
 * Implementations can optionally provide a compensation by overriding {@link #compensate(Object, SagaContext)}.
 *
 * @param <I> input type
 * @param <O> output type
 */
public interface StepHandler<I, O> {
    /**
     * Execute the step business logic.
     */
    Mono<O> execute(I input, SagaContext ctx);

    /**
     * Optional compensation logic. Default is no-op.
     */
    default Mono<Void> compensate(Object arg, SagaContext ctx) {
        return Mono.empty();
    }
}
