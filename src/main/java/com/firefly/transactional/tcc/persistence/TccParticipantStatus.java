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

/**
 * Represents the execution status of a TCC participant.
 * <p>
 * This enum tracks the state of an individual participant through the TCC phases.
 */
public enum TccParticipantStatus {
    
    /**
     * Participant has not yet started execution.
     */
    PENDING,
    
    /**
     * Participant is currently executing the try phase.
     */
    TRYING,
    
    /**
     * Try phase completed successfully.
     */
    TRIED,
    
    /**
     * Try phase failed.
     */
    TRY_FAILED,
    
    /**
     * Participant is currently executing the confirm phase.
     */
    CONFIRMING,
    
    /**
     * Confirm phase completed successfully.
     */
    CONFIRMED,
    
    /**
     * Confirm phase failed.
     */
    CONFIRM_FAILED,
    
    /**
     * Participant is currently executing the cancel phase.
     */
    CANCELING,
    
    /**
     * Cancel phase completed successfully.
     */
    CANCELED,
    
    /**
     * Cancel phase failed.
     */
    CANCEL_FAILED,
    
    /**
     * Participant was skipped (e.g., optional participant).
     */
    SKIPPED;
    
    /**
     * Checks if this status represents a completed state.
     *
     * @return true if the participant has completed execution
     */
    public boolean isCompleted() {
        return this == TRIED || this == TRY_FAILED || 
               this == CONFIRMED || this == CONFIRM_FAILED ||
               this == CANCELED || this == CANCEL_FAILED ||
               this == SKIPPED;
    }
    
    /**
     * Checks if this status represents a successful try.
     *
     * @return true if the try phase succeeded
     */
    public boolean isTrySucceeded() {
        return this == TRIED || this == CONFIRMING || this == CONFIRMED ||
               this == CANCELING || this == CANCELED;
    }
    
    /**
     * Checks if this status represents a failure.
     *
     * @return true if any phase failed
     */
    public boolean isFailed() {
        return this == TRY_FAILED || this == CONFIRM_FAILED || this == CANCEL_FAILED;
    }
}

