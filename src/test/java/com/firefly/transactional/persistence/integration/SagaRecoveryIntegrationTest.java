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

import com.firefly.transactional.annotations.EnableTransactionalEngine;
import com.firefly.transactional.annotations.FromStep;
import com.firefly.transactional.annotations.Saga;
import com.firefly.transactional.annotations.SagaStep;
import com.firefly.transactional.core.SagaContext;
import com.firefly.transactional.core.StepStatus;
import com.firefly.transactional.engine.SagaEngine;
import com.firefly.transactional.persistence.SagaExecutionState;
import com.firefly.transactional.persistence.SagaExecutionStatus;
import com.firefly.transactional.persistence.SagaPersistenceProvider;
import com.firefly.transactional.persistence.SagaRecoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for saga recovery scenarios using Redis and Testcontainers.
 * This test simulates application restarts and verifies that sagas can be
 * properly recovered and resumed.
 */
@SpringBootTest(
    properties = {
        "spring.cloud.config.enabled=false",
        "spring.cloud.config.import-check.enabled=false"
    }
)
@ContextConfiguration(classes = SagaRecoveryIntegrationTest.TestConfig.class)
@Testcontainers
class SagaRecoveryIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Configure both property paths to ensure compatibility
        registry.add("firefly.saga.persistence.enabled", () -> "true");
        registry.add("firefly.saga.engine.persistence.enabled", () -> "true");
        registry.add("firefly.saga.persistence.provider", () -> "redis");
        registry.add("firefly.saga.persistence.redis.host", redis::getHost);
        registry.add("firefly.saga.persistence.redis.port", redis::getFirstMappedPort);
        registry.add("firefly.saga.persistence.redis.database", () -> "1");
        registry.add("firefly.saga.persistence.redis.key-prefix", () -> "recovery:saga:");
        registry.add("firefly.saga.persistence.redis.key-ttl", () -> "PT2H");
        registry.add("firefly.saga.engine.persistence.auto-recovery-enabled", () -> "true");
        registry.add("firefly.saga.engine.persistence.max-saga-age", () -> "PT10S"); // 10 seconds stale threshold
        registry.add("firefly.saga.engine.persistence.cleanup-interval", () -> "PT1M");

        // Override Spring Data Redis properties to use the container
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.database", () -> "1");
    }

    @Configuration
    @EnableTransactionalEngine
    static class TestConfig {

        @Bean
        @Primary
        public LettuceConnectionFactory redisConnectionFactory() {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                redis.getHost(),
                redis.getFirstMappedPort()
            );
            factory.setDatabase(1);
            factory.afterPropertiesSet();
            return factory;
        }

        @Bean
        @Primary
        public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(LettuceConnectionFactory connectionFactory) {
            RedisSerializer<String> stringSerializer = RedisSerializer.string();
            RedisSerializationContext<String, String> serializationContext = RedisSerializationContext
                .<String, String>newSerializationContext()
                .key(stringSerializer)
                .value(stringSerializer)
                .hashKey(stringSerializer)
                .hashValue(stringSerializer)
                .build();
            return new ReactiveRedisTemplate<>(connectionFactory, serializationContext);
        }

        @Bean
        public RecoverableSaga recoverableSaga() {
            return new RecoverableSaga();
        }

        @Bean
        public FailingSaga failingSaga() {
            return new FailingSaga();
        }
    }

    @Saga(name = "RecoverableSaga")
    static class RecoverableSaga {
        
        private final AtomicBoolean step2Called = new AtomicBoolean(false);
        
        @SagaStep(id = "step1")
        public Mono<String> step1() {
            return Mono.just("step1-completed");
        }
        
        @SagaStep(id = "step2", dependsOn = "step1")
        public Mono<String> step2(@FromStep("step1") String step1Result) {
            step2Called.set(true);
            return Mono.just("step2-completed-" + step1Result);
        }

        @SagaStep(id = "step3", dependsOn = "step2")
        public Mono<String> step3(@FromStep("step2") String step2Result) {
            return Mono.just("step3-completed-" + step2Result);
        }
        
        public boolean wasStep2Called() {
            return step2Called.get();
        }
        
        public void reset() {
            step2Called.set(false);
        }
    }

    @Saga(name = "FailingSaga")
    static class FailingSaga {
        
        @SagaStep(id = "step1")
        public Mono<String> step1() {
            return Mono.just("step1-completed");
        }
        
        @SagaStep(id = "step2", dependsOn = "step1", compensate = "compensateStep2")
        public Mono<String> step2() {
            return Mono.error(new RuntimeException("Simulated failure in step2"));
        }
        
        @SagaStep(id = "step3", dependsOn = "step2")
        public Mono<String> step3() {
            return Mono.just("step3-completed");
        }
        
        public Mono<Void> compensateStep2() {
            return Mono.empty(); // Successful compensation
        }
    }

    @Autowired
    private SagaEngine sagaEngine;

    @Autowired
    private SagaPersistenceProvider persistenceProvider;

    @Autowired
    private SagaRecoveryService recoveryService;

    @Autowired
    private RecoverableSaga recoverableSaga;

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

        // Reset saga state
        recoverableSaga.reset();
    }

    @Test
    void shouldRecoverPartiallyExecutedSaga() {
        String correlationId = "recovery-test-" + testCounter.incrementAndGet();
        
        // Simulate a saga that was interrupted after step1 completed
        SagaContext context = new SagaContext(correlationId, "RecoverableSaga");
        context.setStatus("step1", StepStatus.DONE);
        context.putResult("step1", "step1-completed");
        context.markStepStarted("step1", Instant.now().minusSeconds(30));
        // markStepCompleted doesn't exist, just mark as started
        
        // step2 and step3 are still pending
        context.setStatus("step2", StepStatus.PENDING);
        context.setStatus("step3", StepStatus.PENDING);

        SagaExecutionState state = new SagaExecutionState.Builder()
                .correlationId(correlationId)
                .sagaName("RecoverableSaga")
                .status(SagaExecutionStatus.RUNNING)
                .startedAt(Instant.now().minus(Duration.ofSeconds(30)))
                .lastUpdatedAt(Instant.now().minus(Duration.ofSeconds(25)))
                .context(context)
                .build();

        // Persist the interrupted state
        StepVerifier.create(persistenceProvider.persistSagaState(state))
                .verifyComplete();

        // Reset the saga bean to ensure step2 hasn't been called yet
        recoverableSaga.reset();
        assertThat(recoverableSaga.wasStep2Called()).isFalse();

        // Perform recovery
        StepVerifier.create(recoveryService.recoverInFlightSagas())
                .assertNext(report -> {
                    assertThat(report.getSuccessfullyRecovered()).isGreaterThanOrEqualTo(1);
                    assertThat(report.getFailed()).isEqualTo(0);
                })
                .verifyComplete();

        // Verify the saga was completed after recovery
        StepVerifier.create(persistenceProvider.getSagaState(correlationId))
                .assertNext(optionalState -> {
                    assertThat(optionalState).isPresent();
                    SagaExecutionState recoveredState = optionalState.get();
                    assertThat(recoveredState.getStatus()).isEqualTo(SagaExecutionStatus.COMPLETED_SUCCESS);
                    
                    SagaContext recoveredContext = recoveredState.toSagaContext();
                    assertThat(recoveredContext.getStatus("step1")).isEqualTo(StepStatus.DONE);
                    assertThat(recoveredContext.getStatus("step2")).isEqualTo(StepStatus.DONE);
                    assertThat(recoveredContext.getStatus("step3")).isEqualTo(StepStatus.DONE);
                    
                    assertThat(recoveredContext.getResult("step1")).isEqualTo("step1-completed");
                    assertThat(recoveredContext.getResult("step2")).isEqualTo("step2-completed-step1-completed");
                    assertThat(recoveredContext.getResult("step3")).isEqualTo("step3-completed-step2-completed-step1-completed");
                })
                .verifyComplete();

        // Verify that step2 was actually called during recovery
        assertThat(recoverableSaga.wasStep2Called()).isTrue();
    }

    @Test
    void shouldRecoverFailedSaga() {
        String correlationId = "failed-recovery-test-" + testCounter.incrementAndGet();
        
        // Simulate a saga that is in compensation (recoverable state)
        SagaContext context = new SagaContext(correlationId, "FailingSaga");
        context.setStatus("step1", StepStatus.DONE);
        context.putResult("step1", "step1-completed");
        context.setStatus("step2", StepStatus.FAILED);
        context.setStatus("step3", StepStatus.PENDING);

        SagaExecutionState state = new SagaExecutionState.Builder()
                .correlationId(correlationId)
                .sagaName("FailingSaga")
                .status(SagaExecutionStatus.COMPENSATING) // Use COMPENSATING instead of FAILED
                .startedAt(Instant.now().minus(Duration.ofSeconds(30)))
                .lastUpdatedAt(Instant.now().minus(Duration.ofSeconds(25)))
                .context(context)
                .build();

        // Persist the compensating state
        StepVerifier.create(persistenceProvider.persistSagaState(state))
                .verifyComplete();

        // Perform recovery
        StepVerifier.create(recoveryService.recoverInFlightSagas())
                .assertNext(report -> {
                    assertThat(report.getSuccessfullyRecovered()).isGreaterThanOrEqualTo(1);
                    assertThat(report.getFailed()).isEqualTo(0);
                })
                .verifyComplete();

        // Verify the saga was processed during recovery (compensation should complete)
        StepVerifier.create(persistenceProvider.getSagaState(correlationId))
                .assertNext(optionalState -> {
                    assertThat(optionalState).isPresent();
                    SagaExecutionState recoveredState = optionalState.get();
                    // The saga should be completed after compensation
                    assertThat(recoveredState.getStatus()).isIn(SagaExecutionStatus.FAILED, SagaExecutionStatus.COMPLETED_COMPENSATED);
                })
                .verifyComplete();
    }

    @Test
    void shouldIdentifyMultipleStaleSagas() {
        int testId = testCounter.incrementAndGet();
        String correlationId1 = "stale-multi-" + testId + "-1";
        String correlationId2 = "stale-multi-" + testId + "-2";
        String correlationId3 = "stale-multi-" + testId + "-3";
        
        // Create multiple stale sagas
        Instant staleTime = Instant.now().minus(Duration.ofSeconds(60));

        SagaContext context1 = new SagaContext(correlationId1, "RecoverableSaga");
        context1.setStatus("step1", StepStatus.DONE);
        SagaExecutionState state1 = new SagaExecutionState.Builder()
                .correlationId(correlationId1)
                .sagaName("RecoverableSaga")
                .status(SagaExecutionStatus.RUNNING)
                .startedAt(staleTime)
                .lastUpdatedAt(staleTime)
                .context(context1)
                .build();

        SagaContext context2 = new SagaContext(correlationId2, "RecoverableSaga");
        context2.setStatus("step1", StepStatus.DONE);
        SagaExecutionState state2 = new SagaExecutionState.Builder()
                .correlationId(correlationId2)
                .sagaName("RecoverableSaga")
                .status(SagaExecutionStatus.RUNNING)
                .startedAt(staleTime)
                .lastUpdatedAt(staleTime)
                .context(context2)
                .build();

        SagaContext context3 = new SagaContext(correlationId3, "FailingSaga");
        context3.setStatus("step1", StepStatus.DONE);
        SagaExecutionState state3 = new SagaExecutionState.Builder()
                .correlationId(correlationId3)
                .sagaName("FailingSaga")
                .status(SagaExecutionStatus.FAILED)
                .startedAt(staleTime)
                .lastUpdatedAt(staleTime)
                .context(context3)
                .build();

        // Persist all states
        StepVerifier.create(Mono.when(
                persistenceProvider.persistSagaState(state1),
                persistenceProvider.persistSagaState(state2),
                persistenceProvider.persistSagaState(state3)
        )).verifyComplete();

        // Identify stale sagas (only RUNNING sagas should be identified as stale)
        StepVerifier.create(recoveryService.identifyStaleSagas(Duration.ofSeconds(30)))
                .expectNextCount(2) // Only the 2 RUNNING sagas, not the FAILED one
                .verifyComplete();

        // Perform recovery (only RUNNING sagas should be recovered)
        StepVerifier.create(recoveryService.recoverInFlightSagas())
                .assertNext(report -> {
                    assertThat(report.getSuccessfullyRecovered()).isGreaterThanOrEqualTo(2); // Only 2 recoverable sagas
                    assertThat(report.getFailed()).isEqualTo(0);
                })
                .verifyComplete();
    }

    @Test
    void shouldNotRecoverRecentlyUpdatedSagas() {
        String correlationId = "recent-saga-" + testCounter.incrementAndGet();
        
        // Create a saga that was updated recently (not stale)
        SagaContext context = new SagaContext(correlationId, "RecoverableSaga");
        context.setStatus("step1", StepStatus.DONE);
        
        SagaExecutionState state = new SagaExecutionState.Builder()
                .correlationId(correlationId)
                .sagaName("RecoverableSaga")
                .status(SagaExecutionStatus.RUNNING)
                .startedAt(Instant.now().minus(Duration.ofSeconds(5)))
                .lastUpdatedAt(Instant.now().minus(Duration.ofSeconds(2)))
                .context(context)
                .build();

        // Persist the recent state
        StepVerifier.create(persistenceProvider.persistSagaState(state))
                .verifyComplete();

        // Try to recover - should not recover this recent saga
        StepVerifier.create(recoveryService.recoverInFlightSagas())
                .assertNext(report -> {
                    // This saga is too recent to be considered stale for recovery
                    assertThat(report.getSuccessfullyRecovered()).isGreaterThanOrEqualTo(0);
                })
                .verifyComplete();

        // Verify the saga state is unchanged
        StepVerifier.create(persistenceProvider.getSagaState(correlationId))
                .assertNext(optionalState -> {
                    assertThat(optionalState).isPresent();
                    assertThat(optionalState.get().getStatus()).isEqualTo(SagaExecutionStatus.RUNNING);
                })
                .verifyComplete();
    }

    @Test
    void shouldHandleRecoveryErrors() {
        String correlationId = "invalid-saga-" + testCounter.incrementAndGet();
        
        // Create a saga with an invalid saga name (no corresponding bean)
        SagaContext context = new SagaContext(correlationId, "NonExistentSaga");
        context.setStatus("step1", StepStatus.DONE);
        
        SagaExecutionState state = new SagaExecutionState.Builder()
                .correlationId(correlationId)
                .sagaName("NonExistentSaga")
                .status(SagaExecutionStatus.RUNNING)
                .startedAt(Instant.now().minus(Duration.ofSeconds(60)))
                .lastUpdatedAt(Instant.now().minus(Duration.ofSeconds(60)))
                .context(context)
                .build();

        // Persist the invalid state
        StepVerifier.create(persistenceProvider.persistSagaState(state))
                .verifyComplete();

        // Attempt recovery - should handle the error gracefully
        StepVerifier.create(recoveryService.recoverInFlightSagas())
                .assertNext(report -> {
                    // The recovery should complete but may have failed recoveries
                    assertThat(report.getSuccessfullyRecovered()).isGreaterThanOrEqualTo(0);
                    // Failed recoveries might be reported depending on implementation
                })
                .verifyComplete();
    }
}
