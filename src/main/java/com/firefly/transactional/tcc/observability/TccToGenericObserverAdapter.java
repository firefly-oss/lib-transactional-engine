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

package com.firefly.transactional.tcc.observability;

import com.firefly.transactional.shared.observability.GenericTransactionalObserver;
import com.firefly.transactional.tcc.core.TccContext;
import com.firefly.transactional.tcc.core.TccPhase;

import java.util.List;

/**
 * Adapter that bridges TCC-specific observability events to generic transactional observability.
 * <p>
 * This adapter implements the TccEvents interface and translates TCC-specific events
 * to generic transactional events, allowing TCC observability to be consumed by
 * generic observability systems without method signature conflicts.
 */
public class TccToGenericObserverAdapter implements TccEvents {
    
    private static final String TRANSACTION_TYPE = "TCC";
    private final GenericTransactionalObserver genericObserver;
    
    public TccToGenericObserverAdapter(GenericTransactionalObserver genericObserver) {
        this.genericObserver = genericObserver;
    }
    
    @Override
    public void onTccStarted(String tccName, String correlationId) {
        genericObserver.onTransactionStarted(TRANSACTION_TYPE, tccName, correlationId);
    }
    
    @Override
    public void onTccStarted(String tccName, String correlationId, TccContext ctx) {
        genericObserver.onTransactionStarted(TRANSACTION_TYPE, tccName, correlationId);
    }
    
    @Override
    public void onTccCompleted(String tccName, String correlationId, TccPhase finalPhase, long durationMs) {
        boolean success = finalPhase == TccPhase.CONFIRM;
        genericObserver.onTransactionCompleted(TRANSACTION_TYPE, tccName, correlationId, success, durationMs);
    }
    
    @Override
    public void onPhaseStarted(String tccName, String correlationId, TccPhase phase) {
        // Map TCC phases to generic step events
        String stepId = phase.name().toLowerCase();
        genericObserver.onStepStarted(TRANSACTION_TYPE, tccName, correlationId, stepId);
    }
    
    @Override
    public void onPhaseCompleted(String tccName, String correlationId, TccPhase phase, long durationMs) {
        String stepId = phase.name().toLowerCase();
        genericObserver.onStepSuccess(TRANSACTION_TYPE, tccName, correlationId, stepId, 1, durationMs);
    }
    
    @Override
    public void onPhaseFailed(String tccName, String correlationId, TccPhase phase, Throwable error, int attempts, long durationMs) {
        String stepId = phase.name().toLowerCase();
        genericObserver.onStepFailure(TRANSACTION_TYPE, tccName, correlationId, stepId, attempts, durationMs, error);
    }
    
    @Override
    public void onParticipantStarted(String tccName, String correlationId, String participantId, TccPhase phase) {
        String stepId = participantId + ":" + phase.name().toLowerCase();
        genericObserver.onStepStarted(TRANSACTION_TYPE, tccName, correlationId, stepId);
    }
    
    @Override
    public void onParticipantSuccess(String tccName, String correlationId, String participantId, TccPhase phase, int attempts, long durationMs) {
        String stepId = participantId + ":" + phase.name().toLowerCase();
        genericObserver.onStepSuccess(TRANSACTION_TYPE, tccName, correlationId, stepId, attempts, durationMs);
    }
    
    @Override
    public void onParticipantFailed(String tccName, String correlationId, String participantId, TccPhase phase, Throwable error, int attempts, long durationMs) {
        String stepId = participantId + ":" + phase.name().toLowerCase();
        genericObserver.onStepFailure(TRANSACTION_TYPE, tccName, correlationId, stepId, attempts, durationMs, error);
    }
    
    @Override
    public void onCancellationStarted(String tccName, String correlationId, String participantId) {
        genericObserver.onCompensationStarted(TRANSACTION_TYPE, tccName, correlationId, participantId);
    }
    
    @Override
    public void onCancellationSuccess(String tccName, String correlationId, String participantId, int attempts, long durationMs) {
        genericObserver.onCompensationSuccess(TRANSACTION_TYPE, tccName, correlationId, participantId, attempts, durationMs);
    }
    
    @Override
    public void onCancellationFailed(String tccName, String correlationId, String participantId, Throwable error, int attempts, long durationMs) {
        genericObserver.onCompensationFailure(TRANSACTION_TYPE, tccName, correlationId, participantId, attempts, durationMs, error);
    }
    
    // Default implementations for TCC-specific methods that don't map to generic events
    @Override
    public void onParticipantRegistered(String tccName, String correlationId, String participantId) {
        // TCC-specific event that doesn't map to generic events
    }
    
    @Override
    public void onParticipantTimeout(String tccName, String correlationId, String participantId, TccPhase phase) {
        // TCC-specific event that doesn't map to generic events
    }
    
    @Override
    public void onResourceReserved(String tccName, String correlationId, String participantId, String resourceId) {
        // TCC-specific event that doesn't map to generic events
    }
    
    @Override
    public void onResourceReleased(String tccName, String correlationId, String participantId, String resourceId) {
        // TCC-specific event that doesn't map to generic events
    }
    
    @Override
    public void onBatchOperationStarted(String tccName, String correlationId, TccPhase phase, List<String> participantIds) {
        // TCC-specific event that doesn't map to generic events
    }
    
    @Override
    public void onBatchOperationCompleted(String tccName, String correlationId, TccPhase phase, List<String> participantIds, boolean allSuccessful) {
        // TCC-specific event that doesn't map to generic events
    }
}
