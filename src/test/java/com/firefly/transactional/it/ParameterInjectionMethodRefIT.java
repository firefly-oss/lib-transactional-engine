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


package com.firefly.transactional.it;

import com.firefly.transactional.saga.annotations.FromStep;
import com.firefly.transactional.saga.annotations.Saga;
import com.firefly.transactional.saga.annotations.SagaStep;
import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.saga.core.SagaResult;
import com.firefly.transactional.saga.engine.SagaEngine;
import com.firefly.transactional.saga.engine.StepInputs;
import com.firefly.transactional.saga.registry.SagaRegistry;
import com.firefly.transactional.shared.annotations.EnableTransactionalEngine;
import com.firefly.transactional.shared.annotations.Header;
import com.firefly.transactional.shared.annotations.Headers;
import com.firefly.transactional.shared.annotations.Input;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParameterInjectionMethodRefIT {

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

    @Saga(name = "ParamSagaMR")
    static class Orchestrator {
        @SagaStep(id = "r1", compensate = "cr1")
        public Mono<String> r1() { return Mono.just("R1"); }
        public Mono<Void> cr1(String res) { return Mono.empty(); }

        @SagaStep(id = "p1", compensate = "cp1", dependsOn = {"r1"})
        public Mono<String> p1(@FromStep("r1") String r1, @Header("X-User-Id") String user, SagaContext ctx) {
            return Mono.just("P1:" + r1 + ":" + user + ":" + ctx.correlationId());
        }
        public Mono<Void> cp1(String res, SagaContext ctx) { return Mono.empty(); }

        @SagaStep(id = "c1", compensate = "cc1", dependsOn = {"p1"})
        public Mono<String> c1(@Headers Map<String,String> headers, @Input("extra") String extra) {
            return Mono.just("C1:" + headers.get("X-User-Id") + ":" + extra);
        }
        public Mono<Void> cc1(String res) { return Mono.empty(); }
    }

    @Test
    void method_reference_overload_compiles_and_runs() {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        SagaRegistry reg = ctx.getBean(SagaRegistry.class);
        assertNotNull(reg.getSaga("ParamSagaMR"));

        SagaEngine engine = ctx.getBean(SagaEngine.class);
        SagaContext sctx = new SagaContext("corr-param-mr-1");
        sctx.putHeader("X-User-Id", "u1");

        // Use Class::method directly
        StepInputs inputs = StepInputs.builder()
                .forStep(Orchestrator::c1, Map.of("extra", "E"))
                .build();

        SagaResult result = engine.execute("ParamSagaMR", inputs, sctx).block();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("R1", result.resultOf("r1", String.class).orElse(null));
        assertTrue(result.resultOf("p1", String.class).orElse("").startsWith("P1:R1:u1:"));
        assertEquals("C1:u1:E", result.resultOf("c1", String.class).orElse(null));
    }
}
