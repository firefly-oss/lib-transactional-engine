package com.catalis.transactionalengine.aws;

import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.observability.SagaEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * CloudWatch-based implementation of SagaEvents that publishes metrics to AWS CloudWatch.
 * 
 * Features:
 * - Batched metric publishing for efficiency
 * - Automatic metric aggregation by saga name and status
 * - Custom dimensions for detailed analysis
 * - Error handling with fallback to logging
 * - Configurable publishing intervals
 */
public class CloudWatchSagaEvents implements SagaEvents {
    
    private static final Logger log = LoggerFactory.getLogger(CloudWatchSagaEvents.class);
    
    private final CloudWatchAsyncClient cloudWatchClient;
    private final AwsTransactionalEngineProperties.CloudWatchProperties config;
    private final ScheduledExecutorService scheduler;
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, List<Double>> gauges = new ConcurrentHashMap<>();
    
    public CloudWatchSagaEvents(CloudWatchAsyncClient cloudWatchClient, 
                               AwsTransactionalEngineProperties.CloudWatchProperties config) {
        this.cloudWatchClient = cloudWatchClient;
        this.config = config;
        this.scheduler = Executors.newScheduledThreadPool(2);
        
        // Start periodic metric publishing
        startMetricPublisher();
    }
    
    @Override
    public void onStart(String sagaName, String sagaId) {
        incrementCounter("saga_started", sagaName);
        log.debug("Saga started: {} ({})", sagaName, sagaId);
    }
    
    @Override
    public void onStart(String sagaName, String sagaId, SagaContext context) {
        onStart(sagaName, sagaId);
        recordGauge("saga_context_variables", sagaName, context.variables().size());
        recordGauge("saga_context_headers", sagaName, context.headers().size());
    }
    
    @Override
    public void onCompleted(String sagaName, String sagaId, boolean success) {
        if (success) {
            incrementCounter("saga_completed_success", sagaName);
        } else {
            incrementCounter("saga_completed_failure", sagaName);
        }
        log.debug("Saga completed: {} ({}) - success: {}", sagaName, sagaId, success);
    }
    
    @Override
    public void onStepStarted(String sagaName, String sagaId, String stepId) {
        incrementCounter("step_started", sagaName, stepId);
        log.debug("Step started: {}.{} ({})", sagaName, stepId, sagaId);
    }
    
    @Override
    public void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs) {
        incrementCounter("step_completed_success", sagaName, stepId);
        recordGauge("step_latency_ms", sagaName, latencyMs);
        recordGauge("step_attempts", sagaName, attempts);
        log.debug("Step succeeded: {}.{} ({}) - {} attempts, {}ms", 
                sagaName, stepId, sagaId, attempts, latencyMs);
    }
    
    @Override
    public void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs) {
        incrementCounter("step_completed_failure", sagaName, stepId);
        incrementCounter("step_error_" + error.getClass().getSimpleName(), sagaName, stepId);
        recordGauge("step_latency_ms", sagaName, latencyMs);
        recordGauge("step_attempts", sagaName, attempts);
        log.warn("Step failed: {}.{} ({}) - {} attempts, {}ms - {}", 
                sagaName, stepId, sagaId, attempts, latencyMs, error.getMessage());
    }
    
    public void onStepRetry(String sagaName, String sagaId, String stepId, int attempt, Throwable error) {
        incrementCounter("step_retry", sagaName, stepId);
        log.debug("Step retry: {}.{} ({}) - attempt {}", sagaName, stepId, sagaId, attempt);
    }
    
    @Override
    public void onStepSkippedIdempotent(String sagaName, String sagaId, String stepId) {
        incrementCounter("step_skipped_idempotent", sagaName, stepId);
        log.debug("Step skipped (idempotent): {}.{} ({})", sagaName, stepId, sagaId);
    }
    
    @Override
    public void onCompensationStarted(String sagaName, String sagaId, String stepId) {
        incrementCounter("compensation_started", sagaName, stepId);
        log.debug("Compensation started: {}.{} ({})", sagaName, stepId, sagaId);
    }
    
    @Override
    public void onCompensated(String sagaName, String sagaId, String stepId, Throwable error) {
        if (error == null) {
            // Compensation succeeded
            incrementCounter("compensation_completed_success", sagaName, stepId);
            log.debug("Compensation succeeded: {}.{} ({})", sagaName, stepId, sagaId);
        } else {
            // Compensation failed
            incrementCounter("compensation_completed_failure", sagaName, stepId);
            incrementCounter("compensation_error_" + error.getClass().getSimpleName(), sagaName, stepId);
            log.warn("Compensation failed: {}.{} ({}) - {}", sagaName, stepId, sagaId, error.getMessage());
        }
    }
    
    @Override
    public void onCompensationRetry(String sagaName, String sagaId, String stepId, int attempt) {
        incrementCounter("compensation_retry", sagaName, stepId);
        log.debug("Compensation retry: {}.{} ({}) - attempt {}", sagaName, stepId, sagaId, attempt);
    }
    
    @Override
    public void onCompensationSkipped(String sagaName, String sagaId, String stepId, String reason) {
        incrementCounter("compensation_skipped_" + reason, sagaName, stepId);
        log.debug("Compensation skipped: {}.{} ({}) - reason: {}", sagaName, stepId, sagaId, reason);
    }
    
    @Override
    public void onCompensationCircuitOpen(String sagaName, String sagaId, String stepId) {
        incrementCounter("compensation_circuit_open", sagaName, stepId);
        log.warn("Compensation circuit opened: {}.{} ({})", sagaName, stepId, sagaId);
    }
    
    @Override
    public void onCompensationBatchCompleted(String sagaName, String sagaId, List<String> stepIds, boolean success) {
        String metricName = success ? "compensation_batch_success" : "compensation_batch_failure";
        incrementCounter(metricName, sagaName);
        recordGauge("compensation_batch_size", sagaName, stepIds.size());
        log.debug("Compensation batch completed: {} ({}) - {} steps, success: {}", 
                sagaName, sagaId, stepIds.size(), success);
    }
    
    private void incrementCounter(String metricName, String sagaName) {
        incrementCounter(metricName, sagaName, null);
    }
    
    private void incrementCounter(String metricName, String sagaName, String stepId) {
        String key = buildMetricKey(metricName, sagaName, stepId);
        counters.computeIfAbsent(key, k -> new LongAdder()).increment();
    }
    
    private void recordGauge(String metricName, String sagaName, double value) {
        String key = buildMetricKey(metricName, sagaName, null);
        gauges.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).add(value);
    }
    
    private String buildMetricKey(String metricName, String sagaName, String stepId) {
        if (stepId != null) {
            return metricName + "|" + sagaName + "|" + stepId;
        }
        return metricName + "|" + sagaName;
    }
    
    private void startMetricPublisher() {
        scheduler.scheduleAtFixedRate(
            this::publishMetricsToCloudWatch,
            config.getPublishInterval().toSeconds(),
            config.getPublishInterval().toSeconds(),
            TimeUnit.SECONDS
        );
    }
    
    private void publishMetricsToCloudWatch() {
        try {
            List<MetricDatum> metricData = new ArrayList<>();
            
            // Publish counters
            publishCounterMetrics(metricData);
            
            // Publish gauge metrics
            publishGaugeMetrics(metricData);
            
            if (!metricData.isEmpty()) {
                // Split into batches if necessary (CloudWatch limit is 20 metrics per request)
                Flux.fromIterable(metricData)
                    .buffer(config.getMaxBatchSize())
                    .flatMap(this::publishMetricBatch)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                        result -> log.debug("Published {} metrics to CloudWatch", result),
                        error -> log.error("Failed to publish metrics to CloudWatch: {}", error.getMessage())
                    );
            }
            
        } catch (Exception e) {
            log.error("Error during metric publishing: {}", e.getMessage(), e);
        }
    }
    
    private void publishCounterMetrics(List<MetricDatum> metricData) {
        for (Map.Entry<String, LongAdder> entry : counters.entrySet()) {
            long value = entry.getValue().sumThenReset();
            if (value > 0) {
                MetricDatum datum = createMetricDatum(entry.getKey(), value, StandardUnit.COUNT);
                metricData.add(datum);
            }
        }
    }
    
    private void publishGaugeMetrics(List<MetricDatum> metricData) {
        for (Map.Entry<String, List<Double>> entry : gauges.entrySet()) {
            List<Double> values;
            synchronized (entry.getValue()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                values = new ArrayList<>(entry.getValue());
                entry.getValue().clear();
            }
            
            if (!values.isEmpty()) {
                // Publish statistics
                double sum = values.stream().mapToDouble(Double::doubleValue).sum();
                double avg = sum / values.size();
                double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
                double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
                
                // Create statistical set
                StatisticSet statisticSet = StatisticSet.builder()
                    .sampleCount((double) values.size())
                    .sum(sum)
                    .minimum(min)
                    .maximum(max)
                    .build();
                
                MetricDatum datum = createMetricDatumWithStats(entry.getKey(), statisticSet);
                metricData.add(datum);
            }
        }
    }
    
    private MetricDatum createMetricDatum(String metricKey, double value, StandardUnit unit) {
        String[] parts = metricKey.split("\\|");
        String metricName = parts[0];
        String sagaName = parts[1];
        String stepId = parts.length > 2 ? parts[2] : null;
        
        List<Dimension> dimensions = new ArrayList<>();
        dimensions.add(Dimension.builder().name("SagaName").value(sagaName).build());
        if (stepId != null) {
            dimensions.add(Dimension.builder().name("StepId").value(stepId).build());
        }
        
        return MetricDatum.builder()
            .metricName(metricName)
            .value(value)
            .unit(unit)
            .timestamp(Instant.now())
            .dimensions(dimensions)
            .build();
    }
    
    private MetricDatum createMetricDatumWithStats(String metricKey, StatisticSet statisticSet) {
        String[] parts = metricKey.split("\\|");
        String metricName = parts[0];
        String sagaName = parts[1];
        
        List<Dimension> dimensions = new ArrayList<>();
        dimensions.add(Dimension.builder().name("SagaName").value(sagaName).build());
        
        return MetricDatum.builder()
            .metricName(metricName)
            .statisticValues(statisticSet)
            .timestamp(Instant.now())
            .dimensions(dimensions)
            .build();
    }
    
    private Mono<Integer> publishMetricBatch(List<MetricDatum> batch) {
        PutMetricDataRequest request = PutMetricDataRequest.builder()
            .namespace(config.getNamespace())
            .metricData(batch)
            .build();
        
        return Mono.fromFuture(cloudWatchClient.putMetricData(request))
            .map(response -> batch.size())
            .onErrorResume(error -> {
                log.error("Failed to publish metric batch: {}", error.getMessage());
                return Mono.just(0);
            });
    }
    
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}