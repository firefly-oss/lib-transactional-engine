package com.catalis.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Declares a SAGA step within an orchestrator. A step typically performs an external call.
 *
 * Supported method signatures:
 * - Two parameters: (InputType input, SagaContext ctx)
 * - One parameter: (InputType input) or (SagaContext ctx)
 * - Zero parameters
 *
 * Return type:
 * - Preferably Reactor Mono<T>, but plain T is also supported (it will be wrapped).
 *
 * Compensation method name must refer to a method on the same class. Compensation method signatures mirror the above;
 * when an argument is expected, the engine will pass either the original step input or the step result (if types match),
 * plus SagaContext when present. See README for details.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SagaStep {
    String id();
    String compensate();
    String[] dependsOn() default {};
    int retry() default 0;
    long backoffMs() default 0;
    long timeoutMs() default 0;
    String idempotencyKey() default "";
}
