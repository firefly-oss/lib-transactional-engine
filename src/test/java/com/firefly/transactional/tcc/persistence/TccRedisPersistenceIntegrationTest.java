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

package com.firefly.transactional.tcc.persistence;

import com.firefly.transactional.shared.annotations.EnableTransactionalEngine;
import com.firefly.transactional.saga.persistence.SagaPersistenceProvider;
import com.firefly.transactional.tcc.annotations.*;
import com.firefly.transactional.tcc.core.TccInputs;
import com.firefly.transactional.tcc.core.TccResult;
import com.firefly.transactional.tcc.engine.TccEngine;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for TCC persistence with Redis backend.
 * Uses Testcontainers to spin up a real Redis instance.
 */
@SpringBootTest(
    properties = {
        "spring.cloud.config.enabled=false",
        "spring.cloud.config.import-check.enabled=false"
    }
)
@ContextConfiguration(classes = TccRedisPersistenceIntegrationTest.TestConfig.class)
@Testcontainers
class TccRedisPersistenceIntegrationTest {

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
        registry.add("firefly.tx.persistence.redis.database", () -> "3");
        registry.add("firefly.tx.persistence.redis.key-prefix", () -> "tcc:test:");
        registry.add("firefly.tx.persistence.redis.key-ttl", () -> "PT1H");

        // Also configure legacy properties for backward compatibility testing
        registry.add("firefly.saga.persistence.enabled", () -> "true");
        registry.add("firefly.saga.engine.persistence.enabled", () -> "true");
        registry.add("firefly.saga.persistence.provider", () -> "redis");

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.data.redis.database", () -> "3");
    }

    @Configuration
    @EnableTransactionalEngine
    static class TestConfig {

        @Bean
        @Primary
        public org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory() {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                redis.getHost(),
                redis.getFirstMappedPort()
            );
            factory.setDatabase(3);
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
            return new ReactiveRedisTemplate<>(connectionFactory, serializationContext);
        }

        @Bean
        public RedisTcc redisTcc() {
            return new RedisTcc();
        }
    }

    @Tcc(name = "RedisTcc")
    static class RedisTcc {

        @TccParticipant(id = "payment", order = 1)
        public static class PaymentParticipant {

            @TryMethod
            public Mono<PaymentReservation> reservePayment(OrderRequest order) {
                return Mono.just(new PaymentReservation(
                    "payment-" + order.getOrderId(),
                    order.getAmount()
                ));
            }

            @ConfirmMethod
            public Mono<Void> confirmPayment(@FromTry PaymentReservation reservation) {
                return Mono.empty();
            }

            @CancelMethod
            public Mono<Void> cancelPayment(@FromTry PaymentReservation reservation) {
                return Mono.empty();
            }
        }

        @TccParticipant(id = "inventory", order = 2)
        public static class InventoryParticipant {

            @TryMethod
            public Mono<InventoryReservation> reserveInventory(OrderRequest order) {
                return Mono.just(new InventoryReservation(
                    "inventory-" + order.getOrderId(),
                    order.getQuantity()
                ));
            }

            @ConfirmMethod
            public Mono<Void> confirmInventory(@FromTry InventoryReservation reservation) {
                return Mono.empty();
            }

            @CancelMethod
            public Mono<Void> cancelInventory(@FromTry InventoryReservation reservation) {
                return Mono.empty();
            }
        }
    }

    static class OrderRequest {
        private final String orderId;
        private final double amount;
        private final int quantity;

        public OrderRequest(String orderId, double amount, int quantity) {
            this.orderId = orderId;
            this.amount = amount;
            this.quantity = quantity;
        }

        public String getOrderId() { return orderId; }
        public double getAmount() { return amount; }
        public int getQuantity() { return quantity; }
    }

    static class PaymentReservation {
        private final String reservationId;
        private final double amount;

        public PaymentReservation(String reservationId, double amount) {
            this.reservationId = reservationId;
            this.amount = amount;
        }

        public String getReservationId() { return reservationId; }
        public double getAmount() { return amount; }
    }

    static class InventoryReservation {
        private final String reservationId;
        private final int quantity;

        public InventoryReservation(String reservationId, int quantity) {
            this.reservationId = reservationId;
            this.quantity = quantity;
        }

        public String getReservationId() { return reservationId; }
        public int getQuantity() { return quantity; }
    }

    @Autowired
    private TccEngine tccEngine;

    @Autowired
    private SagaPersistenceProvider persistenceProvider;

    @BeforeEach
    void setUp() {
        // Verify Redis is healthy
        StepVerifier.create(persistenceProvider.isHealthy())
            .expectNext(true)
            .verifyComplete();
    }

    @Test
    void testTccExecutionWithRedisPersistence() {
        // Given
        OrderRequest order = new OrderRequest("order-123", 99.99, 5);
        
        TccInputs inputs = TccInputs.builder()
            .forParticipant("payment", order)
            .forParticipant("inventory", order)
            .build();

        // When - Execute TCC transaction
        StepVerifier.create(tccEngine.execute("RedisTcc", inputs))
            .assertNext(result -> {
                // Then - Verify successful execution
                assertThat(result.isSuccess()).isTrue();
                assertThat(result.isConfirmed()).isTrue();
                
                PaymentReservation paymentRes = (PaymentReservation) result.getTryResult("payment");
                InventoryReservation inventoryRes = (InventoryReservation) result.getTryResult("inventory");
                
                assertThat(paymentRes.getReservationId()).isEqualTo("payment-order-123");
                assertThat(paymentRes.getAmount()).isEqualTo(99.99);
                assertThat(inventoryRes.getReservationId()).isEqualTo("inventory-order-123");
                assertThat(inventoryRes.getQuantity()).isEqualTo(5);
            })
            .verifyComplete();
    }



    @Test
    void testMultipleConcurrentTccExecutions() {
        // Given
        OrderRequest order1 = new OrderRequest("order-001", 50.00, 2);
        OrderRequest order2 = new OrderRequest("order-002", 75.00, 3);
        OrderRequest order3 = new OrderRequest("order-003", 100.00, 1);

        TccInputs inputs1 = TccInputs.builder()
            .forParticipant("payment", order1)
            .forParticipant("inventory", order1)
            .build();

        TccInputs inputs2 = TccInputs.builder()
            .forParticipant("payment", order2)
            .forParticipant("inventory", order2)
            .build();

        TccInputs inputs3 = TccInputs.builder()
            .forParticipant("payment", order3)
            .forParticipant("inventory", order3)
            .build();

        // When - Execute multiple TCC transactions concurrently
        Mono<TccResult> exec1 = tccEngine.execute("RedisTcc", inputs1);
        Mono<TccResult> exec2 = tccEngine.execute("RedisTcc", inputs2);
        Mono<TccResult> exec3 = tccEngine.execute("RedisTcc", inputs3);

        // Then - All should complete successfully
        StepVerifier.create(Mono.zip(exec1, exec2, exec3))
            .assertNext(tuple -> {
                assertThat(tuple.getT1().isSuccess()).isTrue();
                assertThat(tuple.getT2().isSuccess()).isTrue();
                assertThat(tuple.getT3().isSuccess()).isTrue();
            })
            .verifyComplete();
    }

    @Test
    void testRedisPersistenceProviderHealth() {
        // When - Check provider health
        StepVerifier.create(persistenceProvider.isHealthy())
            .expectNext(true)
            .verifyComplete();
    }
}

