package com.catalis.transactionalengine.config;

import com.catalis.transactionalengine.aop.StepLoggingAspect;
import com.catalis.transactionalengine.engine.SagaEngine;
import com.catalis.transactionalengine.observability.*;
import com.catalis.transactionalengine.registry.SagaRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

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
    public SagaLoggerEvents sagaLoggerEvents() {
        return new SagaLoggerEvents();
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(SagaEvents.class)
    public SagaEvents sagaEventsComposite(SagaLoggerEvents logger,
                                          ObjectProvider<SagaMicrometerEvents> micrometer,
                                          ObjectProvider<SagaTracingEvents> tracing) {
        List<SagaEvents> sinks = new ArrayList<>();
        sinks.add(logger);
        SagaMicrometerEvents m = micrometer.getIfAvailable();
        if (m != null) sinks.add(m);
        SagaTracingEvents t = tracing.getIfAvailable();
        if (t != null) sinks.add(t);
        return new CompositeSagaEvents(sinks);
    }

    @Configuration
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnBean(type = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerAutoConfig {
        @Bean
        public SagaMicrometerEvents sagaMicrometerEvents(io.micrometer.core.instrument.MeterRegistry registry) {
            return new SagaMicrometerEvents(registry);
        }
    }

    @Configuration
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    @ConditionalOnBean(type = "io.micrometer.tracing.Tracer")
    static class TracingAutoConfig {
        @Bean
        public SagaTracingEvents sagaTracingEvents(io.micrometer.tracing.Tracer tracer) {
            return new SagaTracingEvents(tracer);
        }
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "transactionalengine.step-logging.enabled", havingValue = "true", matchIfMissing = true)
    public StepLoggingAspect stepLoggingAspect() {
        return new StepLoggingAspect();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
