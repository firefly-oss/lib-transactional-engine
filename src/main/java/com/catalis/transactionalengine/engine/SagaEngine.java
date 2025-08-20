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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final Logger log = LoggerFactory.getLogger(SagaEngine.class);

    private final SagaRegistry registry;
    private final SagaEvents events;

    /**
         * Create a new SagaEngine.
         * @param registry saga metadata registry discovered from Spring context
         * @param events observability sink receiving lifecycle notifications
         */
        public SagaEngine(SagaRegistry registry, SagaEvents events) {
        this.registry = registry;
        this.events = events;
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
                    // run all in parallel within layer
                    List<Mono<Void>> executions = layer.stream().map(stepId ->
                            executeStep(sagaName, saga, stepId, stepInputs != null ? stepInputs.get(stepId) : null, ctx)
                                    .doOnSuccess(res -> completionOrder.add(stepId))
                                    .onErrorResume(err -> {
                                        failed.set(true);
                                        stepErrors.put(stepId, err);
                                        // Swallow the error here so the layer completes and finalization executes
                                        return Mono.empty();
                                    })
                    ).toList();
                    // No need to delay errors since we swallow them; when works fine
                    return Mono.when(executions);
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
                    // run all in parallel within layer
                    List<Mono<Void>> executions = layer.stream().map(stepId ->
                            executeStep(sagaName, saga, stepId, inputs != null ? inputs.resolveFor(stepId, ctx) : null, ctx)
                                    .doOnSuccess(res -> completionOrder.add(stepId))
                                    .onErrorResume(err -> {
                                        failed.set(true);
                                        stepErrors.put(stepId, err);
                                        return Mono.empty();
                                    })
                    ).toList();
                    return Mono.when(executions);
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
        Objects.requireNonNull(ctx, "ctx");
        SagaDefinition saga = registry.getSaga(sagaName);
        return execute(saga, inputs, ctx);
    }

    public Mono<SagaResult> execute(SagaDefinition saga, StepInputs inputs, SagaContext ctx) {
        Objects.requireNonNull(saga, "saga");
        Objects.requireNonNull(ctx, "ctx");
        String sagaName = saga.name;
        String sagaId = ctx.correlationId();
        events.onStart(sagaName, sagaId);

        List<List<String>> layers = buildLayers(saga);
        List<String> completionOrder = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean failed = new AtomicBoolean(false);
        Map<String, Throwable> stepErrors = new ConcurrentHashMap<>();
        Map<String, Boolean> compensated = new ConcurrentHashMap<>();

        return Flux.fromIterable(layers)
                .concatMap(layer -> {
                    if (failed.get()) return Mono.empty();
                    var executions = layer.stream().map(stepId ->
                            executeStep(sagaName, saga, stepId, inputs != null ? inputs.resolveFor(stepId, ctx) : null, ctx)
                                    .doOnSuccess(v -> completionOrder.add(stepId))
                                    .onErrorResume(err -> { failed.set(true); stepErrors.put(stepId, err); return Mono.empty(); })
                    ).toList();
                    return Mono.when(executions);
                })
                .then(Mono.defer(() -> {
                    boolean success = !failed.get();
                    events.onCompleted(sagaName, sagaId, success);
                    if (success) {
                        return Mono.just(SagaResult.from(sagaName, ctx, compensated, stepErrors));
                    }
                    Map<String, Object> materialized = inputs != null ? inputs.materializedView(ctx) : Map.of();
                    return compensate(sagaName, saga, completionOrder, materialized, ctx)
                            .then(Mono.defer(() -> {
                                // derive compensated flags from context statuses
                                for (String id : completionOrder) {
                                    if (StepStatus.COMPENSATED.equals(ctx.getStatus(id))) {
                                        compensated.put(id, true);
                                    }
                                }
                                return Mono.just(SagaResult.from(sagaName, ctx, compensated, stepErrors));
                            }));
                }));
    }

    /**
         * Build execution layers (topological levels) from the saga's step graph.
         * Steps with indegree=0 form the first layer; removing them may unlock subsequent layers.
         * No cycle detection here (validated in registry). Order inside a layer is not guaranteed.
         */
        private List<List<String>> buildLayers(SagaDefinition saga) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
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
        for (Map.Entry<String,Integer> e : indegree.entrySet()) if (e.getValue() == 0) q.add(e.getKey());
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
     * - Applies retry with backoff and timeout via {@link #attemptCall(Object, Method, Method, Object, SagaContext, long, int, long, String)}.
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
                return Mono.empty();
            }
            ctx.markIdempotent(sd.idempotencyKey);
        }

        ctx.setStatus(stepId, StepStatus.RUNNING);
        final long start = System.currentTimeMillis();

        Mono<Object> execution;
        if (sd.handler != null) {
            execution = attemptCallHandler(sd, input, ctx, sd.timeoutMs, sd.retry, sd.backoffMs, stepId);
        } else {
            Method invokeMethod = sd.stepInvocationMethod != null ? sd.stepInvocationMethod : sd.stepMethod;
            execution = attemptCall(saga.bean, invokeMethod, sd.stepMethod, input, ctx, sd.timeoutMs, sd.retry, sd.backoffMs, stepId);
        }
        return execution
                .doOnNext(res -> ctx.putResult(stepId, res))
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
                                     long timeoutMs, int retry, long backoffMs, String stepId) {
        return Mono.defer(() -> invokeMono(bean, invocationMethod, annotationSource, input, ctx))
                .transform(m -> timeoutMs > 0 ? m.timeout(Duration.ofMillis(timeoutMs)) : m)
                .onErrorResume(err -> {
                    if (retry > 0) {
                        return Mono.delay(Duration.ofMillis(Math.max(0, backoffMs)))
                                .then(attemptCall(bean, invocationMethod, annotationSource, input, ctx, timeoutMs, retry - 1, backoffMs, stepId));
                    }
                    return Mono.error(err);
                })
                .doFirst(() -> ctx.incrementAttempts(stepId));
    }

    /**
     * Attempt to invoke a handler-based step with retry/backoff/timeout semantics.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Mono<Object> attemptCallHandler(StepDefinition sd, Object input, SagaContext ctx,
                                            long timeoutMs, int retry, long backoffMs, String stepId) {
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
                        return Mono.delay(Duration.ofMillis(Math.max(0, backoffMs)))
                                .then(attemptCallHandler(sd, input, ctx, timeoutMs, retry - 1, backoffMs, stepId));
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
        var params = method.getParameters();
        if (params.length == 0) return new Object[0];
        Object[] args = new Object[params.length];
        boolean implicitInputUsed = false;
        for (int i = 0; i < params.length; i++) {
            var p = params[i];
            Class<?> type = p.getType();

            // Type-based injection for SagaContext
            if (SagaContext.class.isAssignableFrom(type)) {
                args[i] = ctx;
                continue;
            }

            // Annotation-based resolution
            var inputAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Input.class);
            if (inputAnn != null) {
                String key = inputAnn.value();
                if (key == null || key.isBlank()) {
                    args[i] = input;
                } else if (input instanceof Map<?, ?> m) {
                    args[i] = m.get(key);
                } else {
                    args[i] = null; // no map input; best-effort
                }
                continue;
            }

            var fromStepAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.FromStep.class);
            if (fromStepAnn != null) {
                args[i] = ctx.getResult(fromStepAnn.value());
                continue;
            }

            var headerAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Header.class);
            if (headerAnn != null) {
                String name = headerAnn.value();
                args[i] = ctx.headers().get(name);
                continue;
            }

            var headersAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Headers.class);
            if (headersAnn != null) {
                args[i] = ctx.headers();
                continue;
            }

            // Legacy implicit input injection: allow a single unannotated non-context parameter
            if (!implicitInputUsed) {
                args[i] = input;
                implicitInputUsed = true;
            } else {
                throw new IllegalStateException("Unresolvable parameter '" + p.getName() + "' at position " + i +
                        " in method " + method + ". Use @Input/@FromStep/@Header/@Headers or SagaContext.");
            }
        }
        return args;
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
        List<String> reversed = new ArrayList<>(completionOrder);
        Collections.reverse(reversed);
        return Flux.fromIterable(reversed)
                .concatMap(stepId -> {
                    StepDefinition sd = saga.steps.get(stepId);
                    // Prefer handler-based compensation when provided
                    if (sd.handler != null) {
                        Object input = stepInputs.get(stepId);
                        Object result = ctx.getResult(stepId);
                        Object arg = input != null ? input : result; // simple heuristic; handler may ignore
                        return sd.handler.compensate(arg, ctx)
                                .doOnSuccess(v -> ctx.setStatus(stepId, StepStatus.COMPENSATED))
                                .doOnError(err -> events.onCompensated(sagaName, ctx.correlationId(), stepId, err))
                                .onErrorResume(err -> Mono.empty());
                    }
                    Method comp = sd.compensateInvocationMethod != null ? sd.compensateInvocationMethod : sd.compensateMethod;
                    if (comp == null) return Mono.empty();
                    Object arg = resolveCompensationArg(comp, stepInputs.get(stepId), ctx.getResult(stepId));
                    return invokeMono(saga.bean, comp, arg, ctx)
                            .doOnSuccess(v -> ctx.setStatus(stepId, StepStatus.COMPENSATED))
                            .doOnError(err -> events.onCompensated(sagaName, ctx.correlationId(), stepId, err))
                            .onErrorResume(err -> Mono.empty()); // swallow compensation errors in MVP
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
