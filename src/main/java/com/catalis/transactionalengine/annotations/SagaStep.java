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
 * Compensation can be declared in two ways:
 * - In-class: `compensate` refers to a method on the same orchestrator class (traditional style).
 * - External: via `@CompensationSagaStep(saga = ..., forStepId = ...)` on any Spring bean. When both are present, the external mapping takes precedence.
 * Compensation method signatures mirror the above; when an argument is expected, the engine will pass either the original
 * step input or the step result (if types match), plus SagaContext when present. See README for details.
 *
 * Duration configuration (updated):
 * - ISO-8601 String-based fields timeout() and backoff() are deprecated.
 * - Prefer setting durations via SagaBuilder's Duration-based methods or omit them to use defaults.
 * - Defaults: backoff = 100ms; timeout = disabled (0). Use retry with backoff for resilience.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SagaStep {
    String id();
    String compensate();
    String[] dependsOn() default {};
    int retry() default 0;
    /** Deprecated: use programmatic Duration configuration via SagaBuilder or rely on defaults. */
    @Deprecated
    String timeout() default "";
    /** Deprecated: use programmatic Duration configuration via SagaBuilder or rely on defaults. */
    @Deprecated
    String backoff() default "";
    /** Optional jitter configuration: when true, backoff delay will be randomized by jitterFactor. */
    boolean jitter() default false;
    /** Jitter factor in range [0.0, 1.0]. e.g., 0.5 means +/-50% around backoff. */
    double jitterFactor() default 0.5d;
    /** Legacy: millisecond fields are still accepted. Prefer builder-based Duration or defaults. */
    @Deprecated
    long backoffMs() default 0;
    /** Legacy: millisecond fields are still accepted. Prefer builder-based Duration or defaults. */
    @Deprecated
    long timeoutMs() default 0;
    String idempotencyKey() default "";
    /** Hint that this step performs CPU-bound work and can be scheduled on a CPU scheduler. */
    boolean cpuBound() default false;

    // Compensation-specific overrides (optional). Use negative values to indicate "inherit from step".
    int compensationRetry() default -1;
    long compensationTimeoutMs() default -1;
    long compensationBackoffMs() default -1;
    boolean compensationCritical() default false;
}
