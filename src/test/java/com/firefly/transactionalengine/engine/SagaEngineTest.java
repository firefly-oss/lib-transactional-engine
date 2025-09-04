/*
 * Copyright 2025 Firefly Software Solutions Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.firefly.transactionalengine.engine;

import com.firefly.transactionalengine.core.SagaContext;
import com.firefly.transactionalengine.core.SagaResult;
import com.firefly.transactionalengine.core.StepStatus;
import com.firefly.transactionalengine.observability.SagaEvents;
import com.firefly.transactionalengine.registry.SagaBuilder;
import com.firefly.transactionalengine.registry.SagaDefinition;
import com.firefly.transactionalengine.registry.SagaRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

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
        // Given a simple saga with two steps in sequence
        SagaDefinition def = SagaBuilder.saga("S").
                step("a").handler((StepHandler<String, String>) (input, ctx) -> Mono.just("A-"+input)).add().
                step("b").dependsOn("a").handler((StepHandler<Void, String>) (input, ctx) -> Mono.just("B" + ctx.getResult("a"))).add()
                .build();

        TestEvents events = new TestEvents();
        SagaEngine engine = newEngine(events);
        SagaContext ctx = new SagaContext("corr1");
        StepInputs inputs = StepInputs.builder().forStepId("a", "in").build();

        // When executing the saga via the new API
        SagaResult result = engine.execute(def, inputs, ctx).block();

        // Then results are stored and events emitted
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("A-in", result.resultOf("a", String.class).orElse(null));
        assertEquals("BA-in", result.resultOf("b", String.class).orElse(null));

        assertEquals(StepStatus.DONE, ctx.getStatus("a"));
        assertEquals(StepStatus.DONE, ctx.getStatus("b"));
        assertTrue(events.calls.contains("completed:true"));
        assertTrue(events.calls.stream().anyMatch(s -> s.startsWith("success:a")));
        assertTrue(events.calls.stream().anyMatch(s -> s.startsWith("success:b")));
    }

    @Test
    void retryBackoffWorks() {
        // Given a step that fails twice before succeeding, with backoff using the new Duration API
        SagaDefinition def = SagaBuilder.saga("R").
                step("r").retry(2).backoff(Duration.ofMillis(1)).handler(new StepHandler<Void, String>() {
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

        SagaResult res = engine.execute(def, StepInputs.builder().build(), ctx).block();
        assertNotNull(res);
        assertTrue(res.isSuccess());

        assertEquals(3, ctx.getAttempts("r")); // 1 initial + 2 retries
        assertTrue(events.calls.stream().anyMatch(s -> s.equals("success:r:attempts=3")));
    }

    @Test
    void timeoutFailsStep() {
        // Given a slow step with a short timeout using the new Duration API
        SagaDefinition def = SagaBuilder.saga("T").
                step("slow").timeout(Duration.ofMillis(50)).handler((StepHandler<Void, String>) (input, ctx) -> Mono.delay(Duration.ofMillis(200)).thenReturn("late")).add()
                .build();

        TestEvents events = new TestEvents();
        SagaEngine engine = newEngine(events);
        SagaContext ctx = new SagaContext();

        SagaResult result = engine.execute(def, StepInputs.builder().build(), ctx).block();
        assertNotNull(result);
        assertFalse(result.isSuccess());

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

        SagaResult result = engine.execute(def, StepInputs.builder().build(), ctx).block();
        assertNotNull(result);
        assertFalse(result.isSuccess());

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

        SagaResult res2 = engine.execute(def, StepInputs.builder().build(), ctx).block();
        assertNotNull(res2);
        assertTrue(res2.isSuccess());

        assertEquals(0, ctx.getAttempts("x"));
        assertEquals(StepStatus.DONE, ctx.getStatus("x")); // marked done even if skipped
    }
}
