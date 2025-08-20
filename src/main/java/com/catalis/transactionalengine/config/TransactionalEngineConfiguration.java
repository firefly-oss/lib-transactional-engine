package com.catalis.transactionalengine.config;

import com.catalis.transactionalengine.aop.StepLoggingAspect;
import com.catalis.transactionalengine.engine.SagaEngine;
import com.catalis.transactionalengine.observability.SagaEvents;
import com.catalis.transactionalengine.observability.SagaLoggerEvents;
import com.catalis.transactionalengine.registry.SagaRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Spring configuration that wires the Transactional Engine components.
 * Users typically activate it via {@link com.catalis.transactionalengine.annotations.EnableTransactionalEngine}.
 */
@Configuration
@EnableAspectJAutoProxy
public class TransactionalEngineConfiguration {

    @Bean
    public SagaRegistry sagaRegistry(ApplicationContext applicationContext) {
        return new SagaRegistry(applicationContext);
    }

    @Bean
    public SagaEngine sagaEngine(SagaRegistry registry, SagaEvents events) {
        return new SagaEngine(registry, events);
    }

    @Bean
    public SagaEvents sagaEvents() {
        return new SagaLoggerEvents();
    }

    @Bean
    public StepLoggingAspect stepLoggingAspect() {
        return new StepLoggingAspect();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
