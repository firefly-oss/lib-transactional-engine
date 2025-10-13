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

package com.firefly.transactional.tcc.core;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of a TCC transaction execution.
 * <p>
 * Contains information about the transaction outcome, including:
 * <ul>
 *   <li>Success or failure status</li>
 *   <li>Final phase reached (CONFIRM or CANCEL)</li>
 *   <li>Participant results from each phase</li>
 *   <li>Execution timing information</li>
 *   <li>Error information if the transaction failed</li>
 * </ul>
 */
public class TccResult {
    
    private final String correlationId;
    private final String tccName;
    private final boolean success;
    private final TccPhase finalPhase;
    private final Map<String, Object> tryResults;
    private final Map<String, ParticipantResult> participantResults;
    private final Instant startedAt;
    private final Instant completedAt;
    private final Throwable error;
    private final String failedParticipantId;
    
    private TccResult(Builder builder) {
        this.correlationId = builder.correlationId;
        this.tccName = builder.tccName;
        this.success = builder.success;
        this.finalPhase = builder.finalPhase;
        this.tryResults = Map.copyOf(builder.tryResults);
        this.participantResults = Map.copyOf(builder.participantResults);
        this.startedAt = builder.startedAt;
        this.completedAt = builder.completedAt;
        this.error = builder.error;
        this.failedParticipantId = builder.failedParticipantId;
    }
    
    /**
     * Gets the correlation ID of the TCC transaction.
     *
     * @return the correlation ID
     */
    public String getCorrelationId() {
        return correlationId;
    }
    
    /**
     * Gets the name of the TCC transaction.
     *
     * @return the TCC transaction name
     */
    public String getTccName() {
        return tccName;
    }
    
    /**
     * Checks if the TCC transaction completed successfully.
     * A transaction is successful if all try operations succeeded and
     * all confirm operations completed.
     *
     * @return true if successful, false otherwise
     */
    public boolean isSuccess() {
        return success;
    }
    
    /**
     * Gets the final phase reached by the transaction.
     *
     * @return CONFIRM if successful, CANCEL if failed
     */
    public TccPhase getFinalPhase() {
        return finalPhase;
    }
    
    /**
     * Gets all try results from participants.
     *
     * @return a map of participant IDs to their try results
     */
    public Map<String, Object> getTryResults() {
        return tryResults;
    }
    
    /**
     * Gets the try result for a specific participant.
     *
     * @param participantId the participant identifier
     * @return the try result, or null if not found
     */
    public Object getTryResult(String participantId) {
        return tryResults.get(participantId);
    }
    
    /**
     * Gets the try result for a specific participant with type casting.
     *
     * @param participantId the participant identifier
     * @param resultType the expected result type
     * @param <T> the result type
     * @return an Optional containing the result if found and of correct type
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getTryResult(String participantId, Class<T> resultType) {
        Object result = tryResults.get(participantId);
        if (result != null && resultType.isInstance(result)) {
            return Optional.of((T) result);
        }
        return Optional.empty();
    }
    
    /**
     * Gets detailed results for all participants.
     *
     * @return a map of participant IDs to their detailed results
     */
    public Map<String, ParticipantResult> getParticipantResults() {
        return participantResults;
    }
    
    /**
     * Gets the detailed result for a specific participant.
     *
     * @param participantId the participant identifier
     * @return the participant result, or null if not found
     */
    public ParticipantResult getParticipantResult(String participantId) {
        return participantResults.get(participantId);
    }
    
    /**
     * Gets the transaction start time.
     *
     * @return the start timestamp
     */
    public Instant getStartedAt() {
        return startedAt;
    }
    
    /**
     * Gets the transaction completion time.
     *
     * @return the completion timestamp
     */
    public Instant getCompletedAt() {
        return completedAt;
    }
    
    /**
     * Gets the total execution duration.
     *
     * @return the duration from start to completion
     */
    public Duration getDuration() {
        return Duration.between(startedAt, completedAt);
    }
    
    /**
     * Gets the error that caused the transaction to fail, if any.
     *
     * @return the error, or null if the transaction succeeded
     */
    public Throwable getError() {
        return error;
    }
    
    /**
     * Gets the ID of the participant that failed during the try phase.
     *
     * @return the failed participant ID, or null if no participant failed
     */
    public String getFailedParticipantId() {
        return failedParticipantId;
    }
    
    /**
     * Checks if the transaction was canceled.
     *
     * @return true if the final phase was CANCEL
     */
    public boolean isCanceled() {
        return finalPhase == TccPhase.CANCEL;
    }
    
    /**
     * Checks if the transaction was confirmed.
     *
     * @return true if the final phase was CONFIRM
     */
    public boolean isConfirmed() {
        return finalPhase == TccPhase.CONFIRM;
    }
    
    /**
     * Creates a new builder for TccResult.
     *
     * @param correlationId the correlation ID
     * @return a new builder instance
     */
    public static Builder builder(String correlationId) {
        return new Builder(correlationId);
    }
    
    /**
     * Builder for creating TccResult instances.
     */
    public static class Builder {
        private final String correlationId;
        private String tccName;
        private boolean success;
        private TccPhase finalPhase;
        private Map<String, Object> tryResults = new HashMap<>();
        private Map<String, ParticipantResult> participantResults = new HashMap<>();
        private Instant startedAt = Instant.now();
        private Instant completedAt = Instant.now();
        private Throwable error;
        private String failedParticipantId;
        
        private Builder(String correlationId) {
            this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        }
        
        public Builder tccName(String tccName) {
            this.tccName = tccName;
            return this;
        }
        
        public Builder success(boolean success) {
            this.success = success;
            return this;
        }
        
        public Builder finalPhase(TccPhase finalPhase) {
            this.finalPhase = finalPhase;
            return this;
        }
        
        public Builder tryResults(Map<String, Object> tryResults) {
            this.tryResults = new HashMap<>(tryResults);
            return this;
        }
        
        public Builder addTryResult(String participantId, Object result) {
            this.tryResults.put(participantId, result);
            return this;
        }
        
        public Builder participantResults(Map<String, ParticipantResult> participantResults) {
            this.participantResults = new HashMap<>(participantResults);
            return this;
        }
        
        public Builder addParticipantResult(String participantId, ParticipantResult result) {
            this.participantResults.put(participantId, result);
            return this;
        }
        
        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }
        
        public Builder completedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }
        
        public Builder error(Throwable error) {
            this.error = error;
            return this;
        }
        
        public Builder failedParticipantId(String failedParticipantId) {
            this.failedParticipantId = failedParticipantId;
            return this;
        }
        
        public TccResult build() {
            return new TccResult(this);
        }
    }
    
    /**
     * Detailed result for a single participant.
     */
    public static class ParticipantResult {
        private final String participantId;
        private final Object tryResult;
        private final boolean trySucceeded;
        private final boolean confirmSucceeded;
        private final boolean cancelSucceeded;
        private final Throwable error;
        
        public ParticipantResult(String participantId, Object tryResult, boolean trySucceeded,
                                boolean confirmSucceeded, boolean cancelSucceeded, Throwable error) {
            this.participantId = participantId;
            this.tryResult = tryResult;
            this.trySucceeded = trySucceeded;
            this.confirmSucceeded = confirmSucceeded;
            this.cancelSucceeded = cancelSucceeded;
            this.error = error;
        }
        
        public String getParticipantId() { return participantId; }
        public Object getTryResult() { return tryResult; }
        public boolean isTrySucceeded() { return trySucceeded; }
        public boolean isConfirmSucceeded() { return confirmSucceeded; }
        public boolean isCancelSucceeded() { return cancelSucceeded; }
        public Throwable getError() { return error; }
    }
}

