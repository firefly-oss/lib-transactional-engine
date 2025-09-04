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
import com.firefly.transactionalengine.registry.SagaRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ExpandEachTest {

    private SagaEngine newEngine() {
        SagaRegistry dummy = mock(SagaRegistry.class);
        return new SagaEngine(dummy, new SagaEvents() {});
    }

    @Test
    void expandsListInputIntoPerItemStepsWithCompensation() {
        // Given a saga with a step 'ins' and a downstream failing step
        SagaDefinition def = SagaBuilder.saga("Expand")
                .step("ins")
                    .handler((StepHandler<String, String>) (in, ctx) -> Mono.just("ok-" + in))
                    .compensation((arg, ctx) -> { ctx.putVariable("comp:" + arg, true); return Mono.empty(); })
                    .add()
                .step("fail").dependsOn("ins")
                    .handler((StepHandler<Void, Void>) (in, ctx) -> Mono.error(new RuntimeException("boom")))
                    .add()
                .build();

        SagaEngine engine = newEngine();
        SagaContext ctx = new SagaContext("corr-expand-1");

        StepInputs inputs = StepInputs.builder()
                .forStepId("ins", ExpandEach.of(List.of("A", "B", "C")))
                .build();

        SagaResult res = engine.execute(def, inputs, ctx).block();
        assertNotNull(res);
        assertFalse(res.isSuccess(), "Saga must fail to trigger compensation");

        // Each item gets its own step id: ins#0, ins#1, ins#2
        assertTrue(res.steps().containsKey("ins#0"));
        assertTrue(res.steps().containsKey("ins#1"));
        assertTrue(res.steps().containsKey("ins#2"));

        // Compensation ran per clone; verify flags via variables we set in compensation
        assertEquals(Boolean.TRUE, ctx.getVariable("comp:A"));
        assertEquals(Boolean.TRUE, ctx.getVariable("comp:B"));
        assertEquals(Boolean.TRUE, ctx.getVariable("comp:C"));
    }
}
