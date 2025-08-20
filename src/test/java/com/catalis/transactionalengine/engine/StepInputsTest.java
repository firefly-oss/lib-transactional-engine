package com.catalis.transactionalengine.engine;

import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.observability.SagaEvents;
import com.catalis.transactionalengine.registry.SagaBuilder;
import com.catalis.transactionalengine.registry.SagaDefinition;
import com.catalis.transactionalengine.registry.SagaRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;

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
        SagaDefinition def = SagaBuilder.saga("S1").
                step("a").handler((StepHandler<String, String>) (input, ctx) -> Mono.just("A-" + input)).add().
                step("b").dependsOn("a").handler((StepHandler<Void, String>) (input, ctx) -> Mono.just("B" + ctx.getResult("a"))).add()
                .build();

        SagaEngine engine = newEngine();
        SagaContext ctx = new SagaContext("corr-test-1");

        StepInputs inputs = StepInputs.builder()
                .forStepId("a", "in")
                .build();

        Map<String, Object> results = engine.run(def, inputs, ctx).block();
        assertNotNull(results);
        assertEquals("A-in", results.get("a"));
        assertEquals("BA-in", results.get("b"));
        assertEquals("A-in", ctx.getResult("a"));
    }

    @Test
    void runWithResolverBuildsInputFromContext() {
        SagaDefinition def = SagaBuilder.saga("S2").
                step("a").handler((StepHandler<Void, String>) (input, ctx) -> Mono.just("ra")).add().
                step("b").dependsOn("a").handler((StepHandler<String, String>) (input, ctx) -> Mono.just(input)).add()
                .build();

        SagaEngine engine = newEngine();
        SagaContext ctx = new SagaContext("corr-test-2");

        StepInputs inputs = StepInputs.builder()
                .forStepId("b", (c) -> ((String) c.getResult("a")) + "-x")
                .build();

        Map<String, Object> results = engine.run(def, inputs, ctx).block();
        assertNotNull(results);
        assertEquals("ra", results.get("a"));
        assertEquals("ra-x", results.get("b"));
    }
}
