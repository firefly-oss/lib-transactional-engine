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

package com.firefly.transactional.persistence;

import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.shared.core.StepStatus;
import com.firefly.transactional.saga.persistence.impl.InMemorySagaPersistenceProvider;
import com.firefly.transactional.saga.persistence.serialization.SerializableSagaContext;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic integration test for persistence functionality.
 */
class BasicPersistenceIntegrationTest {

    @Test
    void testInMemoryPersistenceProvider() {
        InMemorySagaPersistenceProvider provider = new InMemorySagaPersistenceProvider();

        // Test that provider is healthy
        StepVerifier.create(provider.isHealthy())
                .expectNext(true)
                .verifyComplete();

        // Test that no sagas exist initially
        assertThat(provider.getActiveSagaCount()).isEqualTo(0);
    }

    @Test
    void testSerializableSagaContext() {
        // Create a test saga context
        SagaContext original = new SagaContext("test-correlation-id", "test-saga");
        original.putVariable("testVar", "testValue");
        original.putHeader("testHeader", "headerValue");
        original.putResult("step1", "result1");
        original.setStatus("step1", StepStatus.DONE);
        original.markStepStarted("step1", Instant.now());

        // Convert to serializable
        SerializableSagaContext serializable = SerializableSagaContext.fromSagaContext(original);

        // Verify serializable context
        assertThat(serializable.getCorrelationId()).isEqualTo("test-correlation-id");
        assertThat(serializable.getSagaName()).isEqualTo("test-saga");
        assertThat(serializable.getVariables().get("testVar")).isEqualTo("testValue");
        assertThat(serializable.getHeaders().get("testHeader")).isEqualTo("headerValue");
        assertThat(serializable.getStepResults().get("step1")).isEqualTo("result1");
        assertThat(serializable.getStepStatuses().get("step1")).isEqualTo(StepStatus.DONE);

        // Convert back to SagaContext
        SagaContext reconstructed = serializable.toSagaContext();

        // Verify reconstructed context
        assertThat(reconstructed.correlationId()).isEqualTo("test-correlation-id");
        assertThat(reconstructed.sagaName()).isEqualTo("test-saga");
        assertThat(reconstructed.getVariable("testVar")).isEqualTo("testValue");
        assertThat(reconstructed.headers().get("testHeader")).isEqualTo("headerValue");
        assertThat(reconstructed.getResult("step1")).isEqualTo("result1");
        assertThat(reconstructed.getStatus("step1")).isEqualTo(StepStatus.DONE);
    }
}
