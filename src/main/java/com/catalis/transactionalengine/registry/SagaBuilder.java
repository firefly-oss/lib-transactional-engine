package com.catalis.transactionalengine.registry;

import com.catalis.transactionalengine.annotations.SagaStep;
import com.catalis.transactionalengine.engine.StepHandler;

import java.lang.reflect.Method;
import java.time.Duration;
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

    /**
     * Convenience to register a step using a method annotated with @SagaStep.
     * The step id will be read from the annotation.
     */
    public Step step(Method method) {
        if (method == null) throw new IllegalArgumentException("method");
        SagaStep ann = method.getAnnotation(SagaStep.class);
        if (ann == null) throw new IllegalArgumentException("Method " + method + " is not annotated with @SagaStep");
        Step s = new Step(ann.id());
        s.stepMethod = method;
        return s;
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
        private boolean jitter = false;
        private double jitterFactor = 0.5d;
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
        @Deprecated
        public Step backoffMs(long backoffMs) { this.backoffMs = backoffMs; return this; }
        @Deprecated
        public Step timeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; return this; }
        public Step backoff(Duration backoff) { this.backoffMs = backoff != null ? backoff.toMillis() : 0L; return this; }
        public Step timeout(Duration timeout) { this.timeoutMs = timeout != null ? timeout.toMillis() : 0L; return this; }
        public Step idempotencyKey(String key) { this.idempotencyKey = key != null ? key : ""; return this; }
        public Step compensateName(String name) { this.compensateName = name != null ? name : ""; return this; }
        public Step handler(StepHandler<?,?> handler) { this.handler = handler; return this; }
        /** Optional: set method directly when id was provided by constructor */
        public Step method(Method method) { this.stepMethod = method; return this; }
        /** Enable jitter with default factor (0.5). */
        public Step jitter() { this.jitter = true; return this; }
        /** Enable/disable jitter. */
        public Step jitter(boolean enabled) { this.jitter = enabled; return this; }
        /** Set jitter factor (0..1). */
        public Step jitterFactor(double factor) { this.jitterFactor = factor; return this; }

        public SagaBuilder add() {
            if (this.handler == null && this.stepMethod == null) {
                throw new IllegalStateException("Missing handler or step method for step '" + id + "' in saga '" + saga.name + "'");
            }
            StepDefinition sd = new StepDefinition(
                    id,
                    compensateName,
                    dependsOn,
                    retry,
                    backoffMs,
                    timeoutMs,
                    idempotencyKey,
                    jitter,
                    jitterFactor,
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
