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

package com.firefly.transactional.saga.composition;

import com.firefly.transactional.saga.config.SagaCompositionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for visualizing saga composition structure and execution flow.
 * <p>
 * Provides tools for generating visual representations of compositions
 * for debugging, documentation, and monitoring purposes.
 */
public class CompositionVisualizationService {
    
    private static final Logger log = LoggerFactory.getLogger(CompositionVisualizationService.class);
    
    private final SagaCompositor sagaCompositor;
    private final SagaCompositionProperties.DevToolsProperties properties;
    
    public CompositionVisualizationService(SagaCompositor sagaCompositor,
                                         SagaCompositionProperties.DevToolsProperties properties) {
        this.sagaCompositor = sagaCompositor;
        this.properties = properties;
        log.info("Composition visualization service initialized");
    }
    
    /**
     * Generates a Mermaid diagram representation of a composition.
     * 
     * @param composition the composition to visualize
     * @return Mermaid diagram as string
     */
    public String generateMermaidDiagram(SagaComposition composition) {
        StringBuilder mermaid = new StringBuilder();
        mermaid.append("graph TD\n");
        
        // Add nodes for each saga
        for (Map.Entry<String, SagaComposition.CompositionSaga> entry : composition.sagas.entrySet()) {
            String sagaId = entry.getKey();
            SagaComposition.CompositionSaga saga = entry.getValue();
            
            String nodeStyle = determineNodeStyle(saga);
            String nodeLabel = formatNodeLabel(saga);
            
            mermaid.append("    ").append(sagaId).append("[\"").append(nodeLabel).append("\"]").append(nodeStyle).append("\n");
        }
        
        mermaid.append("\n");
        
        // Add dependency edges
        for (Map.Entry<String, SagaComposition.CompositionSaga> entry : composition.sagas.entrySet()) {
            String sagaId = entry.getKey();
            SagaComposition.CompositionSaga saga = entry.getValue();
            
            for (String dependency : saga.dependencies) {
                mermaid.append("    ").append(dependency).append(" --> ").append(sagaId).append("\n");
            }
            
            // Add parallel execution indicators
            for (String parallelSaga : saga.parallelWith) {
                mermaid.append("    ").append(sagaId).append(" -.-> ").append(parallelSaga)
                       .append(" ").append("style").append(" dotted").append("\n");
            }
        }
        
        // Add data flow edges
        mermaid.append("\n");
        for (Map.Entry<String, SagaComposition.CompositionSaga> entry : composition.sagas.entrySet()) {
            String sagaId = entry.getKey();
            SagaComposition.CompositionSaga saga = entry.getValue();
            
            for (Map.Entry<String, SagaComposition.DataMapping> dataEntry : saga.dataFromSagas.entrySet()) {
                String sourceSaga = dataEntry.getValue().sourceSagaId;
                String dataKey = dataEntry.getValue().sourceKey;
                
                mermaid.append("    ").append(sourceSaga).append(" -.->|\"").append(dataKey).append("\"| ")
                       .append(sagaId).append("\n");
            }
        }
        
        // Add styling
        mermaid.append("\n");
        mermaid.append("    classDef optional fill:#fff2cc,stroke:#d6b656\n");
        mermaid.append("    classDef timeout fill:#ffe6cc,stroke:#d79b00\n");
        mermaid.append("    classDef conditional fill:#e1d5e7,stroke:#9673a6\n");
        
        return mermaid.toString();
    }
    
    /**
     * Generates a DOT (Graphviz) representation of a composition.
     * 
     * @param composition the composition to visualize
     * @return DOT diagram as string
     */
    public String generateDotDiagram(SagaComposition composition) {
        StringBuilder dot = new StringBuilder();
        dot.append("digraph SagaComposition {\n");
        dot.append("    rankdir=TB;\n");
        dot.append("    node [shape=box, style=rounded];\n\n");
        
        // Add nodes
        for (Map.Entry<String, SagaComposition.CompositionSaga> entry : composition.sagas.entrySet()) {
            String sagaId = entry.getKey();
            SagaComposition.CompositionSaga saga = entry.getValue();
            
            String nodeAttributes = generateDotNodeAttributes(saga);
            String nodeLabel = formatNodeLabel(saga);
            
            dot.append("    \"").append(sagaId).append("\" [label=\"").append(nodeLabel).append("\"")
               .append(nodeAttributes).append("];\n");
        }
        
        dot.append("\n");
        
        // Add edges
        for (Map.Entry<String, SagaComposition.CompositionSaga> entry : composition.sagas.entrySet()) {
            String sagaId = entry.getKey();
            SagaComposition.CompositionSaga saga = entry.getValue();
            
            // Dependency edges
            for (String dependency : saga.dependencies) {
                dot.append("    \"").append(dependency).append("\" -> \"").append(sagaId)
                   .append("\" [color=blue];\n");
            }
            
            // Parallel execution edges
            for (String parallelSaga : saga.parallelWith) {
                dot.append("    \"").append(sagaId).append("\" -> \"").append(parallelSaga)
                   .append("\" [style=dashed, color=green, label=\"parallel\"];\n");
            }
            
            // Data flow edges
            for (Map.Entry<String, SagaComposition.DataMapping> dataEntry : saga.dataFromSagas.entrySet()) {
                String sourceSaga = dataEntry.getValue().sourceSagaId;
                String dataKey = dataEntry.getValue().sourceKey;
                
                dot.append("    \"").append(sourceSaga).append("\" -> \"").append(sagaId)
                   .append("\" [style=dotted, color=red, label=\"").append(dataKey).append("\"];\n");
            }
        }
        
        dot.append("}\n");
        return dot.toString();
    }
    
    /**
     * Generates a text-based tree representation of a composition.
     * 
     * @param composition the composition to visualize
     * @return tree representation as string
     */
    public String generateTextTree(SagaComposition composition) {
        StringBuilder tree = new StringBuilder();
        tree.append("Composition: ").append(composition.name).append("\n");
        tree.append("Compensation Policy: ").append(composition.compensationPolicy).append("\n\n");
        
        // Build execution layers
        List<Set<String>> layers = buildExecutionLayers(composition);
        
        for (int i = 0; i < layers.size(); i++) {
            tree.append("Layer ").append(i + 1).append(":\n");
            
            for (String sagaId : layers.get(i)) {
                SagaComposition.CompositionSaga saga = composition.sagas.get(sagaId);
                tree.append("  ├─ ").append(formatSagaInfo(saga)).append("\n");
                
                // Add dependencies
                if (!saga.dependencies.isEmpty()) {
                    tree.append("     │  Dependencies: ").append(String.join(", ", saga.dependencies)).append("\n");
                }
                
                // Add parallel execution
                if (!saga.parallelWith.isEmpty()) {
                    tree.append("     │  Parallel with: ").append(String.join(", ", saga.parallelWith)).append("\n");
                }
                
                // Add data mappings
                if (!saga.dataFromSagas.isEmpty()) {
                    tree.append("     │  Data from: ");
                    List<String> mappings = saga.dataFromSagas.entrySet().stream()
                            .map(entry -> entry.getValue().sourceSagaId + "." + entry.getValue().sourceKey)
                            .collect(Collectors.toList());
                    tree.append(String.join(", ", mappings)).append("\n");
                }
            }
            tree.append("\n");
        }
        
        return tree.toString();
    }
    
    /**
     * Generates execution statistics for a composition.
     * 
     * @param composition the composition to analyze
     * @return statistics as formatted string
     */
    public String generateExecutionStats(SagaComposition composition) {
        StringBuilder stats = new StringBuilder();
        stats.append("Composition Analysis: ").append(composition.name).append("\n");
        stats.append("═".repeat(50)).append("\n\n");
        
        // Basic statistics
        stats.append("Basic Statistics:\n");
        stats.append("  Total Sagas: ").append(composition.sagas.size()).append("\n");
        
        long optionalSagas = composition.sagas.values().stream().filter(s -> s.optional).count();
        stats.append("  Optional Sagas: ").append(optionalSagas).append("\n");
        
        long sagasWithTimeouts = composition.sagas.values().stream().filter(s -> s.timeoutMs > 0).count();
        stats.append("  Sagas with Timeouts: ").append(sagasWithTimeouts).append("\n");
        
        long conditionalSagas = composition.sagas.values().stream()
                .filter(s -> s.executionCondition != null).count();
        stats.append("  Conditional Sagas: ").append(conditionalSagas).append("\n\n");
        
        // Execution layers
        List<Set<String>> layers = buildExecutionLayers(composition);
        stats.append("Execution Layers: ").append(layers.size()).append("\n");
        for (int i = 0; i < layers.size(); i++) {
            stats.append("  Layer ").append(i + 1).append(": ").append(layers.get(i).size()).append(" sagas\n");
        }
        stats.append("\n");
        
        // Complexity analysis
        stats.append("Complexity Analysis:\n");
        int totalDependencies = composition.sagas.values().stream()
                .mapToInt(s -> s.dependencies.size()).sum();
        stats.append("  Total Dependencies: ").append(totalDependencies).append("\n");
        
        int totalDataMappings = composition.sagas.values().stream()
                .mapToInt(s -> s.dataFromSagas.size()).sum();
        stats.append("  Total Data Mappings: ").append(totalDataMappings).append("\n");
        
        int parallelRelationships = composition.sagas.values().stream()
                .mapToInt(s -> s.parallelWith.size()).sum();
        stats.append("  Parallel Relationships: ").append(parallelRelationships).append("\n");
        
        return stats.toString();
    }
    
    private String determineNodeStyle(SagaComposition.CompositionSaga saga) {
        List<String> classes = new ArrayList<>();
        
        if (saga.optional) {
            classes.add("optional");
        }
        if (saga.timeoutMs > 0) {
            classes.add("timeout");
        }
        if (saga.executionCondition != null) {
            classes.add("conditional");
        }
        
        if (!classes.isEmpty()) {
            return ":::" + String.join(" ", classes);
        }
        return "";
    }
    
    private String formatNodeLabel(SagaComposition.CompositionSaga saga) {
        StringBuilder label = new StringBuilder();
        label.append(saga.compositionId);
        
        if (saga.optional) {
            label.append("\\n(optional)");
        }
        if (saga.timeoutMs > 0) {
            label.append("\\n").append(saga.timeoutMs).append("ms");
        }
        
        return label.toString();
    }
    
    private String generateDotNodeAttributes(SagaComposition.CompositionSaga saga) {
        List<String> attributes = new ArrayList<>();
        
        if (saga.optional) {
            attributes.add("fillcolor=lightyellow");
            attributes.add("style=\"rounded,filled\"");
        }
        if (saga.timeoutMs > 0) {
            attributes.add("color=orange");
        }
        
        return attributes.isEmpty() ? "" : ", " + String.join(", ", attributes);
    }
    
    private String formatSagaInfo(SagaComposition.CompositionSaga saga) {
        StringBuilder info = new StringBuilder();
        info.append(saga.compositionId).append(" (").append(saga.sagaName).append(")");
        
        List<String> flags = new ArrayList<>();
        if (saga.optional) flags.add("optional");
        if (saga.timeoutMs > 0) flags.add(saga.timeoutMs + "ms");
        
        if (!flags.isEmpty()) {
            info.append(" [").append(String.join(", ", flags)).append("]");
        }
        
        return info.toString();
    }
    
    private List<Set<String>> buildExecutionLayers(SagaComposition composition) {
        List<Set<String>> layers = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        
        while (processed.size() < composition.sagas.size()) {
            Set<String> currentLayer = new HashSet<>();
            
            for (Map.Entry<String, SagaComposition.CompositionSaga> entry : composition.sagas.entrySet()) {
                String sagaId = entry.getKey();
                SagaComposition.CompositionSaga saga = entry.getValue();
                
                if (!processed.contains(sagaId) && processed.containsAll(saga.dependencies)) {
                    currentLayer.add(sagaId);
                }
            }
            
            if (currentLayer.isEmpty()) {
                // Circular dependency or other issue
                break;
            }
            
            layers.add(currentLayer);
            processed.addAll(currentLayer);
        }
        
        return layers;
    }
}
