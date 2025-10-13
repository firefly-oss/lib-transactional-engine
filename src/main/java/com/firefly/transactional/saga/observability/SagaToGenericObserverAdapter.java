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

package com.firefly.transactional.saga.observability;

import com.firefly.transactional.shared.observability.GenericTransactionalObserver;
import com.firefly.transactional.saga.composition.SagaCompositionContext;

import java.util.List;

/**
 * Adapter that bridges SAGA-specific observability events to generic transactional observability.
 * <p>
 * This adapter implements the SagaEvents interface and translates SAGA-specific events
 * to generic transactional events, allowing SAGA observability to be consumed by
 * generic observability systems without method signature conflicts.
 */
public class SagaToGenericObserverAdapter implements SagaEvents {
    
    private static final String TRANSACTION_TYPE = "SAGA";
    private final GenericTransactionalObserver genericObserver;
    
    public SagaToGenericObserverAdapter(GenericTransactionalObserver genericObserver) {
        this.genericObserver = genericObserver;
    }
    
    @Override
    public void onStart(String sagaName, String sagaId) {
        genericObserver.onTransactionStarted(TRANSACTION_TYPE, sagaName, sagaId);
    }

    @Override
    public void onStart(String sagaName, String sagaId, com.firefly.transactional.saga.core.SagaContext ctx) {
        genericObserver.onTransactionStarted(TRANSACTION_TYPE, sagaName, sagaId);
    }

    @Override
    public void onStarted(String sagaName, String sagaId) {
        genericObserver.onTransactionStarted(TRANSACTION_TYPE, sagaName, sagaId);
    }
    
    @Override
    public void onCompleted(String sagaName, String sagaId, boolean success) {
        // Note: We don't have duration here, so we pass 0
        genericObserver.onTransactionCompleted(TRANSACTION_TYPE, sagaName, sagaId, success, 0L);
    }
    
    @Override
    public void onStepStarted(String sagaName, String sagaId, String stepId) {
        genericObserver.onStepStarted(TRANSACTION_TYPE, sagaName, sagaId, stepId);
    }
    
    @Override
    public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {
        genericObserver.onStepSuccess(TRANSACTION_TYPE, sagaName, sagaId, stepId, attempts, latencyMs);
    }
    
    @Override
    public void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) {
        genericObserver.onStepFailure(TRANSACTION_TYPE, sagaName, sagaId, stepId, attempts, latencyMs, error);
    }
    
    @Override
    public void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) {
        if (error == null) {
            // Note: We don't have attempts and duration here, so we pass defaults
            genericObserver.onCompensationSuccess(TRANSACTION_TYPE, sagaName, sagaId, stepId, 1, 0L);
        } else {
            genericObserver.onCompensationFailure(TRANSACTION_TYPE, sagaName, sagaId, stepId, 1, 0L, error);
        }
    }
    
    @Override
    public void onCompensationStarted(String sagaName, String sagaId, String stepId) {
        genericObserver.onCompensationStarted(TRANSACTION_TYPE, sagaName, sagaId, stepId);
    }
    

    
    // Default implementations for other SAGA-specific methods
    @Override
    public void onStepSkippedIdempotent(String sagaName, String sagaId, String stepId) {
        // This is SAGA-specific and doesn't map to generic events
    }

    @Override
    public void onCompensationRetry(String sagaName, String sagaId, String stepId, int attempt) {
        // This is SAGA-specific and doesn't map to generic events
    }

    @Override
    public void onCompensationSkipped(String sagaName, String sagaId, String stepId, String reason) {
        // This is SAGA-specific and doesn't map to generic events
    }

    @Override
    public void onCompensationCircuitOpen(String sagaName, String sagaId, String stepId) {
        // This is SAGA-specific and doesn't map to generic events
    }

    @Override
    public void onCompensationBatchCompleted(String sagaName, String sagaId, List<String> stepIds, boolean allSuccessful) {
        // This is SAGA-specific and doesn't map to generic events
    }

    // Composition-level events
    @Override
    public void onCompositionStarted(String compositionName, String compositionId) {
        genericObserver.onTransactionStarted(TRANSACTION_TYPE + "_COMPOSITION", compositionName, compositionId);
    }

    @Override
    public void onCompositionStarted(String compositionName, String compositionId, SagaCompositionContext ctx) {
        genericObserver.onTransactionStarted(TRANSACTION_TYPE + "_COMPOSITION", compositionName, compositionId);
    }

    @Override
    public void onCompositionCompleted(String compositionName, String compositionId, boolean success, long latencyMs, int completedSagas, int failedSagas, int skippedSagas) {
        genericObserver.onTransactionCompleted(TRANSACTION_TYPE + "_COMPOSITION", compositionName, compositionId, success, latencyMs);
    }

    @Override
    public void onCompositionFailed(String compositionName, String compositionId, Throwable error, long latencyMs) {
        genericObserver.onTransactionCompleted(TRANSACTION_TYPE + "_COMPOSITION", compositionName, compositionId, false, latencyMs);
    }

    @Override
    public void onCompositionCompensationStarted(String compositionName, String compositionId) {
        genericObserver.onCompensationStarted(TRANSACTION_TYPE + "_COMPOSITION", compositionName, compositionId, "composition");
    }

    @Override
    public void onCompositionCompensationCompleted(String compositionName, String compositionId, boolean success) {
        if (success) {
            genericObserver.onCompensationSuccess(TRANSACTION_TYPE + "_COMPOSITION", compositionName, compositionId, "composition", 1, 0L);
        } else {
            genericObserver.onCompensationFailure(TRANSACTION_TYPE + "_COMPOSITION", compositionName, compositionId, "composition", 1, 0L, new RuntimeException("Composition compensation failed"));
        }
    }
}
