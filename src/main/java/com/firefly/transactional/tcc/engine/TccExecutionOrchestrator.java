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

package com.firefly.transactional.tcc.engine;


import com.firefly.transactional.tcc.events.TccEventEnvelope;
import com.firefly.transactional.tcc.events.TccEventPublisher;
import com.firefly.transactional.tcc.observability.TccEvents;
import com.firefly.transactional.tcc.persistence.TccPersistenceProvider;
import com.firefly.transactional.saga.persistence.serialization.SerializableSagaContext;
import com.firefly.transactional.tcc.core.TccContext;
import com.firefly.transactional.tcc.core.TccInputs;
import com.firefly.transactional.tcc.core.TccPhase;
import com.firefly.transactional.tcc.core.TccResult;
import com.firefly.transactional.tcc.persistence.TccExecutionState;
import com.firefly.transactional.tcc.persistence.TccExecutionStatus;
import com.firefly.transactional.tcc.persistence.TccParticipantStatus;
import com.firefly.transactional.tcc.registry.TccDefinition;
import com.firefly.transactional.tcc.registry.TccParticipantDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the execution of TCC transactions through the try-confirm-cancel phases.
 * <p>
 * This orchestrator is responsible for:
 * <ul>
 *   <li>Executing the try phase for all participants</li>
 *   <li>Deciding whether to confirm or cancel based on try results</li>
 *   <li>Executing the confirm or cancel phase</li>
 *   <li>Persisting state at each phase transition</li>
 *   <li>Emitting observability events</li>
 * </ul>
 */
public class TccExecutionOrchestrator {
    
    private static final Logger log = LoggerFactory.getLogger(TccExecutionOrchestrator.class);
    
    private final TccParticipantInvoker participantInvoker;
    private final TccEvents tccEvents;
    private final TccEventPublisher tccEventPublisher;
    private final TccPersistenceProvider persistenceProvider;
    private final boolean persistenceEnabled;
    
    /**
     * Creates a new TCC execution orchestrator.
     *
     * @param participantInvoker the participant invoker
     * @param tccEvents the TCC-specific events interface for observability
     * @param tccEventPublisher the TCC event publisher for external events
     * @param persistenceProvider the TCC persistence provider
     * @param persistenceEnabled whether persistence is enabled
     */
    public TccExecutionOrchestrator(TccParticipantInvoker participantInvoker,
                                   TccEvents tccEvents,
                                   TccEventPublisher tccEventPublisher,
                                   TccPersistenceProvider persistenceProvider,
                                   boolean persistenceEnabled) {
        this.participantInvoker = Objects.requireNonNull(participantInvoker, "participantInvoker");
        this.tccEvents = tccEvents; // Can be null
        this.tccEventPublisher = tccEventPublisher; // Can be null
        this.persistenceProvider = Objects.requireNonNull(persistenceProvider, "persistenceProvider");
        this.persistenceEnabled = persistenceEnabled;
    }
    
    /**
     * Orchestrates the execution of a TCC transaction.
     *
     * @param tccDef the TCC definition
     * @param inputs the participant inputs
     * @param context the TCC context
     * @return a Mono containing the TCC result
     */
    public Mono<TccResult> orchestrate(TccDefinition tccDef, TccInputs inputs, TccContext context) {
        Instant startedAt = Instant.now();

        // Initialize execution state
        ExecutionState state = new ExecutionState(tccDef, inputs, context, startedAt);

        // Emit TCC started events
        if (tccEvents != null) {
            tccEvents.onTccStarted(tccDef.name, context.correlationId(), context);
        }

        // Persist initial state if enabled
        Mono<Void> persistInitial = persistenceEnabled ?
                persistState(state, TccExecutionStatus.TRYING) :
                Mono.empty();

        // Execute try phase
        return persistInitial
                .then(executeTryPhase(state))
                .flatMap(trySuccess -> {
                    if (trySuccess) {
                        // All try operations succeeded - execute confirm phase
                        return executeConfirmPhase(state)
                                .then(Mono.fromCallable(() -> buildSuccessResult(state, startedAt)));
                    } else {
                        // One or more try operations failed - execute cancel phase
                        return executeCancelPhase(state)
                                .then(Mono.fromCallable(() -> buildFailureResult(state, startedAt)));
                    }
                })
                .doOnNext(result -> {
                    // Emit TCC completion events
                    if (tccEvents != null) {
                        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
                        TccPhase finalPhase = result.isSuccess() ? TccPhase.CONFIRM : TccPhase.CANCEL;
                        tccEvents.onTccCompleted(tccDef.name, context.correlationId(),
                                finalPhase, durationMs);
                    }
                })
                .onErrorResume(error -> {
                    log.error("TCC transaction '{}' failed with unexpected error", tccDef.name, error);

                    // Emit failure event
                    if (tccEvents != null) {
                        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
                        tccEvents.onTccCompleted(tccDef.name, context.correlationId(),
                                TccPhase.CANCEL, durationMs);
                    }

                    return executeCancelPhase(state)
                            .then(Mono.fromCallable(() -> buildErrorResult(state, startedAt, error)));
                });
    }
    
    /**
     * Executes the try phase for all participants.
     */
    private Mono<Boolean> executeTryPhase(ExecutionState state) {
        state.context.setCurrentPhase(TccPhase.TRY);

        log.info("Executing TRY phase for TCC '{}' with {} participant(s)",
                state.tccDef.name, state.tccDef.participants.size());

        // Emit phase started event
        if (tccEvents != null) {
            tccEvents.onPhaseStarted(state.tccDef.name, state.context.correlationId(), TccPhase.TRY);
        }

        // Get participants in execution order
        List<TccParticipantDefinition> participants = getParticipantsInOrder(state.tccDef);

        Instant phaseStarted = Instant.now();

        // Execute participants sequentially
        return Flux.fromIterable(participants)
                .concatMap(participant -> executeTryForParticipant(state, participant))
                .then(Mono.fromCallable(() -> !state.hasFailed()))
                .doOnNext(success -> {
                    // Emit phase completed event
                    if (tccEvents != null) {
                        long durationMs = Duration.between(phaseStarted, Instant.now()).toMillis();
                        if (success) {
                            tccEvents.onPhaseCompleted(state.tccDef.name, state.context.correlationId(),
                                    TccPhase.TRY, durationMs);
                        } else {
                            tccEvents.onPhaseFailed(state.tccDef.name, state.context.correlationId(),
                                    TccPhase.TRY, new RuntimeException("One or more participants failed"), 1, durationMs);
                        }
                    }
                });
    }
    
    /**
     * Executes the try method for a single participant.
     */
    private Mono<Void> executeTryForParticipant(ExecutionState state, TccParticipantDefinition participant) {
        if (state.hasFailed()) {
            // Skip if a previous participant failed
            state.markParticipantSkipped(participant.id);
            return Mono.empty();
        }
        
        String participantId = participant.id;
        Object input = state.inputs.getInput(participantId);
        
        log.debug("Executing TRY for participant '{}'", participantId);

        // Update status
        state.markParticipantTrying(participantId);

        // Emit TCC participant started event
        if (tccEvents != null) {
            tccEvents.onParticipantStarted(state.tccDef.name, state.context.correlationId(),
                    participantId, TccPhase.TRY);
        }

        Instant participantStarted = Instant.now();
        
        return participantInvoker.invokeTry(participant, input, state.context)
                .doOnNext(result -> {
                    // Store try result
                    state.context.putTryResult(participantId, result);
                    state.markParticipantTried(participantId, result);

                    log.debug("TRY succeeded for participant '{}'", participantId);

                    // Emit TCC participant success event
                    if (tccEvents != null) {
                        long durationMs = Duration.between(participantStarted, Instant.now()).toMillis();
                        tccEvents.onParticipantSuccess(state.tccDef.name, state.context.correlationId(),
                                participantId, TccPhase.TRY, 1, durationMs);
                    }

                    // Publish TCC event if configured
                    publishTccEventIfConfigured(participant, state, TccPhase.TRY, result, null,
                            participantStarted, Instant.now(), 1, true);
                })
                .then(persistStateIfEnabled(state, TccExecutionStatus.TRYING))
                .onErrorResume(error -> {
                    log.error("TRY failed for participant '{}'", participantId, error);
                    state.markParticipantTryFailed(participantId, error);

                    // Emit TCC participant failure event
                    if (tccEvents != null) {
                        long durationMs = Duration.between(participantStarted, Instant.now()).toMillis();
                        tccEvents.onParticipantFailed(state.tccDef.name, state.context.correlationId(),
                                participantId, TccPhase.TRY, error, 1, durationMs);
                    }

                    // Publish TCC event if configured
                    publishTccEventIfConfigured(participant, state, TccPhase.TRY, null, error,
                            participantStarted, Instant.now(), 1, false);

                    // Don't propagate error - just mark as failed
                    return Mono.empty();
                });
    }
    
    /**
     * Executes the confirm phase for all participants that succeeded in try.
     */
    private Mono<Void> executeConfirmPhase(ExecutionState state) {
        state.context.setCurrentPhase(TccPhase.CONFIRM);
        
        log.info("Executing CONFIRM phase for TCC '{}'", state.tccDef.name);
        
        // Persist phase transition
        Mono<Void> persistPhase = persistStateIfEnabled(state, TccExecutionStatus.CONFIRMING);
        
        // Get participants that succeeded in try
        List<TccParticipantDefinition> participants = getSuccessfulParticipants(state);
        
        // Execute confirm for each participant
        return persistPhase
                .then(Flux.fromIterable(participants)
                        .concatMap(participant -> executeConfirmForParticipant(state, participant))
                        .then())
                .then(persistStateIfEnabled(state, TccExecutionStatus.CONFIRMED))
                .then(markCompletedIfEnabled(state, true));
    }
    
    /**
     * Executes the confirm method for a single participant.
     */
    private Mono<Void> executeConfirmForParticipant(ExecutionState state, TccParticipantDefinition participant) {
        String participantId = participant.id;
        Object tryResult = state.context.getTryResult(participantId);

        log.debug("Executing CONFIRM for participant '{}'", participantId);

        state.markParticipantConfirming(participantId);

        Instant startedAt = Instant.now();
        tccEvents.onParticipantStarted(state.tccDef.name, state.context.correlationId(), participantId, TccPhase.CONFIRM);

        return participantInvoker.invokeConfirm(participant, tryResult, state.context)
                .doOnSuccess(v -> {
                    Instant completedAt = Instant.now();
                    state.markParticipantConfirmed(participantId);
                    log.debug("CONFIRM succeeded for participant '{}'", participantId);
                    tccEvents.onParticipantSuccess(state.tccDef.name, state.context.correlationId(), participantId, TccPhase.CONFIRM, 1, Duration.between(startedAt, completedAt).toMillis());

                    // Publish to custom TccEventPublisher if configured
                    publishTccEventIfConfigured(participant, state, TccPhase.CONFIRM, v, null, startedAt, completedAt, 1, true);
                })
                .onErrorResume(error -> {
                    Instant completedAt = Instant.now();
                    log.error("CONFIRM failed for participant '{}' - this may require manual intervention",
                            participantId, error);
                    state.markParticipantConfirmFailed(participantId, error);
                    tccEvents.onParticipantFailed(state.tccDef.name, state.context.correlationId(), participantId, TccPhase.CONFIRM, error, 1, Duration.between(startedAt, completedAt).toMillis());

                    // Publish to custom TccEventPublisher if configured
                    publishTccEventIfConfigured(participant, state, TccPhase.CONFIRM, null, error, startedAt, completedAt, 1, false);

                    // Continue with other participants even if one fails
                    return Mono.empty();
                });
    }
    
    /**
     * Executes the cancel phase for all participants that succeeded in try.
     */
    private Mono<Void> executeCancelPhase(ExecutionState state) {
        state.context.setCurrentPhase(TccPhase.CANCEL);
        
        log.info("Executing CANCEL phase for TCC '{}'", state.tccDef.name);
        
        // Persist phase transition
        Mono<Void> persistPhase = persistStateIfEnabled(state, TccExecutionStatus.CANCELING);
        
        // Get participants that succeeded in try (in reverse order)
        List<TccParticipantDefinition> participants = getSuccessfulParticipants(state);
        Collections.reverse(participants); // Cancel in reverse order
        
        // Execute cancel for each participant
        return persistPhase
                .then(Flux.fromIterable(participants)
                        .concatMap(participant -> executeCancelForParticipant(state, participant))
                        .then())
                .then(persistStateIfEnabled(state, TccExecutionStatus.CANCELED))
                .then(markCompletedIfEnabled(state, false));
    }
    
    /**
     * Executes the cancel method for a single participant.
     */
    private Mono<Void> executeCancelForParticipant(ExecutionState state, TccParticipantDefinition participant) {
        String participantId = participant.id;
        Object tryResult = state.context.getTryResult(participantId);

        log.debug("Executing CANCEL for participant '{}'", participantId);

        state.markParticipantCanceling(participantId);

        Instant startedAt = Instant.now();
        tccEvents.onParticipantStarted(state.tccDef.name, state.context.correlationId(), participantId, TccPhase.CANCEL);

        return participantInvoker.invokeCancel(participant, tryResult, state.context)
                .doOnSuccess(v -> {
                    Instant completedAt = Instant.now();
                    state.markParticipantCanceled(participantId);
                    log.debug("CANCEL succeeded for participant '{}'", participantId);
                    tccEvents.onParticipantSuccess(state.tccDef.name, state.context.correlationId(), participantId, TccPhase.CANCEL, 1, Duration.between(startedAt, completedAt).toMillis());

                    // Publish to custom TccEventPublisher if configured
                    publishTccEventIfConfigured(participant, state, TccPhase.CANCEL, v, null, startedAt, completedAt, 1, true);
                })
                .onErrorResume(error -> {
                    Instant completedAt = Instant.now();
                    log.error("CANCEL failed for participant '{}' - this may require manual intervention",
                            participantId, error);
                    state.markParticipantCancelFailed(participantId, error);
                    tccEvents.onParticipantFailed(state.tccDef.name, state.context.correlationId(), participantId, TccPhase.CANCEL, error, 1, Duration.between(startedAt, completedAt).toMillis());

                    // Publish to custom TccEventPublisher if configured
                    publishTccEventIfConfigured(participant, state, TccPhase.CANCEL, null, error, startedAt, completedAt, 1, false);

                    // Continue with other participants even if one fails
                    return Mono.empty();
                });
    }
    
    /**
     * Gets participants in execution order.
     */
    private List<TccParticipantDefinition> getParticipantsInOrder(TccDefinition tccDef) {
        return tccDef.participants.values().stream()
                .sorted(Comparator.comparingInt(p -> p.order))
                .toList();
    }
    
    /**
     * Gets participants that succeeded in the try phase.
     */
    private List<TccParticipantDefinition> getSuccessfulParticipants(ExecutionState state) {
        return state.tccDef.participants.values().stream()
                .filter(p -> state.participantStatuses.get(p.id) == TccParticipantStatus.TRIED)
                .sorted(Comparator.comparingInt(p -> p.order))
                .toList();
    }
    
    /**
     * Persists the current state if persistence is enabled.
     */
    private Mono<Void> persistStateIfEnabled(ExecutionState state, TccExecutionStatus status) {
        if (!persistenceEnabled) {
            return Mono.empty();
        }
        return persistState(state, status);
    }
    
    /**
     * Persists the current state.
     */
    private Mono<Void> persistState(ExecutionState state, TccExecutionStatus status) {
        TccExecutionState tccState = TccExecutionState.builder()
                .correlationId(state.context.correlationId())
                .tccName(state.tccDef.name)
                .status(status)
                .currentPhase(state.context.getCurrentPhase())
                .startedAt(state.startedAt)
                .lastUpdatedAt(Instant.now())
                .context(SerializableSagaContext.fromSagaContext(state.context.getSagaContext()))
                .participantInputs(state.inputs.getAllInputs())
                .tryResults(state.context.getAllTryResults())
                .participantStatuses(new HashMap<>(state.participantStatuses))
                .failureReason(state.failureReason)
                .failedParticipantId(state.failedParticipantId)
                .build();
        
        return persistenceProvider.saveState(tccState)
                .doOnError(error -> log.warn("Failed to persist TCC state: {}", 
                        state.context.correlationId(), error))
                .onErrorResume(error -> Mono.empty()); // Don't fail execution on persistence errors
    }
    
    /**
     * Marks the TCC as completed if persistence is enabled.
     */
    private Mono<Void> markCompletedIfEnabled(ExecutionState state, boolean successful) {
        if (!persistenceEnabled) {
            return Mono.empty();
        }
        return persistenceProvider.deleteState(state.context.correlationId())
                .doOnError(error -> log.warn("Failed to mark TCC as completed: {}", 
                        state.context.correlationId(), error))
                .onErrorResume(error -> Mono.empty());
    }
    
    /**
     * Builds a success result.
     */
    private TccResult buildSuccessResult(ExecutionState state, Instant startedAt) {
        return TccResult.builder(state.context.correlationId())
                .tccName(state.tccDef.name)
                .success(true)
                .finalPhase(TccPhase.CONFIRM)
                .tryResults(state.context.getAllTryResults())
                .participantResults(buildParticipantResults(state))
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .build();
    }
    
    /**
     * Builds a failure result.
     */
    private TccResult buildFailureResult(ExecutionState state, Instant startedAt) {
        return TccResult.builder(state.context.correlationId())
                .tccName(state.tccDef.name)
                .success(false)
                .finalPhase(TccPhase.CANCEL)
                .tryResults(state.context.getAllTryResults())
                .participantResults(buildParticipantResults(state))
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .failedParticipantId(state.failedParticipantId)
                .error(state.errors.isEmpty() ? null : state.errors.values().iterator().next())
                .build();
    }
    
    /**
     * Builds an error result.
     */
    private TccResult buildErrorResult(ExecutionState state, Instant startedAt, Throwable error) {
        return TccResult.builder(state.context.correlationId())
                .tccName(state.tccDef.name)
                .success(false)
                .finalPhase(TccPhase.CANCEL)
                .tryResults(state.context.getAllTryResults())
                .participantResults(buildParticipantResults(state))
                .startedAt(startedAt)
                .completedAt(Instant.now())
                .error(error)
                .build();
    }
    
    /**
     * Builds participant results from execution state.
     */
    private Map<String, TccResult.ParticipantResult> buildParticipantResults(ExecutionState state) {
        Map<String, TccResult.ParticipantResult> results = new HashMap<>();

        for (Map.Entry<String, TccParticipantStatus> entry : state.participantStatuses.entrySet()) {
            String participantId = entry.getKey();
            TccParticipantStatus status = entry.getValue();

            Object tryResult = state.context.getTryResult(participantId);
            boolean trySucceeded = status.isTrySucceeded();
            boolean confirmSucceeded = status == TccParticipantStatus.CONFIRMED;
            boolean cancelSucceeded = status == TccParticipantStatus.CANCELED;
            Throwable error = state.errors.get(participantId);

            results.put(participantId, new TccResult.ParticipantResult(
                    participantId, tryResult, trySucceeded, confirmSucceeded, cancelSucceeded, error
            ));
        }

        return results;
    }
    
    /**
     * Internal execution state tracking.
     */
    private static class ExecutionState {
        final TccDefinition tccDef;
        final TccInputs inputs;
        final TccContext context;
        final Instant startedAt;
        final Map<String, TccParticipantStatus> participantStatuses = new ConcurrentHashMap<>();
        final Map<String, Throwable> errors = new ConcurrentHashMap<>();
        String failureReason;
        String failedParticipantId;
        
        ExecutionState(TccDefinition tccDef, TccInputs inputs, TccContext context, Instant startedAt) {
            this.tccDef = tccDef;
            this.inputs = inputs;
            this.context = context;
            this.startedAt = startedAt;
            
            // Initialize all participants as pending
            tccDef.participants.keySet().forEach(id -> 
                    participantStatuses.put(id, TccParticipantStatus.PENDING));
        }
        
        boolean hasFailed() {
            return failedParticipantId != null;
        }
        
        void markParticipantTrying(String participantId) {
            participantStatuses.put(participantId, TccParticipantStatus.TRYING);
        }
        
        void markParticipantTried(String participantId, Object result) {
            participantStatuses.put(participantId, TccParticipantStatus.TRIED);
        }
        
        void markParticipantTryFailed(String participantId, Throwable error) {
            participantStatuses.put(participantId, TccParticipantStatus.TRY_FAILED);
            errors.put(participantId, error);
            if (failedParticipantId == null) {
                failedParticipantId = participantId;
                failureReason = error.getMessage();
            }
        }
        
        void markParticipantConfirming(String participantId) {
            participantStatuses.put(participantId, TccParticipantStatus.CONFIRMING);
        }
        
        void markParticipantConfirmed(String participantId) {
            participantStatuses.put(participantId, TccParticipantStatus.CONFIRMED);
        }
        
        void markParticipantConfirmFailed(String participantId, Throwable error) {
            participantStatuses.put(participantId, TccParticipantStatus.CONFIRM_FAILED);
            errors.put(participantId, error);
        }
        
        void markParticipantCanceling(String participantId) {
            participantStatuses.put(participantId, TccParticipantStatus.CANCELING);
        }
        
        void markParticipantCanceled(String participantId) {
            participantStatuses.put(participantId, TccParticipantStatus.CANCELED);
        }
        
        void markParticipantCancelFailed(String participantId, Throwable error) {
            participantStatuses.put(participantId, TccParticipantStatus.CANCEL_FAILED);
            errors.put(participantId, error);
        }
        
        void markParticipantSkipped(String participantId) {
            participantStatuses.put(participantId, TccParticipantStatus.SKIPPED);
        }
    }

    /**
     * Publishes a TCC event if the participant is configured for event publishing.
     */
    private void publishTccEventIfConfigured(TccParticipantDefinition participant, ExecutionState state,
                                           TccPhase phase, Object payload, Throwable error,
                                           Instant startedAt, Instant completedAt, int attempts, boolean success) {
        if (participant.tccEvent == null || tccEventPublisher == null) {
            return;
        }

        TccEventEnvelope envelope = TccEventEnvelope.builder()
                .tccName(state.tccDef.name)
                .correlationId(state.context.correlationId())
                .participantId(participant.id)
                .topic(participant.tccEvent.topic)
                .type(participant.tccEvent.eventType)
                .key(participant.tccEvent.key)
                .payload(payload)
                .headers(state.context.getHeaders())
                .timestamp(completedAt)
                .phase(phase)
                .attempts(attempts)
                .durationMs(Duration.between(startedAt, completedAt).toMillis())
                .startedAt(startedAt)
                .completedAt(completedAt)
                .error(error)
                .success(success)
                .build();

        tccEventPublisher.publish(envelope).subscribe();
    }
}

