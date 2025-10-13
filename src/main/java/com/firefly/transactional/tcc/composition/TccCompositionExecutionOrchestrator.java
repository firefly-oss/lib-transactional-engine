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
import com.firefly.transactional.tcc.core.TccPhase;
import com.firefly.transactional.tcc.core.TccResult;
import com.firefly.transactional.tcc.engine.TccEngine;
import com.firefly.transactional.tcc.observability.TccEvents;
import com.firefly.transactional.tcc.registry.TccDefinition;
import com.firefly.transactional.tcc.registry.TccRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the execution of TCC compositions with proper three-phase protocol management.
 * <p>
 * This orchestrator manages the complex execution flow of TCC compositions, handling:
 * - Dependency resolution and execution ordering
 * - Parallel execution of independent TCCs
 * - Cross-TCC data flow and context sharing
 * - Three-phase protocol coordination (TRY, CONFIRM, CANCEL)
 * - Comprehensive error handling and compensation
 * - Event emission for observability
 */
public class TccCompositionExecutionOrchestrator {
    
    private static final Logger log = LoggerFactory.getLogger(TccCompositionExecutionOrchestrator.class);
    private static final int MAX_EXECUTION_DEPTH = 50;
    
    private final TccEngine tccEngine;
    private final TccEvents tccEvents;
    private final TccCompositionDataFlowManager dataFlowManager;
    private final TccCompositionCompensationManager compensationManager;
    
    /**
     * Creates a new TCC composition execution orchestrator.
     * 
     * @param tccEngine the TCC engine for executing individual TCCs
     * @param tccEvents the events system for observability
     */
    public TccCompositionExecutionOrchestrator(TccEngine tccEngine, TccEvents tccEvents) {
        this.tccEngine = Objects.requireNonNull(tccEngine, "tccEngine cannot be null");
        this.tccEvents = Objects.requireNonNull(tccEvents, "tccEvents cannot be null");
        this.dataFlowManager = new TccCompositionDataFlowManager();
        this.compensationManager = new TccCompositionCompensationManager(tccEngine, tccEvents);
    }
    
    /**
     * Orchestrates the execution of a TCC composition.
     * 
     * @param composition the composition to execute
     * @param rootContext the root TCC context
     * @param tccRegistry the TCC registry for resolving TCC definitions
     * @return a Mono containing the composition execution result
     */
    public Mono<TccCompositionResult> orchestrate(TccComposition composition, 
                                                  TccContext rootContext,
                                                  TccRegistry tccRegistry) {
        Objects.requireNonNull(composition, "composition cannot be null");
        Objects.requireNonNull(rootContext, "rootContext cannot be null");
        Objects.requireNonNull(tccRegistry, "tccRegistry cannot be null");
        
        TccCompositionContext compositionContext = new TccCompositionContext(composition.name, rootContext);
        
        // Emit composition start event
        emitCompositionStartEvent(composition, compositionContext);
        
        return executeCompositionLayers(composition, compositionContext, tccRegistry)
                .then(Mono.fromCallable(() -> {
                    boolean success = determineOverallSuccess(composition, compositionContext);
                    Throwable compositionError = success ? null : 
                        new RuntimeException("Composition failed - see individual TCC errors");
                    
                    // Emit completion event
                    emitCompositionCompletedEvent(composition, compositionContext, success);
                    
                    return TccCompositionResult.from(compositionContext, success, compositionError);
                }))
                .onErrorResume(error -> {
                    log.error("Composition '{}' failed with error", composition.name, error);
                    
                    // Emit error event
                    emitCompositionErrorEvent(composition, compositionContext, error);
                    
                    return compensationManager.compensateComposition(composition, compositionContext, tccRegistry)
                            .then(Mono.fromCallable(() ->
                                TccCompositionResult.from(compositionContext, false, error)));
                });
    }
    
    /**
     * Executes the composition by processing TCCs in dependency-ordered layers.
     */
    private Mono<Void> executeCompositionLayers(TccComposition composition,
                                               TccCompositionContext compositionContext,
                                               TccRegistry tccRegistry) {
        return executeCompositionLayersWithDepthLimit(composition, compositionContext, tccRegistry, 0);
    }
    
    /**
     * Executes composition layers with a depth limit to prevent infinite loops.
     */
    private Mono<Void> executeCompositionLayersWithDepthLimit(TccComposition composition,
                                                             TccCompositionContext compositionContext,
                                                             TccRegistry tccRegistry,
                                                             int depth) {
        if (depth > MAX_EXECUTION_DEPTH) {
            return Mono.error(new RuntimeException("Maximum execution depth exceeded - possible circular dependency"));
        }

        return Mono.defer(() -> {
            Set<String> completedAndFailedTccs = new HashSet<>(compositionContext.getCompletedTccs());
            completedAndFailedTccs.addAll(compositionContext.getFailedTccs());
            completedAndFailedTccs.addAll(compositionContext.getSkippedTccs());

            List<String> executableTccs = composition.getExecutableParallelTccs(completedAndFailedTccs);

            if (executableTccs.isEmpty()) {
                if (composition.hasRemainingWork(completedAndFailedTccs)) {
                    // Check if we're stuck due to failed dependencies
                    return Mono.error(new RuntimeException(
                        "Composition execution stuck - remaining TCCs have unsatisfied dependencies"));
                }
                return Mono.empty(); // All work completed
            }

            log.debug("Executing TCC layer with {} TCCs: {}", executableTccs.size(), executableTccs);

            // Execute all TCCs in this layer in parallel
            return Flux.fromIterable(executableTccs)
                    .flatMap(tccId -> executeSingleTcc(composition, tccId, compositionContext, tccRegistry))
                    .then()
                    .then(executeCompositionLayersWithDepthLimit(composition, compositionContext, tccRegistry, depth + 1));
        });
    }
    
    /**
     * Executes a single TCC within the composition.
     */
    private Mono<Void> executeSingleTcc(TccComposition composition,
                                       String tccId,
                                       TccCompositionContext compositionContext,
                                       TccRegistry tccRegistry) {
        TccComposition.CompositionTcc compositionTcc = composition.getTcc(tccId);
        if (compositionTcc == null) {
            return Mono.error(new IllegalStateException("TCC not found in composition: " + tccId));
        }
        
        // Check execution condition
        if (!compositionTcc.executionCondition.apply(compositionContext)) {
            log.debug("Skipping TCC '{}' due to execution condition", tccId);
            compositionContext.recordTccSkipped(tccId, "Execution condition not met");
            return Mono.empty();
        }
        
        // Resolve TCC definition
        TccDefinition tccDefinition = tccRegistry.getTcc(compositionTcc.tccName);
        if (tccDefinition == null) {
            return Mono.error(new IllegalStateException("TCC definition not found: " + compositionTcc.tccName));
        }
        
        // Prepare TCC inputs and context with data flow
        return Mono.zip(
                dataFlowManager.prepareTccInputs(compositionTcc, compositionContext),
                dataFlowManager.prepareTccContext(compositionTcc, compositionContext)
        ).flatMap(tuple -> {
            TccInputs tccInputs = tuple.getT1();
            TccContext tccContext = tuple.getT2();

            compositionContext.markTccStarted(tccId);
                    
                    // Emit TCC start event
                    emitTccStartEvent(composition, tccId, compositionContext);
                    
                    Instant startTime = Instant.now();
                    
                    // Execute the TCC
                    Mono<TccResult> execution = tccEngine.execute(tccDefinition.name, tccInputs, tccContext);
                    
                    // Apply timeout if configured
                    if (compositionTcc.timeoutMs > 0) {
                        execution = execution.timeout(Duration.ofMillis(compositionTcc.timeoutMs));
                    }
                    
                    return execution
                            .doOnSuccess(result -> {
                                Duration duration = Duration.between(startTime, Instant.now());
                                compositionContext.recordTccResult(tccId, result, tccContext);
                                
                                if (result.isSuccess()) {
                                    emitTccSuccessEvent(composition, tccId, compositionContext, duration);
                                } else {
                                    emitTccFailureEvent(composition, tccId, compositionContext, result.getError(), duration);
                                }
                            })
                            .doOnError(error -> {
                                Duration duration = Duration.between(startTime, Instant.now());
                                
                                if (compositionTcc.optional) {
                                    log.warn("Optional TCC '{}' failed, continuing composition", tccId, error);
                                    compositionContext.recordTccSkipped(tccId, "Optional TCC failed: " + error.getMessage());
                                    emitTccFailureEvent(composition, tccId, compositionContext, error, duration);
                                } else {
                                    log.error("TCC '{}' failed", tccId, error);
                                    // Create a failed result
                                    TccResult failedResult = TccResult.builder(tccContext.correlationId())
                                            .tccName(tccDefinition.name)
                                            .success(false)
                                            .finalPhase(TccPhase.CANCEL)
                                            .error(error)
                                            .build();
                                    compositionContext.recordTccResult(tccId, failedResult, tccContext);
                                    emitTccFailureEvent(composition, tccId, compositionContext, error, duration);
                                }
                            })
                            .onErrorResume(error -> {
                                if (compositionTcc.optional) {
                                    return Mono.empty(); // Continue execution for optional TCCs
                                }
                                return Mono.error(error); // Propagate error for required TCCs
                            })
                            .then();
        });
    }
    
    /**
     * Determines the overall success of the composition based on individual TCC results.
     */
    private boolean determineOverallSuccess(TccComposition composition, TccCompositionContext compositionContext) {
        Set<String> allTccIds = composition.getAllTccIds();
        Set<String> completedTccs = compositionContext.getCompletedTccs();
        Set<String> failedTccs = compositionContext.getFailedTccs();
        Set<String> skippedTccs = compositionContext.getSkippedTccs();
        
        // Check if all required TCCs completed successfully
        for (String tccId : allTccIds) {
            TccComposition.CompositionTcc tcc = composition.getTcc(tccId);
            if (tcc != null && !tcc.optional) {
                if (!completedTccs.contains(tccId)) {
                    return false; // Required TCC didn't complete successfully
                }
            }
        }
        
        return true;
    }
    
    // Event emission methods
    private void emitCompositionStartEvent(TccComposition composition, TccCompositionContext context) {
        try {
            tccEvents.onTccStarted(
                "composition:" + composition.name,
                context.getCompositionId(),
                context.getRootContext()
            );
        } catch (Exception e) {
            log.warn("Failed to emit composition start event", e);
        }
    }
    
    private void emitCompositionCompletedEvent(TccComposition composition, TccCompositionContext context, boolean success) {
        try {
            Duration duration = Duration.between(context.getStartedAt(), Instant.now());
            tccEvents.onTccCompleted(
                "composition:" + composition.name,
                context.getCompositionId(),
                success ? TccPhase.CONFIRM : TccPhase.CANCEL,
                duration.toMillis()
            );
        } catch (Exception e) {
            log.warn("Failed to emit composition completed event", e);
        }
    }
    
    private void emitCompositionErrorEvent(TccComposition composition, TccCompositionContext context, Throwable error) {
        try {
            Duration duration = Duration.between(context.getStartedAt(), Instant.now());
            tccEvents.onPhaseFailed(
                "composition:" + composition.name,
                context.getCompositionId(),
                TccPhase.TRY, // Default to TRY phase for compositions
                error,
                1,
                duration.toMillis()
            );
        } catch (Exception e) {
            log.warn("Failed to emit composition error event", e);
        }
    }
    
    private void emitTccStartEvent(TccComposition composition, String tccId, TccCompositionContext context) {
        try {
            tccEvents.onParticipantStarted(
                "composition:" + composition.name,
                context.getCompositionId(),
                tccId,
                null // No specific phase for composition-level events
            );
        } catch (Exception e) {
            log.warn("Failed to emit TCC start event", e);
        }
    }
    
    private void emitTccSuccessEvent(TccComposition composition, String tccId, TccCompositionContext context, Duration duration) {
        try {
            tccEvents.onParticipantSuccess(
                "composition:" + composition.name,
                context.getCompositionId(),
                tccId,
                null, // No specific phase for composition-level events
                1, // Single attempt for composition-level tracking
                duration.toMillis()
            );
        } catch (Exception e) {
            log.warn("Failed to emit TCC success event", e);
        }
    }
    
    private void emitTccFailureEvent(TccComposition composition, String tccId, TccCompositionContext context, Throwable error, Duration duration) {
        try {
            tccEvents.onParticipantFailed(
                "composition:" + composition.name,
                context.getCompositionId(),
                tccId,
                null, // No specific phase for composition-level events
                error,
                1, // Single attempt for composition-level tracking
                duration.toMillis()
            );
        } catch (Exception e) {
            log.warn("Failed to emit TCC failure event", e);
        }
    }
}
