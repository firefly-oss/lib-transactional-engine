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


package com.firefly.transactional.saga.aop;

import com.firefly.transactional.saga.annotations.SagaStep;
import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.shared.util.JsonUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Aspect
/**
 * AOP aspect that logs step method invocation context, latency and outcome.
 * Note: Retries/timeout/idempotency are handled by the engine; this only wraps the original method
 * to provide additional debug-level visibility with structured key=value logs.
 */
public class StepLoggingAspect {
    private static final Logger log = LoggerFactory.getLogger(StepLoggingAspect.class);

    @Around("@annotation(com.firefly.transactional.saga.annotations.SagaStep) || @annotation(com.firefly.transactional.saga.annotations.ExternalSagaStep)")
    public Object aroundSagaStep(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        SagaStep ann = ms.getMethod().getAnnotation(SagaStep.class);
        String stepId;
        if (ann != null) {
            stepId = ann.id();
        } else {
            var ext = ms.getMethod().getAnnotation(com.firefly.transactional.saga.annotations.ExternalSagaStep.class);
            stepId = ext != null ? ext.id() : ms.getMethod().getName();
        }
        SagaContext ctx = null;
        for (Object arg : pjp.getArgs()) {
            if (arg instanceof SagaContext sc) { ctx = sc; break; }
        }
        String sagaId = ctx != null ? ctx.correlationId() : "n/a";
        String className = ms.getDeclaringTypeName();
        String methodName = ms.getMethod().getName();
        int argsCount = pjp.getArgs() != null ? pjp.getArgs().length : 0;
        String thread = Thread.currentThread().getName();

        long start = System.currentTimeMillis();
        if (log.isInfoEnabled()) {
            log.info(JsonUtils.json(
                    "saga_aspect","step_invocation_start",
                    "sagaId", sagaId,
                    "stepId", stepId,
                    "class", className,
                    "method", methodName,
                    "args_count", Integer.toString(argsCount),
                    "thread", thread
            ));
        }
        try {
            Object result = pjp.proceed();
            long elapsed = System.currentTimeMillis() - start;
            if (log.isInfoEnabled()) {
                String resultType = result != null ? result.getClass().getName() : "null";
                String resultPreview = summarize(result, 200);
                log.info(JsonUtils.json(
                        "saga_aspect","step_invocation_success",
                        "sagaId", sagaId,
                        "stepId", stepId,
                        "class", className,
                        "method", methodName,
                        "latencyMs", Long.toString(elapsed),
                        "result_type", resultType,
                        "result_preview", resultPreview
                ));
            }
            return result;
        } catch (Throwable t) {
            long elapsed = System.currentTimeMillis() - start;
            String errClass = t.getClass().getName();
            String errMsg = safeString(t.getMessage(), 300);
            log.info(JsonUtils.json(
                    "saga_aspect","step_invocation_error",
                    "sagaId", sagaId,
                    "stepId", stepId,
                    "class", className,
                    "method", methodName,
                    "latencyMs", Long.toString(elapsed),
                    "error_class", errClass,
                    "error_msg", errMsg
            ));
            throw t;
        }
    }

    private String summarize(Object obj, int max) {
        if (obj == null) return "null";
        String s;
        try { s = String.valueOf(obj); } catch (Throwable ignore) { s = obj.getClass().getName(); }
        return safeString(s, max);
    }

    private String safeString(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max)) + "...";
    }

}
