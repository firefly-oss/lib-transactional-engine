package com.firefly.transactionalengine.engine;

import com.firefly.transactionalengine.core.SagaContext;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Helper to invoke step or compensation methods with retry/backoff/timeout semantics
 * and argument resolution.
 */
final class StepInvoker {

    private final SagaArgumentResolver argumentResolver;

    StepInvoker(SagaArgumentResolver argumentResolver) {
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
    Mono<Object> attemptCall(Object bean, Method invocationMethod, Method annotationSource, Object input, SagaContext ctx,
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
    Mono<Object> attemptCallHandler(StepHandler handler, Object input, SagaContext ctx,
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

    static long computeDelay(long backoffMs, boolean jitter, double jitterFactor) {
        if (backoffMs <= 0) return 0L;
        if (!jitter) return backoffMs;
        double f = Math.max(0.0d, Math.min(jitterFactor, 1.0d));
        double min = backoffMs * (1.0d - f);
        double max = backoffMs * (1.0d + f);
        long v = Math.round(ThreadLocalRandom.current().nextDouble(min, max));
        return Math.max(0L, v);
    }
}
