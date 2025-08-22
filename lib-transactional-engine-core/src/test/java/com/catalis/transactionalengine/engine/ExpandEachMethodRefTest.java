package com.catalis.transactionalengine.engine;

import com.catalis.transactionalengine.annotations.SagaStep;
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

class ExpandEachMethodRefTest {

    static class Steps {
        @SagaStep(id = "ins")
        public static String insert(String in, SagaContext ctx) {
            return "ok-" + in;
        }

        @SagaStep(id = "fail")
        public static Void fail(Void in, SagaContext ctx) {
            throw new RuntimeException("boom");
        }
    }

    private SagaEngine newEngine() {
        SagaRegistry dummy = mock(SagaRegistry.class);
        return new SagaEngine(dummy, new SagaEvents() {});
    }

    @Test
    void expandsUsingMethodReferenceInputsToo() {
        // Given a saga with a step 'ins' and a downstream failing step
        SagaDefinition def = SagaBuilder.saga("ExpandByRef")
                .step("ins")
                    .handler((StepHandler<String, String>) (in, ctx) -> Mono.just("ok-" + in))
                    .compensation((arg, ctx) -> { ctx.putVariable("comp:" + arg, true); return Mono.empty(); })
                    .add()
                .step("fail").dependsOn("ins")
                    .handler((StepHandler<Void, Void>) (in, ctx) -> Mono.error(new RuntimeException("boom")))
                    .add()
                .build();

        SagaEngine engine = newEngine();
        SagaContext ctx = new SagaContext("corr-expand-ref-1");

        // Use forStep with method reference to specify input for step id derived from @SagaStep
        StepInputs inputs = StepInputs.builder()
                .forStep(Steps::insert, ExpandEach.of(List.of("A", "B", "C"), it -> (String) it))
                .build();

        SagaResult res = engine.execute(def, inputs, ctx).block();
        assertNotNull(res);
        assertFalse(res.isSuccess(), "Saga must fail to trigger compensation");

        // Each item gets its own step id using custom suffix: ins:A, ins:B, ins:C
        assertTrue(res.steps().containsKey("ins:A"));
        assertTrue(res.steps().containsKey("ins:B"));
        assertTrue(res.steps().containsKey("ins:C"));

        // Compensation ran per clone; verify flags via variables we set in compensation
        assertEquals(Boolean.TRUE, ctx.getVariable("comp:A"));
        assertEquals(Boolean.TRUE, ctx.getVariable("comp:B"));
        assertEquals(Boolean.TRUE, ctx.getVariable("comp:C"));
    }
}
