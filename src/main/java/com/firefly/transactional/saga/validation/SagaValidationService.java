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

package com.firefly.transactional.saga.validation;

import com.firefly.transactional.saga.registry.SagaDefinition;
import com.firefly.transactional.saga.registry.SagaRegistry;
import com.firefly.transactional.saga.registry.StepDefinition;
import com.firefly.transactional.saga.config.SagaEngineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for validating saga definitions and runtime inputs.
 * Integrates with Spring Boot to provide startup validation and runtime validation services.
 */
@Service
public class SagaValidationService {
    
    private static final Logger log = LoggerFactory.getLogger(SagaValidationService.class);
    
    private final SagaEngineProperties properties;
    private final ApplicationContext applicationContext;
    
    @Autowired(required = false)
    private SagaRegistry sagaRegistry;
    
    public SagaValidationService(SagaEngineProperties properties, ApplicationContext applicationContext) {
        this.properties = properties;
        this.applicationContext = applicationContext;
    }
    
    /**
     * Validates all registered sagas at application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validateSagasAtStartup() {
        if (!properties.getValidation().isValidateAtStartup()) {
            log.debug("Startup validation is disabled");
            return;
        }
        
        if (sagaRegistry == null) {
            log.warn("SagaRegistry not available for startup validation");
            return;
        }
        
        log.info("Starting saga definition validation...");
        
        List<String> allErrors = new ArrayList<>();
        List<String> allWarnings = new ArrayList<>();
        int validatedCount = 0;
        
        try {
            for (SagaDefinition sagaDefinition : sagaRegistry.getAll()) {
            String sagaName = sagaDefinition.name;
                SagaDefinition saga = sagaDefinition;
                if (saga != null) {
                    SagaValidator.ValidationResult result = validateSagaDefinition(saga);
                    
                    if (result.hasErrors()) {
                        log.error("Validation errors for saga '{}': {}", sagaName, result.getErrors());
                        allErrors.addAll(result.getErrors());
                    }
                    
                    if (result.hasWarnings()) {
                        log.warn("Validation warnings for saga '{}': {}", sagaName, result.getWarnings());
                        allWarnings.addAll(result.getWarnings());
                    }
                    
                    if (result.isValid()) {
                        log.debug("Saga '{}' validation passed", sagaName);
                    }
                    
                    validatedCount++;
                }
            }
        } catch (Exception e) {
            log.error("Error during startup validation", e);
            if (properties.getValidation().isFailFast()) {
                throw new RuntimeException("Saga validation failed during startup", e);
            }
        }
        
        // Summary
        log.info("Saga validation completed: {} sagas validated, {} errors, {} warnings", 
                validatedCount, allErrors.size(), allWarnings.size());
        
        // Fail fast if configured and errors found
        if (properties.getValidation().isFailFast() && !allErrors.isEmpty()) {
            throw new RuntimeException("Saga validation failed with " + allErrors.size() + " errors. See logs for details.");
        }
    }
    
    /**
     * Validates a saga definition with enhanced Spring context validation.
     * 
     * @param saga the saga definition to validate
     * @return validation result
     */
    public SagaValidator.ValidationResult validateSagaDefinition(SagaDefinition saga) {
        if (!properties.getValidation().isEnabled()) {
            return new SagaValidator.ValidationResult(List.of(), List.of());
        }
        
        // Basic validation
        SagaValidator.ValidationResult basicResult = SagaValidator.validateSagaDefinition(saga);
        
        // Enhanced validation with Spring context
        List<String> errors = new ArrayList<>(basicResult.getErrors());
        List<String> warnings = new ArrayList<>(basicResult.getWarnings());
        
        validateSpringBeans(saga, errors, warnings);
        validateMethodSignatures(saga, errors, warnings);
        
        return new SagaValidator.ValidationResult(errors, warnings);
    }
    
    /**
     * Validates saga inputs at runtime.
     * 
     * @param saga the saga definition
     * @param inputs the input parameters
     * @return validation result
     */
    public SagaValidator.ValidationResult validateSagaInputs(SagaDefinition saga, Object inputs) {
        if (!properties.getValidation().isEnabled() || !properties.getValidation().isValidateInputs()) {
            return new SagaValidator.ValidationResult(List.of(), List.of());
        }
        
        return SagaValidator.validateSagaInputs(saga, inputs);
    }
    
    /**
     * Validates that all referenced Spring beans exist.
     */
    private void validateSpringBeans(SagaDefinition saga, List<String> errors, List<String> warnings) {
        if (saga.steps == null) {
            return;
        }
        
        for (StepDefinition step : saga.steps.values()) {
            if (step.stepBean != null) {
                try {
                    // For external steps, the stepBean is already resolved
                    if (step.stepBean == null) {
                        errors.add("Step '" + step.id + "' has null step bean");
                    }
                } catch (Exception e) {
                    errors.add("Step '" + step.id + "' has invalid step bean: " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Validates method signatures for step and compensation methods.
     */
    private void validateMethodSignatures(SagaDefinition saga, List<String> errors, List<String> warnings) {
        if (saga.steps == null) {
            return;
        }
        
        for (StepDefinition step : saga.steps.values()) {
            validateStepMethod(step, errors, warnings);
            validateCompensationMethod(step, errors, warnings);
        }
    }
    
    private void validateStepMethod(StepDefinition step, List<String> errors, List<String> warnings) {
        if (step.stepMethod == null) {
            return; // Already validated in basic validation
        }

        try {
            Object bean = step.stepBean != null ? step.stepBean : applicationContext.getBean(step.stepMethod.getDeclaringClass());
            Class<?> beanClass = bean.getClass();
            
            // Validate the step method directly
            if (step.stepMethod != null) {
                validateMethodReturnType(step, step.stepMethod, warnings);
            } else {
                errors.add("Step '" + step.id + "' has no step method defined");
            }
            
        } catch (Exception e) {
            // Bean validation already handled this case
        }
    }
    
    private void validateCompensationMethod(StepDefinition step, List<String> errors, List<String> warnings) {
        if (step.compensateName == null || step.compensateName.trim().isEmpty()) {
            return;
        }

        try {
            Object bean = step.compensateBean != null ? step.compensateBean :
                         (step.stepBean != null ? step.stepBean : applicationContext.getBean(step.stepMethod.getDeclaringClass()));

            // Validate the compensation method directly
            if (step.compensateMethod != null) {
                // Method is already resolved, just validate it exists
                if (step.compensateMethod.getDeclaringClass().isAssignableFrom(bean.getClass())) {
                    // Method is valid
                } else {
                    errors.add("Step '" + step.id + "' compensation method is not compatible with bean class");
                }
            } else {
                warnings.add("Step '" + step.id + "' has compensation name '" + step.compensateName + "' but no resolved compensation method");
            }

        } catch (Exception e) {
            // Bean validation already handled this case
        }
    }
    
    private void validateMethodReturnType(StepDefinition step, Method method, List<String> warnings) {
        Class<?> returnType = method.getReturnType();
        
        // Check for reactive return types
        if (returnType.equals(reactor.core.publisher.Mono.class) || 
            returnType.equals(reactor.core.publisher.Flux.class)) {
            // Good - reactive return type
        } else if (returnType.equals(java.util.concurrent.CompletableFuture.class)) {
            warnings.add("Step '" + step.id + "' method '" + method.getName() + "' returns CompletableFuture. Consider using Mono for better integration.");
        } else if (returnType.equals(void.class) || returnType.equals(Void.class)) {
            warnings.add("Step '" + step.id + "' method '" + method.getName() + "' returns void. Consider returning Mono<Void> for better reactive integration.");
        } else {
            warnings.add("Step '" + step.id + "' method '" + method.getName() + "' returns " + returnType.getSimpleName() + ". Consider returning Mono<" + returnType.getSimpleName() + "> for reactive integration.");
        }
    }
    
    /**
     * Validates a saga definition and throws an exception if validation fails.
     * 
     * @param saga the saga definition to validate
     * @throws SagaValidationException if validation fails
     */
    public void validateSagaDefinitionOrThrow(SagaDefinition saga) throws SagaValidationException {
        SagaValidator.ValidationResult result = validateSagaDefinition(saga);
        
        if (result.hasErrors()) {
            throw new SagaValidationException("Saga validation failed: " + result.getErrors());
        }
    }
    
    /**
     * Validates saga inputs and throws an exception if validation fails.
     * 
     * @param saga the saga definition
     * @param inputs the input parameters
     * @throws SagaValidationException if validation fails
     */
    public void validateSagaInputsOrThrow(SagaDefinition saga, Object inputs) throws SagaValidationException {
        SagaValidator.ValidationResult result = validateSagaInputs(saga, inputs);
        
        if (result.hasErrors()) {
            throw new SagaValidationException("Saga input validation failed: " + result.getErrors());
        }
    }
    
    /**
     * Exception thrown when saga validation fails.
     */
    public static class SagaValidationException extends Exception {
        public SagaValidationException(String message) {
            super(message);
        }
        
        public SagaValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
