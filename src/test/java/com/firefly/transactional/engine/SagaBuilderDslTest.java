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


package com.firefly.transactional.saga.engine;

import com.firefly.transactional.saga.annotations.SagaStep;
import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.saga.core.SagaResult;
import com.firefly.transactional.saga.registry.SagaBuilder;
import com.firefly.transactional.saga.registry.SagaDefinition;
import com.firefly.transactional.saga.registry.SagaRegistry;
import com.firefly.transactional.saga.observability.SagaEvents;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SagaBuilderDslTest {

    static class Orchestrator {
        @SagaStep(id = "mrA")
        public Mono<String> a(SagaContext ctx) { return Mono.just("A"); }
        public Mono<String> b(SagaContext ctx) { return Mono.just("B"); }
    }

    private SagaEngine newEngine() {
        return new SagaEngine(mock(SagaRegistry.class), new SagaEvents(){});
    }

    @Test
    void methodRefStepInfersIdFromAnnotation() {
        SagaDefinition def = SagaBuilder.saga("DSL")
                .step(Orchestrator::a) // should infer id "mrA" from annotation
                .handlerCtx(ctx -> Mono.just("A"))
                .add()
                .build();

        assertTrue(def.steps.containsKey("mrA"));
    }

    @Test
    void methodRefStepUsesMethodNameWhenNoAnnotation() {
        SagaDefinition def = SagaBuilder.named("DSL2")
                .step(Orchestrator::b) // no annotation -> id "b"
                .handlerCtx(ctx -> Mono.just("B"))
                .add()
                .build();

        assertTrue(def.steps.containsKey("b"));
    }

    @Test
    void handlerCtxExecutesEndToEnd() {
        SagaDefinition def = SagaBuilder.saga("Run")
                .step(Orchestrator::b)
                .handlerCtx(ctx -> Mono.just("OK"))
                .add()
                .build();
        SagaEngine engine = newEngine();
        SagaContext ctx = new SagaContext();
        SagaResult res = engine.execute(def, StepInputs.empty(), ctx).block();
        assertNotNull(res);
        assertTrue(res.isSuccess());
        assertEquals("OK", res.resultOf("b", String.class).orElse(null));
    }
}
