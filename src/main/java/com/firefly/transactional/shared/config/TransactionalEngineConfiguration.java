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


package com.firefly.transactional.shared.config;

import com.firefly.transactional.saga.core.SagaContextFactory;
import com.firefly.transactional.saga.engine.SagaEngine;
import com.firefly.transactional.saga.registry.SagaRegistry;
import com.firefly.transactional.saga.composition.SagaCompositor;
import com.firefly.transactional.saga.aop.StepLoggingAspect;
import com.firefly.transactional.saga.events.NoOpStepEventPublisher;
import com.firefly.transactional.saga.events.StepEventPublisher;
import com.firefly.transactional.saga.observability.*;
import com.firefly.transactional.shared.observability.*;
import com.firefly.transactional.saga.persistence.SagaPersistenceProvider;
import com.firefly.transactional.saga.validation.SagaValidationService;
import com.firefly.transactional.tcc.engine.TccEngine;
import com.firefly.transactional.tcc.events.NoOpTccEventPublisher;
import com.firefly.transactional.tcc.events.TccEventPublisher;
import com.firefly.transactional.tcc.observability.*;
import com.firefly.transactional.tcc.persistence.TccPersistenceProvider;
import com.firefly.transactional.tcc.persistence.TccRecoveryService;
import com.firefly.transactional.tcc.persistence.impl.DefaultTccRecoveryService;
import com.firefly.transactional.tcc.persistence.impl.InMemoryTccPersistenceProvider;
import com.firefly.transactional.saga.config.SagaSpecificProperties;
import com.firefly.transactional.saga.config.SagaEngineProperties;
import com.firefly.transactional.tcc.registry.TccRegistry;
import com.firefly.transactional.tcc.composition.TccCompositor;
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
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring configuration that wires the Transactional Engine components.
 * Users typically activate it via {@link com.firefly.transactional.shared.annotations.EnableTransactionalEngine}.
 *
 * <p>
 * This configuration now uses the new generic property structure under {@code firefly.tx.*}
 * while maintaining backward compatibility with legacy {@code firefly.saga.engine.*} properties.
 */
@Configuration
@EnableAspectJAutoProxy
@EnableConfigurationProperties({
    TransactionalEngineProperties.class,
    SagaSpecificProperties.class,
    TccSpecificProperties.class,
    SagaEngineProperties.class  // Keep for backward compatibility
})
public class TransactionalEngineConfiguration {

    @Bean
    public TransactionalEnginePropertiesCompatibility propertiesCompatibility(Environment environment) {
        return new TransactionalEnginePropertiesCompatibility(environment);
    }

    @Bean
    @Primary
    public TransactionalEngineProperties transactionalEngineProperties(
            TransactionalEnginePropertiesCompatibility compatibility) {
        return compatibility.createMergedProperties();
    }

    @Bean
    @Primary
    public SagaSpecificProperties sagaSpecificProperties(
            TransactionalEnginePropertiesCompatibility compatibility) {
        return compatibility.createMergedSagaProperties();
    }

    @Bean
    @Primary
    public TccSpecificProperties tccSpecificProperties(
            TransactionalEnginePropertiesCompatibility compatibility) {
        return compatibility.createTccProperties();
    }

    @Bean
    public SagaRegistry sagaRegistry(ApplicationContext applicationContext) {
        return new SagaRegistry(applicationContext);
    }

    @Bean
    public TccRegistry tccRegistry(ApplicationContext applicationContext) {
        return new TccRegistry(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public SagaContextFactory sagaContextFactory(SagaSpecificProperties sagaProperties) {
        return new SagaContextFactory(
            sagaProperties.getContext().isOptimizationEnabled(),
            sagaProperties.getContext().getExecutionMode()
        );
    }

    @Bean
    public SagaEngine sagaEngine(SagaRegistry registry,
                                SagaEvents events,
                                StepEventPublisher publisher,
                                SagaSpecificProperties sagaProperties,
                                TransactionalEngineProperties txProperties,
                                SagaPersistenceProvider persistenceProvider,
                                ObjectProvider<SagaValidationService> validationService) {
        return new SagaEngine(
            registry,
            events,
            sagaProperties.getCompensationPolicy(),
            publisher,
            sagaProperties.isAutoOptimizationEnabled(),
            validationService.getIfAvailable(),
            persistenceProvider,
            txProperties.getPersistence().isEnabled()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public TccEvents tccEvents(LoggingTransactionalObserver loggingObserver) {
        return new TccToGenericObserverAdapter(loggingObserver);
    }

    @Bean
    @ConditionalOnMissingBean
    public TccEventPublisher tccEventPublisher() {
        Logger log = LoggerFactory.getLogger(TransactionalEngineConfiguration.class);
        log.info("No custom TccEventPublisher found. Using NoOpTccEventPublisher - events will be discarded");
        return new NoOpTccEventPublisher();
    }

    @Bean
    @ConditionalOnMissingBean
    public TccPersistenceProvider tccPersistenceProvider() {
        return new InMemoryTccPersistenceProvider();
    }

    @Bean
    public TccEngine tccEngine(TccRegistry registry,
                              TccEvents tccEvents,
                              TccEventPublisher tccEventPublisher,
                              TransactionalEngineProperties txProperties,
                              TccPersistenceProvider tccPersistenceProvider) {
        Logger log = LoggerFactory.getLogger(TransactionalEngineConfiguration.class);
        log.info("Initializing TCC Engine with persistence: {}", txProperties.getPersistence().isEnabled());

        return new TccEngine(
            registry,
            tccEvents,
            tccPersistenceProvider,
            txProperties.getPersistence().isEnabled(),
            tccEventPublisher
        );
    }

    @Bean
    public LoggingTransactionalObserver loggingTransactionalObserver() {
        return new LoggingTransactionalObserver();
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
    public SagaEvents sagaEventsComposite(ApplicationContext applicationContext,
                                          LoggingTransactionalObserver logger,
                                          ObjectProvider<MicrometerTransactionalObserver> micrometer,
                                          ObjectProvider<GenericTransactionalObserver> tracing) {
        List<SagaEvents> sinks = new ArrayList<>();

        // Add all other SagaEvents beans first (including test beans)
        // Exclude the composite itself to avoid circular dependency
        Map<String, SagaEvents> allEvents = applicationContext.getBeansOfType(SagaEvents.class);
        for (Map.Entry<String, SagaEvents> entry : allEvents.entrySet()) {
            if (!"sagaEventsComposite".equals(entry.getKey())) {
                sinks.add(entry.getValue());
            }
        }

        // Add logging adapter
        sinks.add(new SagaToGenericObserverAdapter(logger));

        // Add micrometer adapter if available
        MicrometerTransactionalObserver m = micrometer.getIfAvailable();
        if (m != null) {
            sinks.add(new SagaToGenericObserverAdapter(m));
        }

        // Add tracing adapter if available
        GenericTransactionalObserver t = tracing.getIfAvailable();
        if (t != null) {
            sinks.add(new SagaToGenericObserverAdapter(t));
        }

        return new CompositeSagaEvents(sinks);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(TccEvents.class)
    public TccEvents tccEventsComposite(LoggingTransactionalObserver logger,
                                        ObjectProvider<MicrometerTransactionalObserver> micrometer,
                                        ObjectProvider<GenericTransactionalObserver> tracing) {
        List<TccEvents> sinks = new ArrayList<>();

        // Add logging adapter
        sinks.add(new TccToGenericObserverAdapter(logger));

        // Add micrometer adapter if available
        MicrometerTransactionalObserver m = micrometer.getIfAvailable();
        if (m != null) {
            sinks.add(new TccToGenericObserverAdapter(m));
        }

        // Add tracing adapter if available
        GenericTransactionalObserver t = tracing.getIfAvailable();
        if (t != null) {
            sinks.add(new TccToGenericObserverAdapter(t));
        }

        return new CompositeTccEvents(sinks);
    }

    @Configuration
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnBean(type = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerAutoConfig {
        @Bean
        public MicrometerTransactionalObserver micrometerTransactionalObserver(
                io.micrometer.core.instrument.MeterRegistry registry,
                TransactionalMetricsCollector metricsCollector) {
            return new MicrometerTransactionalObserver(registry, metricsCollector);
        }
    }

    @Configuration
    @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
    @ConditionalOnBean(type = "io.micrometer.tracing.Tracer")
    static class TracingAutoConfig {
        @Bean
        public GenericTransactionalObserver tracingTransactionalObserver(io.micrometer.tracing.Tracer tracer) {
            // Create a tracing observer implementation
            return new TracingTransactionalObserver(tracer);
        }
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "transactional.step-logging.enabled", havingValue = "true", matchIfMissing = true)
    public StepLoggingAspect stepLoggingAspect() {
        return new StepLoggingAspect();
    }

    @Bean
    @ConditionalOnMissingBean
    public SagaCompositor sagaCompositor(SagaEngine sagaEngine, SagaRegistry sagaRegistry, SagaEvents sagaEvents) {
        Logger log = LoggerFactory.getLogger(TransactionalEngineConfiguration.class);
        log.info("Initializing SAGA Compositor for orchestrating multiple sagas");
        return new SagaCompositor(sagaEngine, sagaRegistry, sagaEvents);
    }

    @Bean
    @ConditionalOnMissingBean
    public TccCompositor tccCompositor(TccEngine tccEngine, TccRegistry tccRegistry, TccEvents tccEvents) {
        Logger log = LoggerFactory.getLogger(TransactionalEngineConfiguration.class);
        log.info("Initializing TCC Compositor for orchestrating multiple TCC coordinators");
        return new TccCompositor(tccEngine, tccRegistry, tccEvents);
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
