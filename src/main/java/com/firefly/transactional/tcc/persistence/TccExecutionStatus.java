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
 * Represents the execution status of a TCC transaction.
 * <p>
 * This enum tracks the overall state of the TCC transaction through its lifecycle.
 */
public enum TccExecutionStatus {
    
    /**
     * TCC transaction has been initialized but not yet started.
     */
    INITIALIZED,

    /**
     * TCC transaction is currently running (generic running state).
     */
    RUNNING,

    /**
     * TCC transaction is currently executing the try phase.
     */
    TRYING,
    
    /**
     * All try operations succeeded, now executing the confirm phase.
     */
    CONFIRMING,
    
    /**
     * All confirm operations completed successfully.
     * This is a terminal success state.
     */
    CONFIRMED,

    /**
     * TCC transaction completed (generic completion state).
     */
    COMPLETED,
    
    /**
     * One or more try operations failed, now executing the cancel phase.
     */
    CANCELING,
    
    /**
     * All cancel operations completed.
     * This is a terminal failure state.
     */
    CANCELED,
    
    /**
     * TCC transaction failed during confirm or cancel phase.
     * This indicates a serious error that may require manual intervention.
     */
    FAILED;
    
    /**
     * Checks if this status represents a completed state.
     *
     * @return true if the transaction is in a terminal state
     */
    public boolean isCompleted() {
        return this == CONFIRMED || this == COMPLETED || this == CANCELED || this == FAILED;
    }
    
    /**
     * Checks if this status represents a successful completion.
     *
     * @return true if the transaction completed successfully
     */
    public boolean isSuccess() {
        return this == CONFIRMED;
    }
    
    /**
     * Checks if this status represents a failure.
     *
     * @return true if the transaction failed or was canceled
     */
    public boolean isFailure() {
        return this == CANCELED || this == FAILED;
    }
    
    /**
     * Checks if this status represents an in-flight transaction.
     * In-flight transactions can be recovered after application restarts.
     *
     * @return true if the transaction is in-flight
     */
    public boolean isInFlight() {
        return this == RUNNING || this == TRYING || this == CONFIRMING || this == CANCELING;
    }
    
    /**
     * Checks if this status is recoverable.
     * Recoverable states can be resumed after application restarts.
     *
     * @return true if the transaction can be recovered
     */
    public boolean isRecoverable() {
        return isInFlight();
    }
}

