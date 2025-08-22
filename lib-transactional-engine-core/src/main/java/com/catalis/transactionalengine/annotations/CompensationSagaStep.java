package com.catalis.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Declares a compensation method for a saga step that may live outside of the orchestrator class.
 *
 * Usage example:
 *
 *  @CompensationSagaStep(saga = "OrderSaga", forStepId = "reserveFunds")
 *  public Mono<Void> releaseFunds(ReserveCmd cmd, SagaContext ctx) { ... }
 *
 * The method signature follows the same conventions as in-class compensation for @SagaStep:
 * - Parameters can include the original step input or the step result (engine decides by type), and/or SagaContext.
 * - Return type can be Mono<Void> or void (void will be adapted to Mono.empty()).
 *
 * When both in-class and external compensations are provided for the same step, the external declaration takes precedence.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CompensationSagaStep {
    /** The saga name as declared in {@link Saga#name()}. */
    String saga();
    /** The step id to compensate, as declared in {@link SagaStep#id()}. */
    String forStepId();
}
