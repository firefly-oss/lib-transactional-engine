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

package com.firefly.transactional.tcc.engine;


import com.firefly.transactional.tcc.core.TccContext;
import com.firefly.transactional.tcc.core.TccPhase;
import com.firefly.transactional.tcc.registry.TccParticipantDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Invokes TCC participant methods (try, confirm, cancel) with retry, timeout, and backoff.
 * <p>
 * This class uses the {@link TccArgumentResolver} for TCC-specific parameter resolution,
 * ensuring complete isolation from SAGA infrastructure.
 */
public class TccParticipantInvoker {
    
    private static final Logger log = LoggerFactory.getLogger(TccParticipantInvoker.class);
    
    private final TccArgumentResolver argumentResolver;
    
    /**
     * Creates a new TCC participant invoker.
     *
     * @param argumentResolver the TCC argument resolver for method parameters
     */
    public TccParticipantInvoker(TccArgumentResolver argumentResolver) {
        this.argumentResolver = argumentResolver;
    }
    
    /**
     * Invokes the try method of a participant.
     *
     * @param participant the participant definition
     * @param input the input for the try method
     * @param context the TCC context
     * @return a Mono containing the try result
     */
    public Mono<Object> invokeTry(TccParticipantDefinition participant, Object input, TccContext context) {
        long timeoutMs = participant.getEffectiveTryTimeout(30000L);
        int retry = participant.getEffectiveTryRetry(0);
        long backoffMs = participant.getEffectiveTryBackoff(100L);
        
        return attemptCall(
                participant.target,
                participant.tryMethod,
                input,
                context,
                timeoutMs,
                retry,
                backoffMs,
                participant.id,
                TccPhase.TRY
        );
    }
    
    /**
     * Invokes the confirm method of a participant.
     *
     * @param participant the participant definition
     * @param tryResult the result from the try phase
     * @param context the TCC context
     * @return a Mono that completes when confirm is done
     */
    public Mono<Void> invokeConfirm(TccParticipantDefinition participant, Object tryResult, TccContext context) {
        long timeoutMs = participant.getEffectiveConfirmTimeout(30000L);
        int retry = participant.getEffectiveConfirmRetry(3); // Confirm should retry by default
        long backoffMs = participant.getEffectiveConfirmBackoff(100L);
        
        return attemptCall(
                participant.target,
                participant.confirmMethod,
                tryResult,
                context,
                timeoutMs,
                retry,
                backoffMs,
                participant.id,
                TccPhase.CONFIRM,
                tryResult
        ).then();
    }
    
    /**
     * Invokes the cancel method of a participant.
     *
     * @param participant the participant definition
     * @param tryResult the result from the try phase
     * @param context the TCC context
     * @return a Mono that completes when cancel is done
     */
    public Mono<Void> invokeCancel(TccParticipantDefinition participant, Object tryResult, TccContext context) {
        long timeoutMs = participant.getEffectiveCancelTimeout(30000L);
        int retry = participant.getEffectiveCancelRetry(3); // Cancel should retry by default
        long backoffMs = participant.getEffectiveCancelBackoff(100L);
        
        return attemptCall(
                participant.target,
                participant.cancelMethod,
                tryResult,
                context,
                timeoutMs,
                retry,
                backoffMs,
                participant.id,
                TccPhase.CANCEL,
                tryResult
        ).then();
    }
    
    /**
     * Attempts to call a method with retry, timeout, and backoff.
     */
    private Mono<Object> attemptCall(Object bean, Method method, Object input, TccContext context,
                                     long timeoutMs, int retry, long backoffMs,
                                     String participantId, TccPhase phase) {
        return attemptCall(bean, method, input, context, timeoutMs, retry, backoffMs, participantId, phase, null);
    }

    /**
     * Attempts to call a method with retry, timeout, and backoff, with participant-specific try result.
     */
    private Mono<Object> attemptCall(Object bean, Method method, Object input, TccContext context,
                                     long timeoutMs, int retry, long backoffMs,
                                     String participantId, TccPhase phase, Object participantTryResult) {
        return Mono.defer(() -> invokeMono(bean, method, input, context, participantTryResult))
                .transform(m -> timeoutMs > 0 ? m.timeout(Duration.ofMillis(timeoutMs)) : m)
                .onErrorResume(err -> {
                    if (retry > 0) {
                        long delay = computeDelay(backoffMs);
                        log.debug("Retrying {} phase for participant '{}' after {}ms (retries left: {})",
                                phase, participantId, delay, retry);
                        return Mono.delay(Duration.ofMillis(Math.max(0, delay)))
                                .then(attemptCall(bean, method, input, context, timeoutMs,
                                        retry - 1, backoffMs, participantId, phase, participantTryResult));
                    }
                    log.error("Failed to execute {} phase for participant '{}' after all retries",
                            phase, participantId, err);
                    return Mono.error(err);
                })
                .doFirst(() -> {
                    log.debug("Invoking {} phase for participant '{}'", phase, participantId);
                    context.getSagaContext().incrementAttempts(participantId);
                });
    }
    
    /**
     * Invokes a method and returns a Mono.
     */
    @SuppressWarnings("unchecked")
    private Mono<Object> invokeMono(Object bean, Method method, Object input, TccContext context) {
        return invokeMono(bean, method, input, context, null);
    }

    @SuppressWarnings("unchecked")
    private Mono<Object> invokeMono(Object bean, Method method, Object input, TccContext context, Object participantTryResult) {
        try {
            // Resolve arguments using the TCC argument resolver
            // This allows TCC methods to use @FromTry, @Header, @Input, etc.
            Object[] args = argumentResolver.resolveArguments(method, input, context, participantTryResult);

            // Invoke the method
            Object result = method.invoke(bean, args);
            
            // Handle Mono return types
            if (result instanceof Mono<?> mono) {
                return (Mono<Object>) mono;
            }
            
            // Handle non-Mono return types
            return Mono.justOrEmpty(result);
        } catch (InvocationTargetException e) {
            return Mono.error(e.getTargetException());
        } catch (Throwable t) {
            return Mono.error(t);
        }
    }
    
    /**
     * Computes the delay for retry with optional jitter.
     */
    private static long computeDelay(long backoffMs) {
        if (backoffMs <= 0) {
            return 0L;
        }
        
        // Add simple jitter (±20%)
        double jitterFactor = 0.2;
        double min = backoffMs * (1.0 - jitterFactor);
        double max = backoffMs * (1.0 + jitterFactor);
        long delay = Math.round(ThreadLocalRandom.current().nextDouble(min, max));
        
        return Math.max(0L, delay);
    }
}

