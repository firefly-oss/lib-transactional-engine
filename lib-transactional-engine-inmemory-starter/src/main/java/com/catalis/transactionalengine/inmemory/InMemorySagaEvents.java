package com.catalis.transactionalengine.inmemory;

import com.catalis.transactionalengine.observability.SagaEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory implementation of SagaEvents that provides enhanced logging and 
 * keeps a limited history of events in memory for debugging purposes.
 * 
 * This implementation is suitable for development, testing, and small-scale 
 * deployments where external observability systems are not required.
 */
public class InMemorySagaEvents implements SagaEvents {
    
    private static final Logger log = LoggerFactory.getLogger(InMemorySagaEvents.class);
    
    private final InMemoryTransactionalEngineProperties.EventsProperties config;
    private final ConcurrentLinkedQueue<SagaEvent> eventHistory;
    private final AtomicInteger eventCounter = new AtomicInteger(0);

    public InMemorySagaEvents(InMemoryTransactionalEngineProperties.EventsProperties config) {
        this.config = config;
        this.eventHistory = new ConcurrentLinkedQueue<>();
        log.info("Initialized InMemorySagaEvents with maxEventsInMemory: {}", config.getMaxEventsInMemory());
    }

    @Override
    public void onStart(String sagaName, String sagaId) {
        if (config.isEnabled()) {
            log.info("🚀 Saga started: {} [{}]", sagaName, sagaId);
            recordEvent("SAGA_STARTED", sagaName, sagaId, null, null);
        }
    }

    @Override
    public void onStart(String sagaName, String sagaId, com.catalis.transactionalengine.core.SagaContext ctx) {
        if (config.isEnabled()) {
            log.info("🚀 Saga started with context: {} [{}]", sagaName, sagaId);
            recordEvent("SAGA_STARTED_WITH_CONTEXT", sagaName, sagaId, null, null);
        }
    }

    @Override
    public void onStepStarted(String sagaName, String sagaId, String stepId) {
        if (config.isEnabled() && config.isLogStepDetails()) {
            log.info("▶️  Step started: {} -> {} [{}]", sagaName, stepId, sagaId);
            recordEvent("STEP_STARTED", sagaName, sagaId, stepId, null);
        }
    }

    @Override
    public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {
        if (config.isEnabled()) {
            if (config.isLogTiming()) {
                log.info("✅ Step succeeded: {} -> {} [{}] (attempts: {}, latency: {}ms)", 
                    sagaName, stepId, sagaId, attempts, latencyMs);
            } else {
                log.info("✅ Step succeeded: {} -> {} [{}]", sagaName, stepId, sagaId);
            }
            recordEvent("STEP_SUCCESS", sagaName, sagaId, stepId, 
                String.format("attempts=%d, latency=%dms", attempts, latencyMs));
        }
    }

    @Override
    public void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) {
        if (config.isEnabled()) {
            if (config.isLogTiming()) {
                log.error("❌ Step failed: {} -> {} [{}] (attempts: {}, latency: {}ms): {}", 
                    sagaName, stepId, sagaId, attempts, latencyMs, error.getMessage());
            } else {
                log.error("❌ Step failed: {} -> {} [{}]: {}", sagaName, stepId, sagaId, error.getMessage());
            }
            recordEvent("STEP_FAILED", sagaName, sagaId, stepId, 
                String.format("attempts=%d, latency=%dms, error=%s", attempts, latencyMs, error.getMessage()));
        }
    }

    @Override
    public void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) {
        if (config.isEnabled() && config.isLogCompensation()) {
            if (error == null) {
                log.info("🔄 Compensation succeeded: {} -> {} [{}]", sagaName, stepId, sagaId);
            } else {
                log.error("💥 Compensation failed: {} -> {} [{}]: {}", sagaName, stepId, sagaId, error.getMessage());
            }
            recordEvent("COMPENSATED", sagaName, sagaId, stepId, 
                error != null ? "error=" + error.getMessage() : "success");
        }
    }

    @Override
    public void onStepSkippedIdempotent(String sagaName, String sagaId, String stepId) {
        if (config.isEnabled() && config.isLogStepDetails()) {
            log.info("⏭️  Step skipped (idempotent): {} -> {} [{}]", sagaName, stepId, sagaId);
            recordEvent("STEP_SKIPPED_IDEMPOTENT", sagaName, sagaId, stepId, null);
        }
    }

    @Override
    public void onCompensationStarted(String sagaName, String sagaId, String stepId) {
        if (config.isEnabled() && config.isLogCompensation()) {
            log.info("🔄 Compensation started: {} -> {} [{}]", sagaName, stepId, sagaId);
            recordEvent("COMPENSATION_STARTED", sagaName, sagaId, stepId, null);
        }
    }

    @Override
    public void onCompensationRetry(String sagaName, String sagaId, String stepId, int attempt) {
        if (config.isEnabled() && config.isLogCompensation()) {
            log.info("🔁 Compensation retry: {} -> {} [{}] (attempt: {})", sagaName, stepId, sagaId, attempt);
            recordEvent("COMPENSATION_RETRY", sagaName, sagaId, stepId, "attempt=" + attempt);
        }
    }

    @Override
    public void onCompensationSkipped(String sagaName, String sagaId, String stepId, String reason) {
        if (config.isEnabled() && config.isLogCompensation()) {
            log.info("⏭️  Compensation skipped: {} -> {} [{}] (reason: {})", sagaName, stepId, sagaId, reason);
            recordEvent("COMPENSATION_SKIPPED", sagaName, sagaId, stepId, "reason=" + reason);
        }
    }

    @Override
    public void onCompensationCircuitOpen(String sagaName, String sagaId, String stepId) {
        if (config.isEnabled() && config.isLogCompensation()) {
            log.warn("🔌 Compensation circuit open: {} -> {} [{}]", sagaName, stepId, sagaId);
            recordEvent("COMPENSATION_CIRCUIT_OPEN", sagaName, sagaId, stepId, null);
        }
    }

    @Override
    public void onCompensationBatchCompleted(String sagaName, String sagaId, List<String> stepIds, boolean allSuccessful) {
        if (config.isEnabled() && config.isLogCompensation()) {
            if (allSuccessful) {
                log.info("✅ Compensation batch completed successfully: {} [{}] (steps: {})", 
                    sagaName, sagaId, stepIds.size());
            } else {
                log.warn("⚠️  Compensation batch completed with failures: {} [{}] (steps: {})", 
                    sagaName, sagaId, stepIds.size());
            }
            recordEvent("COMPENSATION_BATCH_COMPLETED", sagaName, sagaId, null, 
                String.format("steps=%d, allSuccessful=%s", stepIds.size(), allSuccessful));
        }
    }

    @Override
    public void onCompleted(String sagaName, String sagaId, boolean success) {
        if (config.isEnabled()) {
            if (success) {
                log.info("🎉 Saga completed successfully: {} [{}]", sagaName, sagaId);
            } else {
                log.error("💥 Saga completed with failure: {} [{}]", sagaName, sagaId);
            }
            recordEvent("SAGA_COMPLETED", sagaName, sagaId, null, "success=" + success);
        }
    }

    private void recordEvent(String eventType, String sagaName, String sagaId, String stepId, String details) {
        if (eventHistory.size() >= config.getMaxEventsInMemory()) {
            eventHistory.poll(); // Remove oldest event
        }
        
        SagaEvent event = new SagaEvent(
            eventCounter.incrementAndGet(),
            Instant.now(),
            eventType,
            sagaName,
            sagaId,
            stepId,
            details
        );
        
        eventHistory.offer(event);
    }

    /**
     * Returns a snapshot of recent saga events for debugging purposes.
     */
    public List<SagaEvent> getRecentEvents() {
        return List.copyOf(eventHistory);
    }

    /**
     * Returns the total number of events processed.
     */
    public int getTotalEventCount() {
        return eventCounter.get();
    }

    /**
     * Clears the event history.
     */
    public void clearHistory() {
        eventHistory.clear();
        log.info("Cleared saga event history");
    }

    /**
     * Represents a saga event stored in memory.
     */
    public static class SagaEvent {
        private final long id;
        private final Instant timestamp;
        private final String eventType;
        private final String sagaName;
        private final String sagaId;
        private final String stepId;
        private final String details;

        public SagaEvent(long id, Instant timestamp, String eventType, String sagaName, 
                        String sagaId, String stepId, String details) {
            this.id = id;
            this.timestamp = timestamp;
            this.eventType = eventType;
            this.sagaName = sagaName;
            this.sagaId = sagaId;
            this.stepId = stepId;
            this.details = details;
        }

        public long getId() { return id; }
        public Instant getTimestamp() { return timestamp; }
        public String getEventType() { return eventType; }
        public String getSagaName() { return sagaName; }
        public String getSagaId() { return sagaId; }
        public String getStepId() { return stepId; }
        public String getDetails() { return details; }

        @Override
        public String toString() {
            return String.format("SagaEvent{id=%d, timestamp=%s, type=%s, saga=%s[%s], step=%s, details=%s}",
                id, timestamp, eventType, sagaName, sagaId, stepId, details);
        }
    }
}