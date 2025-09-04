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


package com.firefly.transactionalengine.core;

import com.firefly.transactionalengine.registry.SagaDefinition;
import com.firefly.transactionalengine.registry.StepDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Detects whether a saga can be optimized for single-threaded execution.
 * 
 * <p>A saga can be optimized (use HashMap instead of ConcurrentHashMap) when:
 * <ul>
 *   <li>All topology layers contain only one step (sequential execution)</li>
 *   <li>No step dependencies create concurrent execution within the same layer</li>
 * </ul>
 * 
 * <p>When ANY layer contains multiple steps, those steps execute concurrently and
 * require thread-safe access to the shared SagaContext.
 */
public class SagaOptimizationDetector {
    private static final Logger log = LoggerFactory.getLogger(SagaOptimizationDetector.class);
    
    /**
     * Determines if the given saga can be safely optimized for single-threaded execution.
     * 
     * @param saga the saga definition to analyze
     * @return true if optimization is safe (sequential execution), false if concurrent execution occurs
     */
    public static boolean canOptimize(SagaDefinition saga) {
        if (saga == null || saga.steps == null || saga.steps.isEmpty()) {
            return true; // Empty saga can be optimized
        }
        
        List<List<String>> layers = buildTopologyLayers(saga);
        
        // Check if all layers have only one step
        boolean isSequential = layers.stream().allMatch(layer -> layer.size() <= 1);
        
        if (log.isDebugEnabled()) {
            int totalSteps = saga.steps.size();
            int layerCount = layers.size();
            int maxLayerSize = layers.stream().mapToInt(List::size).max().orElse(0);
            
            log.debug("Saga optimization analysis for '{}': {} steps, {} layers, max layer size: {}, can optimize: {}", 
                    saga.name, totalSteps, layerCount, maxLayerSize, isSequential);
            
            if (!isSequential) {
                log.debug("Concurrent execution detected in saga '{}' - layers with multiple steps: {}", 
                        saga.name, layers.stream()
                            .filter(layer -> layer.size() > 1)
                            .map(layer -> layer.size() + " steps")
                            .toList());
            }
        }
        
        return isSequential;
    }
    
    /**
     * Build execution topology layers from saga definition.
     * Steps in the same layer can execute concurrently.
     * 
     * @param saga the saga definition
     * @return list of layers, where each layer is a list of step IDs that can execute concurrently
     */
    private static List<List<String>> buildTopologyLayers(SagaDefinition saga) {
        Map<String, Set<String>> dependencies = new HashMap<>();
        Set<String> allSteps = new HashSet<>(saga.steps.keySet());
        
        // Build dependency map
        for (StepDefinition step : saga.steps.values()) {
            dependencies.put(step.id, new HashSet<>(step.dependsOn));
        }
        
        List<List<String>> layers = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        
        while (processed.size() < allSteps.size()) {
            List<String> currentLayer = new ArrayList<>();
            
            // Find steps that have all dependencies satisfied
            for (String stepId : allSteps) {
                if (!processed.contains(stepId)) {
                    Set<String> stepDeps = dependencies.get(stepId);
                    if (stepDeps == null || processed.containsAll(stepDeps)) {
                        currentLayer.add(stepId);
                    }
                }
            }
            
            if (currentLayer.isEmpty()) {
                // Circular dependency detected - add remaining steps to break the cycle
                log.warn("Circular dependency detected in saga '{}', adding remaining steps: {}", 
                        saga.name, 
                        allSteps.stream().filter(s -> !processed.contains(s)).toList());
                
                for (String stepId : allSteps) {
                    if (!processed.contains(stepId)) {
                        currentLayer.add(stepId);
                    }
                }
            }
            
            layers.add(currentLayer);
            processed.addAll(currentLayer);
        }
        
        return layers;
    }
    
    /**
     * Provides detailed analysis of saga execution patterns for debugging and monitoring.
     * 
     * @param saga the saga definition to analyze
     * @return detailed analysis result
     */
    public static OptimizationAnalysis analyze(SagaDefinition saga) {
        if (saga == null || saga.steps == null || saga.steps.isEmpty()) {
            return new OptimizationAnalysis(true, 0, 0, 0, List.of(), "Empty saga");
        }
        
        List<List<String>> layers = buildTopologyLayers(saga);
        boolean canOptimize = layers.stream().allMatch(layer -> layer.size() <= 1);
        int totalSteps = saga.steps.size();
        int layerCount = layers.size();
        int maxLayerSize = layers.stream().mapToInt(List::size).max().orElse(0);
        
        List<LayerInfo> layerDetails = layers.stream()
                .map(layer -> new LayerInfo(layer.size(), new ArrayList<>(layer)))
                .toList();
        
        String reason = canOptimize ? 
                "All layers have single step (sequential execution)" : 
                String.format("Found %d concurrent layers with multiple steps", 
                        layers.stream().mapToInt(layer -> layer.size() > 1 ? 1 : 0).sum());
        
        return new OptimizationAnalysis(canOptimize, totalSteps, layerCount, maxLayerSize, layerDetails, reason);
    }
    
    /**
     * Detailed analysis result of saga optimization potential.
     */
    public record OptimizationAnalysis(
            boolean canOptimize,
            int totalSteps,
            int layerCount,
            int maxLayerSize,
            List<LayerInfo> layers,
            String reason
    ) {}
    
    /**
     * Information about a specific execution layer.
     */
    public record LayerInfo(
            int stepCount,
            List<String> stepIds
    ) {}
}