package com.catalis.transactionalengine.events;

import java.time.Instant;
import java.util.Map;

/**
 * Transport-agnostic event describing a completed saga step, emitted only when the saga
 * completes successfully without compensations.
 */
public class StepEventEnvelope {
    public final String sagaName;
    public final String sagaId;
    public final String stepId;
    public final String topic;
    public final String type;
    public final String key;
    public final Object payload; // typically the step result
    public final Map<String, String> headers; // snapshot of context headers
    public final Instant timestamp;

    // New useful metadata
    public final Integer attempts;     // how many attempts the step took
    public final Long latencyMs;       // step latency in milliseconds
    public final Instant startedAt;    // when the step started
    public final Instant completedAt;  // when the step completed/published
    public final String resultType;    // class name of payload (if any)

    /**
     * Legacy constructor (kept for binary compatibility inside the library usage).
     * New fields will be derived as null/defaults by publishers/consumers if needed.
     */
    public StepEventEnvelope(String sagaName, String sagaId, String stepId,
                             String topic, String type, String key,
                             Object payload, Map<String, String> headers,
                             Instant timestamp) {
        this(sagaName, sagaId, stepId, topic, type, key, payload, headers, timestamp,
                null, null, null, null, (payload != null ? payload.getClass().getName() : null));
    }

    public StepEventEnvelope(String sagaName, String sagaId, String stepId,
                             String topic, String type, String key,
                             Object payload, Map<String, String> headers,
                             Instant timestamp,
                             Integer attempts, Long latencyMs,
                             Instant startedAt, Instant completedAt,
                             String resultType) {
        this.sagaName = sagaName;
        this.sagaId = sagaId;
        this.stepId = stepId;
        this.topic = topic;
        this.type = type;
        this.key = key;
        this.payload = payload;
        this.headers = headers;
        this.timestamp = (timestamp != null ? timestamp : Instant.now());
        this.attempts = attempts;
        this.latencyMs = latencyMs;
        this.startedAt = startedAt;
        this.completedAt = (completedAt != null ? completedAt : this.timestamp);
        this.resultType = (resultType != null ? resultType : (payload != null ? payload.getClass().getName() : null));
    }

    @Override
    public String toString() {
        // Compact JSON-like representation for logging/bridging; avoid printing payload
        StringBuilder sb = new StringBuilder(256);
        sb.append('{')
          .append("\"saga\":\"").append(sagaName).append('\"')
          .append(',').append("\"sagaId\":\"").append(sagaId).append('\"')
          .append(',').append("\"stepId\":\"").append(stepId).append('\"')
          .append(',').append("\"topic\":\"").append(topic).append('\"')
          .append(',').append("\"type\":\"").append(type).append('\"')
          .append(',').append("\"key\":\"").append(key).append('\"')
          .append(',').append("\"resultType\":\"").append(resultType).append('\"')
          .append(',').append("\"attempts\":").append(attempts)
          .append(',').append("\"latencyMs\":").append(latencyMs)
          .append(',').append("\"startedAt\":\"").append(startedAt).append('\"')
          .append(',').append("\"completedAt\":\"").append(completedAt).append('\"')
          .append(',').append("\"timestamp\":\"").append(timestamp).append('\"')
          .append('}');
        return sb.toString();
    }
}
