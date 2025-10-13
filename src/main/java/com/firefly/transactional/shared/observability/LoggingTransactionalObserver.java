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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logging-based implementation of GenericTransactionalObserver.
 * <p>
 * This implementation logs transactional events using SLF4J for debugging
 * and monitoring purposes across different patterns (SAGA, TCC, etc.).
 */
public class LoggingTransactionalObserver implements GenericTransactionalObserver {
    
    private static final Logger log = LoggerFactory.getLogger(LoggingTransactionalObserver.class);
    
    @Override
    public void onTransactionStarted(String transactionType, String transactionName, String correlationId) {
        log.info("Transaction started: type={}, name={}, correlationId={}", 
                transactionType, transactionName, correlationId);
    }
    
    @Override
    public void onTransactionCompleted(String transactionType, String transactionName, String correlationId, boolean success, long durationMs) {
        if (success) {
            log.info("Transaction completed successfully: type={}, name={}, correlationId={}, durationMs={}", 
                    transactionType, transactionName, correlationId, durationMs);
        } else {
            log.warn("Transaction completed with failure: type={}, name={}, correlationId={}, durationMs={}", 
                    transactionType, transactionName, correlationId, durationMs);
        }
    }
    
    @Override
    public void onStepStarted(String transactionType, String transactionName, String correlationId, String stepId) {
        log.debug("Step started: type={}, name={}, correlationId={}, stepId={}", 
                transactionType, transactionName, correlationId, stepId);
    }
    
    @Override
    public void onStepSuccess(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs) {
        log.debug("Step succeeded: type={}, name={}, correlationId={}, stepId={}, attempts={}, durationMs={}", 
                transactionType, transactionName, correlationId, stepId, attempts, durationMs);
    }
    
    @Override
    public void onStepFailure(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs, Throwable error) {
        log.warn("Step failed: type={}, name={}, correlationId={}, stepId={}, attempts={}, durationMs={}, error={}", 
                transactionType, transactionName, correlationId, stepId, attempts, durationMs, error.getMessage(), error);
    }
    
    @Override
    public void onCompensationStarted(String transactionType, String transactionName, String correlationId, String stepId) {
        log.info("Compensation started: type={}, name={}, correlationId={}, stepId={}", 
                transactionType, transactionName, correlationId, stepId);
    }
    
    @Override
    public void onCompensationSuccess(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs) {
        log.info("Compensation succeeded: type={}, name={}, correlationId={}, stepId={}, attempts={}, durationMs={}", 
                transactionType, transactionName, correlationId, stepId, attempts, durationMs);
    }
    
    @Override
    public void onCompensationFailure(String transactionType, String transactionName, String correlationId, String stepId, int attempts, long durationMs, Throwable error) {
        log.error("Compensation failed: type={}, name={}, correlationId={}, stepId={}, attempts={}, durationMs={}, error={}", 
                transactionType, transactionName, correlationId, stepId, attempts, durationMs, error.getMessage(), error);
    }
}
