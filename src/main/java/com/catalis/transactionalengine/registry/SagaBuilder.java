package com.catalis.transactionalengine.registry;

import com.catalis.transactionalengine.engine.StepHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fluent builder to construct SagaDefinition programmatically without annotations.
 * Steps can be provided via functional StepHandler, enabling a simpler execution style
 * while keeping the classic annotation-based approach intact.
 */
public class SagaBuilder {
    private final SagaDefinition saga;

    private SagaBuilder(String name) {
        this.saga = new SagaDefinition(name, null, null);
    }

    public static SagaBuilder saga(String name) {
        return new SagaBuilder(name);
    }

    public Step step(String id) {
        return new Step(id);
    }

    public SagaDefinition build() {
        return saga;
    }

    public class Step {
        private final String id;
        private String compensateName = "";
        private final List<String> dependsOn = new ArrayList<>();
        private int retry = 0;
        private long backoffMs = 0;
        private long timeoutMs = 0;
        private String idempotencyKey = "";
        private StepHandler<?,?> handler;
        private Method stepMethod; // optional: allow method-based in future

        private Step(String id) {
            this.id = id;
        }

        public Step dependsOn(String... ids) {
            if (ids != null && ids.length > 0) this.dependsOn.addAll(Arrays.asList(ids));
            return this;
        }

        public Step retry(int retry) { this.retry = retry; return this; }
        public Step backoffMs(long backoffMs) { this.backoffMs = backoffMs; return this; }
        public Step timeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; return this; }
        public Step idempotencyKey(String key) { this.idempotencyKey = key != null ? key : ""; return this; }
        public Step compensateName(String name) { this.compensateName = name != null ? name : ""; return this; }
        public Step handler(StepHandler<?,?> handler) { this.handler = handler; return this; }

        public SagaBuilder add() {
            StepDefinition sd = new StepDefinition(
                    id,
                    compensateName,
                    dependsOn,
                    retry,
                    backoffMs,
                    timeoutMs,
                    idempotencyKey,
                    stepMethod
            );
            sd.handler = handler;
            if (saga.steps.putIfAbsent(id, sd) != null) {
                throw new IllegalStateException("Duplicate step id '" + id + "' in saga '" + saga.name + "'");
            }
            return SagaBuilder.this;
        }
    }
}
