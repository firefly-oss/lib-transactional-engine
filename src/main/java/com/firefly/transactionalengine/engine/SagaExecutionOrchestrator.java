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

package com.firefly.transactionalengine.engine;

import com.firefly.transactionalengine.core.SagaContext;
import com.firefly.transactionalengine.core.StepStatus;
import com.firefly.transactionalengine.events.StepEventEnvelope;
import com.firefly.transactionalengine.events.StepEventPublisher;
import com.firefly.transactionalengine.observability.SagaEvents;
import com.firefly.transactionalengine.registry.SagaDefinition;
import com.firefly.transactionalengine.registry.StepDefinition;
import com.firefly.transactionalengine.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles pure execution orchestration for sagas, including DAG building and layer execution.
 * This class is focused solely on the execution flow and delegates compensation and other
 * concerns to specialized components.
 * 
 * <p>Key responsibilities:
 * <ul>
 *   <li>Build execution topology (DAG layers) from saga definitions</li>
 *   <li>Execute steps in layers with proper concurrency control</li>
 *   <li>Handle step lifecycle events and status tracking</li>
 *   <li>Coordinate with step invokers and event publishers</li>
 * </ul>
 */
public class SagaExecutionOrchestrator {
    
    private static final Logger log = LoggerFactory.getLogger(SagaExecutionOrchestrator.class);
    
    private final StepInvoker stepInvoker;
    private final SagaEvents events;
    private final StepEventPublisher stepEventPublisher;
    
    /**
     * Creates a new orchestrator with the required dependencies.
     * 
     * @param stepInvoker the step invoker for executing individual steps
     * @param events the event sink for saga lifecycle notifications
     * @param stepEventPublisher the publisher for step events
     */
    public SagaExecutionOrchestrator(StepInvoker stepInvoker, SagaEvents events, StepEventPublisher stepEventPublisher) {
        this.stepInvoker = Objects.requireNonNull(stepInvoker, "stepInvoker");
        this.events = Objects.requireNonNull(events, "events");
        this.stepEventPublisher = Objects.requireNonNull(stepEventPublisher, "stepEventPublisher");
    }
    
    /**
     * Orchestrates the execution of a saga by building the topology and executing steps in layers.
     * 
     * @param saga the saga definition to execute
     * @param inputs the step inputs
     * @param context the saga context
     * @param overrideInputs any override inputs from expansion
     * @return execution result containing completion order and any errors
     */
    public Mono<ExecutionResult> orchestrate(SagaDefinition saga, StepInputs inputs, SagaContext context, Map<String, Object> overrideInputs) {
        return Mono.fromCallable(() -> {
            // Build execution topology
            List<List<String>> layers = SagaTopology.buildLayers(saga);
            
            // Initialize execution state
            ExecutionState state = new ExecutionState(saga, layers, inputs, context, overrideInputs);
            
            // Notify execution start
            events.onStart(saga.name, context.correlationId());
            events.onStart(saga.name, context.correlationId(), context);
            
            return state;
        })
        .flatMap(this::executeLayersSequentially)
        .map(state -> new ExecutionResult(
            state.completionOrder(),
            state.failed(),
            state.stepErrors(),
            state.saga(),
            state.context()
        ));
    }
    
    /**
     * Executes all layers sequentially, with steps within each layer executing concurrently.
     */
    private Mono<ExecutionState> executeLayersSequentially(ExecutionState state) {
        return Flux.fromIterable(state.layers())
                .concatMap(layer -> {
                    if (state.failed()) {
                        return Mono.just(state); // Stop processing if any step failed
                    }
                    return executeLayer(state, layer);
                })
                .last(state); // Return the final state
    }
    
    /**
     * Executes all steps in a single layer concurrently.
     */
    private Mono<ExecutionState> executeLayer(ExecutionState state, List<String> layer) {
        if (layer.isEmpty()) {
            return Mono.just(state);
        }
        
        List<Mono<Void>> stepExecutions = layer.stream()
                .map(stepId -> executeStepWithErrorHandling(state, stepId))
                .toList();
        
        return Mono.when(stepExecutions)
                .then(Mono.just(state))
                .onErrorReturn(state); // Continue even if layer execution fails
    }
    
    /**
     * Executes a single step with proper error handling and state tracking.
     */
    private Mono<Void> executeStepWithErrorHandling(ExecutionState state, String stepId) {
        Object input = resolveStepInput(state, stepId);
        
        return executeStep(state.saga(), stepId, input, state.context())
                .doOnSuccess(v -> state.markStepCompleted(stepId))
                .onErrorResume(err -> {
                    state.markStepFailed(stepId, err);
                    return Mono.empty();
                });
    }
    
    /**
     * Executes a single step according to its configuration.
     */
    private Mono<Void> executeStep(SagaDefinition saga, String stepId, Object input, SagaContext context) {
        StepDefinition stepDef = saga.steps.get(stepId);
        if (stepDef == null) {
            return Mono.error(new IllegalArgumentException("Unknown step: " + stepId));
        }

        // Idempotency within run
        if (stepDef.idempotencyKey != null && !stepDef.idempotencyKey.isEmpty()) {
            if (context.hasIdempotencyKey(stepDef.idempotencyKey)) {
                log.info(JsonUtils.json(
                        "event", "step_skipped_idempotent",
                        "step_id", stepId,
                        "idempotency_key", stepDef.idempotencyKey
                ));
                context.setStatus(stepId, StepStatus.DONE);
                events.onStepSkippedIdempotent(saga.name, context.correlationId(), stepId);
                return Mono.empty();
            }
            context.markIdempotent(stepDef.idempotencyKey);
        }

        context.setStatus(stepId, StepStatus.RUNNING);
        context.markStepStarted(stepId, Instant.now());
        events.onStepStarted(saga.name, context.correlationId(), stepId);
        final long start = System.currentTimeMillis();

        Mono<Object> execution = createStepExecution(saga, stepDef, stepId, input, context);
        
        if (stepDef.cpuBound) {
            execution = execution.subscribeOn(Schedulers.parallel());
        }
        
        return execution
                .doOnNext(result -> handleStepSuccess(saga, stepDef, stepId, result, context, start))
                .then()
                .doOnError(err -> handleStepError(saga, stepId, err, context, start));
    }
    
    /**
     * Creates the execution Mono for a step based on its configuration.
     */
    private Mono<Object> createStepExecution(SagaDefinition saga, StepDefinition stepDef, String stepId, Object input, SagaContext context) {
        long timeoutMs = stepDef.timeout != null ? stepDef.timeout.toMillis() : 0L;
        long backoffMs = stepDef.backoff != null ? stepDef.backoff.toMillis() : 0L;
        
        if (log.isInfoEnabled()) {
            String mode = stepDef.handler != null ? "handler" : "method";
            String inputType = input != null ? input.getClass().getName() : "null";
            log.info(JsonUtils.json(
                    "event", "executing_step",
                    "step_id", stepId,
                    "mode", mode,
                    "timeout_ms", Long.toString(timeoutMs),
                    "retry", Integer.toString(stepDef.retry),
                    "backoff_ms", Long.toString(backoffMs)
            ));
        }
        
        if (stepDef.handler != null) {
            return stepInvoker.attemptCallHandler(stepDef.handler, input, context, timeoutMs, stepDef.retry, 
                    backoffMs, stepDef.jitter, stepDef.jitterFactor, stepId);
        } else {
            Method invokeMethod = stepDef.stepInvocationMethod != null ? stepDef.stepInvocationMethod : stepDef.stepMethod;
            Object targetBean = stepDef.stepBean != null ? stepDef.stepBean : saga.bean;
            return stepInvoker.attemptCall(targetBean, invokeMethod, stepDef.stepMethod, input, context, 
                    timeoutMs, stepDef.retry, backoffMs, stepDef.jitter, stepDef.jitterFactor, stepId);
        }
    }
    
    /**
     * Handles successful step completion.
     */
    private void handleStepSuccess(SagaDefinition saga, StepDefinition stepDef, String stepId, Object result, SagaContext context, long start) {
        if (log.isInfoEnabled()) {
            String resultType = result != null ? result.getClass().getName() : "null";
            log.info(JsonUtils.json(
                    "event", "step_completed",
                    "step_id", stepId,
                    "result_type", resultType
            ));
        }
        
        context.putResult(stepId, result);
        
        // Support @SetVariable if this step was defined by method
        if (stepDef.stepMethod != null) {
            var setVarAnn = stepDef.stepMethod.getAnnotation(
                    com.firefly.transactionalengine.annotations.SetVariable.class);
            if (setVarAnn != null) {
                String name = setVarAnn.value();
                if (name != null && !name.isBlank()) {
                    context.putVariable(name, result);
                }
            }
        }
        
        long latency = System.currentTimeMillis() - start;
        context.setLatency(stepId, latency);
        context.setStatus(stepId, StepStatus.DONE);
        events.onStepSuccess(saga.name, context.correlationId(), stepId, context.getAttempts(stepId), latency);
    }
    
    /**
     * Handles step execution errors.
     */
    private void handleStepError(SagaDefinition saga, String stepId, Throwable err, SagaContext context, long start) {
        long latency = System.currentTimeMillis() - start;
        context.setLatency(stepId, latency);
        context.setStatus(stepId, StepStatus.FAILED);
        int attempts = context.getAttempts(stepId);
        String errClass = err.getClass().getName();
        String errMsg = err.getMessage() != null ? err.getMessage() : "No message";
        
        log.warn(JsonUtils.json(
                "event", "step_failed",
                "step_id", stepId,
                "attempts", Integer.toString(attempts),
                "error_class", errClass,
                "error_message", errMsg
        ));
        
        events.onStepFailed(saga.name, context.correlationId(), stepId, err, attempts, latency);
    }
    
    /**
     * Resolves the input for a step from override inputs or regular inputs.
     */
    private Object resolveStepInput(ExecutionState state, String stepId) {
        return state.overrideInputs().containsKey(stepId)
                ? state.overrideInputs().get(stepId)
                : (state.inputs() != null ? state.inputs().resolveFor(stepId, state.context()) : null);
    }
    
    /**
     * Publishes step events for completed steps.
     */
    public Mono<Void> publishStepEvents(List<String> completionOrder, SagaDefinition saga, SagaContext context) {
        return Flux.fromIterable(completionOrder)
                .concatMap(stepId -> publishStepEventIfConfigured(saga, stepId, context))
                .then();
    }
    
    /**
     * Publishes a step event if the step is configured for event publishing.
     */
    private Mono<Void> publishStepEventIfConfigured(SagaDefinition saga, String stepId, SagaContext context) {
        StepDefinition stepDef = saga.steps.get(stepId);
        if (stepDef == null || stepDef.stepEvent == null) {
            return Mono.empty();
        }
        
        Object payload = context.getResult(stepId);
        StepEventEnvelope envelope = createStepEventEnvelope(saga, stepId, stepDef, payload, context);
        
        return stepEventPublisher.publish(envelope);
    }
    
    /**
     * Creates a step event envelope for publishing.
     */
    private StepEventEnvelope createStepEventEnvelope(SagaDefinition saga, String stepId, StepDefinition stepDef, Object payload, SagaContext context) {
        return new StepEventEnvelope(
            saga.name,
            context.correlationId(),
            stepId,
            stepDef.stepEvent.topic,
            stepDef.stepEvent.type,
            stepDef.stepEvent.key,
            payload,
            context.headers(),
            context.getAttempts(stepId),
            context.getLatency(stepId),
            context.getStepStartedAt(stepId),
            Instant.now(),
            payload != null ? payload.getClass().getName() : null
        );
    }
    
    /**
     * Execution state that tracks the progress of saga execution.
     */
    private static class ExecutionState {
        private final SagaDefinition saga;
        private final List<List<String>> layers;
        private final StepInputs inputs;
        private final SagaContext context;
        private final Map<String, Object> overrideInputs;
        private final List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean failed = new AtomicBoolean(false);
        private final Map<String, Throwable> stepErrors = new ConcurrentHashMap<>();
        
        public ExecutionState(SagaDefinition saga, List<List<String>> layers, StepInputs inputs, SagaContext context, Map<String, Object> overrideInputs) {
            this.saga = saga;
            this.layers = layers;
            this.inputs = inputs;
            this.context = context;
            this.overrideInputs = overrideInputs != null ? overrideInputs : Map.of();
        }
        
        public void markStepCompleted(String stepId) {
            completionOrder.add(stepId);
        }
        
        public void markStepFailed(String stepId, Throwable error) {
            failed.set(true);
            stepErrors.put(stepId, error);
        }
        
        // Getters
        public SagaDefinition saga() { return saga; }
        public List<List<String>> layers() { return layers; }
        public StepInputs inputs() { return inputs; }
        public SagaContext context() { return context; }
        public Map<String, Object> overrideInputs() { return overrideInputs; }
        public List<String> completionOrder() { return completionOrder; }
        public boolean failed() { return failed.get(); }
        public Map<String, Throwable> stepErrors() { return stepErrors; }
    }
    
    /**
     * Result of saga execution orchestration.
     */
    public static class ExecutionResult {
        private final List<String> completionOrder;
        private final boolean failed;
        private final Map<String, Throwable> stepErrors;
        private final SagaDefinition saga;
        private final SagaContext context;
        
        public ExecutionResult(List<String> completionOrder, boolean failed, Map<String, Throwable> stepErrors, SagaDefinition saga, SagaContext context) {
            this.completionOrder = completionOrder;
            this.failed = failed;
            this.stepErrors = stepErrors;
            this.saga = saga;
            this.context = context;
        }
        
        public List<String> getCompletionOrder() { return completionOrder; }
        public boolean isFailed() { return failed; }
        public Map<String, Throwable> getStepErrors() { return stepErrors; }
        public SagaDefinition getSaga() { return saga; }
        public SagaContext getContext() { return context; }
    }
}
