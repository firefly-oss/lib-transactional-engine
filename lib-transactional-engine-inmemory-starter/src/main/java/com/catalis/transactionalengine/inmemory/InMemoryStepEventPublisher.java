package com.catalis.transactionalengine.inmemory;

import com.catalis.transactionalengine.events.StepEventEnvelope;
import com.catalis.transactionalengine.util.JsonUtils;
import com.catalis.transactionalengine.events.StepEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory implementation of StepEventPublisher for development and testing purposes.
 * 
 * This implementation provides:
 * - Enhanced logging with emojis for better visibility during development
 * - In-memory storage of published events for debugging
 * - Thread-safe event handling
 * - Configurable logging levels and event storage limits
 * 
 * Perfect for:
 * - Development environments where external message queues aren't needed
 * - Testing scenarios requiring event verification
 * - Getting started quickly without external dependencies
 * - Debugging saga execution flow
 * 
 * Features:
 * - Rich console output with emojis and formatting
 * - Event history accessible for testing/debugging
 * - Configurable maximum events to keep in memory
 * - Thread-safe operations
 * - Non-blocking reactive implementation
 */
public class InMemoryStepEventPublisher implements StepEventPublisher {
    
    private static final Logger log = LoggerFactory.getLogger(InMemoryStepEventPublisher.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    
    private final InMemoryTransactionalEngineProperties.EventsProperties properties;
    private final ConcurrentLinkedQueue<StepEventRecord> eventHistory;
    private final AtomicLong eventCounter = new AtomicLong();
    
    public InMemoryStepEventPublisher(InMemoryTransactionalEngineProperties.EventsProperties properties) {
        this.properties = properties;
        this.eventHistory = new ConcurrentLinkedQueue<>();
        log.info(JsonUtils.json(
                "event", "step_event_publisher_initialized",
                "type", "inmemory",
                "max_events_in_memory", Integer.toString(properties.getMaxEventsInMemory()),
                "log_step_details", Boolean.toString(properties.isLogStepDetails())
        ));
    }

    @Override
    public Mono<Void> publish(StepEventEnvelope event) {
        return Mono.fromRunnable(() -> {
            long eventNumber = eventCounter.incrementAndGet();
            Instant timestamp = Instant.now();
            
            // Create event record for history
            StepEventRecord record = new StepEventRecord(eventNumber, timestamp, event);
            addToHistory(record);
            
            // Log the event with rich formatting
            logStepEvent(record);
            
        }).then();
    }
    
    private void addToHistory(StepEventRecord record) {
        eventHistory.offer(record);
        
        // Maintain size limit
        while (eventHistory.size() > properties.getMaxEventsInMemory()) {
            StepEventRecord removed = eventHistory.poll();
            if (removed != null) {
                log.trace(JsonUtils.json(
                        "event", "old_event_removed_from_history",
                        "event_number", Long.toString(removed.eventNumber)
                ));
            }
        }
    }
    
    private void logStepEvent(StepEventRecord record) {
        StepEventEnvelope event = record.envelope;
        String timeStr = TIME_FORMATTER.format(record.timestamp);
        
        if (properties.isLogStepDetails()) {
            // Detailed JSON log with all event information
            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append(JsonUtils.json(
                    "event", "step_event_published",
                    "event_number", Long.toString(record.eventNumber),
                    "timestamp", timeStr,
                    "saga_name", event.sagaName,
                    "saga_id", event.sagaId,
                    "step_id", event.stepId,
                    "topic", event.topic,
                    "event_type", event.type,
                    "event_key", event.key,
                    "event_timestamp", event.timestamp != null ? event.timestamp.toString() : "",
                    "result_type", event.resultType != null ? event.resultType : "",
                    "payload_class", event.payload != null ? event.payload.getClass().getSimpleName() : "",
                    "attempts", event.attempts != null ? event.attempts.toString() : "",
                    "latency_ms", event.latencyMs != null ? event.latencyMs.toString() : "",
                    "started_at", event.startedAt != null ? event.startedAt.toString() : "",
                    "completed_at", event.completedAt != null ? event.completedAt.toString() : "",
                    "headers_count", event.headers != null ? Integer.toString(event.headers.size()) : "0"
            ));
            log.info(jsonBuilder.toString());
        } else {
            // Basic JSON log with essential information
            log.info(JsonUtils.json(
                    "event", "step_event_published",
                    "event_number", Long.toString(record.eventNumber),
                    "timestamp", timeStr,
                    "saga_name", event.sagaName,
                    "saga_id", event.sagaId,
                    "step_id", event.stepId,
                    "topic", event.topic,
                    "event_type", event.type,
                    "event_key", event.key
            ));
        }
    }
    
    /**
     * Get the current event history for testing/debugging purposes.
     * Returns a copy to avoid external modifications.
     */
    public List<StepEventRecord> getEventHistory() {
        return List.copyOf(eventHistory);
    }
    
    /**
     * Get the total number of events published since startup.
     */
    public long getTotalEventCount() {
        return eventCounter.get();
    }
    
    /**
     * Clear the event history (useful for testing).
     */
    public void clearHistory() {
        eventHistory.clear();
        log.info(JsonUtils.json(
                "event", "event_history_cleared"
        ));
    }
    
    /**
     * Get events for a specific saga ID.
     */
    public List<StepEventRecord> getEventsForSaga(String sagaId) {
        return eventHistory.stream()
                .filter(record -> sagaId.equals(record.envelope.sagaId))
                .toList();
    }
    
    /**
     * Get events for a specific saga name.
     */
    public List<StepEventRecord> getEventsForSagaName(String sagaName) {
        return eventHistory.stream()
                .filter(record -> sagaName.equals(record.envelope.sagaName))
                .toList();
    }
    
    /**
     * Record of a published step event with metadata.
     */
    public static class StepEventRecord {
        private final long eventNumber;
        private final Instant timestamp;
        private final StepEventEnvelope envelope;
        
        public StepEventRecord(long eventNumber, Instant timestamp, StepEventEnvelope envelope) {
            this.eventNumber = eventNumber;
            this.timestamp = timestamp;
            this.envelope = envelope;
        }
        
        public long getEventNumber() { return eventNumber; }
        public Instant getTimestamp() { return timestamp; }
        public StepEventEnvelope getEnvelope() { return envelope; }
        
        @Override
        public String toString() {
            return String.format("StepEventRecord{#%d, %s, %s->%s, %s}", 
                eventNumber,
                TIME_FORMATTER.format(timestamp),
                envelope.sagaName,
                envelope.stepId,
                envelope.type
            );
        }
    }

    private static String safeString(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max)) + "...";
    }
}