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

package com.firefly.transactionalengine.engine.compensation;

import com.firefly.transactionalengine.core.SagaContext;
import com.firefly.transactionalengine.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Error handler that retries compensation operations with exponential backoff.
 * Useful for transient errors that may resolve with retry.
 */
public class RetryWithBackoffErrorHandler implements CompensationErrorHandler {
    
    private static final Logger log = LoggerFactory.getLogger(RetryWithBackoffErrorHandler.class);
    
    private final int maxRetries;
    private final Set<Class<? extends Throwable>> retryableErrors;
    private final boolean retryAllErrors;
    
    /**
     * Creates a retry handler that retries all errors up to 3 times.
     */
    public RetryWithBackoffErrorHandler() {
        this(3, true, Set.of());
    }
    
    /**
     * Creates a retry handler with specific configuration.
     * 
     * @param maxRetries maximum number of retry attempts
     * @param retryAllErrors if true, retries all errors; if false, only retries specific error types
     * @param retryableErrors set of error types that should be retried
     */
    public RetryWithBackoffErrorHandler(int maxRetries, 
                                       boolean retryAllErrors, 
                                       Set<Class<? extends Throwable>> retryableErrors) {
        this.maxRetries = maxRetries;
        this.retryAllErrors = retryAllErrors;
        this.retryableErrors = retryableErrors != null ? retryableErrors : Set.of();
    }
    
    @Override
    public Mono<CompensationErrorResult> handleError(String stepId, 
                                                   Throwable error, 
                                                   SagaContext context, 
                                                   int attempt) {
        boolean shouldRetry = (retryAllErrors || isRetryableError(error)) && attempt <= maxRetries;
        
        if (shouldRetry) {
            log.warn(JsonUtils.json(
                "event", "compensation_error_retrying",
                "saga_name", context.sagaName(),
                "correlation_id", context.correlationId(),
                "step_id", stepId,
                "attempt", Integer.toString(attempt),
                "max_retries", Integer.toString(maxRetries),
                "error_class", error.getClass().getName(),
                "error_message", error.getMessage() != null ? error.getMessage() : "No message"
            ), error);
            
            return Mono.just(CompensationErrorResult.RETRY);
        } else {
            log.error(JsonUtils.json(
                "event", "compensation_error_max_retries_exceeded",
                "saga_name", context.sagaName(),
                "correlation_id", context.correlationId(),
                "step_id", stepId,
                "attempt", Integer.toString(attempt),
                "max_retries", Integer.toString(maxRetries),
                "error_class", error.getClass().getName(),
                "error_message", error.getMessage() != null ? error.getMessage() : "No message",
                "action", "continuing"
            ), error);
            
            // Store the final error in context
            context.putCompensationError(stepId, error);
            
            return Mono.just(CompensationErrorResult.CONTINUE);
        }
    }
    
    @Override
    public boolean canHandle(Throwable error, String stepId, SagaContext context) {
        return retryAllErrors || isRetryableError(error);
    }
    
    @Override
    public String getStrategyName() {
        return "RetryWithBackoff";
    }
    
    /**
     * Checks if the error is retryable.
     */
    private boolean isRetryableError(Throwable error) {
        return retryableErrors.stream()
                .anyMatch(retryableType -> retryableType.isAssignableFrom(error.getClass()));
    }
    
    /**
     * Creates a retry handler for specific retryable error types.
     */
    public static RetryWithBackoffErrorHandler forRetryableErrors(int maxRetries, 
                                                                 Class<? extends Throwable>... errorTypes) {
        return new RetryWithBackoffErrorHandler(maxRetries, false, Set.of(errorTypes));
    }
    
    /**
     * Creates a retry handler for transient network errors.
     */
    public static RetryWithBackoffErrorHandler forNetworkErrors(int maxRetries) {
        return forRetryableErrors(maxRetries, 
            java.net.ConnectException.class,
            java.net.SocketTimeoutException.class,
            java.io.IOException.class);
    }
}
