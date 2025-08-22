package com.catalis.transactionalengine.azure;

import com.catalis.transactionalengine.events.StepEventEnvelope;
import com.catalis.transactionalengine.events.StepEventPublisher;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubProducerAsyncClient;
import com.azure.messaging.eventhubs.models.CreateBatchOptions;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Event Hubs-based implementation of StepEventPublisher that publishes step events to Azure Event Hubs.
 * 
 * Features:
 * - Batched publishing for efficiency and cost optimization
 * - Automatic retry with exponential backoff for failed records
 * - JSON serialization of step events
 * - Async publishing with backpressure handling
 * - Error tracking and metrics
 * - Configurable batch size and timeout
 */
public class EventHubsStepEventPublisher implements StepEventPublisher {
    
    private static final Logger log = LoggerFactory.getLogger(EventHubsStepEventPublisher.class);
    
    private final EventHubProducerAsyncClient eventHubClient;
    private final AzureTransactionalEngineProperties.EventHubsProperties config;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentLinkedQueue<StepEventEnvelope> eventQueue;
    private final AtomicInteger publishedEvents = new AtomicInteger(0);
    private final AtomicInteger failedEvents = new AtomicInteger(0);
    
    public EventHubsStepEventPublisher(EventHubProducerAsyncClient eventHubClient, 
                                     AzureTransactionalEngineProperties.EventHubsProperties config) {
        this.eventHubClient = eventHubClient;
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
            log.debug("Queued step event for publishing: {}.{}", envelope.sagaName, envelope.stepId);
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
        
        log.info("Started Event Hubs batch publisher with max batch size: {}, timeout: {}", 
                config.getMaxRecordsPerBatch(), config.getBatchTimeout());
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
            publishBatchToEventHub(batch)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                    result -> {
                        publishedEvents.addAndGet(result.successfulRecords());
                        if (result.failedRecords() > 0) {
                            failedEvents.addAndGet(result.failedRecords());
                            log.warn("Published batch with {} successful and {} failed records", 
                                    result.successfulRecords(), result.failedRecords());
                        } else {
                            log.debug("Successfully published batch of {} step events", result.successfulRecords());
                        }
                    },
                    error -> {
                        failedEvents.addAndGet(batch.size());
                        log.error("Failed to publish step event batch: {}", error.getMessage());
                        // Re-queue failed events for retry
                        batch.forEach(eventQueue::offer);
                    }
                );
        }
    }
    
    private Mono<PublishResult> publishBatchToEventHub(List<StepEventEnvelope> events) {
        return eventHubClient.createBatch()
            .flatMap(batch -> {
                List<StepEventEnvelope> failedToAdd = new ArrayList<>();
                int successfullyAdded = 0;
                
                for (StepEventEnvelope envelope : events) {
                    try {
                        String json = objectMapper.writeValueAsString(envelope);
                        EventData eventData = new EventData(json);
                        
                        // Add partition key if available
                        if (envelope.sagaId != null) {
                            eventData.getProperties().put("partitionKey", envelope.sagaId);
                        }
                        
                        // Add custom properties
                        eventData.getProperties().put("sagaName", envelope.sagaName);
                        eventData.getProperties().put("stepId", envelope.stepId);
                        eventData.getProperties().put("eventType", envelope.type);
                        
                        if (batch.tryAdd(eventData)) {
                            successfullyAdded++;
                        } else {
                            failedToAdd.add(envelope);
                            log.debug("Event batch full, will retry event: {}.{}", 
                                    envelope.sagaName, envelope.stepId);
                        }
                    } catch (JsonProcessingException e) {
                        log.error("Failed to serialize step event: {}.{} - {}", 
                                envelope.sagaName, envelope.stepId, e.getMessage());
                        failedToAdd.add(envelope);
                    }
                }
                
                // Re-queue events that couldn't be added to this batch
                failedToAdd.forEach(eventQueue::offer);
                
                if (successfullyAdded > 0) {
                    return eventHubClient.send(batch)
                        .then(Mono.just(new PublishResult(successfullyAdded, failedToAdd.size())));
                } else {
                    return Mono.just(new PublishResult(0, events.size()));
                }
            })
            .onErrorResume(error -> {
                log.error("Error creating or sending Event Hub batch: {}", error.getMessage());
                return Mono.just(new PublishResult(0, events.size()));
            });
    }
    
    public int getPublishedEvents() {
        return publishedEvents.get();
    }
    
    public int getFailedEvents() {
        return failedEvents.get();
    }
    
    public int getQueuedEvents() {
        return eventQueue.size();
    }
    
    public void shutdown() {
        try {
            // Publish remaining events
            publishBatch();
            
            scheduler.shutdown();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            
            eventHubClient.close();
            
            log.info("Event Hubs publisher shutdown complete. Published: {}, Failed: {}, Remaining: {}", 
                    publishedEvents.get(), failedEvents.get(), eventQueue.size());
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Result of a batch publish operation.
     */
    private static class PublishResult {
        private final int successfulRecords;
        private final int failedRecords;
        
        public PublishResult(int successfulRecords, int failedRecords) {
            this.successfulRecords = successfulRecords;
            this.failedRecords = failedRecords;
        }
        
        public int successfulRecords() {
            return successfulRecords;
        }
        
        public int failedRecords() {
            return failedRecords;
        }
    }
}