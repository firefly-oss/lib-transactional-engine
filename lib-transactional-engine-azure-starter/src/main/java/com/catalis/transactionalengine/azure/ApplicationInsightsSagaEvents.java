package com.catalis.transactionalengine.azure;

import com.catalis.transactionalengine.observability.SagaEvents;
import com.catalis.transactionalengine.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Application Insights-based implementation of SagaEvents that would publish metrics 
 * to Application Insights if the dependencies were available.
 * 
 * Since Application Insights SDK is not available in Maven Central, this implementation
 * provides a mock/simulation of what the real implementation would do, with comprehensive
 * logging that mimics the metrics that would be sent to Application Insights.
 * 
 * In a real scenario with Application Insights dependencies, this would use:
 * - TelemetryClient for sending custom metrics and events
 * - Custom dimensions for saga metadata
 * - Performance counters for latency tracking
 * - Exception telemetry for error tracking
 */
public class ApplicationInsightsSagaEvents implements SagaEvents {
    
    private static final Logger log = LoggerFactory.getLogger(ApplicationInsightsSagaEvents.class);
    
    private final AzureTransactionalEngineProperties.ApplicationInsightsProperties properties;
    private final Map<String, Instant> stepStartTimes = new ConcurrentHashMap<>();
    private final AtomicLong sagaCounter = new AtomicLong();
    private final AtomicLong stepCounter = new AtomicLong();
    private final AtomicLong compensationCounter = new AtomicLong();

    public ApplicationInsightsSagaEvents(AzureTransactionalEngineProperties.ApplicationInsightsProperties properties) {
        this.properties = properties;
        log.info(JsonUtils.json(
                "event", "application_insights_initialized",
                "implementation", "mock",
                "instrumentation_key", safeString(properties.getInstrumentationKey(), 50),
                "connection_string_configured", Boolean.toString(properties.getConnectionString() != null),
                "publish_interval", properties.getPublishInterval().toString(),
                "max_batch_size", Integer.toString(properties.getMaxBatchSize())
        ));
    }

    @Override
    public void onStart(String sagaName, String sagaId) {
        long sagaNumber = sagaCounter.incrementAndGet();
        
        log.info(JsonUtils.json(
                "event", "application_insights_saga_started",
                "saga_name", sagaName,
                "saga_id", sagaId,
                "metric", "saga.started",
                "value", "1",
                "saga_count", Long.toString(sagaNumber)
        ));
        
        Map<String, String> properties = new HashMap<>();
        properties.put("sagaName", sagaName);
        properties.put("sagaId", sagaId);
        addCommonProperties(properties);
        
        logTelemetryEvent("SagaStarted", properties, Map.of("sagaCount", (double) sagaNumber));
    }

    @Override
    public void onStepStarted(String sagaName, String sagaId, String stepId) {
        String stepKey = sagaId + ":" + stepId;
        stepStartTimes.put(stepKey, Instant.now());
        long stepNumber = stepCounter.incrementAndGet();
        
        log.info(JsonUtils.json(
                "event", "application_insights_step_started",
                "saga_name", sagaName,
                "saga_id", sagaId,
                "step_id", stepId,
                "metric", "step.started",
                "value", "1",
                "step_count", Long.toString(stepNumber)
        ));
        
        Map<String, String> properties = new HashMap<>();
        properties.put("sagaName", sagaName);
        properties.put("sagaId", sagaId);
        properties.put("stepId", stepId);
        addCommonProperties(properties);
        
        logTelemetryEvent("StepStarted", properties, Map.of("stepCount", (double) stepNumber));
    }

    @Override
    public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {
        String stepKey = sagaId + ":" + stepId;
        stepStartTimes.remove(stepKey);
        
        log.info(JsonUtils.json(
                "event", "application_insights_step_success",
                "saga_name", sagaName,
                "saga_id", sagaId,
                "step_id", stepId,
                "metric", "step.success",
                "value", "1",
                "latency_ms", Long.toString(latencyMs),
                "attempts", Integer.toString(attempts)
        ));
        
        Map<String, String> properties = new HashMap<>();
        properties.put("sagaName", sagaName);
        properties.put("sagaId", sagaId);
        properties.put("stepId", stepId);
        properties.put("attempts", String.valueOf(attempts));
        addCommonProperties(properties);
        
        Map<String, Double> metrics = new HashMap<>();
        metrics.put("latencyMs", (double) latencyMs);
        metrics.put("attempts", (double) attempts);
        
        logTelemetryEvent("StepSuccess", properties, metrics);
    }

    @Override
    public void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) {
        String stepKey = sagaId + ":" + stepId;
        stepStartTimes.remove(stepKey);
        
        log.error(JsonUtils.json(
                "event", "application_insights_step_failed",
                "saga_name", sagaName,
                "saga_id", sagaId,
                "step_id", stepId,
                "metric", "step.failed",
                "value", "1",
                "latency_ms", Long.toString(latencyMs),
                "attempts", Integer.toString(attempts),
                "error_type", error.getClass().getSimpleName(),
                "error_message", safeString(error.getMessage(), 500)
        ), error);
        
        Map<String, String> properties = new HashMap<>();
        properties.put("sagaName", sagaName);
        properties.put("sagaId", sagaId);
        properties.put("stepId", stepId);
        properties.put("errorType", error.getClass().getSimpleName());
        properties.put("errorMessage", error.getMessage());
        properties.put("attempts", String.valueOf(attempts));
        addCommonProperties(properties);
        
        Map<String, Double> metrics = new HashMap<>();
        metrics.put("latencyMs", (double) latencyMs);
        metrics.put("attempts", (double) attempts);
        
        logTelemetryEvent("StepFailed", properties, metrics);
        logTelemetryException(error, properties);
    }

    @Override
    public void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) {
        long compensationNumber = compensationCounter.incrementAndGet();
        
        if (error == null) {
            log.info(JsonUtils.json(
                    "event", "application_insights_compensation_succeeded",
                    "saga_name", sagaName,
                    "saga_id", sagaId,
                    "step_id", stepId,
                    "metric", "compensation.success",
                    "value", "1"
            ));
        } else {
            log.error(JsonUtils.json(
                    "event", "application_insights_compensation_failed",
                    "saga_name", sagaName,
                    "saga_id", sagaId,
                    "step_id", stepId,
                    "metric", "compensation.failed",
                    "value", "1",
                    "error_message", safeString(error.getMessage(), 500)
            ), error);
        }
        
        Map<String, String> properties = new HashMap<>();
        properties.put("sagaName", sagaName);
        properties.put("sagaId", sagaId);
        properties.put("stepId", stepId);
        properties.put("success", String.valueOf(error == null));
        if (error != null) {
            properties.put("errorType", error.getClass().getSimpleName());
            properties.put("errorMessage", error.getMessage());
        }
        addCommonProperties(properties);
        
        logTelemetryEvent("Compensation", properties, Map.of("compensationCount", (double) compensationNumber));
        
        if (error != null) {
            logTelemetryException(error, properties);
        }
    }

    @Override
    public void onStepSkippedIdempotent(String sagaName, String sagaId, String stepId) {
        log.info(JsonUtils.json(
                "event", "application_insights_step_skipped_idempotent",
                "saga_name", sagaName,
                "saga_id", sagaId,
                "step_id", stepId,
                "metric", "step.skipped.idempotent",
                "value", "1",
                "reason", "idempotent"
        ));
        
        Map<String, String> properties = new HashMap<>();
        properties.put("sagaName", sagaName);
        properties.put("sagaId", sagaId);
        properties.put("stepId", stepId);
        properties.put("reason", "idempotent");
        addCommonProperties(properties);
        
        logTelemetryEvent("StepSkipped", properties, Map.of());
    }

    @Override
    public void onCompensationStarted(String sagaName, String sagaId, String stepId) {
        log.info(JsonUtils.json(
                "event", "application_insights_compensation_started",
                "saga_name", sagaName,
                "saga_id", sagaId,
                "step_id", stepId,
                "metric", "compensation.started",
                "value", "1"
        ));
        
        Map<String, String> properties = new HashMap<>();
        properties.put("sagaName", sagaName);
        properties.put("sagaId", sagaId);
        properties.put("stepId", stepId);
        addCommonProperties(properties);
        
        logTelemetryEvent("CompensationStarted", properties, Map.of());
    }

    @Override
    public void onCompensationRetry(String sagaName, String sagaId, String stepId, int attempt) {
        log.warn(JsonUtils.json(
                "event", "application_insights_compensation_retry",
                "saga_name", sagaName,
                "saga_id", sagaId,
                "step_id", stepId,
                "metric", "compensation.retry",
                "value", "1",
                "attempt", Integer.toString(attempt)
        ));
        
        Map<String, String> properties = new HashMap<>();
        properties.put("sagaName", sagaName);
        properties.put("sagaId", sagaId);
        properties.put("stepId", stepId);
        properties.put("attempt", String.valueOf(attempt));
        addCommonProperties(properties);
        
        logTelemetryEvent("CompensationRetry", properties, Map.of("attempt", (double) attempt));
    }

    @Override
    public void onCompensationSkipped(String sagaName, String sagaId, String stepId, String reason) {
        log.info(JsonUtils.json(
                "event", "application_insights_compensation_skipped",
                "saga_name", sagaName,
                "saga_id", sagaId,
                "step_id", stepId,
                "metric", "compensation.skipped",
                "value", "1",
                "reason", safeString(reason, 300)
        ));
        
        Map<String, String> properties = new HashMap<>();
        properties.put("sagaName", sagaName);
        properties.put("sagaId", sagaId);
        properties.put("stepId", stepId);
        properties.put("reason", reason);
        addCommonProperties(properties);
        
        logTelemetryEvent("CompensationSkipped", properties, Map.of());
    }

    @Override
    public void onCompensationCircuitOpen(String sagaName, String sagaId, String stepId) {
        log.warn(JsonUtils.json(
                "event", "application_insights_compensation_circuit_open",
                "saga_name", sagaName,
                "saga_id", sagaId,
                "step_id", stepId,
                "metric", "compensation.circuit.open",
                "value", "1"
        ));
        
        Map<String, String> properties = new HashMap<>();
        properties.put("sagaName", sagaName);
        properties.put("sagaId", sagaId);
        properties.put("stepId", stepId);
        addCommonProperties(properties);
        
        logTelemetryEvent("CompensationCircuitOpen", properties, Map.of());
    }

    @Override
    public void onCompensationBatchCompleted(String sagaName, String sagaId, List<String> stepIds, boolean allSuccessful) {
        log.info(JsonUtils.json(
                "event", "application_insights_compensation_batch_completed",
                "saga_name", sagaName,
                "saga_id", sagaId,
                "metric", "compensation.batch.completed",
                "value", "1",
                "steps_count", Integer.toString(stepIds.size()),
                "success_all", Boolean.toString(allSuccessful),
                "steps", String.join(",", stepIds)
        ));
        
        Map<String, String> properties = new HashMap<>();
        properties.put("sagaName", sagaName);
        properties.put("sagaId", sagaId);
        properties.put("stepIds", String.join(",", stepIds));
        properties.put("allSuccessful", String.valueOf(allSuccessful));
        addCommonProperties(properties);
        
        logTelemetryEvent("CompensationBatchCompleted", properties, 
                Map.of("stepCount", (double) stepIds.size()));
    }

    @Override
    public void onCompleted(String sagaName, String sagaId, boolean success) {
        log.info(JsonUtils.json(
                "event", "application_insights_saga_completed",
                "saga_name", sagaName,
                "saga_id", sagaId,
                "metric", "saga.completed",
                "value", "1",
                "success", Boolean.toString(success)
        ));
        
        Map<String, String> properties = new HashMap<>();
        properties.put("sagaName", sagaName);
        properties.put("sagaId", sagaId);
        properties.put("success", String.valueOf(success));
        addCommonProperties(properties);
        
        logTelemetryEvent("SagaCompleted", properties, Map.of());
    }

    /**
     * Adds common properties to telemetry data
     */
    private void addCommonProperties(Map<String, String> properties) {
        if (this.properties.getInstrumentationKey() != null) {
            properties.put("instrumentationKey", this.properties.getInstrumentationKey());
        }
    }

    /**
     * Simulates sending a custom event to Application Insights
     */
    private void logTelemetryEvent(String eventName, Map<String, String> properties, Map<String, Double> metrics) {
        log.debug(JsonUtils.json(
                "event", "telemetry_event",
                "event_name", eventName,
                "properties", properties.toString(),
                "metrics", metrics.toString()
        ));
        
        // In real implementation, this would be:
        // telemetryClient.trackEvent(eventName, properties, metrics);
    }

    /**
     * Simulates sending exception telemetry to Application Insights
     */
    private void logTelemetryException(Throwable exception, Map<String, String> properties) {
        log.debug(JsonUtils.json(
                "event", "telemetry_exception", 
                "exception_type", exception.getClass().getSimpleName(),
                "exception_message", safeString(exception.getMessage(), 500),
                "properties", properties.toString()
        ));
    }

    private static String safeString(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max)) + "...";
    }
}