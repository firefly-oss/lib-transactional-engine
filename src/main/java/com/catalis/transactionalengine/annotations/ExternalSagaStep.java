package com.catalis.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Declares a Saga step outside the orchestrator class.
 *
 * Usage example:
 *
 *  @ExternalSagaStep(saga = "OrderSaga", id = "reserveFunds", compensate = "releaseFunds")
 *  Mono<String> reserveFunds(@Input ReserveCmd cmd, SagaContext ctx) { ... }
 *
 * The attributes mirror those in {@link SagaStep}, but you must specify the target saga by name.
 * The compensate attribute refers to a method on the same bean (external class). If you want to
 * declare compensation in a different bean, use {@link CompensationSagaStep} which takes precedence.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExternalSagaStep {
    /** The saga name as declared in {@link Saga#name()}. */
    String saga();
    /** Step identifier, same semantics as {@link SagaStep#id()}. */
    String id();
    /** Optional name of a compensation method on the same bean. */
    String compensate() default "";
    String[] dependsOn() default {};
    int retry() default 0;
    @Deprecated
    String timeout() default "";
    @Deprecated
    String backoff() default "";
    boolean jitter() default false;
    double jitterFactor() default 0.5d;
    @Deprecated
    long backoffMs() default 0;
    @Deprecated
    long timeoutMs() default 0;
    String idempotencyKey() default "";
    boolean cpuBound() default false;

    // Compensation-specific overrides (optional). Use negative values to indicate "inherit from step".
    int compensationRetry() default -1;
    long compensationTimeoutMs() default -1;
    long compensationBackoffMs() default -1;
    boolean compensationCritical() default false;
}
