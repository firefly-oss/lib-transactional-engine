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

import java.lang.reflect.Method;

/**
 * Metadata for a TCC participant.
 * <p>
 * Contains information about the participant's try, confirm, and cancel methods,
 * along with configuration such as timeout, retry settings, and execution order.
 */
public class TccParticipantDefinition {
    
    public final String id;
    public final int order;
    public final long timeoutMs;
    public final boolean optional;
    public final Object bean;
    public final Object target;
    
    // Try phase
    public final Method tryMethod;
    public final long tryTimeoutMs;
    public final int tryRetry;
    public final long tryBackoffMs;
    
    // Confirm phase
    public final Method confirmMethod;
    public final long confirmTimeoutMs;
    public final int confirmRetry;
    public final long confirmBackoffMs;
    
    // Cancel phase
    public final Method cancelMethod;
    public final long cancelTimeoutMs;
    public final int cancelRetry;
    public final long cancelBackoffMs;

    // Optional event publication configuration
    public TccEventConfig tccEvent;
    
    /**
     * Creates a new TCC participant definition.
     */
    public TccParticipantDefinition(
            String id,
            int order,
            long timeoutMs,
            boolean optional,
            Object bean,
            Object target,
            Method tryMethod,
            long tryTimeoutMs,
            int tryRetry,
            long tryBackoffMs,
            Method confirmMethod,
            long confirmTimeoutMs,
            int confirmRetry,
            long confirmBackoffMs,
            Method cancelMethod,
            long cancelTimeoutMs,
            int cancelRetry,
            long cancelBackoffMs) {
        
        this.id = id;
        this.order = order;
        this.timeoutMs = timeoutMs;
        this.optional = optional;
        this.bean = bean;
        this.target = target;
        
        this.tryMethod = tryMethod;
        this.tryTimeoutMs = tryTimeoutMs;
        this.tryRetry = tryRetry;
        this.tryBackoffMs = tryBackoffMs;
        
        this.confirmMethod = confirmMethod;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.confirmRetry = confirmRetry;
        this.confirmBackoffMs = confirmBackoffMs;
        
        this.cancelMethod = cancelMethod;
        this.cancelTimeoutMs = cancelTimeoutMs;
        this.cancelRetry = cancelRetry;
        this.cancelBackoffMs = cancelBackoffMs;
    }
    
    /**
     * Gets the effective timeout for the try phase.
     *
     * @param defaultTimeout the default timeout to use if not specified
     * @return the effective timeout in milliseconds
     */
    public long getEffectiveTryTimeout(long defaultTimeout) {
        if (tryTimeoutMs > 0) return tryTimeoutMs;
        if (timeoutMs > 0) return timeoutMs;
        return defaultTimeout;
    }
    
    /**
     * Gets the effective timeout for the confirm phase.
     *
     * @param defaultTimeout the default timeout to use if not specified
     * @return the effective timeout in milliseconds
     */
    public long getEffectiveConfirmTimeout(long defaultTimeout) {
        if (confirmTimeoutMs > 0) return confirmTimeoutMs;
        if (timeoutMs > 0) return timeoutMs;
        return defaultTimeout;
    }
    
    /**
     * Gets the effective timeout for the cancel phase.
     *
     * @param defaultTimeout the default timeout to use if not specified
     * @return the effective timeout in milliseconds
     */
    public long getEffectiveCancelTimeout(long defaultTimeout) {
        if (cancelTimeoutMs > 0) return cancelTimeoutMs;
        if (timeoutMs > 0) return timeoutMs;
        return defaultTimeout;
    }
    
    /**
     * Gets the effective retry count for the try phase.
     *
     * @param defaultRetry the default retry count to use if not specified
     * @return the effective retry count
     */
    public int getEffectiveTryRetry(int defaultRetry) {
        return tryRetry >= 0 ? tryRetry : defaultRetry;
    }
    
    /**
     * Gets the effective retry count for the confirm phase.
     *
     * @param defaultRetry the default retry count to use if not specified
     * @return the effective retry count
     */
    public int getEffectiveConfirmRetry(int defaultRetry) {
        return confirmRetry >= 0 ? confirmRetry : defaultRetry;
    }
    
    /**
     * Gets the effective retry count for the cancel phase.
     *
     * @param defaultRetry the default retry count to use if not specified
     * @return the effective retry count
     */
    public int getEffectiveCancelRetry(int defaultRetry) {
        return cancelRetry >= 0 ? cancelRetry : defaultRetry;
    }
    
    /**
     * Gets the effective backoff delay for the try phase.
     *
     * @param defaultBackoff the default backoff to use if not specified
     * @return the effective backoff in milliseconds
     */
    public long getEffectiveTryBackoff(long defaultBackoff) {
        return tryBackoffMs >= 0 ? tryBackoffMs : defaultBackoff;
    }
    
    /**
     * Gets the effective backoff delay for the confirm phase.
     *
     * @param defaultBackoff the default backoff to use if not specified
     * @return the effective backoff in milliseconds
     */
    public long getEffectiveConfirmBackoff(long defaultBackoff) {
        return confirmBackoffMs >= 0 ? confirmBackoffMs : defaultBackoff;
    }
    
    /**
     * Gets the effective backoff delay for the cancel phase.
     *
     * @param defaultBackoff the default backoff to use if not specified
     * @return the effective backoff in milliseconds
     */
    public long getEffectiveCancelBackoff(long defaultBackoff) {
        return cancelBackoffMs >= 0 ? cancelBackoffMs : defaultBackoff;
    }
    
    @Override
    public String toString() {
        return "TccParticipantDefinition{" +
                "id='" + id + '\'' +
                ", order=" + order +
                ", optional=" + optional +
                ", tryMethod=" + tryMethod.getName() +
                ", confirmMethod=" + confirmMethod.getName() +
                ", cancelMethod=" + cancelMethod.getName() +
                '}';
    }
}

