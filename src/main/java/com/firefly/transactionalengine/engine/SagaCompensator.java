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
import com.firefly.transactionalengine.engine.compensation.CompensationErrorHandler;
import com.firefly.transactionalengine.engine.compensation.CompensationErrorHandlerFactory;
import com.firefly.transactionalengine.observability.SagaEvents;
import com.firefly.transactionalengine.registry.SagaDefinition;
import com.firefly.transactionalengine.registry.StepDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;

/**
 * Extracted compensation coordinator used by SagaEngine. It encapsulates
 * all compensation flows and delegates to injected helpers for invocation.
 */
final class SagaCompensator {

    private final SagaEvents events;
    private final SagaEngine.CompensationPolicy policy;
    private final StepInvoker invoker;
    private final CompensationErrorHandler errorHandler;

    SagaCompensator(SagaEvents events, SagaEngine.CompensationPolicy policy, StepInvoker invoker) {
        this(events, policy, invoker, CompensationErrorHandlerFactory.defaultHandler());
    }

    SagaCompensator(SagaEvents events, SagaEngine.CompensationPolicy policy, StepInvoker invoker, CompensationErrorHandler errorHandler) {
        this.events = events;
        this.policy = policy;
        this.invoker = invoker;
        this.errorHandler = errorHandler != null ? errorHandler : CompensationErrorHandlerFactory.defaultHandler();
    }

    Mono<Void> compensate(String sagaName, 
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
        List<List<String>> layers = SagaTopology.buildLayers(saga);
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
        if (sd == null) {
            return Mono.empty();
        }
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
        return invoker.invokeMono(targetBean, comp, sd.compensateMethod != null ? sd.compensateMethod : comp, arg, ctx)
                .doOnNext(obj -> ctx.putCompensationResult(stepId, obj))
                .doOnSuccess(v -> {
                    ctx.setStatus(stepId, StepStatus.COMPENSATED);
                    events.onCompensated(sagaName, ctx.correlationId(), stepId, null);
                })
                .doOnError(err -> { ctx.putCompensationError(stepId, err); events.onCompensated(sagaName, ctx.correlationId(), stepId, err); })
                .onErrorResume(err -> Mono.empty())
                .then();
    }

    private record CompParams(int retry, long timeoutMs, long backoffMs, boolean jitter, double jitterFactor) {}

    private CompParams computeCompParams(StepDefinition sd) {
        int retry = sd.compensationRetry != null ? sd.compensationRetry : sd.retry;
        long backoffMs = (sd.compensationBackoff != null ? sd.compensationBackoff : sd.backoff).toMillis();
        long timeoutMs = (sd.compensationTimeout != null ? sd.compensationTimeout : sd.timeout).toMillis();
        boolean jitter = sd.jitter;
        double jitterFactor = sd.jitterFactor;
        return new CompParams(Math.max(0, retry), Math.max(0, timeoutMs), Math.max(0, backoffMs), jitter, jitterFactor);
    }

    private Mono<Boolean> compensateOneWithResult(String sagaName,
                                                  SagaDefinition saga,
                                                  String stepId,
                                                  Map<String, Object> stepInputs,
                                                  SagaContext ctx) {
        StepDefinition sd = saga.steps.get(stepId);
        if (sd == null) return Mono.just(true);
        Method comp = sd.compensateInvocationMethod != null ? sd.compensateInvocationMethod : sd.compensateMethod;
        if (comp == null && sd.handler == null) return Mono.just(true);
        // Emit start for all policies
        try { events.onCompensationStarted(sagaName, ctx.correlationId(), stepId); } catch (Throwable ignored) {}
        CompParams p = computeCompParams(sd);
        Mono<Object> call;
        if (sd.handler != null) {
            Object input = stepInputs.get(stepId);
            Object result = ctx.getResult(stepId);
            Object arg = input != null ? input : result;
            // Manual retry to emit onCompensationRetry
            call = Mono.defer(() -> sd.handler.compensate(arg, ctx).cast(Object.class))
                    .transform(m -> p.timeoutMs > 0 ? m.timeout(Duration.ofMillis(p.timeoutMs)) : m)
                    .onErrorResume(err -> {
                        if (p.retry > 0) {
                            long delay = StepInvoker.computeDelay(p.backoffMs, p.jitter, p.jitterFactor);
                            try { events.onCompensationRetry(sagaName, ctx.correlationId(), stepId, 1); } catch (Throwable ignored) {}
                            return Mono.delay(Duration.ofMillis(Math.max(0, delay)))
                                    .then(Mono.defer(() -> sd.handler.compensate(arg, ctx).cast(Object.class)))
                                    .transform(m2 -> p.timeoutMs > 0 ? m2.timeout(Duration.ofMillis(p.timeoutMs)) : m2)
                                    .onErrorResume(err2 -> Mono.error(err2));
                        }
                        return Mono.error(err);
                    });
        } else {
            Object arg = resolveCompensationArg(comp, stepInputs.get(stepId), ctx.getResult(stepId));
            Object targetBean = sd.compensateBean != null ? sd.compensateBean : saga.bean;
            call = invoker.attemptCall(targetBean, comp, sd.compensateMethod != null ? sd.compensateMethod : comp, arg, ctx, p.timeoutMs, p.retry, p.backoffMs, p.jitter, p.jitterFactor, stepId);
        }
        return call
                .doOnNext(v -> ctx.putCompensationResult(stepId, v))
                .thenReturn(true)
                .onErrorResume(err -> handleCompensationError(stepId, err, ctx, sagaName, 1))
                .doOnSuccess(ok -> {
                    if (Boolean.TRUE.equals(ok)) {
                        ctx.setStatus(stepId, StepStatus.COMPENSATED);
                        events.onCompensated(sagaName, ctx.correlationId(), stepId, null);
                    } else {
                        events.onCompensated(sagaName, ctx.correlationId(), stepId, ctx.getCompensationError(stepId));
                    }
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
                .concatMap(stepId -> compensateOneWithResult(sagaName, saga, stepId, stepInputs, ctx))
                .then();
    }

    private Mono<Void> compensateSequentialWithCircuitBreaker(String sagaName,
                                                              SagaDefinition saga,
                                                              List<String> completionOrder,
                                                              Map<String, Object> stepInputs,
                                                              SagaContext ctx) {
        List<String> reversed = new ArrayList<>(completionOrder);
        Collections.reverse(reversed);
        final boolean[] circuitOpen = {false};
        return Flux.fromIterable(reversed)
                .concatMap(stepId -> {
                    if (circuitOpen[0]) {
                        try { events.onCompensationSkipped(sagaName, ctx.correlationId(), stepId, "circuit_open"); } catch (Throwable ignored) {}
                        return Mono.empty();
                    }
                    return compensateOneWithResult(sagaName, saga, stepId, stepInputs, ctx)
                            .doOnNext(ok -> {
                                if (!ok) {
                                    StepDefinition sd = saga.steps.get(stepId);
                                    boolean critical = sd != null && sd.compensationCritical;
                                    if (critical) {
                                        circuitOpen[0] = true;
                                        try { events.onCompensationCircuitOpen(sagaName, ctx.correlationId(), stepId); } catch (Throwable ignored) {}
                                    }
                                }
                            })
                            .then();
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
                        .map(ok -> new AbstractMap.SimpleEntry<>(stepId, ok)))
                .collectList()
                .doOnSuccess(list -> {
                    boolean allOk = list.stream().allMatch(e -> Boolean.TRUE.equals(e.getValue()));
                    List<String> ids = list.stream().map(AbstractMap.SimpleEntry::getKey).toList();
                    try { events.onCompensationBatchCompleted(sagaName, ctx.correlationId(), ids, allOk); } catch (Throwable ignored) {}
                })
                .then();
    }

    /**
     * Handles compensation errors using the configured error handler.
     */
    private Mono<Boolean> handleCompensationError(String stepId, Throwable error, SagaContext ctx, String sagaName, int attempt) {
        return errorHandler.handleError(stepId, error, ctx, attempt)
                .flatMap(result -> {
                    switch (result) {
                        case CONTINUE:
                            ctx.putCompensationError(stepId, error);
                            return Mono.just(false);
                        case RETRY:
                            // For now, just continue - retry logic would need to be implemented at a higher level
                            ctx.putCompensationError(stepId, error);
                            return Mono.just(false);
                        case FAIL_SAGA:
                            return Mono.error(new CompensationFailedException("Compensation failed for step " + stepId, error));
                        case SKIP_STEP:
                            return Mono.just(true);
                        case MARK_COMPENSATED:
                            ctx.setStatus(stepId, StepStatus.COMPENSATED);
                            return Mono.just(true);
                        default:
                            ctx.putCompensationError(stepId, error);
                            return Mono.just(false);
                    }
                });
    }

    private Object resolveCompensationArg(Method comp, Object input, Object result) {
        Class<?>[] params = comp.getParameterTypes();
        if (params.length == 0) return null;
        Class<?> t = params[0];
        if (input != null && t.isAssignableFrom(input.getClass())) return input;
        if (result != null && t.isAssignableFrom(result.getClass())) return result;
        return input != null ? input : result;
    }

    /**
     * Exception thrown when compensation fails and the error handler decides to fail the saga.
     */
    public static class CompensationFailedException extends RuntimeException {
        public CompensationFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
