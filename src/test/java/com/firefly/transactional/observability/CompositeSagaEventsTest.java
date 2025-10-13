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


package com.firefly.transactional.observability;

import com.firefly.transactional.saga.observability.CompositeSagaEvents;
import com.firefly.transactional.saga.observability.SagaEvents;
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
