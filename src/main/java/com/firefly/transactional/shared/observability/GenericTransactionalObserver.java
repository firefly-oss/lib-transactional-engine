/*
 * Copyright (c) 2023 Firefly Authors. All rights reserved.
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

package com.firefly.transactional.shared.observability;

/**
 * Generic observability interface for transactional patterns.
 * <p>
 * This interface provides a clean abstraction for observing transactional operations
 * across different patterns (SAGA, TCC, etc.) without forcing inheritance or
 * creating method signature conflicts.
 * <p>
 * This interface is designed to be implemented by adapters that bridge between
 * pattern-specific observability interfaces and generic observability systems.
 */
public interface GenericTransactionalObserver {
    
    /**
     * Invoked when a transaction starts.
     *
     * @param transactionType the type of transaction (e.g., "SAGA", "TCC")
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     */
    void onTransactionStarted(String transactionType, String transactionName, String correlationId);
    
    /**
     * Invoked when a transaction completes.
     *
     * @param transactionType the type of transaction (e.g., "SAGA", "TCC")
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param success whether the transaction completed successfully
     * @param durationMs the duration in milliseconds
     */
    void onTransactionCompleted(String transactionType, String transactionName, String correlationId, boolean success, long durationMs);
    
    /**
     * Invoked when a step/participant starts.
     *
     * @param transactionType the type of transaction (e.g., "SAGA", "TCC")
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param stepId the step/participant ID
     */
    void onStepStarted(String transactionType, String transactionName, String correlationId, String stepId);
    
    /**
     * Invoked when a step/participant succeeds.
     *
     * @param transactionType the type of transaction (e.g., "SAGA", "TCC")
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param stepId the step/participant ID
     * @param attempts the number of attempts made
     * @param durationMs the duration in milliseconds
     */
    void onStepSuccess(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs);
    
    /**
     * Invoked when a step/participant fails.
     *
     * @param transactionType the type of transaction (e.g., "SAGA", "TCC")
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param stepId the step/participant ID
     * @param attempts the number of attempts made
     * @param durationMs the duration in milliseconds
     * @param error the error that occurred
     */
    void onStepFailure(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs, Throwable error);
    
    /**
     * Invoked when compensation/cancellation starts.
     *
     * @param transactionType the type of transaction (e.g., "SAGA", "TCC")
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param stepId the step/participant ID being compensated/canceled
     */
    void onCompensationStarted(String transactionType, String transactionName, String correlationId, String stepId);
    
    /**
     * Invoked when compensation/cancellation succeeds.
     *
     * @param transactionType the type of transaction (e.g., "SAGA", "TCC")
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param stepId the step/participant ID that was compensated/canceled
     * @param attempts the number of attempts made
     * @param durationMs the duration in milliseconds
     */
    void onCompensationSuccess(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs);
    
    /**
     * Invoked when compensation/cancellation fails.
     *
     * @param transactionType the type of transaction (e.g., "SAGA", "TCC")
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param stepId the step/participant ID that failed to be compensated/canceled
     * @param attempts the number of attempts made
     * @param durationMs the duration in milliseconds
     * @param error the error that occurred
     */
    void onCompensationFailure(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs, Throwable error);
}
