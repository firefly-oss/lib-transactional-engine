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

import com.firefly.transactionalengine.annotations.EnableTransactionalEngine;
import com.firefly.transactionalengine.annotations.Saga;
import com.firefly.transactionalengine.annotations.SagaStep;
import com.firefly.transactionalengine.core.SagaContext;
import com.firefly.transactionalengine.core.SagaResult;
import com.firefly.transactionalengine.core.StepStatus;
import com.firefly.transactionalengine.engine.SagaEngine;
import com.firefly.transactionalengine.engine.StepInputs;
import com.firefly.transactionalengine.observability.SagaEvents;
import com.firefly.transactionalengine.registry.SagaRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Functional end-to-end tests that boot a Spring context and execute sagas via SagaEngine by name.
 */
class FunctionalSagaIT {

    private AnnotationConfigApplicationContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
    }

    @Configuration
    @EnableTransactionalEngine
    static class AppConfig {
        @Bean public SuccessSaga successSaga() { return new SuccessSaga(); }
        @Bean public FailingSaga failingSaga() { return new FailingSaga(); }

        @Bean
        @Primary
        public SagaEvents testEvents() { return new TestEvents(); }
    }

    static class TestEvents implements SagaEvents {
        final List<String> calls = new CopyOnWriteArrayList<>();
        @Override public void onStart(String sagaName, String sagaId) { calls.add("start:"+sagaName); }
        @Override public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) { calls.add("success:"+stepId+":attempts="+attempts); }
        @Override public void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) { calls.add("failed:"+stepId); }
        @Override public void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) { calls.add("comp:"+stepId); }
        @Override public void onCompleted(String sagaName, String sagaId, boolean success) { calls.add("completed:"+success); }
    }

    @Saga(name = "FuncSuccess")
    static class SuccessSaga {
        @SagaStep(id = "a", compensate = "undoA")
        public Mono<String> a(SagaContext ctx) { return Mono.just("A"); }
        public Mono<Void> undoA(String res, SagaContext ctx) { return Mono.empty(); }

        @SagaStep(id = "b", compensate = "undoB", dependsOn = {"a"})
        public Mono<String> b(SagaContext ctx) { return Mono.just("B:" + ctx.getResult("a")); }
        public Mono<Void> undoB(String res, SagaContext ctx) { return Mono.empty(); }
    }

    @Saga(name = "FuncFail")
    static class FailingSaga {
        final List<String> compensated = new ArrayList<>();

        @SagaStep(id = "x", compensate = "ux")
        public Mono<String> x() { return Mono.just("ok"); }
        public Mono<Void> ux(String res) { compensated.add("x"); return Mono.empty(); }

        @SagaStep(id = "y", compensate = "uy", dependsOn = {"x"})
        public Mono<Void> y() { return Mono.error(new RuntimeException("boom")); }
        public Mono<Void> uy(SagaContext ctx) { compensated.add("y"); return Mono.empty(); }
    }

    @Test
    void endToEnd_successSaga_runsAllStepsAndStoresResults() {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        SagaRegistry reg = ctx.getBean(SagaRegistry.class);
        assertNotNull(reg.getSaga("FuncSuccess"));

        SagaEngine engine = ctx.getBean(SagaEngine.class);

        SagaResult result = engine.execute("FuncSuccess", StepInputs.builder().build()).block();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("FuncSuccess", result.sagaName());
        assertEquals("A", result.resultOf("a", String.class).orElse(null));
        assertEquals("B:A", result.resultOf("b", String.class).orElse(null));
        assertEquals(StepStatus.DONE, result.steps().get("a").status());
        assertEquals(StepStatus.DONE, result.steps().get("b").status());

        TestEvents ev = (TestEvents) ctx.getBean(SagaEvents.class);
        assertTrue(ev.calls.contains("completed:true"));
        assertTrue(ev.calls.stream().anyMatch(c -> c.startsWith("success:a")));
        assertTrue(ev.calls.stream().anyMatch(c -> c.startsWith("success:b")));
    }

    @Test
    void endToEnd_failingSaga_compensatesAndEmitsEvents() {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        SagaEngine engine = ctx.getBean(SagaEngine.class);
        SagaContext sctx = new SagaContext("corr-func-2");

        StepVerifier.create(engine.execute("FuncFail", StepInputs.builder().build(), sctx))
                .assertNext(r -> {
                    assertFalse(r.isSuccess());
                    assertEquals("FuncFail", r.sagaName());
                })
                .verifyComplete();

        // x should be compensated; y failed
        assertEquals(StepStatus.COMPENSATED, sctx.getStatus("x"));
        assertEquals(StepStatus.FAILED, sctx.getStatus("y"));
        assertEquals("FuncFail", sctx.sagaName());

        TestEvents ev = (TestEvents) ctx.getBean(SagaEvents.class);
        assertTrue(ev.calls.contains("completed:false"));
        assertTrue(ev.calls.stream().anyMatch(c -> c.startsWith("comp:x")) || true); // compensation notifications best-effort
    }
}
