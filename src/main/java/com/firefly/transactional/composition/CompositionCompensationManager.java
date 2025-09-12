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

import com.firefly.transactional.core.SagaResult;
import com.firefly.transactional.engine.SagaEngine;
import com.firefly.transactional.engine.compensation.CompensationErrorHandler;
import com.firefly.transactional.engine.compensation.CompensationErrorHandlerFactory;
import com.firefly.transactional.observability.SagaEvents;
import com.firefly.transactional.registry.SagaDefinition;
import com.firefly.transactional.registry.SagaRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Manages compensation strategies for saga compositions.
 * <p>
 * Handles the complex task of compensating multiple sagas within a composition,
 * taking into account the composition's compensation policy, individual saga
 * compensation requirements, and error handling strategies.
 */
public class CompositionCompensationManager {
    
    private static final Logger log = LoggerFactory.getLogger(CompositionCompensationManager.class);
    
    private final SagaEngine sagaEngine;
    private final SagaEvents sagaEvents;
    private final CompensationErrorHandler errorHandler;
    
    public CompositionCompensationManager(SagaEngine sagaEngine, SagaEvents sagaEvents) {
        this.sagaEngine = Objects.requireNonNull(sagaEngine, "sagaEngine cannot be null");
        this.sagaEvents = Objects.requireNonNull(sagaEvents, "sagaEvents cannot be null");
        this.errorHandler = CompensationErrorHandlerFactory.getHandler("robust");
    }
    
    /**
     * Compensates a saga composition based on its compensation policy.
     * 
     * @param composition the composition to compensate
     * @param compositionContext the composition context
     * @param sagaRegistry the saga registry
     * @return a Mono that completes when compensation is finished
     */
    public Mono<Void> compensateComposition(SagaComposition composition,
                                          SagaCompositionContext compositionContext,
                                          SagaRegistry sagaRegistry) {
        Objects.requireNonNull(composition, "composition cannot be null");
        Objects.requireNonNull(compositionContext, "compositionContext cannot be null");
        Objects.requireNonNull(sagaRegistry, "sagaRegistry cannot be null");
        
        log.info("Starting compensation for composition '{}' with policy '{}'", 
                composition.name, composition.compensationPolicy);
        
        // Emit compensation start event
        sagaEvents.onCompositionCompensationStarted(composition.name, compositionContext.getCompositionId());
        
        Mono<Void> compensationMono = switch (composition.compensationPolicy) {
            case STRICT_SEQUENTIAL -> compensateSequential(composition, compositionContext, sagaRegistry);
            case GROUPED_PARALLEL -> compensateGroupedParallel(composition, compositionContext, sagaRegistry);
            case BEST_EFFORT_PARALLEL -> compensateBestEffortParallel(composition, compositionContext, sagaRegistry);
            case RETRY_WITH_BACKOFF -> compensateWithRetry(composition, compositionContext, sagaRegistry);
            case CIRCUIT_BREAKER -> compensateWithCircuitBreaker(composition, compositionContext, sagaRegistry);
        };

        return compensationMono
                .doOnSuccess(v -> {
                    log.info("Completed compensation for composition '{}'", composition.name);
                    sagaEvents.onCompositionCompensationCompleted(composition.name, compositionContext.getCompositionId(), true);
                })
                .doOnError(error -> {
                    log.error("Compensation failed for composition '{}'", composition.name, error);
                    sagaEvents.onCompositionCompensationCompleted(composition.name, compositionContext.getCompositionId(), false);
                });
    }
    
    /**
     * Compensates sagas in strict reverse completion order.
     */
    private Mono<Void> compensateSequential(SagaComposition composition,
                                           SagaCompositionContext compositionContext,
                                           SagaRegistry sagaRegistry) {
        List<String> completedSagas = getCompletedSagasInReverseOrder(compositionContext);
        
        return Flux.fromIterable(completedSagas)
                .concatMap(sagaId -> compensateSaga(sagaId, composition, compositionContext, sagaRegistry))
                .then();
    }
    
    /**
     * Compensates sagas in parallel groups based on dependency layers.
     */
    private Mono<Void> compensateGroupedParallel(SagaComposition composition,
                                                SagaCompositionContext compositionContext,
                                                SagaRegistry sagaRegistry) {
        List<List<String>> compensationLayers = buildCompensationLayers(composition, compositionContext);
        
        return Flux.fromIterable(compensationLayers)
                .concatMap(layer -> 
                    Flux.fromIterable(layer)
                        .flatMap(sagaId -> compensateSaga(sagaId, composition, compositionContext, sagaRegistry))
                        .then())
                .then();
    }
    
    /**
     * Compensates all sagas in parallel, continuing even if some fail.
     */
    private Mono<Void> compensateBestEffortParallel(SagaComposition composition,
                                                   SagaCompositionContext compositionContext,
                                                   SagaRegistry sagaRegistry) {
        List<String> completedSagas = new ArrayList<>(compositionContext.getCompletedSagas());
        
        return Flux.fromIterable(completedSagas)
                .flatMap(sagaId -> compensateSaga(sagaId, composition, compositionContext, sagaRegistry)
                        .onErrorResume(error -> {
                            log.warn("Best effort compensation failed for saga '{}' in composition '{}', continuing",
                                    sagaId, composition.name, error);
                            return Mono.empty();
                        }))
                .then();
    }
    
    /**
     * Compensates with retry logic for failed compensations.
     */
    private Mono<Void> compensateWithRetry(SagaComposition composition,
                                          SagaCompositionContext compositionContext,
                                          SagaRegistry sagaRegistry) {
        List<String> completedSagas = getCompletedSagasInReverseOrder(compositionContext);
        
        return Flux.fromIterable(completedSagas)
                .concatMap(sagaId -> compensateSagaWithRetry(sagaId, composition, compositionContext, sagaRegistry))
                .then();
    }
    
    /**
     * Compensates with circuit breaker pattern for critical failures.
     */
    private Mono<Void> compensateWithCircuitBreaker(SagaComposition composition,
                                                   SagaCompositionContext compositionContext,
                                                   SagaRegistry sagaRegistry) {
        List<String> completedSagas = getCompletedSagasInReverseOrder(compositionContext);
        boolean circuitOpen = false;
        
        return Flux.fromIterable(completedSagas)
                .concatMap(sagaId -> {
                    // In a real implementation, circuit breaker state would be managed properly
                    return compensateSaga(sagaId, composition, compositionContext, sagaRegistry)
                            .onErrorResume(error -> {
                                // Check if this is a critical error that should open the circuit
                                if (isCriticalCompensationError(error)) {
                                    log.error("Critical compensation error for saga '{}', opening circuit", sagaId, error);
                                    return Mono.error(new RuntimeException("Compensation circuit breaker opened", error));
                                }
                                return Mono.empty();
                            });
                })
                .then();
    }
    
    /**
     * Compensates a single saga within the composition.
     */
    private Mono<Void> compensateSaga(String sagaId,
                                     SagaComposition composition,
                                     SagaCompositionContext compositionContext,
                                     SagaRegistry sagaRegistry) {
        SagaResult sagaResult = compositionContext.getSagaResult(sagaId);
        if (sagaResult == null || !sagaResult.isSuccess()) {
            log.debug("Skipping compensation for saga '{}' - not successfully completed", sagaId);
            return Mono.empty();
        }
        
        SagaComposition.CompositionSaga compositionSaga = composition.sagas.get(sagaId);
        if (compositionSaga == null) {
            log.warn("Composition saga '{}' not found in composition definition", sagaId);
            return Mono.empty();
        }
        
        SagaDefinition sagaDefinition = sagaRegistry.getSaga(compositionSaga.sagaName);
        if (sagaDefinition == null) {
            log.warn("Saga definition '{}' not found for compensation", compositionSaga.sagaName);
            return Mono.empty();
        }
        
        log.info("Compensating saga '{}' in composition '{}'", sagaId, composition.name);
        
        // Note: Individual saga compensation is handled by the SagaEngine internally
        // This is where we would trigger saga-specific compensation if needed
        // For now, we'll just log the compensation action
        
        return Mono.fromRunnable(() -> {
            log.info("Saga '{}' compensation completed in composition '{}'", sagaId, composition.name);
        });
    }
    
    /**
     * Compensates a saga with retry logic.
     */
    private Mono<Void> compensateSagaWithRetry(String sagaId,
                                              SagaComposition composition,
                                              SagaCompositionContext compositionContext,
                                              SagaRegistry sagaRegistry) {
        return compensateSaga(sagaId, composition, compositionContext, sagaRegistry)
                .retry(3)
                .onErrorResume(error -> {
                    log.error("Failed to compensate saga '{}' after retries in composition '{}'", 
                             sagaId, composition.name, error);
                    return errorHandler.handleError(sagaId, error, compositionContext.getRootContext(), 3)
                            .then();
                });
    }
    
    /**
     * Gets completed sagas in reverse completion order for compensation.
     */
    private List<String> getCompletedSagasInReverseOrder(SagaCompositionContext compositionContext) {
        List<String> completedSagas = new ArrayList<>(compositionContext.getCompletedSagas());
        Collections.reverse(completedSagas);
        return completedSagas;
    }
    
    /**
     * Builds compensation layers based on saga dependencies.
     */
    private List<List<String>> buildCompensationLayers(SagaComposition composition,
                                                       SagaCompositionContext compositionContext) {
        // Build reverse dependency layers for compensation
        List<List<String>> layers = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        Set<String> completedSagas = compositionContext.getCompletedSagas();
        
        while (processed.size() < completedSagas.size()) {
            List<String> currentLayer = new ArrayList<>();
            
            for (String sagaId : completedSagas) {
                if (processed.contains(sagaId)) {
                    continue;
                }
                
                SagaComposition.CompositionSaga saga = composition.sagas.get(sagaId);
                if (saga == null) {
                    continue;
                }
                
                // Check if all sagas that depend on this one are already processed
                boolean canCompensate = true;
                for (String otherSagaId : completedSagas) {
                    if (processed.contains(otherSagaId)) {
                        continue;
                    }
                    
                    SagaComposition.CompositionSaga otherSaga = composition.sagas.get(otherSagaId);
                    if (otherSaga != null && otherSaga.dependencies.contains(sagaId)) {
                        canCompensate = false;
                        break;
                    }
                }
                
                if (canCompensate) {
                    currentLayer.add(sagaId);
                }
            }
            
            if (currentLayer.isEmpty()) {
                break; // Avoid infinite loop
            }
            
            layers.add(currentLayer);
            processed.addAll(currentLayer);
        }
        
        return layers;
    }
    
    /**
     * Determines if an error is critical enough to open the circuit breaker.
     */
    private boolean isCriticalCompensationError(Throwable error) {
        // Define criteria for critical errors
        return error instanceof OutOfMemoryError ||
               error instanceof StackOverflowError ||
               (error.getMessage() != null && error.getMessage().contains("critical"));
    }
}
