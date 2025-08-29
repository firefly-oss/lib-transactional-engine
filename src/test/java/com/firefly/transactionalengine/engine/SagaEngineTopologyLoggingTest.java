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

class SagaEngineTopologyLoggingTest {

    private SagaEngine newEngine(SagaEvents events) {
        SagaRegistry dummy = mock(SagaRegistry.class);
        return new SagaEngine(dummy, events);
    }

    @Test
    void alwaysLogsTopologyLayersAtInfo() {
        // Prepare a simple linear saga: a -> b -> c
        SagaDefinition def = SagaBuilder.saga("Topo").
                step("a").handler((StepHandler<Void, String>) (input, ctx) -> Mono.just("ra")).add().
                step("b").dependsOn("a").handler((StepHandler<Void, String>) (input, ctx) -> Mono.just("rb")).add().
                step("c").dependsOn("b").handler((StepHandler<Void, String>) (input, ctx) -> Mono.just("rc")).add().
                build();

        // Capture logs from SagaEngine
        Logger logger = (Logger) LoggerFactory.getLogger(SagaEngine.class);
        Level old = logger.getLevel();
        logger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            SagaEngine engine = newEngine(new NoopEvents());
            SagaContext ctx = new SagaContext("corr-topo");
            SagaResult result = engine.execute(def, StepInputs.builder().build(), ctx).block();
            assertNotNull(result);

            // Find pretty topology log entry only
            boolean foundPretty = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(msg -> msg.contains("\"saga_topology\"")
                            && msg.contains("\"layers_pretty\"")
                            && msg.contains("\"saga\":\"Topo\"")
                            && msg.contains("\"sagaId\":\"" + ctx.correlationId() + "\"")
                            && msg.contains("L1 [a]")
                            && msg.contains("-> L2 [b]")
                            && msg.contains("-> L3 [c]"));
            assertTrue(foundPretty, "Expected only pretty topology log to be present with multi-line representation");

            // Also confirm the topology is exposed in context
            List<List<String>> layers = ctx.topologyLayersView();
            assertEquals(3, layers.size());
            assertEquals(List.of("a"), layers.get(0));
            assertEquals(List.of("b"), layers.get(1));
            assertEquals(List.of("c"), layers.get(2));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(old);
        }
    }

    static class NoopEvents implements SagaEvents {}
}
