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


package com.firefly.transactional.saga.engine;

import com.firefly.transactional.saga.annotations.Saga;
import com.firefly.transactional.saga.core.OptimizedSagaContext;
import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.saga.core.SagaOptimizationDetector;
import com.firefly.transactional.saga.core.SagaResult;
import com.firefly.transactional.saga.registry.SagaDefinition;
import com.firefly.transactional.saga.registry.SagaRegistry;
import com.firefly.transactional.saga.registry.StepDefinition;
import com.firefly.transactional.shared.core.StepStatus;
import com.firefly.transactional.saga.engine.SagaArgumentResolver;
import com.firefly.transactional.saga.engine.step.StepInvoker;
import com.firefly.transactional.saga.events.NoOpStepEventPublisher;
import com.firefly.transactional.saga.events.StepEventPublisher;
import com.firefly.transactional.saga.observability.SagaEvents;
import com.firefly.transactional.saga.persistence.SagaExecutionState;
import com.firefly.transactional.saga.persistence.SagaPersistenceProvider;
import com.firefly.transactional.saga.persistence.impl.InMemorySagaPersistenceProvider;
import com.firefly.transactional.saga.persistence.serialization.SerializableSagaContext;
import com.firefly.transactional.saga.tools.MethodRefs;
import com.firefly.transactional.saga.validation.SagaValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Core orchestrator that executes Sagas with optional persistence capabilities.
 * <p>
 * Responsibilities:
 * - Build a Directed Acyclic Graph (DAG) of steps from the registry and group them into execution layers
 *   using topological ordering. All steps within the same layer are executed concurrently.
 * - For each step, apply timeout, retry with backoff, idempotency-within-execution, and capture status/latency/attempts.
 * - Store step results in {@link com.firefly.transactional.saga.core.SagaContext} under the step id.
 * - On failure, compensate already completed steps in reverse completion order (best effort; compensation errors are
 *   logged via {@link com.firefly.transactional.observability.SagaEvents} and swallowed in this MVP).
 * - Emit lifecycle events to {@link com.firefly.transactional.observability.SagaEvents} for observability.
 * - Optionally persist saga state for recovery after application restarts (uses in-memory by default for backward compatibility).
 */
public class SagaEngine {

    // Backward-compatible nested enum to preserve public API while using top-level type internally
    public enum CompensationPolicy {
        STRICT_SEQUENTIAL,
        GROUPED_PARALLEL,
        RETRY_WITH_BACKOFF,
        CIRCUIT_BREAKER,
        BEST_EFFORT_PARALLEL
    }

    private static final Logger log = LoggerFactory.getLogger(SagaEngine.class);

    private final SagaRegistry registry;
    private final SagaEvents events;
    private final CompensationPolicy policy;
    private final SagaCompensator compensator;
    private final StepEventPublisher stepEventPublisher;
    private final boolean autoOptimizationEnabled;
    private final SagaExecutionOrchestrator orchestrator;
    private final SagaValidationService validationService;
    private final SagaPersistenceProvider persistenceProvider;
    private final boolean persistenceEnabled;

    /**
         * Create a new SagaEngine.
         * @param registry saga metadata registry discovered from Spring context
         * @param events observability sink receiving lifecycle notifications
         */
        public SagaEngine(SagaRegistry registry, SagaEvents events) {
        this(registry, events, CompensationPolicy.STRICT_SEQUENTIAL, new NoOpStepEventPublisher());
    }

    /**
     * Create a new SagaEngine with a specific compensation policy.
     */
    public SagaEngine(SagaRegistry registry, SagaEvents events, CompensationPolicy policy) {
        this(registry, events, policy, new NoOpStepEventPublisher());
    }

    public SagaEngine(SagaRegistry registry, SagaEvents events, StepEventPublisher stepEventPublisher) {
        this(registry, events, CompensationPolicy.STRICT_SEQUENTIAL, stepEventPublisher);
    }

    public SagaEngine(SagaRegistry registry, SagaEvents events, CompensationPolicy policy, StepEventPublisher stepEventPublisher) {
        this(registry, events, policy, stepEventPublisher, true);
    }

    /**
     * Create a new SagaEngine with all configuration options.
     * @param autoOptimizationEnabled whether to automatically optimize SagaContext based on execution patterns
     */
    public SagaEngine(SagaRegistry registry, SagaEvents events, CompensationPolicy policy, StepEventPublisher stepEventPublisher, boolean autoOptimizationEnabled) {
        this(registry, events, policy, stepEventPublisher, autoOptimizationEnabled, null);
    }

    /**
     * Create a new SagaEngine with all configuration options including validation.
     */
    public SagaEngine(SagaRegistry registry, SagaEvents events, CompensationPolicy policy, StepEventPublisher stepEventPublisher, boolean autoOptimizationEnabled, SagaValidationService validationService) {
        this(registry, events, policy, stepEventPublisher, autoOptimizationEnabled, validationService, null, false);
    }

    /**
     * Create a new SagaEngine with all configuration options including persistence.
     */
    public SagaEngine(SagaRegistry registry, SagaEvents events, CompensationPolicy policy, StepEventPublisher stepEventPublisher, boolean autoOptimizationEnabled, SagaValidationService validationService, SagaPersistenceProvider persistenceProvider, boolean persistenceEnabled) {
        this.registry = registry;
        this.events = events;
        this.policy = (policy != null ? policy : CompensationPolicy.STRICT_SEQUENTIAL);
        this.validationService = validationService;
        this.persistenceProvider = (persistenceProvider != null ? persistenceProvider : new InMemorySagaPersistenceProvider());
        this.persistenceEnabled = persistenceEnabled;

        // Create shared components
        SagaArgumentResolver argumentResolver = new SagaArgumentResolver();
        StepInvoker invoker = new StepInvoker(argumentResolver);

        this.compensator = new SagaCompensator(this.events, this.policy, invoker);
        this.stepEventPublisher = (stepEventPublisher != null ? stepEventPublisher : new NoOpStepEventPublisher());
        this.autoOptimizationEnabled = autoOptimizationEnabled;

        // Create the execution orchestrator
        this.orchestrator = new SagaExecutionOrchestrator(invoker, this.events, this.stepEventPublisher);
    }





    // New API returning a typed SagaResult
    public Mono<SagaResult> execute(String sagaName, StepInputs inputs, SagaContext ctx) {
        Objects.requireNonNull(sagaName, "sagaName");
        SagaDefinition saga = registry.getSaga(sagaName);
        return execute(saga, inputs, ctx);
    }

    /** Convenience overload: automatically creates a SagaContext using the @Saga name. */
    public Mono<SagaResult> execute(String sagaName, StepInputs inputs) {
        Objects.requireNonNull(sagaName, "sagaName");
        SagaDefinition saga = registry.getSaga(sagaName);
        return execute(saga, inputs, null);
    }

    // New overloads: execute by Saga class or method reference
    public Mono<SagaResult> execute(Class<?> sagaClass, StepInputs inputs, SagaContext ctx) {
        Objects.requireNonNull(sagaClass, "sagaClass");
        String sagaName = resolveSagaName(sagaClass);
        return execute(sagaName, inputs, ctx);
    }

    /** Convenience overload: auto-create context. */
    public Mono<SagaResult> execute(Class<?> sagaClass, StepInputs inputs) {
        Objects.requireNonNull(sagaClass, "sagaClass");
        String sagaName = resolveSagaName(sagaClass);
        return execute(sagaName, inputs);
    }



    // Method reference overloads (Class::method) to infer the saga from the declaring class
    public <A, R> Mono<SagaResult> execute(MethodRefs.Fn1<A, R> methodRef, StepInputs inputs, SagaContext ctx) {
        return execute(extractDeclaringClass(methodRef), inputs, ctx);
    }
    public <A, B, R> Mono<SagaResult> execute(MethodRefs.Fn2<A, B, R> methodRef, StepInputs inputs, SagaContext ctx) {
        return execute(extractDeclaringClass(methodRef), inputs, ctx);
    }
    public <A, B, C, R> Mono<SagaResult> execute(MethodRefs.Fn3<A, B, C, R> methodRef, StepInputs inputs, SagaContext ctx) {
        return execute(extractDeclaringClass(methodRef), inputs, ctx);
    }
    public <A, B, C, D, R> Mono<SagaResult> execute(MethodRefs.Fn4<A, B, C, D, R> methodRef, StepInputs inputs, SagaContext ctx) {
        return execute(extractDeclaringClass(methodRef), inputs, ctx);
    }

    public <A, R> Mono<SagaResult> execute(MethodRefs.Fn1<A, R> methodRef, StepInputs inputs) {
        return execute(extractDeclaringClass(methodRef), inputs);
    }
    public <A, B, R> Mono<SagaResult> execute(MethodRefs.Fn2<A, B, R> methodRef, StepInputs inputs) {
        return execute(extractDeclaringClass(methodRef), inputs);
    }
    public <A, B, C, R> Mono<SagaResult> execute(MethodRefs.Fn3<A, B, C, R> methodRef, StepInputs inputs) {
        return execute(extractDeclaringClass(methodRef), inputs);
    }
    public <A, B, C, D, R> Mono<SagaResult> execute(MethodRefs.Fn4<A, B, C, D, R> methodRef, StepInputs inputs) {
        return execute(extractDeclaringClass(methodRef), inputs);
    }

    /**
     * Helper method to extract the declaring class from any MethodRefs functional interface.
     * All MethodRefs interfaces extend Serializable, allowing unified handling.
     */
    private Class<?> extractDeclaringClass(java.io.Serializable methodRef) {
        Method method = MethodRefs.methodOf(methodRef);
        return method.getDeclaringClass();
    }

    private static String resolveSagaName(Class<?> sagaClass) {
        Saga ann = sagaClass.getAnnotation(Saga.class);
        if (ann == null) {
            throw new IllegalArgumentException("Class " + sagaClass.getName() + " is not annotated with @Saga");
        }
        return ann.name();
    }


    /** Convenience overload: automatically creates a SagaContext using the @Saga name. */
    public Mono<SagaResult> execute(String sagaName, Map<String, Object> stepInputs) {
        Objects.requireNonNull(sagaName, "sagaName");
        StepInputs.Builder b = StepInputs.builder();
        if (stepInputs != null) {
            stepInputs.forEach(b::forStepId);
        }
        return execute(sagaName, b.build());
    }


    /** Convenience overload: automatically creates a SagaContext using the @Saga name. */
    public Mono<SagaResult> execute(SagaDefinition saga, StepInputs inputs) {
        return execute(saga, inputs, (SagaContext) null);
    }

    public Mono<SagaResult> execute(SagaDefinition saga, StepInputs inputs, SagaContext ctx) {
        Objects.requireNonNull(saga, "saga");

        // Validate saga definition and inputs if validation service is available
        if (validationService != null) {
            try {
                validationService.validateSagaDefinitionOrThrow(saga);
                validationService.validateSagaInputsOrThrow(saga, inputs);
            } catch (SagaValidationService.SagaValidationException e) {
                return Mono.error(e);
            }
        }

        // Auto-create context if not provided, with automatic optimization
        final com.firefly.transactional.saga.core.SagaContext finalCtx =
                (ctx != null ? ctx : createOptimizedContextIfPossible(saga));

        // Perform optional expansion first to maintain original behavior
        final Map<String, Object> overrideInputs = new LinkedHashMap<>();
        SagaDefinition workSaga = maybeExpandSaga(saga, inputs, overrideInputs, finalCtx);

        // Set saga name in context
        finalCtx.setSagaName(workSaga.name);

        // Preserve topology logging functionality for compatibility
        SagaTopologyReporter.exposeAndLog(workSaga, finalCtx, log);

        // Add persistence checkpoint if enabled
        Mono<Void> persistenceSetup = persistenceEnabled ?
                persistInitialState(workSaga, inputs, finalCtx) :
                Mono.empty();

        // Use the orchestrator to handle execution
        return persistenceSetup
                .then(orchestrator.orchestrate(workSaga, inputs, finalCtx, overrideInputs))
                .flatMap(result -> handleExecutionResult(result, workSaga, inputs, overrideInputs));
    }

    /**
     * Handles the execution result from the orchestrator, including compensation and event publishing.
     */
    private Mono<SagaResult> handleExecutionResult(SagaExecutionOrchestrator.ExecutionResult result,
                                                  SagaDefinition workSaga,
                                                  StepInputs inputs,
                                                  Map<String, Object> overrideInputs) {
        String sagaName = workSaga.name;
        String sagaId = result.getContext().correlationId();
        boolean success = !result.isFailed();

        // Notify completion
        events.onCompleted(sagaName, sagaId, success);

        if (success) {
            // Persist final state with all step results, then mark as completed
            Mono<Void> persistCompletion = persistenceEnabled ?
                    persistFinalState(result.getContext(), true)
                            .then(persistenceProvider.markSagaCompleted(result.getContext().correlationId(), true))
                            .doOnError(error -> log.warn("Failed to persist saga completion: {}",
                                    result.getContext().correlationId(), error))
                            .onErrorResume(error -> Mono.empty()) :
                    Mono.empty();

            // Publish step events and return success result
            return persistCompletion
                    .then(orchestrator.publishStepEvents(result.getCompletionOrder(), workSaga, result.getContext()))
                    .then(Mono.just(SagaResult.from(
                        sagaName,
                        result.getContext(),
                        Map.of(), // No compensations on success
                        result.getStepErrors(),
                        workSaga.steps.keySet()
                    )));
        } else {
            // Persist final state with step results, then mark as failed
            Mono<Void> persistFailure = persistenceEnabled ?
                    persistFinalState(result.getContext(), false)
                            .then(persistenceProvider.markSagaCompleted(result.getContext().correlationId(), false))
                            .doOnError(error -> log.warn("Failed to persist saga failure: {}",
                                    result.getContext().correlationId(), error))
                            .onErrorResume(error -> Mono.empty()) :
                    Mono.empty();

            // Handle failure with compensation
            Map<String, Object> materializedInputs = materializeInputs(inputs, overrideInputs, result.getContext());

            return persistFailure
                    .then(compensator.compensate(sagaName, workSaga, result.getCompletionOrder(), materializedInputs, result.getContext()))
                    .then(Mono.defer(() -> {
                        Map<String, Boolean> compensated = extractCompensationFlags(result.getCompletionOrder(), result.getContext());
                        return Mono.just(SagaResult.from(
                            sagaName,
                            result.getContext(),
                            compensated,
                            result.getStepErrors(),
                            workSaga.steps.keySet()
                        ));
                    }));
        }
    }

    /**
     * Materializes step inputs for compensation.
     */
    private Map<String, Object> materializeInputs(StepInputs inputs, Map<String, Object> overrideInputs, SagaContext context) {
        Map<String, Object> materialized = inputs != null ? inputs.materializedView(context) : Map.of();

        if (!overrideInputs.isEmpty()) {
            Map<String, Object> combined = new LinkedHashMap<>(materialized);
            combined.putAll(overrideInputs);
            return combined;
        }

        return materialized;
    }

    /**
     * Extracts compensation flags from the context.
     */
    private Map<String, Boolean> extractCompensationFlags(List<String> completionOrder, SagaContext context) {
        Map<String, Boolean> compensated = new HashMap<>();
        for (String stepId : completionOrder) {
            if (StepStatus.COMPENSATED.equals(context.getStatus(stepId))) {
                compensated.put(stepId, true);
            }
        }
        return compensated;
    }

    /**
     * Creates an optimized SagaContext if the saga supports sequential execution,
     * otherwise creates a standard SagaContext for concurrent execution.
     */
    private SagaContext createOptimizedContextIfPossible(SagaDefinition saga) {
        if (!autoOptimizationEnabled) {
            return new SagaContext();
        }
        
        boolean canOptimize = SagaOptimizationDetector.canOptimize(saga);
        
        if (canOptimize) {
            // Sequential execution - use optimized context
            OptimizedSagaContext optimized = new OptimizedSagaContext();
            optimized.setSagaName(saga.name);
            return optimized.toStandardContext();
        } else {
            // Concurrent execution - use standard context
            SagaContext standard = new SagaContext();
            standard.setSagaName(saga.name);
            return standard;
        }
    }

    /**
     * Optionally expand steps whose input was marked with ExpandEach at build time via StepInputs.
     * Only concrete values are inspected (no resolver evaluation) to avoid dependency on previous results.
     * Returns the original saga if no expansion is necessary; otherwise returns a derived definition.
     */
    private SagaDefinition maybeExpandSaga(SagaDefinition saga, StepInputs inputs, Map<String, Object> overrideInputs, SagaContext ctx) {
        if (inputs == null) return saga;
        // Discover which steps are marked for expansion
        Map<String, ExpandEach> toExpand = new LinkedHashMap<>();
        for (String stepId : saga.steps.keySet()) {
            Object raw = inputs.rawValue(stepId);
            if (raw instanceof ExpandEach ee) {
                toExpand.put(stepId, ee);
            }
        }
        if (toExpand.isEmpty()) return saga;

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
     * Persists the initial saga state if persistence is enabled.
     */
    private Mono<Void> persistInitialState(SagaDefinition saga, StepInputs inputs, SagaContext context) {
        if (!persistenceEnabled) {
            return Mono.empty();
        }

        String correlationId = context.correlationId();
        log.debug("Persisting initial state for saga: {} ({})", correlationId, saga.name);

        // Build topology layers for state tracking
        List<List<String>> layers = SagaTopology.buildLayers(saga);

        // Materialize step inputs to a serializable map
        // Use materializeAll to ensure all resolvers are evaluated and captured for recovery
        Map<String, Object> stepInputsMap = inputs != null ? inputs.materializeAll(context) : Map.of();

        SagaExecutionState initialState = new SagaExecutionState.Builder()
                .correlationId(correlationId)
                .sagaName(saga.name)
                .status(com.firefly.transactional.saga.persistence.SagaExecutionStatus.RUNNING)
                .startedAt(java.time.Instant.now())
                .lastUpdatedAt(java.time.Instant.now())
                .context(SerializableSagaContext.fromSagaContext(context))
                .stepInputs(stepInputsMap)
                .topologyLayers(layers)
                .currentLayerIndex(0)
                .build();

        return persistenceProvider.persistSagaState(initialState)
                .doOnSuccess(v -> log.debug("Successfully persisted initial state for saga: {}", correlationId))
                .doOnError(error -> log.warn("Failed to persist initial state for saga: {}", correlationId, error))
                .onErrorResume(error -> {
                    // Log the error but don't fail the saga execution
                    log.error("Persistence failed for initial state, continuing without persistence: {}",
                            correlationId, error);
                    return Mono.empty();
                });
    }

    /**
     * Persists the final saga state with all step results before marking as completed.
     */
    private Mono<Void> persistFinalState(SagaContext context, boolean successful) {
        if (!persistenceEnabled) {
            return Mono.empty();
        }

        String correlationId = context.correlationId();
        log.debug("Persisting final state for saga: {} (success: {})", correlationId, successful);

        // Determine the final status
        var finalStatus = successful ?
            com.firefly.transactional.saga.persistence.SagaExecutionStatus.RUNNING : // Will be updated to COMPLETED_SUCCESS by markSagaCompleted
            com.firefly.transactional.saga.persistence.SagaExecutionStatus.FAILED;

        // Create final state with all step results
        SagaExecutionState finalState = new SagaExecutionState.Builder()
                .correlationId(correlationId)
                .sagaName(context.sagaName())
                .status(finalStatus)
                .startedAt(context.startedAt())
                .lastUpdatedAt(java.time.Instant.now())
                .context(SerializableSagaContext.fromSagaContext(context))
                .build();

        return persistenceProvider.persistSagaState(finalState)
                .doOnSuccess(v -> log.debug("Successfully persisted final state for saga: {}", correlationId))
                .doOnError(error -> log.warn("Failed to persist final state for saga: {}", correlationId, error))
                .onErrorResume(error -> {
                    // Log the error but don't fail the saga execution
                    log.error("Persistence failed for final state, continuing: {}", correlationId, error);
                    return Mono.empty();
                });
    }

    /**
     * Gets the persistence provider used by this engine.
     */
    public SagaPersistenceProvider getPersistenceProvider() {
        return persistenceProvider;
    }

    /**
     * Checks if persistence is enabled for this engine.
     */
    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    /**
     * Gets the health status of the persistence provider.
     */
    public Mono<Boolean> isPersistenceHealthy() {
        if (!persistenceEnabled || persistenceProvider == null) {
            return Mono.just(true); // No persistence means always healthy
        }
        return persistenceProvider.isHealthy();
    }

    /**
     * Creates a checkpoint of the current saga state.
     * This method can be called manually to create additional checkpoints.
     */
    public Mono<Void> checkpoint(String correlationId, SagaContext context) {
        if (!persistenceEnabled) {
            return Mono.empty();
        }

        log.debug("Creating manual checkpoint for saga: {}", correlationId);

        // Create a state snapshot
        SagaExecutionState state = new SagaExecutionState.Builder()
                .correlationId(correlationId)
                .sagaName(context.sagaName())
                .status(com.firefly.transactional.saga.persistence.SagaExecutionStatus.RUNNING)
                .startedAt(context.startedAt())
                .lastUpdatedAt(java.time.Instant.now())
                .context(SerializableSagaContext.fromSagaContext(context))
                .build();

        return persistenceProvider.persistSagaState(state)
                .doOnSuccess(v -> log.debug("Manual checkpoint created for saga: {}", correlationId))
                .doOnError(error -> log.warn("Failed to create manual checkpoint for saga: {}",
                        correlationId, error));
    }
}