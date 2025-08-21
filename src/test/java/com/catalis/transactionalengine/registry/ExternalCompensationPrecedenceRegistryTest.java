package com.catalis.transactionalengine.registry;

import com.catalis.transactionalengine.annotations.CompensationSagaStep;
import com.catalis.transactionalengine.annotations.Saga;
import com.catalis.transactionalengine.annotations.SagaStep;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class ExternalCompensationPrecedenceRegistryTest {

    @Configuration
    static class AppConfig {
        @Bean public Orchestrator orchestrator() { return new Orchestrator(); }
        @Bean public CompensationSteps compensationSteps() { return new CompensationSteps(); }
    }

    @Saga(name = "PrecSaga")
    static class Orchestrator {
        @SagaStep(id = "a", compensate = "undoA")
        public Mono<String> a() { return Mono.just("ok"); }
        public Mono<Void> undoA(String res) { return Mono.empty(); }
    }

    static class CompensationSteps {
        @CompensationSagaStep(saga = "PrecSaga", forStepId = "a")
        public Mono<Void> externalUndoA(String res) { return Mono.empty(); }
    }

    @Test
    void external_mapping_overrides_in_class_compensation() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        try {
            SagaRegistry reg = new SagaRegistry(ctx);
            SagaDefinition def = reg.getSaga("PrecSaga");
            StepDefinition sd = def.steps.get("a");
            assertNotNull(sd);
            assertEquals("externalUndoA", sd.compensateMethod.getName(), "External compensation should override in-class method");
            assertSame(ctx.getBean(CompensationSteps.class), sd.compensateBean);
        } finally { ctx.close(); }
    }
}
