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


package com.firefly.transactional.engine;

import com.firefly.transactional.core.SagaContext;
import com.firefly.transactional.core.SagaResult;
import com.firefly.transactional.observability.SagaEvents;
import com.firefly.transactional.registry.SagaBuilder;
import com.firefly.transactional.registry.SagaDefinition;
import com.firefly.transactional.registry.SagaRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SagaEngineCompensationPolicyTest {

    static class Events implements SagaEvents {}

    @Test
    void groupedParallelCompensationIsFasterThanSequential() {
        // Create three independent steps that will be compensated with delay when a dependent step fails
        StepHandler<Void, String> a = new StepHandler<Void, String>() {
            @Override public Mono<String> execute(Void input, com.firefly.transactional.core.SagaContext ctx) { return Mono.just("ra"); }
            @Override public Mono<Void> compensate(Object arg, com.firefly.transactional.core.SagaContext ctx) { return Mono.delay(Duration.ofMillis(200)).then(); }
        };
        StepHandler<Void, String> b = new StepHandler<Void, String>() {
            @Override public Mono<String> execute(Void input, com.firefly.transactional.core.SagaContext ctx) { return Mono.just("rb"); }
            @Override public Mono<Void> compensate(Object arg, com.firefly.transactional.core.SagaContext ctx) { return Mono.delay(Duration.ofMillis(200)).then(); }
        };
        StepHandler<Void, String> c = new StepHandler<Void, String>() {
            @Override public Mono<String> execute(Void input, com.firefly.transactional.core.SagaContext ctx) { return Mono.just("rc"); }
            @Override public Mono<Void> compensate(Object arg, com.firefly.transactional.core.SagaContext ctx) { return Mono.delay(Duration.ofMillis(200)).then(); }
        };
        StepHandler<Void, String> fail = (input, ctx) -> Mono.error(new RuntimeException("boom"));

        SagaDefinition def = SagaBuilder.saga("P").
                step("a").handler(a).add().
                step("b").handler(b).add().
                step("c").handler(c).add().
                step("d").dependsOn("a","b","c").handler(fail).add().
                build();

        SagaRegistry dummy = mock(SagaRegistry.class);
        Events events = new Events();

        // Sequential engine
        SagaEngine seq = new SagaEngine(dummy, events, SagaEngine.CompensationPolicy.STRICT_SEQUENTIAL);
        SagaContext ctx1 = new SagaContext("seq");
        long t1 = System.currentTimeMillis();
        SagaResult r1 = seq.execute(def, StepInputs.builder().build(), ctx1).block();
        long e1 = System.currentTimeMillis() - t1;
        assertNotNull(r1);
        assertFalse(r1.isSuccess());

        // Grouped-parallel engine
        SagaEngine par = new SagaEngine(dummy, events, SagaEngine.CompensationPolicy.GROUPED_PARALLEL);
        SagaContext ctx2 = new SagaContext("par");
        long t2 = System.currentTimeMillis();
        SagaResult r2 = par.execute(def, StepInputs.builder().build(), ctx2).block();
        long e2 = System.currentTimeMillis() - t2;
        assertNotNull(r2);
        assertFalse(r2.isSuccess());

        // With three compensations delayed 200ms each, sequential should be ~= 600ms, grouped ~= ~200ms (+ overhead).
        // Allow generous thresholds to avoid flakiness.
        assertTrue(e1 >= 450, "sequential should take at least ~450ms but was " + e1);
        assertTrue(e2 < e1 - 150, "grouped should be significantly faster; seq=" + e1 + " grouped=" + e2);
    }
}
