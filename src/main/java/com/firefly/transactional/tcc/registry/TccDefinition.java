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

package com.firefly.transactional.tcc.registry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable metadata for a discovered TCC transaction coordinator.
 * <p>
 * Holds the TCC transaction name, the original Spring bean (possibly a proxy),
 * the unwrapped target instance, and an ordered map of its participants.
 * <p>
 * This class is analogous to {@link com.firefly.transactional.registry.SagaDefinition}
 * but for TCC transactions.
 */
public class TccDefinition {
    
    public final String name;
    public final Object bean;
    public final Object target;
    public final long timeoutMs;
    public final boolean retryEnabled;
    public final int maxRetries;
    public final long backoffMs;
    public final Map<String, TccParticipantDefinition> participants = new LinkedHashMap<>();
    
    /**
     * Creates a new TCC definition.
     *
     * @param name the unique name of the TCC transaction
     * @param bean the original Spring bean (possibly a proxy)
     * @param target the unwrapped target object for direct invocation
     * @param timeoutMs the transaction timeout in milliseconds
     * @param retryEnabled whether retry is enabled
     * @param maxRetries the maximum number of retries
     * @param backoffMs the backoff delay in milliseconds
     */
    public TccDefinition(String name, Object bean, Object target, long timeoutMs,
                        boolean retryEnabled, int maxRetries, long backoffMs) {
        this.name = name;
        this.bean = bean;
        this.target = target;
        this.timeoutMs = timeoutMs;
        this.retryEnabled = retryEnabled;
        this.maxRetries = maxRetries;
        this.backoffMs = backoffMs;
    }
    
    /**
     * Adds a participant to this TCC transaction.
     *
     * @param participant the participant definition to add
     */
    public void addParticipant(TccParticipantDefinition participant) {
        participants.put(participant.id, participant);
    }
    
    /**
     * Gets a participant by ID.
     *
     * @param participantId the participant identifier
     * @return the participant definition, or null if not found
     */
    public TccParticipantDefinition getParticipant(String participantId) {
        return participants.get(participantId);
    }
    
    /**
     * Gets all participants in execution order.
     *
     * @return a map of participant IDs to their definitions
     */
    public Map<String, TccParticipantDefinition> getParticipants() {
        return participants;
    }
    
    @Override
    public String toString() {
        return "TccDefinition{" +
                "name='" + name + '\'' +
                ", participants=" + participants.size() +
                ", timeoutMs=" + timeoutMs +
                ", retryEnabled=" + retryEnabled +
                '}';
    }
}

