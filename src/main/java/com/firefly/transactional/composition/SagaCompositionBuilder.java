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
import com.firefly.transactional.config.SagaCompositionProperties;

import java.util.*;
import java.util.function.Function;

/**
 * Fluent builder for creating saga compositions.
 * <p>
 * Provides a DSL for defining complex saga workflows with dependencies,
 * parallel execution, data flow, and conditional execution patterns.
 * <p>
 * Enhanced with real-time validation and helpful error messages to provide
 * the best developer experience possible.
 */
public class SagaCompositionBuilder {

    private final String compositionName;
    private CompensationPolicy compensationPolicy = CompensationPolicy.STRICT_SEQUENTIAL;
    private final Map<String, SagaComposition.CompositionSaga> sagas = new LinkedHashMap<>();
    private final List<String> executionOrder = new ArrayList<>();
    private final List<ValidationIssue> validationIssues = new ArrayList<>();
    private final SagaCompositionProperties properties;
    private boolean strictValidation = true;

    public SagaCompositionBuilder(String compositionName) {
        this(compositionName, new SagaCompositionProperties());
    }

    public SagaCompositionBuilder(String compositionName, SagaCompositionProperties properties) {
        this.compositionName = Objects.requireNonNull(compositionName, "compositionName cannot be null");
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        this.strictValidation = properties.isStrictValidation();
        this.compensationPolicy = properties.getDefaultCompensationPolicy();
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
     * Builds the final saga composition with comprehensive validation.
     *
     * @return the constructed saga composition
     * @throws CompositionValidationException if validation fails and strict mode is enabled
     */
    public SagaComposition build() {
        if (sagas.isEmpty()) {
            addValidationIssue(ValidationIssue.error("EMPTY_COMPOSITION",
                "Composition must contain at least one saga",
                "Add at least one saga using .saga(\"saga-name\").add()"));
        }

        // Perform comprehensive validation
        validateComposition();

        // Handle validation results
        if (strictValidation && hasErrors()) {
            throw new CompositionValidationException(compositionName, validationIssues);
        }

        // Build execution order based on dependencies
        List<String> order = buildExecutionOrder();

        return new SagaComposition(compositionName, compensationPolicy, sagas, order);
    }

    /**
     * Gets all validation issues found during composition building.
     *
     * @return list of validation issues
     */
    public List<ValidationIssue> getValidationIssues() {
        return new ArrayList<>(validationIssues);
    }

    /**
     * Checks if there are any validation errors.
     *
     * @return true if there are validation errors
     */
    public boolean hasErrors() {
        return validationIssues.stream().anyMatch(issue -> issue.getSeverity() == ValidationIssue.Severity.ERROR);
    }

    /**
     * Checks if there are any validation warnings.
     *
     * @return true if there are validation warnings
     */
    public boolean hasWarnings() {
        return validationIssues.stream().anyMatch(issue -> issue.getSeverity() == ValidationIssue.Severity.WARNING);
    }
    
    /**
     * Performs comprehensive validation of the composition.
     */
    private void validateComposition() {
        if (properties.getValidation().isValidateDependencies()) {
            validateDependencies();
        }

        if (properties.getValidation().isValidateParallelConstraints()) {
            validateParallelConstraints();
        }

        if (properties.getValidation().isValidateDataMappings()) {
            validateDataMappings();
        }

        validatePerformanceConstraints();
        validateNamingConventions();
    }

    /**
     * Validates dependencies and checks for circular references.
     */
    private void validateDependencies() {
        // Check for circular dependencies
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String sagaId : sagas.keySet()) {
            if (!visited.contains(sagaId)) {
                List<String> path = new ArrayList<>();
                if (hasCircularDependency(sagaId, visited, visiting, path)) {
                    addValidationIssue(ValidationIssue.error("CIRCULAR_DEPENDENCY",
                        "Circular dependency detected: " + String.join(" -> ", path),
                        "Remove the circular dependency by restructuring the saga dependencies"));
                }
            }
        }

        // Check for missing dependencies
        for (SagaComposition.CompositionSaga saga : sagas.values()) {
            for (String dependency : saga.dependencies) {
                if (!sagas.containsKey(dependency)) {
                    addValidationIssue(ValidationIssue.error("MISSING_DEPENDENCY",
                        "Saga '" + saga.compositionId + "' depends on unknown saga: " + dependency,
                        "Ensure the dependency saga '" + dependency + "' is added to the composition before this saga",
                        saga.compositionId));
                }
            }
        }

        // Check for self-dependencies
        for (SagaComposition.CompositionSaga saga : sagas.values()) {
            if (saga.dependencies.contains(saga.compositionId)) {
                addValidationIssue(ValidationIssue.error("SELF_DEPENDENCY",
                    "Saga '" + saga.compositionId + "' cannot depend on itself",
                    "Remove the self-dependency from saga '" + saga.compositionId + "'",
                    saga.compositionId));
            }
        }
    }

    /**
     * Validates parallel execution constraints.
     */
    private void validateParallelConstraints() {
        for (SagaComposition.CompositionSaga saga : sagas.values()) {
            for (String parallelSagaId : saga.parallelWith) {
                if (!sagas.containsKey(parallelSagaId)) {
                    addValidationIssue(ValidationIssue.error("MISSING_PARALLEL_SAGA",
                        "Saga '" + saga.compositionId + "' declares parallel execution with unknown saga: " + parallelSagaId,
                        "Ensure the parallel saga '" + parallelSagaId + "' is added to the composition",
                        saga.compositionId));
                    continue;
                }

                // Check for dependency conflicts with parallel execution
                SagaComposition.CompositionSaga parallelSaga = sagas.get(parallelSagaId);
                if (saga.dependencies.contains(parallelSagaId) ||
                    parallelSaga.dependencies.contains(saga.compositionId)) {
                    addValidationIssue(ValidationIssue.error("PARALLEL_DEPENDENCY_CONFLICT",
                        "Sagas '" + saga.compositionId + "' and '" + parallelSagaId + "' cannot be both dependent and parallel",
                        "Either remove the dependency or the parallel execution declaration",
                        saga.compositionId));
                }
            }
        }
    }

    /**
     * Validates data mapping configurations.
     */
    private void validateDataMappings() {
        for (SagaComposition.CompositionSaga saga : sagas.values()) {
            for (Map.Entry<String, SagaComposition.DataMapping> entry : saga.dataFromSagas.entrySet()) {
                String targetKey = entry.getKey();
                SagaComposition.DataMapping mapping = entry.getValue();

                // Check that source saga exists
                if (!sagas.containsKey(mapping.sourceSagaId)) {
                    addValidationIssue(ValidationIssue.error("MISSING_DATA_SOURCE",
                        "Data mapping in saga '" + saga.compositionId + "' references unknown source saga: " + mapping.sourceSagaId,
                        "Ensure the source saga '" + mapping.sourceSagaId + "' is added to the composition",
                        saga.compositionId));
                    continue;
                }

                // Check that source saga is a dependency (direct or transitive)
                if (!saga.dependencies.contains(mapping.sourceSagaId) &&
                    !hasTransitiveDependency(saga.compositionId, mapping.sourceSagaId)) {
                    addValidationIssue(ValidationIssue.warning("DATA_MAPPING_WITHOUT_DEPENDENCY",
                        "Data mapping in saga '" + saga.compositionId + "' references saga '" + mapping.sourceSagaId + "' which is not a dependency",
                        "Consider adding '" + mapping.sourceSagaId + "' as a dependency to ensure execution order",
                        saga.compositionId));
                }
            }
        }
    }

    /**
     * Validates performance constraints.
     */
    private void validatePerformanceConstraints() {
        if (sagas.size() > properties.getMaxParallelSagas()) {
            addValidationIssue(ValidationIssue.warning("TOO_MANY_SAGAS",
                "Composition has " + sagas.size() + " sagas, which exceeds the recommended maximum of " + properties.getMaxParallelSagas(),
                "Consider breaking the composition into smaller sub-compositions for better performance"));
        }

        // Check for sagas without timeouts
        for (SagaComposition.CompositionSaga saga : sagas.values()) {
            if (saga.timeoutMs <= 0) {
                addValidationIssue(ValidationIssue.info("NO_TIMEOUT",
                    "Saga '" + saga.compositionId + "' has no timeout configured",
                    "Consider adding a timeout using .timeout(milliseconds) to prevent hanging",
                    saga.compositionId));
            }
        }
    }

    /**
     * Validates naming conventions.
     */
    private void validateNamingConventions() {
        for (SagaComposition.CompositionSaga saga : sagas.values()) {
            if (saga.compositionId.contains(" ") || saga.compositionId.contains("-")) {
                addValidationIssue(ValidationIssue.info("NAMING_CONVENTION",
                    "Saga ID '" + saga.compositionId + "' contains spaces or hyphens",
                    "Consider using camelCase or snake_case for better consistency",
                    saga.compositionId));
            }
        }
    }

    /**
     * Adds a validation issue to the list.
     */
    private void addValidationIssue(ValidationIssue issue) {
        validationIssues.add(issue);

        // If fail-fast is enabled and this is an error, throw immediately
        if (properties.isFailFast() && issue.getSeverity() == ValidationIssue.Severity.ERROR) {
            throw new CompositionValidationException(compositionName, List.of(issue));
        }
    }

    private boolean hasCircularDependency(String sagaId, Set<String> visited, Set<String> visiting, List<String> path) {
        if (visiting.contains(sagaId)) {
            // Found a cycle
            path.add(sagaId);
            return true;
        }

        if (visited.contains(sagaId)) {
            return false;
        }

        visiting.add(sagaId);
        path.add(sagaId);

        SagaComposition.CompositionSaga saga = sagas.get(sagaId);
        if (saga != null) {
            for (String dependency : saga.dependencies) {
                if (hasCircularDependency(dependency, visited, visiting, path)) {
                    return true;
                }
            }
        }

        visiting.remove(sagaId);
        visited.add(sagaId);
        path.remove(path.size() - 1);

        return false;
    }

    /**
     * Checks if there's a transitive dependency between two sagas.
     */
    private boolean hasTransitiveDependency(String fromSaga, String toSaga) {
        Set<String> visited = new HashSet<>();
        return hasTransitiveDependencyDFS(fromSaga, toSaga, visited);
    }

    private boolean hasTransitiveDependencyDFS(String current, String target, Set<String> visited) {
        if (visited.contains(current)) {
            return false;
        }
        visited.add(current);

        SagaComposition.CompositionSaga saga = sagas.get(current);
        if (saga == null) {
            return false;
        }

        for (String dependency : saga.dependencies) {
            if (dependency.equals(target) || hasTransitiveDependencyDFS(dependency, target, visited)) {
                return true;
            }
        }

        return false;
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
