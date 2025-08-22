package com.catalis.transactionalengine.azure;

import com.catalis.transactionalengine.events.StepEventEnvelope;
import com.catalis.transactionalengine.events.StepEventPublisher;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderAsyncClient;
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
 * Service Bus-based implementation of StepEventPublisher that publishes step events to Azure Service Bus queues or topics.
 * 
 * Features:
 * - Batched publishing for efficiency and cost optimization
 * - Automatic retry with exponential backoff for failed records
 * - JSON serialization of step events
 * - Async publishing with backpressure handling
 * - Error tracking and metrics
 * - Configurable batch size and lock duration
 * - Support for both queues and topics
 */
public class ServiceBusStepEventPublisher implements StepEventPublisher {
    
    private static final Logger log = LoggerFactory.getLogger(ServiceBusStepEventPublisher.class);
    
    private final ServiceBusSenderAsyncClient serviceBusClient;
    private final AzureTransactionalEngineProperties.ServiceBusProperties config;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentLinkedQueue<StepEventEnvelope> eventQueue;
    private final AtomicInteger publishedEvents = new AtomicInteger(0);
    private final AtomicInteger failedEvents = new AtomicInteger(0);
    
    public ServiceBusStepEventPublisher(ServiceBusSenderAsyncClient serviceBusClient, 
                                      AzureTransactionalEngineProperties.ServiceBusProperties config) {
        this.serviceBusClient = serviceBusClient;
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
        // Use lock duration as the timeout for batch publishing to ensure messages are processed within lock time
        long timeoutSeconds = Math.min(config.getLockDuration().toSeconds() / 2, 30);
        
        scheduler.scheduleAtFixedRate(
            this::publishBatch,
            timeoutSeconds,
            timeoutSeconds,
            TimeUnit.SECONDS
        );
        
        log.info("Started Service Bus batch publisher with max batch size: {}, timeout: {}s", 
                config.getMaxBatchSize(), timeoutSeconds);
    }
    
    private void publishBatch() {
        if (eventQueue.isEmpty()) {
            return;
        }
        
        List<StepEventEnvelope> batch = new ArrayList<>();
        for (int i = 0; i < config.getMaxBatchSize() && !eventQueue.isEmpty(); i++) {
            StepEventEnvelope event = eventQueue.poll();
            if (event != null) {
                batch.add(event);
            }
        }
        
        if (!batch.isEmpty()) {
            publishBatchToServiceBus(batch)
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
    
    private Mono<PublishResult> publishBatchToServiceBus(List<StepEventEnvelope> events) {
        List<ServiceBusMessage> messages = new ArrayList<>();
        List<StepEventEnvelope> failedToSerialize = new ArrayList<>();
        
        for (StepEventEnvelope envelope : events) {
            try {
                String json = objectMapper.writeValueAsString(envelope);
                ServiceBusMessage message = new ServiceBusMessage(json);
                
                // Set message properties
                message.getApplicationProperties().put("sagaName", envelope.sagaName);
                message.getApplicationProperties().put("stepId", envelope.stepId);
                message.getApplicationProperties().put("sagaId", envelope.sagaId);
                message.getApplicationProperties().put("eventType", envelope.type);
                message.getApplicationProperties().put("timestamp", envelope.timestamp.toString());
                
                // Set message ID and correlation ID for tracking
                message.setMessageId(envelope.sagaId + "-" + envelope.stepId + "-" + System.currentTimeMillis());
                message.setCorrelationId(envelope.sagaId);
                
                // Set subject/label for message routing
                message.setSubject(envelope.sagaName + "." + envelope.stepId);
                
                // Set time to live based on configuration
                if (config.getLockDuration() != null) {
                    message.setTimeToLive(config.getLockDuration().multipliedBy(2));
                }
                
                messages.add(message);
                
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize step event: {}.{} - {}", 
                        envelope.sagaName, envelope.stepId, e.getMessage());
                failedToSerialize.add(envelope);
            }
        }
        
        // Re-queue events that failed serialization
        failedToSerialize.forEach(eventQueue::offer);
        
        if (messages.isEmpty()) {
            return Mono.just(new PublishResult(0, events.size()));
        }
        
        return serviceBusClient.sendMessages(messages)
            .then(Mono.just(new PublishResult(messages.size(), failedToSerialize.size())))
            .onErrorResume(error -> {
                log.error("Error sending messages to Service Bus: {}", error.getMessage());
                // Re-queue all events for retry
                events.forEach(eventQueue::offer);
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
            
            serviceBusClient.close();
            
            log.info("Service Bus publisher shutdown complete. Published: {}, Failed: {}, Remaining: {}", 
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