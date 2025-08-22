package com.catalis.transactionalengine.engine;

import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.core.SagaResult;
import com.catalis.transactionalengine.observability.SagaEvents;
import com.catalis.transactionalengine.registry.SagaBuilder;
import com.catalis.transactionalengine.registry.SagaDefinition;
import com.catalis.transactionalengine.registry.SagaRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ExpandEachTest {

    private SagaEngine newEngine() {
        SagaRegistry dummy = mock(SagaRegistry.class);
        return new SagaEngine(dummy, new SagaEvents() {});
    }

    @Test
    void expandsListInputIntoPerItemStepsWithCompensation() {
        // Given a saga with a step 'ins' and a downstream failing step
        SagaDefinition def = SagaBuilder.saga("Expand")
                .step("ins")
                    .handler((StepHandler<String, String>) (in, ctx) -> Mono.just("ok-" + in))
                    .compensation((arg, ctx) -> { ctx.putVariable("comp:" + arg, true); return Mono.empty(); })
                    .add()
                .step("fail").dependsOn("ins")
                    .handler((StepHandler<Void, Void>) (in, ctx) -> Mono.error(new RuntimeException("boom")))
                    .add()
                .build();

        SagaEngine engine = newEngine();
        SagaContext ctx = new SagaContext("corr-expand-1");

        StepInputs inputs = StepInputs.builder()
                .forStepId("ins", ExpandEach.of(List.of("A", "B", "C")))
                .build();

        SagaResult res = engine.execute(def, inputs, ctx).block();
        assertNotNull(res);
        assertFalse(res.isSuccess(), "Saga must fail to trigger compensation");

        // Each item gets its own step id: ins#0, ins#1, ins#2
        assertTrue(res.steps().containsKey("ins#0"));
        assertTrue(res.steps().containsKey("ins#1"));
        assertTrue(res.steps().containsKey("ins#2"));

        // Compensation ran per clone; verify flags via variables we set in compensation
        assertEquals(Boolean.TRUE, ctx.getVariable("comp:A"));
        assertEquals(Boolean.TRUE, ctx.getVariable("comp:B"));
        assertEquals(Boolean.TRUE, ctx.getVariable("comp:C"));
    }
}
