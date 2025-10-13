/*
 * Copyright 2024 Firefly Authors
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

package com.firefly.transactional.events;

import com.firefly.transactional.saga.annotations.FromStep;
import com.firefly.transactional.saga.annotations.Saga;
import com.firefly.transactional.saga.annotations.SagaStep;
import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.saga.core.SagaResult;
import com.firefly.transactional.saga.engine.SagaEngine;
import com.firefly.transactional.saga.engine.StepInputs;
import com.firefly.transactional.saga.observability.SagaEvents;
import com.firefly.transactional.shared.annotations.EnableTransactionalEngine;
import com.firefly.transactional.tcc.annotations.*;
import com.firefly.transactional.tcc.core.TccContext;
import com.firefly.transactional.tcc.core.TccInputs;
import com.firefly.transactional.tcc.core.TccPhase;
import com.firefly.transactional.tcc.core.TccResult;
import com.firefly.transactional.tcc.engine.TccEngine;
import com.firefly.transactional.tcc.observability.TccEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that SAGA and TCC event systems are properly isolated
 * and work correctly without cross-contamination.
 */
class EventSystemIsolationTest {

    private AnnotationConfigApplicationContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    @Configuration
    @EnableTransactionalEngine
    static class TestConfig {
        @Bean
        public TestSaga testSaga() {
            return new TestSaga();
        }

        @Bean
        public TestTcc testTcc() {
            return new TestTcc();
        }

        // Override the default composite events with our test implementations
        // This tests that we can properly isolate event systems without conflicts
        @Bean
        @Primary
        public SagaEvents sagaEventsComposite() {
            return new TestSagaEvents();
        }

        @Bean
        @Primary
        public TccEvents tccEventsComposite() {
            return new TestTccEvents();
        }
    }

    @Test
    void sagaEventsShouldNotReceiveTccEvents() {
        // Setup Spring context
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        TccEngine tccEngine = ctx.getBean(TccEngine.class);
        TestSagaEvents sagaEvents = (TestSagaEvents) ctx.getBean(SagaEvents.class);
        TestTccEvents tccEvents = (TestTccEvents) ctx.getBean(TccEvents.class);

        // Execute TCC transaction
        TccInputs tccInputs = TccInputs.builder()
                .forParticipant("participant1", "test-data")
                .build();

        TccResult tccResult = tccEngine.execute("TestTcc", tccInputs).block();

        // Verify TCC executed successfully
        assertNotNull(tccResult);
        assertTrue(tccResult.isSuccess());

        // Verify SAGA events were not triggered
        assertTrue(sagaEvents.events.isEmpty(),
                "SAGA events should not receive TCC events");

        // Verify TCC events were triggered
        assertFalse(tccEvents.events.isEmpty(),
                "TCC events should have been triggered");
        assertTrue(tccEvents.events.stream().anyMatch(e -> e.contains("tcc_started")));
        assertTrue(tccEvents.events.stream().anyMatch(e -> e.contains("phase_started:TRY")));
        assertTrue(tccEvents.events.stream().anyMatch(e -> e.contains("participant_success")));
        assertTrue(tccEvents.events.stream().anyMatch(e -> e.contains("tcc_completed")));
    }

    @Test
    void tccEventsShouldNotReceiveSagaEvents() {
        // Setup Spring context
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        SagaEngine sagaEngine = ctx.getBean(SagaEngine.class);
        TestSagaEvents sagaEvents = (TestSagaEvents) ctx.getBean(SagaEvents.class);
        TestTccEvents tccEvents = (TestTccEvents) ctx.getBean(TccEvents.class);

        // Execute SAGA transaction
        StepInputs sagaInputs = StepInputs.builder()
                .forStepId("step1", "test-data")
                .build();

        SagaResult sagaResult = sagaEngine.execute("TestSaga", sagaInputs).block();

        // Verify SAGA executed successfully
        assertNotNull(sagaResult);
        assertTrue(sagaResult.isSuccess());

        // Verify TCC events were not triggered
        assertTrue(tccEvents.events.isEmpty(),
                "TCC events should not receive SAGA events");

        // Verify SAGA events were triggered
        assertFalse(sagaEvents.events.isEmpty(),
                "SAGA events should have been triggered");
        assertTrue(sagaEvents.events.stream().anyMatch(e -> e.contains("saga_started")));
        assertTrue(sagaEvents.events.stream().anyMatch(e -> e.contains("step_started")));
        assertTrue(sagaEvents.events.stream().anyMatch(e -> e.contains("step_success")));
        assertTrue(sagaEvents.events.stream().anyMatch(e -> e.contains("saga_completed")));
    }

    @Test
    void bothEventSystemsCanWorkSimultaneously() {
        // Setup Spring context
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        SagaEngine sagaEngine = ctx.getBean(SagaEngine.class);
        TccEngine tccEngine = ctx.getBean(TccEngine.class);
        TestSagaEvents sagaEvents = (TestSagaEvents) ctx.getBean(SagaEvents.class);
        TestTccEvents tccEvents = (TestTccEvents) ctx.getBean(TccEvents.class);

        // Execute both SAGA and TCC transactions
        StepInputs sagaInputs = StepInputs.builder()
                .forStepId("step1", "saga-data")
                .build();

        TccInputs tccInputs = TccInputs.builder()
                .forParticipant("participant1", "tcc-data")
                .build();

        // Execute both
        SagaResult sagaResult = sagaEngine.execute("TestSaga", sagaInputs).block();
        TccResult tccResult = tccEngine.execute("TestTcc", tccInputs).block();

        // Verify both executed successfully
        assertNotNull(sagaResult);
        assertTrue(sagaResult.isSuccess());
        assertNotNull(tccResult);
        assertTrue(tccResult.isSuccess());

        // Verify both event systems captured their respective events
        assertFalse(sagaEvents.events.isEmpty());
        assertFalse(tccEvents.events.isEmpty());

        // Verify no cross-contamination
        assertTrue(sagaEvents.events.stream().noneMatch(e -> e.contains("tcc_")));
        assertTrue(tccEvents.events.stream().noneMatch(e -> e.contains("saga_")));
    }

    @Test
    void springConfigurationShouldNotHaveBeanConflicts() {
        // This test verifies that SAGA and TCC event systems can coexist
        // in the same Spring context without bean definition conflicts

        // Setup Spring context - this should not throw any exceptions
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);

        // Verify both engines are properly configured
        SagaEngine sagaEngine = ctx.getBean(SagaEngine.class);
        TccEngine tccEngine = ctx.getBean(TccEngine.class);

        assertNotNull(sagaEngine, "SagaEngine should be properly configured");
        assertNotNull(tccEngine, "TccEngine should be properly configured");

        // Verify event beans are properly isolated
        SagaEvents sagaEvents = ctx.getBean(SagaEvents.class);
        TccEvents tccEvents = ctx.getBean(TccEvents.class);

        assertNotNull(sagaEvents, "SagaEvents should be available");
        assertNotNull(tccEvents, "TccEvents should be available");

        // Verify they are our test implementations
        assertTrue(sagaEvents instanceof TestSagaEvents,
                "SagaEvents should be our test implementation");
        assertTrue(tccEvents instanceof TestTccEvents,
                "TccEvents should be our test implementation");

        // Verify engines use the correct event implementations by checking TccEngine
        assertEquals(tccEvents, tccEngine.getTccEvents(),
                "TccEngine should use the correct TccEvents implementation");

        // For SagaEngine, we can't directly check the events field, but we can verify
        // that the Spring context properly resolved the dependencies without conflicts
    }

    // Test SAGA implementation
    @Saga(name = "TestSaga")
    static class TestSaga {
        @SagaStep(id = "step1")
        public Mono<String> step1(String input) {
            return Mono.just("result1:" + input);
        }

        @SagaStep(id = "step2", dependsOn = {"step1"})
        public Mono<String> step2(@FromStep("step1") String input) {
            return Mono.just("result2:" + input);
        }
    }

    // Test TCC implementation
    @Tcc(name = "TestTcc")
    static class TestTcc {
        @TccParticipant(id = "participant1", order = 1)
        public static class Participant1 {
            @TryMethod
            public Mono<String> tryMethod(String input) {
                return Mono.just("try-result:" + input);
            }

            @ConfirmMethod
            public Mono<Void> confirmMethod(@FromTry String tryResult) {
                return Mono.empty();
            }

            @CancelMethod
            public Mono<Void> cancelMethod(@FromTry String tryResult) {
                return Mono.empty();
            }
        }
    }

    // Test event collectors
    static class TestSagaEvents implements SagaEvents {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onStart(String sagaName, String sagaId) {
            events.add("saga_started:" + sagaName);
        }

        @Override
        public void onStart(String sagaName, String sagaId, SagaContext ctx) {
            events.add("saga_started_with_context:" + sagaName);
        }

        @Override
        public void onStepStarted(String sagaName, String sagaId, String stepId) {
            events.add("step_started:" + stepId);
        }

        @Override
        public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {
            events.add("step_success:" + stepId);
        }

        @Override
        public void onCompleted(String sagaName, String sagaId, boolean success) {
            events.add("saga_completed:" + success);
        }
    }

    static class TestTccEvents implements TccEvents {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onTccStarted(String tccName, String correlationId) {
            events.add("tcc_started:" + tccName);
        }

        @Override
        public void onTccStarted(String tccName, String correlationId, TccContext context) {
            events.add("tcc_started_with_context:" + tccName);
        }

        @Override
        public void onPhaseStarted(String tccName, String correlationId, TccPhase phase) {
            events.add("phase_started:" + phase);
        }

        @Override
        public void onParticipantSuccess(String tccName, String correlationId, String participantId, TccPhase phase, int attempts, long durationMs) {
            events.add("participant_success:" + participantId + ":" + phase);
        }

        @Override
        public void onTccCompleted(String tccName, String correlationId, TccPhase finalPhase, long durationMs) {
            events.add("tcc_completed:" + finalPhase);
        }
    }
}
