package com.catalis.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Declares a SAGA step within an orchestrator. A step typically performs an external call.
 *
 * Supported method signatures:
 * - Multi-parameter injection supported via annotations on parameters:
 *   - @Input or @Input("key") for step input (optionally from a Map)
 *   - @FromStep("stepId") for another step's result
 *   - @Header("X-User-Id") for a single header value
 *   - @Headers for the full headers Map<String,String>
 *   - SagaContext is injected by type
 * - Backwards compatible with legacy styles: (input, SagaContext) | (input) | (SagaContext) | ().
 *
 * Return type:
 * - Preferably Reactor Mono<T>, but plain T is also supported (it will be wrapped).
 *
 * Compensation method name must refer to a method on the same class. Compensation method signatures mirror the above;
 * when an argument is expected, the engine will pass either the original step input or the step result (if types match),
 * plus SagaContext when present. See README for details.
 *
 * Duration configuration:
 * - Prefer using ISO-8601 strings for durations (e.g., "PT1S" for 1 second) in {@link #timeout()} and {@link #backoff()}.
 * - Millisecond fields {@link #timeoutMs()} and {@link #backoffMs()} are kept for backward compatibility and are deprecated.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SagaStep {
    String id();
    String compensate();
    String[] dependsOn() default {};
    int retry() default 0;
    /** ISO-8601 duration string (e.g., "PT1S"). Preferred over timeoutMs. */
    String timeout() default "";
    /** ISO-8601 duration string (e.g., "PT100MS"). Preferred over backoffMs. */
    String backoff() default "";
    /** Optional jitter configuration: when true, backoff delay will be randomized by jitterFactor. */
    boolean jitter() default false;
    /** Jitter factor in range [0.0, 1.0]. e.g., 0.5 means +/-50% around backoff. */
    double jitterFactor() default 0.5d;
    /** Deprecated: use {@link #backoff()} */
    @Deprecated
    long backoffMs() default 0;
    /** Deprecated: use {@link #timeout()} */
    @Deprecated
    long timeoutMs() default 0;
    String idempotencyKey() default "";
    /** Hint that this step performs CPU-bound work and can be scheduled on a CPU scheduler. */
    boolean cpuBound() default false;
}
