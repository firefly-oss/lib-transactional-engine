package com.catalis.transactionalengine.inmemory;

import com.catalis.transactionalengine.observability.SagaEvents;
import com.catalis.transactionalengine.util.JsonUtils;
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
        log.info(JsonUtils.json(
                "event", "inmemory_saga_events_initialized",
                "component", "InMemorySagaEvents",
                "max_events_in_memory", Integer.toString(config.getMaxEventsInMemory())
        ));
    }

    @Override
    public void onStart(String sagaName, String sagaId) {
        if (config.isEnabled()) {
            log.info(JsonUtils.json(
                    "saga_event", "started",
                    "saga_name", sagaName,
                    "saga_id", sagaId
            ));
            recordEvent("SAGA_STARTED", sagaName, sagaId, null, null);
        }
    }

    @Override
    public void onStart(String sagaName, String sagaId, com.catalis.transactionalengine.core.SagaContext ctx) {
        if (config.isEnabled()) {
            log.info(JsonUtils.json(
                    "saga_event", "started_with_context",
                    "saga_name", sagaName,
                    "saga_id", sagaId
            ));
            recordEvent("SAGA_STARTED_WITH_CONTEXT", sagaName, sagaId, null, null);
        }
    }

    @Override
    public void onStepStarted(String sagaName, String sagaId, String stepId) {
        if (config.isEnabled() && config.isLogStepDetails()) {
            log.info(JsonUtils.json(
                    "saga_event", "step_started",
                    "saga_name", sagaName,
                    "saga_id", sagaId,
                    "step_id", stepId
            ));
            recordEvent("STEP_STARTED", sagaName, sagaId, stepId, null);
        }
    }

    @Override
    public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {
        if (config.isEnabled()) {
            if (config.isLogTiming()) {
                log.info(JsonUtils.json(
                        "saga_event", "step_success",
                        "saga_name", sagaName,
                        "saga_id", sagaId,
                        "step_id", stepId,
                        "attempts", Integer.toString(attempts),
                        "latency_ms", Long.toString(latencyMs)
                ));
            } else {
                log.info(JsonUtils.json(
                        "saga_event", "step_success",
                        "saga_name", sagaName,
                        "saga_id", sagaId,
                        "step_id", stepId
                ));
            }
            recordEvent("STEP_SUCCESS", sagaName, sagaId, stepId, 
                String.format("attempts=%d, latency=%dms", attempts, latencyMs));
        }
    }

    @Override
    public void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) {
        if (config.isEnabled()) {
            if (config.isLogTiming()) {
                log.error(JsonUtils.json(
                        "saga_event", "step_failed",
                        "saga_name", sagaName,
                        "saga_id", sagaId,
                        "step_id", stepId,
                        "attempts", Integer.toString(attempts),
                        "latency_ms", Long.toString(latencyMs),
                        "error_message", safeString(error.getMessage(), 500)
                ), error);
            } else {
                log.error(JsonUtils.json(
                        "saga_event", "step_failed",
                        "saga_name", sagaName,
                        "saga_id", sagaId,
                        "step_id", stepId,
                        "error_message", safeString(error.getMessage(), 500)
                ), error);
            }
            recordEvent("STEP_FAILED", sagaName, sagaId, stepId, 
                String.format("attempts=%d, latency=%dms, error=%s", attempts, latencyMs, error.getMessage()));
        }
    }

    @Override
    public void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) {
        if (config.isEnabled() && config.isLogCompensation()) {
            if (error == null) {
                log.info(JsonUtils.json(
                        "saga_event", "compensated",
                        "saga_name", sagaName,
                        "saga_id", sagaId,
                        "step_id", stepId,
                        "success", "true"
                ));
            } else {
                log.error(JsonUtils.json(
                        "saga_event", "compensated",
                        "saga_name", sagaName,
                        "saga_id", sagaId,
                        "step_id", stepId,
                        "success", "false",
                        "error_message", safeString(error.getMessage(), 500)
                ), error);
            }
            recordEvent("COMPENSATED", sagaName, sagaId, stepId, 
                error != null ? "error=" + error.getMessage() : "success");
        }
    }

    @Override
    public void onStepSkippedIdempotent(String sagaName, String sagaId, String stepId) {
        if (config.isEnabled() && config.isLogStepDetails()) {
            log.info(JsonUtils.json(
                    "saga_event", "step_skipped_idempotent",
                    "saga_name", sagaName,
                    "saga_id", sagaId,
                    "step_id", stepId,
                    "reason", "idempotent"
            ));
            recordEvent("STEP_SKIPPED_IDEMPOTENT", sagaName, sagaId, stepId, null);
        }
    }

    @Override
    public void onCompensationStarted(String sagaName, String sagaId, String stepId) {
        if (config.isEnabled() && config.isLogCompensation()) {
            log.info(JsonUtils.json(
                    "saga_event", "compensation_started",
                    "saga_name", sagaName,
                    "saga_id", sagaId,
                    "step_id", stepId
            ));
            recordEvent("COMPENSATION_STARTED", sagaName, sagaId, stepId, null);
        }
    }

    @Override
    public void onCompensationRetry(String sagaName, String sagaId, String stepId, int attempt) {
        if (config.isEnabled() && config.isLogCompensation()) {
            log.info(JsonUtils.json(
                    "saga_event", "compensation_retry",
                    "saga_name", sagaName,
                    "saga_id", sagaId,
                    "step_id", stepId,
                    "attempt", Integer.toString(attempt)
            ));
            recordEvent("COMPENSATION_RETRY", sagaName, sagaId, stepId, "attempt=" + attempt);
        }
    }

    @Override
    public void onCompensationSkipped(String sagaName, String sagaId, String stepId, String reason) {
        if (config.isEnabled() && config.isLogCompensation()) {
            log.info(JsonUtils.json(
                    "saga_event", "compensation_skipped",
                    "saga_name", sagaName,
                    "saga_id", sagaId,
                    "step_id", stepId,
                    "reason", safeString(reason, 300)
            ));
            recordEvent("COMPENSATION_SKIPPED", sagaName, sagaId, stepId, "reason=" + reason);
        }
    }

    @Override
    public void onCompensationCircuitOpen(String sagaName, String sagaId, String stepId) {
        if (config.isEnabled() && config.isLogCompensation()) {
            log.warn(JsonUtils.json(
                    "saga_event", "compensation_circuit_open",
                    "saga_name", sagaName,
                    "saga_id", sagaId,
                    "step_id", stepId
            ));
            recordEvent("COMPENSATION_CIRCUIT_OPEN", sagaName, sagaId, stepId, null);
        }
    }

    @Override
    public void onCompensationBatchCompleted(String sagaName, String sagaId, List<String> stepIds, boolean allSuccessful) {
        if (config.isEnabled() && config.isLogCompensation()) {
            if (allSuccessful) {
                log.info(JsonUtils.json(
                        "saga_event", "compensation_batch_completed",
                        "saga_name", sagaName,
                        "saga_id", sagaId,
                        "steps_count", Integer.toString(stepIds.size()),
                        "success_all", "true"
                ));
            } else {
                log.warn(JsonUtils.json(
                        "saga_event", "compensation_batch_completed",
                        "saga_name", sagaName,
                        "saga_id", sagaId,
                        "steps_count", Integer.toString(stepIds.size()),
                        "success_all", "false"
                ));
            }
            recordEvent("COMPENSATION_BATCH_COMPLETED", sagaName, sagaId, null, 
                String.format("steps=%d, allSuccessful=%s", stepIds.size(), allSuccessful));
        }
    }

    @Override
    public void onCompleted(String sagaName, String sagaId, boolean success) {
        if (config.isEnabled()) {
            if (success) {
                log.info(JsonUtils.json(
                        "saga_event", "completed",
                        "saga_name", sagaName,
                        "saga_id", sagaId,
                        "success", "true"
                ));
            } else {
                log.error(JsonUtils.json(
                        "saga_event", "completed",
                        "saga_name", sagaName,
                        "saga_id", sagaId,
                        "success", "false"
                ));
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
        log.info(JsonUtils.json(
                "event", "saga_event_history_cleared",
                "component", "InMemorySagaEvents",
                "action", "clear_history"
        ));
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

    private static String safeString(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max)) + "...";
    }
}