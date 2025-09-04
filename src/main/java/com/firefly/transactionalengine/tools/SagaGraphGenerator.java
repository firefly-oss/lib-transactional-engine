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


package com.firefly.transactionalengine.tools;

import com.firefly.transactionalengine.annotations.EnableTransactionalEngine;
import com.firefly.transactionalengine.registry.SagaDefinition;
import com.firefly.transactionalengine.registry.SagaRegistry;
import com.firefly.transactionalengine.registry.StepDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CLI tool to generate Graphviz DOT (and optionally PNG/SVG) diagrams for Sagas discovered
 * in a Spring application using this library.
 *
 * Usage (from a microservice using this library):
 *   mvn -q -Dexec.classpathScope=test \
 *       -Dexec.mainClass=com.firefly.transactionalengine.tools.SagaGraphGenerator \
 *       -Dexec.args="--base-packages com.my.service --output target/saga-graph.dot" exec:java
 *
 * Options:
 *   --base-packages <pkg1,pkg2>   Comma-separated base packages to scan (required)
 *   --output <path>               Output DOT file path (default: target/sagas.dot)
 *   --format <dot|png|svg>        Output format; if png/svg, attempts to run Graphviz 'dot' to render (default: dot)
 *   --verbose                     Extra logging to stderr
 */
public class SagaGraphGenerator {

    public static void main(String[] args) {
        Map<String, String> kv = new HashMap<>();
        boolean verbose = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--verbose".equals(a)) { verbose = true; continue; }
            if (a.startsWith("--")) {
                String key = a.substring(2);
                String value = (i + 1) < args.length && !args[i+1].startsWith("--") ? args[++i] : "";
                kv.put(key, value);
            }
        }

        String basePackages = kv.getOrDefault("base-packages", "").trim();
        if (basePackages.isEmpty()) {
            System.err.println("[SagaGraph] ERROR: --base-packages is required (comma-separated)");
            System.exit(2);
        }
        String output = kv.getOrDefault("output", "target/sagas.dot");
        String format = kv.getOrDefault("format", "dot").toLowerCase(Locale.ROOT);
        if (!List.of("dot","png","svg").contains(format)) {
            System.err.println("[SagaGraph] ERROR: --format must be one of: dot|png|svg");
            System.exit(2);
        }

        if (verbose) System.err.printf("[SagaGraph] Scanning packages: %s%n", basePackages);
        List<String> pkgs = Arrays.stream(basePackages.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        if (pkgs.isEmpty()) {
            System.err.println("[SagaGraph] ERROR: no valid packages provided");
            System.exit(2);
        }

        // Ensure output directory exists
        try {
            Path outPath = Path.of(output);
            Files.createDirectories(outPath.getParent() != null ? outPath.getParent() : Path.of("."));
        } catch (IOException e) {
            System.err.println("[SagaGraph] ERROR: cannot create output directory: " + e.getMessage());
            System.exit(3);
        }

        // Bootstrap a minimal Spring context that enables the engine and scans the given base packages
        AnnotationConfigApplicationContext ctx = null;
        try {
            ctx = new AnnotationConfigApplicationContext();
            // Register engine configuration
            ctx.register(EngineOnlyConfig.class);
            // Scan user-provided packages for @Saga orchestrators and step/compensation beans
            ctx.scan(pkgs.toArray(new String[0]));
            ctx.refresh();

            SagaRegistry registry = ctx.getBean(SagaRegistry.class);
            Collection<SagaDefinition> sagas = registry.getAll();
            if (sagas.isEmpty()) {
                System.err.println("[SagaGraph] WARN: No sagas found under packages: " + basePackages);
            }

            // Print computed DAG execution layers per saga (always visible to help debugging)
            for (SagaDefinition saga : sagas) {
                var layers = com.firefly.transactionalengine.engine.SagaTopology.buildLayers(saga);
                System.err.println("[SagaGraph] DAG layers for saga '" + saga.name + "':");
                String capStr = (saga.layerConcurrency <= 0 ? "unbounded" : Integer.toString(saga.layerConcurrency));
                for (int i = 0; i < layers.size(); i++) {
                    var layer = layers.get(i);
                    String mode = (layer.size() > 1 ? "parallel" : "sequential");
                    System.err.println("  L" + (i+1) + " (" + mode + ", size=" + layer.size() + ", cap=" + capStr + "): " + layer);
                }
            }

            String dot = buildDot(sagas);
            writeString(output, dot);
            if (verbose) System.err.println("[SagaGraph] Wrote DOT to: " + output);

            if (!"dot".equals(format)) {
                String imgOut = renderWithGraphviz(output, format, verbose);
                if (imgOut != null && verbose) {
                    System.err.println("[SagaGraph] Rendered " + format + ": " + imgOut);
                }
            }
        } catch (Exception e) {
            System.err.println("[SagaGraph] ERROR: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        } finally {
            if (ctx != null) ctx.close();
        }
    }

    /**
     * Minimal @Configuration that enables the engine. Package scanning is configured in the context (ctx.scan(...)).
     */
    @Configuration
    @EnableTransactionalEngine
    static class EngineOnlyConfig {
    }

    private static String buildDot(Collection<SagaDefinition> sagas) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph Sagas{\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  graph [fontname=Helvetica];\n");
        sb.append("  node  [fontname=Helvetica, shape=box, style=rounded];\n");
        sb.append("  edge  [fontname=Helvetica];\n");

        // Legend
        sb.append("  subgraph cluster_legend {\n");
        sb.append("    label=\"Legend\"; color=lightgrey; fontcolor=grey20;\n");
        sb.append("    legend_step   [label=\"Step\", shape=box, style=rounded];\n");
        sb.append("    legend_start  [label=\"Start step\", shape=box, style=\"rounded,bold\"];\n");
        sb.append("    legend_end    [label=\"Terminal step\", shape=box, style=\"rounded,filled\", fillcolor=lightblue];\n");
        sb.append("    legend_cpu    [label=\"CPU-bound\", shape=box, style=\"rounded,filled\", fillcolor=gold];\n");
        sb.append("    legend_comp   [label=\"Compensation\", shape=hexagon, style=dashed];\n");
        sb.append("    legend_dep [label=\"dependsOn\", shape=point, width=0.01, height=0.01];\n");
        sb.append("    legend_step -> legend_step [label=\"dependsOn\"];\n");
        sb.append("    legend_step -> legend_comp [style=dashed, label=\"compensate\"];\n");
        sb.append("  }\n");

        for (SagaDefinition saga : sagas) {
            String clusterName = sanitizeId("cluster_" + saga.name);
            sb.append("  subgraph ").append(clusterName).append(" {\n");
            sb.append("    label=\"").append(escape(saga.name)).append("\";\n");
            sb.append("    color=gray;\n");

            // Precompute dependents to find terminal nodes
            Map<String, Integer> outDegree = new HashMap<>();
            for (StepDefinition step : saga.steps.values()) {
                outDegree.put(step.id, 0);
            }
            for (StepDefinition step : saga.steps.values()) {
                for (String dep : step.dependsOn) {
                    outDegree.put(dep, outDegree.getOrDefault(dep, 0) + 1);
                }
            }

            // Declare nodes first, with styles
            for (StepDefinition step : saga.steps.values()) {
                String nodeId = nodeId(saga.name, step.id);
                StringBuilder attrs = new StringBuilder();
                String style = "rounded";
                List<String> extra = new ArrayList<>();
                boolean hasComp = step.compensateMethod != null || (step.compensateName != null && !step.compensateName.isBlank());
                if (hasComp) style = style + ",dashed"; // dashed border hints compensation
                boolean start = step.dependsOn == null || step.dependsOn.isEmpty();
                boolean terminal = outDegree.getOrDefault(step.id, 0) == 0;
                if (start) {
                    extra.add("penwidth=2");
                }
                if (terminal) {
                    extra.add("style=\"" + style + ",filled\"");
                    extra.add("fillcolor=lightblue");
                } else if (step.cpuBound) {
                    extra.add("style=\"" + style + ",filled\"");
                    extra.add("fillcolor=gold");
                } else {
                    extra.add("style=\"" + style + "\"");
                }

                attrs.append("label=\"")
                     .append(escape(step.id))
                     .append(nodeLabelAttrs(step))
                     .append("\"");
                for (String e : extra) attrs.append(", ").append(e);

                sb.append("    ").append(nodeId).append(" [").append(attrs).append("];\n");
            }

            // Explicitly group nodes by topological layers to visually paint and annotate the topology
            {
                var layers = com.firefly.transactionalengine.engine.SagaTopology.buildLayers(saga);
                String capStr = (saga.layerConcurrency <= 0 ? "unbounded" : Integer.toString(saga.layerConcurrency));
                for (int i = 0; i < layers.size(); i++) {
                    List<String> layer = layers.get(i);
                    if (layer.isEmpty()) continue;
                    String labelNode = sanitizeId("\"" + saga.name + ":L" + (i + 1) + "\"");
                    String mode = (layer.size() > 1 ? "parallel" : "sequential");
                    String label = "L" + (i + 1) + ": " + mode + "(size=" + layer.size() + ", cap=" + capStr + ")";
                    // Define a plaintext label node used to annotate the layer
                    sb.append("    ").append(labelNode).append(" [shape=plaintext, fontcolor=grey40, label=\"")
                      .append(escape(label)).append("\"];\n");
                    sb.append("    { rank=same; // Layer ").append(i + 1).append("\n");
                    sb.append("      ").append(labelNode).append(";\n");
                    for (String stepId : layer) {
                        sb.append("      ").append(nodeId(saga.name, stepId)).append(";\n");
                    }
                    sb.append("    }\n");
                }
            }

            // Edges from dependsOn -> step
            for (StepDefinition step : saga.steps.values()) {
                for (String dep : step.dependsOn) {
                    String from = nodeId(saga.name, dep);
                    String to = nodeId(saga.name, step.id);
                    sb.append("    ").append(from).append(" -> ").append(to).append(";\n");
                }
            }

            // Compensation pseudo-nodes and dashed edges
            for (StepDefinition step : saga.steps.values()) {
                boolean hasComp = step.compensateMethod != null || (step.compensateName != null && !step.compensateName.isBlank());
                if (!hasComp) continue;
                String compNode = compNodeId(saga.name, step.id);
                boolean external = step.compensateBean != null && step.compensateBean != (step.stepBean != null ? step.stepBean : saga.bean);
                String label = "compensate" + (external ? " (external)" : "");
                sb.append("    ").append(compNode).append(" [shape=hexagon, style=dashed, label=\"")
                  .append(escape(label)).append("\"];\n");
                sb.append("    ").append(nodeId(saga.name, step.id)).append(" -> ").append(compNode)
                  .append(" [style=dashed, color=grey40];\n");
            }

            sb.append("  }\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private static String nodeLabelAttrs(StepDefinition step) {
        List<String> parts = new ArrayList<>();
        if (step.retry > 0) parts.add("retry=" + step.retry);
        if (step.backoff != null && !step.backoff.isZero()) parts.add("backoff=" + step.backoff.toMillis() + "ms");
        if (step.timeout != null && !step.timeout.isZero()) parts.add("timeout=" + step.timeout.toMillis() + "ms");
        if (step.idempotencyKey != null && !step.idempotencyKey.isBlank()) parts.add("idempotent");
        if (step.compensateMethod != null || (step.compensateName != null && !step.compensateName.isBlank())) parts.add("comp");
        if (parts.isEmpty()) return "";
        return "\\n(" + escape(String.join(", ", parts)) + ")";
    }

    private static String nodeId(String sagaName, String stepId) {
        return sanitizeId("\"" + sagaName + ":" + stepId + "\"");
    }

    private static String compNodeId(String sagaName, String stepId) {
        return sanitizeId("\"comp:" + sagaName + ":" + stepId + "\"");
    }

    private static String sanitizeId(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if ((c >= 'a' && c <= 'z') ||
                (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') ||
                c == '_' || c == ':' || c == '"' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeString(String path, String content) throws IOException {
        File f = new File(path);
        if (f.getParentFile() != null && !f.getParentFile().exists()) {
            if (!f.getParentFile().mkdirs()) {
                throw new IOException("Failed to create directory: " + f.getParent());
            }
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
            w.write(content);
        }
    }

    private static String renderWithGraphviz(String dotFilePath, String format, boolean verbose) {
        String outPath = dotFilePath.replaceAll("\\.dot$", "") + "." + format;
        List<String> cmd = List.of("dot", "-T" + format, dotFilePath, "-o", outPath);
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            var out = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            if (code != 0) {
                System.err.println("[SagaGraph] WARN: Graphviz 'dot' failed (exit=" + code + ") output=\n" + out);
                return null;
            }
            if (verbose && !out.isBlank()) System.err.println("[SagaGraph] dot: \n" + out);
            return outPath;
        } catch (Exception e) {
            System.err.println("[SagaGraph] WARN: Could not run 'dot' (is Graphviz installed and on PATH?): " + e.getMessage());
            return null;
        }
    }
}
