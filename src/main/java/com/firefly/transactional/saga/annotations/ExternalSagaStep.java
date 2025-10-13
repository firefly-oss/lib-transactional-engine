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


package com.firefly.transactional.saga.annotations;

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
    /** Backoff between retries (milliseconds). -1 = inherit default. */
    long backoffMs() default -1;
    /** Per-attempt timeout (milliseconds). 0 = disabled; -1 = inherit default. */
    long timeoutMs() default -1;
    boolean jitter() default false;
    double jitterFactor() default 0.5d;
    String idempotencyKey() default "";
    boolean cpuBound() default false;

    // Compensation-specific overrides (optional). Use negative values to indicate "inherit from step".
    int compensationRetry() default -1;
    long compensationTimeoutMs() default -1;
    long compensationBackoffMs() default -1;
    boolean compensationCritical() default false;
}
