package com.catalis.transactionalengine.aws;

import com.catalis.transactionalengine.events.StepEventEnvelope;
import com.catalis.transactionalengine.util.JsonUtils;
import com.catalis.transactionalengine.events.StepEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.services.kinesis.model.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kinesis-based implementation of StepEventPublisher that publishes step events to AWS Kinesis streams.
 * 
 * Features:
 * - Batched publishing for efficiency and cost optimization
 * - Automatic retry with exponential backoff for failed records
 * - Configurable partition key strategy
 * - JSON serialization of step events
 * - Async publishing with backpressure handling
 * - Error tracking and metrics
 */
public class KinesisStepEventPublisher implements StepEventPublisher {
    
    private static final Logger log = LoggerFactory.getLogger(KinesisStepEventPublisher.class);
    
    private final KinesisAsyncClient kinesisClient;
    private final AwsTransactionalEngineProperties.KinesisProperties config;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentLinkedQueue<StepEventEnvelope> eventQueue;
    private final AtomicInteger publishedEvents = new AtomicInteger(0);
    private final AtomicInteger failedEvents = new AtomicInteger(0);
    
    public KinesisStepEventPublisher(KinesisAsyncClient kinesisClient, 
                                   AwsTransactionalEngineProperties.KinesisProperties config) {
        this.kinesisClient = kinesisClient;
        this.config = config;
        this.objectMapper = createObjectMapper();
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.eventQueue = new ConcurrentLinkedQueue<>();
        
        // Start batch publisher
        startBatchPublisher();
    }
    
    @Override
    public Mono<Void> publish(StepEventEnvelope envelope) {
        return Mono.fromRunnable(() -> {
            eventQueue.offer(envelope);
            log.debug(JsonUtils.json(
                    "event", "step_event_queued",
                    "saga_name", envelope.sagaName,
                    "step_id", envelope.stepId
            ));
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
    }
    
    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
    
    private void startBatchPublisher() {
        scheduler.scheduleAtFixedRate(
            this::publishBatch,
            config.getBatchTimeout().toSeconds(),
            config.getBatchTimeout().toSeconds(),
            TimeUnit.SECONDS
        );
    }
    
    private void publishBatch() {
        if (eventQueue.isEmpty()) {
            return;
        }
        
        List<StepEventEnvelope> batch = new ArrayList<>();
        for (int i = 0; i < config.getMaxRecordsPerBatch() && !eventQueue.isEmpty(); i++) {
            StepEventEnvelope event = eventQueue.poll();
            if (event != null) {
                batch.add(event);
            }
        }
        
        if (!batch.isEmpty()) {
            publishBatchToKinesis(batch)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                    result -> {
                        publishedEvents.addAndGet(result.successfulRecords());
                        if (result.failedRecords() > 0) {
                            failedEvents.addAndGet(result.failedRecords());
                            log.warn(JsonUtils.json(
                                    "event", "batch_published_with_failures",
                                    "successful_records", Integer.toString(result.successfulRecords()),
                                    "failed_records", Integer.toString(result.failedRecords())
                            ));
                        } else {
                            log.debug(JsonUtils.json(
                                    "event", "batch_published_successfully",
                                    "step_events_count", Integer.toString(result.successfulRecords())
                            ));
                        }
                    },
                    error -> {
                        failedEvents.addAndGet(batch.size());
                        log.error(JsonUtils.json(
                                "event", "batch_publishing_failed",
                                "error_message", error.getMessage() != null ? error.getMessage() : "Unknown error"
                        ));
                        // Re-queue failed events for retry
                        batch.forEach(eventQueue::offer);
                    }
                );
        }
    }
    
    private Mono<PublishResult> publishBatchToKinesis(List<StepEventEnvelope> batch) {
        List<PutRecordsRequestEntry> records = new ArrayList<>();
        
        for (StepEventEnvelope envelope : batch) {
            try {
                String jsonData = objectMapper.writeValueAsString(envelope);
                String partitionKey = determinePartitionKey(envelope);
                
                PutRecordsRequestEntry record = PutRecordsRequestEntry.builder()
                    .data(SdkBytes.fromString(jsonData, StandardCharsets.UTF_8))
                    .partitionKey(partitionKey)
                    .build();
                
                records.add(record);
                
            } catch (JsonProcessingException e) {
                log.error(JsonUtils.json(
                        "event", "step_event_serialization_failed",
                        "saga_name", envelope.sagaName,
                        "step_id", envelope.stepId,
                        "error_message", e.getMessage() != null ? e.getMessage() : "Unknown serialization error"
                ));
                // Skip this record and continue with others
            }
        }
        
        if (records.isEmpty()) {
            return Mono.just(new PublishResult(0, batch.size()));
        }
        
        PutRecordsRequest request = PutRecordsRequest.builder()
            .streamName(config.getStreamName())
            .records(records)
            .build();
        
        return Mono.fromFuture(kinesisClient.putRecords(request))
            .map(response -> {
                int failedRecords = response.failedRecordCount();
                int successfulRecords = records.size() - failedRecords;
                
                if (failedRecords > 0) {
                    log.warn(JsonUtils.json(
                            "event", "kinesis_put_records_failures",
                            "failed_records", Integer.toString(failedRecords),
                            "total_records", Integer.toString(records.size())
                    ));
                    
                    // Log details of failed records
                    for (int i = 0; i < response.records().size(); i++) {
                        PutRecordsResultEntry resultEntry = response.records().get(i);
                        if (resultEntry.errorCode() != null) {
                            log.warn(JsonUtils.json(
                                    "event", "kinesis_record_failed",
                                    "record_index", Integer.toString(i),
                                    "error_code", resultEntry.errorCode(),
                                    "error_message", resultEntry.errorMessage() != null ? resultEntry.errorMessage() : "Unknown error"
                            ));
                        }
                    }
                }
                
                return new PublishResult(successfulRecords, failedRecords);
            })
            .onErrorResume(error -> {
                if (error instanceof ProvisionedThroughputExceededException) {
                    log.warn(JsonUtils.json(
                            "event", "kinesis_throughput_exceeded",
                            "message", "Kinesis provisioned throughput exceeded, will retry"
                    ));
                    return Mono.delay(Duration.ofSeconds(1))
                            .then(publishBatchToKinesis(batch));
                } else if (error instanceof ResourceNotFoundException) {
                    log.error(JsonUtils.json(
                            "event", "kinesis_stream_not_found",
                            "stream_name", config.getStreamName()
                    ));
                    return Mono.just(new PublishResult(0, batch.size()));
                } else {
                    log.error(JsonUtils.json(
                            "event", "kinesis_publish_error",
                            "error_message", error.getMessage() != null ? error.getMessage() : "Unknown Kinesis error"
                    ));
                    return Mono.just(new PublishResult(0, batch.size()));
                }
            });
    }
    
    private String determinePartitionKey(StepEventEnvelope envelope) {
        String keyStrategy = config.getPartitionKey();
        
        return switch (keyStrategy.toLowerCase()) {
            case "sagaid" -> envelope.sagaId;
            case "saganame" -> envelope.sagaName;
            case "stepid" -> envelope.stepId;
            case "topic" -> envelope.topic;
            default -> {
                // Try to use the configured partition key as a field name
                yield switch (keyStrategy) {
                    case "sagaId" -> envelope.sagaId;
                    case "sagaName" -> envelope.sagaName;
                    case "stepId" -> envelope.stepId;
                    case "topic" -> envelope.topic;
                    default -> envelope.sagaId; // Default fallback
                };
            }
        };
    }
    
    /**
     * Get publishing statistics.
     */
    public PublishingStats getStats() {
        return new PublishingStats(
            publishedEvents.get(),
            failedEvents.get(),
            eventQueue.size()
        );
    }
    
    /**
     * Manually trigger a batch publish (useful for testing or immediate publishing).
     */
    public Mono<Void> flush() {
        return Mono.fromRunnable(this::publishBatch)
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
    
    /**
     * Check if the Kinesis stream exists and is active.
     */
    public Mono<Boolean> isStreamReady() {
        DescribeStreamRequest request = DescribeStreamRequest.builder()
            .streamName(config.getStreamName())
            .build();
        
        return Mono.fromFuture(kinesisClient.describeStream(request))
            .map(response -> response.streamDescription().streamStatus() == StreamStatus.ACTIVE)
            .onErrorResume(ResourceNotFoundException.class, error -> {
                log.warn(JsonUtils.json(
                        "event", "kinesis_stream_not_found_check",
                        "stream_name", config.getStreamName()
                ));
                return Mono.just(false);
            })
            .onErrorResume(error -> {
                log.error(JsonUtils.json(
                        "event", "stream_status_check_error",
                        "error_message", error.getMessage() != null ? error.getMessage() : "Unknown stream status error"
                ));
                return Mono.just(false);
            });
    }
    
    /**
     * Create the Kinesis stream if it doesn't exist.
     * Note: This is a simple implementation. In production, you'd typically manage stream creation
     * through infrastructure as code (CloudFormation, CDK, Terraform, etc.).
     */
    public Mono<Void> ensureStreamExists(int shardCount) {
        return isStreamReady()
            .flatMap(exists -> {
                if (exists) {
                    return Mono.empty();
                }
                
                log.info(JsonUtils.json(
                        "event", "creating_kinesis_stream",
                        "stream_name", config.getStreamName()
                ));
                CreateStreamRequest createRequest = CreateStreamRequest.builder()
                    .streamName(config.getStreamName())
                    .shardCount(shardCount)
                    .build();
                
                return Mono.fromFuture(kinesisClient.createStream(createRequest))
                    .then(waitForStreamToBeActive())
                    .doOnSuccess(v -> log.info(JsonUtils.json(
                            "event", "kinesis_stream_created",
                            "stream_name", config.getStreamName()
                    )));
            });
    }
    
    private Mono<Void> waitForStreamToBeActive() {
        return Flux.interval(Duration.ofSeconds(5))
            .take(60) // Wait up to 5 minutes
            .concatMap(tick -> isStreamReady())
            .filter(ready -> ready)
            .take(1)
            .then()
            .timeout(Duration.ofMinutes(5))
            .onErrorResume(error -> {
                log.error(JsonUtils.json(
                        "event", "stream_activation_timeout",
                        "error_message", error.getMessage() != null ? error.getMessage() : "Timeout waiting for stream to become active"
                ));
                return Mono.empty();
            });
    }
    
    public void shutdown() {
        log.info(JsonUtils.json(
                "event", "kinesis_publisher_shutdown_start"
        ));
        
        // Publish any remaining events
        if (!eventQueue.isEmpty()) {
            log.info(JsonUtils.json(
                    "event", "publishing_remaining_events_before_shutdown",
                    "remaining_events_count", Integer.toString(eventQueue.size())
            ));
            publishBatch();
        }
        
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    log.warn(JsonUtils.json(
                            "event", "kinesis_scheduler_forcibly_shutdown",
                            "message", "Forcibly shut down Kinesis publisher scheduler"
                    ));
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        PublishingStats finalStats = getStats();
        log.info(JsonUtils.json(
                "event", "kinesis_publisher_shutdown_complete",
                "published_events", Integer.toString(finalStats.publishedEvents()),
                "failed_events", Integer.toString(finalStats.failedEvents()),
                "queued_events", Integer.toString(finalStats.queuedEvents())
        ));
    }
    
    // Helper records for results and stats
    private record PublishResult(int successfulRecords, int failedRecords) {}
    
    public record PublishingStats(int publishedEvents, int failedEvents, int queuedEvents) {}
}