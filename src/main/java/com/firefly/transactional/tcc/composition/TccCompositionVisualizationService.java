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

package com.firefly.transactional.tcc.composition;

import com.firefly.transactional.tcc.config.TccCompositionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for visualizing TCC composition structure and execution flow.
 * <p>
 * Provides tools for generating visual representations of TCC compositions
 * for debugging, documentation, and monitoring purposes. Includes support
 * for three-phase protocol visualization and participant relationships.
 */
public class TccCompositionVisualizationService {
    
    private static final Logger log = LoggerFactory.getLogger(TccCompositionVisualizationService.class);
    
    private final TccCompositor tccCompositor;
    private final TccCompositionProperties.DevToolsProperties properties;
    
    public TccCompositionVisualizationService(TccCompositor tccCompositor,
                                            TccCompositionProperties.DevToolsProperties properties) {
        this.tccCompositor = tccCompositor;
        this.properties = properties;
        log.info("TCC composition visualization service initialized");
    }
    
    /**
     * Generates a Mermaid diagram representation of a TCC composition.
     * 
     * @param composition the TCC composition to visualize
     * @return Mermaid diagram as string
     */
    public String generateMermaidDiagram(TccComposition composition) {
        StringBuilder mermaid = new StringBuilder();
        mermaid.append("graph TD\n");
        
        // Add nodes for each TCC
        for (Map.Entry<String, TccComposition.CompositionTcc> entry : composition.tccs.entrySet()) {
            String tccId = entry.getKey();
            TccComposition.CompositionTcc tcc = entry.getValue();
            
            String nodeStyle = determineNodeStyle(tcc);
            String nodeLabel = formatNodeLabel(tcc);
            
            mermaid.append("    ").append(tccId).append("[\"").append(nodeLabel).append("\"]").append(nodeStyle).append("\n");
        }
        
        mermaid.append("\n");
        
        // Add dependency edges
        for (Map.Entry<String, TccComposition.CompositionTcc> entry : composition.tccs.entrySet()) {
            String tccId = entry.getKey();
            TccComposition.CompositionTcc tcc = entry.getValue();
            
            for (String dependency : tcc.dependencies) {
                mermaid.append("    ").append(dependency).append(" --> ").append(tccId).append("\n");
            }
            
            // Add parallel execution indicators
            for (String parallelTcc : tcc.parallelWith) {
                mermaid.append("    ").append(tccId).append(" -.-> ").append(parallelTcc)
                       .append(" ").append("style").append(" dotted").append("\n");
            }
        }
        
        // Add data flow edges
        mermaid.append("\n");
        for (Map.Entry<String, TccComposition.CompositionTcc> entry : composition.tccs.entrySet()) {
            String tccId = entry.getKey();
            TccComposition.CompositionTcc tcc = entry.getValue();
            
            for (Map.Entry<String, TccComposition.DataMapping> dataEntry : tcc.dataFromTccs.entrySet()) {
                String sourceTcc = dataEntry.getValue().sourceTccId;
                String participantId = dataEntry.getValue().sourceParticipantId;
                String dataKey = dataEntry.getValue().sourceKey;

                mermaid.append("    ").append(sourceTcc).append(" -.->|\"").append(participantId)
                       .append(":").append(dataKey).append("\"| ").append(tccId).append("\n");
            }
        }
        
        // Add TCC-specific styling
        mermaid.append("\n");
        mermaid.append("    classDef optional fill:#fff2cc,stroke:#d6b656\n");
        mermaid.append("    classDef timeout fill:#ffe6cc,stroke:#d79b00\n");
        mermaid.append("    classDef conditional fill:#e1d5e7,stroke:#9673a6\n");
        mermaid.append("    classDef tccPhase fill:#d5e8d4,stroke:#82b366\n");
        
        return mermaid.toString();
    }
    
    /**
     * Generates a DOT (Graphviz) representation of a TCC composition.
     * 
     * @param composition the TCC composition to visualize
     * @return DOT diagram as string
     */
    public String generateDotDiagram(TccComposition composition) {
        StringBuilder dot = new StringBuilder();
        dot.append("digraph TccComposition {\n");
        dot.append("    rankdir=TB;\n");
        dot.append("    node [shape=box, style=rounded];\n\n");
        
        // Add nodes
        for (Map.Entry<String, TccComposition.CompositionTcc> entry : composition.tccs.entrySet()) {
            String tccId = entry.getKey();
            TccComposition.CompositionTcc tcc = entry.getValue();
            
            String nodeAttributes = generateDotNodeAttributes(tcc);
            String nodeLabel = formatNodeLabel(tcc);
            
            dot.append("    \"").append(tccId).append("\" [label=\"").append(nodeLabel).append("\"")
               .append(nodeAttributes).append("];\n");
        }
        
        dot.append("\n");
        
        // Add edges
        for (Map.Entry<String, TccComposition.CompositionTcc> entry : composition.tccs.entrySet()) {
            String tccId = entry.getKey();
            TccComposition.CompositionTcc tcc = entry.getValue();
            
            // Dependency edges
            for (String dependency : tcc.dependencies) {
                dot.append("    \"").append(dependency).append("\" -> \"").append(tccId)
                   .append("\" [color=blue];\n");
            }
            
            // Parallel execution edges
            for (String parallelTcc : tcc.parallelWith) {
                dot.append("    \"").append(tccId).append("\" -> \"").append(parallelTcc)
                   .append("\" [style=dashed, color=green, label=\"parallel\"];\n");
            }
            
            // Data flow edges
            for (Map.Entry<String, TccComposition.DataMapping> dataEntry : tcc.dataFromTccs.entrySet()) {
                String sourceTcc = dataEntry.getValue().sourceTccId;
                String participantId = dataEntry.getValue().sourceParticipantId;
                String dataKey = dataEntry.getValue().sourceKey;

                dot.append("    \"").append(sourceTcc).append("\" -> \"").append(tccId)
                   .append("\" [style=dotted, color=red, label=\"").append(participantId)
                   .append(":").append(dataKey).append("\"];\n");
            }
        }
        
        dot.append("}\n");
        return dot.toString();
    }
    
    /**
     * Generates a text-based tree representation of a TCC composition.
     * 
     * @param composition the TCC composition to visualize
     * @return tree representation as string
     */
    public String generateTextTree(TccComposition composition) {
        StringBuilder tree = new StringBuilder();
        tree.append("TCC Composition: ").append(composition.name).append("\n");
        tree.append("Compensation Policy: ").append(composition.compensationPolicy).append("\n\n");
        
        // Build execution layers
        List<Set<String>> layers = buildExecutionLayers(composition);
        
        for (int i = 0; i < layers.size(); i++) {
            tree.append("Layer ").append(i + 1).append(":\n");
            
            for (String tccId : layers.get(i)) {
                TccComposition.CompositionTcc tcc = composition.tccs.get(tccId);
                tree.append("  ├─ ").append(formatTccInfo(tcc)).append("\n");
                
                // Add dependencies
                if (!tcc.dependencies.isEmpty()) {
                    tree.append("     │  Dependencies: ").append(String.join(", ", tcc.dependencies)).append("\n");
                }
                
                // Add parallel execution
                if (!tcc.parallelWith.isEmpty()) {
                    tree.append("     │  Parallel with: ").append(String.join(", ", tcc.parallelWith)).append("\n");
                }
                
                // Add data mappings
                if (!tcc.dataFromTccs.isEmpty()) {
                    tree.append("     │  Data from: ");
                    List<String> mappings = tcc.dataFromTccs.entrySet().stream()
                            .map(e -> e.getValue().sourceTccId + ":" + e.getValue().sourceParticipantId + ":" + e.getValue().sourceKey)
                            .collect(Collectors.toList());
                    tree.append(String.join(", ", mappings)).append("\n");
                }
            }
            
            if (i < layers.size() - 1) {
                tree.append("\n");
            }
        }
        
        return tree.toString();
    }
    
    /**
     * Generates a summary of the TCC composition structure.
     * 
     * @param composition the TCC composition to analyze
     * @return composition summary
     */
    public String generateCompositionSummary(TccComposition composition) {
        StringBuilder summary = new StringBuilder();
        summary.append("TCC Composition Summary\n");
        summary.append("======================\n\n");
        
        summary.append("Name: ").append(composition.name).append("\n");
        summary.append("Compensation Policy: ").append(composition.compensationPolicy).append("\n");
        summary.append("Total TCCs: ").append(composition.tccs.size()).append("\n");
        
        // Count optional TCCs
        long optionalCount = composition.tccs.values().stream()
                .mapToLong(tcc -> tcc.optional ? 1 : 0)
                .sum();
        summary.append("Optional TCCs: ").append(optionalCount).append("\n");
        
        // Count TCCs with timeouts
        long timeoutCount = composition.tccs.values().stream()
                .mapToLong(tcc -> tcc.timeoutMs > 0 ? 1 : 0)
                .sum();
        summary.append("TCCs with Timeouts: ").append(timeoutCount).append("\n");

        // Count data mappings
        long dataMappingCount = composition.tccs.values().stream()
                .mapToLong(tcc -> tcc.dataFromTccs.size())
                .sum();
        summary.append("Data Mappings: ").append(dataMappingCount).append("\n");

        // Execution layers
        List<Set<String>> layers = buildExecutionLayers(composition);
        summary.append("Execution layers: ").append(layers.size()).append("\n");

        // Parallel execution groups
        Set<String> parallelTccs = composition.tccs.values().stream()
                .filter(tcc -> !tcc.parallelWith.isEmpty())
                .map(tcc -> tcc.compositionId)
                .collect(Collectors.toSet());
        summary.append("TCCs with parallel execution: ").append(parallelTccs.size()).append("\n");

        return summary.toString();
    }
    
    private String determineNodeStyle(TccComposition.CompositionTcc tcc) {
        List<String> classes = new ArrayList<>();
        
        if (tcc.optional) {
            classes.add("optional");
        }
        if (tcc.timeoutMs > 0) {
            classes.add("timeout");
        }
        if (tcc.executionCondition != null) {
            classes.add("conditional");
        }
        
        classes.add("tccPhase");
        
        if (classes.isEmpty()) {
            return "";
        }
        
        return ":::" + String.join(" ", classes);
    }
    
    private String formatNodeLabel(TccComposition.CompositionTcc tcc) {
        StringBuilder label = new StringBuilder();
        label.append(tcc.tccName);
        
        if (tcc.optional) {
            label.append("\\n(optional)");
        }
        if (tcc.timeoutMs > 0) {
            label.append("\\n").append(tcc.timeoutMs).append("ms");
        }
        
        return label.toString();
    }
    
    private String generateDotNodeAttributes(TccComposition.CompositionTcc tcc) {
        StringBuilder attrs = new StringBuilder();
        
        if (tcc.optional) {
            attrs.append(", fillcolor=lightyellow, style=\"rounded,filled\"");
        } else if (tcc.timeoutMs > 0) {
            attrs.append(", fillcolor=lightblue, style=\"rounded,filled\"");
        } else {
            attrs.append(", fillcolor=lightgreen, style=\"rounded,filled\"");
        }
        
        return attrs.toString();
    }
    
    private String formatTccInfo(TccComposition.CompositionTcc tcc) {
        StringBuilder info = new StringBuilder();
        info.append(tcc.tccName).append(" (").append(tcc.compositionId).append(")");
        
        List<String> attributes = new ArrayList<>();
        if (tcc.optional) {
            attributes.add("optional");
        }
        if (tcc.timeoutMs > 0) {
            attributes.add(tcc.timeoutMs + "ms");
        }
        
        if (!attributes.isEmpty()) {
            info.append(" [").append(String.join(", ", attributes)).append("]");
        }
        
        return info.toString();
    }
    
    private List<Set<String>> buildExecutionLayers(TccComposition composition) {
        List<Set<String>> layers = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        Set<String> remaining = new HashSet<>(composition.tccs.keySet());
        
        while (!remaining.isEmpty()) {
            Set<String> currentLayer = new HashSet<>();
            
            for (String tccId : remaining) {
                TccComposition.CompositionTcc tcc = composition.tccs.get(tccId);
                if (tcc != null && processed.containsAll(tcc.dependencies)) {
                    currentLayer.add(tccId);
                }
            }
            
            if (currentLayer.isEmpty()) {
                // Circular dependency detected
                currentLayer.addAll(remaining);
            }
            
            layers.add(currentLayer);
            processed.addAll(currentLayer);
            remaining.removeAll(currentLayer);
        }
        
        return layers;
    }
}
