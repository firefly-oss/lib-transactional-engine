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


package com.firefly.transactionalengine.it;

import com.firefly.transactionalengine.annotations.*;
import com.firefly.transactionalengine.core.SagaContext;
import com.firefly.transactionalengine.core.SagaResult;
import com.firefly.transactionalengine.engine.SagaEngine;
import com.firefly.transactionalengine.engine.StepInputs;
import com.firefly.transactionalengine.registry.SagaRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class NewAnnotationsIT {

    private AnnotationConfigApplicationContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
    }

    @Configuration
    @EnableTransactionalEngine
    static class AppConfig {
        @Bean public Orchestrator orchestrator() { return new Orchestrator(); }
    }

    @Saga(name = "NewAnnoSaga")
    static class Orchestrator {
        @SagaStep(id = "a", compensate = "ua")
        public Mono<String> a() { return Mono.just("A"); }
        public Mono<Void> ua(@CompensationError("b") Throwable err, SagaContext ctx) {
            // store error message (if any) for assertion
            if (err != null) ctx.putVariable("compErrB", err.getMessage());
            return Mono.empty();
        }

        @SagaStep(id = "b", compensate = "ub", dependsOn = {"a"})
        public Mono<String> b(@Required @Header("X-User") String user) {
            return Mono.just("B:" + user);
        }
        public Mono<Void> ub(String res) { return Mono.error(new RuntimeException("boomB")); }

        // Introduce a downstream step that fails to trigger compensation for b
        @SagaStep(id = "c", dependsOn = {"b"})
        public Mono<Void> c() { return Mono.error(new RuntimeException("failC")); }
    }

    @Test
    void requiredParameterCausesFailureWhenMissing() {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        SagaRegistry reg = ctx.getBean(SagaRegistry.class);
        assertNotNull(reg.getSaga("NewAnnoSaga"));

        SagaEngine engine = ctx.getBean(SagaEngine.class);
        SagaContext sctx = new SagaContext("corr-new-1");
        // Intentionally do not set header X-User
        SagaResult result = engine.execute("NewAnnoSaga", StepInputs.builder().build(), sctx).block();
        assertNotNull(result);
        assertFalse(result.isSuccess(), "Saga should fail due to @Required header missing");
        // No compensation error for b should be present (its compensation did not run)
        assertNull(sctx.getCompensationError("b"));
    }

    @Test
    void compensationErrorInjectedIntoAnotherCompensation() {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        SagaEngine engine = ctx.getBean(SagaEngine.class);
        SagaContext sctx = new SagaContext("corr-new-2");
        // Provide required header so step b executes; step c will fail to trigger compensations
        sctx.putHeader("X-User", "john");
        SagaResult result = engine.execute("NewAnnoSaga", StepInputs.builder().build(), sctx).block();
        assertNotNull(result);
        assertFalse(result.isSuccess());
        // a's compensation should have received the error from b's compensation and stored it as variable compErrB
        assertEquals("boomB", sctx.getVariable("compErrB"));
    }
}
