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



import com.firefly.transactional.tcc.events.TccEventPublisher;
import com.firefly.transactional.tcc.observability.TccEvents;
import com.firefly.transactional.tcc.persistence.TccPersistenceAdapter;
import com.firefly.transactional.tcc.persistence.TccPersistenceProvider;
import com.firefly.transactional.tcc.persistence.impl.InMemoryTccPersistenceProvider;
import com.firefly.transactional.tcc.engine.TccArgumentResolver;
import com.firefly.transactional.tcc.core.TccContext;
import com.firefly.transactional.tcc.core.TccInputs;
import com.firefly.transactional.tcc.core.TccResult;
import com.firefly.transactional.tcc.registry.TccDefinition;
import com.firefly.transactional.tcc.registry.TccRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.UUID;

/**
 * Main orchestrator for executing TCC (Try-Confirm-Cancel) transactions.
 * <p>
 * This engine is responsible for:
 * <ul>
 *   <li>Executing TCC transactions by name</li>
 *   <li>Managing try-confirm-cancel phase transitions</li>
 *   <li>Handling participant execution with retry and timeout</li>
 *   <li>Integrating with the persistence layer for state management</li>
 *   <li>Emitting observability events</li>
 * </ul>
 * <p>
 * The engine uses TCC-specific infrastructure including:
 * <ul>
 *   <li>{@link TccPersistenceProvider} for TCC state persistence</li>
 *   <li>{@link TccArgumentResolver} for TCC parameter injection</li>
 * </ul>
 * <p>
 * For observability, the engine uses {@link TccEvents} which provides TCC-specific
 * event hooks for monitoring and metrics collection.
 * <p>
 * Additionally, it provides TCC-specific observability through:
 * <ul>
 *   <li>{@link TccEvents} for TCC-specific observability</li>
 *   <li>{@link TccEventPublisher} for external event publishing</li>
 * </ul>
 */
public class TccEngine {
    
    private static final Logger log = LoggerFactory.getLogger(TccEngine.class);
    
    private final TccRegistry registry;
    private final TccEvents tccEvents;
    private final TccPersistenceProvider persistenceProvider;
    private final boolean persistenceEnabled;
    private final TccExecutionOrchestrator orchestrator;
    
    /**
     * Creates a new TCC engine with default configuration.
     *
     * @param registry the TCC registry
     * @param tccEvents the TCC observability events sink
     */
    public TccEngine(TccRegistry registry, TccEvents tccEvents) {
        this(registry, tccEvents, new InMemoryTccPersistenceProvider(), false, null);
    }
    
    /**
     * Creates a new TCC engine with persistence support.
     *
     * @param registry the TCC registry
     * @param tccEvents the TCC observability events sink
     * @param persistenceProvider the TCC persistence provider
     * @param persistenceEnabled whether persistence is enabled
     */
    public TccEngine(TccRegistry registry, TccEvents tccEvents,
                    TccPersistenceProvider persistenceProvider, boolean persistenceEnabled) {
        this(registry, tccEvents, persistenceProvider, persistenceEnabled, null);
    }

    /**
     * Creates a new TCC engine with full configuration including TCC-specific events.
     *
     * @param registry the TCC registry
     * @param tccEvents the TCC-specific observability events sink
     * @param persistenceProvider the persistence provider
     * @param persistenceEnabled whether persistence is enabled
     * @param tccEventPublisher the TCC event publisher for external events
     */
    public TccEngine(TccRegistry registry, TccEvents tccEvents,
                     TccPersistenceProvider persistenceProvider, boolean persistenceEnabled,
                     TccEventPublisher tccEventPublisher) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tccEvents = tccEvents; // Can be null
        this.persistenceProvider = persistenceProvider != null ?
                persistenceProvider : new InMemoryTccPersistenceProvider();
        this.persistenceEnabled = persistenceEnabled;

        // Create TCC-specific components
        TccArgumentResolver argumentResolver = new TccArgumentResolver();
        TccParticipantInvoker participantInvoker = new TccParticipantInvoker(argumentResolver);

        // Create the execution orchestrator
        this.orchestrator = new TccExecutionOrchestrator(
                participantInvoker,
                tccEvents,
                tccEventPublisher,
                this.persistenceProvider,
                persistenceEnabled
        );
    }
    
    /**
     * Executes a TCC transaction by name.
     *
     * @param tccName the name of the TCC transaction
     * @param inputs the participant inputs
     * @return a Mono containing the TCC result
     */
    public Mono<TccResult> execute(String tccName, TccInputs inputs) {
        return execute(tccName, inputs, null);
    }
    
    /**
     * Executes a TCC transaction by name with a custom context.
     *
     * @param tccName the name of the TCC transaction
     * @param inputs the participant inputs
     * @param context the TCC context (null to auto-create)
     * @return a Mono containing the TCC result
     */
    public Mono<TccResult> execute(String tccName, TccInputs inputs, TccContext context) {
        Objects.requireNonNull(tccName, "tccName");
        
        // Get TCC definition from registry
        TccDefinition tccDef = registry.getTcc(tccName);
        
        return execute(tccDef, inputs, context);
    }
    
    /**
     * Executes a TCC transaction using its definition.
     *
     * @param tccDef the TCC definition
     * @param inputs the participant inputs
     * @param context the TCC context (null to auto-create)
     * @return a Mono containing the TCC result
     */
    public Mono<TccResult> execute(TccDefinition tccDef, TccInputs inputs, TccContext context) {
        Objects.requireNonNull(tccDef, "tccDef");
        
        // Auto-create context if not provided
        final TccContext finalContext = context != null ? context : createContext(tccDef.name);
        
        // Set TCC name in context
        finalContext.setTccName(tccDef.name);
        
        log.info("Starting TCC transaction '{}' with correlation ID: {}", 
                tccDef.name, finalContext.correlationId());
        
        // Notify start via TCC events
        if (tccEvents != null) {
            tccEvents.onTccStarted(tccDef.name, finalContext.correlationId(), finalContext);
        }

        // Execute the TCC transaction
        return orchestrator.orchestrate(tccDef, inputs, finalContext)
                .doOnSuccess(result -> {
                    log.info("TCC transaction '{}' completed with status: {} (correlation ID: {})",
                            tccDef.name, result.isSuccess() ? "SUCCESS" : "FAILED",
                            finalContext.correlationId());
                })
                .doOnError(error -> {
                    log.error("TCC transaction '{}' failed with error (correlation ID: {})",
                            tccDef.name, finalContext.correlationId(), error);
                });
    }
    
    /**
     * Creates a new TCC context with a generated correlation ID.
     *
     * @param tccName the TCC transaction name
     * @return a new TCC context
     */
    private TccContext createContext(String tccName) {
        String correlationId = generateCorrelationId(tccName);
        TccContext context = new TccContext(correlationId);
        context.setTccName(tccName);
        return context;
    }
    
    /**
     * Generates a correlation ID for a TCC transaction.
     *
     * @param tccName the TCC transaction name
     * @return a unique correlation ID
     */
    private String generateCorrelationId(String tccName) {
        return tccName + "-" + UUID.randomUUID().toString();
    }
    
    /**
     * Gets the TCC registry.
     *
     * @return the TCC registry
     */
    public TccRegistry getRegistry() {
        return registry;
    }
    
    /**
     * Gets the TCC persistence provider.
     *
     * @return the TCC persistence provider
     */
    public TccPersistenceProvider getTccPersistenceProvider() {
        return persistenceProvider;
    }
    
    /**
     * Checks if persistence is enabled.
     *
     * @return true if persistence is enabled
     */
    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }
    
    /**
     * Gets the TCC observability events sink.
     *
     * @return the TCC events sink
     */
    public TccEvents getTccEvents() {
        return tccEvents;
    }

    /**
     * Gets the TCC persistence provider.
     *
     * @return the TCC persistence provider
     */
    public TccPersistenceProvider getPersistenceProvider() {
        return persistenceProvider;
    }
}

