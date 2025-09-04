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


package com.firefly.transactionalengine.observability;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeSagaEventsNewEventsTest {

    static class CapturingEvents implements SagaEvents {
        final List<String> calls = new ArrayList<>();
        @Override public void onCompensationStarted(String sagaName, String sagaId, String stepId) { calls.add("start:"+stepId); }
        @Override public void onCompensationRetry(String sagaName, String sagaId, String stepId, int attempt) { calls.add("retry:"+stepId+":"+attempt); }
        @Override public void onCompensationSkipped(String sagaName, String sagaId, String stepId, String reason) { calls.add("skip:"+stepId+":"+reason); }
        @Override public void onCompensationCircuitOpen(String sagaName, String sagaId, String stepId) { calls.add("circuit:"+stepId); }
        @Override public void onCompensationBatchCompleted(String sagaName, String sagaId, List<String> stepIds, boolean allSuccessful) { calls.add("batch:"+allSuccessful+":"+String.join(",", stepIds)); }
    }

    @Test
    void compositeFansOutNewCompensationEvents() {
        CapturingEvents a = new CapturingEvents();
        CapturingEvents b = new CapturingEvents();
        CompositeSagaEvents composite = new CompositeSagaEvents(java.util.List.of(a, b));

        composite.onCompensationStarted("S", "id1", "x");
        composite.onCompensationRetry("S", "id1", "x", 2);
        composite.onCompensationSkipped("S", "id1", "y", "circuit open");
        composite.onCompensationCircuitOpen("S", "id1", "x");
        composite.onCompensationBatchCompleted("S", "id1", java.util.List.of("x","y"), false);

        for (var c: List.of(a, b)) {
            assertTrue(c.calls.contains("start:x"));
            assertTrue(c.calls.contains("retry:x:2"));
            assertTrue(c.calls.contains("skip:y:circuit open"));
            assertTrue(c.calls.contains("circuit:x"));
            assertTrue(c.calls.contains("batch:false:x,y"));
        }
    }
}
