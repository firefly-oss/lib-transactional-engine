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

package com.firefly.transactional.saga.persistence.serialization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.firefly.transactional.saga.persistence.SagaExecutionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JSON-based implementation of SagaStateSerializer using Jackson.
 * <p>
 * This implementation provides human-readable serialization that is useful for
 * debugging and development. It handles Java 8 time types and maintains
 * compatibility across different versions.
 * <p>
 * Features:
 * <ul>
 *   <li>Human-readable JSON format</li>
 *   <li>Support for Java 8 time types</li>
 *   <li>Configurable ObjectMapper for customization</li>
 *   <li>Version compatibility tracking</li>
 * </ul>
 */
public class JsonSagaStateSerializer implements SagaStateSerializer {

    private static final Logger log = LoggerFactory.getLogger(JsonSagaStateSerializer.class);
    
    private static final String CONTENT_TYPE = "application/json";
    private static final String VERSION = "1.0";
    
    private final ObjectMapper objectMapper;

    /**
     * Creates a new JSON serializer with default ObjectMapper configuration.
     */
    public JsonSagaStateSerializer() {
        this(createDefaultObjectMapper());
    }

    /**
     * Creates a new JSON serializer with a custom ObjectMapper.
     *
     * @param objectMapper the ObjectMapper to use for serialization
     */
    public JsonSagaStateSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public byte[] serialize(SagaExecutionState state) throws SerializationException {
        try {
            log.debug("Serializing saga execution state for correlation ID: {}", state.getCorrelationId());
            
            // Create a serializable wrapper that includes metadata
            SerializableStateWrapper wrapper = new SerializableStateWrapper(state, CONTENT_TYPE, VERSION);
            
            String json = objectMapper.writeValueAsString(wrapper);
            return json.getBytes(StandardCharsets.UTF_8);
            
        } catch (JsonProcessingException e) {
            String message = String.format("Failed to serialize saga state for correlation ID: %s", 
                    state.getCorrelationId());
            log.error(message, e);
            throw new SerializationException(message, e);
        }
    }

    @Override
    public SagaExecutionState deserialize(byte[] data) throws SerializationException {
        try {
            String json = new String(data, StandardCharsets.UTF_8);
            log.debug("Deserializing saga execution state from JSON: {} bytes", data.length);
            
            SerializableStateWrapper wrapper = objectMapper.readValue(json, SerializableStateWrapper.class);
            
            // Validate compatibility
            if (!canDeserialize(wrapper.getContentType(), wrapper.getVersion())) {
                throw new SerializationException(String.format(
                        "Incompatible serialization format: %s version %s", 
                        wrapper.getContentType(), wrapper.getVersion()));
            }
            
            return wrapper.getState();
            
        } catch (IOException e) {
            String message = "Failed to deserialize saga state from JSON";
            log.error(message, e);
            throw new SerializationException(message, e);
        }
    }

    @Override
    public String getContentType() {
        return CONTENT_TYPE;
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public boolean canDeserialize(String contentType, String version) {
        // For now, we only support exact version match
        // In the future, we could implement backward compatibility logic
        return CONTENT_TYPE.equals(contentType) && VERSION.equals(version);
    }

    /**
     * Creates a default ObjectMapper configured for saga state serialization.
     */
    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        
        // Register Java 8 time module for proper Instant/Duration serialization
        mapper.registerModule(new JavaTimeModule());
        
        // Configure serialization features
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT); // Pretty print for readability
        
        // Configure deserialization features
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        return mapper;
    }

    /**
     * Wrapper class that includes metadata along with the saga state.
     * This allows for version tracking and compatibility checks.
     */
    private static class SerializableStateWrapper {
        private String contentType;
        private String version;
        private SagaExecutionState state;

        // Default constructor for Jackson
        public SerializableStateWrapper() {}

        public SerializableStateWrapper(SagaExecutionState state, String contentType, String version) {
            this.state = state;
            this.contentType = contentType;
            this.version = version;
        }

        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }

        public SagaExecutionState getState() { return state; }
        public void setState(SagaExecutionState state) { this.state = state; }
    }
}
