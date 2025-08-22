package com.catalis.transactionalengine.engine;

import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.core.StepStatus;
import com.catalis.transactionalengine.observability.SagaEvents;
import com.catalis.transactionalengine.registry.SagaBuilder;
import com.catalis.transactionalengine.registry.SagaDefinition;
import com.catalis.transactionalengine.registry.SagaRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SagaEngineCancellationTest {

    static class TestEvents implements SagaEvents {
        final List<String> calls = new CopyOnWriteArrayList<>();
        @Override public void onCompleted(String sagaName, String sagaId, boolean success) { calls.add("completed:"+success); }
        @Override public void onStepStarted(String sagaName, String sagaId, String stepId) { calls.add("started:"+stepId); }
    }

    @Test
    void cancelSubscriptionStopsFurtherProcessing() throws InterruptedException {
        // A saga with one slow step
        SagaDefinition def = SagaBuilder.saga("Cancel").
                step("slow").handler((StepHandler<Void, String>) (input, ctx) -> Mono.delay(Duration.ofSeconds(5)).thenReturn("done")).add().
                build();

        TestEvents events = new TestEvents();
        SagaRegistry dummy = mock(SagaRegistry.class);
        SagaEngine engine = new SagaEngine(dummy, events);
        SagaContext ctx = new SagaContext("corr-cancel");

        Disposable d = engine.execute(def, StepInputs.builder().build(), ctx).subscribe();
        // give it a moment to start
        Thread.sleep(50);
        // cancel subscription
        d.dispose();
        Thread.sleep(20);

        // We expect no completion event since we cancelled before completion
        assertTrue(events.calls.stream().noneMatch(s -> s.startsWith("completed:")));
        // Step should have been marked as RUNNING after start
        assertEquals(StepStatus.RUNNING, ctx.getStatus("slow"));
        // Attempts should be at least 1
        assertTrue(ctx.getAttempts("slow") >= 1);
    }
}
