package com.firefly.transactionalengine.core;

/**
 * Lifecycle states of a Saga step within a single execution.
 */
public enum StepStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    COMPENSATED
}
