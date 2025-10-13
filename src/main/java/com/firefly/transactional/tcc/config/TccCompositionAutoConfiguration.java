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

package com.firefly.transactional.tcc.config;

import com.firefly.transactional.tcc.composition.*;
import com.firefly.transactional.tcc.engine.TccEngine;
import com.firefly.transactional.tcc.registry.TccRegistry;
import com.firefly.transactional.tcc.observability.TccEvents;
import com.firefly.transactional.shared.config.TccSpecificProperties;
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
 * Auto-configuration for TCC Composition functionality.
 * <p>
 * This configuration automatically sets up the TccCompositor and related components
 * when the necessary dependencies are available. It provides sensible defaults while
 * allowing for customization through configuration properties.
 * <p>
 * Features configured:
 * - TccCompositor bean with automatic dependency injection
 * - Composition template registry for common TCC patterns
 * - Metrics collection for composition performance monitoring
 * - Health indicators for composition system health
 * - Integration with existing TCC engine infrastructure
 * - Visualization services for development and debugging
 */
@AutoConfiguration
@ConditionalOnClass({TccCompositor.class, TccEngine.class})
@ConditionalOnBean({TccEngine.class, TccRegistry.class, TccEvents.class})
@EnableConfigurationProperties({TccSpecificProperties.class, TccCompositionProperties.class})
public class TccCompositionAutoConfiguration {
    
    private static final Logger log = LoggerFactory.getLogger(TccCompositionAutoConfiguration.class);
    
    /**
     * Creates the main TccCompositor bean.
     * <p>
     * This is the primary entry point for TCC composition functionality.
     * It automatically wires with the existing TCC engine infrastructure.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "firefly.tx.tcc.composition.enabled", havingValue = "true", matchIfMissing = true)
    public TccCompositor tccCompositor(TccEngine tccEngine, 
                                      TccRegistry tccRegistry,
                                      TccEvents tccEvents) {
        log.info("Configuring TccCompositor with auto-configuration");
        return new TccCompositor(tccEngine, tccRegistry, tccEvents);
    }
    
    /**
     * Creates a template registry for common TCC composition patterns.
     * <p>
     * Provides pre-built templates for typical business workflows like
     * distributed transactions, payment flows, and resource reservations.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "firefly.tx.tcc.composition.templates.enabled", havingValue = "true", matchIfMissing = true)
    public TccCompositionTemplateRegistry tccCompositionTemplateRegistry(TccCompositionProperties properties) {
        log.info("Configuring TCC composition template registry");
        return new TccCompositionTemplateRegistry(properties.getTemplates());
    }
    
    /**
     * Metrics collection configuration.
     * <p>
     * Provides detailed metrics about TCC composition execution, performance,
     * and success rates for monitoring and optimization.
     */
    @Configuration
    @ConditionalOnProperty(name = "firefly.tx.tcc.composition.metrics.enabled", havingValue = "true", matchIfMissing = true)
    static class TccCompositionMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public com.firefly.transactional.shared.observability.TransactionalMetricsCollector tccCompositionMetricsCollector() {
            log.info("Configuring shared transactional metrics collector for TCC composition");
            return new com.firefly.transactional.shared.observability.TransactionalMetricsCollector();
        }
    }
    
    /**
     * Health check configuration.
     * <p>
     * Provides health indicators for the TCC composition system to integrate
     * with Spring Boot Actuator health endpoints.
     */
    @Configuration
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnProperty(name = "firefly.tx.tcc.composition.health.enabled", havingValue = "true", matchIfMissing = true)
    static class TccCompositionHealthConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnEnabledHealthIndicator("tccComposition")
        public com.firefly.transactional.shared.observability.TransactionalHealthIndicator tccCompositionHealthIndicator(
                com.firefly.transactional.shared.observability.TransactionalMetricsCollector metricsCollector) {
            log.info("Configuring shared transactional health indicator for TCC composition");
            var healthProperties = new com.firefly.transactional.shared.observability.TransactionalHealthIndicator.TransactionalHealthProperties();
            return new com.firefly.transactional.shared.observability.TransactionalHealthIndicator(metricsCollector, healthProperties);
        }
    }
    
    /**
     * Development and debugging tools configuration.
     * <p>
     * Provides additional tools for development and debugging when
     * running in development mode or when explicitly enabled.
     */
    @Configuration
    @ConditionalOnProperty(name = "firefly.tx.tcc.composition.dev-tools.enabled", havingValue = "true")
    static class TccCompositionDevToolsConfiguration {
        
        @Bean
        @ConditionalOnMissingBean
        public TccCompositionVisualizationService tccCompositionVisualizationService(
                TccCompositor tccCompositor,
                TccCompositionProperties properties) {
            log.info("Configuring TCC composition visualization service for development");
            return new TccCompositionVisualizationService(tccCompositor, properties.getDevTools());
        }
    }
}
