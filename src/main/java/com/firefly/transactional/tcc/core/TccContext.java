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

import com.firefly.transactional.saga.core.SagaContext;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime context for a TCC transaction execution.
 * <p>
 * This class wraps {@link SagaContext} to provide TCC-specific functionality
 * while reusing the existing context infrastructure. It maintains:
 * <ul>
 *   <li>Current TCC phase (TRY, CONFIRM, CANCEL)</li>
 *   <li>Participant try results for use in confirm/cancel phases</li>
 *   <li>Participant execution status</li>
 *   <li>Transaction-wide variables and headers</li>
 * </ul>
 * <p>
 * Thread-safe for concurrent access from multiple participants.
 */
public class TccContext {
    
    private final SagaContext sagaContext;
    private final Map<String, Object> tryResults;
    private volatile TccPhase currentPhase;
    
    /**
     * Creates a new TCC context with the given correlation ID.
     *
     * @param correlationId the unique identifier for this TCC transaction
     */
    public TccContext(String correlationId) {
        this.sagaContext = new SagaContext(correlationId);
        this.tryResults = new ConcurrentHashMap<>();
        this.currentPhase = TccPhase.TRY;
    }
    
    /**
     * Creates a TCC context wrapping an existing SagaContext.
     * This is useful for integration with existing infrastructure.
     *
     * @param sagaContext the underlying saga context
     */
    public TccContext(SagaContext sagaContext) {
        this.sagaContext = sagaContext;
        this.tryResults = new ConcurrentHashMap<>();
        this.currentPhase = TccPhase.TRY;
    }
    
    /**
     * Gets the correlation ID for this TCC transaction.
     *
     * @return the correlation ID
     */
    public String correlationId() {
        return sagaContext.correlationId();
    }
    
    /**
     * Gets the current phase of the TCC transaction.
     *
     * @return the current phase
     */
    public TccPhase getCurrentPhase() {
        return currentPhase;
    }
    
    /**
     * Sets the current phase of the TCC transaction.
     *
     * @param phase the new phase
     */
    public void setCurrentPhase(TccPhase phase) {
        this.currentPhase = phase;
    }
    
    /**
     * Stores the result from a participant's try method.
     *
     * @param participantId the participant identifier
     * @param result the try method result
     */
    public void putTryResult(String participantId, Object result) {
        tryResults.put(participantId, result);
        // Also store in saga context for compatibility
        sagaContext.putResult(participantId, result);
    }
    
    /**
     * Retrieves the result from a participant's try method.
     *
     * @param participantId the participant identifier
     * @return the try method result, or null if not found
     */
    public Object getTryResult(String participantId) {
        return tryResults.get(participantId);
    }
    
    /**
     * Retrieves the result from a participant's try method with type casting.
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
     * Gets all try results.
     *
     * @return a map of participant IDs to their try results
     */
    public Map<String, Object> getAllTryResults() {
        return Map.copyOf(tryResults);
    }
    
    /**
     * Sets a header value for propagation.
     *
     * @param key the header key
     * @param value the header value
     */
    public void setHeader(String key, String value) {
        sagaContext.putHeader(key, value);
    }
    
    /**
     * Gets a header value.
     *
     * @param key the header key
     * @return the header value, or null if not found
     */
    public String getHeader(String key) {
        return sagaContext.headers().get(key);
    }
    
    /**
     * Gets all headers.
     *
     * @return a map of all headers
     */
    public Map<String, String> getHeaders() {
        return sagaContext.headers();
    }
    
    /**
     * Sets a variable for cross-participant data exchange.
     *
     * @param key the variable key
     * @param value the variable value
     */
    public void setVariable(String key, Object value) {
        sagaContext.putVariable(key, value);
    }
    
    /**
     * Gets a variable value.
     *
     * @param key the variable key
     * @return the variable value, or null if not found
     */
    public Object getVariable(String key) {
        return sagaContext.getVariable(key);
    }
    
    /**
     * Gets all variables.
     *
     * @return a map of all variables
     */
    public Map<String, Object> getVariables() {
        return sagaContext.variables();
    }
    
    /**
     * Gets the underlying SagaContext for advanced use cases.
     * This allows integration with existing SAGA infrastructure.
     *
     * @return the underlying saga context
     */
    public SagaContext getSagaContext() {
        return sagaContext;
    }
    
    /**
     * Stores a result for a participant (generic storage).
     *
     * @param participantId the participant identifier
     * @param result the result to store
     */
    public void putResult(String participantId, Object result) {
        sagaContext.putResult(participantId, result);
    }
    
    /**
     * Retrieves a result for a participant (generic retrieval).
     *
     * @param participantId the participant identifier
     * @return the stored result, or null if not found
     */
    public Object getResult(String participantId) {
        return sagaContext.getResult(participantId);
    }
    
    /**
     * Gets the TCC transaction name.
     *
     * @return the TCC transaction name
     */
    public String getTccName() {
        return sagaContext.sagaName();
    }
    
    /**
     * Sets the TCC transaction name.
     *
     * @param tccName the TCC transaction name
     */
    public void setTccName(String tccName) {
        sagaContext.setSagaName(tccName);
    }
}

