package com.catalis.transactionalengine.registry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable metadata for a discovered Saga orchestrator.
 * Holds the saga name, the original Spring bean (possibly a proxy) and the
 * unwrapped target instance, plus an ordered map of its steps.
 */
public class SagaDefinition {
    public final String name;
    public final Object bean; // original Spring bean (possibly proxy)
    public final Object target; // unwrapped target object for direct invocation
    /** Optional cap for concurrent steps within a layer. 0 means unbounded. */
    public final int layerConcurrency;
    public final Map<String, StepDefinition> steps = new LinkedHashMap<>();

    public SagaDefinition(String name, Object bean, Object target, int layerConcurrency) {
        this.name = name;
        this.bean = bean;
        this.target = target;
        this.layerConcurrency = layerConcurrency;
    }
}
