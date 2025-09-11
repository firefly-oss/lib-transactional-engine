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
import com.firefly.transactionalengine.core.SagaResult;
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
 * Encapsulates the execution logic for a single saga run.
 * This class handles the complex orchestration flow while keeping SagaEngine focused on coordination.
 */
public class SagaExecutionCommand {
    
    private static final Logger log = LoggerFactory.getLogger(SagaExecutionCommand.class);
    
    private final SagaDefinition saga;
    private final StepInputs inputs;
    private final SagaContext context;
    private final SagaEvents events;
    private final StepEventPublisher stepEventPublisher;
    private final SagaCompensator compensator;
    private final StepInvoker stepInvoker;
    private final Map<String, Object> overrideInputs;
    
    public SagaExecutionCommand(SagaDefinition saga, StepInputs inputs, SagaContext context, 
                               SagaEvents events, StepEventPublisher stepEventPublisher, 
                               SagaCompensator compensator, StepInvoker stepInvoker, 
                               Map<String, Object> overrideInputs) {
        this.saga = Objects.requireNonNull(saga, "saga");
        this.inputs = inputs;
        this.context = Objects.requireNonNull(context, "context");
        this.events = Objects.requireNonNull(events, "events");
        this.stepEventPublisher = Objects.requireNonNull(stepEventPublisher, "stepEventPublisher");
        this.compensator = Objects.requireNonNull(compensator, "compensator");
        this.stepInvoker = Objects.requireNonNull(stepInvoker, "stepInvoker");
        this.overrideInputs = overrideInputs != null ? overrideInputs : Map.of();
    }
    
    public Mono<SagaResult> execute() {
        return executeSagaWithExpansion()
                .flatMap(this::executeStepsInLayers)
                .flatMap(executionResult -> handleExecutionCompletion(executionResult));
    }
    
    private Mono<SagaExecutionContext> executeSagaWithExpansion() {
        return Mono.fromCallable(() -> {
            // SagaEngine already handled expansion, just set up execution context
            context.setSagaName(saga.name);
            String sagaId = context.correlationId();
            
            // Notify execution start
            events.onStart(saga.name, sagaId);
            events.onStart(saga.name, sagaId, context);
            
            // Build execution topology
            List<List<String>> layers = buildTopologyLayers(saga);
            
            return new SagaExecutionContext(saga, layers, overrideInputs, sagaId);
        });
    }
    
    private Mono<SagaExecutionResult> executeStepsInLayers(SagaExecutionContext executionContext) {
        List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean failed = new AtomicBoolean(false);
        Map<String, Throwable> stepErrors = new ConcurrentHashMap<>();
        
        return Flux.fromIterable(executionContext.layers())
                .concatMap(layer -> {
                    if (failed.get()) {
                        return Mono.empty(); // Stop processing if any step failed
                    }
                    
                    return executeStepsInLayer(executionContext, layer, completionOrder, failed, stepErrors);
                })
                .then(Mono.just(new SagaExecutionResult(
                    executionContext, 
                    completionOrder, 
                    failed.get(), 
                    stepErrors
                )))
                .onErrorReturn(new SagaExecutionResult(
                    executionContext,
                    completionOrder,
                    true, // Layer execution failed
                    stepErrors
                ));
    }
    
    private Mono<Void> executeStepsInLayer(SagaExecutionContext executionContext, 
                                         List<String> layer,
                                         List<String> completionOrder,
                                         AtomicBoolean failed,
                                         Map<String, Throwable> stepErrors) {
        var executions = layer.stream()
                .map(stepId -> executeStepWithErrorHandling(executionContext, stepId, completionOrder, failed, stepErrors))
                .toList();
        
        return Mono.when(executions)
                .then(Mono.defer(() -> {
                    // Check if any step failed after all executions in layer are complete
                    if (failed.get()) {
                        return Mono.error(new RuntimeException("Layer execution failed"));
                    }
                    return Mono.empty();
                }));
    }
    
    private Mono<Void> executeStepWithErrorHandling(SagaExecutionContext executionContext,
                                                   String stepId,
                                                   List<String> completionOrder,
                                                   AtomicBoolean failed,
                                                   Map<String, Throwable> stepErrors) {
        Object input = resolveStepInput(executionContext, stepId);
        
        return executeStep(executionContext.sagaName(), executionContext.workSaga(), stepId, input, context)
                .doOnSuccess(v -> completionOrder.add(stepId))
                .onErrorResume(err -> {
                    failed.set(true);
                    stepErrors.put(stepId, err);
                    return Mono.empty();
                });
    }
    
    /**
     * Execute a single step according to its configuration.
     * Copied and adapted from SagaEngine's executeStep method.
     */
    private Mono<Void> executeStep(String sagaName,
                                   SagaDefinition saga,
                                   String stepId,
                                   Object input,
                                   SagaContext ctx) {
        StepDefinition sd = saga.steps.get(stepId);
        if (sd == null) {
            return Mono.error(new IllegalArgumentException("Unknown step: " + stepId));
        }

        // Idempotency within run
        if (sd.idempotencyKey != null && !sd.idempotencyKey.isEmpty()) {
            if (ctx.hasIdempotencyKey(sd.idempotencyKey)) {
                log.info(JsonUtils.json(
                        "event", "step_skipped_idempotent",
                        "step_id", stepId,
                        "idempotency_key", sd.idempotencyKey
                ));
                ctx.setStatus(stepId, StepStatus.DONE);
                events.onStepSkippedIdempotent(sagaName, ctx.correlationId(), stepId);
                return Mono.empty();
            }
            ctx.markIdempotent(sd.idempotencyKey);
        }

        ctx.setStatus(stepId, StepStatus.RUNNING);
        ctx.markStepStarted(stepId, Instant.now());
        events.onStepStarted(sagaName, ctx.correlationId(), stepId);
        final long start = System.currentTimeMillis();

        Mono<Object> execution;
        long timeoutMs = sd.timeout != null ? sd.timeout.toMillis() : 0L;
        long backoffMs = sd.backoff != null ? sd.backoff.toMillis() : 0L;
        
        if (log.isInfoEnabled()) {
            String mode = sd.handler != null ? "handler" : "method";
            String inputType = input != null ? input.getClass().getName() : "null";
            log.info(JsonUtils.json(
                    "event", "executing_step",
                    "step_id", stepId,
                    "mode", mode,
                    "timeout_ms", Long.toString(timeoutMs),
                    "retry", Integer.toString(sd.retry),
                    "backoff_ms", Long.toString(backoffMs)
            ));
        }
        
        if (sd.handler != null) {
            execution = stepInvoker.attemptCallHandler(sd.handler, input, ctx, timeoutMs, sd.retry, 
                    backoffMs, sd.jitter, sd.jitterFactor, stepId);
        } else {
            Method invokeMethod = sd.stepInvocationMethod != null ? sd.stepInvocationMethod : sd.stepMethod;
            Object targetBean = sd.stepBean != null ? sd.stepBean : saga.bean;
            execution = stepInvoker.attemptCall(targetBean, invokeMethod, sd.stepMethod, input, ctx, 
                    timeoutMs, sd.retry, backoffMs, sd.jitter, sd.jitterFactor, stepId);
        }
        
        if (sd.cpuBound) {
            execution = execution.subscribeOn(Schedulers.parallel());
        }
        
        return execution
                .doOnNext(res -> {
                    if (log.isInfoEnabled()) {
                        String resultType = res != null ? res.getClass().getName() : "null";
                        log.info(JsonUtils.json(
                                "event", "step_completed",
                                "step_id", stepId,
                                "result_type", resultType
                        ));
                    }
                    ctx.putResult(stepId, res);
                    
                    // Support @SetVariable if this step was defined by method
                    if (sd.stepMethod != null) {
                        var setVarAnn = sd.stepMethod.getAnnotation(
                                com.firefly.transactionalengine.annotations.SetVariable.class);
                        if (setVarAnn != null) {
                            String name = setVarAnn.value();
                            if (name != null && !name.isBlank()) {
                                ctx.putVariable(name, res);
                            }
                        }
                    }
                })
                .then()
                .doOnSuccess(v -> {
                    long latency = System.currentTimeMillis() - start;
                    ctx.setLatency(stepId, latency);
                    ctx.setStatus(stepId, StepStatus.DONE);
                    events.onStepSuccess(sagaName, ctx.correlationId(), stepId, ctx.getAttempts(stepId), latency);
                })
                .doOnError(err -> {
                    long latency = System.currentTimeMillis() - start;
                    ctx.setLatency(stepId, latency);
                    ctx.setStatus(stepId, StepStatus.FAILED);
                    int attempts = ctx.getAttempts(stepId);
                    String errClass = err.getClass().getName();
                    String errMsg = err.getMessage() != null ? err.getMessage() : "No message";
                    log.warn(JsonUtils.json(
                            "event", "step_failed",
                            "step_id", stepId,
                            "attempts", Integer.toString(attempts),
                            "error_class", errClass,
                            "error_message", errMsg
                    ));
                    events.onStepFailed(sagaName, ctx.correlationId(), stepId, err, attempts, latency);
                });
    }
    
    private Object resolveStepInput(SagaExecutionContext executionContext, String stepId) {
        return executionContext.overrideInputs().containsKey(stepId)
                ? executionContext.overrideInputs().get(stepId)
                : (inputs != null ? inputs.resolveFor(stepId, context) : null);
    }
    
    private Mono<SagaResult> handleExecutionCompletion(SagaExecutionResult result) {
        boolean success = !result.failed();
        String sagaName = result.executionContext().sagaName();
        String sagaId = result.executionContext().sagaId();
        
        events.onCompleted(sagaName, sagaId, success);
        
        if (success) {
            return handleSuccessfulExecution(result);
        } else {
            return handleFailedExecution(result);
        }
    }
    
    private Mono<SagaResult> handleSuccessfulExecution(SagaExecutionResult result) {
        return publishStepEvents(result)
                .then(createSuccessResult(result));
    }
    
    private Mono<SagaResult> handleFailedExecution(SagaExecutionResult result) {
        Map<String, Object> materializedInputs = materializeInputs(result.executionContext());
        
        return compensator.compensate(
                result.executionContext().sagaName(),
                result.executionContext().workSaga(),
                result.completionOrder(),
                materializedInputs,
                context
        ).then(Mono.defer(() -> {
            Map<String, Boolean> compensated = extractCompensationFlags(result.completionOrder());
            return Mono.just(SagaResult.from(
                result.executionContext().sagaName(),
                context,
                compensated,
                result.stepErrors(),
                result.executionContext().workSaga().steps.keySet()
            ));
        }));
    }
    
    private Mono<Void> publishStepEvents(SagaExecutionResult result) {
        return Flux.fromIterable(result.completionOrder())
                .concatMap(stepId -> publishStepEventIfConfigured(result.executionContext(), stepId))
                .then();
    }
    
    private Mono<Void> publishStepEventIfConfigured(SagaExecutionContext executionContext, String stepId) {
        StepDefinition stepDef = executionContext.workSaga().steps.get(stepId);
        if (stepDef == null || stepDef.stepEvent == null) {
            return Mono.empty();
        }
        
        Object payload = context.getResult(stepId);
        StepEventEnvelope envelope = createStepEventEnvelope(executionContext, stepId, stepDef, payload);
        
        return stepEventPublisher.publish(envelope);
    }
    
    private StepEventEnvelope createStepEventEnvelope(SagaExecutionContext executionContext, 
                                                    String stepId, 
                                                    StepDefinition stepDef, 
                                                    Object payload) {
        return new StepEventEnvelope(
            executionContext.sagaName(),
            executionContext.sagaId(),
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
            null != context.getResult(stepId) ?  context.getResult(stepId).getClass().getName() : (payload != null ? payload.getClass().getName() : null)
        );
    }
    
    private Mono<SagaResult> createSuccessResult(SagaExecutionResult result) {
        return Mono.just(SagaResult.from(
            result.executionContext().sagaName(),
            context,
            Map.of(), // No compensations on success
            result.stepErrors(),
            result.executionContext().workSaga().steps.keySet()
        ));
    }
    
    private Map<String, Object> materializeInputs(SagaExecutionContext executionContext) {
        Map<String, Object> materialized = inputs != null ? inputs.materializedView(context) : Map.of();
        
        if (!overrideInputs.isEmpty()) {
            Map<String, Object> combined = new LinkedHashMap<>(materialized);
            combined.putAll(overrideInputs);
            return combined;
        }
        
        return materialized;
    }
    
    private Map<String, Boolean> extractCompensationFlags(List<String> completionOrder) {
        Map<String, Boolean> compensated = new HashMap<>();
        for (String stepId : completionOrder) {
            if (StepStatus.COMPENSATED.equals(context.getStatus(stepId))) {
                compensated.put(stepId, true);
            }
        }
        return compensated;
    }
    
    private SagaDefinition expandSagaIfNeeded(SagaDefinition saga, StepInputs inputs, 
                                            Map<String, Object> overrideInputs, SagaContext context) {
        if (inputs == null) {
            return saga;
        }
        // Discover which steps are marked for expansion
        Map<String, ExpandEach> toExpand = new LinkedHashMap<>();
        for (String stepId : saga.steps.keySet()) {
            Object raw = inputs.rawValue(stepId);
            if (raw instanceof ExpandEach ee) {
                toExpand.put(stepId, ee);
            }
        }
        if (toExpand.isEmpty()) {
            return saga;
        }

        SagaDefinition ns = new SagaDefinition(saga.name, saga.bean, saga.target, saga.layerConcurrency);

        // Build map of expansions ids per original
        Map<String, List<String>> expandedIds = new LinkedHashMap<>();

        // First pass: add clones or skip originals marked for expansion
        for (StepDefinition sd : saga.steps.values()) {
            String id = sd.id;
            ExpandEach ee = toExpand.get(id);
            if (ee == null) {
                // Will add later after dependencies are rewritten
                continue;
            }
            List<?> items = ee.items();
            List<String> clones = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                String suffix = ee.idSuffixFn().map(fn -> safeSuffix(fn.apply(item))).orElse("#" + i);
                String cloneId = id + suffix;
                clones.add(cloneId);
                // dependsOn will be rewritten in second pass; start with original's dependsOn
                StepDefinition csd = new StepDefinition(
                        cloneId,
                        sd.compensateName,
                        new ArrayList<>(sd.dependsOn),
                        sd.retry,
                        sd.backoff,
                        sd.timeout,
                        sd.idempotencyKey,
                        sd.jitter,
                        sd.jitterFactor,
                        sd.cpuBound,
                        sd.stepMethod
                );
                csd.stepInvocationMethod = sd.stepInvocationMethod;
                csd.stepBean = sd.stepBean;
                csd.compensateMethod = sd.compensateMethod;
                csd.compensateInvocationMethod = sd.compensateInvocationMethod;
                csd.compensateBean = sd.compensateBean;
                csd.handler = sd.handler;
                csd.compensationRetry = sd.compensationRetry;
                csd.compensationBackoff = sd.compensationBackoff;
                csd.compensationTimeout = sd.compensationTimeout;
                csd.compensationCritical = sd.compensationCritical;
                // propagate step event configuration
                csd.stepEvent = sd.stepEvent;
                if (ns.steps.putIfAbsent(cloneId, csd) != null) {
                    throw new IllegalStateException("Duplicate step id '" + cloneId + "' when expanding '" + id + "'");
                }
                overrideInputs.put(cloneId, item);
            }
            expandedIds.put(id, clones);
        }

        // Second pass: add non-expanded steps and rewrite their dependencies; also rewrite clone deps
        for (StepDefinition sd : saga.steps.values()) {
            String id = sd.id;
            if (toExpand.containsKey(id)) {
                // Update each clone deps
                List<String> clones = expandedIds.getOrDefault(id, List.of());
                for (String cloneId : clones) {
                    StepDefinition csd = ns.steps.get(cloneId);
                    csd.dependsOn.clear();
                    for (String dep : sd.dependsOn) {
                        List<String> repl = expandedIds.get(dep);
                        if (repl != null) csd.dependsOn.addAll(repl);
                        else csd.dependsOn.add(dep);
                    }
                }
                continue;
            }
            // Not expanded: copy and rewrite deps
            List<String> newDeps = new ArrayList<>();
            for (String dep : sd.dependsOn) {
                List<String> repl = expandedIds.get(dep);
                if (repl != null) newDeps.addAll(repl);
                else newDeps.add(dep);
            }
            StepDefinition copy = new StepDefinition(
                    id,
                    sd.compensateName,
                    newDeps,
                    sd.retry,
                    sd.backoff,
                    sd.timeout,
                    sd.idempotencyKey,
                    sd.jitter,
                    sd.jitterFactor,
                    sd.cpuBound,
                    sd.stepMethod
            );
            copy.stepInvocationMethod = sd.stepInvocationMethod;
            copy.stepBean = sd.stepBean;
            copy.compensateMethod = sd.compensateMethod;
            copy.compensateInvocationMethod = sd.compensateInvocationMethod;
            copy.compensateBean = sd.compensateBean;
            copy.handler = sd.handler;
            copy.compensationRetry = sd.compensationRetry;
            copy.compensationBackoff = sd.compensationBackoff;
            copy.compensationTimeout = sd.compensationTimeout;
            copy.compensationCritical = sd.compensationCritical;
            // propagate step event configuration
            copy.stepEvent = sd.stepEvent;
            if (ns.steps.putIfAbsent(id, copy) != null) {
                throw new IllegalStateException("Duplicate step id '" + id + "' while copying saga");
            }
        }
        return ns;
    }
    
    private static String safeSuffix(String s) {
        if (s == null || s.isBlank()) return "";
        // Avoid spaces to keep ids readable in graphs
        return ":" + s.replaceAll("\\s+", "_");
    }
    
    /**
     * Build execution topology layers from saga definition.
     * This is a simplified version that creates layers based on step dependencies.
     */
    private List<List<String>> buildTopologyLayers(SagaDefinition saga) {
        Map<String, Set<String>> dependencies = new HashMap<>();
        Set<String> allSteps = new HashSet<>(saga.steps.keySet());
        
        // Build dependency map
        for (StepDefinition step : saga.steps.values()) {
            dependencies.put(step.id, new HashSet<>(step.dependsOn));
        }
        
        List<List<String>> layers = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        
        while (processed.size() < allSteps.size()) {
            List<String> currentLayer = new ArrayList<>();
            
            // Find steps that have all dependencies satisfied
            for (String stepId : allSteps) {
                if (!processed.contains(stepId)) {
                    Set<String> stepDeps = dependencies.get(stepId);
                    if (stepDeps == null || processed.containsAll(stepDeps)) {
                        currentLayer.add(stepId);
                    }
                }
            }
            
            if (currentLayer.isEmpty()) {
                // Circular dependency or other issue - add remaining steps
                for (String stepId : allSteps) {
                    if (!processed.contains(stepId)) {
                        currentLayer.add(stepId);
                    }
                }
            }
            
            layers.add(currentLayer);
            processed.addAll(currentLayer);
        }
        
        return layers;
    }
    
    // Helper record classes for better structure
    private record SagaExecutionContext(
        SagaDefinition workSaga,
        List<List<String>> layers,
        Map<String, Object> overrideInputs,
        String sagaId
    ) {
        public String sagaName() {
            return workSaga.name;
        }
    }
    
    private record SagaExecutionResult(
        SagaExecutionContext executionContext,
        List<String> completionOrder,
        boolean failed,
        Map<String, Throwable> stepErrors
    ) {}
}