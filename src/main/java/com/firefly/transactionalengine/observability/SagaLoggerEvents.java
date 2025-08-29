package com.firefly.transactionalengine.observability;

import com.firefly.transactionalengine.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link SagaEvents} implementation that emits JSON-friendly key=value logs via SLF4J.
 * Enriched to include error class/message and compensation lifecycle.
 */
public class SagaLoggerEvents implements SagaEvents {
    private static final Logger log = LoggerFactory.getLogger(SagaLoggerEvents.class);

    @Override
    public void onStart(String sagaName, String sagaId) {
        log.info(JsonUtils.json(
                "saga_event","start",
                "saga", sagaName,
                "sagaId", sagaId
        ));
    }

    @Override
    public void onStart(String sagaName, String sagaId, com.firefly.transactionalengine.core.SagaContext ctx) {
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
    }

    private static String safeString(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max)) + "...";
    }

}
