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


package com.firefly.transactional.registry;

import com.firefly.transactional.annotations.ExternalSagaStep;
import com.firefly.transactional.annotations.Saga;
import com.firefly.transactional.annotations.SagaStep;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalSagaStepDuplicateIdTest {

    @Configuration
    static class AppConfig {
        @Bean public Orchestrator orchestrator() { return new Orchestrator(); }
        @Bean public ExternalSteps externalSteps() { return new ExternalSteps(); }
    }

    @Saga(name = "DupSaga")
    static class Orchestrator {
        @SagaStep(id = "a", compensate = "u")
        public Mono<String> a() { return Mono.just("in-class"); }
        public Mono<Void> u(String res) { return Mono.empty(); }
    }

    static class ExternalSteps {
        @ExternalSagaStep(saga = "DupSaga", id = "a")
        public Mono<String> a() { return Mono.just("external"); }
    }

    @Test
    void duplicate_step_id_between_in_class_and_external_throws() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        try {
            SagaRegistry reg = new SagaRegistry(ctx);
            IllegalStateException ex = assertThrows(IllegalStateException.class, reg::getAll);
            assertTrue(ex.getMessage().contains("Duplicate step id 'a'"));
        } finally {
            ctx.close();
        }
    }
}
