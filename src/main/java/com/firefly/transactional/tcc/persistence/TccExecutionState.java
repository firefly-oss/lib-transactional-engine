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

import com.firefly.transactional.saga.persistence.serialization.SerializableSagaContext;
import com.firefly.transactional.tcc.core.TccContext;
import com.firefly.transactional.tcc.core.TccPhase;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable representation of a TCC transaction execution state for persistence.
 * <p>
 * This class captures all the necessary information to persist and recover
 * a TCC transaction execution, including the context, current progress, and metadata.
 * <p>
 * The state is designed to be serializable for storage in external systems
 * like Redis while maintaining all the information needed for recovery.
 * <p>
 * This class reuses {@link SerializableSagaContext} from the SAGA infrastructure
 * to maintain consistency and leverage existing serialization mechanisms.
 */
public final class TccExecutionState implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private final String correlationId;
    private final String tccName;
    private final TccExecutionStatus status;
    private final TccPhase currentPhase;
    private final Instant startedAt;
    private final Instant lastUpdatedAt;
    private final SerializableSagaContext context;
    private final Map<String, Object> participantInputs;
    private final Map<String, Object> tryResults;
    private final Map<String, TccParticipantStatus> participantStatuses;
    private final String failureReason;
    private final String failedParticipantId;
    
    /**
     * Creates a new TCC execution state.
     */
    public TccExecutionState(String correlationId,
                            String tccName,
                            TccExecutionStatus status,
                            TccPhase currentPhase,
                            Instant startedAt,
                            Instant lastUpdatedAt,
                            SerializableSagaContext context,
                            Map<String, Object> participantInputs,
                            Map<String, Object> tryResults,
                            Map<String, TccParticipantStatus> participantStatuses,
                            String failureReason,
                            String failedParticipantId) {
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.tccName = Objects.requireNonNull(tccName, "tccName");
        this.status = Objects.requireNonNull(status, "status");
        this.currentPhase = Objects.requireNonNull(currentPhase, "currentPhase");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.lastUpdatedAt = Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt");
        this.context = Objects.requireNonNull(context, "context");
        this.participantInputs = Map.copyOf(participantInputs != null ? participantInputs : Map.of());
        this.tryResults = Map.copyOf(tryResults != null ? tryResults : Map.of());
        this.participantStatuses = Map.copyOf(participantStatuses != null ? participantStatuses : Map.of());
        this.failureReason = failureReason;
        this.failedParticipantId = failedParticipantId;
    }
    
    // Getters
    public String getCorrelationId() { return correlationId; }
    public String getTccName() { return tccName; }
    public TccExecutionStatus getStatus() { return status; }
    public TccPhase getCurrentPhase() { return currentPhase; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public SerializableSagaContext getContext() { return context; }
    public Map<String, Object> getParticipantInputs() { return participantInputs; }
    public Map<String, Object> getTryResults() { return tryResults; }
    public Map<String, TccParticipantStatus> getParticipantStatuses() { return participantStatuses; }
    public String getFailureReason() { return failureReason; }
    public String getFailedParticipantId() { return failedParticipantId; }
    
    /**
     * Converts the serializable context back to a runtime TccContext.
     */
    public TccContext toTccContext() {
        TccContext tccContext = new TccContext(context.toSagaContext());
        tccContext.setCurrentPhase(currentPhase);
        tccContext.setTccName(tccName);
        
        // Restore try results
        tryResults.forEach(tccContext::putTryResult);
        
        return tccContext;
    }
    
    /**
     * Creates a new state with updated status.
     */
    public TccExecutionState withStatus(TccExecutionStatus newStatus) {
        return new TccExecutionState(
            correlationId, tccName, newStatus, currentPhase, startedAt, Instant.now(),
            context, participantInputs, tryResults, participantStatuses, failureReason, failedParticipantId
        );
    }
    
    /**
     * Creates a new state with updated phase.
     */
    public TccExecutionState withPhase(TccPhase newPhase) {
        return new TccExecutionState(
            correlationId, tccName, status, newPhase, startedAt, Instant.now(),
            context, participantInputs, tryResults, participantStatuses, failureReason, failedParticipantId
        );
    }
    
    /**
     * Creates a new state with updated participant status.
     */
    public TccExecutionState withParticipantStatus(String participantId, TccParticipantStatus participantStatus) {
        Map<String, TccParticipantStatus> updatedStatuses = new java.util.HashMap<>(participantStatuses);
        updatedStatuses.put(participantId, participantStatus);
        
        return new TccExecutionState(
            correlationId, tccName, status, currentPhase, startedAt, Instant.now(),
            context, participantInputs, tryResults, updatedStatuses, failureReason, failedParticipantId
        );
    }
    
    /**
     * Creates a new state with a try result.
     */
    public TccExecutionState withTryResult(String participantId, Object result) {
        Map<String, Object> updatedResults = new java.util.HashMap<>(tryResults);
        updatedResults.put(participantId, result);
        
        return new TccExecutionState(
            correlationId, tccName, status, currentPhase, startedAt, Instant.now(),
            context, participantInputs, updatedResults, participantStatuses, failureReason, failedParticipantId
        );
    }
    
    /**
     * Creates a new state with failure information.
     */
    public TccExecutionState withFailure(String reason, String failedParticipant) {
        return new TccExecutionState(
            correlationId, tccName, TccExecutionStatus.FAILED, currentPhase, startedAt, Instant.now(),
            context, participantInputs, tryResults, participantStatuses, reason, failedParticipant
        );
    }
    
    /**
     * Checks if this TCC execution is in-flight (not completed).
     */
    public boolean isInFlight() {
        return status.isInFlight();
    }
    
    /**
     * Checks if this TCC execution is completed.
     */
    public boolean isCompleted() {
        return status.isCompleted();
    }
    
    /**
     * Creates a new builder for TccExecutionState.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for creating TccExecutionState instances.
     */
    public static class Builder {
        private String correlationId;
        private String tccName;
        private TccExecutionStatus status = TccExecutionStatus.INITIALIZED;
        private TccPhase currentPhase = TccPhase.TRY;
        private Instant startedAt = Instant.now();
        private Instant lastUpdatedAt = Instant.now();
        private SerializableSagaContext context;
        private Map<String, Object> participantInputs = Map.of();
        private Map<String, Object> tryResults = Map.of();
        private Map<String, TccParticipantStatus> participantStatuses = Map.of();
        private String failureReason;
        private String failedParticipantId;
        
        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }
        
        public Builder tccName(String tccName) {
            this.tccName = tccName;
            return this;
        }
        
        public Builder status(TccExecutionStatus status) {
            this.status = status;
            return this;
        }
        
        public Builder currentPhase(TccPhase currentPhase) {
            this.currentPhase = currentPhase;
            return this;
        }
        
        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }
        
        public Builder lastUpdatedAt(Instant lastUpdatedAt) {
            this.lastUpdatedAt = lastUpdatedAt;
            return this;
        }
        
        public Builder context(SerializableSagaContext context) {
            this.context = context;
            return this;
        }
        
        public Builder participantInputs(Map<String, Object> participantInputs) {
            this.participantInputs = participantInputs;
            return this;
        }
        
        public Builder tryResults(Map<String, Object> tryResults) {
            this.tryResults = tryResults;
            return this;
        }
        
        public Builder participantStatuses(Map<String, TccParticipantStatus> participantStatuses) {
            this.participantStatuses = participantStatuses;
            return this;
        }
        
        public Builder failureReason(String failureReason) {
            this.failureReason = failureReason;
            return this;
        }
        
        public Builder failedParticipantId(String failedParticipantId) {
            this.failedParticipantId = failedParticipantId;
            return this;
        }
        
        public TccExecutionState build() {
            return new TccExecutionState(
                correlationId, tccName, status, currentPhase, startedAt, lastUpdatedAt,
                context, participantInputs, tryResults, participantStatuses, failureReason, failedParticipantId
            );
        }
    }
    
    @Override
    public String toString() {
        return "TccExecutionState{" +
                "correlationId='" + correlationId + '\'' +
                ", tccName='" + tccName + '\'' +
                ", status=" + status +
                ", currentPhase=" + currentPhase +
                ", participants=" + participantStatuses.size() +
                '}';
    }
}

