package com.firefly.transactionalengine.engine;

import com.firefly.transactionalengine.core.SagaContext;
import com.firefly.transactionalengine.core.SagaResult;
import com.firefly.transactionalengine.observability.SagaEvents;
import com.firefly.transactionalengine.registry.SagaBuilder;
import com.firefly.transactionalengine.registry.SagaDefinition;
import com.firefly.transactionalengine.registry.SagaRegistry;
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
            @Override public Mono<String> execute(Void input, com.firefly.transactionalengine.core.SagaContext ctx) { return Mono.just("ra"); }
            @Override public Mono<Void> compensate(Object arg, com.firefly.transactionalengine.core.SagaContext ctx) { return Mono.delay(Duration.ofMillis(200)).then(); }
        };
        StepHandler<Void, String> b = new StepHandler<Void, String>() {
            @Override public Mono<String> execute(Void input, com.firefly.transactionalengine.core.SagaContext ctx) { return Mono.just("rb"); }
            @Override public Mono<Void> compensate(Object arg, com.firefly.transactionalengine.core.SagaContext ctx) { return Mono.delay(Duration.ofMillis(200)).then(); }
        };
        StepHandler<Void, String> c = new StepHandler<Void, String>() {
            @Override public Mono<String> execute(Void input, com.firefly.transactionalengine.core.SagaContext ctx) { return Mono.just("rc"); }
            @Override public Mono<Void> compensate(Object arg, com.firefly.transactionalengine.core.SagaContext ctx) { return Mono.delay(Duration.ofMillis(200)).then(); }
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
