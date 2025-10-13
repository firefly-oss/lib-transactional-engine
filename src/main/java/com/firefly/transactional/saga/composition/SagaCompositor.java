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

package com.firefly.transactional.saga.composition;

import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.saga.engine.SagaEngine;
import com.firefly.transactional.saga.engine.StepInputs;
import com.firefly.transactional.saga.registry.SagaDefinition;
import com.firefly.transactional.saga.registry.SagaRegistry;
import com.firefly.transactional.saga.observability.SagaEvents;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * Core orchestrator for composing multiple sagas into coordinated workflows.
 * <p>
 * The SagaCompositor provides a way to combine existing {@link SagaDefinition} objects
 * into higher-level orchestration patterns, supporting both sequential and parallel
 * execution with cross-saga data flow and comprehensive error handling.
 * <p>
 * Key features:
 * - Fluent builder API for composition definition
 * - Sequential and parallel saga execution patterns
 * - Cross-saga context sharing and data flow
 * - Composition-level compensation strategies
 * - Integration with existing SagaEngine and SagaEvents systems
 * - Support for conditional saga execution based on previous results
 * 
 * Example usage:
 * <pre>{@code
 * SagaComposition composition = SagaCompositor.compose("order-fulfillment")
 *     .saga("payment-processing")
 *         .withInput("orderId", orderId)
 *         .add()
 *     .saga("inventory-reservation")
 *         .dependsOn("payment-processing")
 *         .withDataFrom("payment-processing", "paymentId")
 *         .add()
 *     .saga("shipping-preparation")
 *         .dependsOn("inventory-reservation")
 *         .executeInParallelWith("notification-sending")
 *         .add()
 *     .saga("notification-sending")
 *         .dependsOn("payment-processing")
 *         .add()
 *     .build();
 * 
 * SagaCompositionResult result = sagaCompositor.execute(composition, context).block();
 * }</pre>
 */
public class SagaCompositor {
    
    private final SagaEngine sagaEngine;
    private final SagaRegistry sagaRegistry;
    private final SagaEvents sagaEvents;
    private final CompositionExecutionOrchestrator orchestrator;
    
    /**
     * Creates a new SagaCompositor with the provided dependencies.
     * 
     * @param sagaEngine the underlying saga engine for executing individual sagas
     * @param sagaRegistry the saga registry for resolving saga definitions
     * @param sagaEvents the events system for observability
     */
    public SagaCompositor(SagaEngine sagaEngine, SagaRegistry sagaRegistry, SagaEvents sagaEvents) {
        this.sagaEngine = Objects.requireNonNull(sagaEngine, "sagaEngine cannot be null");
        this.sagaRegistry = Objects.requireNonNull(sagaRegistry, "sagaRegistry cannot be null");
        this.sagaEvents = Objects.requireNonNull(sagaEvents, "sagaEvents cannot be null");
        this.orchestrator = new CompositionExecutionOrchestrator(sagaEngine, sagaEvents);
    }
    
    /**
     * Creates a new composition builder with the specified name.
     * 
     * @param compositionName the name for the composition
     * @return a new composition builder
     */
    public static SagaCompositionBuilder compose(String compositionName) {
        return new SagaCompositionBuilder(compositionName);
    }
    
    /**
     * Executes a saga composition with the provided context.
     * 
     * @param composition the composition to execute
     * @param context the saga context for execution
     * @return a Mono containing the composition execution result
     */
    public Mono<SagaCompositionResult> execute(SagaComposition composition, SagaContext context) {
        Objects.requireNonNull(composition, "composition cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        
        return orchestrator.orchestrate(composition, context, sagaRegistry);
    }
    
    /**
     * Executes a saga composition by name with the provided inputs and context.
     * 
     * @param compositionName the name of the composition to execute
     * @param inputs the step inputs for the composition
     * @param context the saga context for execution
     * @return a Mono containing the composition execution result
     */
    public Mono<SagaCompositionResult> execute(String compositionName, StepInputs inputs, SagaContext context) {
        Objects.requireNonNull(compositionName, "compositionName cannot be null");
        Objects.requireNonNull(inputs, "inputs cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        
        // For now, we'll focus on direct composition execution
        // In the future, this could support registered compositions
        throw new UnsupportedOperationException("Named composition execution not yet implemented. Use execute(SagaComposition, SagaContext) instead.");
    }
    
    /**
     * Validates a saga composition for structural correctness.
     * 
     * @param composition the composition to validate
     * @throws IllegalArgumentException if the composition is invalid
     */
    public void validate(SagaComposition composition) {
        Objects.requireNonNull(composition, "composition cannot be null");
        
        CompositionValidator.validate(composition, sagaRegistry);
    }
}
