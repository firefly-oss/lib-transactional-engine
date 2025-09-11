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

package com.firefly.transactional.validation;

import com.firefly.transactional.registry.SagaDefinition;
import com.firefly.transactional.registry.StepDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates saga definitions and inputs at runtime.
 * Provides comprehensive validation to catch configuration errors early.
 */
public class SagaValidator {
    
    /**
     * Validates a saga definition.
     * 
     * @param saga the saga definition to validate
     * @return validation result with any errors or warnings
     */
    public static ValidationResult validateSagaDefinition(SagaDefinition saga) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        if (saga == null) {
            errors.add("Saga definition cannot be null");
            return new ValidationResult(errors, warnings);
        }
        
        // Validate basic properties
        validateBasicProperties(saga, errors, warnings);
        
        // Validate steps
        validateSteps(saga, errors, warnings);
        
        // Validate dependencies
        validateDependencies(saga, errors, warnings);
        
        // Validate topology
        validateTopology(saga, errors, warnings);
        
        return new ValidationResult(errors, warnings);
    }
    
    /**
     * Validates saga input parameters.
     * 
     * @param saga the saga definition
     * @param inputs the input parameters
     * @return validation result with any errors or warnings
     */
    public static ValidationResult validateSagaInputs(SagaDefinition saga, Object inputs) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        if (saga == null) {
            errors.add("Saga definition cannot be null");
            return new ValidationResult(errors, warnings);
        }
        
        // Validate input requirements
        validateInputRequirements(saga, inputs, errors, warnings);
        
        return new ValidationResult(errors, warnings);
    }
    
    private static void validateBasicProperties(SagaDefinition saga, List<String> errors, List<String> warnings) {
        // Validate name
        if (saga.name == null || saga.name.trim().isEmpty()) {
            errors.add("Saga name cannot be null or empty");
        } else if (saga.name.length() > 100) {
            warnings.add("Saga name is very long (" + saga.name.length() + " characters). Consider shorter names for better readability.");
        }
        
        // Validate steps collection
        if (saga.steps == null) {
            errors.add("Saga steps cannot be null");
        } else if (saga.steps.isEmpty()) {
            errors.add("Saga must have at least one step");
        } else if (saga.steps.size() > 50) {
            warnings.add("Saga has many steps (" + saga.steps.size() + "). Consider breaking into smaller sagas for better maintainability.");
        }
    }
    
    private static void validateSteps(SagaDefinition saga, List<String> errors, List<String> warnings) {
        if (saga.steps == null) {
            return; // Already handled in basic validation
        }
        
        Set<String> stepIds = new HashSet<>();
        
        for (StepDefinition step : saga.steps.values()) {
            validateStep(step, stepIds, errors, warnings);
        }
    }
    
    private static void validateStep(StepDefinition step, Set<String> stepIds, List<String> errors, List<String> warnings) {
        if (step == null) {
            errors.add("Step definition cannot be null");
            return;
        }
        
        // Validate step ID
        if (step.id == null || step.id.trim().isEmpty()) {
            errors.add("Step ID cannot be null or empty");
        } else {
            if (stepIds.contains(step.id)) {
                errors.add("Duplicate step ID: " + step.id);
            } else {
                stepIds.add(step.id);
            }
            
            if (step.id.length() > 50) {
                warnings.add("Step ID '" + step.id + "' is very long. Consider shorter IDs for better readability.");
            }
        }
        
        // Validate step method
        if (step.stepMethod == null && step.handler == null) {
            errors.add("Step '" + step.id + "' must have either a step method or handler");
        }

        // Validate compensation method if present
        if (step.compensateName != null && step.compensateName.trim().isEmpty()) {
            warnings.add("Step '" + step.id + "' has empty compensation method. Consider removing or providing a valid method name.");
        }
        
        // Validate dependencies
        if (step.dependsOn != null) {
            for (String dependency : step.dependsOn) {
                if (dependency == null || dependency.trim().isEmpty()) {
                    errors.add("Step '" + step.id + "' has null or empty dependency");
                } else if (dependency.equals(step.id)) {
                    errors.add("Step '" + step.id + "' cannot depend on itself");
                }
            }
        }
    }
    
    private static void validateDependencies(SagaDefinition saga, List<String> errors, List<String> warnings) {
        if (saga.steps == null) {
            return;
        }
        
        Set<String> stepIds = new HashSet<>();
        for (StepDefinition step : saga.steps.values()) {
            if (step.id != null) {
                stepIds.add(step.id);
            }
        }
        
        // Check that all dependencies exist
        for (StepDefinition step : saga.steps.values()) {
            if (step.dependsOn != null) {
                for (String dependency : step.dependsOn) {
                    if (dependency != null && !stepIds.contains(dependency)) {
                        errors.add("Step '" + step.id + "' depends on non-existent step: " + dependency);
                    }
                }
            }
        }
    }
    
    private static void validateTopology(SagaDefinition saga, List<String> errors, List<String> warnings) {
        if (saga.steps == null) {
            return;
        }
        
        // Check for circular dependencies
        try {
            detectCircularDependencies(saga);
        } catch (CircularDependencyException e) {
            errors.add("Circular dependency detected: " + e.getMessage());
        }
        
        // Check for orphaned steps (steps with no path to completion)
        validateStepReachability(saga, errors, warnings);
    }
    
    private static void detectCircularDependencies(SagaDefinition saga) throws CircularDependencyException {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        
        for (StepDefinition step : saga.steps.values()) {
            if (step.id != null && !visited.contains(step.id)) {
                detectCircularDependenciesRecursive(step.id, saga, visiting, visited);
            }
        }
    }
    
    private static void detectCircularDependenciesRecursive(String stepId, SagaDefinition saga, 
                                                          Set<String> visiting, Set<String> visited) 
            throws CircularDependencyException {
        if (visiting.contains(stepId)) {
            throw new CircularDependencyException("Circular dependency involving step: " + stepId);
        }
        
        if (visited.contains(stepId)) {
            return;
        }
        
        visiting.add(stepId);
        
        StepDefinition step = findStep(saga, stepId);
        if (step != null && step.dependsOn != null) {
            for (String dependency : step.dependsOn) {
                if (dependency != null) {
                    detectCircularDependenciesRecursive(dependency, saga, visiting, visited);
                }
            }
        }
        
        visiting.remove(stepId);
        visited.add(stepId);
    }
    
    private static StepDefinition findStep(SagaDefinition saga, String stepId) {
        return saga.steps.get(stepId);
    }
    
    private static void validateStepReachability(SagaDefinition saga, List<String> errors, List<String> warnings) {
        // Find root steps (steps with no dependencies)
        Set<String> rootSteps = new HashSet<>();
        for (StepDefinition step : saga.steps.values()) {
            if (step.id != null && (step.dependsOn == null || step.dependsOn.isEmpty())) {
                rootSteps.add(step.id);
            }
        }
        
        if (rootSteps.isEmpty()) {
            errors.add("No root steps found. At least one step must have no dependencies.");
        }
    }
    
    private static void validateInputRequirements(SagaDefinition saga, Object inputs, List<String> errors, List<String> warnings) {
        // This is a basic validation - in a real implementation, you might want to:
        // 1. Check if required input fields are present
        // 2. Validate input types match expected types
        // 3. Validate input constraints (ranges, formats, etc.)
        
        if (inputs == null) {
            warnings.add("No inputs provided. Ensure all steps can handle null inputs.");
        }
        
        // Additional input validation could be added here based on specific requirements
    }
    
    /**
     * Exception thrown when circular dependencies are detected.
     */
    public static class CircularDependencyException extends Exception {
        public CircularDependencyException(String message) {
            super(message);
        }
    }
    
    /**
     * Result of validation containing errors and warnings.
     */
    public static class ValidationResult {
        private final List<String> errors;
        private final List<String> warnings;
        
        public ValidationResult(List<String> errors, List<String> warnings) {
            this.errors = List.copyOf(errors != null ? errors : List.of());
            this.warnings = List.copyOf(warnings != null ? warnings : List.of());
        }
        
        public List<String> getErrors() {
            return errors;
        }
        
        public List<String> getWarnings() {
            return warnings;
        }
        
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
        
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
        
        public boolean isValid() {
            return !hasErrors();
        }
        
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            if (hasErrors()) {
                sb.append("Errors: ").append(errors.size());
            }
            if (hasWarnings()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("Warnings: ").append(warnings.size());
            }
            if (sb.length() == 0) {
                sb.append("Valid");
            }
            return sb.toString();
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ValidationResult{");
            sb.append("errors=").append(errors.size());
            sb.append(", warnings=").append(warnings.size());
            sb.append(", valid=").append(isValid());
            sb.append('}');
            return sb.toString();
        }
    }
}
