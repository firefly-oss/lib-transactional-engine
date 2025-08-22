package com.catalis.transactionalengine.inmemory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for In-Memory Transactional Engine implementation.
 * 
 * This provides configuration for in-memory implementations that store data locally
 * without external dependencies.
 */
@ConfigurationProperties(prefix = "transactional-engine.inmemory")
public class InMemoryTransactionalEngineProperties {

    private StorageProperties storage = new StorageProperties();
    private EventsProperties events = new EventsProperties();

    public StorageProperties getStorage() {
        return storage;
    }

    public void setStorage(StorageProperties storage) {
        this.storage = storage;
    }

    public EventsProperties getEvents() {
        return events;
    }

    public void setEvents(EventsProperties events) {
        this.events = events;
    }

    /**
     * Properties for in-memory storage configuration.
     */
    public static class StorageProperties {
        
        /**
         * Whether in-memory storage is enabled.
         */
        private boolean enabled = true;

        /**
         * Maximum number of saga contexts to keep in memory.
         */
        private int maxSagaContexts = 10000;

        /**
         * Time to live for completed sagas in memory before cleanup.
         */
        private Duration ttl = Duration.ofHours(24);

        /**
         * Interval for cleanup of expired saga contexts.
         */
        private Duration cleanupInterval = Duration.ofMinutes(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxSagaContexts() {
            return maxSagaContexts;
        }

        public void setMaxSagaContexts(int maxSagaContexts) {
            this.maxSagaContexts = maxSagaContexts;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public Duration getCleanupInterval() {
            return cleanupInterval;
        }

        public void setCleanupInterval(Duration cleanupInterval) {
            this.cleanupInterval = cleanupInterval;
        }
    }

    /**
     * Properties for in-memory event handling configuration.
     */
    public static class EventsProperties {
        
        /**
         * Whether enhanced logging events are enabled.
         */
        private boolean enabled = true;

        /**
         * Whether to log step details.
         */
        private boolean logStepDetails = true;

        /**
         * Whether to log timing information.
         */
        private boolean logTiming = true;

        /**
         * Whether to log compensation events.
         */
        private boolean logCompensation = true;

        /**
         * Maximum number of events to keep in memory for debugging.
         */
        private int maxEventsInMemory = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isLogStepDetails() {
            return logStepDetails;
        }

        public void setLogStepDetails(boolean logStepDetails) {
            this.logStepDetails = logStepDetails;
        }

        public boolean isLogTiming() {
            return logTiming;
        }

        public void setLogTiming(boolean logTiming) {
            this.logTiming = logTiming;
        }

        public boolean isLogCompensation() {
            return logCompensation;
        }

        public void setLogCompensation(boolean logCompensation) {
            this.logCompensation = logCompensation;
        }

        public int getMaxEventsInMemory() {
            return maxEventsInMemory;
        }

        public void setMaxEventsInMemory(int maxEventsInMemory) {
            this.maxEventsInMemory = maxEventsInMemory;
        }
    }
}