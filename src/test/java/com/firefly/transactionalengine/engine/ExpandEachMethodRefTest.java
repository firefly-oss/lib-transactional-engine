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

import com.firefly.transactionalengine.annotations.SagaStep;
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

class ExpandEachMethodRefTest {

    static class Steps {
        @SagaStep(id = "ins")
        public static String insert(String in, SagaContext ctx) {
            return "ok-" + in;
        }

        @SagaStep(id = "fail")
        public static Void fail(Void in, SagaContext ctx) {
            throw new RuntimeException("boom");
        }
    }

    private SagaEngine newEngine() {
        SagaRegistry dummy = mock(SagaRegistry.class);
        return new SagaEngine(dummy, new SagaEvents() {});
    }

    @Test
    void expandsUsingMethodReferenceInputsToo() {
        // Given a saga with a step 'ins' and a downstream failing step
        SagaDefinition def = SagaBuilder.saga("ExpandByRef")
                .step("ins")
                    .handler((StepHandler<String, String>) (in, ctx) -> Mono.just("ok-" + in))
                    .compensation((arg, ctx) -> { ctx.putVariable("comp:" + arg, true); return Mono.empty(); })
                    .add()
                .step("fail").dependsOn("ins")
                    .handler((StepHandler<Void, Void>) (in, ctx) -> Mono.error(new RuntimeException("boom")))
                    .add()
                .build();

        SagaEngine engine = newEngine();
        SagaContext ctx = new SagaContext("corr-expand-ref-1");

        // Use forStep with method reference to specify input for step id derived from @SagaStep
        StepInputs inputs = StepInputs.builder()
                .forStep(Steps::insert, ExpandEach.of(List.of("A", "B", "C"), it -> (String) it))
                .build();

        SagaResult res = engine.execute(def, inputs, ctx).block();
        assertNotNull(res);
        assertFalse(res.isSuccess(), "Saga must fail to trigger compensation");

        // Each item gets its own step id using custom suffix: ins:A, ins:B, ins:C
        assertTrue(res.steps().containsKey("ins:A"));
        assertTrue(res.steps().containsKey("ins:B"));
        assertTrue(res.steps().containsKey("ins:C"));

        // Compensation ran per clone; verify flags via variables we set in compensation
        assertEquals(Boolean.TRUE, ctx.getVariable("comp:A"));
        assertEquals(Boolean.TRUE, ctx.getVariable("comp:B"));
        assertEquals(Boolean.TRUE, ctx.getVariable("comp:C"));
    }
}
