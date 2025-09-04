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


package com.firefly.transactionalengine.registry;

import com.firefly.transactionalengine.annotations.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class RegistryParameterValidationTest {

    @Configuration
    static class BadHeaderTypeConfig { @Bean public BadHeaderType s() { return new BadHeaderType(); } }

    @Saga(name = "BadHeaderType")
    static class BadHeaderType {
        @SagaStep(id = "a", compensate = "u")
        public Mono<String> a(@Header("X-User-Id") Integer wrong) { return Mono.just("x"); }
        public void u() {}
    }

    @Test
    void headerWithWrongTypeFailsStartup() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(BadHeaderTypeConfig.class);
        SagaRegistry reg = new SagaRegistry(ctx);
        IllegalStateException ex = assertThrows(IllegalStateException.class, reg::getAll);
        assertTrue(ex.getMessage().contains("@Header"));
        ctx.close();
    }

    @Configuration
    static class MissingFromStepConfig { @Bean public MissingFromStep s() { return new MissingFromStep(); } }

    @Saga(name = "MissingFrom")
    static class MissingFromStep {
        @SagaStep(id = "a", compensate = "u")
        public Mono<String> a(@FromStep("nope") String missing) { return Mono.just("x"); }
        public void u() {}
    }

    @Test
    void fromStepMissingReferenceFailsStartup() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(MissingFromStepConfig.class);
        SagaRegistry reg = new SagaRegistry(ctx);
        IllegalStateException ex = assertThrows(IllegalStateException.class, reg::getAll);
        assertTrue(ex.getMessage().contains("references missing step"));
        ctx.close();
    }
}
