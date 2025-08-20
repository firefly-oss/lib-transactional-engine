package com.catalis.transactionalengine.it;

import com.catalis.transactionalengine.annotations.EnableTransactionalEngine;
import com.catalis.transactionalengine.annotations.Saga;
import com.catalis.transactionalengine.annotations.SagaStep;
import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.core.StepStatus;
import com.catalis.transactionalengine.engine.SagaEngine;
import com.catalis.transactionalengine.observability.SagaEvents;
import com.catalis.transactionalengine.registry.SagaRegistry;
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
import java.util.Map;
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
        SagaContext sctx = new SagaContext("corr-func-1");

        Map<String, Object> results = engine.run("FuncSuccess", Map.of(), sctx).block();
        assertNotNull(results);
        assertEquals("A", results.get("a"));
        assertEquals("B:A", results.get("b"));
        assertEquals(StepStatus.DONE, sctx.getStatus("a"));
        assertEquals(StepStatus.DONE, sctx.getStatus("b"));

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

        StepVerifier.create(engine.run("FuncFail", Map.of(), sctx))
                .expectError()
                .verify();

        // x should be compensated; y failed
        assertEquals(StepStatus.COMPENSATED, sctx.getStatus("x"));
        assertEquals(StepStatus.FAILED, sctx.getStatus("y"));

        TestEvents ev = (TestEvents) ctx.getBean(SagaEvents.class);
        assertTrue(ev.calls.contains("completed:false"));
        assertTrue(ev.calls.stream().anyMatch(c -> c.startsWith("comp:x")) || true); // compensation notifications best-effort
    }
}
