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


package com.firefly.transactional.observability;

import com.firefly.transactional.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Default {@link SagaEvents} implementation that emits JSON-friendly key=value logs via SLF4J.
 * Enriched to include error class/message and compensation lifecycle.
 * Also integrates with metrics collection for observability.
 */
public class SagaLoggerEvents implements SagaEvents {
    private static final Logger log = LoggerFactory.getLogger(SagaLoggerEvents.class);

    @Autowired(required = false)
    private SagaMetricsCollector metricsCollector;

    @Override
    public void onStart(String sagaName, String sagaId) {
        log.info(JsonUtils.json(
                "saga_event","start",
                "saga", sagaName,
                "sagaId", sagaId
        ));

        if (metricsCollector != null) {
            metricsCollector.recordSagaStarted(sagaName, sagaId);
        }
    }

    @Override
    public void onStart(String sagaName, String sagaId, com.firefly.transactional.core.SagaContext ctx) {
        if (ctx == null) {
            log.info(JsonUtils.json(
                    "saga_event","start_ctx",
                    "saga", sagaName,
                    "sagaId", sagaId,
                    "headers_count", "0"
            ));
        } else {
            int headers = ctx.headers() != null ? ctx.headers().size() : 0;
            int vars = ctx.variables() != null ? ctx.variables().size() : 0;
            log.info(JsonUtils.json(
                    "saga_event","start_ctx",
                    "saga", sagaName,
                    "sagaId", sagaId,
                    "headers_count", Integer.toString(headers),
                    "variables_count", Integer.toString(vars)
            ));
        }
    }

    @Override
    public void onStepStarted(String sagaName, String sagaId, String stepId) {
        log.info(JsonUtils.json(
                "saga_event","step_started",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId
        ));

        if (metricsCollector != null) {
            metricsCollector.recordStepStarted(stepId, sagaName, sagaId);
        }
    }

    @Override
    public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {
        log.info(JsonUtils.json(
                "saga_event","step_success",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId,
                "attempts", Integer.toString(attempts),
                "latencyMs", Long.toString(latencyMs)
        ));

        if (metricsCollector != null) {
            metricsCollector.recordStepSucceeded(stepId, sagaName, sagaId);
        }
    }

    @Override
    public void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) {
        String errClass = error != null ? error.getClass().getName() : "";
        String errMsg = safeString(error != null ? error.getMessage() : "", 500);
        log.warn(JsonUtils.json(
                "saga_event","step_failed",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId,
                "attempts", Integer.toString(attempts),
                "latencyMs", Long.toString(latencyMs),
                "error_class", errClass,
                "error_msg", errMsg
        ));

        if (metricsCollector != null) {
            metricsCollector.recordStepFailed(stepId, sagaName, sagaId, error);
        }
    }

    @Override
    public void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) {
        String errClass = error != null ? error.getClass().getName() : "";
        String errMsg = safeString(error != null ? error.getMessage() : "", 500);
        log.info(JsonUtils.json(
                "saga_event","compensated",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId,
                "error_class", errClass,
                "error_msg", errMsg
        ));

        if (metricsCollector != null) {
            metricsCollector.recordStepCompensated(stepId, sagaName, sagaId);
        }
    }

    @Override
    public void onStepSkippedIdempotent(String sagaName, String sagaId, String stepId) {
        log.info(JsonUtils.json(
                "saga_event","step_skipped_idempotent",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId
        ));
    }

    @Override
    public void onCompensationStarted(String sagaName, String sagaId, String stepId) {
        log.info(JsonUtils.json(
                "saga_event","compensation_started",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId
        ));
    }

    @Override
    public void onCompensationRetry(String sagaName, String sagaId, String stepId, int attempt) {
        log.info(JsonUtils.json(
                "saga_event","compensation_retry",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId,
                "attempt", Integer.toString(attempt)
        ));
    }

    @Override
    public void onCompensationSkipped(String sagaName, String sagaId, String stepId, String reason) {
        log.info(JsonUtils.json(
                "saga_event","compensation_skipped",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId,
                "reason", safeString(reason, 300)
        ));
    }

    @Override
    public void onCompensationCircuitOpen(String sagaName, String sagaId, String stepId) {
        log.warn(JsonUtils.json(
                "saga_event","compensation_circuit_open",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId
        ));
    }

    @Override
    public void onCompensationBatchCompleted(String sagaName, String sagaId, java.util.List<String> stepIds, boolean allSuccessful) {
        int batchSize = stepIds != null ? stepIds.size() : 0;
        log.info(JsonUtils.json(
                "saga_event","compensation_batch_completed",
                "saga", sagaName,
                "sagaId", sagaId,
                "steps_count", Integer.toString(batchSize),
                "success_all", Boolean.toString(allSuccessful)
        ));
    }

    @Override
    public void onCompleted(String sagaName, String sagaId, boolean success) {
        log.info(JsonUtils.json(
                "saga_event","completed",
                "saga", sagaName,
                "sagaId", sagaId,
                "success", Boolean.toString(success)
        ));

        if (metricsCollector != null) {
            if (success) {
                metricsCollector.recordSagaCompleted(sagaName, sagaId, java.time.Duration.ZERO); // Duration will be calculated internally
            } else {
                metricsCollector.recordSagaFailed(sagaName, sagaId, new RuntimeException("Saga failed"));
            }
        }
    }

    private static String safeString(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max)) + "...";
    }

}
