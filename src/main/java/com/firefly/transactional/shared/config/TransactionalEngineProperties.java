/*
 * Copyright 2025 Firefly Software Solutions Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.firefly.transactional.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * Generic configuration properties for the Transactional Engine.
 * These properties are shared between SAGA and TCC transaction patterns.
 * 
 * <p>
 * This class provides common configuration for:
 * <ul>
 *   <li>Persistence settings (Redis, in-memory)</li>
 *   <li>Observability and metrics</li>
 *   <li>Validation settings</li>
 *   <li>Default timeouts and concurrency limits</li>
 * </ul>
 * 
 * <p>
 * Example configuration:
 * <pre>
 * firefly.tx.default-timeout=PT5M
 * firefly.tx.max-concurrent-transactions=100
 * firefly.tx.observability.metrics-enabled=true
 * firefly.tx.persistence.enabled=true
 * firefly.tx.persistence.provider=redis
 * firefly.tx.persistence.redis.host=localhost
 * firefly.tx.persistence.redis.port=6379
 * firefly.tx.persistence.redis.database=0
 * firefly.tx.persistence.redis.key-prefix=firefly:tx:
 * </pre>
 * 
 * <p>
 * For backward compatibility, the old {@code firefly.saga.engine} properties
 * are still supported and will be mapped to these generic properties.
 */
@ConfigurationProperties(prefix = "firefly.tx")
public class TransactionalEngineProperties {
    
    /**
     * Default timeout for transaction execution.
     * This applies to both SAGA and TCC transactions unless overridden.
     */
    private Duration defaultTimeout = Duration.ofMinutes(5);
    
    /**
     * Maximum number of concurrent transactions that can be executed.
     * This applies to both SAGA and TCC transactions combined.
     */
    private int maxConcurrentTransactions = 100;
    
    /**
     * Observability configuration shared by all transaction patterns.
     */
    @NestedConfigurationProperty
    private ObservabilityProperties observability = new ObservabilityProperties();
    
    /**
     * Validation configuration shared by all transaction patterns.
     */
    @NestedConfigurationProperty
    private ValidationProperties validation = new ValidationProperties();

    /**
     * Persistence configuration shared by all transaction patterns.
     */
    @NestedConfigurationProperty
    private PersistenceProperties persistence = new PersistenceProperties();
    
    // Getters and setters
    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }
    
    public void setDefaultTimeout(Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }
    
    public int getMaxConcurrentTransactions() {
        return maxConcurrentTransactions;
    }
    
    public void setMaxConcurrentTransactions(int maxConcurrentTransactions) {
        this.maxConcurrentTransactions = maxConcurrentTransactions;
    }
    
    public ObservabilityProperties getObservability() {
        return observability;
    }
    
    public void setObservability(ObservabilityProperties observability) {
        this.observability = observability;
    }
    
    public ValidationProperties getValidation() {
        return validation;
    }

    public void setValidation(ValidationProperties validation) {
        this.validation = validation;
    }

    public PersistenceProperties getPersistence() {
        return persistence;
    }

    public void setPersistence(PersistenceProperties persistence) {
        this.persistence = persistence;
    }
    
    /**
     * Observability configuration properties shared by all transaction patterns.
     */
    public static class ObservabilityProperties {
        /**
         * Whether to enable metrics collection.
         */
        private boolean metricsEnabled = true;
        
        /**
         * Whether to enable detailed metrics (may impact performance).
         */
        private boolean detailedMetricsEnabled = false;
        
        /**
         * Whether to enable distributed tracing.
         */
        private boolean tracingEnabled = true;
        
        /**
         * Whether to enable event logging.
         */
        private boolean eventLoggingEnabled = true;
        
        /**
         * Metrics collection interval.
         */
        private Duration metricsInterval = Duration.ofSeconds(30);
        
        public boolean isMetricsEnabled() {
            return metricsEnabled;
        }
        
        public void setMetricsEnabled(boolean metricsEnabled) {
            this.metricsEnabled = metricsEnabled;
        }
        
        public boolean isDetailedMetricsEnabled() {
            return detailedMetricsEnabled;
        }
        
        public void setDetailedMetricsEnabled(boolean detailedMetricsEnabled) {
            this.detailedMetricsEnabled = detailedMetricsEnabled;
        }
        
        public boolean isTracingEnabled() {
            return tracingEnabled;
        }
        
        public void setTracingEnabled(boolean tracingEnabled) {
            this.tracingEnabled = tracingEnabled;
        }
        
        public boolean isEventLoggingEnabled() {
            return eventLoggingEnabled;
        }
        
        public void setEventLoggingEnabled(boolean eventLoggingEnabled) {
            this.eventLoggingEnabled = eventLoggingEnabled;
        }
        
        public Duration getMetricsInterval() {
            return metricsInterval;
        }
        
        public void setMetricsInterval(Duration metricsInterval) {
            this.metricsInterval = metricsInterval;
        }
    }
    
    /**
     * Validation configuration properties shared by all transaction patterns.
     */
    public static class ValidationProperties {
        /**
         * Whether to enable validation.
         */
        private boolean enabled = true;
        
        /**
         * Whether to fail fast on validation errors.
         */
        private boolean failFast = true;
        
        /**
         * Whether to enable strict validation mode.
         */
        private boolean strictMode = false;
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public boolean isFailFast() {
            return failFast;
        }
        
        public void setFailFast(boolean failFast) {
            this.failFast = failFast;
        }
        
        public boolean isStrictMode() {
            return strictMode;
        }
        
        public void setStrictMode(boolean strictMode) {
            this.strictMode = strictMode;
        }
    }
    
    /**
     * Persistence configuration properties shared by all transaction patterns.
     */
    public static class PersistenceProperties {
        /**
         * Whether to enable persistence for transaction state.
         */
        private boolean enabled = false;

        /**
         * Type of persistence provider to use.
         */
        private String provider = "in-memory";

        /**
         * Whether to enable automatic recovery of in-flight transactions on startup.
         */
        private boolean autoRecoveryEnabled = true;

        /**
         * Maximum age for transaction executions before they are considered stale.
         */
        private Duration maxTransactionAge = Duration.ofHours(24);

        /**
         * Interval for cleanup of completed transaction states.
         */
        private Duration cleanupInterval = Duration.ofHours(1);

        /**
         * How long to retain completed transaction states before cleanup.
         */
        private Duration retentionPeriod = Duration.ofDays(7);

        /**
         * Redis-specific configuration.
         */
        @NestedConfigurationProperty
        private RedisProperties redis = new RedisProperties();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public boolean isAutoRecoveryEnabled() {
            return autoRecoveryEnabled;
        }

        public void setAutoRecoveryEnabled(boolean autoRecoveryEnabled) {
            this.autoRecoveryEnabled = autoRecoveryEnabled;
        }

        public Duration getMaxTransactionAge() {
            return maxTransactionAge;
        }

        public void setMaxTransactionAge(Duration maxTransactionAge) {
            this.maxTransactionAge = maxTransactionAge;
        }

        public Duration getCleanupInterval() {
            return cleanupInterval;
        }

        public void setCleanupInterval(Duration cleanupInterval) {
            this.cleanupInterval = cleanupInterval;
        }

        public Duration getRetentionPeriod() {
            return retentionPeriod;
        }

        public void setRetentionPeriod(Duration retentionPeriod) {
            this.retentionPeriod = retentionPeriod;
        }

        public RedisProperties getRedis() {
            return redis;
        }

        public void setRedis(RedisProperties redis) {
            this.redis = redis;
        }
    }

    /**
     * Redis-specific persistence configuration properties.
     */
    public static class RedisProperties {
        /**
         * Redis server host.
         */
        private String host = "localhost";

        /**
         * Redis server port.
         */
        private int port = 6379;

        /**
         * Redis database index.
         */
        private int database = 0;

        /**
         * Redis password (optional).
         */
        private String password;

        /**
         * Connection timeout.
         */
        private Duration connectionTimeout = Duration.ofSeconds(5);

        /**
         * Command timeout.
         */
        private Duration commandTimeout = Duration.ofSeconds(10);

        /**
         * Key prefix for transaction state storage.
         * Different transaction patterns can use different prefixes.
         */
        private String keyPrefix = "firefly:tx:";

        /**
         * TTL for transaction state keys (optional, for automatic expiration).
         */
        private Duration keyTtl;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getDatabase() {
            return database;
        }

        public void setDatabase(int database) {
            this.database = database;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public Duration getConnectionTimeout() {
            return connectionTimeout;
        }

        public void setConnectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }

        public Duration getCommandTimeout() {
            return commandTimeout;
        }

        public void setCommandTimeout(Duration commandTimeout) {
            this.commandTimeout = commandTimeout;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public Duration getKeyTtl() {
            return keyTtl;
        }

        public void setKeyTtl(Duration keyTtl) {
            this.keyTtl = keyTtl;
        }
    }
}
