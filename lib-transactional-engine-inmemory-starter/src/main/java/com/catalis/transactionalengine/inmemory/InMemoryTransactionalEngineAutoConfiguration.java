package com.catalis.transactionalengine.inmemory;

import com.catalis.transactionalengine.observability.SagaEvents;
import com.catalis.transactionalengine.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for In-Memory Transactional Engine implementation.
 * 
 * This configuration provides a simple, in-memory implementation suitable for:
 * - Development and testing environments
 * - Small-scale deployments without external dependencies
 * - Getting started with the Transactional Engine quickly
 * 
 * Features provided:
 * - Enhanced logging with emojis and detailed event information
 * - In-memory event history for debugging
 * - Configurable logging levels and storage limits
 * - No external dependencies (vanilla Spring implementation)
 * 
 * This starter automatically configures when:
 * - No other SagaEvents bean is present
 * - The configuration property is enabled (default: true)
 * - No cloud-specific starters (AWS, Azure) take precedence
 */
@AutoConfiguration
@EnableConfigurationProperties(InMemoryTransactionalEngineProperties.class)
public class InMemoryTransactionalEngineAutoConfiguration {
    
    private static final Logger log = LoggerFactory.getLogger(InMemoryTransactionalEngineAutoConfiguration.class);

    /**
     * Creates an in-memory SagaEvents implementation with enhanced logging.
     * 
     * This bean is created when:
     * - No other SagaEvents bean is present (@ConditionalOnMissingBean)
     * - The in-memory events are enabled in configuration (default: true)
     * 
     * The implementation provides:
     * - Rich logging with emoji icons for better visibility
     * - Configurable logging levels (step details, timing, compensation)
     * - In-memory event storage for debugging
     * - Thread-safe event handling
     * 
     * @param properties Configuration properties for the in-memory implementation
     * @return InMemorySagaEvents instance configured according to properties
     */
    @Bean
    @ConditionalOnMissingBean(SagaEvents.class)
    @ConditionalOnProperty(prefix = "transactional-engine.inmemory.events", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SagaEvents inMemorySagaEvents(InMemoryTransactionalEngineProperties properties) {
        log.info(JsonUtils.json(
                "event", "creating_inmemory_saga_events",
                "component", "transactional_engine",
                "implementation", "InMemorySagaEvents"
        ));
        log.info(JsonUtils.json(
                "event", "inmemory_saga_events_configuration",
                "log_step_details", Boolean.toString(properties.getEvents().isLogStepDetails()),
                "log_timing", Boolean.toString(properties.getEvents().isLogTiming()),
                "log_compensation", Boolean.toString(properties.getEvents().isLogCompensation()),
                "max_events_in_memory", Integer.toString(properties.getEvents().getMaxEventsInMemory())
        ));
        
        return new InMemorySagaEvents(properties.getEvents());
    }

    /**
     * Creates an in-memory storage service for managing saga state (future enhancement).
     * 
     * This bean could be used for:
     * - Storing saga contexts in memory
     * - Managing saga lifecycle
     * - Cleanup of expired sagas
     * 
     * Currently, the core engine handles saga state management,
     * but this provides a hook for future in-memory storage enhancements.
     * 
     * @param properties Configuration properties for storage
     * @return InMemoryStorageService instance (placeholder for future use)
     */
    @Bean
    @ConditionalOnProperty(prefix = "transactional-engine.inmemory.storage", name = "enabled", havingValue = "true", matchIfMissing = true)
    public InMemoryStorageService inMemoryStorageService(InMemoryTransactionalEngineProperties properties) {
        log.info(JsonUtils.json(
                "event", "creating_inmemory_storage_service",
                "component", "transactional_engine",
                "implementation", "InMemoryStorageService"
        ));
        log.info(JsonUtils.json(
                "event", "inmemory_storage_configuration",
                "max_saga_contexts", Integer.toString(properties.getStorage().getMaxSagaContexts()),
                "ttl", properties.getStorage().getTtl().toString(),
                "cleanup_interval", properties.getStorage().getCleanupInterval().toString()
        ));
        
        return new InMemoryStorageService(properties.getStorage());
    }
    
    /**
     * Creates an in-memory StepEventPublisher for development and testing.
     * 
     * This bean provides:
     * - Rich logging with emojis for better visibility during development
     * - In-memory event storage for debugging and testing
     * - Event history accessible for verification in tests
     * - Thread-safe operations with configurable storage limits
     * 
     * Perfect for development environments and testing scenarios where
     * external message queues aren't needed or available.
     * 
     * @param properties Configuration properties for the in-memory implementation
     * @return InMemoryStepEventPublisher instance configured according to properties
     */
    @Bean("inMemoryStepEventPublisher")
    @ConditionalOnProperty(prefix = "transactional-engine.inmemory.events", name = "step-publisher-enabled", havingValue = "true", matchIfMissing = true)
    public InMemoryStepEventPublisher inMemoryStepEventPublisher(InMemoryTransactionalEngineProperties properties) {
        log.info(JsonUtils.json(
                "event", "creating_inmemory_step_event_publisher",
                "component", "transactional_engine",
                "implementation", "InMemoryStepEventPublisher"
        ));
        log.info(JsonUtils.json(
                "event", "inmemory_step_event_publisher_configuration",
                "log_step_details", Boolean.toString(properties.getEvents().isLogStepDetails()),
                "max_events_in_memory", Integer.toString(properties.getEvents().getMaxEventsInMemory())
        ));
        
        return new InMemoryStepEventPublisher(properties.getEvents());
    }

    /**
     * Placeholder storage service for future enhancements.
     * Currently provides configuration validation and logging.
     */
    public static class InMemoryStorageService {
        private static final Logger log = LoggerFactory.getLogger(InMemoryStorageService.class);
        private final InMemoryTransactionalEngineProperties.StorageProperties config;
        
        public InMemoryStorageService(InMemoryTransactionalEngineProperties.StorageProperties config) {
            this.config = config;
            log.info(JsonUtils.json(
                    "event", "inmemory_storage_service_initialized",
                    "component", "transactional_engine",
                    "status", "initialized",
                    "purpose", "placeholder_for_future_saga_state_management"
            ));
        }
        
        public InMemoryTransactionalEngineProperties.StorageProperties getConfig() {
            return config;
        }
    }
}