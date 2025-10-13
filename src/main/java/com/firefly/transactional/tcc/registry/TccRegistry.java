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

import com.firefly.transactional.tcc.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that discovers and indexes TCC transaction coordinators from the Spring context.
 * <p>
 * This class scans for beans annotated with {@link Tcc} and builds metadata
 * about their participants and methods. It provides lookup capabilities for
 * TCC definitions by name.
 * <p>
 * Similar to {@link com.firefly.transactional.registry.SagaRegistry} but for TCC transactions.
 */
public class TccRegistry {
    
    private static final Logger log = LoggerFactory.getLogger(TccRegistry.class);
    
    private final ApplicationContext applicationContext;
    private final Map<String, TccDefinition> tccDefinitions = new ConcurrentHashMap<>();
    
    /**
     * Creates a new TCC registry and scans the application context for TCC coordinators.
     *
     * @param applicationContext the Spring application context
     */
    public TccRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        scanForTccCoordinators();
    }
    
    /**
     * Scans the application context for beans annotated with @Tcc.
     */
    private void scanForTccCoordinators() {
        log.info("Scanning for TCC transaction coordinators...");
        
        Map<String, Object> tccBeans = applicationContext.getBeansWithAnnotation(Tcc.class);
        
        for (Map.Entry<String, Object> entry : tccBeans.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();
            
            try {
                registerTccCoordinator(beanName, bean);
            } catch (Exception e) {
                log.error("Failed to register TCC coordinator: {}", beanName, e);
                throw new IllegalStateException("Failed to register TCC coordinator: " + beanName, e);
            }
        }
        
        log.info("Registered {} TCC transaction coordinator(s): {}", 
                tccDefinitions.size(), tccDefinitions.keySet());
    }
    
    /**
     * Registers a single TCC coordinator bean.
     */
    private void registerTccCoordinator(String beanName, Object bean) {
        // Unwrap proxy to get the actual target class
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
        Object target = AopProxyUtils.getSingletonTarget(bean);
        if (target == null) {
            target = bean;
        }
        
        Tcc tccAnnotation = AnnotationUtils.findAnnotation(targetClass, Tcc.class);
        if (tccAnnotation == null) {
            log.warn("Bean {} has no @Tcc annotation on target class", beanName);
            return;
        }
        
        String tccName = tccAnnotation.name();
        
        if (tccDefinitions.containsKey(tccName)) {
            throw new IllegalStateException("Duplicate TCC name: " + tccName);
        }
        
        TccDefinition tccDef = new TccDefinition(
                tccName,
                bean,
                target,
                tccAnnotation.timeoutMs(),
                tccAnnotation.retryEnabled(),
                tccAnnotation.maxRetries(),
                tccAnnotation.backoffMs()
        );
        
        // Scan for participants (nested classes or separate beans)
        scanForParticipants(tccDef, targetClass, target);
        
        // Validate the TCC definition
        validateTccDefinition(tccDef);
        
        tccDefinitions.put(tccName, tccDef);
        log.info("Registered TCC coordinator '{}' with {} participant(s)", 
                tccName, tccDef.participants.size());
    }
    
    /**
     * Scans for TCC participants within the coordinator.
     */
    private void scanForParticipants(TccDefinition tccDef, Class<?> coordinatorClass, Object coordinatorTarget) {
        // Scan nested classes
        for (Class<?> nestedClass : coordinatorClass.getDeclaredClasses()) {
            TccParticipant participantAnnotation = AnnotationUtils.findAnnotation(nestedClass, TccParticipant.class);
            if (participantAnnotation != null) {
                try {
                    // Create instance of nested class
                    Object participantInstance = nestedClass.getDeclaredConstructor().newInstance();
                    registerParticipant(tccDef, participantAnnotation, nestedClass, participantInstance);
                } catch (Exception e) {
                    log.error("Failed to instantiate participant: {}", nestedClass.getName(), e);
                    throw new IllegalStateException("Failed to instantiate participant: " + nestedClass.getName(), e);
                }
            }
        }
        
        // TODO: Support for external participant beans (future enhancement)
    }
    
    /**
     * Registers a single participant.
     */
    private void registerParticipant(TccDefinition tccDef, TccParticipant participantAnnotation,
                                     Class<?> participantClass, Object participantInstance) {
        String participantId = participantAnnotation.id();
        
        // Find try, confirm, and cancel methods
        Method tryMethod = null;
        Method confirmMethod = null;
        Method cancelMethod = null;
        
        long tryTimeoutMs = -1;
        int tryRetry = -1;
        long tryBackoffMs = -1;
        
        long confirmTimeoutMs = -1;
        int confirmRetry = -1;
        long confirmBackoffMs = -1;
        
        long cancelTimeoutMs = -1;
        int cancelRetry = -1;
        long cancelBackoffMs = -1;
        
        for (Method method : participantClass.getDeclaredMethods()) {
            TryMethod tryAnn = AnnotationUtils.findAnnotation(method, TryMethod.class);
            if (tryAnn != null) {
                if (tryMethod != null) {
                    throw new IllegalStateException("Participant " + participantId + 
                            " has multiple @TryMethod annotations");
                }
                tryMethod = method;
                tryTimeoutMs = tryAnn.timeoutMs();
                tryRetry = tryAnn.retry();
                tryBackoffMs = tryAnn.backoffMs();
            }
            
            ConfirmMethod confirmAnn = AnnotationUtils.findAnnotation(method, ConfirmMethod.class);
            if (confirmAnn != null) {
                if (confirmMethod != null) {
                    throw new IllegalStateException("Participant " + participantId + 
                            " has multiple @ConfirmMethod annotations");
                }
                confirmMethod = method;
                confirmTimeoutMs = confirmAnn.timeoutMs();
                confirmRetry = confirmAnn.retry();
                confirmBackoffMs = confirmAnn.backoffMs();
            }
            
            CancelMethod cancelAnn = AnnotationUtils.findAnnotation(method, CancelMethod.class);
            if (cancelAnn != null) {
                if (cancelMethod != null) {
                    throw new IllegalStateException("Participant " + participantId + 
                            " has multiple @CancelMethod annotations");
                }
                cancelMethod = method;
                cancelTimeoutMs = cancelAnn.timeoutMs();
                cancelRetry = cancelAnn.retry();
                cancelBackoffMs = cancelAnn.backoffMs();
            }
        }
        
        // Validate that all required methods are present
        if (tryMethod == null) {
            throw new IllegalStateException("Participant " + participantId + " is missing @TryMethod");
        }
        if (confirmMethod == null) {
            throw new IllegalStateException("Participant " + participantId + " is missing @ConfirmMethod");
        }
        if (cancelMethod == null) {
            throw new IllegalStateException("Participant " + participantId + " is missing @CancelMethod");
        }
        
        TccParticipantDefinition participantDef = new TccParticipantDefinition(
                participantId,
                participantAnnotation.order(),
                participantAnnotation.timeoutMs(),
                participantAnnotation.optional(),
                participantInstance,
                participantInstance,
                tryMethod,
                tryTimeoutMs,
                tryRetry,
                tryBackoffMs,
                confirmMethod,
                confirmTimeoutMs,
                confirmRetry,
                confirmBackoffMs,
                cancelMethod,
                cancelTimeoutMs,
                cancelRetry,
                cancelBackoffMs
        );

        // Optional event config
        TccEvent tccEvent = AnnotationUtils.findAnnotation(participantClass, TccEvent.class);
        if (tccEvent != null) {
            participantDef.tccEvent = new TccEventConfig(tccEvent.topic(), tccEvent.eventType(), tccEvent.key());
        }

        tccDef.addParticipant(participantDef);
        log.debug("Registered participant '{}' for TCC '{}'", participantId, tccDef.name);
    }
    
    /**
     * Validates a TCC definition.
     */
    private void validateTccDefinition(TccDefinition tccDef) {
        if (tccDef.participants.isEmpty()) {
            throw new IllegalStateException("TCC " + tccDef.name + " has no participants");
        }
        
        // Check for duplicate participant IDs
        Set<String> participantIds = new HashSet<>();
        for (TccParticipantDefinition participant : tccDef.participants.values()) {
            if (!participantIds.add(participant.id)) {
                throw new IllegalStateException("TCC " + tccDef.name + 
                        " has duplicate participant ID: " + participant.id);
            }
        }
    }
    
    /**
     * Gets a TCC definition by name.
     *
     * @param tccName the TCC transaction name
     * @return the TCC definition
     * @throws IllegalArgumentException if no TCC with the given name is found
     */
    public TccDefinition getTcc(String tccName) {
        TccDefinition tccDef = tccDefinitions.get(tccName);
        if (tccDef == null) {
            throw new IllegalArgumentException("No TCC found with name: " + tccName + 
                    ". Available: " + tccDefinitions.keySet());
        }
        return tccDef;
    }
    
    /**
     * Gets all registered TCC definitions.
     *
     * @return a map of TCC names to their definitions
     */
    public Map<String, TccDefinition> getAllTccs() {
        return Collections.unmodifiableMap(tccDefinitions);
    }
    
    /**
     * Checks if a TCC with the given name is registered.
     *
     * @param tccName the TCC transaction name
     * @return true if registered, false otherwise
     */
    public boolean hasTcc(String tccName) {
        return tccDefinitions.containsKey(tccName);
    }
}

