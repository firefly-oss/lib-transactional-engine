package com.catalis.transactionalengine.engine;

import com.catalis.transactionalengine.annotations.SagaStep;
import com.catalis.transactionalengine.core.SagaContext;

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

    public static Builder builder() {
        return new Builder();
    }

    public static StepInputs empty() {
        return new Builder().build();
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

    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final Map<String, StepInputResolver> resolvers = new LinkedHashMap<>();

        public Builder forStep(Method stepMethod, Object input) {
            Objects.requireNonNull(stepMethod, "stepMethod");
            String id = extractId(stepMethod);
            values.put(id, input);
            return this;
        }

        public Builder forStep(Method stepMethod, StepInputResolver resolver) {
            Objects.requireNonNull(stepMethod, "stepMethod");
            Objects.requireNonNull(resolver, "resolver");
            String id = extractId(stepMethod);
            resolvers.put(id, resolver);
            return this;
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
