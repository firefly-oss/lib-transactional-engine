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

    // Argument resolver helper (extracts parameter binding + caches strategies)
    private final SagaArgumentResolver argumentResolver = new SagaArgumentResolver();

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
    private final StepInvoker invoker;
    private final SagaCompensator compensator;

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
        this.policy = (policy != null ? policy : CompensationPolicy.STRICT_SEQUENTIAL);
        this.invoker = new StepInvoker(argumentResolver);
        this.compensator = new SagaCompensator(this.events, this.policy, this.invoker);
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

        List<List<String>> layers = SagaTopology.buildLayers(workSaga);
        // Expose topology in the execution context for better accessibility
        finalCtx.setTopologyLayers(layers);
        Map<String, List<String>> deps = new LinkedHashMap<>();
        for (Map.Entry<String, StepDefinition> e : workSaga.steps.entrySet()) {
            deps.put(e.getKey(), List.copyOf(e.getValue().dependsOn));
        }
        finalCtx.setStepDependencies(deps);
        if (log.isDebugEnabled()) {
            String layersStr = layers.stream()
                    .map(l -> String.join(",", l))
                    .collect(Collectors.joining(" | "));
            log.debug(SagaLogUtil.json(
                    "saga_topology","layers",
                    "saga", sagaName,
                    "sagaId", finalCtx.correlationId(),
                    "layers", layersStr
            ));
        }
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
                log.info(SagaLogUtil.json(
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
            String inputPreview = SagaLogUtil.summarize(input, 200);
            log.info(SagaLogUtil.json(
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
            execution = invoker.attemptCallHandler(sd.handler, input, ctx, timeoutMs, sd.retry, backoffMs, sd.jitter, sd.jitterFactor, stepId);
        } else {
            Method invokeMethod = sd.stepInvocationMethod != null ? sd.stepInvocationMethod : sd.stepMethod;
            Object targetBean = sd.stepBean != null ? sd.stepBean : saga.bean;
            execution = invoker.attemptCall(targetBean, invokeMethod, sd.stepMethod, input, ctx, timeoutMs, sd.retry, backoffMs, sd.jitter, sd.jitterFactor, stepId);
        }
        if (sd.cpuBound) {
            execution = execution.subscribeOn(Schedulers.parallel());
        }
        return execution
                .doOnNext(res -> {
                    if (log.isInfoEnabled()) {
                        String resultType = res != null ? res.getClass().getName() : "null";
                        String resultPreview = SagaLogUtil.summarize(res, 200);
                        log.info(SagaLogUtil.json(
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
                    String errMsg = SagaLogUtil.safeString(err.getMessage(), 500);
                    log.warn(SagaLogUtil.json(
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
            log.info(SagaLogUtil.json(
                        "saga_compensation","start",
                        "saga", sagaName,
                        "sagaId", ctx.correlationId(),
                        "completed_steps_count", Integer.toString(completedCount),
                        "policy", String.valueOf(policy)
                    ));
        }
        return compensator.compensate(sagaName, saga, completionOrder, stepInputs, ctx);
    }




}