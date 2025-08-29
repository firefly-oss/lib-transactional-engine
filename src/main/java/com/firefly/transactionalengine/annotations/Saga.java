package com.firefly.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Marks a Spring bean as a Saga orchestrator.
 * <p>
 * The {@code name} is the identifier used to execute the saga via {@code SagaEngine.execute(name, ...)}.
 * Classes annotated with {@link Saga} are discovered by {@link com.firefly.transactionalengine.registry.SagaRegistry}
 * at application startup.
 * <p>
 * Note: Older {@code run(...)} overloads still exist for backward compatibility but are deprecated in favor of
 * the typed {@code execute(...)} API that works with {@link com.firefly.transactionalengine.engine.StepInputs} and
 * returns a typed {@link com.firefly.transactionalengine.core.SagaResult}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Saga {
    String name();
    /** Optional cap for the number of steps executed concurrently within the same layer. 0 means unbounded. */
    int layerConcurrency() default 0;
}
