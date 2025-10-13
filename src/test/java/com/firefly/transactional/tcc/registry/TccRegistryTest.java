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

package com.firefly.transactional.tcc.registry;

import com.firefly.transactional.tcc.annotations.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TccRegistry.
 */
class TccRegistryTest {
    
    @Configuration
    static class TestConfig {
        @Bean
        public SimpleTcc simpleTcc() {
            return new SimpleTcc();
        }
        
        @Bean
        public MultiParticipantTcc multiParticipantTcc() {
            return new MultiParticipantTcc();
        }
    }
    
    @Tcc(name = "SimpleTcc", timeoutMs = 5000, retryEnabled = true, maxRetries = 3)
    static class SimpleTcc {
        @TccParticipant(id = "participant1", order = 1, timeoutMs = 3000)
        public static class Participant1 {
            @TryMethod(timeoutMs = 1000, retry = 2)
            public Mono<String> tryMethod() {
                return Mono.just("result");
            }
            
            @ConfirmMethod(retry = 3)
            public Mono<Void> confirmMethod(@FromTry String result) {
                return Mono.empty();
            }
            
            @CancelMethod(retry = 3)
            public Mono<Void> cancelMethod(@FromTry String result) {
                return Mono.empty();
            }
        }
    }
    
    @Tcc(name = "MultiParticipantTcc")
    static class MultiParticipantTcc {
        @TccParticipant(id = "p1", order = 1)
        public static class Participant1 {
            @TryMethod
            public Mono<String> tryMethod() {
                return Mono.just("p1-result");
            }
            
            @ConfirmMethod
            public Mono<Void> confirmMethod(@FromTry String result) {
                return Mono.empty();
            }
            
            @CancelMethod
            public Mono<Void> cancelMethod(@FromTry String result) {
                return Mono.empty();
            }
        }
        
        @TccParticipant(id = "p2", order = 2)
        public static class Participant2 {
            @TryMethod
            public Mono<Integer> tryMethod() {
                return Mono.just(42);
            }
            
            @ConfirmMethod
            public Mono<Void> confirmMethod(@FromTry Integer result) {
                return Mono.empty();
            }
            
            @CancelMethod
            public Mono<Void> cancelMethod(@FromTry Integer result) {
                return Mono.empty();
            }
        }
        
        @TccParticipant(id = "p3", order = 3, optional = true)
        public static class Participant3 {
            @TryMethod
            public Mono<Boolean> tryMethod() {
                return Mono.just(true);
            }
            
            @ConfirmMethod
            public Mono<Void> confirmMethod(@FromTry Boolean result) {
                return Mono.empty();
            }
            
            @CancelMethod
            public Mono<Void> cancelMethod(@FromTry Boolean result) {
                return Mono.empty();
            }
        }
    }
    
    @Test
    void shouldDiscoverAndRegisterTccCoordinators() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            TccRegistry registry = new TccRegistry(ctx);
            
            assertTrue(registry.hasTcc("SimpleTcc"));
            assertTrue(registry.hasTcc("MultiParticipantTcc"));
        }
    }
    
    @Test
    void shouldRegisterTccWithCorrectMetadata() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            TccRegistry registry = new TccRegistry(ctx);
            
            TccDefinition tccDef = registry.getTcc("SimpleTcc");
            
            assertEquals("SimpleTcc", tccDef.name);
            assertEquals(5000, tccDef.timeoutMs);
            assertTrue(tccDef.retryEnabled);
            assertEquals(3, tccDef.maxRetries);
        }
    }
    
    @Test
    void shouldRegisterParticipantsWithCorrectOrder() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            TccRegistry registry = new TccRegistry(ctx);
            
            TccDefinition tccDef = registry.getTcc("MultiParticipantTcc");
            
            assertEquals(3, tccDef.participants.size());
            assertTrue(tccDef.participants.containsKey("p1"));
            assertTrue(tccDef.participants.containsKey("p2"));
            assertTrue(tccDef.participants.containsKey("p3"));
            
            TccParticipantDefinition p1 = tccDef.getParticipant("p1");
            assertEquals(1, p1.order);
            assertFalse(p1.optional);
            
            TccParticipantDefinition p2 = tccDef.getParticipant("p2");
            assertEquals(2, p2.order);
            assertFalse(p2.optional);
            
            TccParticipantDefinition p3 = tccDef.getParticipant("p3");
            assertEquals(3, p3.order);
            assertTrue(p3.optional);
        }
    }
    
    @Test
    void shouldRegisterParticipantMethodsCorrectly() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            TccRegistry registry = new TccRegistry(ctx);
            
            TccDefinition tccDef = registry.getTcc("SimpleTcc");
            TccParticipantDefinition participant = tccDef.getParticipant("participant1");
            
            assertNotNull(participant.tryMethod);
            assertEquals("tryMethod", participant.tryMethod.getName());
            
            assertNotNull(participant.confirmMethod);
            assertEquals("confirmMethod", participant.confirmMethod.getName());
            
            assertNotNull(participant.cancelMethod);
            assertEquals("cancelMethod", participant.cancelMethod.getName());
        }
    }
    
    @Test
    void shouldRegisterParticipantTimeoutConfiguration() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            TccRegistry registry = new TccRegistry(ctx);
            
            TccDefinition tccDef = registry.getTcc("SimpleTcc");
            TccParticipantDefinition participant = tccDef.getParticipant("participant1");
            
            assertEquals(3000, participant.timeoutMs);
            assertEquals(1000, participant.tryTimeoutMs);
            assertEquals(-1, participant.confirmTimeoutMs); // Not specified
            assertEquals(-1, participant.cancelTimeoutMs); // Not specified
        }
    }
    
    @Test
    void shouldRegisterParticipantRetryConfiguration() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            TccRegistry registry = new TccRegistry(ctx);
            
            TccDefinition tccDef = registry.getTcc("SimpleTcc");
            TccParticipantDefinition participant = tccDef.getParticipant("participant1");
            
            assertEquals(2, participant.tryRetry);
            assertEquals(3, participant.confirmRetry);
            assertEquals(3, participant.cancelRetry);
        }
    }
    
    @Test
    void shouldThrowExceptionForNonExistentTcc() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            TccRegistry registry = new TccRegistry(ctx);
            
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
                registry.getTcc("NonExistentTcc");
            });
            
            assertTrue(exception.getMessage().contains("No TCC found with name: NonExistentTcc"));
        }
    }
    
    @Test
    void shouldGetAllRegisteredTccs() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            TccRegistry registry = new TccRegistry(ctx);
            
            var allTccs = registry.getAllTccs();
            
            assertEquals(2, allTccs.size());
            assertTrue(allTccs.containsKey("SimpleTcc"));
            assertTrue(allTccs.containsKey("MultiParticipantTcc"));
        }
    }
}

