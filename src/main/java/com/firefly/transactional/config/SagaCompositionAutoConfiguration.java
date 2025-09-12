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

import com.firefly.transactional.composition.SagaCompositor;
import com.firefly.transactional.composition.CompositionTemplateRegistry;
import com.firefly.transactional.composition.CompositionMetricsCollector;
import com.firefly.transactional.composition.CompositionHealthIndicator;
import com.firefly.transactional.composition.CompositionVisualizationService;
import com.firefly.transactional.engine.SagaEngine;
import com.firefly.transactional.observability.SagaEvents;
import com.firefly.transactional.registry.SagaRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for Saga Composition functionality.
 * <p>
 * This configuration automatically sets up the SagaCompositor and related components
 * when the necessary dependencies are available. It provides sensible defaults while
 * allowing for customization through configuration properties.
 * <p>
 * Features configured:
 * - SagaCompositor bean with automatic dependency injection
 * - Composition template registry for common patterns
 * - Metrics collection for composition performance monitoring
 * - Health indicators for composition system health
 * - Integration with existing saga engine infrastructure
 */
@AutoConfiguration
@ConditionalOnClass({SagaCompositor.class, SagaEngine.class})
@ConditionalOnBean({SagaEngine.class, SagaRegistry.class, SagaEvents.class})
@EnableConfigurationProperties({SagaEngineProperties.class, SagaCompositionProperties.class})
public class SagaCompositionAutoConfiguration {
    
    private static final Logger log = LoggerFactory.getLogger(SagaCompositionAutoConfiguration.class);
    
    /**
     * Creates the main SagaCompositor bean.
     * <p>
     * This is the primary entry point for saga composition functionality.
     * It automatically wires with the existing saga engine infrastructure.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "firefly.saga.composition.enabled", havingValue = "true", matchIfMissing = true)
    public SagaCompositor sagaCompositor(SagaEngine sagaEngine, 
                                        SagaRegistry sagaRegistry,
                                        SagaEvents sagaEvents) {
        log.info("Configuring SagaCompositor with auto-configuration");
        return new SagaCompositor(sagaEngine, sagaRegistry, sagaEvents);
    }
    
    /**
     * Creates a template registry for common composition patterns.
     * <p>
     * Provides pre-built templates for typical business workflows like
     * order processing, payment flows, and data pipelines.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "firefly.saga.composition.templates.enabled", havingValue = "true", matchIfMissing = true)
    public CompositionTemplateRegistry compositionTemplateRegistry(SagaCompositionProperties properties) {
        log.info("Configuring composition template registry");
        return new CompositionTemplateRegistry(properties.getTemplates());
    }
    
    /**
     * Metrics collection configuration.
     * <p>
     * Provides detailed metrics about composition execution, performance,
     * and success rates for monitoring and optimization.
     */
    @Configuration
    @ConditionalOnProperty(name = "firefly.saga.composition.metrics.enabled", havingValue = "true", matchIfMissing = true)
    static class CompositionMetricsConfiguration {
        
        @Bean
        @ConditionalOnMissingBean
        public CompositionMetricsCollector compositionMetricsCollector(SagaCompositionProperties properties) {
            log.info("Configuring composition metrics collector");
            return new CompositionMetricsCollector(properties.getMetrics());
        }
    }
    
    /**
     * Health check configuration.
     * <p>
     * Provides health indicators for the composition system to integrate
     * with Spring Boot Actuator health endpoints.
     */
    @Configuration
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnProperty(name = "firefly.saga.composition.health.enabled", havingValue = "true", matchIfMissing = true)
    static class CompositionHealthConfiguration {
        
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnEnabledHealthIndicator("sagaComposition")
        public CompositionHealthIndicator compositionHealthIndicator(
                SagaCompositor sagaCompositor,
                CompositionMetricsCollector metricsCollector,
                SagaCompositionProperties properties) {
            log.info("Configuring composition health indicator");
            return new CompositionHealthIndicator(sagaCompositor, metricsCollector, properties.getHealth());
        }
    }
    
    /**
     * Development and debugging tools configuration.
     * <p>
     * Provides additional tools for development and debugging when
     * running in development mode or when explicitly enabled.
     */
    @Configuration
    @ConditionalOnProperty(name = "firefly.saga.composition.dev-tools.enabled", havingValue = "true")
    static class CompositionDevToolsConfiguration {
        
        @Bean
        @ConditionalOnMissingBean
        public CompositionVisualizationService compositionVisualizationService(
                SagaCompositor sagaCompositor,
                SagaCompositionProperties properties) {
            log.info("Configuring composition visualization service for development");
            return new CompositionVisualizationService(sagaCompositor, properties.getDevTools());
        }
    }
}
