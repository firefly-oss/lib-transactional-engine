package com.firefly.transactionalengine.registry;

import com.firefly.transactionalengine.annotations.CompensationSagaStep;
import com.firefly.transactionalengine.annotations.Saga;
import com.firefly.transactionalengine.annotations.SagaStep;
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
