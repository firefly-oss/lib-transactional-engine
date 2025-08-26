package com.catalis.transactionalengine.annotations;

import com.catalis.transactionalengine.config.TransactionalEngineConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Enables the Transactional Engine (Saga orchestrator) components in a Spring application.
 * <p>
 * Imports {@link com.catalis.transactionalengine.config.TransactionalEngineConfiguration} that wires:
 * - {@code SagaRegistry}: scans for @Saga beans and indexes steps
 * - {@code SagaEngine}: the in-memory orchestrator
 * - {@code SagaEvents}: default implementation {@code SagaLoggerEvents} (override by declaring your own bean)
 * - {@code StepLoggingAspect}: AOP aspect for additional logging
 * - {@code WebClient.Builder}: convenience bean for HTTP clients
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Import(TransactionalEngineConfiguration.class)
public @interface EnableTransactionalEngine {
}
