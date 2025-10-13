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

import com.firefly.transactional.tcc.registry.TccDefinition;
import com.firefly.transactional.tcc.registry.TccRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Validates TCC compositions for structural correctness and runtime feasibility.
 * <p>
 * This validator performs comprehensive checks on TCC compositions to ensure:
 * - All referenced TCCs exist in the registry
 * - Dependencies are valid and don't create circular references
 * - Parallel execution configurations are consistent
 * - Data mappings reference valid sources
 * - Execution conditions are properly configured
 */
public class TccCompositionValidator {
    
    private static final Logger log = LoggerFactory.getLogger(TccCompositionValidator.class);
    
    /**
     * Validates a TCC composition against the provided TCC registry.
     * 
     * @param composition the composition to validate
     * @param tccRegistry the TCC registry for checking TCC existence
     * @throws IllegalArgumentException if validation fails
     */
    public static void validate(TccComposition composition, TccRegistry tccRegistry) {
        Objects.requireNonNull(composition, "composition cannot be null");
        Objects.requireNonNull(tccRegistry, "tccRegistry cannot be null");
        
        List<String> validationErrors = new ArrayList<>();
        
        // Basic structure validation
        validateBasicStructure(composition, validationErrors);
        
        // TCC existence validation
        validateTccExistence(composition, tccRegistry, validationErrors);
        
        // Dependency validation
        validateDependencies(composition, validationErrors);
        
        // Parallel execution validation
        validateParallelExecution(composition, validationErrors);
        
        // Data mapping validation
        validateDataMappings(composition, validationErrors);
        
        // Execution order validation
        validateExecutionOrder(composition, validationErrors);
        
        if (!validationErrors.isEmpty()) {
            String errorMessage = String.format("TCC composition '%s' validation failed:\n%s", 
                                               composition.name, String.join("\n", validationErrors));
            throw new IllegalArgumentException(errorMessage);
        }
        
        log.debug("TCC composition '{}' validation passed", composition.name);
    }
    
    /**
     * Validates basic structure of the composition.
     */
    private static void validateBasicStructure(TccComposition composition, List<String> errors) {
        if (composition.name == null || composition.name.trim().isEmpty()) {
            errors.add("Composition name cannot be null or empty");
        }
        
        if (composition.compensationPolicy == null) {
            errors.add("Compensation policy cannot be null");
        }
        
        if (composition.tccs == null || composition.tccs.isEmpty()) {
            errors.add("Composition must contain at least one TCC");
        }
        
        if (composition.executionOrder == null) {
            errors.add("Execution order cannot be null");
        }
    }
    
    /**
     * Validates that all referenced TCCs exist in the registry.
     */
    private static void validateTccExistence(TccComposition composition, TccRegistry tccRegistry, List<String> errors) {
        for (TccComposition.CompositionTcc tcc : composition.tccs.values()) {
            TccDefinition tccDefinition = tccRegistry.getTcc(tcc.tccName);
            if (tccDefinition == null) {
                errors.add(String.format("TCC '%s' (composition ID: '%s') not found in registry", 
                                        tcc.tccName, tcc.compositionId));
            }
        }
    }
    
    /**
     * Validates dependency relationships and checks for circular dependencies.
     */
    private static void validateDependencies(TccComposition composition, List<String> errors) {
        Set<String> allTccIds = composition.tccs.keySet();
        
        // Check that all dependencies exist
        for (TccComposition.CompositionTcc tcc : composition.tccs.values()) {
            for (String dependency : tcc.dependencies) {
                if (!allTccIds.contains(dependency)) {
                    errors.add(String.format("TCC '%s' depends on non-existent TCC '%s'", 
                                            tcc.compositionId, dependency));
                }
            }
        }
        
        // Check for circular dependencies
        try {
            detectCircularDependencies(composition);
        } catch (IllegalArgumentException e) {
            errors.add("Circular dependency detected: " + e.getMessage());
        }
        
        // Check for self-dependencies
        for (TccComposition.CompositionTcc tcc : composition.tccs.values()) {
            if (tcc.dependencies.contains(tcc.compositionId)) {
                errors.add(String.format("TCC '%s' cannot depend on itself", tcc.compositionId));
            }
        }
    }
    
    /**
     * Validates parallel execution configurations.
     */
    private static void validateParallelExecution(TccComposition composition, List<String> errors) {
        Set<String> allTccIds = composition.tccs.keySet();
        
        for (TccComposition.CompositionTcc tcc : composition.tccs.values()) {
            // Check that all parallel TCCs exist
            for (String parallelTcc : tcc.parallelWith) {
                if (!allTccIds.contains(parallelTcc)) {
                    errors.add(String.format("TCC '%s' is configured to run in parallel with non-existent TCC '%s'", 
                                            tcc.compositionId, parallelTcc));
                }
                
                // Check that parallel relationship is mutual
                TccComposition.CompositionTcc parallelTccDef = composition.tccs.get(parallelTcc);
                if (parallelTccDef != null && !parallelTccDef.parallelWith.contains(tcc.compositionId)) {
                    errors.add(String.format("Parallel relationship between '%s' and '%s' is not mutual", 
                                            tcc.compositionId, parallelTcc));
                }
            }
            
            // Check for conflicts between dependencies and parallel execution
            for (String parallelTcc : tcc.parallelWith) {
                if (tcc.dependencies.contains(parallelTcc)) {
                    errors.add(String.format("TCC '%s' cannot both depend on and run in parallel with TCC '%s'", 
                                            tcc.compositionId, parallelTcc));
                }
            }
        }
    }
    
    /**
     * Validates data mapping configurations.
     */
    private static void validateDataMappings(TccComposition composition, List<String> errors) {
        Set<String> allTccIds = composition.tccs.keySet();
        
        for (TccComposition.CompositionTcc tcc : composition.tccs.values()) {
            for (Map.Entry<String, TccComposition.DataMapping> entry : tcc.dataFromTccs.entrySet()) {
                String targetKey = entry.getKey();
                TccComposition.DataMapping mapping = entry.getValue();
                
                // Check that source TCC exists
                if (!allTccIds.contains(mapping.sourceTccId)) {
                    errors.add(String.format("Data mapping for '%s' in TCC '%s' references non-existent source TCC '%s'", 
                                            targetKey, tcc.compositionId, mapping.sourceTccId));
                }
                
                // Check that source TCC is a dependency (direct or indirect)
                if (!isDependency(tcc.compositionId, mapping.sourceTccId, composition)) {
                    errors.add(String.format("Data mapping for '%s' in TCC '%s' references TCC '%s' which is not a dependency", 
                                            targetKey, tcc.compositionId, mapping.sourceTccId));
                }
                
                // Validate mapping keys
                if (mapping.sourceKey == null || mapping.sourceKey.trim().isEmpty()) {
                    errors.add(String.format("Data mapping for '%s' in TCC '%s' has empty source key", 
                                            targetKey, tcc.compositionId));
                }
                
                if (mapping.targetKey == null || mapping.targetKey.trim().isEmpty()) {
                    errors.add(String.format("Data mapping for '%s' in TCC '%s' has empty target key", 
                                            targetKey, tcc.compositionId));
                }
            }
        }
    }
    
    /**
     * Validates execution order consistency.
     */
    private static void validateExecutionOrder(TccComposition composition, List<String> errors) {
        Set<String> tccIds = composition.tccs.keySet();
        Set<String> executionOrderIds = new HashSet<>(composition.executionOrder);
        
        // Check that all TCCs are in execution order
        for (String tccId : tccIds) {
            if (!executionOrderIds.contains(tccId)) {
                errors.add(String.format("TCC '%s' is not included in execution order", tccId));
            }
        }
        
        // Check that execution order doesn't contain extra TCCs
        for (String tccId : composition.executionOrder) {
            if (!tccIds.contains(tccId)) {
                errors.add(String.format("Execution order contains non-existent TCC '%s'", tccId));
            }
        }
        
        // Check for duplicates in execution order
        Set<String> seen = new HashSet<>();
        for (String tccId : composition.executionOrder) {
            if (!seen.add(tccId)) {
                errors.add(String.format("TCC '%s' appears multiple times in execution order", tccId));
            }
        }
    }
    
    /**
     * Detects circular dependencies using depth-first search.
     */
    private static void detectCircularDependencies(TccComposition composition) {
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        
        for (String tccId : composition.tccs.keySet()) {
            if (hasCircularDependency(tccId, composition, visited, recursionStack)) {
                throw new IllegalArgumentException("involving TCC '" + tccId + "'");
            }
        }
    }
    
    private static boolean hasCircularDependency(String tccId, TccComposition composition, 
                                               Set<String> visited, Set<String> recursionStack) {
        if (recursionStack.contains(tccId)) {
            return true;
        }
        
        if (visited.contains(tccId)) {
            return false;
        }
        
        visited.add(tccId);
        recursionStack.add(tccId);
        
        TccComposition.CompositionTcc tcc = composition.tccs.get(tccId);
        if (tcc != null) {
            for (String dependency : tcc.dependencies) {
                if (hasCircularDependency(dependency, composition, visited, recursionStack)) {
                    return true;
                }
            }
        }
        
        recursionStack.remove(tccId);
        return false;
    }
    
    /**
     * Checks if sourceTccId is a dependency (direct or indirect) of targetTccId.
     */
    private static boolean isDependency(String targetTccId, String sourceTccId, TccComposition composition) {
        if (targetTccId.equals(sourceTccId)) {
            return false; // Can't be a dependency of itself
        }
        
        TccComposition.CompositionTcc targetTcc = composition.tccs.get(targetTccId);
        if (targetTcc == null) {
            return false;
        }
        
        // Check direct dependencies
        if (targetTcc.dependencies.contains(sourceTccId)) {
            return true;
        }
        
        // Check indirect dependencies
        for (String directDependency : targetTcc.dependencies) {
            if (isDependency(directDependency, sourceTccId, composition)) {
                return true;
            }
        }
        
        return false;
    }
}
