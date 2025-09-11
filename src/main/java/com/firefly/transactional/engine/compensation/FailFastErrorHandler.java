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

package com.firefly.transactional.engine.compensation;

import com.firefly.transactional.core.SagaContext;
import com.firefly.transactional.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Error handler that fails the saga immediately when compensation errors occur.
 * This is useful for critical systems where compensation failures must not be ignored.
 */
public class FailFastErrorHandler implements CompensationErrorHandler {
    
    private static final Logger log = LoggerFactory.getLogger(FailFastErrorHandler.class);
    
    private final Set<Class<? extends Throwable>> criticalErrors;
    private final boolean failOnAnyError;
    
    /**
     * Creates a fail-fast handler that fails on any compensation error.
     */
    public FailFastErrorHandler() {
        this(true, Set.of());
    }
    
    /**
     * Creates a fail-fast handler with specific critical error types.
     * 
     * @param failOnAnyError if true, fails on any error; if false, only fails on critical errors
     * @param criticalErrors set of error types that should cause immediate failure
     */
    public FailFastErrorHandler(boolean failOnAnyError, Set<Class<? extends Throwable>> criticalErrors) {
        this.failOnAnyError = failOnAnyError;
        this.criticalErrors = criticalErrors != null ? criticalErrors : Set.of();
    }
    
    @Override
    public Mono<CompensationErrorResult> handleError(String stepId, 
                                                   Throwable error, 
                                                   SagaContext context, 
                                                   int attempt) {
        boolean shouldFail = failOnAnyError || isCriticalError(error);
        
        if (shouldFail) {
            log.error(JsonUtils.json(
                "event", "compensation_error_critical",
                "saga_name", context.sagaName(),
                "correlation_id", context.correlationId(),
                "step_id", stepId,
                "attempt", Integer.toString(attempt),
                "error_class", error.getClass().getName(),
                "error_message", error.getMessage() != null ? error.getMessage() : "No message",
                "action", "failing_saga"
            ), error);
            
            // Store the error in context
            context.putCompensationError(stepId, error);
            
            return Mono.just(CompensationErrorResult.FAIL_SAGA);
        } else {
            log.warn(JsonUtils.json(
                "event", "compensation_error_non_critical",
                "saga_name", context.sagaName(),
                "correlation_id", context.correlationId(),
                "step_id", stepId,
                "attempt", Integer.toString(attempt),
                "error_class", error.getClass().getName(),
                "error_message", error.getMessage() != null ? error.getMessage() : "No message",
                "action", "continuing"
            ), error);
            
            context.putCompensationError(stepId, error);
            return Mono.just(CompensationErrorResult.CONTINUE);
        }
    }
    
    @Override
    public boolean canHandle(Throwable error, String stepId, SagaContext context) {
        return failOnAnyError || isCriticalError(error);
    }
    
    @Override
    public String getStrategyName() {
        return "FailFast";
    }
    
    /**
     * Checks if the error is considered critical.
     */
    private boolean isCriticalError(Throwable error) {
        return criticalErrors.stream()
                .anyMatch(criticalType -> criticalType.isAssignableFrom(error.getClass()));
    }
    
    /**
     * Creates a fail-fast handler for specific critical error types.
     */
    public static FailFastErrorHandler forCriticalErrors(Class<? extends Throwable>... errorTypes) {
        return new FailFastErrorHandler(false, Set.of(errorTypes));
    }
}
