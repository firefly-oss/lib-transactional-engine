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

import com.firefly.transactional.annotations.CompensationSagaStep;
import com.firefly.transactional.annotations.Saga;
import com.firefly.transactional.annotations.SagaStep;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class ExternalCompensationRegistryTest {

    @Configuration
    static class AppConfig {
        @Bean public Orchestrator orchestrator() { return new Orchestrator(); }
        @Bean public CompensationSteps compensationSteps() { return new CompensationSteps(); }
    }

    @Saga(name = "ExtSaga")
    static class Orchestrator {
        @SagaStep(id = "a", compensate = "")
        public Mono<String> a() { return Mono.just("ok"); }
    }

    static class CompensationSteps {
        @CompensationSagaStep(saga = "ExtSaga", forStepId = "a")
        public Mono<Void> undoA(String res) { return Mono.empty(); }
    }

    @Test
    void registry_wires_external_compensation_to_step() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        try {
            SagaRegistry reg = new SagaRegistry(ctx);
            SagaDefinition def = reg.getSaga("ExtSaga");
            assertNotNull(def);
            StepDefinition sd = def.steps.get("a");
            assertNotNull(sd);
            assertNotNull(sd.compensateInvocationMethod, "compensateInvocationMethod should be set by external mapping");
            assertEquals("undoA", sd.compensateMethod.getName());
            Object compBean = ctx.getBean(CompensationSteps.class);
            assertSame(compBean, sd.compensateBean, "compensateBean should be the external bean instance");
        } finally {
            ctx.close();
        }
    }
}
