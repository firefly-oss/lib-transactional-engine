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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.firefly.transactional.core.SagaContext;
import com.firefly.transactional.core.SagaResult;
import com.firefly.transactional.observability.SagaEvents;
import com.firefly.transactional.registry.SagaBuilder;
import com.firefly.transactional.registry.SagaDefinition;
import com.firefly.transactional.registry.SagaRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SagaEngineTopologyParallelLoggingTest {

    private SagaEngine newEngine(SagaEvents events) {
        SagaRegistry dummy = mock(SagaRegistry.class);
        return new SagaEngine(dummy, events);
    }

    @Test
    void logsParallelLayerWithDetails() {
        // Saga with parallel first layer: a, b -> c
        SagaDefinition def = SagaBuilder.saga("TopoPar").
                step("a").handler((StepHandler<Void, String>) (input, ctx) -> Mono.just("ra")).add().
                step("b").handler((StepHandler<Void, String>) (input, ctx) -> Mono.just("rb")).add().
                step("c").dependsOn("a", "b").handler((StepHandler<Void, String>) (input, ctx) -> Mono.just("rc")).add().
                build();

        Logger logger = (Logger) LoggerFactory.getLogger(SagaEngine.class);
        Level old = logger.getLevel();
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            SagaEngine engine = newEngine(new NoopEvents());
            SagaContext ctx = new SagaContext("corr-par");
            SagaResult result = engine.execute(def, StepInputs.builder().build(), ctx).block();
            assertNotNull(result);

            List<List<String>> layers = ctx.topologyLayersView();
            assertEquals(2, layers.size());
            assertEquals(List.of("a", "b"), layers.get(0));
            assertEquals(List.of("c"), layers.get(1));

            boolean foundPretty = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(msg -> msg.contains("\"saga_topology\"")
                            && msg.contains("\"layers_pretty\"")
                            && msg.contains("TopoPar")
                            && msg.contains("L1 [a, b]")
                            && msg.contains("-> L2 [c]"));
            assertTrue(foundPretty, "Expected pretty topology log to include parallel layer and next sequential layer");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(old);
        }
    }

    static class NoopEvents implements SagaEvents {}
}
