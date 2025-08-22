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

    public StepEventEnvelope(String sagaName, String sagaId, String stepId,
                             String topic, String type, String key,
                             Object payload, Map<String, String> headers,
                             Instant timestamp) {
        this.sagaName = sagaName;
        this.sagaId = sagaId;
        this.stepId = stepId;
        this.topic = topic;
        this.type = type;
        this.key = key;
        this.payload = payload;
        this.headers = headers;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
    }
}
