package com.catalis.transactionalengine.inmemory;

import com.catalis.transactionalengine.observability.SagaEvents;
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
        log.info("Creating In-Memory SagaEvents implementation for Transactional Engine");
        log.info("Configuration: logStepDetails={}, logTiming={}, logCompensation={}, maxEventsInMemory={}",
            properties.getEvents().isLogStepDetails(),
            properties.getEvents().isLogTiming(), 
            properties.getEvents().isLogCompensation(),
            properties.getEvents().getMaxEventsInMemory());
        
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
        log.info("Creating In-Memory Storage Service for Transactional Engine");
        log.info("Storage configuration: maxSagaContexts={}, ttl={}, cleanupInterval={}",
            properties.getStorage().getMaxSagaContexts(),
            properties.getStorage().getTtl(),
            properties.getStorage().getCleanupInterval());
        
        return new InMemoryStorageService(properties.getStorage());
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
            log.info("In-Memory Storage Service initialized (placeholder for future saga state management)");
        }
        
        public InMemoryTransactionalEngineProperties.StorageProperties getConfig() {
            return config;
        }
    }
}