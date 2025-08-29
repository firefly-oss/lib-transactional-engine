package com.firefly.transactionalengine.engine;

import com.firefly.transactionalengine.core.SagaContext;
import com.firefly.transactionalengine.core.SagaResult;
import com.firefly.transactionalengine.observability.SagaEvents;
import com.firefly.transactionalengine.registry.SagaBuilder;
import com.firefly.transactionalengine.registry.SagaDefinition;
import com.firefly.transactionalengine.registry.SagaRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class StepInputsTest {

    private SagaEngine newEngine() {
        SagaRegistry dummy = mock(SagaRegistry.class);
        // We won't use the registry for programmatic SagaDefinition execution
        return new SagaEngine(dummy, new SagaEvents() {});
    }

    @Test
    void runWithStepInputsConcrete() {
        // Given a saga with an explicit input for step 'a'
        SagaDefinition def = SagaBuilder.saga("S1").
                step("a").handler((StepHandler<String, String>) (input, ctx) -> Mono.just("A-" + input)).add().
                step("b").dependsOn("a").handler((StepHandler<Void, String>) (input, ctx) -> Mono.just("B" + ctx.getResult("a"))).add()
                .build();

        SagaEngine engine = newEngine();
        SagaContext ctx = new SagaContext("corr-test-1");

        StepInputs inputs = StepInputs.builder()
                .forStepId("a", "in")
                .build();

        // When executing via new API
        SagaResult result = engine.execute(def, inputs, ctx).block();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("A-in", result.resultOf("a", String.class).orElse(null));
        assertEquals("BA-in", result.resultOf("b", String.class).orElse(null));
        assertEquals("A-in", ctx.getResult("a"));
    }

    @Test
    void runWithResolverBuildsInputFromContext() {
        // Given a saga where step 'b' input is resolved from previous step result
        SagaDefinition def = SagaBuilder.saga("S2").
                step("a").handler((StepHandler<Void, String>) (input, ctx) -> Mono.just("ra")).add().
                step("b").dependsOn("a").handler((StepHandler<String, String>) (input, ctx) -> Mono.just(input)).add()
                .build();

        SagaEngine engine = newEngine();
        SagaContext ctx = new SagaContext("corr-test-2");

        StepInputs inputs = StepInputs.builder()
                .forStepId("b", (c) -> ((String) c.getResult("a")) + "-x")
                .build();

        SagaResult result = engine.execute(def, inputs, ctx).block();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("ra", result.resultOf("a", String.class).orElse(null));
        assertEquals("ra-x", result.resultOf("b", String.class).orElse(null));
    }
}
