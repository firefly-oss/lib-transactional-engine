package com.catalis.transactionalengine.events;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable event envelope containing saga step completion data.
 * 
 * Events are published only when a saga completes successfully without compensations.
 * The microservice's StepEventPublisher implementation determines how to serialize
 * and route this data to the appropriate messaging infrastructure.
 */
public class StepEventEnvelope {
    
    public final String sagaName;
    public final String sagaId;
    public final String stepId;
    public final String topic;
    public final String type;
    public final String key;
    public final Object payload;
    public final Map<String, String> headers;
    public final Instant timestamp;
    
    public StepEventEnvelope(String sagaName, String sagaId, String stepId,
                             String topic, String type, String key,
                             Object payload, Map<String, String> headers) {
        this.sagaName = sagaName;
        this.sagaId = sagaId;
        this.stepId = stepId;
        this.topic = topic;
        this.type = type;
        this.key = key;
        this.payload = payload;
        this.headers = Map.copyOf(headers != null ? headers : Map.of());
        this.timestamp = Instant.now();
    }
    
    @Override
    public String toString() {
        return String.format("StepEvent{saga=%s, sagaId=%s, step=%s, topic=%s, type=%s}", 
                sagaName, sagaId, stepId, topic, type);
    }
}
