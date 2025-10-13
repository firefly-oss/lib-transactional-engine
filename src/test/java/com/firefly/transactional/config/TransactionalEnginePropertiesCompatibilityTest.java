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

import com.firefly.transactional.shared.config.TransactionalEngineProperties;
import com.firefly.transactional.shared.config.TransactionalEnginePropertiesCompatibility;
import com.firefly.transactional.saga.config.SagaSpecificProperties;
import com.firefly.transactional.shared.config.TccSpecificProperties;
import com.firefly.transactional.saga.engine.SagaEngine;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for backward compatibility of property mappings.
 * This test does not require Spring Boot context as it tests the compatibility layer directly.
 */
class TransactionalEnginePropertiesCompatibilityTest {

    @Test
    void shouldMapLegacyGenericPropertiesToNewStructure() {
        MockEnvironment environment = new MockEnvironment();
        
        // Set legacy properties
        environment.setProperty("firefly.saga.engine.default-timeout", "PT10M");
        environment.setProperty("firefly.saga.engine.max-concurrent-sagas", "200");
        environment.setProperty("firefly.saga.engine.observability.metrics-enabled", "false");
        environment.setProperty("firefly.saga.engine.validation.enabled", "false");
        
        TransactionalEnginePropertiesCompatibility compatibility = 
            new TransactionalEnginePropertiesCompatibility(environment);
        
        TransactionalEngineProperties properties = compatibility.createMergedProperties();
        
        assertThat(properties.getDefaultTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.getMaxConcurrentTransactions()).isEqualTo(200);
        assertThat(properties.getObservability().isMetricsEnabled()).isFalse();
        assertThat(properties.getValidation().isEnabled()).isFalse();
    }

    @Test
    void shouldMapLegacyPersistencePropertiesToNewStructure() {
        MockEnvironment environment = new MockEnvironment();
        
        // Set legacy persistence properties
        environment.setProperty("firefly.saga.engine.persistence.enabled", "true");
        environment.setProperty("firefly.saga.engine.persistence.provider", "redis");
        environment.setProperty("firefly.saga.engine.persistence.redis.host", "test-host");
        environment.setProperty("firefly.saga.engine.persistence.redis.port", "6380");
        environment.setProperty("firefly.saga.engine.persistence.redis.database", "5");
        environment.setProperty("firefly.saga.engine.persistence.redis.key-prefix", "legacy:saga:");
        
        TransactionalEnginePropertiesCompatibility compatibility = 
            new TransactionalEnginePropertiesCompatibility(environment);
        
        TransactionalEngineProperties properties = compatibility.createMergedProperties();
        
        assertThat(properties.getPersistence().isEnabled()).isTrue();
        assertThat(properties.getPersistence().getProvider()).isEqualTo("redis");
        assertThat(properties.getPersistence().getRedis().getHost()).isEqualTo("test-host");
        assertThat(properties.getPersistence().getRedis().getPort()).isEqualTo(6380);
        assertThat(properties.getPersistence().getRedis().getDatabase()).isEqualTo(5);
        assertThat(properties.getPersistence().getRedis().getKeyPrefix()).isEqualTo("legacy:saga:");
    }

    @Test
    void shouldMapAlternateLegacyPersistencePropertiesToNewStructure() {
        MockEnvironment environment = new MockEnvironment();
        
        // Set alternate legacy persistence properties (firefly.saga.persistence.*)
        environment.setProperty("firefly.saga.persistence.enabled", "true");
        environment.setProperty("firefly.saga.persistence.provider", "redis");
        environment.setProperty("firefly.saga.persistence.redis.host", "alt-host");
        environment.setProperty("firefly.saga.persistence.redis.port", "6381");
        environment.setProperty("firefly.saga.persistence.redis.key-prefix", "alt:saga:");
        
        TransactionalEnginePropertiesCompatibility compatibility = 
            new TransactionalEnginePropertiesCompatibility(environment);
        
        TransactionalEngineProperties properties = compatibility.createMergedProperties();
        
        assertThat(properties.getPersistence().isEnabled()).isTrue();
        assertThat(properties.getPersistence().getProvider()).isEqualTo("redis");
        assertThat(properties.getPersistence().getRedis().getHost()).isEqualTo("alt-host");
        assertThat(properties.getPersistence().getRedis().getPort()).isEqualTo(6381);
        assertThat(properties.getPersistence().getRedis().getKeyPrefix()).isEqualTo("alt:saga:");
    }

    @Test
    void shouldMapLegacySagaPropertiesToNewStructure() {
        MockEnvironment environment = new MockEnvironment();
        
        // Set legacy SAGA properties
        environment.setProperty("firefly.saga.engine.compensation-policy", "BEST_EFFORT_PARALLEL");
        environment.setProperty("firefly.saga.engine.auto-optimization-enabled", "false");
        environment.setProperty("firefly.saga.engine.max-concurrent-sagas", "75");
        
        TransactionalEnginePropertiesCompatibility compatibility = 
            new TransactionalEnginePropertiesCompatibility(environment);
        
        SagaSpecificProperties properties = compatibility.createMergedSagaProperties();
        
        assertThat(properties.getCompensationPolicy()).isEqualTo(SagaEngine.CompensationPolicy.BEST_EFFORT_PARALLEL);
        assertThat(properties.isAutoOptimizationEnabled()).isFalse();
        assertThat(properties.getMaxConcurrentSagas()).isEqualTo(75);
    }

    @Test
    void shouldPreferNewPropertiesOverLegacyProperties() {
        MockEnvironment environment = new MockEnvironment();
        
        // Set both new and legacy properties - new should take precedence
        environment.setProperty("firefly.tx.default-timeout", "PT15M");
        environment.setProperty("firefly.saga.engine.default-timeout", "PT10M");
        
        environment.setProperty("firefly.tx.persistence.enabled", "false");
        environment.setProperty("firefly.saga.engine.persistence.enabled", "true");
        
        TransactionalEnginePropertiesCompatibility compatibility = 
            new TransactionalEnginePropertiesCompatibility(environment);
        
        TransactionalEngineProperties properties = compatibility.createMergedProperties();
        
        // New properties should take precedence
        assertThat(properties.getDefaultTimeout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.getPersistence().isEnabled()).isFalse();
    }

    @Test
    void shouldCreateTccPropertiesWithoutLegacyMapping() {
        MockEnvironment environment = new MockEnvironment();
        
        // Set TCC properties (no legacy mapping needed)
        environment.setProperty("firefly.tx.tcc.default-timeout", "PT45S");
        environment.setProperty("firefly.tx.tcc.retry-enabled", "false");
        environment.setProperty("firefly.tx.tcc.max-retries", "5");
        environment.setProperty("firefly.tx.tcc.backoff-ms", "2000");
        
        TransactionalEnginePropertiesCompatibility compatibility = 
            new TransactionalEnginePropertiesCompatibility(environment);
        
        TccSpecificProperties properties = compatibility.createTccProperties();
        
        assertThat(properties.getDefaultTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.isRetryEnabled()).isFalse();
        assertThat(properties.getMaxRetries()).isEqualTo(5);
        assertThat(properties.getBackoffMs()).isEqualTo(2000);
    }

    @Test
    void shouldHandleEmptyEnvironmentGracefully() {
        MockEnvironment environment = new MockEnvironment();
        
        TransactionalEnginePropertiesCompatibility compatibility = 
            new TransactionalEnginePropertiesCompatibility(environment);
        
        TransactionalEngineProperties txProperties = compatibility.createMergedProperties();
        SagaSpecificProperties sagaProperties = compatibility.createMergedSagaProperties();
        TccSpecificProperties tccProperties = compatibility.createTccProperties();
        
        // Should use default values
        assertThat(txProperties.getDefaultTimeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(txProperties.getMaxConcurrentTransactions()).isEqualTo(100);
        assertThat(txProperties.getPersistence().isEnabled()).isFalse();
        
        assertThat(sagaProperties.getCompensationPolicy()).isEqualTo(SagaEngine.CompensationPolicy.STRICT_SEQUENTIAL);
        assertThat(sagaProperties.isAutoOptimizationEnabled()).isTrue();
        
        assertThat(tccProperties.getDefaultTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(tccProperties.isRetryEnabled()).isTrue();
    }
}
