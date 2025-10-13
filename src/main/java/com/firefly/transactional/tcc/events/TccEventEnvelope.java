/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.firefly.transactional.tcc.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.firefly.transactional.tcc.core.TccPhase;

import java.time.Instant;
import java.util.Map;

/**
 * Envelope for TCC transaction events published to external messaging systems.
 * 
 * <p>This class provides a standardized structure for TCC events that can be
 * serialized and published to various messaging infrastructures. It includes
 * all necessary metadata for event processing and correlation.
 * 
 * <p>The envelope supports different event types:
 * <ul>
 *   <li>TCC_STARTED - Transaction started</li>
 *   <li>TCC_COMPLETED - Transaction completed (success or failure)</li>
 *   <li>PHASE_STARTED - Phase started (TRY, CONFIRM, CANCEL)</li>
 *   <li>PHASE_COMPLETED - Phase completed successfully</li>
 *   <li>PHASE_FAILED - Phase failed</li>
 *   <li>PARTICIPANT_STARTED - Participant method started</li>
 *   <li>PARTICIPANT_SUCCESS - Participant method succeeded</li>
 *   <li>PARTICIPANT_FAILED - Participant method failed</li>
 *   <li>PARTICIPANT_RETRY - Participant method retried</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TccEventEnvelope {
    
    private final String tccName;
    private final String correlationId;
    private final String participantId;
    private final String topic;
    private final String type;
    private final String key;
    private final Object payload;
    private final Map<String, String> headers;
    private final Instant timestamp;
    private final TccPhase phase;
    private final Integer attempts;
    private final Long durationMs;
    private final Instant startedAt;
    private final Instant completedAt;
    private final String errorClass;
    private final String errorMessage;
    private final Boolean success;
    
    private TccEventEnvelope(Builder builder) {
        this.tccName = builder.tccName;
        this.correlationId = builder.correlationId;
        this.participantId = builder.participantId;
        this.topic = builder.topic;
        this.type = builder.type;
        this.key = builder.key;
        this.payload = builder.payload;
        this.headers = builder.headers;
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.phase = builder.phase;
        this.attempts = builder.attempts;
        this.durationMs = builder.durationMs;
        this.startedAt = builder.startedAt;
        this.completedAt = builder.completedAt;
        this.errorClass = builder.errorClass;
        this.errorMessage = builder.errorMessage;
        this.success = builder.success;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    // Getters
    public String getTccName() { return tccName; }
    public String getCorrelationId() { return correlationId; }
    public String getParticipantId() { return participantId; }
    public String getTopic() { return topic; }
    public String getType() { return type; }
    public String getKey() { return key; }
    public Object getPayload() { return payload; }
    public Map<String, String> getHeaders() { return headers; }
    public Instant getTimestamp() { return timestamp; }
    public TccPhase getPhase() { return phase; }
    public Integer getAttempts() { return attempts; }
    public Long getDurationMs() { return durationMs; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getErrorClass() { return errorClass; }
    public String getErrorMessage() { return errorMessage; }
    public Boolean getSuccess() { return success; }
    
    public static class Builder {
        private String tccName;
        private String correlationId;
        private String participantId;
        private String topic;
        private String type;
        private String key;
        private Object payload;
        private Map<String, String> headers;
        private Instant timestamp;
        private TccPhase phase;
        private Integer attempts;
        private Long durationMs;
        private Instant startedAt;
        private Instant completedAt;
        private String errorClass;
        private String errorMessage;
        private Boolean success;
        
        public Builder tccName(String tccName) { this.tccName = tccName; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }
        public Builder participantId(String participantId) { this.participantId = participantId; return this; }
        public Builder topic(String topic) { this.topic = topic; return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder key(String key) { this.key = key; return this; }
        public Builder payload(Object payload) { this.payload = payload; return this; }
        public Builder headers(Map<String, String> headers) { this.headers = headers; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder phase(TccPhase phase) { this.phase = phase; return this; }
        public Builder attempts(Integer attempts) { this.attempts = attempts; return this; }
        public Builder durationMs(Long durationMs) { this.durationMs = durationMs; return this; }
        public Builder startedAt(Instant startedAt) { this.startedAt = startedAt; return this; }
        public Builder completedAt(Instant completedAt) { this.completedAt = completedAt; return this; }
        public Builder error(Throwable error) {
            if (error != null) {
                this.errorClass = error.getClass().getSimpleName();
                this.errorMessage = error.getMessage();
            }
            return this;
        }
        public Builder success(Boolean success) { this.success = success; return this; }
        
        public TccEventEnvelope build() {
            return new TccEventEnvelope(this);
        }
    }
}
