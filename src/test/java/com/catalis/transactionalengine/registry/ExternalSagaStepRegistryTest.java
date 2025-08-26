package com.catalis.transactionalengine.registry;

import com.catalis.transactionalengine.annotations.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class ExternalSagaStepRegistryTest {

    @Configuration
    static class AppConfig {
        @Bean public Orchestrator orchestrator() { return new Orchestrator(); }
        @Bean public ExternalSteps externalSteps() { return new ExternalSteps(); }
    }

    @Saga(name = "ExtStepSaga")
    static class Orchestrator { /* no @SagaStep methods here; steps live externally */ }

    static class ExternalSteps {
        @ExternalSagaStep(saga = "ExtStepSaga", id = "a", compensate = "undoA")
        public Mono<String> a() { return Mono.just("ok"); }
        public Mono<Void> undoA(String res) { return Mono.empty(); }

        @ExternalSagaStep(saga = "ExtStepSaga", id = "b", dependsOn = {"a"}, compensate = "undoB")
        public Mono<String> b() { return Mono.just("B"); }
        public Mono<Void> undoB(String res) { return Mono.empty(); }
    }

    @Test
    void registry_discovers_and_wires_external_steps_and_compensation() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        try {
            SagaRegistry reg = new SagaRegistry(ctx);
            SagaDefinition def = reg.getSaga("ExtStepSaga");
            assertNotNull(def);
            assertTrue(def.steps.containsKey("a"));
            assertTrue(def.steps.containsKey("b"));

            StepDefinition sdA = def.steps.get("a");
            assertNotNull(sdA.stepInvocationMethod);
            assertNotNull(sdA.stepBean);
            assertEquals("a", sdA.stepMethod.getName());
            assertEquals("undoA", sdA.compensateMethod.getName());
            assertSame(ctx.getBean(ExternalSteps.class), sdA.stepBean);
            assertSame(ctx.getBean(ExternalSteps.class), sdA.compensateBean);

            StepDefinition sdB = def.steps.get("b");
            assertEquals(1, sdB.dependsOn.size());
            assertEquals("a", sdB.dependsOn.get(0));
            assertEquals("undoB", sdB.compensateMethod.getName());
        } finally {
            ctx.close();
        }
    }
}
