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
import com.firefly.transactional.shared.config.TccSpecificProperties;
import com.firefly.transactional.saga.engine.SagaEngine;
import com.firefly.transactional.tcc.engine.TccEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test to verify that the new generic properties work end-to-end
 * with the Spring Boot auto-configuration.
 */
@SpringBootTest(
    properties = {
        "spring.cloud.config.enabled=false",
        "spring.cloud.config.import-check.enabled=false"
    }
)
@TestPropertySource(properties = {
    // Generic properties
    "firefly.tx.default-timeout=PT8M",
    "firefly.tx.max-concurrent-transactions=150",
    "firefly.tx.observability.metrics-enabled=false",
    "firefly.tx.observability.tracing-enabled=false",
    "firefly.tx.validation.enabled=false",
    "firefly.tx.persistence.enabled=false",
    
    // SAGA-specific properties
    "firefly.tx.saga.compensation-policy=BEST_EFFORT_PARALLEL",
    "firefly.tx.saga.auto-optimization-enabled=false",
    "firefly.tx.saga.max-concurrent-sagas=80",
    
    // TCC-specific properties
    "firefly.tx.tcc.default-timeout=PT45S",
    "firefly.tx.tcc.retry-enabled=false",
    "firefly.tx.tcc.max-retries=5",
    "firefly.tx.tcc.backoff-ms=2000"
})
class GenericPropertiesIntegrationTest {

    @Configuration
    @EnableTransactionalEngine
    static class TestConfig {
    }

    @Autowired
    private TransactionalEngineProperties txProperties;

    @Autowired
    private SagaSpecificProperties sagaProperties;

    @Autowired
    private TccSpecificProperties tccProperties;

    @Autowired
    private SagaEngine sagaEngine;

    @Autowired
    private TccEngine tccEngine;

    @Test
    void shouldLoadGenericPropertiesCorrectly() {
        assertThat(txProperties).isNotNull();
        assertThat(txProperties.getDefaultTimeout()).isEqualTo(Duration.ofMinutes(8));
        assertThat(txProperties.getMaxConcurrentTransactions()).isEqualTo(150);
        assertThat(txProperties.getObservability().isMetricsEnabled()).isFalse();
        assertThat(txProperties.getObservability().isTracingEnabled()).isFalse();
        assertThat(txProperties.getValidation().isEnabled()).isFalse();
        assertThat(txProperties.getPersistence().isEnabled()).isFalse();
    }

    @Test
    void shouldLoadSagaSpecificPropertiesCorrectly() {
        assertThat(sagaProperties).isNotNull();
        assertThat(sagaProperties.getCompensationPolicy()).isEqualTo(SagaEngine.CompensationPolicy.BEST_EFFORT_PARALLEL);
        assertThat(sagaProperties.isAutoOptimizationEnabled()).isFalse();
        assertThat(sagaProperties.getMaxConcurrentSagas()).isEqualTo(80);
    }

    @Test
    void shouldLoadTccSpecificPropertiesCorrectly() {
        assertThat(tccProperties).isNotNull();
        assertThat(tccProperties.getDefaultTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(tccProperties.isRetryEnabled()).isFalse();
        assertThat(tccProperties.getMaxRetries()).isEqualTo(5);
        assertThat(tccProperties.getBackoffMs()).isEqualTo(2000);
    }

    @Test
    void shouldCreateSagaEngineWithNewProperties() {
        assertThat(sagaEngine).isNotNull();
        // The SagaEngine should be configured with the new properties
        // We can't directly test internal state, but we can verify it was created
    }

    @Test
    void shouldCreateTccEngineWithNewProperties() {
        assertThat(tccEngine).isNotNull();
        // The TccEngine should be configured with the new properties
        // We can't directly test internal state, but we can verify it was created
    }
}
