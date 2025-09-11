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


package com.firefly.transactional.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable event envelope containing saga step completion data.
 * 
 * Events are published only when a saga completes successfully without compensations.
 * The microservice's StepEventPublisher implementation determines how to serialize
 * and route this data to the appropriate messaging infrastructure.
 */
@Data
public class StepEventEnvelope {
    
    private String sagaName;
    private String sagaId;
    private String stepId;
    private String topic;
    private String type;
    private String key;
    private Object payload;
    private Map<String, String> headers;
    private Instant timestamp;

    // New useful metadata
    private Integer attempts;     // how many attempts the step took
    private Long latencyMs;       // step latency in milliseconds
    private Instant startedAt;    // when the step started
    private Instant completedAt;  // when the step completed/published
    private String resultType;    // class name of payload (if any)


    public StepEventEnvelope(String sagaName, String sagaId, String stepId,
                             String topic, String type, String key,
                             Object payload, Map<String, String> headers,
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
        this.headers = Map.copyOf(headers != null ? headers : Map.of());
        this.timestamp = Instant.now();
        this.attempts = attempts;
        this.latencyMs = latencyMs;
        this.startedAt = startedAt;
        this.completedAt = (completedAt != null ? completedAt : this.timestamp);
        this.resultType = resultType;

    }

    @Override
    public String toString() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            return mapper.writeValueAsString(this);
        } catch (Exception e) {
            // Fallback to basic representation if JSON serialization fails
            return String.format("StepEvent{saga=%s, sagaId=%s, step=%s, topic=%s, type=%s, key=%s, payload=%s, headers=%s, timestamp=%s}",
                    sagaName, sagaId, stepId, topic, type, key, payload, headers, timestamp);
        }
    }
}
