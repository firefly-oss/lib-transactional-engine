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
 * Fluent builder for creating saga compositions.
 * <p>
 * Provides a DSL for defining complex saga workflows with dependencies,
 * parallel execution, data flow, and conditional execution patterns.
 */
public class SagaCompositionBuilder {
    
    private final String compositionName;
    private CompensationPolicy compensationPolicy = CompensationPolicy.STRICT_SEQUENTIAL;
    private final Map<String, SagaComposition.CompositionSaga> sagas = new LinkedHashMap<>();
    private final List<String> executionOrder = new ArrayList<>();
    
    public SagaCompositionBuilder(String compositionName) {
        this.compositionName = Objects.requireNonNull(compositionName, "compositionName cannot be null");
    }
    
    /**
     * Sets the compensation policy for the entire composition.
     * 
     * @param policy the compensation policy
     * @return this builder
     */
    public SagaCompositionBuilder compensationPolicy(CompensationPolicy policy) {
        this.compensationPolicy = Objects.requireNonNull(policy, "policy cannot be null");
        return this;
    }
    
    /**
     * Starts defining a saga within the composition.
     * 
     * @param sagaName the name of the saga to add
     * @return a saga builder for configuring the saga
     */
    public SagaBuilder saga(String sagaName) {
        return new SagaBuilder(sagaName);
    }
    
    /**
     * Builds the final saga composition.
     * 
     * @return the constructed saga composition
     */
    public SagaComposition build() {
        if (sagas.isEmpty()) {
            throw new IllegalStateException("Composition must contain at least one saga");
        }
        
        // Validate dependencies
        validateDependencies();
        
        // Build execution order based on dependencies
        List<String> order = buildExecutionOrder();
        
        return new SagaComposition(compositionName, compensationPolicy, sagas, order);
    }
    
    private void validateDependencies() {
        for (SagaComposition.CompositionSaga saga : sagas.values()) {
            for (String dependency : saga.dependencies) {
                if (!sagas.containsKey(dependency)) {
                    throw new IllegalStateException(
                        String.format("Saga '%s' depends on unknown saga '%s'", 
                                    saga.compositionId, dependency));
                }
            }
            
            for (String parallel : saga.parallelWith) {
                if (!sagas.containsKey(parallel)) {
                    throw new IllegalStateException(
                        String.format("Saga '%s' declares parallel execution with unknown saga '%s'", 
                                    saga.compositionId, parallel));
                }
            }
        }
    }
    
    private List<String> buildExecutionOrder() {
        // Simple topological sort for now
        List<String> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        
        for (String sagaId : sagas.keySet()) {
            if (!visited.contains(sagaId)) {
                topologicalSort(sagaId, visited, visiting, order);
            }
        }
        
        return order;
    }
    
    private void topologicalSort(String sagaId, Set<String> visited, Set<String> visiting, List<String> order) {
        if (visiting.contains(sagaId)) {
            throw new IllegalStateException("Circular dependency detected involving saga: " + sagaId);
        }
        
        if (visited.contains(sagaId)) {
            return;
        }
        
        visiting.add(sagaId);
        
        SagaComposition.CompositionSaga saga = sagas.get(sagaId);
        for (String dependency : saga.dependencies) {
            topologicalSort(dependency, visited, visiting, order);
        }
        
        visiting.remove(sagaId);
        visited.add(sagaId);
        order.add(sagaId);
    }
    
    /**
     * Builder for configuring individual sagas within a composition.
     */
    public class SagaBuilder {
        private final String sagaName;
        private String compositionId;
        private final Set<String> dependencies = new HashSet<>();
        private final Set<String> parallelWith = new HashSet<>();
        private StepInputs inputs = StepInputs.builder().build();
        private final Map<String, SagaComposition.DataMapping> dataFromSagas = new HashMap<>();
        private Function<SagaCompositionContext, Boolean> executionCondition = ctx -> true;
        private boolean optional = false;
        private int timeoutMs = 0;
        
        public SagaBuilder(String sagaName) {
            this.sagaName = Objects.requireNonNull(sagaName, "sagaName cannot be null");
            this.compositionId = sagaName; // Default composition ID is the saga name
        }
        
        /**
         * Sets a custom ID for this saga within the composition.
         * 
         * @param id the composition-specific ID
         * @return this saga builder
         */
        public SagaBuilder withId(String id) {
            this.compositionId = Objects.requireNonNull(id, "id cannot be null");
            return this;
        }
        
        /**
         * Adds a dependency on another saga in the composition.
         * 
         * @param sagaId the ID of the saga this saga depends on
         * @return this saga builder
         */
        public SagaBuilder dependsOn(String sagaId) {
            this.dependencies.add(Objects.requireNonNull(sagaId, "sagaId cannot be null"));
            return this;
        }
        
        /**
         * Declares that this saga should execute in parallel with another saga.
         * 
         * @param sagaId the ID of the saga to execute in parallel with
         * @return this saga builder
         */
        public SagaBuilder executeInParallelWith(String sagaId) {
            this.parallelWith.add(Objects.requireNonNull(sagaId, "sagaId cannot be null"));
            return this;
        }
        
        /**
         * Sets the inputs for this saga.
         * 
         * @param inputs the step inputs
         * @return this saga builder
         */
        public SagaBuilder withInputs(StepInputs inputs) {
            this.inputs = Objects.requireNonNull(inputs, "inputs cannot be null");
            return this;
        }
        
        /**
         * Adds a simple input value for this saga.
         * 
         * @param key the input key
         * @param value the input value
         * @return this saga builder
         */
        public SagaBuilder withInput(String key, Object value) {
            this.inputs = StepInputs.builder()
                .forSteps(this.inputs.materializeAll(new com.firefly.transactional.core.SagaContext()))
                .forStepId(key, value)
                .build();
            return this;
        }
        
        /**
         * Maps data from another saga's result to this saga's input.
         * 
         * @param sourceSagaId the source saga ID
         * @param sourceKey the key in the source saga's result
         * @param targetKey the key for this saga's input
         * @return this saga builder
         */
        public SagaBuilder withDataFrom(String sourceSagaId, String sourceKey, String targetKey) {
            this.dataFromSagas.put(targetKey, 
                new SagaComposition.DataMapping(sourceSagaId, sourceKey, targetKey));
            return this;
        }
        
        /**
         * Maps data from another saga's result to this saga's input with the same key.
         * 
         * @param sourceSagaId the source saga ID
         * @param key the key in both source and target
         * @return this saga builder
         */
        public SagaBuilder withDataFrom(String sourceSagaId, String key) {
            return withDataFrom(sourceSagaId, key, key);
        }
        
        /**
         * Sets a condition for executing this saga.
         * 
         * @param condition the execution condition
         * @return this saga builder
         */
        public SagaBuilder executeIf(Function<SagaCompositionContext, Boolean> condition) {
            this.executionCondition = Objects.requireNonNull(condition, "condition cannot be null");
            return this;
        }
        
        /**
         * Marks this saga as optional (failures won't fail the entire composition).
         * 
         * @return this saga builder
         */
        public SagaBuilder optional() {
            this.optional = true;
            return this;
        }
        
        /**
         * Sets a timeout for this saga execution.
         * 
         * @param timeoutMs the timeout in milliseconds
         * @return this saga builder
         */
        public SagaBuilder timeout(int timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }
        
        /**
         * Adds this saga to the composition and returns the composition builder.
         * 
         * @return the composition builder
         */
        public SagaCompositionBuilder add() {
            SagaComposition.CompositionSaga compositionSaga = new SagaComposition.CompositionSaga(
                sagaName, compositionId, dependencies, parallelWith, inputs, 
                dataFromSagas, executionCondition, optional, timeoutMs);
            
            if (sagas.putIfAbsent(compositionId, compositionSaga) != null) {
                throw new IllegalStateException("Duplicate saga ID in composition: " + compositionId);
            }
            
            return SagaCompositionBuilder.this;
        }
    }
}
