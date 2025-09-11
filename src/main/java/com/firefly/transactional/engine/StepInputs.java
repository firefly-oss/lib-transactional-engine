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

import com.firefly.transactional.annotations.SagaStep;
import com.firefly.transactional.core.SagaContext;
import com.firefly.transactional.tools.MethodRefs;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable, typed DSL to provide per-step inputs without exposing Map<String,Object> in the public API.
 * Supports both concrete values and lazy resolvers that are evaluated against the current SagaContext
 * right before step execution. Resolved values are cached so that compensation can reuse the original input.
 */
public final class StepInputs {
    private final Map<String, Object> values;            // concrete inputs by step id
    private final Map<String, StepInputResolver> resolvers; // lazy resolvers by step id

    // Cache for materialized resolver values; not exposed to callers
    private final Map<String, Object> cache = new ConcurrentHashMap<>();

    private StepInputs(Map<String, Object> values, Map<String, StepInputResolver> resolvers) {
        this.values = values;
        this.resolvers = resolvers;
    }

    /**
     * Create a new empty builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a new builder pre-populated from an existing StepInputs instance.
     * Useful to extend inputs fluently without mutating the original.
     */
    public static Builder builderFrom(StepInputs existing) {
        Builder b = new Builder();
        if (existing != null) {
            b.values.putAll(existing.values);
            b.resolvers.putAll(existing.resolvers);
        }
        return b;
    }

    /**
     * Convenience factory for a single step id to value mapping.
     */
    public static StepInputs of(String stepId, Object input) {
        return builder().forStepId(stepId, input).build();
    }

    /**
     * Convenience factory to create StepInputs from a map of concrete values.
     */
    public static StepInputs of(Map<String, Object> values) {
        Builder b = builder();
        if (values != null) values.forEach(b::forStepId);
        return b.build();
    }

    public static StepInputs empty() {
        return new Builder().build();
    }

    /**
     * Return the raw concrete value configured for a step id if present (without evaluating resolvers).
     * Package-private: intended for engine use only.
     */
    Object rawValue(String stepId) {
        return values.get(stepId);
    }

    /**
     * Resolve the input for a given step id, evaluating a resolver if present and caching the result.
     * Package-private: intended for engine use only.
     */
    Object resolveFor(String stepId, SagaContext ctx) {
        if (values.containsKey(stepId)) return values.get(stepId);
        Object cached = cache.get(stepId);
        if (cached != null) return cached;
        StepInputResolver resolver = resolvers.get(stepId);
        if (resolver == null) return null;
        Object resolved = resolver.resolve(ctx);
        if (resolved != null) cache.put(stepId, resolved);
        return resolved;
    }

    /**
     * Produce a materialized view of inputs, evaluating all resolvers and returning an unmodifiable map.
     * Package-private: intended for engine compensation use.
     */
    Map<String, Object> materializedView(SagaContext ctx) {
        // Only include concrete values and already-resolved inputs; do not force-evaluate pending resolvers
        Map<String, Object> all = new LinkedHashMap<>(values.size() + cache.size());
        all.putAll(values);
        all.putAll(cache);
        return Collections.unmodifiableMap(all);
    }

    /**
     * Force materialization of all present resolvers and return an immutable map of inputs.
     * This is useful when the caller wants deterministic input capture up-front (e.g., for audit).
     */
    public Map<String, Object> materializeAll(SagaContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        for (Map.Entry<String, StepInputResolver> e : resolvers.entrySet()) {
            String id = e.getKey();
            if (!cache.containsKey(id)) {
                Object resolved = e.getValue().resolve(ctx);
                if (resolved != null) cache.put(id, resolved);
            }
        }
        Map<String, Object> all = new LinkedHashMap<>(values.size() + cache.size());
        all.putAll(values);
        all.putAll(cache);
        return Collections.unmodifiableMap(all);
    }

    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final Map<String, StepInputResolver> resolvers = new LinkedHashMap<>();

        public Builder forStep(Method stepMethod, Object input) {
            Objects.requireNonNull(stepMethod, "stepMethod");
            String id = extractId(stepMethod);
            values.put(id, input);
            return this;
        }

        // Overloads to support method references (Class::method) via serializable lambdas
        public <A, R> Builder forStep(MethodRefs.Fn1<A, R> ref, Object input) {
            return forStep(MethodRefs.methodOf(ref), input);
        }
        public <A, B, R> Builder forStep(MethodRefs.Fn2<A, B, R> ref, Object input) {
            return forStep(MethodRefs.methodOf(ref), input);
        }
        public <A, B, C, R> Builder forStep(MethodRefs.Fn3<A, B, C, R> ref, Object input) {
            return forStep(MethodRefs.methodOf(ref), input);
        }
        public <A, B, C, D, R> Builder forStep(MethodRefs.Fn4<A, B, C, D, R> ref, Object input) {
            return forStep(MethodRefs.methodOf(ref), input);
        }

        public Builder forStep(Method stepMethod, StepInputResolver resolver) {
            Objects.requireNonNull(stepMethod, "stepMethod");
            Objects.requireNonNull(resolver, "resolver");
            String id = extractId(stepMethod);
            resolvers.put(id, resolver);
            return this;
        }

        // Overloads to support method references with lazy resolver
        public <A, R> Builder forStep(MethodRefs.Fn1<A, R> ref, StepInputResolver resolver) {
            return forStep(MethodRefs.methodOf(ref), resolver);
        }
        public <A, B, R> Builder forStep(MethodRefs.Fn2<A, B, R> ref, StepInputResolver resolver) {
            return forStep(MethodRefs.methodOf(ref), resolver);
        }
        public <A, B, C, R> Builder forStep(MethodRefs.Fn3<A, B, C, R> ref, StepInputResolver resolver) {
            return forStep(MethodRefs.methodOf(ref), resolver);
        }
        public <A, B, C, D, R> Builder forStep(MethodRefs.Fn4<A, B, C, D, R> ref, StepInputResolver resolver) {
            return forStep(MethodRefs.methodOf(ref), resolver);
        }

        /** Pragmatic convenience: allow addressing by step id string when method ref is not convenient. */
        public Builder forStepId(String stepId, Object input) {
            Objects.requireNonNull(stepId, "stepId");
            values.put(stepId, input);
            return this;
        }

        public Builder forStepId(String stepId, StepInputResolver resolver) {
            Objects.requireNonNull(stepId, "stepId");
            Objects.requireNonNull(resolver, "resolver");
            resolvers.put(stepId, resolver);
            return this;
        }

        /** Bulk add of concrete inputs by step id. Null map is ignored. */
        public Builder forSteps(Map<String, Object> inputs) {
            if (inputs != null) inputs.forEach(this::forStepId);
            return this;
        }

        /** Bulk add of lazy resolvers by step id. Null map is ignored. */
        public Builder withResolvers(Map<String, StepInputResolver> resolvers) {
            if (resolvers != null) resolvers.forEach(this::forStepId);
            return this;
        }

        public StepInputs build() {
            return new StepInputs(
                    Collections.unmodifiableMap(new LinkedHashMap<>(values)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(resolvers))
            );
        }

        private static String extractId(Method m) {
            SagaStep ann = m.getAnnotation(SagaStep.class);
            if (ann == null) {
                throw new IllegalArgumentException("Method " + m + " is not annotated with @SagaStep");
            }
            return ann.id();
        }
    }
}
