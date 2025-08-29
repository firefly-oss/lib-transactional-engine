package com.firefly.transactionalengine.engine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.firefly.transactionalengine.core.SagaContext;
import com.firefly.transactionalengine.core.SagaResult;
import com.firefly.transactionalengine.observability.SagaEvents;
import com.firefly.transactionalengine.registry.SagaBuilder;
import com.firefly.transactionalengine.registry.SagaDefinition;
import com.firefly.transactionalengine.registry.SagaRegistry;
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
