package com.firefly.transactionalengine.engine;

import com.firefly.transactionalengine.core.SagaContext;

/**
 * Functional resolver to compute a step input lazily from the current SagaContext.
 * The engine evaluates resolvers right before executing the step, once all dependencies
 * from previous layers have produced their results. The resolved value is cached so
 * that compensation can reuse the original input when needed.
 */
@FunctionalInterface
public interface StepInputResolver {
    Object resolve(SagaContext ctx);
}
