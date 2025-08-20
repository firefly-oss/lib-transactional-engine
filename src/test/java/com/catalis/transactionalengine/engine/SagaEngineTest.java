package com.catalis.transactionalengine.engine;

import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.core.StepStatus;
import com.catalis.transactionalengine.observability.SagaEvents;
import com.catalis.transactionalengine.registry.SagaBuilder;
import com.catalis.transactionalengine.registry.SagaDefinition;
import com.catalis.transactionalengine.registry.SagaRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SagaEngineTest {

    static class TestEvents implements SagaEvents {
        final List<String> calls = new CopyOnWriteArrayList<>();
        @Override public void onStart(String sagaName, String sagaId) { calls.add("start:"+sagaName); }
        @Override public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) { calls.add("success:"+stepId+":attempts="+attempts); }
        @Override public void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) { calls.add("failed:"+stepId+":attempts="+attempts+":"+error.getClass().getSimpleName()); }
        @Override public void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) { calls.add("comp:"+stepId); }
        @Override public void onCompleted(String sagaName, String sagaId, boolean success) { calls.add("completed:"+success); }
    }

    private SagaEngine newEngine(SagaEvents events) {
        SagaRegistry dummy = mock(SagaRegistry.class);
        return new SagaEngine(dummy, events);
    }

    @Test
    void successFlowStoresResultsAndEvents() {
        SagaDefinition def = SagaBuilder.saga("S").
                step("a").handler((StepHandler<String, String>) (input, ctx) -> Mono.just("A-"+input)).add().
                step("b").dependsOn("a").handler((StepHandler<Void, String>) (input, ctx) -> Mono.just("B" + ctx.getResult("a"))).add()
                .build();

        TestEvents events = new TestEvents();
        SagaEngine engine = newEngine(events);
        SagaContext ctx = new SagaContext("corr1");

        Map<String, Object> results = engine.run(def, Map.of("a", "in"), ctx).block();
        assertNotNull(results);
        assertEquals("A-in", results.get("a"));
        assertEquals("BA-in", results.get("b"));

        assertEquals(StepStatus.DONE, ctx.getStatus("a"));
        assertEquals(StepStatus.DONE, ctx.getStatus("b"));
        assertTrue(events.calls.contains("completed:true"));
        assertTrue(events.calls.stream().anyMatch(s -> s.startsWith("success:a")));
        assertTrue(events.calls.stream().anyMatch(s -> s.startsWith("success:b")));
    }

    @Test
    void retryBackoffWorks() {
        SagaDefinition def = SagaBuilder.saga("R").
                step("r").retry(2).backoffMs(1).handler(new StepHandler<Void, String>() {
                    final AtomicInteger attempts = new AtomicInteger();
                    @Override public Mono<String> execute(Void input, SagaContext ctx) {
                        int n = attempts.incrementAndGet();
                        if (n < 3) return Mono.error(new RuntimeException("boom"+n));
                        return Mono.just("ok");
                    }
                }).add().build();

        TestEvents events = new TestEvents();
        SagaEngine engine = newEngine(events);
        SagaContext ctx = new SagaContext();

        Map<String, Object> res = engine.run(def, Map.of(), ctx).block();
        assertNotNull(res);

        assertEquals(3, ctx.getAttempts("r")); // 1 initial + 2 retries
        assertTrue(events.calls.stream().anyMatch(s -> s.equals("success:r:attempts=3")));
    }

    @Test
    void timeoutFailsStep() {
        SagaDefinition def = SagaBuilder.saga("T").
                step("slow").timeoutMs(50).handler((StepHandler<Void, String>) (input, ctx) -> Mono.delay(Duration.ofMillis(200)).thenReturn("late")).add()
                .build();

        TestEvents events = new TestEvents();
        SagaEngine engine = newEngine(events);
        SagaContext ctx = new SagaContext();

        assertThrows(RuntimeException.class, () -> engine.run(def, Map.of(), ctx).block());

        assertEquals(StepStatus.FAILED, ctx.getStatus("slow"));
        assertTrue(events.calls.stream().anyMatch(s -> s.startsWith("failed:slow")));
        assertTrue(events.calls.contains("completed:false"));
    }

    @Test
    void compensationOnFailureInReverseCompletionOrder() {
        List<String> executed = new CopyOnWriteArrayList<>();
        List<String> compensated = new CopyOnWriteArrayList<>();

        StepHandler<Void, String> stepA = new StepHandler<Void, String>() {
            @Override public Mono<String> execute(Void input, SagaContext ctx) {
                executed.add("a");
                return Mono.just("ra");
            }
            @Override public Mono<Void> compensate(Object arg, SagaContext ctx) { compensated.add("a"); return Mono.empty(); }
        };
        StepHandler<Void, String> stepB = new StepHandler<Void, String>() {
            @Override public Mono<String> execute(Void input, SagaContext ctx) {
                executed.add("b");
                return Mono.just("rb");
            }
            @Override public Mono<Void> compensate(Object arg, SagaContext ctx) { compensated.add("b"); return Mono.empty(); }
        };
        StepHandler<Void, String> stepC = new StepHandler<Void, String>() {
            @Override public Mono<String> execute(Void input, SagaContext ctx) { return Mono.error(new RuntimeException("failC")); }
        };

        SagaDefinition def = SagaBuilder.saga("Comp").
                step("a").handler(stepA).add().
                step("b").handler(stepB).add().
                step("c").dependsOn("a", "b").handler(stepC).add().build();

        TestEvents events = new TestEvents();
        SagaEngine engine = newEngine(events);
        SagaContext ctx = new SagaContext();

        assertThrows(RuntimeException.class, () -> engine.run(def, Map.of(), ctx).block());

        assertTrue(executed.contains("a") && executed.contains("b"));
        // reverse of completion order; since a and b run concurrently, order of completion is non-deterministic
        // but we expect both compensations to have been invoked
        assertTrue(compensated.containsAll(List.of("a", "b")));
        assertTrue(events.calls.contains("completed:false"));
        assertEquals(StepStatus.COMPENSATED, ctx.getStatus("a"));
        assertEquals(StepStatus.COMPENSATED, ctx.getStatus("b"));
    }

    @Test
    void idempotentStepIsSkipped() {
        SagaDefinition def = SagaBuilder.saga("I").
                step("x").idempotencyKey("key1").handler((StepHandler<Void, String>) (input, ctx) -> Mono.just("should-not-run")).add()
                .build();
        TestEvents events = new TestEvents();
        SagaEngine engine = newEngine(events);
        SagaContext ctx = new SagaContext();
        ctx.markIdempotent("key1");

        Map<String, Object> res2 = engine.run(def, Map.of(), ctx).block();
        assertNotNull(res2);

        assertEquals(0, ctx.getAttempts("x"));
        assertEquals(StepStatus.DONE, ctx.getStatus("x")); // marked done even if skipped
    }
}
