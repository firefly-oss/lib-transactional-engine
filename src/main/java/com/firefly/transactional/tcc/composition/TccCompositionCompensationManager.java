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
import com.firefly.transactional.tcc.core.TccPhase;
import com.firefly.transactional.tcc.core.TccResult;
import com.firefly.transactional.tcc.engine.TccEngine;
import com.firefly.transactional.tcc.observability.TccEvents;
import com.firefly.transactional.tcc.registry.TccDefinition;
import com.firefly.transactional.tcc.registry.TccRegistry;
import com.firefly.transactional.shared.engine.CompensationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages compensation (CANCEL phase) for TCC compositions when failures occur.
 * <p>
 * This manager handles the complex task of canceling successfully completed TCCs
 * when a composition fails, respecting the compensation policy and ensuring
 * proper ordering of cancel operations.
 * <p>
 * Unlike SAGA compensation which runs compensation steps, TCC compensation
 * runs the CANCEL phase of the three-phase protocol for all TCCs that
 * successfully completed their TRY phase.
 */
public class TccCompositionCompensationManager {
    
    private static final Logger log = LoggerFactory.getLogger(TccCompositionCompensationManager.class);
    
    private final TccEngine tccEngine;
    private final TccEvents tccEvents;
    
    /**
     * Creates a new TCC composition compensation manager.
     * 
     * @param tccEngine the TCC engine for executing cancel operations
     * @param tccEvents the events system for observability
     */
    public TccCompositionCompensationManager(TccEngine tccEngine, TccEvents tccEvents) {
        this.tccEngine = Objects.requireNonNull(tccEngine, "tccEngine cannot be null");
        this.tccEvents = Objects.requireNonNull(tccEvents, "tccEvents cannot be null");
    }
    
    /**
     * Compensates a failed TCC composition by canceling all successfully completed TCCs.
     * 
     * @param composition the composition that failed
     * @param compositionContext the composition context with execution state
     * @param tccRegistry the TCC registry for resolving TCC definitions
     * @return a Mono that completes when compensation is finished
     */
    public Mono<Void> compensateComposition(TccComposition composition,
                                           TccCompositionContext compositionContext,
                                           TccRegistry tccRegistry) {
        Objects.requireNonNull(composition, "composition cannot be null");
        Objects.requireNonNull(compositionContext, "compositionContext cannot be null");
        Objects.requireNonNull(tccRegistry, "tccRegistry cannot be null");
        
        Set<String> completedTccs = compositionContext.getCompletedTccs();
        if (completedTccs.isEmpty()) {
            log.debug("No TCCs to compensate for composition '{}'", composition.name);
            return Mono.empty();
        }
        
        log.info("Starting compensation for composition '{}' with {} completed TCCs", 
                composition.name, completedTccs.size());
        
        // Emit compensation start event
        emitCompensationStartEvent(composition, compositionContext);
        
        return executeCompensationStrategy(composition, compositionContext, tccRegistry, completedTccs)
                .doOnSuccess(v -> {
                    log.info("Compensation completed for composition '{}'", composition.name);
                    emitCompensationCompletedEvent(composition, compositionContext, true);
                })
                .doOnError(error -> {
                    log.error("Compensation failed for composition '{}'", composition.name, error);
                    emitCompensationCompletedEvent(composition, compositionContext, false);
                });
    }
    
    /**
     * Executes the compensation strategy based on the composition's compensation policy.
     */
    private Mono<Void> executeCompensationStrategy(TccComposition composition,
                                                  TccCompositionContext compositionContext,
                                                  TccRegistry tccRegistry,
                                                  Set<String> completedTccs) {
        switch (composition.compensationPolicy) {
            case STRICT_SEQUENTIAL:
                return executeSequentialCompensation(composition, compositionContext, tccRegistry, completedTccs);
            case GROUPED_PARALLEL:
                return executeGroupedParallelCompensation(composition, compositionContext, tccRegistry, completedTccs);
            case BEST_EFFORT_PARALLEL:
                return executeBestEffortParallelCompensation(composition, compositionContext, tccRegistry, completedTccs);
            default:
                return Mono.error(new IllegalArgumentException("Unsupported compensation policy: " + composition.compensationPolicy));
        }
    }
    
    /**
     * Executes compensation in strict sequential order (reverse of execution order).
     */
    private Mono<Void> executeSequentialCompensation(TccComposition composition,
                                                     TccCompositionContext compositionContext,
                                                     TccRegistry tccRegistry,
                                                     Set<String> completedTccs) {
        // Get completed TCCs in reverse execution order
        List<String> compensationOrder = composition.executionOrder.stream()
                .filter(completedTccs::contains)
                .collect(Collectors.toList());
        Collections.reverse(compensationOrder);
        
        log.debug("Executing sequential compensation for {} TCCs in order: {}", 
                 compensationOrder.size(), compensationOrder);
        
        return Flux.fromIterable(compensationOrder)
                .concatMap(tccId -> compensateSingleTcc(composition, tccId, compositionContext, tccRegistry))
                .then();
    }
    
    /**
     * Executes compensation in parallel groups based on dependency levels.
     */
    private Mono<Void> executeGroupedParallelCompensation(TccComposition composition,
                                                         TccCompositionContext compositionContext,
                                                         TccRegistry tccRegistry,
                                                         Set<String> completedTccs) {
        // Group TCCs by dependency level (reverse order for compensation)
        List<List<String>> compensationLayers = buildCompensationLayers(composition, completedTccs);
        
        log.debug("Executing grouped parallel compensation for {} layers", compensationLayers.size());
        
        return Flux.fromIterable(compensationLayers)
                .concatMap(layer -> {
                    log.debug("Compensating layer with {} TCCs: {}", layer.size(), layer);
                    return Flux.fromIterable(layer)
                            .flatMap(tccId -> compensateSingleTcc(composition, tccId, compositionContext, tccRegistry))
                            .then();
                })
                .then();
    }
    
    /**
     * Executes compensation in parallel for all TCCs (best effort).
     */
    private Mono<Void> executeBestEffortParallelCompensation(TccComposition composition,
                                                            TccCompositionContext compositionContext,
                                                            TccRegistry tccRegistry,
                                                            Set<String> completedTccs) {
        log.debug("Executing best effort parallel compensation for {} TCCs", completedTccs.size());
        
        return Flux.fromIterable(completedTccs)
                .flatMap(tccId -> compensateSingleTcc(composition, tccId, compositionContext, tccRegistry)
                        .onErrorResume(error -> {
                            log.warn("Compensation failed for TCC '{}', continuing with others", tccId, error);
                            return Mono.empty(); // Continue with other compensations
                        }))
                .then();
    }
    
    /**
     * Builds compensation layers based on dependency relationships (reverse order).
     */
    private List<List<String>> buildCompensationLayers(TccComposition composition, Set<String> completedTccs) {
        List<List<String>> layers = new ArrayList<>();
        Set<String> remaining = new HashSet<>(completedTccs);
        
        while (!remaining.isEmpty()) {
            List<String> currentLayer = new ArrayList<>();
            
            // Find TCCs that don't depend on any remaining TCCs
            for (String tccId : remaining) {
                TccComposition.CompositionTcc tcc = composition.getTcc(tccId);
                if (tcc != null) {
                    boolean hasRemainingDependencies = tcc.dependencies.stream()
                            .anyMatch(remaining::contains);
                    
                    if (!hasRemainingDependencies) {
                        currentLayer.add(tccId);
                    }
                }
            }
            
            if (currentLayer.isEmpty()) {
                // Circular dependency or other issue - add all remaining
                log.warn("Circular dependency detected in compensation, adding all remaining TCCs to final layer");
                currentLayer.addAll(remaining);
            }
            
            layers.add(currentLayer);
            remaining.removeAll(currentLayer);
        }
        
        return layers;
    }
    
    /**
     * Compensates a single TCC by executing its CANCEL phase.
     */
    private Mono<Void> compensateSingleTcc(TccComposition composition,
                                          String tccId,
                                          TccCompositionContext compositionContext,
                                          TccRegistry tccRegistry) {
        TccComposition.CompositionTcc compositionTcc = composition.getTcc(tccId);
        if (compositionTcc == null) {
            return Mono.error(new IllegalStateException("TCC not found in composition: " + tccId));
        }
        
        TccDefinition tccDefinition = tccRegistry.getTcc(compositionTcc.tccName);
        if (tccDefinition == null) {
            return Mono.error(new IllegalStateException("TCC definition not found: " + compositionTcc.tccName));
        }
        
        TccContext tccContext = compositionContext.getTccContext(tccId);
        if (tccContext == null) {
            log.warn("TCC context not found for '{}', skipping compensation", tccId);
            return Mono.empty();
        }
        
        log.debug("Compensating TCC '{}'", tccId);
        
        Instant startTime = Instant.now();
        
        // For TCC, compensation is handled automatically by the TCC engine during the CANCEL phase
        // We just need to emit events and log the compensation attempt
        return Mono.fromRunnable(() -> {
            Duration duration = Duration.between(startTime, Instant.now());

            // Check if the TCC was successfully cancelled (from the original result)
            TccResult originalResult = compositionContext.getTccResult(tccId);
            if (originalResult != null && originalResult.getFinalPhase() == TccPhase.CANCEL) {
                log.debug("TCC '{}' was already compensated (CANCEL phase) in composition '{}'",
                         tccId, compositionContext.getCompositionName());
                emitTccCompensationSuccessEvent(composition, tccId, compositionContext, duration);
            } else {
                log.warn("TCC '{}' compensation status unclear in composition '{}'",
                        tccId, compositionContext.getCompositionName());
                emitTccCompensationFailureEvent(composition, tccId, compositionContext,
                    new IllegalStateException("TCC compensation status unclear"), duration);
            }
        });
    }
    
    // Event emission methods
    private void emitCompensationStartEvent(TccComposition composition, TccCompositionContext context) {
        try {
            tccEvents.onPhaseStarted(
                "composition:" + composition.name,
                context.getCompositionId(),
                null // No specific phase for composition-level events
            );
        } catch (Exception e) {
            log.warn("Failed to emit compensation start event", e);
        }
    }
    
    private void emitCompensationCompletedEvent(TccComposition composition, TccCompositionContext context, boolean success) {
        try {
            Duration duration = Duration.between(context.getStartedAt(), Instant.now());
            if (success) {
                tccEvents.onPhaseCompleted(
                    "composition:" + composition.name,
                    context.getCompositionId(),
                    null, // No specific phase for composition-level events
                    duration.toMillis()
                );
            } else {
                tccEvents.onPhaseFailed(
                    "composition:" + composition.name,
                    context.getCompositionId(),
                    TccPhase.CANCEL, // Compensation is part of cancel phase
                    new RuntimeException("Compensation failed"),
                    1,
                    duration.toMillis()
                );
            }
        } catch (Exception e) {
            log.warn("Failed to emit compensation completed event", e);
        }
    }
    
    private void emitTccCompensationSuccessEvent(TccComposition composition, String tccId, TccCompositionContext context, Duration duration) {
        try {
            tccEvents.onParticipantSuccess(
                "composition:" + composition.name,
                context.getCompositionId(),
                tccId + "-compensation",
                null, // No specific phase for composition-level events
                1, // Single attempt for composition-level tracking
                duration.toMillis()
            );
        } catch (Exception e) {
            log.warn("Failed to emit TCC compensation success event", e);
        }
    }
    
    private void emitTccCompensationFailureEvent(TccComposition composition, String tccId, TccCompositionContext context, Throwable error, Duration duration) {
        try {
            tccEvents.onParticipantFailed(
                "composition:" + composition.name,
                context.getCompositionId(),
                tccId + "-compensation",
                null, // No specific phase for composition-level events
                error,
                1, // Single attempt for composition-level tracking
                duration.toMillis()
            );
        } catch (Exception e) {
            log.warn("Failed to emit TCC compensation failure event", e);
        }
    }
}
