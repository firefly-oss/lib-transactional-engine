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

package com.firefly.transactional.tcc.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Container for TCC participant inputs.
 * <p>
 * This class holds the input data for each participant in a TCC transaction.
 * It provides a fluent builder API for constructing inputs.
 * <p>
 * Example usage:
 * <pre>{@code
 * TccInputs inputs = TccInputs.builder()
 *     .forParticipant("payment", paymentRequest)
 *     .forParticipant("inventory", inventoryRequest)
 *     .build();
 * }</pre>
 */
public class TccInputs {
    
    private final Map<String, Object> participantInputs;
    
    private TccInputs(Map<String, Object> participantInputs) {
        this.participantInputs = Collections.unmodifiableMap(participantInputs);
    }
    
    /**
     * Gets the input for a specific participant.
     *
     * @param participantId the participant identifier
     * @return the input object, or null if not found
     */
    public Object getInput(String participantId) {
        return participantInputs.get(participantId);
    }
    
    /**
     * Gets the input for a specific participant with type casting.
     *
     * @param participantId the participant identifier
     * @param inputType the expected input type
     * @param <T> the input type
     * @return the input object, or null if not found or wrong type
     */
    @SuppressWarnings("unchecked")
    public <T> T getInput(String participantId, Class<T> inputType) {
        Object input = participantInputs.get(participantId);
        if (input != null && inputType.isInstance(input)) {
            return (T) input;
        }
        return null;
    }
    
    /**
     * Gets all participant inputs.
     *
     * @return an unmodifiable map of participant IDs to their inputs
     */
    public Map<String, Object> getAllInputs() {
        return participantInputs;
    }
    
    /**
     * Checks if an input exists for the given participant.
     *
     * @param participantId the participant identifier
     * @return true if an input exists, false otherwise
     */
    public boolean hasInput(String participantId) {
        return participantInputs.containsKey(participantId);
    }
    
    /**
     * Creates an empty TccInputs instance.
     *
     * @return an empty TccInputs
     */
    public static TccInputs empty() {
        return new TccInputs(Collections.emptyMap());
    }
    
    /**
     * Creates a TccInputs instance with a single participant input.
     *
     * @param participantId the participant identifier
     * @param input the input object
     * @return a TccInputs instance
     */
    public static TccInputs of(String participantId, Object input) {
        return builder().forParticipant(participantId, input).build();
    }
    
    /**
     * Creates a new builder for TccInputs.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for creating TccInputs instances.
     */
    public static class Builder {
        private final Map<String, Object> participantInputs = new HashMap<>();
        
        /**
         * Adds an input for a participant.
         *
         * @param participantId the participant identifier
         * @param input the input object
         * @return this builder
         */
        public Builder forParticipant(String participantId, Object input) {
            Objects.requireNonNull(participantId, "participantId cannot be null");
            participantInputs.put(participantId, input);
            return this;
        }
        
        /**
         * Adds multiple participant inputs from a map.
         *
         * @param inputs a map of participant IDs to their inputs
         * @return this builder
         */
        public Builder withInputs(Map<String, Object> inputs) {
            if (inputs != null) {
                participantInputs.putAll(inputs);
            }
            return this;
        }
        
        /**
         * Builds the TccInputs instance.
         *
         * @return a new TccInputs instance
         */
        public TccInputs build() {
            return new TccInputs(new HashMap<>(participantInputs));
        }
    }
    
    @Override
    public String toString() {
        return "TccInputs{" +
                "participants=" + participantInputs.keySet() +
                '}';
    }
}

