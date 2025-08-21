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
        String sagaName = saga.name;
        String sagaId = finalCtx.correlationId();
        events.onStart(sagaName, sagaId);
        events.onStart(sagaName, sagaId, finalCtx);

        List<List<String>> layers = buildLayers(saga);
        List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean failed = new AtomicBoolean(false);
        Map<String, Throwable> stepErrors = new ConcurrentHashMap<>();
        Map<String, Boolean> compensated = new ConcurrentHashMap<>();

        return Flux.fromIterable(layers)
                .concatMap(layer -> {
                    if (failed.get()) return Mono.empty();
                    var executions = layer.stream().map(stepId ->
                            executeStep(sagaName, saga, stepId, inputs != null ? inputs.resolveFor(stepId, finalCtx) : null, finalCtx)
                                    .doOnSuccess(v -> completionOrder.add(stepId))
                                    .onErrorResume(err -> { failed.set(true); stepErrors.put(stepId, err); return Mono.empty(); })
                    ).toList();
                    return Mono.when(executions);
                })
                .then(Mono.defer(() -> {
                    boolean success = !failed.get();
                    events.onCompleted(sagaName, sagaId, success);
                    if (success) {
                        return Mono.just(SagaResult.from(sagaName, finalCtx, compensated, stepErrors, saga.steps.keySet()));
                    }
                    Map<String, Object> materialized = inputs != null ? inputs.materializedView(finalCtx) : Map.of();
                    return compensate(sagaName, saga, completionOrder, materialized, finalCtx)
                            .then(Mono.defer(() -> {
                                // derive compensated flags from context statuses
                                for (String id : completionOrder) {
                                    if (StepStatus.COMPENSATED.equals(finalCtx.getStatus(id))) {
                                        compensated.put(id, true);
                                    }
                                }
                                return Mono.just(SagaResult.from(sagaName, finalCtx, compensated, stepErrors, saga.steps.keySet()));
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
                log.debug("[sagaId={}] [stepId={}] Skipping due to idempotency key present", ctx.correlationId(), stepId);
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
                    events.onStepFailed(sagaName, ctx.correlationId(), stepId, err, ctx.getAttempts(stepId), latency);
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
                    .doOnError(err -> events.onCompensated(sagaName, ctx.correlationId(), stepId, err))
                    .onErrorResume(err -> Mono.empty());
        }
        Method comp = sd.compensateInvocationMethod != null ? sd.compensateInvocationMethod : sd.compensateMethod;
        if (comp == null) return Mono.empty();
        Object arg = resolveCompensationArg(comp, stepInputs.get(stepId), ctx.getResult(stepId));
        Object targetBean = sd.compensateBean != null ? sd.compensateBean : saga.bean;
        return invokeMono(targetBean, comp, arg, ctx)
                .doOnSuccess(v -> {
                    ctx.setStatus(stepId, StepStatus.COMPENSATED);
                    events.onCompensated(sagaName, ctx.correlationId(), stepId, null);
                })
                .doOnError(err -> events.onCompensated(sagaName, ctx.correlationId(), stepId, err))
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
                        events.onCompensated(sagaName, ctx.correlationId(), stepId, err);
                        return Mono.just(false);
                    });
        }
        Method comp = sd.compensateInvocationMethod != null ? sd.compensateInvocationMethod : sd.compensateMethod;
        if (comp == null) return Mono.just(true);
        Object arg = resolveCompensationArg(comp, stepInputs.get(stepId), ctx.getResult(stepId));
        Object targetBean = sd.compensateBean != null ? sd.compensateBean : saga.bean;
        return attemptCompensateMethod(targetBean, comp, sd.compensateMethod, arg, ctx, p.timeoutMs, p.retry, p.backoffMs, p.jitter, p.jitterFactor, sagaName, stepId)
                .thenReturn(true)
                .doOnSuccess(v -> { ctx.setStatus(stepId, StepStatus.COMPENSATED); events.onCompensated(sagaName, ctx.correlationId(), stepId, null); })
                .onErrorResume(err -> { events.onCompensated(sagaName, ctx.correlationId(), stepId, err); return Mono.just(false); });
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
