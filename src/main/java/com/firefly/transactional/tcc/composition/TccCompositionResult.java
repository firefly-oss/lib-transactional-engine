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

import com.firefly.transactional.tcc.core.TccResult;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Result of a TCC composition execution.
 * <p>
 * This class provides comprehensive information about the execution of a TCC composition,
 * including individual TCC results, timing information, and overall success status.
 * It supports both successful and failed composition executions with detailed error information.
 */
public class TccCompositionResult {
    
    private final String compositionName;
    private final String compositionId;
    private final boolean success;
    private final Instant startedAt;
    private final Instant completedAt;
    private final Map<String, TccResult> tccResults;
    private final Set<String> completedTccs;
    private final Set<String> failedTccs;
    private final Set<String> skippedTccs;
    private final Map<String, Throwable> tccErrors;
    private final Throwable compositionError;
    private final Map<String, Object> sharedVariables;
    
    public TccCompositionResult(String compositionName,
                                String compositionId,
                                boolean success,
                                Instant startedAt,
                                Instant completedAt,
                                Map<String, TccResult> tccResults,
                                Set<String> completedTccs,
                                Set<String> failedTccs,
                                Set<String> skippedTccs,
                                Map<String, Throwable> tccErrors,
                                Throwable compositionError,
                                Map<String, Object> sharedVariables) {
        this.compositionName = Objects.requireNonNull(compositionName, "compositionName cannot be null");
        this.compositionId = Objects.requireNonNull(compositionId, "compositionId cannot be null");
        this.success = success;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt cannot be null");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt cannot be null");
        this.tccResults = Collections.unmodifiableMap(new HashMap<>(tccResults));
        this.completedTccs = Collections.unmodifiableSet(new HashSet<>(completedTccs));
        this.failedTccs = Collections.unmodifiableSet(new HashSet<>(failedTccs));
        this.skippedTccs = Collections.unmodifiableSet(new HashSet<>(skippedTccs));
        this.tccErrors = Collections.unmodifiableMap(new HashMap<>(tccErrors));
        this.compositionError = compositionError;
        this.sharedVariables = Collections.unmodifiableMap(new HashMap<>(sharedVariables));
    }
    
    /**
     * Gets the composition name.
     * 
     * @return the composition name
     */
    public String getCompositionName() {
        return compositionName;
    }
    
    /**
     * Gets the composition ID.
     * 
     * @return the composition ID
     */
    public String getCompositionId() {
        return compositionId;
    }
    
    /**
     * Checks if the composition execution was successful.
     * 
     * @return true if successful, false otherwise
     */
    public boolean isSuccess() {
        return success;
    }
    
    /**
     * Gets the composition start time.
     * 
     * @return the start time
     */
    public Instant getStartedAt() {
        return startedAt;
    }
    
    /**
     * Gets the composition completion time.
     * 
     * @return the completion time
     */
    public Instant getCompletedAt() {
        return completedAt;
    }
    
    /**
     * Gets the total execution duration.
     * 
     * @return the execution duration
     */
    public Duration getDuration() {
        return Duration.between(startedAt, completedAt);
    }
    
    /**
     * Gets all TCC results.
     * 
     * @return an unmodifiable map of TCC results
     */
    public Map<String, TccResult> getTccResults() {
        return tccResults;
    }
    
    /**
     * Gets the result of a specific TCC.
     * 
     * @param tccId the TCC ID
     * @return the TCC result, or null if not found
     */
    public TccResult getTccResult(String tccId) {
        return tccResults.get(tccId);
    }
    
    /**
     * Gets all completed TCC IDs.
     * 
     * @return an unmodifiable set of completed TCC IDs
     */
    public Set<String> getCompletedTccs() {
        return completedTccs;
    }
    
    /**
     * Gets all failed TCC IDs.
     * 
     * @return an unmodifiable set of failed TCC IDs
     */
    public Set<String> getFailedTccs() {
        return failedTccs;
    }
    
    /**
     * Gets all skipped TCC IDs.
     * 
     * @return an unmodifiable set of skipped TCC IDs
     */
    public Set<String> getSkippedTccs() {
        return skippedTccs;
    }
    
    /**
     * Gets all TCC errors.
     * 
     * @return an unmodifiable map of TCC errors
     */
    public Map<String, Throwable> getTccErrors() {
        return tccErrors;
    }
    
    /**
     * Gets the composition-level error, if any.
     * 
     * @return the composition error, or null if none
     */
    public Optional<Throwable> getCompositionError() {
        return Optional.ofNullable(compositionError);
    }
    
    /**
     * Gets all shared variables.
     * 
     * @return an unmodifiable map of shared variables
     */
    public Map<String, Object> getSharedVariables() {
        return sharedVariables;
    }
    
    /**
     * Gets a shared variable value.
     * 
     * @param key the variable key
     * @return the variable value, or null if not found
     */
    public Object getSharedVariable(String key) {
        return sharedVariables.get(key);
    }
    
    /**
     * Gets a value from a specific TCC's result.
     * 
     * @param tccId the TCC ID
     * @param participantId the participant ID within the TCC
     * @return the participant result, or null if not available
     */
    public Object getTccParticipantResult(String tccId, String participantId) {
        TccResult tccResult = tccResults.get(tccId);
        if (tccResult == null) {
            return null;
        }
        
        TccResult.ParticipantResult participantResult = tccResult.getParticipantResult(participantId);
        return participantResult != null ? participantResult.getTryResult() : null;
    }
    
    /**
     * Gets the total number of TCCs in the composition.
     */
    public int getTotalTccCount() {
        return completedTccs.size() + failedTccs.size() + skippedTccs.size();
    }
    
    /**
     * Gets the number of successfully completed TCCs.
     */
    public int getCompletedTccCount() {
        return completedTccs.size();
    }
    
    /**
     * Gets the number of failed TCCs.
     */
    public int getFailedTccCount() {
        return failedTccs.size();
    }
    
    /**
     * Gets the number of skipped TCCs.
     */
    public int getSkippedTccCount() {
        return skippedTccs.size();
    }
    
    /**
     * Checks if any TCCs failed during execution.
     * 
     * @return true if any TCCs failed
     */
    public boolean hasFailures() {
        return !failedTccs.isEmpty();
    }
    
    /**
     * Creates a TccCompositionResult from a composition context.
     * 
     * @param context the composition context
     * @param success the overall success status
     * @param compositionError the composition-level error, if any
     * @return the composition result
     */
    public static TccCompositionResult from(TccCompositionContext context, 
                                           boolean success, 
                                           Throwable compositionError) {
        return new TccCompositionResult(
            context.getCompositionName(),
            context.getCompositionId(),
            success,
            context.getStartedAt(),
            Instant.now(),
            context.getAllTccResults(),
            context.getCompletedTccs(),
            context.getFailedTccs(),
            context.getSkippedTccs(),
            new HashMap<>(), // TCC errors are in the individual results
            compositionError,
            context.getSharedVariables()
        );
    }
}
