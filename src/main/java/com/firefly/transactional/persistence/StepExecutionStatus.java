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

package com.firefly.transactional.persistence;

import com.firefly.transactional.core.StepStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Extended step execution status for persistence that includes additional metadata
 * beyond the basic StepStatus enum.
 * <p>
 * This class captures detailed information about step execution that is useful
 * for persistence, recovery, and monitoring purposes.
 */
public final class StepExecutionStatus {
    
    private final StepStatus status;
    private final Instant startedAt;
    private final Instant completedAt;
    private final int attemptCount;
    private final long latencyMs;
    private final String errorMessage;
    private final boolean compensated;
    private final Instant compensatedAt;

    /**
     * Creates a new step execution status.
     */
    public StepExecutionStatus(StepStatus status,
                              Instant startedAt,
                              Instant completedAt,
                              int attemptCount,
                              long latencyMs,
                              String errorMessage,
                              boolean compensated,
                              Instant compensatedAt) {
        this.status = Objects.requireNonNull(status, "status");
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.attemptCount = attemptCount;
        this.latencyMs = latencyMs;
        this.errorMessage = errorMessage;
        this.compensated = compensated;
        this.compensatedAt = compensatedAt;
    }

    // Getters
    public StepStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public int getAttemptCount() { return attemptCount; }
    public long getLatencyMs() { return latencyMs; }
    public String getErrorMessage() { return errorMessage; }
    public boolean isCompensated() { return compensated; }
    public Instant getCompensatedAt() { return compensatedAt; }

    /**
     * Creates a step execution status from the basic StepStatus.
     */
    public static StepExecutionStatus fromStepStatus(StepStatus status) {
        return new StepExecutionStatus(
            status, null, null, 0, 0, null, false, null
        );
    }

    /**
     * Creates a step execution status for a running step.
     */
    public static StepExecutionStatus running(Instant startedAt, int attemptCount) {
        return new StepExecutionStatus(
            StepStatus.RUNNING, startedAt, null, attemptCount, 0, null, false, null
        );
    }

    /**
     * Creates a step execution status for a completed step.
     */
    public static StepExecutionStatus completed(Instant startedAt, Instant completedAt, 
                                               int attemptCount, long latencyMs) {
        return new StepExecutionStatus(
            StepStatus.DONE, startedAt, completedAt, attemptCount, latencyMs, null, false, null
        );
    }

    /**
     * Creates a step execution status for a failed step.
     */
    public static StepExecutionStatus failed(Instant startedAt, Instant completedAt,
                                            int attemptCount, long latencyMs, String errorMessage) {
        return new StepExecutionStatus(
            StepStatus.FAILED, startedAt, completedAt, attemptCount, latencyMs, errorMessage, false, null
        );
    }

    /**
     * Creates a step execution status for a compensated step.
     */
    public static StepExecutionStatus compensated(StepExecutionStatus original, Instant compensatedAt) {
        return new StepExecutionStatus(
            StepStatus.COMPENSATED, original.startedAt, original.completedAt,
            original.attemptCount, original.latencyMs, original.errorMessage, true, compensatedAt
        );
    }

    /**
     * Checks if this step is in a terminal state (completed, failed, or compensated).
     */
    public boolean isTerminal() {
        return status == StepStatus.DONE ||
               status == StepStatus.FAILED ||
               status == StepStatus.COMPENSATED;
    }

    /**
     * Checks if this step execution was successful.
     */
    public boolean isSuccessful() {
        return status == StepStatus.DONE;
    }

    /**
     * Checks if this step execution failed.
     */
    public boolean isFailed() {
        return status == StepStatus.FAILED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StepExecutionStatus that = (StepExecutionStatus) o;
        return attemptCount == that.attemptCount &&
               latencyMs == that.latencyMs &&
               compensated == that.compensated &&
               status == that.status &&
               Objects.equals(startedAt, that.startedAt) &&
               Objects.equals(completedAt, that.completedAt) &&
               Objects.equals(errorMessage, that.errorMessage) &&
               Objects.equals(compensatedAt, that.compensatedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, startedAt, completedAt, attemptCount, 
                           latencyMs, errorMessage, compensated, compensatedAt);
    }

    @Override
    public String toString() {
        return "StepExecutionStatus{" +
                "status=" + status +
                ", startedAt=" + startedAt +
                ", completedAt=" + completedAt +
                ", attemptCount=" + attemptCount +
                ", latencyMs=" + latencyMs +
                ", errorMessage='" + errorMessage + '\'' +
                ", compensated=" + compensated +
                ", compensatedAt=" + compensatedAt +
                '}';
    }

    /**
     * Builder for creating StepExecutionStatus instances.
     */
    public static class Builder {
        private StepStatus status;
        private Instant startedAt;
        private Instant completedAt;
        private int attemptCount = 0;
        private long latencyMs = 0;
        private String errorMessage;
        private boolean compensated = false;
        private Instant compensatedAt;

        public Builder status(StepStatus status) {
            this.status = status;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder completedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public Builder attemptCount(int attemptCount) {
            this.attemptCount = attemptCount;
            return this;
        }

        public Builder latencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder compensated(boolean compensated) {
            this.compensated = compensated;
            return this;
        }

        public Builder compensatedAt(Instant compensatedAt) {
            this.compensatedAt = compensatedAt;
            return this;
        }

        public StepExecutionStatus build() {
            return new StepExecutionStatus(
                status, startedAt, completedAt, attemptCount, 
                latencyMs, errorMessage, compensated, compensatedAt
            );
        }
    }
}
