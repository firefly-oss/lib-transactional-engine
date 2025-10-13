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

import com.firefly.transactional.saga.registry.SagaDefinition;
import com.firefly.transactional.saga.registry.SagaRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Validates saga compositions for structural correctness and dependency consistency.
 */
public class CompositionValidator {
    
    private static final Logger log = LoggerFactory.getLogger(CompositionValidator.class);
    
    /**
     * Validates a saga composition for structural correctness.
     * 
     * @param composition the composition to validate
     * @param sagaRegistry the saga registry for resolving saga definitions
     * @throws IllegalArgumentException if the composition is invalid
     */
    public static void validate(SagaComposition composition, SagaRegistry sagaRegistry) {
        Objects.requireNonNull(composition, "composition cannot be null");
        Objects.requireNonNull(sagaRegistry, "sagaRegistry cannot be null");
        
        List<String> errors = new ArrayList<>();
        
        // Validate basic structure
        validateBasicStructure(composition, errors);
        
        // Validate saga definitions exist
        validateSagaDefinitions(composition, sagaRegistry, errors);
        
        // Validate dependencies
        validateDependencies(composition, errors);
        
        // Validate parallel execution constraints
        validateParallelExecution(composition, errors);
        
        // Validate data mappings
        validateDataMappings(composition, errors);
        
        if (!errors.isEmpty()) {
            String errorMessage = "Composition validation failed:\n" + String.join("\n", errors);
            throw new IllegalArgumentException(errorMessage);
        }
        
        log.info("Composition '{}' validation passed", composition.name);
    }
    
    /**
     * Validates basic structural requirements.
     */
    private static void validateBasicStructure(SagaComposition composition, List<String> errors) {
        if (composition.name == null || composition.name.trim().isEmpty()) {
            errors.add("Composition name cannot be null or empty");
        }
        
        if (composition.sagas == null || composition.sagas.isEmpty()) {
            errors.add("Composition must contain at least one saga");
        }
        
        if (composition.compensationPolicy == null) {
            errors.add("Compensation policy cannot be null");
        }
        
        // Validate saga IDs are unique
        Set<String> sagaIds = new HashSet<>();
        for (String sagaId : composition.sagas.keySet()) {
            if (!sagaIds.add(sagaId)) {
                errors.add("Duplicate saga ID: " + sagaId);
            }
        }
    }
    
    /**
     * Validates that all referenced saga definitions exist in the registry.
     */
    private static void validateSagaDefinitions(SagaComposition composition, 
                                               SagaRegistry sagaRegistry, 
                                               List<String> errors) {
        for (SagaComposition.CompositionSaga saga : composition.sagas.values()) {
            SagaDefinition definition = sagaRegistry.getSaga(saga.sagaName);
            if (definition == null) {
                errors.add("Saga definition not found: " + saga.sagaName + 
                          " (referenced by composition saga: " + saga.compositionId + ")");
            }
        }
    }
    
    /**
     * Validates dependency relationships and checks for circular dependencies.
     */
    private static void validateDependencies(SagaComposition composition, List<String> errors) {
        Map<String, SagaComposition.CompositionSaga> sagas = composition.sagas;
        
        // Check that all dependencies reference existing sagas
        for (SagaComposition.CompositionSaga saga : sagas.values()) {
            for (String dependency : saga.dependencies) {
                if (!sagas.containsKey(dependency)) {
                    errors.add("Saga '" + saga.compositionId + "' depends on unknown saga: " + dependency);
                }
            }
        }
        
        // Check for circular dependencies using DFS
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        
        for (String sagaId : sagas.keySet()) {
            if (!visited.contains(sagaId)) {
                if (hasCircularDependency(sagaId, sagas, visited, visiting, new ArrayList<>())) {
                    errors.add("Circular dependency detected involving saga: " + sagaId);
                }
            }
        }
        
        // Check for self-dependencies
        for (SagaComposition.CompositionSaga saga : sagas.values()) {
            if (saga.dependencies.contains(saga.compositionId)) {
                errors.add("Saga '" + saga.compositionId + "' cannot depend on itself");
            }
        }
    }
    
    /**
     * Detects circular dependencies using depth-first search.
     */
    private static boolean hasCircularDependency(String sagaId,
                                               Map<String, SagaComposition.CompositionSaga> sagas,
                                               Set<String> visited,
                                               Set<String> visiting,
                                               List<String> path) {
        if (visiting.contains(sagaId)) {
            return true; // Circular dependency found
        }
        
        if (visited.contains(sagaId)) {
            return false; // Already processed
        }
        
        visiting.add(sagaId);
        path.add(sagaId);
        
        SagaComposition.CompositionSaga saga = sagas.get(sagaId);
        if (saga != null) {
            for (String dependency : saga.dependencies) {
                if (hasCircularDependency(dependency, sagas, visited, visiting, path)) {
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
     * Validates parallel execution constraints.
     */
    private static void validateParallelExecution(SagaComposition composition, List<String> errors) {
        Map<String, SagaComposition.CompositionSaga> sagas = composition.sagas;
        
        for (SagaComposition.CompositionSaga saga : sagas.values()) {
            for (String parallelSagaId : saga.parallelWith) {
                if (!sagas.containsKey(parallelSagaId)) {
                    errors.add("Saga '" + saga.compositionId + 
                              "' declares parallel execution with unknown saga: " + parallelSagaId);
                    continue;
                }
                
                // Check for dependency conflicts with parallel execution
                SagaComposition.CompositionSaga parallelSaga = sagas.get(parallelSagaId);
                if (saga.dependencies.contains(parallelSagaId) || 
                    parallelSaga.dependencies.contains(saga.compositionId)) {
                    errors.add("Sagas '" + saga.compositionId + "' and '" + parallelSagaId + 
                              "' cannot be both dependent and parallel");
                }
                
                // Check for transitive dependency conflicts
                if (hasTransitiveDependency(saga.compositionId, parallelSagaId, sagas) ||
                    hasTransitiveDependency(parallelSagaId, saga.compositionId, sagas)) {
                    errors.add("Sagas '" + saga.compositionId + "' and '" + parallelSagaId + 
                              "' cannot be parallel due to transitive dependencies");
                }
            }
        }
    }
    
    /**
     * Checks if there's a transitive dependency between two sagas.
     */
    private static boolean hasTransitiveDependency(String fromSaga, 
                                                 String toSaga, 
                                                 Map<String, SagaComposition.CompositionSaga> sagas) {
        Set<String> visited = new HashSet<>();
        return hasTransitiveDependencyRecursive(fromSaga, toSaga, sagas, visited);
    }
    
    private static boolean hasTransitiveDependencyRecursive(String fromSaga,
                                                          String toSaga,
                                                          Map<String, SagaComposition.CompositionSaga> sagas,
                                                          Set<String> visited) {
        if (visited.contains(fromSaga)) {
            return false; // Avoid infinite recursion
        }
        
        visited.add(fromSaga);
        
        SagaComposition.CompositionSaga saga = sagas.get(fromSaga);
        if (saga == null) {
            return false;
        }
        
        for (String dependency : saga.dependencies) {
            if (dependency.equals(toSaga)) {
                return true;
            }
            if (hasTransitiveDependencyRecursive(dependency, toSaga, sagas, visited)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Validates data mapping configurations.
     */
    private static void validateDataMappings(SagaComposition composition, List<String> errors) {
        Map<String, SagaComposition.CompositionSaga> sagas = composition.sagas;
        
        for (SagaComposition.CompositionSaga saga : sagas.values()) {
            for (Map.Entry<String, SagaComposition.DataMapping> entry : saga.dataFromSagas.entrySet()) {
                String targetKey = entry.getKey();
                SagaComposition.DataMapping mapping = entry.getValue();
                
                // Check that source saga exists
                if (!sagas.containsKey(mapping.sourceSagaId)) {
                    errors.add("Data mapping in saga '" + saga.compositionId + 
                              "' references unknown source saga: " + mapping.sourceSagaId);
                    continue;
                }
                
                // Check that source saga is a dependency (direct or transitive)
                if (!saga.dependencies.contains(mapping.sourceSagaId) &&
                    !hasTransitiveDependency(saga.compositionId, mapping.sourceSagaId, sagas)) {
                    errors.add("Data mapping in saga '" + saga.compositionId + 
                              "' references saga '" + mapping.sourceSagaId + 
                              "' which is not a dependency");
                }
                
                // Validate mapping keys
                if (mapping.sourceKey == null || mapping.sourceKey.trim().isEmpty()) {
                    errors.add("Data mapping in saga '" + saga.compositionId + 
                              "' has null or empty source key");
                }
                
                if (targetKey == null || targetKey.trim().isEmpty()) {
                    errors.add("Data mapping in saga '" + saga.compositionId + 
                              "' has null or empty target key");
                }
            }
        }
    }
}
