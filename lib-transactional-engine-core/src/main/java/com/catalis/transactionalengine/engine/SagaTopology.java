package com.catalis.transactionalengine.engine;

import com.catalis.transactionalengine.registry.SagaDefinition;
import com.catalis.transactionalengine.registry.StepDefinition;

import java.util.*;

/**
 * Topology utilities for Saga definitions: builds execution layers from the step DAG.
 */
public final class SagaTopology {
    private SagaTopology() {}

    /**
     * Build execution layers (topological levels) from the saga's step graph.
     * Steps with indegree=0 form the first layer; removing them may unlock subsequent layers.
     * No cycle detection here (validated in registry). Order inside a layer is not guaranteed.
     */
    public static List<List<String>> buildLayers(SagaDefinition saga) {
        // Use LinkedHashMap to preserve deterministic iteration order based on saga.steps insertion
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (String id : saga.steps.keySet()) {
            indegree.putIfAbsent(id, 0);
            adj.putIfAbsent(id, new ArrayList<>());
        }
        for (StepDefinition sd : saga.steps.values()) {
            for (String dep : sd.dependsOn) {
                indegree.put(sd.id, indegree.getOrDefault(sd.id, 0) + 1);
                adj.get(dep).add(sd.id);
            }
        }
        List<List<String>> layers = new ArrayList<>();
        Queue<String> q = new ArrayDeque<>();
        // Fill initial queue in the exact order of saga.steps keys for determinism
        for (String id : saga.steps.keySet()) {
            if (indegree.get(id) == 0) q.add(id);
        }
        while (!q.isEmpty()) {
            int size = q.size();
            List<String> layer = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                String u = q.poll();
                layer.add(u);
                for (String v : adj.getOrDefault(u, List.of())) {
                    indegree.put(v, indegree.get(v) - 1);
                    if (indegree.get(v) == 0) q.add(v);
                }
            }
            layers.add(layer);
        }
        return layers;
    }
}
