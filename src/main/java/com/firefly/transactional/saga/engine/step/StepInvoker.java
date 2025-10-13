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


package com.firefly.transactional.saga.engine.step;

import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.saga.engine.SagaArgumentResolver;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Helper to invoke step or compensation methods with retry/backoff/timeout semantics
 * and argument resolution.
 */
public final class StepInvoker {

    private final SagaArgumentResolver argumentResolver;

    public StepInvoker(SagaArgumentResolver argumentResolver) {
        this.argumentResolver = argumentResolver;
    }

    @SuppressWarnings("unchecked")
    Mono<Object> invokeMono(Object bean, Method method, Object input, SagaContext ctx) {
        try {
            Object[] args = argumentResolver.resolveArguments(method, input, ctx);
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
    public Mono<Object> attemptCall(Object bean, Method invocationMethod, Method annotationSource, Object input, SagaContext ctx,
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

    // Handler-based execution with retry/backoff/timeout
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Mono<Object> attemptCallHandler(StepHandler handler, Object input, SagaContext ctx,
                                    long timeoutMs, int retry, long backoffMs, boolean jitter, double jitterFactor, String stepId) {
        Mono<Object> base = Mono.defer(() -> handler.execute(input, ctx).cast(Object.class));
        if (timeoutMs > 0) {
            base = base.timeout(Duration.ofMillis(timeoutMs));
        }
        Mono<Object> finalBase = base;
        return finalBase
                .onErrorResume(err -> {
                    if (retry > 0) {
                        long delay = computeDelay(backoffMs, jitter, jitterFactor);
                        return Mono.delay(Duration.ofMillis(Math.max(0, delay)))
                                .then(attemptCallHandler(handler, input, ctx, timeoutMs, retry - 1, backoffMs, jitter, jitterFactor, stepId));
                    }
                    return Mono.error(err);
                })
                .doFirst(() -> ctx.incrementAttempts(stepId));
    }

    // Overload for separate annotation source
    @SuppressWarnings("unchecked")
    public Mono<Object> invokeMono(Object bean, Method invocationMethod, Method annotationSource, Object input, SagaContext ctx) {
        try {
            Object[] args = argumentResolver.resolveArguments(annotationSource, input, ctx);
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

    public static long computeDelay(long backoffMs, boolean jitter, double jitterFactor) {
        if (backoffMs <= 0) return 0L;
        if (!jitter) return backoffMs;
        double f = Math.max(0.0d, Math.min(jitterFactor, 1.0d));
        double min = backoffMs * (1.0d - f);
        double max = backoffMs * (1.0d + f);
        long v = Math.round(ThreadLocalRandom.current().nextDouble(min, max));
        return Math.max(0L, v);
    }
}
