package com.catalis.transactionalengine.observability;

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
        log.info(json(
                "saga_event","start",
                "saga", sagaName,
                "sagaId", sagaId
        ));
    }

    @Override
    public void onStart(String sagaName, String sagaId, com.catalis.transactionalengine.core.SagaContext ctx) {
        if (ctx == null) {
            log.info(json(
                    "saga_event","start_ctx",
                    "saga", sagaName,
                    "sagaId", sagaId,
                    "headers_count", "0"
            ));
        } else {
            int headers = ctx.headers() != null ? ctx.headers().size() : 0;
            int vars = ctx.variables() != null ? ctx.variables().size() : 0;
            log.info(json(
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
        log.info(json(
                "saga_event","step_started",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId
        ));
    }

    @Override
    public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {
        log.info(json(
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
        log.warn(json(
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
        log.info(json(
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
        log.info(json(
                "saga_event","step_skipped_idempotent",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId
        ));
    }

    @Override
    public void onCompensationStarted(String sagaName, String sagaId, String stepId) {
        log.info(json(
                "saga_event","compensation_started",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId
        ));
    }

    @Override
    public void onCompensationRetry(String sagaName, String sagaId, String stepId, int attempt) {
        log.info(json(
                "saga_event","compensation_retry",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId,
                "attempt", Integer.toString(attempt)
        ));
    }

    @Override
    public void onCompensationSkipped(String sagaName, String sagaId, String stepId, String reason) {
        log.info(json(
                "saga_event","compensation_skipped",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId,
                "reason", safeString(reason, 300)
        ));
    }

    @Override
    public void onCompensationCircuitOpen(String sagaName, String sagaId, String stepId) {
        log.warn(json(
                "saga_event","compensation_circuit_open",
                "saga", sagaName,
                "sagaId", sagaId,
                "stepId", stepId
        ));
    }

    @Override
    public void onCompensationBatchCompleted(String sagaName, String sagaId, java.util.List<String> stepIds, boolean allSuccessful) {
        int batchSize = stepIds != null ? stepIds.size() : 0;
        log.info(json(
                "saga_event","compensation_batch_completed",
                "saga", sagaName,
                "sagaId", sagaId,
                "steps_count", Integer.toString(batchSize),
                "success_all", Boolean.toString(allSuccessful)
        ));
    }

    @Override
    public void onCompleted(String sagaName, String sagaId, boolean success) {
        log.info(json(
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

    private static String json(String... kv) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(esc(kv[i])).append('"').append(':');
            sb.append('"').append(esc(kv[i + 1] == null ? "" : kv[i + 1])).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
