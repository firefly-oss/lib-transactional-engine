/*
 * Copyright 2024 Firefly Authors
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

import com.firefly.transactional.tcc.core.TccInputs;
import com.firefly.transactional.shared.engine.CompensationPolicy;

import java.util.*;
import java.util.function.Function;

/**
 * Builder for creating TCC compositions with fluent API.
 * <p>
 * This builder provides a fluent interface for defining complex TCC compositions
 * with dependencies, parallel execution, data flow, and execution conditions.
 * It supports comprehensive validation and provides clear error messages for
 * invalid composition configurations.
 */
public class TccCompositionBuilder {

    private final String compositionName;
    private CompensationPolicy compensationPolicy = CompensationPolicy.STRICT_SEQUENTIAL;
    private final Map<String, TccComposition.CompositionTcc> tccs = new LinkedHashMap<>();
    private final List<String> executionOrder = new ArrayList<>();

    public TccCompositionBuilder(String compositionName) {
        this.compositionName = Objects.requireNonNull(compositionName, "compositionName cannot be null");
    }

    /**
     * Sets the compensation policy for the composition.
     * 
     * @param policy the compensation policy
     * @return this builder
     */
    public TccCompositionBuilder compensationPolicy(CompensationPolicy policy) {
        this.compensationPolicy = Objects.requireNonNull(policy, "policy cannot be null");
        return this;
    }

    /**
     * Starts building a TCC within the composition.
     * 
     * @param tccName the name of the TCC coordinator
     * @return a TCC builder
     */
    public TccBuilder tcc(String tccName) {
        Objects.requireNonNull(tccName, "tccName cannot be null");
        return new TccBuilder(tccName);
    }

    /**
     * Builds the final TCC composition with validation.
     *
     * @return the constructed TCC composition
     * @throws IllegalArgumentException if validation fails
     */
    public TccComposition build() {
        if (tccs.isEmpty()) {
            throw new IllegalArgumentException("Composition must contain at least one TCC");
        }

        // Validate dependencies
        validateDependencies();

        return new TccComposition(compositionName, compensationPolicy, tccs, executionOrder);
    }

    /**
     * Validates that all dependencies are satisfied and there are no circular dependencies.
     */
    private void validateDependencies() {
        Set<String> allTccIds = tccs.keySet();
        
        for (TccComposition.CompositionTcc tcc : tccs.values()) {
            // Check that all dependencies exist
            for (String dependency : tcc.dependencies) {
                if (!allTccIds.contains(dependency)) {
                    throw new IllegalArgumentException(
                        String.format("TCC '%s' depends on non-existent TCC '%s'", 
                                    tcc.compositionId, dependency));
                }
            }
            
            // Check that all parallel TCCs exist
            for (String parallelTcc : tcc.parallelWith) {
                if (!allTccIds.contains(parallelTcc)) {
                    throw new IllegalArgumentException(
                        String.format("TCC '%s' is configured to run in parallel with non-existent TCC '%s'", 
                                    tcc.compositionId, parallelTcc));
                }
            }
        }
        
        // Check for circular dependencies
        detectCircularDependencies();
    }

    /**
     * Detects circular dependencies using depth-first search.
     */
    private void detectCircularDependencies() {
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        
        for (String tccId : tccs.keySet()) {
            if (hasCircularDependency(tccId, visited, recursionStack)) {
                throw new IllegalArgumentException(
                    String.format("Circular dependency detected involving TCC '%s'", tccId));
            }
        }
    }

    private boolean hasCircularDependency(String tccId, Set<String> visited, Set<String> recursionStack) {
        if (recursionStack.contains(tccId)) {
            return true;
        }
        
        if (visited.contains(tccId)) {
            return false;
        }
        
        visited.add(tccId);
        recursionStack.add(tccId);
        
        TccComposition.CompositionTcc tcc = tccs.get(tccId);
        if (tcc != null) {
            for (String dependency : tcc.dependencies) {
                if (hasCircularDependency(dependency, visited, recursionStack)) {
                    return true;
                }
            }
        }
        
        recursionStack.remove(tccId);
        return false;
    }

    /**
     * Builder for configuring individual TCCs within a composition.
     */
    public class TccBuilder {
        private final String tccName;
        private String compositionId;
        private final Set<String> dependencies = new HashSet<>();
        private final Set<String> parallelWith = new HashSet<>();
        private TccInputs inputs = TccInputs.builder().build();
        private final Map<String, TccComposition.DataMapping> dataFromTccs = new HashMap<>();
        private Function<TccCompositionContext, Boolean> executionCondition = ctx -> true;
        private boolean optional = false;
        private int timeoutMs = 0;
        
        public TccBuilder(String tccName) {
            this.tccName = Objects.requireNonNull(tccName, "tccName cannot be null");
            this.compositionId = tccName; // Default composition ID is the TCC name
        }

        /**
         * Sets a custom composition ID for this TCC.
         * 
         * @param id the composition ID
         * @return this TCC builder
         */
        public TccBuilder withId(String id) {
            this.compositionId = Objects.requireNonNull(id, "id cannot be null");
            return this;
        }

        /**
         * Adds a dependency on another TCC in the composition.
         * 
         * @param tccId the ID of the TCC this one depends on
         * @return this TCC builder
         */
        public TccBuilder dependsOn(String tccId) {
            Objects.requireNonNull(tccId, "tccId cannot be null");
            dependencies.add(tccId);
            return this;
        }

        /**
         * Configures this TCC to execute in parallel with another TCC.
         * 
         * @param tccId the ID of the TCC to execute in parallel with
         * @return this TCC builder
         */
        public TccBuilder executeInParallelWith(String tccId) {
            Objects.requireNonNull(tccId, "tccId cannot be null");
            parallelWith.add(tccId);
            return this;
        }

        /**
         * Adds an input parameter for the TCC.
         *
         * @param key the input key
         * @param value the input value
         * @return this TCC builder
         */
        public TccBuilder withInput(String key, Object value) {
            Objects.requireNonNull(key, "key cannot be null");
            // Create a new builder with existing inputs plus the new one
            TccInputs.Builder builder = TccInputs.builder();
            if (this.inputs != null) {
                builder.withInputs(this.inputs.getAllInputs());
            }
            builder.forParticipant(key, value);
            this.inputs = builder.build();
            return this;
        }

        /**
         * Maps data from another TCC's result to this TCC's input.
         * 
         * @param sourceTccId the source TCC ID
         * @param sourceParticipantId the source participant ID
         * @param sourceKey the source data key
         * @param targetKey the target input key
         * @return this TCC builder
         */
        public TccBuilder withDataFrom(String sourceTccId, String sourceParticipantId, String sourceKey, String targetKey) {
            Objects.requireNonNull(sourceTccId, "sourceTccId cannot be null");
            Objects.requireNonNull(sourceParticipantId, "sourceParticipantId cannot be null");
            Objects.requireNonNull(sourceKey, "sourceKey cannot be null");
            Objects.requireNonNull(targetKey, "targetKey cannot be null");
            
            dataFromTccs.put(targetKey, new TccComposition.DataMapping(sourceTccId, sourceParticipantId, sourceKey, targetKey));
            return this;
        }

        /**
         * Maps data from another TCC's result using the same key name.
         * 
         * @param sourceTccId the source TCC ID
         * @param sourceParticipantId the source participant ID
         * @param key the data key (used for both source and target)
         * @return this TCC builder
         */
        public TccBuilder withDataFrom(String sourceTccId, String sourceParticipantId, String key) {
            return withDataFrom(sourceTccId, sourceParticipantId, key, key);
        }

        /**
         * Sets an execution condition for this TCC.
         * 
         * @param condition the execution condition
         * @return this TCC builder
         */
        public TccBuilder when(Function<TccCompositionContext, Boolean> condition) {
            this.executionCondition = Objects.requireNonNull(condition, "condition cannot be null");
            return this;
        }

        /**
         * Marks this TCC as optional (failure won't fail the entire composition).
         * 
         * @return this TCC builder
         */
        public TccBuilder optional() {
            this.optional = true;
            return this;
        }

        /**
         * Sets a timeout for this TCC execution.
         * 
         * @param timeoutMs the timeout in milliseconds
         * @return this TCC builder
         */
        public TccBuilder timeout(int timeoutMs) {
            if (timeoutMs < 0) {
                throw new IllegalArgumentException("timeout cannot be negative");
            }
            this.timeoutMs = timeoutMs;
            return this;
        }

        /**
         * Adds this TCC to the composition and returns the composition builder.
         * 
         * @return the composition builder
         */
        public TccCompositionBuilder add() {
            TccComposition.CompositionTcc compositionTcc = new TccComposition.CompositionTcc(
                tccName, compositionId, dependencies, parallelWith, inputs, 
                dataFromTccs, executionCondition, optional, timeoutMs);
            
            if (tccs.putIfAbsent(compositionId, compositionTcc) != null) {
                throw new IllegalStateException("Duplicate TCC ID: " + compositionId);
            }
            
            executionOrder.add(compositionId);
            return TccCompositionBuilder.this;
        }
    }

    /**
     * Gets validation issues for the current composition.
     *
     * @return list of validation issues
     */
    public List<ValidationIssue> getValidationIssues() {
        List<ValidationIssue> issues = new ArrayList<>();

        // Validate dependencies
        for (Map.Entry<String, TccComposition.CompositionTcc> entry : tccs.entrySet()) {
            String tccId = entry.getKey();
            TccComposition.CompositionTcc tcc = entry.getValue();

            for (String dependency : tcc.dependencies) {
                if (!tccs.containsKey(dependency)) {
                    issues.add(ValidationIssue.error(
                        "INVALID_DEPENDENCY",
                        "TCC '" + tccId + "' depends on non-existent TCC '" + dependency + "'",
                        "Remove the dependency or add the missing TCC to the composition",
                        tccId
                    ));
                }
            }

            // Validate parallel execution
            for (String parallelTcc : tcc.parallelWith) {
                if (!tccs.containsKey(parallelTcc)) {
                    issues.add(ValidationIssue.error(
                        "INVALID_PARALLEL_TCC",
                        "TCC '" + tccId + "' is configured to execute in parallel with non-existent TCC '" + parallelTcc + "'",
                        "Remove the parallel configuration or add the missing TCC to the composition",
                        tccId
                    ));
                }
            }

            // Validate data mappings
            for (Map.Entry<String, TccComposition.DataMapping> dataEntry : tcc.dataFromTccs.entrySet()) {
                String sourceTcc = dataEntry.getValue().sourceTccId;
                if (!tccs.containsKey(sourceTcc)) {
                    issues.add(ValidationIssue.error(
                        "INVALID_DATA_SOURCE",
                        "TCC '" + tccId + "' is configured to receive data from non-existent TCC '" + sourceTcc + "'",
                        "Remove the data mapping or add the missing TCC to the composition",
                        tccId
                    ));
                }
            }
        }

        return issues;
    }

    /**
     * Checks if there are any validation errors.
     *
     * @return true if there are validation errors
     */
    public boolean hasErrors() {
        return getValidationIssues().stream()
                .anyMatch(issue -> issue.getSeverity() == ValidationIssue.Severity.ERROR);
    }
}
