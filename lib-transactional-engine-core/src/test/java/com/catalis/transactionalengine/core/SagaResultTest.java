package com.catalis.transactionalengine.core;

import com.catalis.transactionalengine.engine.SagaEngine;
import com.catalis.transactionalengine.engine.StepHandler;
import com.catalis.transactionalengine.engine.StepInputs;
import com.catalis.transactionalengine.observability.SagaEvents;
import com.catalis.transactionalengine.registry.SagaBuilder;
import com.catalis.transactionalengine.registry.SagaDefinition;
import com.catalis.transactionalengine.registry.SagaRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests focused on the new SagaResult snapshot API to ensure
 * developers get clear, typed access to execution outcomes.
 */
class SagaResultTest {

    static class Events implements SagaEvents {
        final List<String> calls = new CopyOnWriteArrayList<>();
        @Override public void onCompleted(String sagaName, String sagaId, boolean success) { calls.add("completed:"+success); }
    }

    private SagaEngine newEngine(SagaEvents events) {
        SagaRegistry dummy = mock(SagaRegistry.class);
        return new SagaEngine(dummy, events);
    }

    @Test
    void success_metadata_and_typed_accessors() {
        // Given a simple two-step saga
        SagaDefinition def = SagaBuilder.saga("SR-Success").
                step("a").handler((StepHandler<Void, String>) (in, ctx) -> Mono.just("A" )).add().
                step("b").dependsOn("a").handler((StepHandler<Void, String>) (in, ctx) -> Mono.just("B:"+ctx.getResult("a"))).add().
                build();

        Events ev = new Events();
        SagaEngine engine = newEngine(ev);
        SagaContext ctx = new SagaContext("corr-sr-1");
        ctx.putHeader("X-User", "u-123");

        // When executing via new API
        SagaResult result = engine.execute(def, StepInputs.builder().build(), ctx).block();

        // Then the snapshot exposes typed results and metadata
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("SR-Success", result.sagaName());
        assertEquals("corr-sr-1", result.correlationId());
        assertEquals("u-123", result.headers().get("X-User"));
        assertTrue(result.duration().toMillis() >= 0);

        assertEquals("A", result.resultOf("a", String.class).orElse(null));
        assertEquals("B:A", result.resultOf("b", String.class).orElse(null));
        assertTrue(result.failedSteps().isEmpty());
        assertTrue(result.compensatedSteps().isEmpty());
    }

    @Test
    void failure_reports_failed_and_compensated_steps() {
        // Given a saga where the third step fails, triggering compensation of previous ones
        StepHandler<Void, String> stepA = (in, ctx) -> Mono.just("ra");
        StepHandler<Void, String> stepB = (in, ctx) -> Mono.just("rb");
        StepHandler<Void, String> stepC = (in, ctx) -> Mono.error(new RuntimeException("boom"));

        SagaDefinition def = SagaBuilder.saga("SR-Fail").
                step("a").handler(stepA).add().
                step("b").handler(stepB).add().
                step("c").dependsOn("a","b").handler(stepC).add().
                build();

        Events ev = new Events();
        SagaEngine engine = newEngine(ev);
        SagaContext ctx = new SagaContext("corr-sr-2");

        // When executing
        SagaResult result = engine.execute(def, StepInputs.builder().build(), ctx).block();

        // Then failure is captured without throwing, with step-level details
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.failedSteps().contains("c"));
        assertTrue(result.compensatedSteps().containsAll(List.of("a", "b")));
        assertTrue(result.error().isPresent());
        assertEquals("c", result.firstErrorStepId().orElse(null));
    }
}
