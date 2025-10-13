/*
 * Copyright 2024 Firefly Authors
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

package com.firefly.transactional.tcc.composition;

import com.firefly.transactional.tcc.core.TccContext;
import com.firefly.transactional.tcc.engine.TccEngine;
import com.firefly.transactional.tcc.observability.TccEvents;
import com.firefly.transactional.tcc.registry.TccRegistry;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Core orchestrator for composing multiple TCC coordinators into coordinated workflows.
 * <p>
 * The TccCompositor provides a way to combine existing TCC coordinators into higher-level
 * orchestration patterns, supporting both sequential and parallel execution with cross-TCC
 * data flow and comprehensive error handling.
 * <p>
 * Key features:
 * - Fluent builder API for composition definition
 * - Sequential and parallel TCC execution patterns
 * - Cross-TCC context sharing and data flow
 * - Composition-level compensation strategies
 * - Integration with existing TccEngine and TccEvents systems
 * - Support for conditional TCC execution based on previous results
 * - Three-phase protocol management (TRY, CONFIRM, CANCEL) across multiple TCCs
 * 
 * Example usage:
 * <pre>{@code
 * TccComposition composition = TccCompositor.compose("order-fulfillment")
 *     .tcc("payment-processing")
 *         .withInput("orderId", orderId)
 *         .add()
 *     .tcc("inventory-reservation")
 *         .dependsOn("payment-processing")
 *         .withDataFrom("payment-processing", "payment-participant", "paymentId")
 *         .add()
 *     .tcc("shipping-preparation")
 *         .dependsOn("inventory-reservation")
 *         .executeInParallelWith("notification-sending")
 *         .add()
 *     .tcc("notification-sending")
 *         .dependsOn("payment-processing")
 *         .add()
 *     .build();
 * 
 * TccCompositionResult result = tccCompositor.execute(composition, context).block();
 * }</pre>
 */
public class TccCompositor {
    
    private final TccEngine tccEngine;
    private final TccRegistry tccRegistry;
    private final TccEvents tccEvents;
    private final TccCompositionExecutionOrchestrator orchestrator;
    
    /**
     * Creates a new TccCompositor with the provided dependencies.
     * 
     * @param tccEngine the underlying TCC engine for executing individual TCCs
     * @param tccRegistry the TCC registry for resolving TCC definitions
     * @param tccEvents the events system for observability
     */
    public TccCompositor(TccEngine tccEngine, TccRegistry tccRegistry, TccEvents tccEvents) {
        this.tccEngine = Objects.requireNonNull(tccEngine, "tccEngine cannot be null");
        this.tccRegistry = Objects.requireNonNull(tccRegistry, "tccRegistry cannot be null");
        this.tccEvents = Objects.requireNonNull(tccEvents, "tccEvents cannot be null");
        this.orchestrator = new TccCompositionExecutionOrchestrator(tccEngine, tccEvents);
    }
    
    /**
     * Creates a new composition builder with the specified name.
     * 
     * @param compositionName the name of the composition
     * @return a new composition builder
     */
    public static TccCompositionBuilder compose(String compositionName) {
        return new TccCompositionBuilder(compositionName);
    }
    
    /**
     * Executes a TCC composition with the provided context.
     * 
     * @param composition the composition to execute
     * @param context the TCC context for execution
     * @return a Mono containing the composition execution result
     */
    public Mono<TccCompositionResult> execute(TccComposition composition, TccContext context) {
        Objects.requireNonNull(composition, "composition cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        
        return orchestrator.orchestrate(composition, context, tccRegistry);
    }
    
    /**
     * Executes a named composition with the provided context.
     * <p>
     * This method is reserved for future implementation of composition templates
     * and registered compositions.
     * 
     * @param compositionName the name of the composition to execute
     * @param context the TCC context for execution
     * @return a Mono containing the composition execution result
     * @throws UnsupportedOperationException currently not implemented
     */
    public Mono<TccCompositionResult> execute(String compositionName, TccContext context) {
        Objects.requireNonNull(compositionName, "compositionName cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        
        // For now, we'll focus on direct composition execution
        // In the future, this could support registered compositions
        throw new UnsupportedOperationException("Named composition execution not yet implemented. Use execute(TccComposition, TccContext) instead.");
    }
    
    /**
     * Validates a TCC composition for structural correctness.
     * 
     * @param composition the composition to validate
     * @throws IllegalArgumentException if the composition is invalid
     */
    public void validate(TccComposition composition) {
        Objects.requireNonNull(composition, "composition cannot be null");
        
        TccCompositionValidator.validate(composition, tccRegistry);
    }
    
    /**
     * Gets the underlying TCC engine.
     * 
     * @return the TCC engine
     */
    public TccEngine getTccEngine() {
        return tccEngine;
    }
    
    /**
     * Gets the TCC registry.
     * 
     * @return the TCC registry
     */
    public TccRegistry getTccRegistry() {
        return tccRegistry;
    }
    
    /**
     * Gets the TCC events system.
     * 
     * @return the TCC events
     */
    public TccEvents getTccEvents() {
        return tccEvents;
    }
}
