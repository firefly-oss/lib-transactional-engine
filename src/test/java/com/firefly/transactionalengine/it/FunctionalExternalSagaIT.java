package com.firefly.transactionalengine.it;

import com.firefly.transactionalengine.annotations.EnableTransactionalEngine;
import com.firefly.transactionalengine.annotations.ExternalSagaStep;
import com.firefly.transactionalengine.annotations.Saga;
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class FunctionalExternalSagaIT {

    private AnnotationConfigApplicationContext ctx;

    @AfterEach
    void tearDown() { if (ctx != null) ctx.close(); }

    @Configuration
    @EnableTransactionalEngine
    static class AppConfig {
        @Bean public Orchestrator orchestrator() { return new Orchestrator(); }
        @Bean public ExternalFlow steps() { return new ExternalFlow(); }
        @Bean @Primary public SagaEvents events() { return new TestEvents(); }
    }

    static class TestEvents implements SagaEvents {
        final List<String> calls = new CopyOnWriteArrayList<>();
        @Override public void onStart(String sagaName, String sagaId) { calls.add("start:"+sagaName); }
        @Override public void onCompleted(String sagaName, String sagaId, boolean success) { calls.add("completed:"+success); }
    }

    @Saga(name = "ExtFunc")
    static class Orchestrator { }

    static class ExternalFlow {
        @ExternalSagaStep(saga = "ExtFunc", id = "a", compensate = "ua")
        public Mono<String> a() { return Mono.just("A"); }
        public Mono<Void> ua(String res) { return Mono.empty(); }

        @ExternalSagaStep(saga = "ExtFunc", id = "b", dependsOn = {"a"}, compensate = "ub")
        public Mono<String> b() { return Mono.just("B"); }
        public Mono<Void> ub(String res) { return Mono.empty(); }

        @ExternalSagaStep(saga = "ExtFunc", id = "c", dependsOn = {"b"}, compensate = "uc")
        public Mono<Void> c() { return Mono.empty(); }
        public Mono<Void> uc() { return Mono.empty(); }
    }

    @Test
    void endToEnd_externalSteps_success() {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        SagaRegistry reg = ctx.getBean(SagaRegistry.class);
        assertNotNull(reg.getSaga("ExtFunc"));
        SagaEngine engine = ctx.getBean(SagaEngine.class);
        SagaResult r = engine.execute("ExtFunc", StepInputs.builder().build()).block();
        assertNotNull(r);
        assertTrue(r.isSuccess());
        assertEquals("A", r.resultOf("a", String.class).orElse(null));
        assertEquals("B", r.resultOf("b", String.class).orElse(null));
        assertEquals(StepStatus.DONE, r.steps().get("a").status());
        assertEquals(StepStatus.DONE, r.steps().get("b").status());
        assertEquals(StepStatus.DONE, r.steps().get("c").status());
    }

    @Configuration
    @EnableTransactionalEngine
    static class FailingAppConfig {
        @Bean public Orchestrator orchestrator() { return new Orchestrator(); }
        @Bean public FailingExternalFlow steps() { return new FailingExternalFlow(); }
        @Bean @Primary public SagaEvents events() { return new TestEvents(); }
    }

    static class FailingExternalFlow {
        @ExternalSagaStep(saga = "ExtFunc", id = "x", compensate = "ux")
        public Mono<String> x() { return Mono.just("ok"); }
        public Mono<Void> ux(String res) { return Mono.empty(); }

        @ExternalSagaStep(saga = "ExtFunc", id = "y", dependsOn = {"x"}, compensate = "uy")
        public Mono<Void> y() { return Mono.error(new RuntimeException("boom")); }
        public Mono<Void> uy(SagaContext ctx) { return Mono.empty(); }
    }

    @Test
    void endToEnd_externalSteps_failure_triggers_compensation() {
        ctx = new AnnotationConfigApplicationContext(FailingAppConfig.class);
        SagaEngine engine = ctx.getBean(SagaEngine.class);
        SagaContext sctx = new SagaContext();
        SagaResult r = engine.execute("ExtFunc", StepInputs.builder().build(), sctx).block();
        assertNotNull(r);
        assertFalse(r.isSuccess());
        assertEquals(StepStatus.COMPENSATED, sctx.getStatus("x"));
        assertEquals(StepStatus.FAILED, sctx.getStatus("y"));
    }
}
