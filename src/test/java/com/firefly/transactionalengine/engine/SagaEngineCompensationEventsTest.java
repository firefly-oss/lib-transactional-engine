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
import com.firefly.transactionalengine.observability.SagaEvents;
import com.firefly.transactionalengine.registry.SagaBuilder;
import com.firefly.transactionalengine.registry.SagaDefinition;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SagaEngineCompensationEventsTest {

    static class Events implements SagaEvents {
        final List<String> calls = new CopyOnWriteArrayList<>();
        @Override public void onCompensationStarted(String sagaName, String sagaId, String stepId) { calls.add("start:"+stepId); }
        @Override public void onCompensationRetry(String sagaName, String sagaId, String stepId, int attempt) { calls.add("retry:"+stepId+":"+attempt); }
        @Override public void onCompensationSkipped(String sagaName, String sagaId, String stepId, String reason) { calls.add("skipped:"+stepId+":"+reason); }
        @Override public void onCompensationCircuitOpen(String sagaName, String sagaId, String stepId) { calls.add("circuit:"+stepId); }
        @Override public void onCompensationBatchCompleted(String sagaName, String sagaId, java.util.List<String> stepIds, boolean allSuccessful) { calls.add("batch:"+allSuccessful+":"+String.join(",", stepIds)); }
        @Override public void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) { calls.add("comp:"+stepId+":"+(error==null)); }
        @Override public void onStepSkippedIdempotent(String sagaName, String sagaId, String stepId) { calls.add("idem-skip:"+stepId); }
    }

    @Test
    void compensationRetryEmitsRetryEventsAndEventuallySuccess() {
        // a and b succeed; c fails to trigger compensation of a and b.
        AtomicInteger compAttemptsA = new AtomicInteger();
        StepHandler<Void, String> a = new StepHandler<Void, String>() {
            @Override public Mono<String> execute(Void input, SagaContext ctx) { return Mono.just("ra"); }
            @Override public Mono<Void> compensate(Object arg, SagaContext ctx) {
                int n = compAttemptsA.incrementAndGet();
                if (n == 1) { return Mono.error(new RuntimeException("fail once")); }
                return Mono.empty();
            }
        };
        StepHandler<Void, String> b = (input, ctx) -> Mono.just("rb");
        StepHandler<Void, String> c = (input, ctx) -> Mono.error(new RuntimeException("boom"));

        // Configure retry/backoff on step A so its compensation inherits those (since no explicit comp overrides via builder)
        SagaDefinition def = SagaBuilder.saga("CR").
                step("a").retry(1).backoff(Duration.ofMillis(1)).handler(a).add().
                step("b").handler(b).add().
                step("c").dependsOn("a","b").handler(c).add().
                build();

        Events events = new Events();
        SagaEngine engine = new SagaEngine(null, events, SagaEngine.CompensationPolicy.RETRY_WITH_BACKOFF);
        SagaResult res = engine.execute(def, StepInputs.builder().build(), new SagaContext("cr-1")).block();

        assertNotNull(res);
        assertFalse(res.isSuccess());
        // Expect a retry event for 'a' compensation and eventual compensated success
        assertTrue(events.calls.stream().anyMatch(s -> s.equals("retry:a:1")));
        assertTrue(events.calls.stream().anyMatch(s -> s.equals("comp:a:true")));
    }

    @Test
    void circuitBreakerSkipsAfterCriticalCompensationFailure() {
        // a succeeds then fails to compensate; marked critical so circuit opens and skips b's compensation
        StepHandler<Void, String> a = new StepHandler<Void, String>() {
            @Override public Mono<String> execute(Void input, SagaContext ctx) {
                // Delay to ensure 'a' completes after 'b', so in reversed compensation order 'a' is attempted first
                return Mono.delay(Duration.ofMillis(50)).thenReturn("ra");
            }
            @Override public Mono<Void> compensate(Object arg, SagaContext ctx) { return Mono.error(new RuntimeException("comp-fail")); }
        };
        StepHandler<Void, String> b = new StepHandler<Void, String>() {
            @Override public Mono<String> execute(Void input, SagaContext ctx) { return Mono.just("rb"); }
            @Override public Mono<Void> compensate(Object arg, SagaContext ctx) { return Mono.empty(); }
        };
        StepHandler<Void, String> c = (input, ctx) -> Mono.error(new RuntimeException("boom"));

        SagaDefinition def = SagaBuilder.saga("CB").
                step("a").handler(a).add().
                step("b").handler(b).add().
                step("c").dependsOn("a","b").handler(c).add().
                build();
        // Mark 'a' compensation as critical so circuit breaker opens when it fails
        def.steps.get("a").compensationCritical = true;

        Events events = new Events();
        SagaEngine engine = new SagaEngine(null, events, SagaEngine.CompensationPolicy.CIRCUIT_BREAKER);
        SagaResult res = engine.execute(def, StepInputs.builder().build(), new SagaContext("cb-1")).block();

        assertNotNull(res);
        assertFalse(res.isSuccess());
        assertTrue(events.calls.stream().anyMatch(s -> s.equals("circuit:a")));
        // b should be skipped due to open circuit
        assertTrue(events.calls.stream().anyMatch(s -> s.startsWith("skipped:b:")));
    }

    @Test
    void bestEffortParallelBatchCompletedAllSuccessfulFalse() {
        StepHandler<Void, String> a = new StepHandler<Void, String>() {
            @Override public Mono<String> execute(Void input, SagaContext ctx) { return Mono.just("ra"); }
            @Override public Mono<Void> compensate(Object arg, SagaContext ctx) { return Mono.error(new RuntimeException("fail-comp-a")); }
        };
        StepHandler<Void, String> b = new StepHandler<Void, String>() {
            @Override public Mono<String> execute(Void input, SagaContext ctx) { return Mono.just("rb"); }
            @Override public Mono<Void> compensate(Object arg, SagaContext ctx) { return Mono.empty(); }
        };
        StepHandler<Void, String> c = (input, ctx) -> Mono.error(new RuntimeException("boom"));

        SagaDefinition def = SagaBuilder.saga("BP").
                step("a").handler(a).add().
                step("b").handler(b).add().
                step("c").dependsOn("a","b").handler(c).add().
                build();

        Events events = new Events();
        SagaEngine engine = new SagaEngine(null, events, SagaEngine.CompensationPolicy.BEST_EFFORT_PARALLEL);
        SagaResult res = engine.execute(def, StepInputs.builder().build(), new SagaContext("bp-1")).block();
        assertNotNull(res);
        assertFalse(res.isSuccess());
        assertTrue(events.calls.stream().anyMatch(s -> s.startsWith("batch:false:")));
    }

    @Test
    void idempotentStepEmitsSkipEvent() {
        // Step x is idempotent and key present in context -> should emit onStepSkippedIdempotent
        SagaDefinition def = SagaBuilder.saga("I2").
                step("x").idempotencyKey("key1").handler((StepHandler<Void, String>) (in, ctx) -> Mono.just("noop")).add().
                build();
        Events events = new Events();
        SagaEngine engine = new SagaEngine(null, events);
        SagaContext ctx = new SagaContext("idem-1");
        ctx.markIdempotent("key1");
        SagaResult r = engine.execute(def, StepInputs.builder().build(), ctx).block();
        assertNotNull(r);
        assertTrue(r.isSuccess());
        assertTrue(events.calls.stream().anyMatch(s -> s.equals("idem-skip:x")));
    }
}
