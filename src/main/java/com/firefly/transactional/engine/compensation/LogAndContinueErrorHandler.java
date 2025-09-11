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

/**
 * Error handler that logs compensation errors and continues with remaining compensations.
 * This is the default behavior that maintains backward compatibility.
 */
public class LogAndContinueErrorHandler implements CompensationErrorHandler {
    
    private static final Logger log = LoggerFactory.getLogger(LogAndContinueErrorHandler.class);
    
    @Override
    public Mono<CompensationErrorResult> handleError(String stepId, 
                                                   Throwable error, 
                                                   SagaContext context, 
                                                   int attempt) {
        // Log the error with structured logging
        log.error(JsonUtils.json(
            "event", "compensation_error",
            "saga_name", context.sagaName(),
            "correlation_id", context.correlationId(),
            "step_id", stepId,
            "attempt", Integer.toString(attempt),
            "error_class", error.getClass().getName(),
            "error_message", error.getMessage() != null ? error.getMessage() : "No message"
        ), error);
        
        // Store the error in context for later analysis
        context.putCompensationError(stepId, error);
        
        // Continue with remaining compensations
        return Mono.just(CompensationErrorResult.CONTINUE);
    }
    
    @Override
    public String getStrategyName() {
        return "LogAndContinue";
    }
}
