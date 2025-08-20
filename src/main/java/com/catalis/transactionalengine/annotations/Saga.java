package com.catalis.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Marks a Spring bean as a Saga orchestrator.
 * <p>
 * The {@code name} is the identifier used to execute the saga via {@code SagaEngine.run(name,...)}.
 * Classes annotated with {@link Saga} are discovered by {@link com.catalis.transactionalengine.registry.SagaRegistry}
 * at application startup.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Saga {
    String name();
}
