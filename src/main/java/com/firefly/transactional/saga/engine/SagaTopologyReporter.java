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


package com.firefly.transactional.saga.engine;

import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.saga.registry.SagaDefinition;
import com.firefly.transactional.saga.registry.StepDefinition;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralizes building, exposing and logging of Saga topological layers to keep SagaEngine clean.
 */
public final class SagaTopologyReporter {
    private SagaTopologyReporter() {}

    /**
     * Compute the topology layers for the given saga, expose them (and per-step dependencies) in the context,
     * and emit a pretty multi-line log entry using the provided logger.
     * Returns the computed layers for further orchestration.
     */
    public static List<List<String>> exposeAndLog(SagaDefinition saga, SagaContext ctx, Logger log) {
        List<List<String>> layers = SagaTopology.buildLayers(saga);
        // Expose topology in the execution context for better accessibility
        ctx.setTopologyLayers(layers);
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (Map.Entry<String, StepDefinition> e : saga.steps.entrySet()) {
            deps.put(e.getKey(), List.copyOf(e.getValue().dependsOn));
        }
        ctx.setStepDependencies(deps);

        // Build a pretty, multi-line representation for developers (only one we keep)
        String capStr = (saga.layerConcurrency <= 0 ? "unbounded" : Integer.toString(saga.layerConcurrency));
        StringBuilder pretty = new StringBuilder();
        pretty.append("Topology for ").append(saga.name)
              .append(" (cap=").append(capStr).append(")\n");
        for (int i = 0; i < layers.size(); i++) {
            List<String> layer = layers.get(i);
            int size = layer.size();
            String mode = size > 1 ? "parallel" : "sequential";
            if (i == 0) {
                pretty.append("L").append(i + 1)
                      .append(" [").append(String.join(", ", layer)).append("] ")
                      .append("(").append(mode).append(", size=").append(size).append(")\n");
            } else {
                pretty.append("-> L").append(i + 1)
                      .append(" [").append(String.join(", ", layer)).append("] ")
                      .append("(").append(mode).append(", size=").append(size).append(")\n");
            }
        }
        // Log always at INFO (tests rely on SagaEngine logger level INFO)
        log.info(SagaLogUtil.json(
                "saga_topology","layers_pretty",
                "saga", saga.name,
                "sagaId", ctx.correlationId(),
                "layers_pretty", pretty.toString()
        ));

        return layers;
    }
}
