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

import com.firefly.transactional.shared.annotations.EnableTransactionalEngine;
import com.firefly.transactional.shared.config.TransactionalEngineProperties;
import com.firefly.transactional.saga.config.SagaSpecificProperties;
import com.firefly.transactional.saga.engine.SagaEngine;
import com.firefly.transactional.tcc.engine.TccEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test to verify that legacy properties still work and are properly
 * mapped to the new generic properties structure.
 */
@SpringBootTest(
    properties = {
        "spring.cloud.config.enabled=false",
        "spring.cloud.config.import-check.enabled=false"
    }
)
@TestPropertySource(properties = {
    // Legacy SAGA engine properties
    "firefly.saga.engine.default-timeout=PT12M",
    "firefly.saga.engine.max-concurrent-sagas=250",
    "firefly.saga.engine.compensation-policy=STRICT_SEQUENTIAL",
    "firefly.saga.engine.auto-optimization-enabled=true",
    "firefly.saga.engine.observability.metrics-enabled=true",
    "firefly.saga.engine.validation.enabled=true",
    
    // Legacy persistence properties
    "firefly.saga.engine.persistence.enabled=false",
    "firefly.saga.engine.persistence.provider=in-memory",
    "firefly.saga.engine.persistence.redis.host=legacy-host",
    "firefly.saga.engine.persistence.redis.port=6379",
    "firefly.saga.engine.persistence.redis.key-prefix=legacy:saga:"
})
class BackwardCompatibilityIntegrationTest {

    @Configuration
    @EnableTransactionalEngine
    static class TestConfig {
    }

    @Autowired
    private TransactionalEngineProperties txProperties;

    @Autowired
    private SagaSpecificProperties sagaProperties;

    @Autowired
    private SagaEngine sagaEngine;

    @Autowired
    private TccEngine tccEngine;

    @Test
    void shouldMapLegacyPropertiesToGenericProperties() {
        assertThat(txProperties).isNotNull();
        
        // Legacy properties should be mapped to generic properties
        assertThat(txProperties.getDefaultTimeout()).isEqualTo(Duration.ofMinutes(12));
        assertThat(txProperties.getMaxConcurrentTransactions()).isEqualTo(250);
        assertThat(txProperties.getObservability().isMetricsEnabled()).isTrue();
        assertThat(txProperties.getValidation().isEnabled()).isTrue();
        assertThat(txProperties.getPersistence().isEnabled()).isFalse();
        assertThat(txProperties.getPersistence().getProvider()).isEqualTo("in-memory");
        assertThat(txProperties.getPersistence().getRedis().getHost()).isEqualTo("legacy-host");
        assertThat(txProperties.getPersistence().getRedis().getPort()).isEqualTo(6379);
        assertThat(txProperties.getPersistence().getRedis().getKeyPrefix()).isEqualTo("legacy:saga:");
    }

    @Test
    void shouldMapLegacyPropertiesToSagaSpecificProperties() {
        assertThat(sagaProperties).isNotNull();
        
        // Legacy SAGA properties should be mapped to SAGA-specific properties
        assertThat(sagaProperties.getCompensationPolicy()).isEqualTo(SagaEngine.CompensationPolicy.STRICT_SEQUENTIAL);
        assertThat(sagaProperties.isAutoOptimizationEnabled()).isTrue();
        assertThat(sagaProperties.getMaxConcurrentSagas()).isEqualTo(250);
    }

    @Test
    void shouldCreateEnginesWithLegacyProperties() {
        assertThat(sagaEngine).isNotNull();
        assertThat(tccEngine).isNotNull();
        
        // Both engines should be created successfully using the mapped properties
        // We can't directly test internal state, but we can verify they were created
    }
}
