package com.catalis.transactionalengine.events;

import com.catalis.transactionalengine.annotations.*;
import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.core.SagaResult;
import com.catalis.transactionalengine.engine.SagaEngine;
import com.catalis.transactionalengine.engine.StepInputs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class StepEventsIT {

    private AnnotationConfigApplicationContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
    }

    static class Collector {
        final List<StepEventEnvelope> events = new CopyOnWriteArrayList<>();
        @EventListener
        public void onEvent(StepEventEnvelope event) { events.add(event); }
        public List<StepEventEnvelope> events() { return events; }
    }

    @Configuration
    @EnableTransactionalEngine
    static class AppConfig {
        @Bean public Collector collector() { return new Collector(); }
        @Bean public OkSaga okSaga() { return new OkSaga(); }
        @Bean public FailSaga failSaga() { return new FailSaga(); }
    }

    @Saga(name = "EventSaga")
    static class OkSaga {
        @SagaStep(id = "a")
        @StepEvent(topic = "orders", type = "A", key = "k1")
        public Mono<String> a() { return Mono.just("A"); }

        @SagaStep(id = "b", dependsOn = {"a"})
        @StepEvent(topic = "orders", type = "B", key = "k2")
        public Mono<String> b(@FromStep("a") String a) { return Mono.just("B:" + a); }
    }

    @Saga(name = "EventSagaFail")
    static class FailSaga {
        @SagaStep(id = "a")
        @StepEvent(topic = "orders", type = "A")
        public Mono<String> a() { return Mono.just("A"); }

        @SagaStep(id = "b", dependsOn = {"a"})
        @StepEvent(topic = "orders", type = "B")
        public Mono<String> b() { return Mono.just("B"); }

        @SagaStep(id = "c", dependsOn = {"b"})
        public Mono<Void> c() { return Mono.error(new RuntimeException("failC")); }
    }

    @Test
    void successSagaPublishesOneEventPerStep() {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        SagaEngine engine = ctx.getBean(SagaEngine.class);
        Collector collector = ctx.getBean(Collector.class);
        SagaContext sctx = new SagaContext("corr-evt-1");
        sctx.putHeader("X-Tenant", "acme");

        SagaResult result = engine.execute("EventSaga", StepInputs.builder().build(), sctx).block();
        assertNotNull(result);
        assertTrue(result.isSuccess(), "Saga should succeed");

        List<StepEventEnvelope> evs = collector.events();
        assertEquals(2, evs.size(), "Should publish one event per step");
        assertEquals("EventSaga", evs.get(0).sagaName);
        assertEquals(sctx.correlationId(), evs.get(0).sagaId);
        assertEquals("a", evs.get(0).stepId);
        assertEquals("A", evs.get(0).payload);
        assertEquals("acme", evs.get(0).headers.get("X-Tenant"));

        assertEquals("b", evs.get(1).stepId);
        assertEquals("B:A", evs.get(1).payload);
        assertEquals("orders", evs.get(1).topic);
        assertEquals("B", evs.get(1).type);
    }

    @Test
    void failingSagaPublishesNoEvents() {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        SagaEngine engine = ctx.getBean(SagaEngine.class);
        Collector collector = ctx.getBean(Collector.class);
        SagaContext sctx = new SagaContext("corr-evt-2");

        SagaResult result = engine.execute("EventSagaFail", StepInputs.builder().build(), sctx).block();
        assertNotNull(result);
        assertFalse(result.isSuccess(), "Saga should fail");
        assertEquals(0, collector.events().size(), "No events should be published on failure");
    }
}
