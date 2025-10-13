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

import com.firefly.transactional.tcc.core.TccContext;
import com.firefly.transactional.tcc.core.TccPhase;

import java.util.List;

/**
 * Composite implementation of TccEvents that delegates to multiple TccEvents implementations.
 * <p>
 * This allows multiple observability sinks (logging, metrics, tracing) to be used simultaneously.
 */
public class CompositeTccEvents implements TccEvents {
    
    private final List<TccEvents> delegates;
    
    public CompositeTccEvents(List<TccEvents> delegates) {
        this.delegates = delegates;
    }
    
    @Override
    public void onTccStarted(String tccName, String correlationId) {
        delegates.forEach(delegate -> delegate.onTccStarted(tccName, correlationId));
    }
    
    @Override
    public void onTccStarted(String tccName, String correlationId, TccContext context) {
        delegates.forEach(delegate -> delegate.onTccStarted(tccName, correlationId, context));
    }
    
    @Override
    public void onTccCompleted(String tccName, String correlationId, TccPhase finalPhase, long durationMs) {
        delegates.forEach(delegate -> delegate.onTccCompleted(tccName, correlationId, finalPhase, durationMs));
    }
    
    @Override
    public void onPhaseStarted(String tccName, String correlationId, TccPhase phase) {
        delegates.forEach(delegate -> delegate.onPhaseStarted(tccName, correlationId, phase));
    }
    
    @Override
    public void onPhaseCompleted(String tccName, String correlationId, TccPhase phase, long durationMs) {
        delegates.forEach(delegate -> delegate.onPhaseCompleted(tccName, correlationId, phase, durationMs));
    }
    
    @Override
    public void onPhaseFailed(String tccName, String correlationId, TccPhase phase, Throwable error, int attempts, long durationMs) {
        delegates.forEach(delegate -> delegate.onPhaseFailed(tccName, correlationId, phase, error, attempts, durationMs));
    }
    
    @Override
    public void onParticipantStarted(String tccName, String correlationId, String participantId, TccPhase phase) {
        delegates.forEach(delegate -> delegate.onParticipantStarted(tccName, correlationId, participantId, phase));
    }
    
    @Override
    public void onParticipantSuccess(String tccName, String correlationId, String participantId, TccPhase phase, int attempts, long durationMs) {
        delegates.forEach(delegate -> delegate.onParticipantSuccess(tccName, correlationId, participantId, phase, attempts, durationMs));
    }
    
    @Override
    public void onParticipantFailed(String tccName, String correlationId, String participantId, TccPhase phase, Throwable error, int attempts, long durationMs) {
        delegates.forEach(delegate -> delegate.onParticipantFailed(tccName, correlationId, participantId, phase, error, attempts, durationMs));
    }
    
    @Override
    public void onCancellationStarted(String tccName, String correlationId, String participantId) {
        delegates.forEach(delegate -> delegate.onCancellationStarted(tccName, correlationId, participantId));
    }
    
    @Override
    public void onCancellationSuccess(String tccName, String correlationId, String participantId, int attempts, long durationMs) {
        delegates.forEach(delegate -> delegate.onCancellationSuccess(tccName, correlationId, participantId, attempts, durationMs));
    }
    
    @Override
    public void onCancellationFailed(String tccName, String correlationId, String participantId, Throwable error, int attempts, long durationMs) {
        delegates.forEach(delegate -> delegate.onCancellationFailed(tccName, correlationId, participantId, error, attempts, durationMs));
    }
    
    @Override
    public void onParticipantRegistered(String tccName, String correlationId, String participantId) {
        delegates.forEach(delegate -> delegate.onParticipantRegistered(tccName, correlationId, participantId));
    }
    
    @Override
    public void onParticipantTimeout(String tccName, String correlationId, String participantId, TccPhase phase) {
        delegates.forEach(delegate -> delegate.onParticipantTimeout(tccName, correlationId, participantId, phase));
    }
    
    @Override
    public void onResourceReserved(String tccName, String correlationId, String participantId, String resourceId) {
        delegates.forEach(delegate -> delegate.onResourceReserved(tccName, correlationId, participantId, resourceId));
    }
    
    @Override
    public void onResourceReleased(String tccName, String correlationId, String participantId, String resourceId) {
        delegates.forEach(delegate -> delegate.onResourceReleased(tccName, correlationId, participantId, resourceId));
    }
    
    @Override
    public void onBatchOperationStarted(String tccName, String correlationId, TccPhase phase, List<String> participantIds) {
        delegates.forEach(delegate -> delegate.onBatchOperationStarted(tccName, correlationId, phase, participantIds));
    }
    
    @Override
    public void onBatchOperationCompleted(String tccName, String correlationId, TccPhase phase, List<String> participantIds, boolean allSuccessful) {
        delegates.forEach(delegate -> delegate.onBatchOperationCompleted(tccName, correlationId, phase, participantIds, allSuccessful));
    }
}
