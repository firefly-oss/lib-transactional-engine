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
import com.firefly.transactionalengine.core.SagaResult;
import com.firefly.transactionalengine.events.StepEventEnvelope;
import com.firefly.transactionalengine.events.StepEventPublisher;
import com.firefly.transactionalengine.events.NoOpStepEventPublisher;
import com.firefly.transactionalengine.observability.SagaEvents;
import com.firefly.transactionalengine.registry.SagaDefinition;
import com.firefly.transactionalengine.registry.SagaRegistry;
import com.firefly.transactionalengine.registry.StepDefinition;
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

import com.firefly.transactionalengine.annotations.Saga;
import com.firefly.transactionalengine.tools.MethodRefs;
import com.firefly.transactionalengine.core.SagaOptimizationDetector;
import com.firefly.transactionalengine.core.OptimizedSagaContext;

/**
 * Core orchestrator that executes Sagas purely in-memory (no persistence).
 * <p>
 * Responsibilities:
 * - Build a Directed Acyclic Graph (DAG) of steps from the registry and group them into execution layers
 *   using topological ordering. All steps within the same layer are executed concurrently.
 * - For each step, apply timeout, retry with backoff, idempotency-within-execution, and capture status/latency/attempts.
 * - Store step results in {@link com.firefly.transactionalengine.core.SagaContext} under the step id.
 * - On failure, compensate already completed steps in reverse completion order (best effort; compensation errors are
 *   logged via {@link com.firefly.transactionalengine.observability.SagaEvents} and swallowed in this MVP).
 * - Emit lifecycle events to {@link com.firefly.transactionalengine.observability.SagaEvents} for observability.
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
    private final StepEventPublisher stepEventPublisher;
    private final boolean autoOptimizationEnabled;

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
        this.registry = registry;
        this.events = events;
        this.policy = (policy != null ? policy : CompensationPolicy.STRICT_SEQUENTIAL);
        this.invoker = new StepInvoker(argumentResolver);
        this.compensator = new SagaCompensator(this.events, this.policy, this.invoker);
        this.stepEventPublisher = (stepEventPublisher != null ? stepEventPublisher : new NoOpStepEventPublisher());
        this.autoOptimizationEnabled = autoOptimizationEnabled;
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
        // Auto-create context if not provided, with automatic optimization
        final com.firefly.transactionalengine.core.SagaContext finalCtx = 
                (ctx != null ? ctx : createOptimizedContextIfPossible(saga));
        
        // Perform optional expansion first to maintain original behavior
        final Map<String, Object> overrideInputs = new LinkedHashMap<>();
        SagaDefinition workSaga = maybeExpandSaga(saga, inputs, overrideInputs, finalCtx);
        
        // Preserve topology logging functionality for compatibility
        SagaTopologyReporter.exposeAndLog(workSaga, finalCtx, log);
        
        // Use SagaExecutionCommand to handle all execution logic
        SagaExecutionCommand command = new SagaExecutionCommand(
            workSaga, inputs, finalCtx, events, stepEventPublisher, compensator, invoker, overrideInputs
        );
        
        return command.execute();
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
}