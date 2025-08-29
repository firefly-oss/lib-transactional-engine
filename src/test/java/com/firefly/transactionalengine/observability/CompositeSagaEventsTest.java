package com.firefly.transactionalengine.observability;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeSagaEventsTest {

    static class CapturingEvents implements SagaEvents {
        final List<String> calls = new ArrayList<>();
        @Override public void onStart(String sagaName, String sagaId) { calls.add("start:"+sagaName+":"+sagaId); }
        @Override public void onStepStarted(String sagaName, String sagaId, String stepId) { calls.add("step:"+stepId); }
        @Override public void onCompleted(String sagaName, String sagaId, boolean success) { calls.add("completed:"+success); }
    }

    @Test
    void compositeFansOutCalls() {
        CapturingEvents a = new CapturingEvents();
        CapturingEvents b = new CapturingEvents();
        CompositeSagaEvents composite = new CompositeSagaEvents(java.util.List.of(a, b));

        composite.onStart("S", "id1");
        composite.onStepStarted("S", "id1", "x");
        composite.onCompleted("S", "id1", true);

        assertTrue(a.calls.contains("start:S:id1"));
        assertTrue(b.calls.contains("start:S:id1"));
        assertTrue(a.calls.contains("step:x"));
        assertTrue(b.calls.contains("step:x"));
        assertTrue(a.calls.contains("completed:true"));
        assertTrue(b.calls.contains("completed:true"));
    }
}
