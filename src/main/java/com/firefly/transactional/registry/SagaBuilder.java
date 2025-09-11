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


package com.firefly.transactional.registry;

import com.firefly.transactional.annotations.SagaStep;
import com.firefly.transactional.core.SagaContext;
import com.firefly.transactional.engine.StepHandler;
import com.firefly.transactional.tools.MethodRefs;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Fluent builder to construct SagaDefinition programmatically without annotations.
 * Steps can be provided via functional StepHandler, enabling a simpler execution style
 * while keeping the classic annotation-based approach intact.
 */
public class SagaBuilder {
    private final SagaDefinition saga;

    private SagaBuilder(String name) {
        this.saga = new SagaDefinition(name, null, null, 0);
    }

    /** Alias for saga(name) for readability in docs/DSL samples. */
    public static SagaBuilder named(String name) { return saga(name); }

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

    // --- Method reference overloads (Class::method) ---
    public <A, R> Step step(MethodRefs.Fn1<A, R> ref) { return stepFromRef(ref); }
    public <A, B, R> Step step(MethodRefs.Fn2<A, B, R> ref) { return stepFromRef(ref); }
    public <A, B, C, R> Step step(MethodRefs.Fn3<A, B, C, R> ref) { return stepFromRef(ref); }
    public <A, B, C, D, R> Step step(MethodRefs.Fn4<A, B, C, D, R> ref) { return stepFromRef(ref); }

    private Step stepFromRef(java.io.Serializable ref) {
        if (ref == null) throw new IllegalArgumentException("method reference");
        Method m = MethodRefs.methodOf(ref);
        String id = extractId(m);
        Step s = new Step(id);
        // Keep a reference to the method so @SetVariable on that method can still be honored after handler execution
        s.stepMethod = m;
        return s;
    }

    private String extractId(Method m) {
        SagaStep ann = m.getAnnotation(SagaStep.class);
        if (ann != null && ann.id() != null && !ann.id().isBlank()) return ann.id();
        return m.getName();
    }

    public SagaDefinition build() {
        return saga;
    }

    public class Step {
        private final String id;
        private String compensateName = "";
        private final List<String> dependsOn = new ArrayList<>();
        private int retry = 0;
        private Duration backoff = null;
        private Duration timeout = null;
        private String idempotencyKey = "";
        private boolean jitter = false;
        private double jitterFactor = 0.5d;
        private StepHandler<?,?> handler;
        private Method stepMethod; // optional: allow method-based in future
        private BiFunction<Object, SagaContext, Mono<Void>> compensationFn;
        // Compensation-specific optional overrides for DSL usage
        private Integer compensationRetry = null;
        private Duration compensationBackoff = null;
        private Duration compensationTimeout = null;
        private boolean compensationCritical = false;

        private Step(String id) {
            this.id = id;
        }

        public Step dependsOn(String... ids) {
            if (ids != null && ids.length > 0) this.dependsOn.addAll(Arrays.asList(ids));
            return this;
        }

        public Step retry(int retry) { this.retry = retry; return this; }
        public Step backoff(Duration backoff) { this.backoff = backoff; return this; }
        /** Convenience overload: set backoff in milliseconds. */
        public Step backoffMs(long ms) { this.backoff = (ms >= 0 ? Duration.ofMillis(ms) : null); return this; }
        public Step timeout(Duration timeout) { this.timeout = timeout; return this; }
        /** Convenience overload: set timeout in milliseconds. */
        public Step timeoutMs(long ms) { this.timeout = (ms >= 0 ? Duration.ofMillis(ms) : null); return this; }
        public Step idempotencyKey(String key) { this.idempotencyKey = key != null ? key : ""; return this; }
        public Step compensateName(String name) { this.compensateName = name != null ? name : ""; return this; }

        // Compensation-specific overrides for DSL
        public Step compensationRetry(int retry) { this.compensationRetry = retry; return this; }
        public Step compensationBackoff(Duration d) { this.compensationBackoff = d; return this; }
        public Step compensationBackoffMs(long ms) { this.compensationBackoff = (ms >= 0 ? Duration.ofMillis(ms) : null); return this; }
        public Step compensationTimeout(Duration d) { this.compensationTimeout = d; return this; }
        public Step compensationTimeoutMs(long ms) { this.compensationTimeout = (ms >= 0 ? Duration.ofMillis(ms) : null); return this; }
        public Step compensationCritical(boolean critical) { this.compensationCritical = critical; return this; }

        // Core StepHandler setter
        public Step handler(StepHandler<?,?> handler) { this.handler = handler; return this; }

        // Developer-friendly handler overloads
        public <I, O> Step handler(BiFunction<I, SagaContext, Mono<O>> fn) {
            if (fn == null) throw new IllegalArgumentException("handler");
            this.handler = new StepHandler<I, O>() {
                @Override public Mono<O> execute(I input, SagaContext ctx) { return fn.apply(input, ctx); }
            };
            return this;
        }
        public <O> Step handlerCtx(Function<SagaContext, Mono<O>> fn) {
            if (fn == null) throw new IllegalArgumentException("handler");
            this.handler = new StepHandler<Void, O>() {
                @Override public Mono<O> execute(Void input, SagaContext ctx) { return fn.apply(ctx); }
            };
            return this;
        }
        public <I, O> Step handlerInput(Function<I, Mono<O>> fn) {
            if (fn == null) throw new IllegalArgumentException("handler");
            this.handler = new StepHandler<I, O>() {
                @Override public Mono<O> execute(I input, SagaContext ctx) { return fn.apply(input); }
            };
            return this;
        }
        public <O> Step handler(Supplier<Mono<O>> fn) {
            if (fn == null) throw new IllegalArgumentException("handler");
            this.handler = new StepHandler<Void, O>() {
                @Override public Mono<O> execute(Void input, SagaContext ctx) { return fn.get(); }
            };
            return this;
        }

        /** Optional: set method directly when id was provided by constructor */
        public Step method(Method method) { this.stepMethod = method; return this; }

        // Compensation convenience overloads (for handler-based steps)
        public Step compensation(BiFunction<Object, SagaContext, Mono<Void>> fn) { this.compensationFn = fn; return this; }
        public Step compensationCtx(Function<SagaContext, Mono<Void>> fn) {
            this.compensationFn = (arg, ctx) -> fn.apply(ctx);
            return this;
        }
        public Step compensationArg(Function<Object, Mono<Void>> fn) {
            this.compensationFn = (arg, ctx) -> fn.apply(arg);
            return this;
        }
        public Step compensation(Supplier<Mono<Void>> fn) {
            this.compensationFn = (arg, ctx) -> fn.get();
            return this;
        }

        /** Enable jitter with default factor (0.5). */
        public Step jitter() { this.jitter = true; return this; }
        /** Enable/disable jitter. */
        public Step jitter(boolean enabled) { this.jitter = enabled; return this; }
        /** Set jitter factor (0..1). */
        public Step jitterFactor(double factor) { this.jitterFactor = factor; return this; }

        public SagaBuilder add() {
            // In programmatic sagas (no Spring bean), we require a handler for execution.
            if (saga.bean == null) {
                if (this.handler == null) {
                    throw new IllegalStateException("Missing handler for step '" + id + "' in programmatic saga '" + saga.name + "'");
                }
            } else {
                if (this.handler == null && this.stepMethod == null) {
                    throw new IllegalStateException("Missing handler or step method for step '" + id + "' in saga '" + saga.name + "'");
                }
            }
            StepDefinition sd = new StepDefinition(
                    id,
                    compensateName,
                    dependsOn,
                    retry,
                    backoff,
                    timeout,
                    idempotencyKey,
                    jitter,
                    jitterFactor,
                    false,
                    stepMethod
            );
            if (this.handler != null && this.compensationFn != null) {
                StepHandler<?,?> base = this.handler;
                this.handler = new StepHandler<Object, Object>() {
                    @SuppressWarnings("unchecked")
                    @Override public Mono<Object> execute(Object input, SagaContext ctx) { return ((StepHandler<Object, Object>) base).execute(input, ctx); }
                    @Override public Mono<Void> compensate(Object arg, SagaContext ctx) { return compensationFn.apply(arg, ctx); }
                };
            }
            sd.handler = handler;
            // Apply DSL-provided compensation overrides when defined
            if (this.compensationRetry != null) sd.compensationRetry = this.compensationRetry;
            if (this.compensationBackoff != null) sd.compensationBackoff = this.compensationBackoff;
            if (this.compensationTimeout != null) sd.compensationTimeout = this.compensationTimeout;
            sd.compensationCritical = this.compensationCritical;
            if (saga.steps.putIfAbsent(id, sd) != null) {
                throw new IllegalStateException("Duplicate step id '" + id + "' in saga '" + saga.name + "'");
            }
            return SagaBuilder.this;
        }
    }
}
