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
import reactor.core.publisher.Mono;

/**
 * Strategy interface for handling compensation errors.
 * Implementations define how to respond when compensation operations fail.
 */
public interface CompensationErrorHandler {
    
    /**
     * Handles a compensation error for a specific step.
     * 
     * @param stepId the ID of the step that failed compensation
     * @param error the error that occurred during compensation
     * @param context the saga context
     * @param attempt the attempt number (1-based)
     * @return a Mono that completes with the handling result
     */
    Mono<CompensationErrorResult> handleError(String stepId, 
                                            Throwable error, 
                                            SagaContext context, 
                                            int attempt);
    
    /**
     * Gets the name of this error handling strategy.
     * 
     * @return strategy name
     */
    String getStrategyName();
    
    /**
     * Determines if this handler should be applied to the given error.
     * 
     * @param error the error to check
     * @param stepId the step ID
     * @param context the saga context
     * @return true if this handler should process the error
     */
    default boolean canHandle(Throwable error, String stepId, SagaContext context) {
        return true;
    }
    
    /**
     * Result of compensation error handling.
     */
    enum CompensationErrorResult {
        /**
         * Continue with compensation of remaining steps.
         */
        CONTINUE,
        
        /**
         * Retry the failed compensation step.
         */
        RETRY,
        
        /**
         * Stop compensation and fail the saga.
         */
        FAIL_SAGA,
        
        /**
         * Skip this step and continue with remaining compensations.
         */
        SKIP_STEP,
        
        /**
         * Mark the step as compensated despite the error.
         */
        MARK_COMPENSATED
    }
}
