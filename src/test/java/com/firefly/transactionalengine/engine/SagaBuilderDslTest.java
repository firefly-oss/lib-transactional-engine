package com.firefly.transactionalengine.engine;

import com.firefly.transactionalengine.annotations.SagaStep;
import com.firefly.transactionalengine.core.SagaContext;
import com.firefly.transactionalengine.core.SagaResult;
import com.firefly.transactionalengine.registry.SagaBuilder;
import com.firefly.transactionalengine.registry.SagaDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SagaBuilderDslTest {

    static class Orchestrator {
        @SagaStep(id = "mrA")
        public Mono<String> a(SagaContext ctx) { return Mono.just("A"); }
        public Mono<String> b(SagaContext ctx) { return Mono.just("B"); }
    }

    private SagaEngine newEngine() {
        return new SagaEngine(mock(com.firefly.transactionalengine.registry.SagaRegistry.class), new com.firefly.transactionalengine.observability.SagaEvents(){});
    }

    @Test
    void methodRefStepInfersIdFromAnnotation() {
        SagaDefinition def = SagaBuilder.saga("DSL")
                .step(Orchestrator::a) // should infer id "mrA" from annotation
                .handlerCtx(ctx -> Mono.just("A"))
                .add()
                .build();

        assertTrue(def.steps.containsKey("mrA"));
    }

    @Test
    void methodRefStepUsesMethodNameWhenNoAnnotation() {
        SagaDefinition def = SagaBuilder.named("DSL2")
                .step(Orchestrator::b) // no annotation -> id "b"
                .handlerCtx(ctx -> Mono.just("B"))
                .add()
                .build();

        assertTrue(def.steps.containsKey("b"));
    }

    @Test
    void handlerCtxExecutesEndToEnd() {
        SagaDefinition def = SagaBuilder.saga("Run")
                .step(Orchestrator::b)
                .handlerCtx(ctx -> Mono.just("OK"))
                .add()
                .build();
        SagaEngine engine = newEngine();
        SagaContext ctx = new SagaContext();
        SagaResult res = engine.execute(def, StepInputs.empty(), ctx).block();
        assertNotNull(res);
        assertTrue(res.isSuccess());
        assertEquals("OK", res.resultOf("b", String.class).orElse(null));
    }
}
