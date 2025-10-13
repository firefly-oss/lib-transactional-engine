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

import com.firefly.transactional.tcc.core.TccContext;
import com.firefly.transactional.tcc.core.TccInputs;
import com.firefly.transactional.tcc.core.TccResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;

/**
 * Manages data flow between TCCs in a composition.
 * <p>
 * This manager handles the complex task of transferring data from completed TCCs
 * to subsequent TCCs in the composition, supporting various data mapping patterns
 * and ensuring type safety where possible.
 */
public class TccCompositionDataFlowManager {
    
    private static final Logger log = LoggerFactory.getLogger(TccCompositionDataFlowManager.class);
    
    /**
     * Prepares TCC inputs with data from previous TCCs and composition inputs.
     *
     * @param compositionTcc the TCC configuration within the composition
     * @param compositionContext the composition context containing previous results
     * @return a Mono containing the prepared TCC inputs
     */
    public Mono<TccInputs> prepareTccInputs(TccComposition.CompositionTcc compositionTcc,
                                           TccCompositionContext compositionContext) {
        Objects.requireNonNull(compositionTcc, "compositionTcc cannot be null");
        Objects.requireNonNull(compositionContext, "compositionContext cannot be null");

        return Mono.fromCallable(() -> {
            TccInputs.Builder inputsBuilder = TccInputs.builder();

            // Apply static inputs from the composition TCC configuration
            applyStaticInputs(compositionTcc, inputsBuilder);

            // Apply data mappings from previous TCCs
            applyDataMappings(compositionTcc, compositionContext, inputsBuilder);

            // Apply shared variables
            applySharedVariables(compositionContext, inputsBuilder);

            TccInputs finalInputs = inputsBuilder.build();

            log.debug("Prepared TCC inputs for '{}' with {} participants",
                     compositionTcc.compositionId, finalInputs.getAllInputs().size());

            return finalInputs;
        });
    }

    /**
     * Prepares a TCC context with composition-specific headers.
     *
     * @param compositionTcc the TCC configuration within the composition
     * @param compositionContext the composition context containing previous results
     * @return a Mono containing the prepared TCC context
     */
    public Mono<TccContext> prepareTccContext(TccComposition.CompositionTcc compositionTcc,
                                             TccCompositionContext compositionContext) {
        Objects.requireNonNull(compositionTcc, "compositionTcc cannot be null");
        Objects.requireNonNull(compositionContext, "compositionContext cannot be null");

        return Mono.fromCallable(() -> {
            // Create a new TCC context based on the root context
            TccContext rootContext = compositionContext.getRootContext();
            TccContext tccContext = new TccContext(
                rootContext.correlationId() + "-" + compositionTcc.compositionId
            );

            // Copy headers from root context
            rootContext.getHeaders().forEach(tccContext::setHeader);

            // Add composition-specific headers
            tccContext.setHeader("X-Composition-Name", compositionContext.getCompositionName());
            tccContext.setHeader("X-Composition-Id", compositionContext.getCompositionId());
            tccContext.setHeader("X-Tcc-Id", compositionTcc.compositionId);

            return tccContext;
        });
    }
    
    /**
     * Applies static inputs defined in the composition TCC configuration.
     */
    private void applyStaticInputs(TccComposition.CompositionTcc compositionTcc, TccInputs.Builder inputsBuilder) {
        if (compositionTcc.inputs != null) {
            compositionTcc.inputs.getAllInputs().forEach((key, value) -> {
                inputsBuilder.forParticipant(key, value);
                log.trace("Applied static input '{}' = '{}' to TCC '{}'",
                         key, value, compositionTcc.compositionId);
            });
        }
    }
    
    /**
     * Applies data mappings from previous TCCs to the current TCC inputs.
     */
    private void applyDataMappings(TccComposition.CompositionTcc compositionTcc,
                                  TccCompositionContext compositionContext,
                                  TccInputs.Builder inputsBuilder) {
        for (Map.Entry<String, TccComposition.DataMapping> entry : compositionTcc.dataFromTccs.entrySet()) {
            String targetKey = entry.getKey();
            TccComposition.DataMapping mapping = entry.getValue();

            try {
                Object value = extractDataFromMapping(mapping, compositionContext);
                if (value != null) {
                    inputsBuilder.forParticipant(targetKey, value);
                    log.trace("Applied data mapping '{}' = '{}' from TCC '{}' to TCC '{}'",
                             targetKey, value, mapping.sourceTccId, compositionTcc.compositionId);
                } else {
                    log.warn("Data mapping for '{}' from TCC '{}' participant '{}' key '{}' returned null",
                            targetKey, mapping.sourceTccId, mapping.sourceParticipantId, mapping.sourceKey);
                }
            } catch (Exception e) {
                log.error("Failed to apply data mapping for '{}' from TCC '{}': {}",
                         targetKey, mapping.sourceTccId, e.getMessage(), e);
                // Continue with other mappings - don't fail the entire preparation
            }
        }
    }
    
    /**
     * Extracts data from a completed TCC based on the data mapping configuration.
     */
    private Object extractDataFromMapping(TccComposition.DataMapping mapping,
                                         TccCompositionContext compositionContext) {
        // Get the source TCC result
        TccResult sourceTccResult = compositionContext.getTccResult(mapping.sourceTccId);
        if (sourceTccResult == null) {
            log.warn("Source TCC '{}' result not found for data mapping", mapping.sourceTccId);
            return null;
        }
        
        if (!sourceTccResult.isSuccess()) {
            log.warn("Source TCC '{}' was not successful, cannot extract data", mapping.sourceTccId);
            return null;
        }
        
        // Get the participant result
        TccResult.ParticipantResult participantResult = sourceTccResult.getParticipantResult(mapping.sourceParticipantId);
        if (participantResult == null) {
            log.warn("Participant '{}' not found in TCC '{}' result",
                    mapping.sourceParticipantId, mapping.sourceTccId);
            return null;
        }

        // Extract the data based on the source key
        Object tryResult = participantResult.getTryResult();
        if (tryResult == null) {
            log.warn("TRY result is null for participant '{}' in TCC '{}'", 
                    mapping.sourceParticipantId, mapping.sourceTccId);
            return null;
        }
        
        // If the source key is empty or matches a special pattern, return the entire result
        if (mapping.sourceKey == null || mapping.sourceKey.isEmpty() || "$result".equals(mapping.sourceKey)) {
            return tryResult;
        }
        
        // Try to extract a field from the result object
        return extractFieldFromObject(tryResult, mapping.sourceKey);
    }
    
    /**
     * Extracts a field from an object using reflection or map access.
     */
    private Object extractFieldFromObject(Object object, String fieldName) {
        if (object == null || fieldName == null) {
            return null;
        }
        
        try {
            // If the object is a Map, try to get the value directly
            if (object instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) object;
                return map.get(fieldName);
            }
            
            // Try to access the field using reflection
            try {
                var field = object.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException e) {
                // Try to access via getter method
                String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                try {
                    var method = object.getClass().getMethod(getterName);
                    return method.invoke(object);
                } catch (Exception methodException) {
                    // Try boolean getter
                    String booleanGetterName = "is" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                    try {
                        var booleanMethod = object.getClass().getMethod(booleanGetterName);
                        return booleanMethod.invoke(object);
                    } catch (Exception booleanMethodException) {
                        log.warn("Could not extract field '{}' from object of type '{}': no field, getter, or boolean getter found", 
                                fieldName, object.getClass().getSimpleName());
                        return null;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error extracting field '{}' from object of type '{}': {}", 
                     fieldName, object.getClass().getSimpleName(), e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Applies shared variables from the composition context to the TCC inputs.
     */
    private void applySharedVariables(TccCompositionContext compositionContext, TccInputs.Builder inputsBuilder) {
        Map<String, Object> sharedVariables = compositionContext.getSharedVariables();
        for (Map.Entry<String, Object> entry : sharedVariables.entrySet()) {
            String key = "shared." + entry.getKey();
            inputsBuilder.forParticipant(key, entry.getValue());
            log.trace("Applied shared variable '{}' = '{}' to TCC inputs", key, entry.getValue());
        }
    }
}
