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

package com.firefly.transactional.shared.observability;

/**
 * Generic observability interface for transactional patterns.
 * <p>
 * This interface provides a common abstraction for observability events
 * that can be implemented by both SAGA and TCC patterns. It defines
 * the core lifecycle events that are common across transaction patterns.
 * 
 * @param <C> the type of context (e.g., SagaContext, TccContext)
 */
public interface TransactionalEvents<C> {
    
    /**
     * Called when a transaction starts.
     *
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     */
    default void onStart(String transactionName, String correlationId) {
        // Default implementation for backward compatibility
    }
    
    /**
     * Called when a transaction starts with context.
     *
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param context the transaction context
     */
    default void onStart(String transactionName, String correlationId, C context) {
        onStart(transactionName, correlationId);
    }
    
    /**
     * Called when a transaction completes (successfully or with failure).
     *
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param success whether the transaction completed successfully
     */
    void onCompleted(String transactionName, String correlationId, boolean success);
    
    /**
     * Called when a transaction step/participant starts.
     *
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param stepId the step/participant ID
     */
    void onStepStarted(String transactionName, String correlationId, String stepId);
    
    /**
     * Called when a transaction step/participant succeeds.
     *
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param stepId the step/participant ID
     * @param attempts the number of attempts made
     * @param latencyMs the latency in milliseconds
     */
    void onStepSuccess(String transactionName, String correlationId, String stepId, int attempts, long latencyMs);
    
    /**
     * Called when a transaction step/participant fails.
     *
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param stepId the step/participant ID
     * @param attempts the number of attempts made
     * @param latencyMs the latency in milliseconds
     * @param error the error that occurred
     */
    void onStepFailure(String transactionName, String correlationId, String stepId, int attempts, long latencyMs, Throwable error);
    
    /**
     * Called when a compensation/cancel operation starts.
     *
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param stepId the step/participant ID being compensated/canceled
     */
    void onCompensationStarted(String transactionName, String correlationId, String stepId);
    
    /**
     * Called when a compensation/cancel operation succeeds.
     *
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param stepId the step/participant ID that was compensated/canceled
     * @param attempts the number of attempts made
     * @param latencyMs the latency in milliseconds
     */
    void onCompensationSuccess(String transactionName, String correlationId, String stepId, int attempts, long latencyMs);
    
    /**
     * Called when a compensation/cancel operation fails.
     *
     * @param transactionName the name of the transaction
     * @param correlationId the correlation ID
     * @param stepId the step/participant ID that failed to be compensated/canceled
     * @param attempts the number of attempts made
     * @param latencyMs the latency in milliseconds
     * @param error the error that occurred
     */
    void onCompensationFailure(String transactionName, String correlationId, String stepId, int attempts, long latencyMs, Throwable error);
}
