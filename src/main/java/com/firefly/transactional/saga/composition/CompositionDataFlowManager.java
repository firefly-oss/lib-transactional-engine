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

import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.saga.core.SagaResult;
import com.firefly.transactional.saga.engine.StepInputs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Manages data flow between sagas in a composition.
 * <p>
 * Handles the mapping and transformation of data from completed sagas
 * to provide inputs for subsequent sagas in the composition workflow.
 */
public class CompositionDataFlowManager {
    
    private static final Logger log = LoggerFactory.getLogger(CompositionDataFlowManager.class);
    
    /**
     * Prepares inputs for a saga by combining its static inputs with data from previous sagas.
     * 
     * @param saga the composition saga to prepare inputs for
     * @param compositionContext the composition context containing previous saga results
     * @return the prepared step inputs for the saga
     */
    public StepInputs prepareInputsForSaga(SagaComposition.CompositionSaga saga, 
                                          SagaCompositionContext compositionContext) {
        Objects.requireNonNull(saga, "saga cannot be null");
        Objects.requireNonNull(compositionContext, "compositionContext cannot be null");
        
        // Start with the saga's static inputs
        Map<String, Object> allInputs = new HashMap<>();
        if (saga.inputs != null) {
            allInputs.putAll(saga.inputs.materializeAll(compositionContext.getRootContext()));
        }
        
        // Add data from other sagas
        for (Map.Entry<String, SagaComposition.DataMapping> entry : saga.dataFromSagas.entrySet()) {
            String targetKey = entry.getKey();
            SagaComposition.DataMapping mapping = entry.getValue();
            
            Object value = extractDataFromSaga(mapping, compositionContext);
            if (value != null) {
                // Apply transformation if specified
                Object transformedValue = mapping.transformer.apply(value);
                allInputs.put(targetKey, transformedValue);
                
                log.debug("Mapped data from saga '{}' key '{}' to saga '{}' key '{}': {}",
                         mapping.sourceSagaId, mapping.sourceKey, saga.compositionId, targetKey, transformedValue);
            } else {
                log.warn("No data found for mapping from saga '{}' key '{}' to saga '{}' key '{}'",
                        mapping.sourceSagaId, mapping.sourceKey, saga.compositionId, targetKey);
            }
        }
        
        // Add shared variables from composition context
        for (Map.Entry<String, Object> entry : compositionContext.getSharedVariables().entrySet()) {
            String key = "shared." + entry.getKey();
            allInputs.put(key, entry.getValue());
        }
        
        return StepInputs.builder().forSteps(allInputs).build();
    }
    
    /**
     * Extracts data from a completed saga based on the data mapping configuration.
     * 
     * @param mapping the data mapping specification
     * @param compositionContext the composition context
     * @return the extracted data value, or null if not found
     */
    private Object extractDataFromSaga(SagaComposition.DataMapping mapping, 
                                     SagaCompositionContext compositionContext) {
        SagaResult sagaResult = compositionContext.getSagaResult(mapping.sourceSagaId);
        if (sagaResult == null) {
            log.warn("Source saga '{}' not found in composition context", mapping.sourceSagaId);
            return null;
        }
        
        if (!sagaResult.isSuccess()) {
            log.warn("Source saga '{}' did not complete successfully, cannot extract data", mapping.sourceSagaId);
            return null;
        }
        
        // Try to extract from step results first
        for (Map.Entry<String, SagaResult.StepOutcome> stepEntry : sagaResult.steps().entrySet()) {
            String stepId = stepEntry.getKey();
            SagaResult.StepOutcome outcome = stepEntry.getValue();
            
            if (outcome.result() != null) {
                // If the source key matches the step ID, return the step result
                if (mapping.sourceKey.equals(stepId)) {
                    return outcome.result();
                }
                
                // If the step result is a map or object, try to extract the key
                Object stepResult = outcome.result();
                if (stepResult instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resultMap = (Map<String, Object>) stepResult;
                    if (resultMap.containsKey(mapping.sourceKey)) {
                        return resultMap.get(mapping.sourceKey);
                    }
                }
                
                // Try reflection for object properties
                Object propertyValue = extractPropertyFromObject(stepResult, mapping.sourceKey);
                if (propertyValue != null) {
                    return propertyValue;
                }
            }
        }
        
        // Try to extract from saga context variables
        SagaContext sagaContext = compositionContext.getSagaContext(mapping.sourceSagaId);
        if (sagaContext != null) {
            Object variable = sagaContext.getVariable(mapping.sourceKey);
            if (variable != null) {
                return variable;
            }
        }
        
        log.debug("Could not extract data for key '{}' from saga '{}'", 
                 mapping.sourceKey, mapping.sourceSagaId);
        return null;
    }
    
    /**
     * Attempts to extract a property from an object using reflection.
     * 
     * @param object the object to extract from
     * @param propertyName the property name
     * @return the property value, or null if not found or error occurred
     */
    private Object extractPropertyFromObject(Object object, String propertyName) {
        if (object == null || propertyName == null) {
            return null;
        }
        
        try {
            // Try getter method first
            String getterName = "get" + Character.toUpperCase(propertyName.charAt(0)) + 
                               propertyName.substring(1);
            
            java.lang.reflect.Method getter = object.getClass().getMethod(getterName);
            return getter.invoke(object);
        } catch (Exception e) {
            // Try field access
            try {
                java.lang.reflect.Field field = object.getClass().getDeclaredField(propertyName);
                field.setAccessible(true);
                return field.get(object);
            } catch (Exception fieldException) {
                log.debug("Could not extract property '{}' from object of type {}: {}", 
                         propertyName, object.getClass().getSimpleName(), fieldException.getMessage());
                return null;
            }
        }
    }
    
    /**
     * Propagates context data from the composition to individual saga contexts.
     * 
     * @param sagaContext the saga context to populate
     * @param compositionContext the composition context
     * @param saga the composition saga configuration
     */
    public void propagateContextToSaga(SagaContext sagaContext, 
                                     SagaCompositionContext compositionContext,
                                     SagaComposition.CompositionSaga saga) {
        Objects.requireNonNull(sagaContext, "sagaContext cannot be null");
        Objects.requireNonNull(compositionContext, "compositionContext cannot be null");
        Objects.requireNonNull(saga, "saga cannot be null");
        
        // Copy headers from root context
        SagaContext rootContext = compositionContext.getRootContext();
        for (Map.Entry<String, String> header : rootContext.headers().entrySet()) {
            sagaContext.putHeader(header.getKey(), header.getValue());
        }
        
        // Add composition-specific headers
        sagaContext.putHeader("X-Composition-Id", compositionContext.getCompositionId());
        sagaContext.putHeader("X-Composition-Name", compositionContext.getCompositionName());
        sagaContext.putHeader("X-Saga-Id", saga.compositionId);
        
        // Copy shared variables as saga variables
        for (Map.Entry<String, Object> entry : compositionContext.getSharedVariables().entrySet()) {
            sagaContext.putVariable("composition." + entry.getKey(), entry.getValue());
        }
        
        log.debug("Propagated context to saga '{}' in composition '{}'", 
                 saga.compositionId, compositionContext.getCompositionName());
    }
    
    /**
     * Extracts data from a completed saga result to update shared variables.
     * 
     * @param sagaResult the completed saga result
     * @param sagaContext the saga context
     * @param compositionContext the composition context to update
     * @param saga the composition saga configuration
     */
    public void extractSharedDataFromSaga(SagaResult sagaResult,
                                        SagaContext sagaContext,
                                        SagaCompositionContext compositionContext,
                                        SagaComposition.CompositionSaga saga) {
        Objects.requireNonNull(sagaResult, "sagaResult cannot be null");
        Objects.requireNonNull(sagaContext, "sagaContext cannot be null");
        Objects.requireNonNull(compositionContext, "compositionContext cannot be null");
        Objects.requireNonNull(saga, "saga cannot be null");
        
        // Extract variables that should be shared (prefixed with "shared.")
        for (Map.Entry<String, Object> entry : sagaContext.variables().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("shared.")) {
                String sharedKey = key.substring("shared.".length());
                compositionContext.setSharedVariable(sharedKey, entry.getValue());
                
                log.debug("Extracted shared variable '{}' from saga '{}' to composition context",
                         sharedKey, saga.compositionId);
            }
        }
    }
}
