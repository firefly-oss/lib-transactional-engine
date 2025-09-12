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

import com.firefly.transactional.core.SagaContext;
import com.firefly.transactional.core.SagaResult;
import com.firefly.transactional.engine.SagaEngine;
import com.firefly.transactional.engine.StepInputs;
import com.firefly.transactional.observability.SagaEvents;
import com.firefly.transactional.registry.SagaDefinition;
import com.firefly.transactional.registry.SagaRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/**
 * Orchestrates the execution of saga compositions.
 * <p>
 * Handles the complex workflow of executing multiple sagas with dependencies,
 * parallel execution, data flow, and comprehensive error handling and compensation.
 */
public class CompositionExecutionOrchestrator {
    
    private static final Logger log = LoggerFactory.getLogger(CompositionExecutionOrchestrator.class);
    
    private final SagaEngine sagaEngine;
    private final SagaEvents sagaEvents;
    private final CompositionDataFlowManager dataFlowManager;
    private final CompositionCompensationManager compensationManager;
    
    public CompositionExecutionOrchestrator(SagaEngine sagaEngine, SagaEvents sagaEvents) {
        this.sagaEngine = Objects.requireNonNull(sagaEngine, "sagaEngine cannot be null");
        this.sagaEvents = Objects.requireNonNull(sagaEvents, "sagaEvents cannot be null");
        this.dataFlowManager = new CompositionDataFlowManager();
        this.compensationManager = new CompositionCompensationManager(sagaEngine, sagaEvents);
    }
    
    /**
     * Orchestrates the execution of a saga composition.
     * 
     * @param composition the composition to execute
     * @param rootContext the root saga context
     * @param sagaRegistry the saga registry for resolving saga definitions
     * @return a Mono containing the composition execution result
     */
    public Mono<SagaCompositionResult> orchestrate(SagaComposition composition, 
                                                  SagaContext rootContext,
                                                  SagaRegistry sagaRegistry) {
        Objects.requireNonNull(composition, "composition cannot be null");
        Objects.requireNonNull(rootContext, "rootContext cannot be null");
        Objects.requireNonNull(sagaRegistry, "sagaRegistry cannot be null");
        
        SagaCompositionContext compositionContext = new SagaCompositionContext(composition.name, rootContext);
        
        // Emit composition start event
        emitCompositionStartEvent(composition, compositionContext);
        
        return executeCompositionLayers(composition, compositionContext, sagaRegistry)
                .then(Mono.fromCallable(() -> {
                    boolean success = determineOverallSuccess(composition, compositionContext);
                    Throwable compositionError = success ? null : 
                        new RuntimeException("Composition failed - see individual saga errors");
                    
                    // Emit completion event
                    emitCompositionCompletedEvent(composition, compositionContext, success);
                    
                    return SagaCompositionResult.from(compositionContext, success, compositionError);
                }))
                .onErrorResume(error -> {
                    log.error("Composition '{}' failed with error", composition.name, error);
                    
                    // Emit error event
                    emitCompositionErrorEvent(composition, compositionContext, error);
                    
                    return compensationManager.compensateComposition(composition, compositionContext, sagaRegistry)
                            .then(Mono.fromCallable(() ->
                                SagaCompositionResult.from(compositionContext, false, error)));
                });
    }
    
    /**
     * Executes the composition by processing sagas in dependency-ordered layers.
     */
    private Mono<Void> executeCompositionLayers(SagaComposition composition,
                                               SagaCompositionContext compositionContext,
                                               SagaRegistry sagaRegistry) {
        return executeCompositionLayersWithDepthLimit(composition, compositionContext, sagaRegistry, 0);
    }

    private Mono<Void> executeCompositionLayersWithDepthLimit(SagaComposition composition,
                                                             SagaCompositionContext compositionContext,
                                                             SagaRegistry sagaRegistry,
                                                             int depth) {
        // Prevent infinite recursion
        if (depth > 100) {
            return Mono.error(new RuntimeException(
                "Composition execution depth limit exceeded - possible circular dependency"));
        }

        return Mono.defer(() -> {
            Set<String> completedAndFailedSagas = new HashSet<>(compositionContext.getCompletedSagas());
            completedAndFailedSagas.addAll(compositionContext.getFailedSagas());
            completedAndFailedSagas.addAll(compositionContext.getSkippedSagas());

            List<String> executableSagas = composition.getExecutableParallelSagas(completedAndFailedSagas);

            if (executableSagas.isEmpty()) {
                if (composition.hasRemainingWork(completedAndFailedSagas)) {
                    // Check if we're stuck due to failed dependencies
                    return Mono.error(new RuntimeException(
                        "Composition execution stuck - remaining sagas have unsatisfied dependencies"));
                }
                return Mono.empty(); // All work completed
            }

            // Execute current layer of sagas in parallel
            return Flux.fromIterable(executableSagas)
                    .flatMap(sagaId -> executeSagaInComposition(
                        composition.sagas.get(sagaId), composition, compositionContext, sagaRegistry))
                    .then()
                    .then(Mono.defer(() -> {
                        // Continue with next layer if there's more work
                        Set<String> newCompletedAndFailed = new HashSet<>(compositionContext.getCompletedSagas());
                        newCompletedAndFailed.addAll(compositionContext.getFailedSagas());
                        newCompletedAndFailed.addAll(compositionContext.getSkippedSagas());

                        if (composition.hasRemainingWork(newCompletedAndFailed)) {
                            return executeCompositionLayersWithDepthLimit(composition, compositionContext, sagaRegistry, depth + 1);
                        }
                        return Mono.empty();
                    }));
        });
    }
    
    /**
     * Executes a single saga within the composition context.
     */
    private Mono<Void> executeSagaInComposition(SagaComposition.CompositionSaga compositionSaga,
                                              SagaComposition composition,
                                              SagaCompositionContext compositionContext,
                                              SagaRegistry sagaRegistry) {
        return Mono.defer(() -> {
            // Check execution condition
            if (!compositionSaga.executionCondition.apply(compositionContext)) {
                log.info("Skipping saga '{}' in composition '{}' due to execution condition",
                        compositionSaga.compositionId, composition.name);
                compositionContext.recordSagaSkipped(compositionSaga.compositionId);
                return Mono.empty();
            }
            
            // Resolve saga definition
            SagaDefinition sagaDefinition = sagaRegistry.getSaga(compositionSaga.sagaName);
            if (sagaDefinition == null) {
                return Mono.error(new RuntimeException(
                    "Saga definition not found: " + compositionSaga.sagaName));
            }
            
            // Prepare inputs with data from previous sagas
            StepInputs sagaInputs = dataFlowManager.prepareInputsForSaga(compositionSaga, compositionContext);
            
            // Create saga context
            SagaContext sagaContext = new SagaContext(
                compositionContext.getRootContext().correlationId() + "-" + compositionSaga.compositionId,
                compositionSaga.sagaName);
            
            // Propagate context data
            dataFlowManager.propagateContextToSaga(sagaContext, compositionContext, compositionSaga);
            
            // Record saga start
            compositionContext.recordSagaStarted(compositionSaga.compositionId);
            emitSagaStartEvent(composition, compositionSaga, compositionContext);
            
            // Execute the saga
            Mono<SagaResult> sagaExecution = sagaEngine.execute(sagaDefinition, sagaInputs, sagaContext);
            
            // Apply timeout if specified
            if (compositionSaga.timeoutMs > 0) {
                sagaExecution = sagaExecution.timeout(Duration.ofMillis(compositionSaga.timeoutMs));
            }
            
            return sagaExecution
                    .doOnNext(result -> {
                        // Record saga completion
                        compositionContext.recordSagaResult(compositionSaga.compositionId, result, sagaContext);
                        
                        // Extract shared data
                        dataFlowManager.extractSharedDataFromSaga(result, sagaContext, compositionContext, compositionSaga);
                        
                        // Emit completion event
                        emitSagaCompletedEvent(composition, compositionSaga, compositionContext, result);
                    })
                    .doOnError(error -> {
                        log.error("Saga '{}' failed in composition '{}'", 
                                compositionSaga.compositionId, composition.name, error);
                        
                        // Create a failed result
                        SagaResult failedResult = createFailedSagaResult(compositionSaga, sagaContext, error);
                        compositionContext.recordSagaResult(compositionSaga.compositionId, failedResult, sagaContext);
                        
                        // Emit error event
                        emitSagaErrorEvent(composition, compositionSaga, compositionContext, error);
                    })
                    .onErrorResume(error -> {
                        if (compositionSaga.optional) {
                            log.warn("Optional saga '{}' failed in composition '{}', continuing execution",
                                    compositionSaga.compositionId, composition.name, error);
                            return Mono.empty();
                        }
                        return Mono.error(error);
                    })
                    .then();
        });
    }
    
    /**
     * Creates a failed SagaResult for error scenarios.
     */
    private SagaResult createFailedSagaResult(SagaComposition.CompositionSaga compositionSaga,
                                            SagaContext sagaContext,
                                            Throwable error) {
        return SagaResult.from(
            compositionSaga.sagaName,
            sagaContext,
            Collections.emptyMap(), // no compensated flags
            Map.of("composition-error", error),
            Collections.emptyList() // no step IDs
        );
    }
    
    /**
     * Determines the overall success of the composition.
     */
    private boolean determineOverallSuccess(SagaComposition composition, SagaCompositionContext compositionContext) {
        // Check if any required sagas failed
        for (String sagaId : composition.sagas.keySet()) {
            SagaComposition.CompositionSaga saga = composition.sagas.get(sagaId);
            if (!saga.optional && compositionContext.isSagaFailed(sagaId)) {
                return false;
            }
        }
        
        // Check if all required sagas completed
        for (String sagaId : composition.sagas.keySet()) {
            SagaComposition.CompositionSaga saga = composition.sagas.get(sagaId);
            if (!saga.optional && !compositionContext.isSagaCompleted(sagaId) && !compositionContext.isSagaSkipped(sagaId)) {
                return false;
            }
        }
        
        return true;
    }
    

    
    // Event emission methods
    private void emitCompositionStartEvent(SagaComposition composition, SagaCompositionContext context) {
        sagaEvents.onCompositionStarted(composition.name, context.getCompositionId());
        sagaEvents.onCompositionStarted(composition.name, context.getCompositionId(), context);
        log.debug("Emitted composition start event for '{}'", composition.name);
    }

    private void emitCompositionCompletedEvent(SagaComposition composition, SagaCompositionContext context, boolean success) {
        long latencyMs = java.time.Duration.between(context.getStartedAt(), java.time.Instant.now()).toMillis();
        sagaEvents.onCompositionCompleted(
            composition.name,
            context.getCompositionId(),
            success,
            latencyMs,
            context.getCompletedSagas().size(),
            context.getFailedSagas().size(),
            context.getSkippedSagas().size()
        );
        log.debug("Emitted composition completed event for '{}' with success: {}", composition.name, success);
    }

    private void emitCompositionErrorEvent(SagaComposition composition, SagaCompositionContext context, Throwable error) {
        long latencyMs = java.time.Duration.between(context.getStartedAt(), java.time.Instant.now()).toMillis();
        sagaEvents.onCompositionFailed(composition.name, context.getCompositionId(), error, latencyMs);
        log.debug("Emitted composition error event for '{}'", composition.name, error);
    }

    private void emitSagaStartEvent(SagaComposition composition, SagaComposition.CompositionSaga saga, SagaCompositionContext context) {
        sagaEvents.onCompositionSagaStarted(composition.name, context.getCompositionId(), saga.compositionId, saga.sagaName);
        log.debug("Emitted saga start event for '{}' in composition '{}'", saga.compositionId, composition.name);
    }

    private void emitSagaCompletedEvent(SagaComposition composition, SagaComposition.CompositionSaga saga, SagaCompositionContext context, SagaResult result) {
        long latencyMs = result.duration().toMillis();
        sagaEvents.onCompositionSagaCompleted(composition.name, context.getCompositionId(), saga.compositionId, saga.sagaName, latencyMs);
        log.debug("Emitted saga completed event for '{}' in composition '{}'", saga.compositionId, composition.name);
    }

    private void emitSagaErrorEvent(SagaComposition composition, SagaComposition.CompositionSaga saga, SagaCompositionContext context, Throwable error) {
        // Calculate latency from saga start time if available
        long latencyMs = 0;
        java.time.Instant startTime = context.getRootContext().getStepStartedAt(saga.compositionId);
        if (startTime != null) {
            latencyMs = java.time.Duration.between(startTime, java.time.Instant.now()).toMillis();
        }
        sagaEvents.onCompositionSagaFailed(composition.name, context.getCompositionId(), saga.compositionId, saga.sagaName, error, latencyMs);
        log.debug("Emitted saga error event for '{}' in composition '{}'", saga.compositionId, composition.name, error);
    }
}
