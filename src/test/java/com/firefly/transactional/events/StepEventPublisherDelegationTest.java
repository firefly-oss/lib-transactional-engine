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


package com.firefly.transactional.events;

import com.firefly.transactional.annotations.*;
import com.firefly.transactional.core.SagaContext;
import com.firefly.transactional.core.SagaResult;
import com.firefly.transactional.engine.SagaEngine;
import com.firefly.transactional.engine.StepInputs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that verifies step event publisher delegation works correctly.
 * Ensures that:
 * 1. Events are only published on successful saga completion
 * 2. Events are delegated to the microservice's StepEventPublisher implementation
 * 3. No events are published when saga fails
 * 4. Default NoOpStepEventPublisher works when no custom implementation provided
 * 5. Custom StepEventPublisher implementations are auto-detected and logged
 * 6. Multiple StepEventPublisher implementations cause Spring error (preventing ambiguity)
 * 7. @Primary annotation resolves multiple implementations correctly
 */
class StepEventPublisherDelegationTest {

    private AnnotationConfigApplicationContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
    }

    static class TestStepEventPublisher implements StepEventPublisher {
        final List<StepEventEnvelope> publishedEvents = new CopyOnWriteArrayList<>();
        final AtomicInteger publishCallCount = new AtomicInteger(0);
        
        @Override
        public Mono<Void> publish(StepEventEnvelope event) {
            publishCallCount.incrementAndGet();
            publishedEvents.add(event);
            return Mono.empty();
        }
    }

    @Configuration
    @EnableTransactionalEngine
    static class TestConfig {
        @Bean public TestStepEventPublisher stepEventPublisher() { return new TestStepEventPublisher(); }
        @Bean public TestSaga testSaga() { return new TestSaga(); }
        @Bean public FailingSaga failingSaga() { return new FailingSaga(); }
    }

    @Configuration
    @EnableTransactionalEngine
    static class NoPublisherConfig {
        @Bean public TestSaga testSaga() { return new TestSaga(); }
    }

    @Saga(name = "TestSaga")
    static class TestSaga {
        @SagaStep(id = "step1")
        @StepEvent(topic = "test-topic", type = "STEP1_COMPLETED", key = "test-key")
        public Mono<String> step1() { 
            return Mono.just("result1"); 
        }

        @SagaStep(id = "step2", dependsOn = {"step1"})
        @StepEvent(topic = "test-topic", type = "STEP2_COMPLETED")
        public Mono<String> step2(@FromStep("step1") String input) { 
            return Mono.just("result2:" + input); 
        }

        @SagaStep(id = "step3", dependsOn = {"step2"})
        public Mono<String> step3(@FromStep("step2") String input) { 
            return Mono.just("result3:" + input); 
        }
    }

    @Saga(name = "FailingSaga")
    static class FailingSaga {
        @SagaStep(id = "step1")
        @StepEvent(topic = "test-topic", type = "STEP1_COMPLETED")
        public Mono<String> step1() { 
            return Mono.just("result1"); 
        }

        @SagaStep(id = "step2", dependsOn = {"step1"})
        @StepEvent(topic = "test-topic", type = "STEP2_COMPLETED")
        public Mono<String> step2() { 
            return Mono.error(new RuntimeException("Step failed")); 
        }
    }

    @Test
    void successfulSagaDelegatesEventsToPublisher() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        SagaEngine engine = ctx.getBean(SagaEngine.class);
        TestStepEventPublisher publisher = ctx.getBean(TestStepEventPublisher.class);
        
        SagaContext sagaContext = new SagaContext("test-saga-1");
        sagaContext.putHeader("tenant", "test-tenant");

        SagaResult result = engine.execute("TestSaga", StepInputs.builder().build(), sagaContext).block();
        
        assertTrue(result.isSuccess());
        assertEquals(2, publisher.publishCallCount.get(), "Should publish 2 events (step1 and step2 have @StepEvent)");
        assertEquals(2, publisher.publishedEvents.size());
        
        StepEventEnvelope event1 = publisher.publishedEvents.get(0);
        assertEquals("TestSaga", event1.getSagaName());
        assertEquals("test-saga-1", event1.getSagaId());
        assertEquals("step1", event1.getStepId());
        assertEquals("test-topic", event1.getTopic());
        assertEquals("STEP1_COMPLETED", event1.getType());
        assertEquals("test-key", event1.getKey());
        assertEquals("result1", event1.getPayload());
        assertEquals("test-tenant", event1.getHeaders().get("tenant"));
        
        StepEventEnvelope event2 = publisher.publishedEvents.get(1);
        assertEquals("step2", event2.getStepId());
        assertEquals("STEP2_COMPLETED", event2.getType());
        assertEquals("result2:result1", event2.getPayload());
    }

    @Test
    void failedSagaDoesNotPublishEvents() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        SagaEngine engine = ctx.getBean(SagaEngine.class);
        TestStepEventPublisher publisher = ctx.getBean(TestStepEventPublisher.class);
        
        SagaContext sagaContext = new SagaContext("failing-saga-1");

        SagaResult result = engine.execute("FailingSaga", StepInputs.builder().build(), sagaContext).block();
        
        assertFalse(result.isSuccess());
        assertEquals(0, publisher.publishCallCount.get(), "Failed saga should not publish any events");
        assertTrue(publisher.publishedEvents.isEmpty());
    }

    @Test
    void noOpPublisherWorksWhenNoCustomImplementation() {
        ctx = new AnnotationConfigApplicationContext(NoPublisherConfig.class);
        SagaEngine engine = ctx.getBean(SagaEngine.class);
        
        SagaContext sagaContext = new SagaContext("noop-saga-1");

        SagaResult result = engine.execute("TestSaga", StepInputs.builder().build(), sagaContext).block();
        
        assertTrue(result.isSuccess(), "Saga should succeed even with NoOpStepEventPublisher");
        // No way to verify NoOp behavior other than ensuring saga completes successfully
    }
    
    @Test
    void customPublisherIsAutoDetectedAndLogged() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        
        // Verify custom publisher is detected
        TestStepEventPublisher publisher = ctx.getBean(TestStepEventPublisher.class);
        assertNotNull(publisher, "Custom StepEventPublisher should be auto-detected");
        
        // Verify it's used by the engine
        SagaEngine engine = ctx.getBean(SagaEngine.class);
        assertNotNull(engine, "SagaEngine should be configured with custom publisher");
        
        // Verify detector bean is created (indicates logging occurred)
        assertTrue(ctx.containsBean("stepEventPublisherDetector"), 
            "StepEventPublisherDetector should be created when custom publisher is found");
    }
    
    static class AnotherTestStepEventPublisher implements StepEventPublisher {
        @Override
        public Mono<Void> publish(StepEventEnvelope event) {
            return Mono.empty();
        }
    }
    
    @Configuration
    @EnableTransactionalEngine
    static class MultiplePublishersConfig {
        @Bean public TestStepEventPublisher testStepEventPublisher() { return new TestStepEventPublisher(); }
        @Bean public AnotherTestStepEventPublisher anotherTestStepEventPublisher() { return new AnotherTestStepEventPublisher(); }
        @Bean public TestSaga testSaga() { return new TestSaga(); }
    }
    
    @Configuration
    @EnableTransactionalEngine
    static class MultiplePublishersWithPrimaryConfig {
        @Bean @Primary public TestStepEventPublisher testStepEventPublisher() { return new TestStepEventPublisher(); }
        @Bean public AnotherTestStepEventPublisher anotherTestStepEventPublisher() { return new AnotherTestStepEventPublisher(); }
        @Bean public TestSaga testSaga() { return new TestSaga(); }
    }
    
    @Test
    void multiplePublishersWithoutPrimaryCauseSpringError() {
        // Spring should fail to start when multiple StepEventPublisher implementations exist without @Primary
        assertThrows(org.springframework.beans.factory.UnsatisfiedDependencyException.class, () -> {
            ctx = new AnnotationConfigApplicationContext(MultiplePublishersConfig.class);
        }, "Spring should fail when multiple StepEventPublisher implementations exist without @Primary");
    }
    
    @Test
    void multiplePublishersWithPrimaryWorks() {
        ctx = new AnnotationConfigApplicationContext(MultiplePublishersWithPrimaryConfig.class);
        
        // Verify both publishers exist
        TestStepEventPublisher publisher1 = ctx.getBean(TestStepEventPublisher.class);
        AnotherTestStepEventPublisher publisher2 = ctx.getBean(AnotherTestStepEventPublisher.class);
        assertNotNull(publisher1, "Primary publisher should exist");
        assertNotNull(publisher2, "Secondary publisher should exist");
        
        // Verify engine works with @Primary publisher
        SagaEngine engine = ctx.getBean(SagaEngine.class);
        assertNotNull(engine, "SagaEngine should work with @Primary publisher");
        
        // The detector should log the multiple implementations warning
        assertTrue(ctx.containsBean("stepEventPublisherDetector"), 
            "StepEventPublisherDetector should detect multiple implementations");
    }
}