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

package com.firefly.transactional.composition;

import com.firefly.transactional.engine.CompensationPolicy;
import com.firefly.transactional.engine.StepInputs;

import java.util.*;
import java.util.function.Function;

/**
 * Immutable definition of a saga composition workflow.
 * <p>
 * A SagaComposition represents a coordinated execution of multiple sagas with
 * defined dependencies, data flow, and execution patterns. It serves as the
 * blueprint for the CompositionExecutionOrchestrator to execute the workflow.
 */
public class SagaComposition {
    
    public final String name;
    public final CompensationPolicy compensationPolicy;
    public final Map<String, CompositionSaga> sagas;
    public final List<String> executionOrder;
    
    /**
     * Creates a new saga composition.
     * 
     * @param name the composition name
     * @param compensationPolicy the compensation policy for the composition
     * @param sagas the sagas in the composition
     * @param executionOrder the execution order of sagas
     */
    public SagaComposition(String name, 
                          CompensationPolicy compensationPolicy,
                          Map<String, CompositionSaga> sagas,
                          List<String> executionOrder) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.compensationPolicy = Objects.requireNonNull(compensationPolicy, "compensationPolicy cannot be null");
        this.sagas = Collections.unmodifiableMap(new LinkedHashMap<>(sagas));
        this.executionOrder = Collections.unmodifiableList(new ArrayList<>(executionOrder));
    }
    
    /**
     * Represents a saga within a composition with its execution configuration.
     */
    public static class CompositionSaga {
        public final String sagaName;
        public final String compositionId;
        public final Set<String> dependencies;
        public final Set<String> parallelWith;
        public final StepInputs inputs;
        public final Map<String, DataMapping> dataFromSagas;
        public final Function<SagaCompositionContext, Boolean> executionCondition;
        public final boolean optional;
        public final int timeoutMs;
        
        public CompositionSaga(String sagaName,
                              String compositionId,
                              Set<String> dependencies,
                              Set<String> parallelWith,
                              StepInputs inputs,
                              Map<String, DataMapping> dataFromSagas,
                              Function<SagaCompositionContext, Boolean> executionCondition,
                              boolean optional,
                              int timeoutMs) {
            this.sagaName = Objects.requireNonNull(sagaName, "sagaName cannot be null");
            this.compositionId = Objects.requireNonNull(compositionId, "compositionId cannot be null");
            this.dependencies = Collections.unmodifiableSet(new HashSet<>(dependencies));
            this.parallelWith = Collections.unmodifiableSet(new HashSet<>(parallelWith));
            this.inputs = inputs;
            this.dataFromSagas = Collections.unmodifiableMap(new HashMap<>(dataFromSagas));
            this.executionCondition = executionCondition;
            this.optional = optional;
            this.timeoutMs = timeoutMs;
        }
    }
    
    /**
     * Represents data mapping between sagas in a composition.
     */
    public static class DataMapping {
        public final String sourceSagaId;
        public final String sourceKey;
        public final String targetKey;
        public final Function<Object, Object> transformer;
        
        public DataMapping(String sourceSagaId, String sourceKey, String targetKey) {
            this(sourceSagaId, sourceKey, targetKey, Function.identity());
        }
        
        public DataMapping(String sourceSagaId, 
                          String sourceKey, 
                          String targetKey, 
                          Function<Object, Object> transformer) {
            this.sourceSagaId = Objects.requireNonNull(sourceSagaId, "sourceSagaId cannot be null");
            this.sourceKey = Objects.requireNonNull(sourceKey, "sourceKey cannot be null");
            this.targetKey = Objects.requireNonNull(targetKey, "targetKey cannot be null");
            this.transformer = Objects.requireNonNull(transformer, "transformer cannot be null");
        }
    }
    
    /**
     * Gets the sagas that can be executed in parallel at the current execution layer.
     * 
     * @param completedSagas the set of already completed saga IDs
     * @return a list of saga IDs that can be executed in parallel
     */
    public List<String> getExecutableParallelSagas(Set<String> completedSagas) {
        List<String> executable = new ArrayList<>();
        
        for (String sagaId : executionOrder) {
            if (completedSagas.contains(sagaId)) {
                continue;
            }
            
            CompositionSaga saga = sagas.get(sagaId);
            if (saga == null) {
                continue;
            }
            
            // Check if all dependencies are satisfied
            boolean dependenciesSatisfied = saga.dependencies.isEmpty() || 
                                          completedSagas.containsAll(saga.dependencies);
            
            if (dependenciesSatisfied) {
                executable.add(sagaId);
                
                // Add all sagas that should execute in parallel with this one
                for (String parallelSagaId : saga.parallelWith) {
                    if (!completedSagas.contains(parallelSagaId) && 
                        !executable.contains(parallelSagaId)) {
                        CompositionSaga parallelSaga = sagas.get(parallelSagaId);
                        if (parallelSaga != null && 
                            (parallelSaga.dependencies.isEmpty() || 
                             completedSagas.containsAll(parallelSaga.dependencies))) {
                            executable.add(parallelSagaId);
                        }
                    }
                }
            }
        }
        
        return executable;
    }
    
    /**
     * Checks if the composition has any remaining sagas to execute.
     * 
     * @param completedSagas the set of completed saga IDs
     * @return true if there are more sagas to execute
     */
    public boolean hasRemainingWork(Set<String> completedSagas) {
        return sagas.keySet().size() > completedSagas.size();
    }
}
