package com.catalis.transactionalengine.engine;

import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.core.StepStatus;
import com.catalis.transactionalengine.core.SagaResult;
import com.catalis.transactionalengine.observability.SagaEvents;
import com.catalis.transactionalengine.registry.SagaDefinition;
import com.catalis.transactionalengine.registry.SagaRegistry;
import com.catalis.transactionalengine.registry.StepDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import com.catalis.transactionalengine.annotations.Saga;
import com.catalis.transactionalengine.tools.MethodRefs;

/**
 * Core orchestrator that executes Sagas purely in-memory (no persistence).
 * <p>
 * Responsibilities:
 * - Build a Directed Acyclic Graph (DAG) of steps from the registry and group them into execution layers
 *   using topological ordering. All steps within the same layer are executed concurrently.
 * - For each step, apply timeout, retry with backoff, idempotency-within-execution, and capture status/latency/attempts.
 * - Store step results in {@link com.catalis.transactionalengine.core.SagaContext} under the step id.
 * - On failure, compensate already completed steps in reverse completion order (best effort; compensation errors are
 *   logged via {@link com.catalis.transactionalengine.observability.SagaEvents} and swallowed in this MVP).
 * - Emit lifecycle events to {@link com.catalis.transactionalengine.observability.SagaEvents} for observability.
 */
public class SagaEngine {

    // Cache for parameter resolution strategies per method to avoid repeated annotation scans
    private final Map<Method, ArgResolver[]> argResolverCache = new ConcurrentHashMap<>();

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

    /**
         * Create a new SagaEngine.
         * @param registry saga metadata registry discovered from Spring context
         * @param events observability sink receiving lifecycle notifications
         */
        public SagaEngine(SagaRegistry registry, SagaEvents events) {
        this(registry, events, CompensationPolicy.STRICT_SEQUENTIAL);
    }

    /**
     * Create a new SagaEngine with a specific compensation policy.
     */
    public SagaEngine(SagaRegistry registry, SagaEvents events, CompensationPolicy policy) {
        this.registry = registry;
        this.events = events;
        this.policy = policy != null ? policy : CompensationPolicy.STRICT_SEQUENTIAL;
    }

    /**
     * Execute a Saga by its registered name.
     * <p>
     * Semantics:
     * - Builds execution layers from the Saga's DAG and executes steps layer-by-layer. Steps within the same layer run concurrently.
     * - Propagates lifecycle events to {@link SagaEvents} (start and completed).
     * - Aggregates each step's emitted result into the provided {@link SagaContext} under the step id.
     * - On any step failure, aborts subsequent layers, triggers compensation for successfully completed steps
     *   in reverse completion order, and completes with an error.
     *
     * @param sagaName   the name from {@link com.catalis.transactionalengine.annotations.Saga#name()}
     * @param stepInputs inputs per step id (may be null for steps that don't require input)
     * @param ctx        in-memory execution context (carries correlation id, headers, and result/status maps)
     * @return Mono emitting an immutable view of step results if the saga completed successfully; otherwise errors
     */
    @Deprecated
    public Mono<Map<String, Object>> run(String sagaName,
                                         Map<String, Object> stepInputs,
                                         SagaContext ctx) {
        Objects.requireNonNull(sagaName, "sagaName");
        Objects.requireNonNull(ctx, "ctx");
        SagaDefinition saga = registry.getSaga(sagaName);
        return run(saga, stepInputs, ctx);
    }

    /**
     * Execute a Saga using the provided definition (programmatic or from registry).
     * Keeps the same semantics as the name-based overload.
     */
    @Deprecated
    public Mono<Map<String, Object>> run(SagaDefinition saga,
                                         Map<String, Object> stepInputs,
                                         SagaContext ctx) {
        Objects.requireNonNull(saga, "saga");
        Objects.requireNonNull(ctx, "ctx");
        String sagaName = saga.name;
        String sagaId = ctx.correlationId();
        events.onStart(sagaName, sagaId);
        events.onStart(sagaName, sagaId, ctx);

        // Build layers using indegree
        List<List<String>> layers = buildLayers(saga);
        if (log.isInfoEnabled()) {
            int stepsCount = saga.steps != null ? saga.steps.size() : 0;
            int layersCount = layers.size();
            String layerSizes = layers.stream().map(l -> Integer.toString(l.size())).collect(java.util.stream.Collectors.joining(","));
            log.info(json(
                    "saga_lifecycle","start",
                    "saga", sagaName,
                    "sagaId", sagaId,
                    "steps_count", Integer.toString(stepsCount),
                    "layers_count", Integer.toString(layersCount),
                    "layer_sizes", layerSizes,
                    "policy", String.valueOf(policy)
            ));
        }
        List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean failed = new AtomicBoolean(false);
        Map<String, Throwable> stepErrors = new ConcurrentHashMap<>();

        return Flux.fromIterable(layers)
                .concatMap(layer -> {
                    if (failed.get()) {
                        // Skip remaining layers but allow finalization (onCompleted/compensation) to run
                        return Mono.empty();
                    }
                    int concurrency = saga.layerConcurrency > 0 ? saga.layerConcurrency : layer.size();
                    return Flux.fromIterable(layer)
                            .flatMap(stepId ->
                                    executeStep(sagaName, saga, stepId, stepInputs != null ? stepInputs.get(stepId) : null, ctx)
                                            .doOnSuccess(res -> completionOrder.add(stepId))
                                            .onErrorResume(err -> { failed.set(true); stepErrors.put(stepId, err); return Mono.empty(); })
                                    , concurrency)
                            .then();
                })
                .then(Mono.defer(() -> {
                    boolean success = !failed.get();
                    events.onCompleted(sagaName, sagaId, success);
                    if (success) {
                        return Mono.just(ctx.stepResultsView());
                    }
                    return compensate(sagaName, saga, completionOrder, stepInputs != null ? stepInputs : Map.of(), ctx)
                            .then(Mono.error(stepErrors.values().stream().findFirst().orElse(new RuntimeException("Saga failed"))));
                }));
    }

    /** New API: typed StepInputs without exposing Map in public interface. */
    @Deprecated
    public Mono<Map<String, Object>> run(String sagaName, StepInputs inputs, SagaContext ctx) {
        Objects.requireNonNull(sagaName, "sagaName");
        Objects.requireNonNull(ctx, "ctx");
        SagaDefinition saga = registry.getSaga(sagaName);
        return run(saga, inputs, ctx);
    }

    @Deprecated
    public Mono<Map<String, Object>> run(SagaDefinition saga, StepInputs inputs, SagaContext ctx) {
        Objects.requireNonNull(saga, "saga");
        Objects.requireNonNull(ctx, "ctx");
        String sagaName = saga.name;
        String sagaId = ctx.correlationId();
        events.onStart(sagaName, sagaId);
        events.onStart(sagaName, sagaId, ctx);

        // Build layers using indegree
        List<List<String>> layers = buildLayers(saga);
        if (log.isInfoEnabled()) {
            int stepsCount = saga.steps != null ? saga.steps.size() : 0;
            int layersCount = layers.size();
            String layerSizes = layers.stream().map(l -> Integer.toString(l.size())).collect(java.util.stream.Collectors.joining(","));
            log.info(json(
                    "saga_lifecycle","start",
                    "saga", sagaName,
                    "sagaId", sagaId,
                    "steps_count", Integer.toString(stepsCount),
                    "layers_count", Integer.toString(layersCount),
                    "layer_sizes", layerSizes,
                    "policy", String.valueOf(policy)
            ));
        }
        List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean failed = new AtomicBoolean(false);
        Map<String, Throwable> stepErrors = new ConcurrentHashMap<>();

        return Flux.fromIterable(layers)
                .concatMap(layer -> {
                    if (failed.get()) {
                        return Mono.empty();
                    }
                    int concurrency = saga.layerConcurrency > 0 ? saga.layerConcurrency : layer.size();
                    return Flux.fromIterable(layer)
                            .flatMap(stepId ->
                                    executeStep(sagaName, saga, stepId, inputs != null ? inputs.resolveFor(stepId, ctx) : null, ctx)
                                            .doOnSuccess(res -> completionOrder.add(stepId))
                                            .onErrorResume(err -> { failed.set(true); stepErrors.put(stepId, err); return Mono.empty(); })
                                    , concurrency)
                            .then();
                })
                .then(Mono.defer(() -> {
                    boolean success = !failed.get();
                    events.onCompleted(sagaName, sagaId, success);
                    if (success) {
                        return Mono.just(ctx.stepResultsView());
                    }
                    Map<String, Object> materialized = inputs != null ? inputs.materializedView(ctx) : Map.of();
                    return compensate(sagaName, saga, completionOrder, materialized, ctx)
                            .then(Mono.error(stepErrors.values().stream().findFirst().orElse(new RuntimeException("Saga failed"))));
                }));
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

    /** Map-based convenience; prefer StepInputs. */
    @Deprecated
    public Mono<SagaResult> execute(Class<?> sagaClass, Map<String, Object> stepInputs, SagaContext ctx) {
        Objects.requireNonNull(sagaClass, "sagaClass");
        String sagaName = resolveSagaName(sagaClass);
        return execute(sagaName, stepInputs, ctx);
    }

    /** Map-based convenience; prefer StepInputs. */
    @Deprecated
    public Mono<SagaResult> execute(Class<?> sagaClass, Map<String, Object> stepInputs) {
        Objects.requireNonNull(sagaClass, "sagaClass");
        String sagaName = resolveSagaName(sagaClass);
        return execute(sagaName, stepInputs);
    }

    // Method reference overloads (Class::method) to infer the saga from the declaring class
    public <A, R> Mono<SagaResult> execute(MethodRefs.Fn1<A, R> methodRef, StepInputs inputs, SagaContext ctx) {
        Method m = MethodRefs.methodOf(methodRef);
        return execute(m.getDeclaringClass(), inputs, ctx);
    }
    public <A, B, R> Mono<SagaResult> execute(MethodRefs.Fn2<A, B, R> methodRef, StepInputs inputs, SagaContext ctx) {
        Method m = MethodRefs.methodOf(methodRef);
        return execute(m.getDeclaringClass(), inputs, ctx);
    }
    public <A, B, C, R> Mono<SagaResult> execute(MethodRefs.Fn3<A, B, C, R> methodRef, StepInputs inputs, SagaContext ctx) {
        Method m = MethodRefs.methodOf(methodRef);
        return execute(m.getDeclaringClass(), inputs, ctx);
    }
    public <A, B, C, D, R> Mono<SagaResult> execute(MethodRefs.Fn4<A, B, C, D, R> methodRef, StepInputs inputs, SagaContext ctx) {
        Method m = MethodRefs.methodOf(methodRef);
        return execute(m.getDeclaringClass(), inputs, ctx);
    }

    public <A, R> Mono<SagaResult> execute(MethodRefs.Fn1<A, R> methodRef, StepInputs inputs) {
        Method m = MethodRefs.methodOf(methodRef);
        return execute(m.getDeclaringClass(), inputs);
    }
    public <A, B, R> Mono<SagaResult> execute(MethodRefs.Fn2<A, B, R> methodRef, StepInputs inputs) {
        Method m = MethodRefs.methodOf(methodRef);
        return execute(m.getDeclaringClass(), inputs);
    }
    public <A, B, C, R> Mono<SagaResult> execute(MethodRefs.Fn3<A, B, C, R> methodRef, StepInputs inputs) {
        Method m = MethodRefs.methodOf(methodRef);
        return execute(m.getDeclaringClass(), inputs);
    }
    public <A, B, C, D, R> Mono<SagaResult> execute(MethodRefs.Fn4<A, B, C, D, R> methodRef, StepInputs inputs) {
        Method m = MethodRefs.methodOf(methodRef);
        return execute(m.getDeclaringClass(), inputs);
    }

    private static String resolveSagaName(Class<?> sagaClass) {
        Saga ann = sagaClass.getAnnotation(Saga.class);
        if (ann == null) {
            throw new IllegalArgumentException("Class " + sagaClass.getName() + " is not annotated with @Saga");
        }
        return ann.name();
    }

    /** Transitional convenience: allow Map-based inputs; prefer StepInputs DSL. */
    @Deprecated
    public Mono<SagaResult> execute(String sagaName, Map<String, Object> stepInputs, SagaContext ctx) {
        Objects.requireNonNull(sagaName, "sagaName");
        Objects.requireNonNull(ctx, "ctx");
        StepInputs.Builder b = StepInputs.builder();
        if (stepInputs != null) {
            stepInputs.forEach(b::forStepId);
        }
        return execute(sagaName, b.build(), ctx);
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

    /** Transitional convenience: allow Map-based inputs; prefer StepInputs DSL. */
    @Deprecated
    public Mono<SagaResult> execute(SagaDefinition saga, Map<String, Object> stepInputs, SagaContext ctx) {
        Objects.requireNonNull(saga, "saga");
        Objects.requireNonNull(ctx, "ctx");
        StepInputs.Builder b = StepInputs.builder();
        if (stepInputs != null) {
            stepInputs.forEach(b::forStepId);
        }
        return execute(saga, b.build(), ctx);
    }

    /** Convenience overload: automatically creates a SagaContext using the @Saga name. */
    public Mono<SagaResult> execute(SagaDefinition saga, StepInputs inputs) {
        return execute(saga, inputs, (SagaContext) null);
    }

    public Mono<SagaResult> execute(SagaDefinition saga, StepInputs inputs, SagaContext ctx) {
        Objects.requireNonNull(saga, "saga");
        // Auto-create context if not provided
        final com.catalis.transactionalengine.core.SagaContext finalCtx =
                (ctx != null ? ctx : new com.catalis.transactionalengine.core.SagaContext());
        // Always set the saga name on the context for visibility
        finalCtx.setSagaName(saga.name);

        // Perform optional expansion: clone steps for inputs marked with ExpandEach
        final Map<String, Object> overrideInputs = new LinkedHashMap<>();
        SagaDefinition workSaga = maybeExpandSaga(saga, inputs, overrideInputs, finalCtx);

        String sagaName = workSaga.name;
        String sagaId = finalCtx.correlationId();
        events.onStart(sagaName, sagaId);
        events.onStart(sagaName, sagaId, finalCtx);

        List<List<String>> layers = buildLayers(workSaga);
        List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean failed = new AtomicBoolean(false);
        Map<String, Throwable> stepErrors = new ConcurrentHashMap<>();
        Map<String, Boolean> compensated = new ConcurrentHashMap<>();

        return Flux.fromIterable(layers)
                .concatMap(layer -> {
                    if (failed.get()) return Mono.empty();
                    var executions = layer.stream().map(stepId -> {
                                Object in = overrideInputs.containsKey(stepId)
                                        ? overrideInputs.get(stepId)
                                        : (inputs != null ? inputs.resolveFor(stepId, finalCtx) : null);
                                return executeStep(sagaName, workSaga, stepId, in, finalCtx)
                                        .doOnSuccess(v -> completionOrder.add(stepId))
                                        .onErrorResume(err -> { failed.set(true); stepErrors.put(stepId, err); return Mono.empty(); });
                            }
                    ).toList();
                    return Mono.when(executions);
                })
                .then(Mono.defer(() -> {
                    boolean success = !failed.get();
                    events.onCompleted(sagaName, sagaId, success);
                    if (success) {
                        return Mono.just(SagaResult.from(sagaName, finalCtx, compensated, stepErrors, workSaga.steps.keySet()));
                    }
                    Map<String, Object> materialized = inputs != null ? inputs.materializedView(finalCtx) : Map.of();
                    if (!overrideInputs.isEmpty()) {
                        Map<String, Object> mat2 = new LinkedHashMap<>(materialized);
                        mat2.putAll(overrideInputs);
                        materialized = mat2;
                    }
                    return compensate(sagaName, workSaga, completionOrder, materialized, finalCtx)
                            .then(Mono.defer(() -> {
                                // derive compensated flags from context statuses
                                for (String id : completionOrder) {
                                    if (StepStatus.COMPENSATED.equals(finalCtx.getStatus(id))) {
                                        compensated.put(id, true);
                                    }
                                }
                                return Mono.just(SagaResult.from(sagaName, finalCtx, compensated, stepErrors, workSaga.steps.keySet()));
                            }));
                }));
    }

    /**
         * Build execution layers (topological levels) from the saga's step graph.
         * Steps with indegree=0 form the first layer; removing them may unlock subsequent layers.
         * No cycle detection here (validated in registry). Order inside a layer is not guaranteed.
         */
        private List<List<String>> buildLayers(SagaDefinition saga) {
        // Use LinkedHashMap to preserve deterministic iteration order based on saga.steps insertion
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (String id : saga.steps.keySet()) {
            indegree.putIfAbsent(id, 0);
            adj.putIfAbsent(id, new ArrayList<>());
        }
        for (StepDefinition sd : saga.steps.values()) {
            for (String dep : sd.dependsOn) {
                indegree.put(sd.id, indegree.getOrDefault(sd.id, 0) + 1);
                adj.get(dep).add(sd.id);
            }
        }
        List<List<String>> layers = new ArrayList<>();
        Queue<String> q = new ArrayDeque<>();
        // Fill initial queue in the exact order of saga.steps keys for determinism
        for (String id : saga.steps.keySet()) {
            if (indegree.get(id) == 0) q.add(id);
        }
        while (!q.isEmpty()) {
            int size = q.size();
            List<String> layer = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                String u = q.poll();
                layer.add(u);
                for (String v : adj.getOrDefault(u, List.of())) {
                    indegree.put(v, indegree.get(v) - 1);
                    if (indegree.get(v) == 0) q.add(v);
                }
            }
            layers.add(layer);
        }
        return layers;
    }

/**
     * Execute a single step according to its configuration.
     * - Honors per-run idempotency using {@link SagaContext#markIdempotent(String)} if an idempotencyKey was provided.
     * - Applies retry with backoff, optional jitter, and timeout via the attemptCall/attemptCallHandler helpers.
     * - Updates status, attempts and latency on the {@link SagaContext} and emits step success/failure events.
     */
    private Mono<Void> executeStep(String sagaName,
                                   SagaDefinition saga,
                                   String stepId,
                                   Object input,
                                   SagaContext ctx) {
        StepDefinition sd = saga.steps.get(stepId);
        if (sd == null) return Mono.error(new IllegalArgumentException("Unknown step: " + stepId));

        // Idempotency within run
        if (sd.idempotencyKey != null && !sd.idempotencyKey.isEmpty()) {
            if (ctx.hasIdempotencyKey(sd.idempotencyKey)) {
                log.info(json(
                                "saga_step","skipped_idempotent",
                                "saga", sagaName,
                                "sagaId", ctx.correlationId(),
                                "stepId", stepId,
                                "reason", "idempotency_key_present"
                        ));
                // Mark as done to reflect that the business effect was already applied in this run
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
            String inputPreview = summarize(input, 200);
            log.info(json(
                    "saga_step","exec_config",
                    "saga", sagaName,
                    "sagaId", ctx.correlationId(),
                    "stepId", stepId,
                    "mode", mode,
                    "timeoutMs", Long.toString(timeoutMs),
                    "retry", Integer.toString(sd.retry),
                    "backoffMs", Long.toString(backoffMs),
                    "jitter", Boolean.toString(sd.jitter),
                    "jitterFactor", Double.toString(sd.jitterFactor),
                    "cpuBound", Boolean.toString(sd.cpuBound),
                    "input_type", inputType,
                    "input_preview", inputPreview
            ));
        }
        if (sd.handler != null) {
            execution = attemptCallHandler(sd, input, ctx, timeoutMs, sd.retry, backoffMs, sd.jitter, sd.jitterFactor, stepId);
        } else {
            Method invokeMethod = sd.stepInvocationMethod != null ? sd.stepInvocationMethod : sd.stepMethod;
            Object targetBean = sd.stepBean != null ? sd.stepBean : saga.bean;
            execution = attemptCall(targetBean, invokeMethod, sd.stepMethod, input, ctx, timeoutMs, sd.retry, backoffMs, sd.jitter, sd.jitterFactor, stepId);
        }
        if (sd.cpuBound) {
            execution = execution.subscribeOn(Schedulers.parallel());
        }
        return execution
                .doOnNext(res -> {
                    if (log.isInfoEnabled()) {
                        String resultType = res != null ? res.getClass().getName() : "null";
                        String resultPreview = summarize(res, 200);
                        log.info(json(
                                "saga_step","result",
                                "saga", sagaName,
                                "sagaId", ctx.correlationId(),
                                "stepId", stepId,
                                "result_type", resultType,
                                "result_preview", resultPreview
                        ));
                    }
                    ctx.putResult(stepId, res);
                    // If this step was defined by method, support @SetVariable; handler-based steps skip this
                    if (sd.stepMethod != null) {
                        var setVarAnn = sd.stepMethod.getAnnotation(com.catalis.transactionalengine.annotations.SetVariable.class);
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
                    String errMsg = safeString(err.getMessage(), 500);
                    log.warn(json(
                            "saga_step","error",
                            "saga", sagaName,
                            "sagaId", ctx.correlationId(),
                            "stepId", stepId,
                            "attempts", Integer.toString(attempts),
                            "latencyMs", Long.toString(latency),
                            "error_class", errClass,
                            "error_msg", errMsg
                    ));
                    events.onStepFailed(sagaName, ctx.correlationId(), stepId, err, attempts, latency);
                });
    }

/**
     * Attempt to invoke the provided method with retry/backoff/timeout semantics.
     * Increments attempts counter on the context before each attempt.
     *
     * @param bean              target object
     * @param invocationMethod  method to invoke (proxy-safe)
     * @param annotationSource  method that carries the annotations for parameter resolution
     * @param input             input argument (may be null)
     * @param ctx               saga context
     * @param timeoutMs         per-attempt timeout in milliseconds (0 disables)
     * @param retry             number of retries after the first attempt (0 means no retries)
     * @param backoffMs         delay between retries in milliseconds
     * @param stepId            step identifier for attempts bookkeeping
     */
    private Mono<Object> attemptCall(Object bean, Method invocationMethod, Method annotationSource, Object input, SagaContext ctx,
                                     long timeoutMs, int retry, long backoffMs, boolean jitter, double jitterFactor, String stepId) {
        return Mono.defer(() -> invokeMono(bean, invocationMethod, annotationSource, input, ctx))
                .transform(m -> timeoutMs > 0 ? m.timeout(Duration.ofMillis(timeoutMs)) : m)
                .onErrorResume(err -> {
                    if (retry > 0) {
                        long delay = computeDelay(backoffMs, jitter, jitterFactor);
                        return Mono.delay(Duration.ofMillis(Math.max(0, delay)))
                                .then(attemptCall(bean, invocationMethod, annotationSource, input, ctx, timeoutMs, retry - 1, backoffMs, jitter, jitterFactor, stepId));
                    }
                    return Mono.error(err);
                })
                .doFirst(() -> ctx.incrementAttempts(stepId));
    }

    private long computeDelay(long backoffMs, boolean jitter, double jitterFactor) {
        if (backoffMs <= 0) return 0L;
        if (!jitter) return backoffMs;
        double f = Math.max(0.0d, Math.min(jitterFactor, 1.0d));
        double min = backoffMs * (1.0d - f);
        double max = backoffMs * (1.0d + f);
        long v = Math.round(ThreadLocalRandom.current().nextDouble(min, max));
        return Math.max(0L, v);
    }

    private static String summarize(Object obj, int max) {
        if (obj == null) return "null";
        String s;
        try { s = String.valueOf(obj); } catch (Throwable ignore) { s = obj.getClass().getName(); }
        return safeString(s, max);
    }

    private static String safeString(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max)) + "...";
    }

    private static String json(String... kv) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(esc(kv[i])).append('"').append(':');
            sb.append('"').append(esc(kv[i + 1] == null ? "" : kv[i + 1])).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
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
     * Attempt to invoke a handler-based step with retry/backoff/timeout semantics.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Mono<Object> attemptCallHandler(StepDefinition sd, Object input, SagaContext ctx,
                                            long timeoutMs, int retry, long backoffMs, boolean jitter, double jitterFactor, String stepId) {
        Mono<Object> base = Mono.defer(() -> {
            if (sd.handler == null) return Mono.error(new IllegalStateException("No handler for step " + sd.id));
            StepHandler h = sd.handler;
            return h.execute(input, ctx).cast(Object.class);
        });
        if (timeoutMs > 0) {
            base = base.timeout(Duration.ofMillis(timeoutMs));
        }
        Mono<Object> finalBase = base;
        return finalBase
                .onErrorResume(err -> {
                    if (retry > 0) {
                        long delay = computeDelay(backoffMs, jitter, jitterFactor);
                        return Mono.delay(Duration.ofMillis(Math.max(0, delay)))
                                .then(attemptCallHandler(sd, input, ctx, timeoutMs, retry - 1, backoffMs, jitter, jitterFactor, stepId));
                    }
                    return Mono.error(err);
                })
                .doFirst(() -> ctx.incrementAttempts(stepId));
    }

/**
     * Invoke the given method on the bean and normalize the result to a Mono.
     *
     * Supported target method signatures:
     * - (input, SagaContext)
     * - (input)
     * - (SagaContext)
     * - ()
     *
     * If the return value is not a Mono, it will be wrapped using Mono.justOrEmpty.
     * Invocation exceptions are unwrapped to the target exception when applicable.
     */
    @SuppressWarnings("unchecked")
    private Mono<Object> invokeMono(Object bean, Method method, Object input, SagaContext ctx) {
        try {
            Object[] args = resolveArguments(method, input, ctx);
            Object result = method.invoke(bean, args);
            if (result instanceof Mono<?> mono) {
                return (Mono<Object>) mono;
            }
            return Mono.justOrEmpty(result);
        } catch (InvocationTargetException e) {
            return Mono.error(e.getTargetException());
        } catch (Throwable t) {
            return Mono.error(t);
        }
    }

    // Overload: use a separate method as annotation source while invoking another (proxy-safe) method
    private Mono<Object> invokeMono(Object bean, Method invocationMethod, Method annotationSource, Object input, SagaContext ctx) {
        try {
            Object[] args = resolveArguments(annotationSource, input, ctx);
            Object result = invocationMethod.invoke(bean, args);
            if (result instanceof Mono<?> mono) {
                return (Mono<Object>) mono;
            }
            return Mono.justOrEmpty(result);
        } catch (InvocationTargetException e) {
            return Mono.error(e.getTargetException());
        } catch (Throwable t) {
            return Mono.error(t);
        }
    }

    private Object[] resolveArguments(Method method, Object input, SagaContext ctx) {
        ArgResolver[] resolvers = argResolverCache.computeIfAbsent(method, this::compileArgResolvers);
        Object[] args = new Object[resolvers.length];
        for (int i = 0; i < resolvers.length; i++) {
            args[i] = resolvers[i].resolve(input, ctx);
        }
        return args;
    }

    private ArgResolver[] compileArgResolvers(Method method) {
        var params = method.getParameters();
        if (params.length == 0) return new ArgResolver[0];
        ArgResolver[] resolvers = new ArgResolver[params.length];
        boolean implicitUsed = false;
        for (int i = 0; i < params.length; i++) {
            var p = params[i];
            Class<?> type = p.getType();

            if (SagaContext.class.isAssignableFrom(type)) {
                resolvers[i] = (in, c) -> c;
                continue;
            }

            var inputAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Input.class);
            if (inputAnn != null) {
                String key = inputAnn.value();
                if (key == null || key.isBlank()) {
                    resolvers[i] = (in, c) -> in;
                } else {
                    resolvers[i] = (in, c) -> (in instanceof Map<?, ?> m) ? m.get(key) : null;
                }
                continue;
            }

            var fromStepAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.FromStep.class);
            if (fromStepAnn != null) {
                String ref = fromStepAnn.value();
                resolvers[i] = (in, c) -> c.getResult(ref);
                continue;
            }

            var headerAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Header.class);
            if (headerAnn != null) {
                String name = headerAnn.value();
                resolvers[i] = (in, c) -> c.headers().get(name);
                continue;
            }

            var headersAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Headers.class);
            if (headersAnn != null) {
                resolvers[i] = (in, c) -> c.headers();
                continue;
            }

            var variableAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Variable.class);
            if (variableAnn != null) {
                String name = variableAnn.value();
                resolvers[i] = (in, c) -> c.getVariable(name);
                continue;
            }

            var variablesAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Variables.class);
            if (variablesAnn != null) {
                resolvers[i] = (in, c) -> c.variables();
                continue;
            }

            if (!implicitUsed) {
                resolvers[i] = (in, c) -> in;
                implicitUsed = true;
            } else {
                String msg = "Unresolvable parameter '" + p.getName() + "' at position " + i +
                        " in method " + method + ". Use @Input/@FromStep/@Header/@Headers/@Variable/@Variables or SagaContext.";
                throw new IllegalStateException(msg);
            }
        }
        return resolvers;
    }

    @FunctionalInterface
    private interface ArgResolver {
        Object resolve(Object input, SagaContext ctx);
    }

/**
     * Compensate previously completed steps in reverse completion order.
     * Best-effort: compensation errors are logged via {@link SagaEvents#onCompensated(String, String, String, Throwable)}
     * and swallowed in this MVP so that all compensations are attempted.
     */
    private Mono<Void> compensate(String sagaName,
                                  SagaDefinition saga,
                                  List<String> completionOrder,
                                  Map<String, Object> stepInputs,
                                  SagaContext ctx) {
        if (log.isInfoEnabled()) {
            int completedCount = completionOrder != null ? completionOrder.size() : 0;
            log.info(json(
                        "saga_compensation","start",
                        "saga", sagaName,
                        "sagaId", ctx.correlationId(),
                        "completed_steps_count", Integer.toString(completedCount),
                        "policy", String.valueOf(policy)
                    ));
        }
        return switch (policy) {
            case GROUPED_PARALLEL -> compensateGroupedByLayer(sagaName, saga, completionOrder, stepInputs, ctx);
            case RETRY_WITH_BACKOFF -> compensateSequentialWithRetries(sagaName, saga, completionOrder, stepInputs, ctx);
            case CIRCUIT_BREAKER -> compensateSequentialWithCircuitBreaker(sagaName, saga, completionOrder, stepInputs, ctx);
            case BEST_EFFORT_PARALLEL -> compensateBestEffortParallel(sagaName, saga, completionOrder, stepInputs, ctx);
            case STRICT_SEQUENTIAL -> compensateSequential(sagaName, saga, completionOrder, stepInputs, ctx);
        };
    }

    private Mono<Void> compensateSequential(String sagaName,
                                            SagaDefinition saga,
                                            List<String> completionOrder,
                                            Map<String, Object> stepInputs,
                                            SagaContext ctx) {
        List<String> reversed = new ArrayList<>(completionOrder);
        Collections.reverse(reversed);
        return Flux.fromIterable(reversed)
                .concatMap(stepId -> compensateOne(sagaName, saga, stepId, stepInputs, ctx))
                .then();
    }

    private Mono<Void> compensateGroupedByLayer(String sagaName,
                                                SagaDefinition saga,
                                                List<String> completionOrder,
                                                Map<String, Object> stepInputs,
                                                SagaContext ctx) {
        // Build layers and keep only steps that actually completed
        List<List<String>> layers = buildLayers(saga);
        Set<String> completed = new LinkedHashSet<>(completionOrder);
        List<List<String>> filtered = new ArrayList<>();
        for (List<String> layer : layers) {
            List<String> lf = layer.stream().filter(completed::contains).toList();
            if (!lf.isEmpty()) filtered.add(lf);
        }
        Collections.reverse(filtered);
        return Flux.fromIterable(filtered)
                .concatMap(layer -> Mono.when(layer.stream()
                                .map(stepId -> compensateOne(sagaName, saga, stepId, stepInputs, ctx))
                                .toList()
                        )
                        .doFinally(s -> {
                            try { events.onCompensationBatchCompleted(sagaName, ctx.correlationId(), layer, true); } catch (Throwable ignored) {}
                        })
                )
                .then();
    }

    private Mono<Void> compensateOne(String sagaName,
                                     SagaDefinition saga,
                                     String stepId,
                                     Map<String, Object> stepInputs,
                                     SagaContext ctx) {
        StepDefinition sd = saga.steps.get(stepId);
        if (sd == null) return Mono.empty();
        events.onCompensationStarted(sagaName, ctx.correlationId(), stepId);
        if (sd.handler != null) {
            Object input = stepInputs.get(stepId);
            Object result = ctx.getResult(stepId);
            Object arg = input != null ? input : result; // simple heuristic; handler may ignore
            return sd.handler.compensate(arg, ctx)
                    .doOnSuccess(v -> {
                        ctx.setStatus(stepId, StepStatus.COMPENSATED);
                        events.onCompensated(sagaName, ctx.correlationId(), stepId, null);
                    })
                    .doOnError(err -> { ctx.putCompensationError(stepId, err); events.onCompensated(sagaName, ctx.correlationId(), stepId, err); })
                    .onErrorResume(err -> Mono.empty());
        }
        Method comp = sd.compensateInvocationMethod != null ? sd.compensateInvocationMethod : sd.compensateMethod;
        if (comp == null) return Mono.empty();
        Object arg = resolveCompensationArg(comp, stepInputs.get(stepId), ctx.getResult(stepId));
        Object targetBean = sd.compensateBean != null ? sd.compensateBean : saga.bean;
        return invokeMono(targetBean, comp, arg, ctx)
                .doOnNext(obj -> ctx.putCompensationResult(stepId, obj))
                .doOnSuccess(v -> {
                    ctx.setStatus(stepId, StepStatus.COMPENSATED);
                    events.onCompensated(sagaName, ctx.correlationId(), stepId, null);
                })
                .doOnError(err -> { ctx.putCompensationError(stepId, err); events.onCompensated(sagaName, ctx.correlationId(), stepId, err); })
                .onErrorResume(err -> Mono.empty())
                .then();
    }

    // --- New compensation policies and helpers ---
    private static class CompParams {
        final int retry; final long timeoutMs; final long backoffMs; final boolean jitter; final double jitterFactor;
        CompParams(int retry, long timeoutMs, long backoffMs, boolean jitter, double jitterFactor) {
            this.retry = retry; this.timeoutMs = timeoutMs; this.backoffMs = backoffMs; this.jitter = jitter; this.jitterFactor = jitterFactor;
        }
    }

    private CompParams computeCompParams(StepDefinition sd) {
        int retry = sd.compensationRetry != null ? sd.compensationRetry : sd.retry;
        long timeoutMs = (sd.compensationTimeout != null ? sd.compensationTimeout : sd.timeout).toMillis();
        long backoffMs = (sd.compensationBackoff != null ? sd.compensationBackoff : sd.backoff).toMillis();
        return new CompParams(Math.max(0, retry), Math.max(0L, timeoutMs), Math.max(0L, backoffMs), sd.jitter, sd.jitterFactor);
    }

    private Mono<Boolean> compensateOneWithResult(String sagaName,
                                                  SagaDefinition saga,
                                                  String stepId,
                                                  Map<String, Object> stepInputs,
                                                  SagaContext ctx) {
        StepDefinition sd = saga.steps.get(stepId);
        if (sd == null) return Mono.just(true);
        events.onCompensationStarted(sagaName, ctx.correlationId(), stepId);
        CompParams p = computeCompParams(sd);
        if (sd.handler != null) {
            Object input = stepInputs.get(stepId);
            Object result = ctx.getResult(stepId);
            Object arg = input != null ? input : result;
            return attemptCompensateHandler(sd, arg, ctx, p.timeoutMs, p.retry, p.backoffMs, p.jitter, p.jitterFactor, sagaName, stepId)
                    .thenReturn(true)
                    .doOnSuccess(v -> { ctx.setStatus(stepId, StepStatus.COMPENSATED); events.onCompensated(sagaName, ctx.correlationId(), stepId, null); })
                    .onErrorResume(err -> {
                        ctx.putCompensationError(stepId, err);
                        events.onCompensated(sagaName, ctx.correlationId(), stepId, err);
                        return Mono.just(false);
                    });
        }
        Method comp = sd.compensateInvocationMethod != null ? sd.compensateInvocationMethod : sd.compensateMethod;
        if (comp == null) return Mono.just(true);
        Object arg = resolveCompensationArg(comp, stepInputs.get(stepId), ctx.getResult(stepId));
        Object targetBean = sd.compensateBean != null ? sd.compensateBean : saga.bean;
        return attemptCompensateMethod(targetBean, comp, sd.compensateMethod, arg, ctx, p.timeoutMs, p.retry, p.backoffMs, p.jitter, p.jitterFactor, sagaName, stepId)
                .doOnNext(obj -> ctx.putCompensationResult(stepId, obj))
                .thenReturn(true)
                .doOnSuccess(v -> { ctx.setStatus(stepId, StepStatus.COMPENSATED); events.onCompensated(sagaName, ctx.correlationId(), stepId, null); })
                .onErrorResume(err -> { ctx.putCompensationError(stepId, err); events.onCompensated(sagaName, ctx.correlationId(), stepId, err); return Mono.just(false); });
    }

    private Mono<Object> attemptCompensateHandler(StepDefinition sd, Object input, SagaContext ctx,
                                                  long timeoutMs, int retry, long backoffMs, boolean jitter, double jitterFactor,
                                                  String sagaName, String stepId) {
        Mono<Object> base = Mono.defer(() -> {
            if (sd.handler == null) return Mono.error(new IllegalStateException("No handler for step " + sd.id));
            StepHandler h = sd.handler;
            // Complete successfully without emitting a value; upstream will map completion to a success signal.
            return h.compensate(input, ctx).then(Mono.empty());
        });
        if (timeoutMs > 0) base = base.timeout(Duration.ofMillis(timeoutMs));
        return base.onErrorResume(err -> {
                    if (retry > 0) {
                        events.onCompensationRetry(sagaName, ctx.correlationId(), stepId, (sd.compensationRetry != null ? sd.compensationRetry - retry + 1 : (sd.retry - retry + 1)));
                        long delay = computeDelay(backoffMs, jitter, jitterFactor);
                        return Mono.delay(Duration.ofMillis(Math.max(0, delay)))
                                .then(attemptCompensateHandler(sd, input, ctx, timeoutMs, retry - 1, backoffMs, jitter, jitterFactor, sagaName, stepId));
                    }
                    return Mono.error(err);
                });
    }

    private Mono<Object> attemptCompensateMethod(Object bean, Method invocationMethod, Method annotationSource, Object input, SagaContext ctx,
                                                 long timeoutMs, int retry, long backoffMs, boolean jitter, double jitterFactor,
                                                 String sagaName, String stepId) {
        Mono<Object> base = Mono.defer(() -> invokeMono(bean, invocationMethod, annotationSource, input, ctx));
        if (timeoutMs > 0) base = base.timeout(Duration.ofMillis(timeoutMs));
        return base.onErrorResume(err -> {
            if (retry > 0) {
                events.onCompensationRetry(sagaName, ctx.correlationId(), stepId, (retry));
                long delay = computeDelay(backoffMs, jitter, jitterFactor);
                return Mono.delay(Duration.ofMillis(Math.max(0, delay)))
                        .then(attemptCompensateMethod(bean, invocationMethod, annotationSource, input, ctx, timeoutMs, retry - 1, backoffMs, jitter, jitterFactor, sagaName, stepId));
            }
            return Mono.error(err);
        });
    }

    private Mono<Void> compensateSequentialWithRetries(String sagaName,
                                                       SagaDefinition saga,
                                                       List<String> completionOrder,
                                                       Map<String, Object> stepInputs,
                                                       SagaContext ctx) {
        List<String> reversed = new ArrayList<>(completionOrder);
        Collections.reverse(reversed);
        return Flux.fromIterable(reversed)
                .concatMap(stepId -> compensateOneWithResult(sagaName, saga, stepId, stepInputs, ctx).then())
                .then();
    }

    private Mono<Void> compensateSequentialWithCircuitBreaker(String sagaName,
                                                              SagaDefinition saga,
                                                              List<String> completionOrder,
                                                              Map<String, Object> stepInputs,
                                                              SagaContext ctx) {
        List<String> reversed = new ArrayList<>(completionOrder);
        Collections.reverse(reversed);
        AtomicBoolean circuitOpen = new AtomicBoolean(false);
        return Flux.fromIterable(reversed)
                .concatMap(stepId -> {
                    if (circuitOpen.get()) {
                        events.onCompensationSkipped(sagaName, ctx.correlationId(), stepId, "circuit open");
                        return Mono.empty();
                    }
                    StepDefinition sd = saga.steps.get(stepId);
                    return compensateOneWithResult(sagaName, saga, stepId, stepInputs, ctx)
                            .flatMap(success -> {
                                if (!success && sd != null && sd.compensationCritical) {
                                    circuitOpen.set(true);
                                    events.onCompensationCircuitOpen(sagaName, ctx.correlationId(), stepId);
                                }
                                return Mono.empty();
                            });
                })
                .then();
    }

    private Mono<Void> compensateBestEffortParallel(String sagaName,
                                                    SagaDefinition saga,
                                                    List<String> completionOrder,
                                                    Map<String, Object> stepInputs,
                                                    SagaContext ctx) {
        List<String> reversed = new ArrayList<>(completionOrder);
        Collections.reverse(reversed);
        return Flux.fromIterable(reversed)
                .flatMap(stepId -> compensateOneWithResult(sagaName, saga, stepId, stepInputs, ctx)
                        .onErrorReturn(false))
                .collectList()
                .doOnNext(results -> {
                    boolean allOk = results.stream().allMatch(Boolean::booleanValue);
                    events.onCompensationBatchCompleted(sagaName, ctx.correlationId(), reversed, allOk);
                })
                .then();
    }

/**
     * Decide which argument to pass to a compensation method when it expects a business argument.
     * Preference order by type compatibility:
     * 1) If compensation's first parameter type matches the original step input, pass input.
     * 2) Else, if it matches the step result, pass result.
     * If the compensation expects only SagaContext, null is returned for the business argument.
     */
    private Object resolveCompensationArg(Method comp, Object input, Object result) {
        if (comp.getParameterCount() == 2) {
            Class<?> p0 = comp.getParameterTypes()[0];
            if (input != null && p0.isAssignableFrom(input.getClass())) return input;
            if (result != null && p0.isAssignableFrom(result.getClass())) return result;
        } else if (comp.getParameterCount() == 1) {
            Class<?> p0 = comp.getParameterTypes()[0];
            if (SagaContext.class.isAssignableFrom(p0)) return null; // only context expected
            if (input != null && p0.isAssignableFrom(input.getClass())) return input;
            if (result != null && p0.isAssignableFrom(result.getClass())) return result;
        }
        return null;
    }
}
