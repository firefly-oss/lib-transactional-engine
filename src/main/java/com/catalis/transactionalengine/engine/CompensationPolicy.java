package com.catalis.transactionalengine.engine;

/**
 * Compensation execution policies for Saga compensation phase.
 * Extracted from SagaEngine to improve code organization.
 */
public enum CompensationPolicy {
    STRICT_SEQUENTIAL,
    GROUPED_PARALLEL,
    RETRY_WITH_BACKOFF,
    CIRCUIT_BREAKER,
    BEST_EFFORT_PARALLEL
}
