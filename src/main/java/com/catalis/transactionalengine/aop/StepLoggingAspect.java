package com.catalis.transactionalengine.aop;

import com.catalis.transactionalengine.annotations.SagaStep;
import com.catalis.transactionalengine.core.SagaContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Aspect
/**
 * Minimal AOP aspect that logs raw step method invocation latency and outcome.
 * This aspect does not implement retries/timeout/idempotency (handled by the engine);
 * it only wraps the original method for additional debug-level visibility.
 */
public class StepLoggingAspect {
    private static final Logger log = LoggerFactory.getLogger(StepLoggingAspect.class);

    @Around("@annotation(com.catalis.transactionalengine.annotations.SagaStep)")
    public Object aroundSagaStep(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        SagaStep ann = ms.getMethod().getAnnotation(SagaStep.class);
        String stepId = ann != null ? ann.id() : ms.getMethod().getName();
        SagaContext ctx = null;
        for (Object arg : pjp.getArgs()) {
            if (arg instanceof SagaContext sc) { ctx = sc; break; }
        }
        String sagaId = ctx != null ? ctx.correlationId() : "n/a";
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            long elapsed = System.currentTimeMillis() - start;
            log.debug("saga_aspect=step_invocation_success sagaId={} stepId={} latencyMs={}", sagaId, stepId, elapsed);
            return result;
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            log.debug("saga_aspect=step_invocation_error sagaId={} stepId={} latencyMs={} error={}", sagaId, stepId, elapsed, t.toString());
            throw t;
        }
    }
}
