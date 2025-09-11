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


package com.firefly.transactional.config;

import com.firefly.transactional.aop.StepLoggingAspect;
import com.firefly.transactional.core.SagaContextFactory;
import com.firefly.transactional.engine.SagaEngine;
import com.firefly.transactional.events.NoOpStepEventPublisher;
import com.firefly.transactional.events.StepEventPublisher;
import com.firefly.transactional.observability.*;
import com.firefly.transactional.persistence.SagaPersistenceProvider;
import com.firefly.transactional.registry.SagaRegistry;
import com.firefly.transactional.validation.SagaValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring configuration that wires the Transactional Engine components.
 * Users typically activate it via {@link com.firefly.transactional.annotations.EnableTransactionalEngine}.
 */
@Configuration
@EnableAspectJAutoProxy
@EnableConfigurationProperties(SagaEngineProperties.class)
public class TransactionalEngineConfiguration {

    @Bean
    public SagaRegistry sagaRegistry(ApplicationContext applicationContext) {
        return new SagaRegistry(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public SagaContextFactory sagaContextFactory(SagaEngineProperties properties) {
        return new SagaContextFactory(
            properties.getContext().isOptimizationEnabled(),
            properties.getContext().getExecutionMode()
        );
    }

    @Bean
    public SagaEngine sagaEngine(SagaRegistry registry,
                                SagaEvents events,
                                StepEventPublisher publisher,
                                SagaEngineProperties properties,
                                SagaPersistenceProvider persistenceProvider,
                                ObjectProvider<SagaValidationService> validationService) {
        return new SagaEngine(
            registry,
            events,
            properties.getCompensationPolicy(),
            publisher,
            properties.isAutoOptimizationEnabled(),
            validationService.getIfAvailable(),
            persistenceProvider,
            properties.getPersistence().isEnabled()
        );
    }

    @Bean
    public SagaLoggerEvents sagaLoggerEvents() {
        return new SagaLoggerEvents();
    }

    @Bean
    @ConditionalOnMissingBean(StepEventPublisher.class)
    public StepEventPublisher stepEventPublisher() {
        Logger log = LoggerFactory.getLogger(TransactionalEngineConfiguration.class);
        log.info("No custom StepEventPublisher found. Using NoOpStepEventPublisher - events will be discarded");
        return new NoOpStepEventPublisher();
    }
    
    @Bean
    public StepEventPublisherDetector stepEventPublisherDetector(ApplicationContext context) {
        Logger log = LoggerFactory.getLogger(TransactionalEngineConfiguration.class);
        
        // Get all StepEventPublisher beans (excluding NoOp)
        Map<String, StepEventPublisher> publishers = context.getBeansOfType(StepEventPublisher.class);
        List<String> customPublishers = publishers.entrySet().stream()
            .filter(entry -> !(entry.getValue() instanceof NoOpStepEventPublisher))
            .map(entry -> entry.getKey() + " (" + entry.getValue().getClass().getName() + ")")
            .collect(java.util.stream.Collectors.toList());
        
        if (customPublishers.isEmpty()) {
            log.info("Using default NoOpStepEventPublisher - events will be discarded");
        } else if (customPublishers.size() == 1) {
            log.info("Custom StepEventPublisher active: {}", customPublishers.get(0));
        } else {
            log.error("Multiple StepEventPublisher implementations found: {}. Only one should be defined. Spring will use @Primary or the first one found.", customPublishers);
            log.warn("To resolve this, either: 1) Remove extra implementations, 2) Mark one with @Primary, or 3) Use @Qualifier to specify which one to use");
        }
        
        return new StepEventPublisherDetector();
    }
    
    static class StepEventPublisherDetector {
        // Marker class for detection logging
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
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "transactional.step-logging.enabled", havingValue = "true", matchIfMissing = true)
    public StepLoggingAspect stepLoggingAspect() {
        return new StepLoggingAspect();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
