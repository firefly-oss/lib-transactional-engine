/*
 * Copyright 2025 Firefly Software Solutions Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.firefly.transactionalengine.annotations;

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
 * Duration configuration:
 * - Prefer Duration via SagaBuilder for readability. When using annotations, specify milliseconds via backoffMs/timeoutMs.
 * - Defaults: backoff = 100ms; timeout = disabled (0). Use retry with backoff for resilience.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SagaStep {
    String id();
    /** Optional name of an in-class compensation method. Leave empty when using @CompensationSagaStep. */
    String compensate() default "";
    String[] dependsOn() default {};
    int retry() default 0;
    /** Backoff between retries (milliseconds). -1 = inherit default. */
    long backoffMs() default -1;
    /** Per-attempt timeout (milliseconds). 0 = disabled; -1 = inherit default. */
    long timeoutMs() default -1;
    /** Optional jitter configuration: when true, backoff delay will be randomized by jitterFactor. */
    boolean jitter() default false;
    /** Jitter factor in range [0.0, 1.0]. e.g., 0.5 means +/-50% around backoff. */
    double jitterFactor() default 0.5d;
    String idempotencyKey() default "";
    /** Hint that this step performs CPU-bound work and can be scheduled on a CPU scheduler. */
    boolean cpuBound() default false;

    // Compensation-specific overrides (optional). Use negative values to indicate "inherit from step".
    int compensationRetry() default -1;
    long compensationTimeoutMs() default -1;
    long compensationBackoffMs() default -1;
    boolean compensationCritical() default false;
}
