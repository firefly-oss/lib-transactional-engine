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

class SagaReportTest {

    static class Events implements SagaEvents {
        final List<String> calls = new CopyOnWriteArrayList<>();
        @Override public void onCompleted(String sagaName, String sagaId, boolean success) { calls.add("completed:"+success); }
    }

    private SagaEngine newEngine(SagaEvents events) {
        SagaRegistry dummy = mock(SagaRegistry.class);
        return new SagaEngine(dummy, events);
    }

    @Test
    void report_wraps_result_and_exposes_steps_and_compensations() {
        SagaDefinition def = SagaBuilder.saga("SRPT-Success").
                step("a").handler((StepHandler<Void, String>) (in, ctx) -> Mono.just("A" )).add().
                step("b").dependsOn("a").handler((StepHandler<Void, String>) (in, ctx) -> Mono.just("B:"+ctx.getResult("a"))).add().
                build();

        Events ev = new Events();
        SagaEngine engine = newEngine(ev);
        SagaContext ctx = new SagaContext("corr-srpt-1");
        SagaResult res = engine.execute(def, StepInputs.builder().build(), ctx).block();
        assertNotNull(res);

        SagaReport report = SagaReport.from(res);
        assertEquals("SRPT-Success", report.sagaName());
        assertEquals("corr-srpt-1", report.correlationId());
        assertTrue(report.isSuccess());
        assertEquals(2, report.steps().size());
        assertEquals("A", report.steps().get("a").result());
        assertNull(report.steps().get("a").compensationResult());
        assertTrue(report.steps().get("a").compensationError().isEmpty());
        assertFalse(report.steps().get("a").compensated());
    }
}
