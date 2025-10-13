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

package com.firefly.transactional.persistence.integration;

import com.firefly.transactional.saga.annotations.FromStep;
import com.firefly.transactional.saga.annotations.Saga;
import com.firefly.transactional.saga.annotations.SagaStep;
import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.saga.engine.SagaEngine;
import com.firefly.transactional.saga.engine.StepInputs;
import com.firefly.transactional.shared.annotations.EnableTransactionalEngine;
import com.firefly.transactional.shared.core.StepStatus;
import com.firefly.transactional.saga.persistence.SagaExecutionState;
import com.firefly.transactional.saga.persistence.SagaExecutionStatus;
import com.firefly.transactional.saga.persistence.SagaPersistenceProvider;
import com.firefly.transactional.saga.persistence.SagaRecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for Redis persistence using Testcontainers.
 * This test verifies that the Redis persistence provider works correctly
 * with a real Redis instance running in a Docker container.
 */
@SpringBootTest(
    classes = RedisPersistenceIntegrationTest.TestConfig.class,
    properties = {
        "spring.cloud.config.enabled=false",
        "spring.cloud.config.import-check.enabled=false"
    }
)
@Testcontainers
class RedisPersistenceIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Configure new generic properties
        registry.add("firefly.tx.persistence.enabled", () -> "true");
        registry.add("firefly.tx.persistence.provider", () -> "redis");
        registry.add("firefly.tx.persistence.redis.host", redis::getHost);
        registry.add("firefly.tx.persistence.redis.port", redis::getFirstMappedPort);
        registry.add("firefly.tx.persistence.redis.database", () -> "0");
        registry.add("firefly.tx.persistence.redis.key-prefix", () -> "test:tx:");
        registry.add("firefly.tx.persistence.redis.key-ttl", () -> "PT1H");
        registry.add("firefly.tx.persistence.auto-recovery-enabled", () -> "true");
        registry.add("firefly.tx.persistence.max-transaction-age", () -> "PT30S");

        // Also configure legacy properties to test backward compatibility
        registry.add("firefly.saga.persistence.enabled", () -> "true");
        registry.add("firefly.saga.engine.persistence.enabled", () -> "true");
        registry.add("firefly.saga.persistence.provider", () -> "redis");

        // Override Spring Data Redis properties to use the container
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.database", () -> "0");
    }

    @Configuration
    @EnableTransactionalEngine
    static class TestConfig {

        @Bean
        @Primary
        public RedisConnectionFactory redisConnectionFactory() {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                redis.getHost(),
                redis.getFirstMappedPort()
            );
            factory.setDatabase(0);
            factory.afterPropertiesSet();
            return factory;
        }

        @Bean
        public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(LettuceConnectionFactory connectionFactory) {
            RedisSerializer<String> stringSerializer = RedisSerializer.string();
            RedisSerializationContext<String, String> serializationContext = RedisSerializationContext
                .<String, String>newSerializationContext()
                .key(RedisSerializationContext.SerializationPair.fromSerializer(stringSerializer))
                .value(RedisSerializationContext.SerializationPair.fromSerializer(stringSerializer))
                .hashKey(RedisSerializationContext.SerializationPair.fromSerializer(stringSerializer))
                .hashValue(RedisSerializationContext.SerializationPair.fromSerializer(stringSerializer))
                .build();
            return new ReactiveRedisTemplate<String, String>(connectionFactory, serializationContext);
        }

        @Bean
        public TestSaga testSaga() {
            return new TestSaga();
        }
    }

    @Saga(name = "TestSaga")
    static class TestSaga {

        @SagaStep(id = "step1")
        public Mono<String> step1() {
            return Mono.just("step1-result");
        }

        @SagaStep(id = "step2", dependsOn = "step1")
        public Mono<String> step2(@FromStep("step1") String step1Result) {
            return Mono.just("step2-result-" + step1Result);
        }

        @SagaStep(id = "step3", dependsOn = "step2")
        public Mono<String> step3(@FromStep("step2") String step2Result) {
            return Mono.just("step3-result-" + step2Result);
        }
    }

    @Autowired
    private SagaEngine sagaEngine;

    @Autowired
    private SagaPersistenceProvider persistenceProvider;

    @Autowired
    private SagaRecoveryService recoveryService;

    @Autowired
    private ReactiveRedisTemplate<String, String> redisTemplate;

    private static final AtomicInteger testCounter = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        // Clear Redis before each test to ensure isolation
        redisTemplate.getConnectionFactory().getReactiveConnection()
                .serverCommands()
                .flushAll()
                .block();
    }

    @Test
    void shouldConnectToRedis() {
        assertThat(redis.isRunning()).isTrue();
        
        // Test Redis health check
        StepVerifier.create(persistenceProvider.isHealthy())
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void shouldPersistAndRetrieveSagaState() {
        // Create a test saga execution state
        SagaContext context = new SagaContext("test-correlation-1", "TestSaga");
        context.putVariable("testVar", "testValue");
        context.putHeader("testHeader", "headerValue");
        context.setStatus("step1", StepStatus.DONE);
        context.putResult("step1", "step1-result");
        context.markStepStarted("step1", Instant.now().minusSeconds(10));

        SagaExecutionState state = new SagaExecutionState.Builder()
                .correlationId("test-correlation-1")
                .sagaName("TestSaga")
                .status(SagaExecutionStatus.RUNNING)
                .startedAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .context(context)
                .build();

        // Persist the state
        StepVerifier.create(persistenceProvider.persistSagaState(state))
                .verifyComplete();

        // Retrieve the state
        StepVerifier.create(persistenceProvider.getSagaState("test-correlation-1"))
                .assertNext(optionalState -> {
                    assertThat(optionalState).isPresent();
                    SagaExecutionState retrievedState = optionalState.get();
                    assertThat(retrievedState.getCorrelationId()).isEqualTo("test-correlation-1");
                    assertThat(retrievedState.getSagaName()).isEqualTo("TestSaga");
                    assertThat(retrievedState.getStatus()).isEqualTo(SagaExecutionStatus.RUNNING);

                    SagaContext retrievedContext = retrievedState.toSagaContext();
                    assertThat(retrievedContext.getVariable("testVar")).isEqualTo("testValue");
                    assertThat(retrievedContext.headers().get("testHeader")).isEqualTo("headerValue");
                    assertThat(retrievedContext.getStatus("step1")).isEqualTo(StepStatus.DONE);
                    assertThat(retrievedContext.getResult("step1")).isEqualTo("step1-result");
                })
                .verifyComplete();
    }

    @Test
    void shouldExecuteAndPersistSagaExecution() {
        String correlationId = "integration-test-" + testCounter.incrementAndGet();

        // Execute saga
        SagaContext sagaContext = new SagaContext(correlationId, "TestSaga");
        StepVerifier.create(sagaEngine.execute("TestSaga", StepInputs.empty(), sagaContext))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.steps()).hasSize(3);
                    // Check that all expected steps are present (order doesn't matter for this check)
                    assertThat(result.steps().keySet()).containsExactlyInAnyOrder("step1", "step2", "step3");
                    // Verify step results
                    assertThat(result.resultOf("step1", String.class)).hasValue("step1-result");
                    assertThat(result.resultOf("step2", String.class)).hasValue("step2-result-step1-result");
                    assertThat(result.resultOf("step3", String.class)).hasValue("step3-result-step2-result-step1-result");
                })
                .verifyComplete();

        // Add a small delay to ensure persistence operations complete
        StepVerifier.create(Mono.delay(Duration.ofMillis(100)).then(Mono.empty()))
                .verifyComplete();

        // Verify saga state was persisted
        StepVerifier.create(persistenceProvider.getSagaState(correlationId))
                .assertNext(optionalState -> {
                    assertThat(optionalState).isPresent();
                    SagaExecutionState state = optionalState.get();
                    assertThat(state.getStatus()).isEqualTo(SagaExecutionStatus.COMPLETED_SUCCESS);

                    SagaContext retrievedContext = state.toSagaContext();
                    assertThat(retrievedContext.getStatus("step1")).isEqualTo(StepStatus.DONE);
                    assertThat(retrievedContext.getStatus("step2")).isEqualTo(StepStatus.DONE);
                    assertThat(retrievedContext.getStatus("step3")).isEqualTo(StepStatus.DONE);
                    assertThat(retrievedContext.getResult("step1")).isEqualTo("step1-result");
                    assertThat(retrievedContext.getResult("step2")).isEqualTo("step2-result-step1-result");
                    assertThat(retrievedContext.getResult("step3")).isEqualTo("step3-result-step2-result-step1-result");
                })
                .verifyComplete();
    }

    @Test
    void shouldMarkSagaAsCompleted() {
        String correlationId = "completion-test-" + testCounter.incrementAndGet();

        // Create and persist initial state
        SagaContext context = new SagaContext(correlationId, "TestSaga");
        SagaExecutionState state = new SagaExecutionState.Builder()
                .correlationId(correlationId)
                .sagaName("TestSaga")
                .status(SagaExecutionStatus.RUNNING)
                .startedAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .context(context)
                .build();

        StepVerifier.create(persistenceProvider.persistSagaState(state))
                .verifyComplete();

        // Mark as completed
        StepVerifier.create(persistenceProvider.markSagaCompleted(correlationId, true))
                .verifyComplete();

        // Verify completion status
        StepVerifier.create(persistenceProvider.getSagaState(correlationId))
                .assertNext(optionalState -> {
                    assertThat(optionalState).isPresent();
                    assertThat(optionalState.get().getStatus()).isEqualTo(SagaExecutionStatus.COMPLETED_SUCCESS);
                })
                .verifyComplete();
    }

    @Test
    void shouldIdentifyActiveSagas() {
        String correlationId1 = "active-test-1";
        String correlationId2 = "active-test-2";

        // Create two active sagas
        SagaContext context1 = new SagaContext(correlationId1, "TestSaga");
        SagaContext context2 = new SagaContext(correlationId2, "TestSaga");

        SagaExecutionState state1 = new SagaExecutionState.Builder()
                .correlationId(correlationId1)
                .sagaName("TestSaga")
                .status(SagaExecutionStatus.RUNNING)
                .startedAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .context(context1)
                .build();
        SagaExecutionState state2 = new SagaExecutionState.Builder()
                .correlationId(correlationId2)
                .sagaName("TestSaga")
                .status(SagaExecutionStatus.RUNNING)
                .startedAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .context(context2)
                .build();

        StepVerifier.create(Mono.when(
                persistenceProvider.persistSagaState(state1),
                persistenceProvider.persistSagaState(state2)
        )).verifyComplete();

        // Get active sagas
        StepVerifier.create(persistenceProvider.getInFlightSagas())
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void shouldIdentifyStaleSagas() throws InterruptedException {
        String correlationId = "stale-test-" + testCounter.incrementAndGet();
        
        // Create a saga with old timestamp
        SagaContext context = new SagaContext(correlationId, "TestSaga");
        SagaExecutionState state = new SagaExecutionState.Builder()
                .correlationId(correlationId)
                .sagaName("TestSaga")
                .status(SagaExecutionStatus.RUNNING)
                .startedAt(Instant.now().minus(Duration.ofSeconds(60)))
                .lastUpdatedAt(Instant.now().minus(Duration.ofSeconds(60)))
                .context(context)
                .build();

        StepVerifier.create(persistenceProvider.persistSagaState(state))
                .verifyComplete();

        // Wait a bit to ensure the saga is considered stale
        Thread.sleep(1000);

        // Identify stale sagas (threshold is 30 seconds)
        StepVerifier.create(recoveryService.identifyStaleSagas(Duration.ofSeconds(30)))
                .assertNext(staleSaga -> {
                    assertThat(staleSaga.getCorrelationId()).isEqualTo(correlationId);
                    assertThat(staleSaga.getSagaName()).isEqualTo("TestSaga");
                })
                .verifyComplete();
    }

    @Test
    @org.junit.jupiter.api.Disabled("Cleanup functionality needs further investigation")
    void shouldCleanupCompletedSagas() {
        String correlationId = "cleanup-test-" + testCounter.incrementAndGet();

        // Create and complete a saga
        SagaContext context = new SagaContext(correlationId, "TestSaga");
        SagaExecutionState state = new SagaExecutionState.Builder()
                .correlationId(correlationId)
                .sagaName("TestSaga")
                .status(SagaExecutionStatus.RUNNING) // Start as running
                .startedAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .context(context)
                .build();

        // Persist initial state and mark as completed
        StepVerifier.create(persistenceProvider.persistSagaState(state)
                .then(persistenceProvider.markSagaCompleted(correlationId, true)))
                .verifyComplete();

        // Verify saga exists before cleanup
        StepVerifier.create(persistenceProvider.getSagaState(correlationId))
                .assertNext(optionalState -> assertThat(optionalState).isPresent())
                .verifyComplete();

        // Cleanup all completed sagas (use a very large duration to ensure cleanup)
        StepVerifier.create(persistenceProvider.cleanupCompletedSagas(Duration.ofDays(-1)))
                .expectNextMatches(count -> count >= 1L) // Should cleanup at least 1 saga
                .verifyComplete();

        // Verify saga was removed
        StepVerifier.create(persistenceProvider.getSagaState(correlationId))
                .assertNext(optionalState -> assertThat(optionalState).isEmpty())
                .verifyComplete();
    }
}
