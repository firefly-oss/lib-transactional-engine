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

package com.firefly.transactional.tcc.it;

import com.firefly.transactional.shared.annotations.EnableTransactionalEngine;
import com.firefly.transactional.tcc.observability.TccEvents;
import com.firefly.transactional.tcc.core.TccContext;
import com.firefly.transactional.tcc.annotations.*;
import com.firefly.transactional.tcc.core.TccInputs;
import com.firefly.transactional.tcc.core.TccPhase;
import com.firefly.transactional.tcc.engine.TccEngine;
import com.firefly.transactional.tcc.registry.TccRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for TCC Engine with Spring context.
 */
class TccEngineIntegrationTest {
    
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
        public SuccessfulTcc successfulTcc() {
            return new SuccessfulTcc();
        }
        
        @Bean
        public FailingTcc failingTcc() {
            return new FailingTcc();
        }
        
        @Bean
        @Primary
        public TccEvents testEvents() {
            return new TestEvents();
        }
    }

    static class TestEvents implements TccEvents {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void onTccStarted(String tccName, String correlationId, TccContext context) {
            calls.add("start:" + tccName);
        }

        @Override
        public void onTccCompleted(String tccName, String correlationId, TccPhase finalPhase, long durationMs) {
            calls.add("completed:" + (finalPhase == TccPhase.CONFIRM));
        }

        @Override
        public void onParticipantStarted(String tccName, String correlationId, String participantId, TccPhase phase) {
            calls.add("participant-started:" + participantId + ":" + phase);
        }

        @Override
        public void onParticipantSuccess(String tccName, String correlationId, String participantId, TccPhase phase, int attempts, long durationMs) {
            calls.add("participant-success:" + participantId + ":" + phase);
        }

        @Override
        public void onParticipantFailed(String tccName, String correlationId, String participantId, TccPhase phase, Throwable error, int attempts, long durationMs) {
            calls.add("participant-failed:" + participantId + ":" + phase);
        }
    }
    
    @Tcc(name = "SuccessfulTcc")
    static class SuccessfulTcc {
        static final AtomicInteger tryCount = new AtomicInteger(0);
        static final AtomicInteger confirmCount = new AtomicInteger(0);
        static final AtomicInteger cancelCount = new AtomicInteger(0);
        
        @TccParticipant(id = "participant1", order = 1)
        public static class Participant1 {
            @TryMethod
            public Mono<String> tryMethod() {
                tryCount.incrementAndGet();
                return Mono.just("try-result-1");
            }
            
            @ConfirmMethod
            public Mono<Void> confirmMethod(@FromTry String tryResult) {
                confirmCount.incrementAndGet();
                assertEquals("try-result-1", tryResult);
                return Mono.empty();
            }
            
            @CancelMethod
            public Mono<Void> cancelMethod(@FromTry String tryResult) {
                cancelCount.incrementAndGet();
                return Mono.empty();
            }
        }
        
        @TccParticipant(id = "participant2", order = 2)
        public static class Participant2 {
            @TryMethod
            public Mono<String> tryMethod() {
                tryCount.incrementAndGet();
                return Mono.just("try-result-2");
            }
            
            @ConfirmMethod
            public Mono<Void> confirmMethod(@FromTry String tryResult) {
                confirmCount.incrementAndGet();
                assertEquals("try-result-2", tryResult);
                return Mono.empty();
            }
            
            @CancelMethod
            public Mono<Void> cancelMethod(@FromTry String tryResult) {
                cancelCount.incrementAndGet();
                return Mono.empty();
            }
        }
    }
    
    @Tcc(name = "FailingTcc")
    static class FailingTcc {
        static final AtomicInteger tryCount = new AtomicInteger(0);
        static final AtomicInteger confirmCount = new AtomicInteger(0);
        static final AtomicInteger cancelCount = new AtomicInteger(0);
        
        @TccParticipant(id = "participant1", order = 1)
        public static class Participant1 {
            @TryMethod
            public Mono<String> tryMethod() {
                tryCount.incrementAndGet();
                return Mono.just("try-result-1");
            }
            
            @ConfirmMethod
            public Mono<Void> confirmMethod(@FromTry String tryResult) {
                confirmCount.incrementAndGet();
                return Mono.empty();
            }
            
            @CancelMethod
            public Mono<Void> cancelMethod(@FromTry String tryResult) {
                cancelCount.incrementAndGet();
                return Mono.empty();
            }
        }
        
        @TccParticipant(id = "participant2", order = 2)
        public static class Participant2 {
            @TryMethod
            public Mono<String> tryMethod() {
                tryCount.incrementAndGet();
                return Mono.error(new RuntimeException("Try failed"));
            }
            
            @ConfirmMethod
            public Mono<Void> confirmMethod(@FromTry String tryResult) {
                confirmCount.incrementAndGet();
                return Mono.empty();
            }
            
            @CancelMethod
            public Mono<Void> cancelMethod(@FromTry String tryResult) {
                cancelCount.incrementAndGet();
                return Mono.empty();
            }
        }
    }
    
    @Test
    void shouldRegisterTccCoordinators() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        TccRegistry registry = ctx.getBean(TccRegistry.class);
        
        assertNotNull(registry);
        assertTrue(registry.hasTcc("SuccessfulTcc"));
        assertTrue(registry.hasTcc("FailingTcc"));
    }
    
    @Test
    void shouldExecuteSuccessfulTccTransaction() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        TccEngine engine = ctx.getBean(TccEngine.class);
        
        // Reset counters
        SuccessfulTcc.tryCount.set(0);
        SuccessfulTcc.confirmCount.set(0);
        SuccessfulTcc.cancelCount.set(0);
        
        TccInputs inputs = TccInputs.empty();
        
        StepVerifier.create(engine.execute("SuccessfulTcc", inputs))
                .assertNext(result -> {
                    assertTrue(result.isSuccess());
                    assertTrue(result.isConfirmed());
                    assertFalse(result.isCanceled());
                    assertEquals(TccPhase.CONFIRM, result.getFinalPhase());
                    assertEquals("SuccessfulTcc", result.getTccName());
                    
                    // Check try results
                    assertEquals("try-result-1", result.getTryResult("participant1"));
                    assertEquals("try-result-2", result.getTryResult("participant2"));
                    
                    // Check participant results
                    assertNotNull(result.getParticipantResult("participant1"));
                    assertTrue(result.getParticipantResult("participant1").isTrySucceeded());
                    assertTrue(result.getParticipantResult("participant1").isConfirmSucceeded());
                    
                    assertNotNull(result.getParticipantResult("participant2"));
                    assertTrue(result.getParticipantResult("participant2").isTrySucceeded());
                    assertTrue(result.getParticipantResult("participant2").isConfirmSucceeded());
                })
                .verifyComplete();
        
        // Verify execution counts
        assertEquals(2, SuccessfulTcc.tryCount.get(), "Both try methods should be called");
        assertEquals(2, SuccessfulTcc.confirmCount.get(), "Both confirm methods should be called");
        assertEquals(0, SuccessfulTcc.cancelCount.get(), "Cancel methods should not be called");
        
        // Verify events
        TestEvents events = (TestEvents) ctx.getBean("testEvents");
        assertTrue(events.calls.contains("start:SuccessfulTcc"));
        assertTrue(events.calls.contains("completed:true"));
    }
    
    @Test
    void shouldExecuteFailingTccTransactionWithCancellation() {
        ctx = new AnnotationConfigApplicationContext(TestConfig.class);
        TccEngine engine = ctx.getBean(TccEngine.class);
        
        // Reset counters
        FailingTcc.tryCount.set(0);
        FailingTcc.confirmCount.set(0);
        FailingTcc.cancelCount.set(0);
        
        TccInputs inputs = TccInputs.empty();
        
        StepVerifier.create(engine.execute("FailingTcc", inputs))
                .assertNext(result -> {
                    assertFalse(result.isSuccess());
                    assertFalse(result.isConfirmed());
                    assertTrue(result.isCanceled());
                    assertEquals(TccPhase.CANCEL, result.getFinalPhase());
                    assertEquals("FailingTcc", result.getTccName());
                    
                    // Check that participant1 succeeded but participant2 failed
                    assertEquals("try-result-1", result.getTryResult("participant1"));
                    assertNull(result.getTryResult("participant2"));
                    
                    // Check participant results
                    assertNotNull(result.getParticipantResult("participant1"));
                    assertTrue(result.getParticipantResult("participant1").isTrySucceeded());
                    assertTrue(result.getParticipantResult("participant1").isCancelSucceeded());
                    
                    assertNotNull(result.getParticipantResult("participant2"));
                    assertFalse(result.getParticipantResult("participant2").isTrySucceeded());
                })
                .verifyComplete();
        
        // Verify execution counts
        assertEquals(2, FailingTcc.tryCount.get(), "Both try methods should be attempted");
        assertEquals(0, FailingTcc.confirmCount.get(), "Confirm methods should not be called");
        assertEquals(1, FailingTcc.cancelCount.get(), "Only participant1 should be canceled");
        
        // Verify events
        TestEvents events = (TestEvents) ctx.getBean("testEvents");
        assertTrue(events.calls.contains("start:FailingTcc"));
        assertTrue(events.calls.contains("completed:false"));
    }
}

