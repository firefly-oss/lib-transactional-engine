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

package com.firefly.transactional.tcc.persistence;

import com.firefly.transactional.shared.core.StepStatus;
import com.firefly.transactional.saga.persistence.SagaExecutionState;
import com.firefly.transactional.saga.persistence.SagaExecutionStatus;
import com.firefly.transactional.saga.persistence.SagaPersistenceProvider;
import com.firefly.transactional.saga.persistence.StepExecutionStatus;
import com.firefly.transactional.tcc.core.TccPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Adapter that allows TCC execution states to be persisted using the existing
 * SAGA persistence infrastructure.
 * <p>
 * This adapter converts between TCC-specific state models and SAGA state models,
 * allowing TCC transactions to leverage the same Redis/in-memory persistence
 * providers without modification.
 * <p>
 * The adapter uses a different key prefix ("tcc:" instead of "saga:") to keep
 * TCC and SAGA states separate in the persistence layer.
 */
public class TccPersistenceAdapter {
    
    private static final Logger log = LoggerFactory.getLogger(TccPersistenceAdapter.class);
    
    private final SagaPersistenceProvider persistenceProvider;
    
    /**
     * Creates a new TCC persistence adapter.
     *
     * @param persistenceProvider the underlying SAGA persistence provider
     */
    public TccPersistenceAdapter(SagaPersistenceProvider persistenceProvider) {
        this.persistenceProvider = Objects.requireNonNull(persistenceProvider, "persistenceProvider");
    }
    
    /**
     * Persists a TCC execution state.
     *
     * @param tccState the TCC state to persist
     * @return a Mono that completes when the state is persisted
     */
    public Mono<Void> persistTccState(TccExecutionState tccState) {
        SagaExecutionState sagaState = convertToSagaState(tccState);
        return persistenceProvider.persistSagaState(sagaState)
                .doOnSuccess(v -> log.debug("Persisted TCC state for correlation ID: {}", tccState.getCorrelationId()))
                .doOnError(error -> log.error("Failed to persist TCC state for correlation ID: {}", 
                        tccState.getCorrelationId(), error));
    }
    
    /**
     * Retrieves a TCC execution state.
     *
     * @param correlationId the correlation ID
     * @return a Mono containing the TCC state if found
     */
    public Mono<Optional<TccExecutionState>> getTccState(String correlationId) {
        return persistenceProvider.getSagaState(correlationId)
                .map(optionalSagaState -> optionalSagaState.map(this::convertToTccState))
                .doOnNext(result -> {
                    if (result.isPresent()) {
                        log.debug("Retrieved TCC state for correlation ID: {}", correlationId);
                    } else {
                        log.debug("No TCC state found for correlation ID: {}", correlationId);
                    }
                });
    }
    
    /**
     * Updates the status of a participant.
     *
     * @param correlationId the correlation ID
     * @param participantId the participant ID
     * @param status the new status
     * @return a Mono that completes when the status is updated
     */
    public Mono<Void> updateParticipantStatus(String correlationId, String participantId, 
                                              TccParticipantStatus status) {
        return getTccState(correlationId)
                .flatMap(optionalState -> {
                    if (optionalState.isPresent()) {
                        TccExecutionState currentState = optionalState.get();
                        TccExecutionState updatedState = currentState.withParticipantStatus(participantId, status);
                        return persistTccState(updatedState);
                    } else {
                        log.warn("Attempted to update participant status for non-existent TCC: {}", correlationId);
                        return Mono.empty();
                    }
                });
    }
    
    /**
     * Marks a TCC transaction as completed.
     *
     * @param correlationId the correlation ID
     * @param successful whether the transaction was successful
     * @return a Mono that completes when the transaction is marked as completed
     */
    public Mono<Void> markTccCompleted(String correlationId, boolean successful) {
        return persistenceProvider.markSagaCompleted(correlationId, successful);
    }
    
    /**
     * Retrieves all in-flight TCC transactions.
     *
     * @return a Flux of in-flight TCC states
     */
    public Flux<TccExecutionState> getInFlightTccs() {
        return persistenceProvider.getInFlightSagas()
                .filter(sagaState -> sagaState.getSagaName() != null && 
                        sagaState.getSagaName().startsWith("tcc:"))
                .map(this::convertToTccState);
    }
    
    /**
     * Cleans up completed TCC transactions older than the specified duration.
     *
     * @param olderThan the age threshold
     * @return a Mono containing the number of cleaned up transactions
     */
    public Mono<Long> cleanupCompletedTccs(Duration olderThan) {
        return persistenceProvider.cleanupCompletedSagas(olderThan);
    }
    
    /**
     * Checks if the persistence provider is healthy.
     *
     * @return a Mono containing true if healthy
     */
    public Mono<Boolean> isHealthy() {
        return persistenceProvider.isHealthy();
    }
    
    /**
     * Converts a TCC execution state to a SAGA execution state for persistence.
     */
    private SagaExecutionState convertToSagaState(TccExecutionState tccState) {
        // Convert TCC status to SAGA status
        SagaExecutionStatus sagaStatus = convertTccStatusToSagaStatus(tccState.getStatus());
        
        // Convert participant statuses to step statuses
        Map<String, StepExecutionStatus> stepStatuses = tccState.getParticipantStatuses().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> convertParticipantStatusToStepStatus(entry.getValue())
                ));
        
        // Build SAGA execution state using constructor
        return new SagaExecutionState(
                tccState.getCorrelationId(),
                "tcc:" + tccState.getTccName(), // Prefix to distinguish TCC from SAGA
                sagaStatus,
                tccState.getStartedAt(),
                tccState.getLastUpdatedAt(),
                tccState.getContext(),
                tccState.getParticipantInputs(),
                new ArrayList<>(tccState.getTryResults().keySet()),
                null, // currentLayer - not used in TCC
                0, // currentLayerIndex - not used in TCC
                List.of(), // topologyLayers - not used in TCC
                stepStatuses,
                tccState.getFailureReason(),
                tccState.getStatus() == TccExecutionStatus.CANCELING ||
                        tccState.getStatus() == TccExecutionStatus.CANCELED
        );
    }
    
    /**
     * Converts a SAGA execution state back to a TCC execution state.
     */
    private TccExecutionState convertToTccState(SagaExecutionState sagaState) {
        // Extract TCC name (remove "tcc:" prefix)
        String tccName = sagaState.getSagaName();
        if (tccName.startsWith("tcc:")) {
            tccName = tccName.substring(4);
        }
        
        // Convert SAGA status to TCC status
        TccExecutionStatus tccStatus = convertSagaStatusToTccStatus(sagaState.getStatus());
        
        // Determine current phase from status
        TccPhase currentPhase = determinePhaseFromStatus(tccStatus);
        
        // Convert step statuses to participant statuses
        Map<String, TccParticipantStatus> participantStatuses = sagaState.getStepStatuses().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> convertStepStatusToParticipantStatus(entry.getValue())
                ));
        
        // Build TCC execution state
        return TccExecutionState.builder()
                .correlationId(sagaState.getCorrelationId())
                .tccName(tccName)
                .status(tccStatus)
                .currentPhase(currentPhase)
                .startedAt(sagaState.getStartedAt())
                .lastUpdatedAt(sagaState.getLastUpdatedAt())
                .context(sagaState.getContext())
                .participantInputs(sagaState.getStepInputs())
                .tryResults(extractTryResults(sagaState))
                .participantStatuses(participantStatuses)
                .failureReason(sagaState.getFailureReason())
                .build();
    }
    
    private SagaExecutionStatus convertTccStatusToSagaStatus(TccExecutionStatus tccStatus) {
        return switch (tccStatus) {
            case INITIALIZED -> SagaExecutionStatus.RUNNING;
            case RUNNING -> SagaExecutionStatus.RUNNING;
            case TRYING -> SagaExecutionStatus.RUNNING;
            case CONFIRMING -> SagaExecutionStatus.RUNNING;
            case CONFIRMED -> SagaExecutionStatus.COMPLETED_SUCCESS;
            case COMPLETED -> SagaExecutionStatus.COMPLETED_SUCCESS;
            case CANCELING -> SagaExecutionStatus.COMPENSATING;
            case CANCELED -> SagaExecutionStatus.COMPLETED_COMPENSATED;
            case FAILED -> SagaExecutionStatus.FAILED;
        };
    }
    
    private TccExecutionStatus convertSagaStatusToTccStatus(SagaExecutionStatus sagaStatus) {
        return switch (sagaStatus) {
            case RUNNING -> TccExecutionStatus.TRYING;
            case COMPLETED_SUCCESS -> TccExecutionStatus.CONFIRMED;
            case FAILED, CANCELLED -> TccExecutionStatus.FAILED;
            case COMPENSATING -> TccExecutionStatus.CANCELING;
            case COMPLETED_COMPENSATED -> TccExecutionStatus.CANCELED;
            case PAUSED -> TccExecutionStatus.TRYING; // Map paused to trying
            default -> TccExecutionStatus.INITIALIZED;
        };
    }
    
    private StepExecutionStatus convertParticipantStatusToStepStatus(TccParticipantStatus participantStatus) {
        StepStatus stepStatus = switch (participantStatus) {
            case PENDING -> StepStatus.PENDING;
            case TRYING, CONFIRMING, CANCELING -> StepStatus.RUNNING;
            case TRIED, CONFIRMED -> StepStatus.DONE;
            case TRY_FAILED, CONFIRM_FAILED, CANCEL_FAILED -> StepStatus.FAILED;
            case CANCELED -> StepStatus.COMPENSATED;
            case SKIPPED -> StepStatus.PENDING; // Map skipped to pending
        };
        return StepExecutionStatus.fromStepStatus(stepStatus);
    }

    private TccParticipantStatus convertStepStatusToParticipantStatus(StepExecutionStatus stepStatus) {
        return switch (stepStatus.getStatus()) {
            case PENDING -> TccParticipantStatus.PENDING;
            case RUNNING -> TccParticipantStatus.TRYING;
            case DONE -> TccParticipantStatus.TRIED;
            case FAILED -> TccParticipantStatus.TRY_FAILED;
            case COMPENSATED -> TccParticipantStatus.CANCELED;
        };
    }
    
    private TccPhase determinePhaseFromStatus(TccExecutionStatus status) {
        return switch (status) {
            case INITIALIZED, RUNNING, TRYING -> TccPhase.TRY;
            case CONFIRMING, CONFIRMED, COMPLETED -> TccPhase.CONFIRM;
            case CANCELING, CANCELED, FAILED -> TccPhase.CANCEL;
        };
    }
    
    private Map<String, Object> extractTryResults(SagaExecutionState sagaState) {
        // Try results are stored in the completion order
        Map<String, Object> tryResults = new HashMap<>();
        for (String participantId : sagaState.getCompletionOrder()) {
            // Results would be in the saga context
            tryResults.put(participantId, null); // Placeholder
        }
        return tryResults;
    }
}

