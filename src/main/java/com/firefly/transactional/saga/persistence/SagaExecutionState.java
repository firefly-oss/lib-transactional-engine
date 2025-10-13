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

package com.firefly.transactional.saga.persistence;

import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.saga.persistence.serialization.SerializableSagaContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable representation of a saga execution state for persistence.
 * <p>
 * This class captures all the necessary information to persist and recover
 * a saga execution, including the context, current progress, and metadata.
 * <p>
 * The state is designed to be serializable for storage in external systems
 * like Redis while maintaining all the information needed for recovery.
 */
public final class SagaExecutionState {
    
    private final String correlationId;
    private final String sagaName;
    private final SagaExecutionStatus status;
    private final Instant startedAt;
    private final Instant lastUpdatedAt;
    private final SerializableSagaContext context;
    private final Map<String, Object> stepInputs;
    private final List<String> completionOrder;
    private final String currentLayer;
    private final int currentLayerIndex;
    private final List<List<String>> topologyLayers;
    private final Map<String, StepExecutionStatus> stepStatuses;
    private final String failureReason;
    private final boolean compensationRequired;

    /**
     * Creates a new saga execution state.
     */
    public SagaExecutionState(String correlationId,
                             String sagaName,
                             SagaExecutionStatus status,
                             Instant startedAt,
                             Instant lastUpdatedAt,
                             SerializableSagaContext context,
                             Map<String, Object> stepInputs,
                             List<String> completionOrder,
                             String currentLayer,
                             int currentLayerIndex,
                             List<List<String>> topologyLayers,
                             Map<String, StepExecutionStatus> stepStatuses,
                             String failureReason,
                             boolean compensationRequired) {
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
        this.sagaName = Objects.requireNonNull(sagaName, "sagaName");
        this.status = Objects.requireNonNull(status, "status");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.lastUpdatedAt = Objects.requireNonNull(lastUpdatedAt, "lastUpdatedAt");
        this.context = Objects.requireNonNull(context, "context");
        this.stepInputs = Map.copyOf(stepInputs != null ? stepInputs : Map.of());
        this.completionOrder = List.copyOf(completionOrder != null ? completionOrder : List.of());
        this.currentLayer = currentLayer;
        this.currentLayerIndex = currentLayerIndex;
        this.topologyLayers = topologyLayers != null ? List.copyOf(topologyLayers) : List.of();
        this.stepStatuses = Map.copyOf(stepStatuses != null ? stepStatuses : Map.of());
        this.failureReason = failureReason;
        this.compensationRequired = compensationRequired;
    }

    // Getters
    public String getCorrelationId() { return correlationId; }
    public String getSagaName() { return sagaName; }
    public SagaExecutionStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public SerializableSagaContext getContext() { return context; }

    /**
     * Converts the serializable context back to a runtime SagaContext.
     */
    public SagaContext toSagaContext() {
        return context.toSagaContext();
    }
    public Map<String, Object> getStepInputs() { return stepInputs; }
    public List<String> getCompletionOrder() { return completionOrder; }
    public String getCurrentLayer() { return currentLayer; }
    public int getCurrentLayerIndex() { return currentLayerIndex; }
    public List<List<String>> getTopologyLayers() { return topologyLayers; }
    public Map<String, StepExecutionStatus> getStepStatuses() { return stepStatuses; }
    public String getFailureReason() { return failureReason; }
    public boolean isCompensationRequired() { return compensationRequired; }

    /**
     * Creates a new state with updated status and timestamp.
     */
    public SagaExecutionState withStatus(SagaExecutionStatus newStatus) {
        return new SagaExecutionState(
            correlationId, sagaName, newStatus, startedAt, Instant.now(),
            context, stepInputs, completionOrder, currentLayer, currentLayerIndex,
            topologyLayers, stepStatuses, failureReason, compensationRequired
        );
    }

    /**
     * Creates a new state with updated step status.
     */
    public SagaExecutionState withStepStatus(String stepId, StepExecutionStatus stepStatus) {
        Map<String, StepExecutionStatus> updatedStatuses = Map.copyOf(stepStatuses);
        updatedStatuses.put(stepId, stepStatus);
        return new SagaExecutionState(
            correlationId, sagaName, status, startedAt, Instant.now(),
            context, stepInputs, completionOrder, currentLayer, currentLayerIndex,
            topologyLayers, updatedStatuses, failureReason, compensationRequired
        );
    }

    /**
     * Creates a new state with failure information.
     */
    public SagaExecutionState withFailure(String reason, boolean requiresCompensation) {
        return new SagaExecutionState(
            correlationId, sagaName, SagaExecutionStatus.FAILED, startedAt, Instant.now(),
            context, stepInputs, completionOrder, currentLayer, currentLayerIndex,
            topologyLayers, stepStatuses, reason, requiresCompensation
        );
    }

    /**
     * Checks if this saga execution is in-flight (not completed).
     */
    public boolean isInFlight() {
        return status == SagaExecutionStatus.RUNNING || 
               status == SagaExecutionStatus.COMPENSATING ||
               status == SagaExecutionStatus.PAUSED;
    }

    /**
     * Checks if this saga execution is stale (hasn't been updated recently).
     */
    public boolean isStale(Instant cutoff) {
        return lastUpdatedAt.isBefore(cutoff);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SagaExecutionState that = (SagaExecutionState) o;
        return Objects.equals(correlationId, that.correlationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(correlationId);
    }

    @Override
    public String toString() {
        return "SagaExecutionState{" +
                "correlationId='" + correlationId + '\'' +
                ", sagaName='" + sagaName + '\'' +
                ", status=" + status +
                ", startedAt=" + startedAt +
                ", lastUpdatedAt=" + lastUpdatedAt +
                '}';
    }

    /**
     * Builder for creating SagaExecutionState instances.
     */
    public static class Builder {
        private String correlationId;
        private String sagaName;
        private SagaExecutionStatus status = SagaExecutionStatus.RUNNING;
        private Instant startedAt = Instant.now();
        private Instant lastUpdatedAt = Instant.now();
        private SerializableSagaContext context;
        private Map<String, Object> stepInputs = Map.of();
        private List<String> completionOrder = List.of();
        private String currentLayer;
        private int currentLayerIndex = 0;
        private List<List<String>> topologyLayers = List.of();
        private Map<String, StepExecutionStatus> stepStatuses = Map.of();
        private String failureReason;
        private boolean compensationRequired = false;

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder sagaName(String sagaName) {
            this.sagaName = sagaName;
            return this;
        }

        public Builder status(SagaExecutionStatus status) {
            this.status = status;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder lastUpdatedAt(Instant lastUpdatedAt) {
            this.lastUpdatedAt = lastUpdatedAt;
            return this;
        }

        public Builder context(SerializableSagaContext context) {
            this.context = context;
            return this;
        }

        public Builder context(SagaContext context) {
            this.context = SerializableSagaContext.fromSagaContext(context);
            return this;
        }

        public Builder stepInputs(Map<String, Object> stepInputs) {
            this.stepInputs = stepInputs;
            return this;
        }

        public Builder completionOrder(List<String> completionOrder) {
            this.completionOrder = completionOrder;
            return this;
        }

        public Builder currentLayer(String currentLayer) {
            this.currentLayer = currentLayer;
            return this;
        }

        public Builder currentLayerIndex(int currentLayerIndex) {
            this.currentLayerIndex = currentLayerIndex;
            return this;
        }

        public Builder topologyLayers(List<List<String>> topologyLayers) {
            this.topologyLayers = topologyLayers;
            return this;
        }

        public Builder stepStatuses(Map<String, StepExecutionStatus> stepStatuses) {
            this.stepStatuses = stepStatuses;
            return this;
        }

        public Builder failureReason(String failureReason) {
            this.failureReason = failureReason;
            return this;
        }

        public Builder compensationRequired(boolean compensationRequired) {
            this.compensationRequired = compensationRequired;
            return this;
        }

        public SagaExecutionState build() {
            return new SagaExecutionState(
                correlationId, sagaName, status, startedAt, lastUpdatedAt,
                context, stepInputs, completionOrder, currentLayer, currentLayerIndex,
                topologyLayers, stepStatuses, failureReason, compensationRequired
            );
        }
    }
}
