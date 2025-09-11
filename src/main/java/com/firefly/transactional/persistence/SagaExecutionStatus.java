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

package com.firefly.transactional.persistence;

/**
 * Enumeration of possible saga execution statuses for persistence tracking.
 * <p>
 * These statuses represent the high-level state of a saga execution and are used
 * for persistence, recovery, and monitoring purposes.
 */
public enum SagaExecutionStatus {
    
    /**
     * Saga execution is currently running and making progress.
     */
    RUNNING,
    
    /**
     * Saga execution has been paused and can be resumed later.
     * This status is used for long-running sagas that may need to wait
     * for external events or manual intervention.
     */
    PAUSED,
    
    /**
     * Saga execution has failed and compensation is in progress.
     */
    COMPENSATING,
    
    /**
     * Saga execution completed successfully.
     * All steps were executed successfully and no compensation was needed.
     */
    COMPLETED_SUCCESS,
    
    /**
     * Saga execution failed and compensation completed successfully.
     * The saga failed but all compensation actions were executed successfully.
     */
    COMPLETED_COMPENSATED,
    
    /**
     * Saga execution failed and compensation also failed.
     * This represents a critical failure state that may require manual intervention.
     */
    FAILED,
    
    /**
     * Saga execution was cancelled before completion.
     * This can happen due to external cancellation requests or timeout.
     */
    CANCELLED,
    
    /**
     * Saga execution is in an unknown state.
     * This status is used when the system cannot determine the current state,
     * typically during recovery scenarios.
     */
    UNKNOWN;
    
    /**
     * Checks if this status represents a completed saga (successful or failed).
     */
    public boolean isCompleted() {
        return this == COMPLETED_SUCCESS || 
               this == COMPLETED_COMPENSATED || 
               this == FAILED || 
               this == CANCELLED;
    }
    
    /**
     * Checks if this status represents an in-flight saga that can be recovered.
     */
    public boolean isRecoverable() {
        return this == RUNNING || 
               this == PAUSED || 
               this == COMPENSATING;
    }
    
    /**
     * Checks if this status represents a successful completion.
     */
    public boolean isSuccessful() {
        return this == COMPLETED_SUCCESS;
    }
    
    /**
     * Checks if this status represents a failure state.
     */
    public boolean isFailed() {
        return this == FAILED || this == CANCELLED;
    }
    
    /**
     * Checks if compensation is in progress or completed.
     */
    public boolean involvesCompensation() {
        return this == COMPENSATING || this == COMPLETED_COMPENSATED;
    }
}
