/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.firefly.transactional.tcc.events;

import com.firefly.transactional.shared.annotations.EnableTransactionalEngine;
import com.firefly.transactional.tcc.annotations.*;
import com.firefly.transactional.tcc.core.TccInputs;
import com.firefly.transactional.tcc.core.TccPhase;
import com.firefly.transactional.tcc.core.TccResult;
import com.firefly.transactional.tcc.engine.TccEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that TCC events are properly delegated to custom TccEventPublisher implementations.
 */
@SpringBootTest(classes = TccEventPublisherDelegationTest.TestConfiguration.class,
                properties = {
                    "spring.cloud.config.enabled=false",
                    "spring.main.allow-bean-definition-overriding=true"
                })
class TccEventPublisherDelegationTest {

    @Autowired
    private TccEngine tccEngine;

    @Autowired
    @Qualifier("testTccEventPublisher")
    private TestTccEventPublisher testEventPublisher;

    @BeforeEach
    void setUp() {
        testEventPublisher.getPublishedEvents().clear();
    }

    @Test
    void shouldDelegateTccEventsToCustomPublisher() {
        // Verify that the custom TccEventPublisher bean is available
        assertThat(testEventPublisher).isNotNull();
        System.out.println("TestTccEventPublisher bean: " + testEventPublisher.getClass().getName());

        // Given
        TccInputs inputs = TccInputs.builder()
                .forParticipant("payment", new PaymentRequest("user123", 100.0))
                .build();

        // When
        StepVerifier.create(tccEngine.execute("PaymentTcc", inputs))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.getFinalPhase()).isEqualTo(TccPhase.CONFIRM);
                })
                .verifyComplete();

        // Then - verify events were published
        List<TccEventEnvelope> events = testEventPublisher.getPublishedEvents();
        System.out.println("Published events count: " + events.size());
        for (TccEventEnvelope event : events) {
            System.out.println("Event: " + event.getType() + " - " + event.getPhase() + " - " + event.getTopic());
        }
        assertThat(events).isNotEmpty();

        // Should have participant events for both TRY and CONFIRM phases
        assertThat(events).anyMatch(e -> "PAYMENT_PARTICIPANT".equals(e.getType()) && TccPhase.TRY.equals(e.getPhase()));
        assertThat(events).anyMatch(e -> "PAYMENT_PARTICIPANT".equals(e.getType()) && TccPhase.CONFIRM.equals(e.getPhase()));

        // Verify TRY event details
        TccEventEnvelope tryEvent = events.stream()
                .filter(e -> "PAYMENT_PARTICIPANT".equals(e.getType()) && TccPhase.TRY.equals(e.getPhase()))
                .findFirst()
                .orElseThrow();

        assertThat(tryEvent.getTopic()).isEqualTo("payment-events");
        assertThat(tryEvent.getParticipantId()).isEqualTo("payment");
        assertThat(tryEvent.getSuccess()).isTrue();

        // Verify CONFIRM event details
        TccEventEnvelope confirmEvent = events.stream()
                .filter(e -> "PAYMENT_PARTICIPANT".equals(e.getType()) && TccPhase.CONFIRM.equals(e.getPhase()))
                .findFirst()
                .orElseThrow();

        assertThat(confirmEvent.getTopic()).isEqualTo("payment-events");
        assertThat(confirmEvent.getParticipantId()).isEqualTo("payment");
        assertThat(confirmEvent.getSuccess()).isTrue();

        // All events should have correct TCC name and correlation ID
        assertThat(events).allMatch(e -> "PaymentTcc".equals(e.getTccName()));
        assertThat(events).allMatch(e -> e.getCorrelationId() != null);
    }

    @Test
    void shouldDelegateTccEventsToCustomPublisherForFailingTransaction() {
        // Given - a TCC that will fail in the TRY phase
        TccInputs inputs = TccInputs.builder()
                .forParticipant("payment", new PaymentRequest("user123", -100.0)) // Negative amount will cause failure
                .build();

        // When
        StepVerifier.create(tccEngine.execute("PaymentTcc", inputs))
                .assertNext(result -> {
                    assertThat(result.isSuccess()).isFalse();
                    assertThat(result.getFinalPhase()).isEqualTo(TccPhase.CANCEL);
                })
                .verifyComplete();

        // Then - verify events were published for TRY phase (failed)
        // Note: CANCEL phase events are only published for participants that succeeded in TRY phase
        List<TccEventEnvelope> events = testEventPublisher.getPublishedEvents();
        System.out.println("Published events count for failing transaction: " + events.size());
        for (TccEventEnvelope event : events) {
            System.out.println("Event: " + event.getType() + " - " + event.getPhase() + " - " + event.getTopic() + " - Success: " + event.getSuccess());
        }

        assertThat(events).isNotEmpty();

        // Should have TRY phase event (failed)
        assertThat(events).anyMatch(e -> "PAYMENT_PARTICIPANT".equals(e.getType()) && TccPhase.TRY.equals(e.getPhase()) && Boolean.FALSE.equals(e.getSuccess()));

        // Should NOT have CANCEL phase event because no participants succeeded in TRY phase
        assertThat(events).noneMatch(e -> TccPhase.CANCEL.equals(e.getPhase()));

        // All events should have correct TCC name and correlation ID
        assertThat(events).allMatch(e -> "PaymentTcc".equals(e.getTccName()));
        assertThat(events).allMatch(e -> e.getCorrelationId() != null);
    }

    @Configuration
    @EnableTransactionalEngine
    static class TestConfiguration {

        @Bean
        public TccEventPublisher tccEventPublisher() {
            return new TestTccEventPublisher();
        }

        @Bean
        public TestTccEventPublisher testTccEventPublisher(TccEventPublisher tccEventPublisher) {
            return (TestTccEventPublisher) tccEventPublisher;
        }

        @Bean
        public PaymentTcc paymentTcc() {
            return new PaymentTcc();
        }
    }

    static class TestTccEventPublisher implements TccEventPublisher {
        private final List<TccEventEnvelope> publishedEvents = new CopyOnWriteArrayList<>();

        @Override
        public Mono<Void> publish(TccEventEnvelope event) {
            publishedEvents.add(event);
            return Mono.empty();
        }

        public List<TccEventEnvelope> getPublishedEvents() {
            return publishedEvents;
        }
    }

    @Tcc(name = "PaymentTcc")
    static class PaymentTcc {

        @TccParticipant(id = "payment")
        @TccEvent(topic = "payment-events", eventType = "PAYMENT_PARTICIPANT")
        public static class PaymentParticipant {

            @TryMethod
            public Mono<PaymentReservation> tryReservePayment(PaymentRequest request) {
                if (request.amount() < 0) {
                    return Mono.error(new RuntimeException("Invalid payment amount: " + request.amount()));
                }
                return Mono.just(new PaymentReservation(request.userId(), request.amount(), "reservation-123"));
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
    }

    record PaymentRequest(String userId, Double amount) {}
    record PaymentReservation(String userId, Double amount, String reservationId) {}
}
