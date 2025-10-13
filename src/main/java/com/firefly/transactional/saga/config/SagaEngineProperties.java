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

package com.firefly.transactional.saga.config;

import com.firefly.transactional.saga.core.SagaContextFactory;
import com.firefly.transactional.saga.engine.SagaEngine;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * Configuration properties for the Saga Engine.
 * These properties can be configured via application.properties or application.yml.
 * 
 * Example configuration:
 * <pre>
 * firefly.saga.engine.compensation-policy=STRICT_SEQUENTIAL
 * firefly.saga.engine.auto-optimization-enabled=true
 * firefly.saga.engine.context.execution-mode=AUTO
 * firefly.saga.engine.backpressure.strategy=batched
 * firefly.saga.engine.backpressure.concurrency=10
 * firefly.saga.engine.compensation.error-handler=robust
 * firefly.saga.engine.observability.metrics-enabled=true
 * firefly.saga.engine.persistence.enabled=true
 * firefly.saga.engine.persistence.provider=redis
 * firefly.saga.engine.persistence.redis.host=localhost
 * firefly.saga.engine.persistence.redis.port=6379
 * firefly.saga.engine.persistence.redis.database=0
 * firefly.saga.engine.persistence.redis.key-prefix=firefly:saga:
 * </pre>
 */
@ConfigurationProperties(prefix = "firefly.saga.engine")
public class SagaEngineProperties {
    
    /**
     * Compensation policy for handling saga failures.
     */
    private SagaEngine.CompensationPolicy compensationPolicy = SagaEngine.CompensationPolicy.STRICT_SEQUENTIAL;
    
    /**
     * Whether to enable automatic optimization of saga contexts.
     */
    private boolean autoOptimizationEnabled = true;
    
    /**
     * Default timeout for saga execution.
     */
    private Duration defaultTimeout = Duration.ofMinutes(5);
    
    /**
     * Maximum number of concurrent sagas that can be executed.
     */
    private int maxConcurrentSagas = 100;
    
    /**
     * Context creation configuration.
     */
    @NestedConfigurationProperty
    private ContextProperties context = new ContextProperties();
    
    /**
     * Backpressure configuration.
     */
    @NestedConfigurationProperty
    private BackpressureProperties backpressure = new BackpressureProperties();
    
    /**
     * Compensation configuration.
     */
    @NestedConfigurationProperty
    private CompensationProperties compensation = new CompensationProperties();
    
    /**
     * Observability configuration.
     */
    @NestedConfigurationProperty
    private ObservabilityProperties observability = new ObservabilityProperties();
    
    /**
     * Validation configuration.
     */
    @NestedConfigurationProperty
    private ValidationProperties validation = new ValidationProperties();

    /**
     * Persistence configuration.
     */
    @NestedConfigurationProperty
    private PersistenceProperties persistence = new PersistenceProperties();
    
    // Getters and setters
    public SagaEngine.CompensationPolicy getCompensationPolicy() {
        return compensationPolicy;
    }
    
    public void setCompensationPolicy(SagaEngine.CompensationPolicy compensationPolicy) {
        this.compensationPolicy = compensationPolicy;
    }
    
    public boolean isAutoOptimizationEnabled() {
        return autoOptimizationEnabled;
    }
    
    public void setAutoOptimizationEnabled(boolean autoOptimizationEnabled) {
        this.autoOptimizationEnabled = autoOptimizationEnabled;
    }
    
    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }
    
    public void setDefaultTimeout(Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }
    
    public int getMaxConcurrentSagas() {
        return maxConcurrentSagas;
    }
    
    public void setMaxConcurrentSagas(int maxConcurrentSagas) {
        this.maxConcurrentSagas = maxConcurrentSagas;
    }
    
    public ContextProperties getContext() {
        return context;
    }
    
    public void setContext(ContextProperties context) {
        this.context = context;
    }
    
    public BackpressureProperties getBackpressure() {
        return backpressure;
    }
    
    public void setBackpressure(BackpressureProperties backpressure) {
        this.backpressure = backpressure;
    }
    
    public CompensationProperties getCompensation() {
        return compensation;
    }
    
    public void setCompensation(CompensationProperties compensation) {
        this.compensation = compensation;
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
     * Context creation properties.
     */
    public static class ContextProperties {
        /**
         * Default execution mode for context creation.
         */
        private SagaContextFactory.ExecutionMode executionMode = SagaContextFactory.ExecutionMode.AUTO;
        
        /**
         * Whether to enable context optimization.
         */
        private boolean optimizationEnabled = true;
        
        public SagaContextFactory.ExecutionMode getExecutionMode() {
            return executionMode;
        }
        
        public void setExecutionMode(SagaContextFactory.ExecutionMode executionMode) {
            this.executionMode = executionMode;
        }
        
        public boolean isOptimizationEnabled() {
            return optimizationEnabled;
        }
        
        public void setOptimizationEnabled(boolean optimizationEnabled) {
            this.optimizationEnabled = optimizationEnabled;
        }
    }
    
    /**
     * Backpressure configuration properties.
     */
    public static class BackpressureProperties {
        /**
         * Default backpressure strategy name.
         */
        private String strategy = "batched";
        
        /**
         * Default concurrency for backpressure operations.
         */
        private int concurrency = 10;
        
        /**
         * Default batch size for batched operations.
         */
        private int batchSize = 50;
        
        /**
         * Default timeout for backpressure operations.
         */
        private Duration timeout = Duration.ofSeconds(30);
        
        public String getStrategy() {
            return strategy;
        }
        
        public void setStrategy(String strategy) {
            this.strategy = strategy;
        }
        
        public int getConcurrency() {
            return concurrency;
        }
        
        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }
        
        public int getBatchSize() {
            return batchSize;
        }
        
        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
        
        public Duration getTimeout() {
            return timeout;
        }
        
        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
    
    /**
     * Compensation configuration properties.
     */
    public static class CompensationProperties {
        /**
         * Error handler strategy name.
         */
        private String errorHandler = "log-and-continue";
        
        /**
         * Maximum retry attempts for compensation operations.
         */
        private int maxRetries = 3;
        
        /**
         * Retry delay for compensation operations.
         */
        private Duration retryDelay = Duration.ofMillis(100);
        
        /**
         * Whether to fail fast on critical compensation errors.
         */
        private boolean failFastOnCriticalErrors = false;
        
        public String getErrorHandler() {
            return errorHandler;
        }
        
        public void setErrorHandler(String errorHandler) {
            this.errorHandler = errorHandler;
        }
        
        public int getMaxRetries() {
            return maxRetries;
        }
        
        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
        
        public Duration getRetryDelay() {
            return retryDelay;
        }
        
        public void setRetryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
        }
        
        public boolean isFailFastOnCriticalErrors() {
            return failFastOnCriticalErrors;
        }
        
        public void setFailFastOnCriticalErrors(boolean failFastOnCriticalErrors) {
            this.failFastOnCriticalErrors = failFastOnCriticalErrors;
        }
    }
    
    /**
     * Observability configuration properties.
     */
    public static class ObservabilityProperties {
        /**
         * Whether to enable metrics collection.
         */
        private boolean metricsEnabled = true;
        
        /**
         * Whether to enable tracing.
         */
        private boolean tracingEnabled = true;
        
        /**
         * Whether to enable detailed logging.
         */
        private boolean detailedLoggingEnabled = false;
        
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
        
        public boolean isTracingEnabled() {
            return tracingEnabled;
        }
        
        public void setTracingEnabled(boolean tracingEnabled) {
            this.tracingEnabled = tracingEnabled;
        }
        
        public boolean isDetailedLoggingEnabled() {
            return detailedLoggingEnabled;
        }
        
        public void setDetailedLoggingEnabled(boolean detailedLoggingEnabled) {
            this.detailedLoggingEnabled = detailedLoggingEnabled;
        }
        
        public Duration getMetricsInterval() {
            return metricsInterval;
        }
        
        public void setMetricsInterval(Duration metricsInterval) {
            this.metricsInterval = metricsInterval;
        }
    }
    
    /**
     * Validation configuration properties.
     */
    public static class ValidationProperties {
        /**
         * Whether to enable runtime validation.
         */
        private boolean enabled = true;
        
        /**
         * Whether to validate saga definitions at startup.
         */
        private boolean validateAtStartup = true;
        
        /**
         * Whether to validate step inputs at runtime.
         */
        private boolean validateInputs = true;
        
        /**
         * Whether to fail fast on validation errors.
         */
        private boolean failFast = true;
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public boolean isValidateAtStartup() {
            return validateAtStartup;
        }
        
        public void setValidateAtStartup(boolean validateAtStartup) {
            this.validateAtStartup = validateAtStartup;
        }
        
        public boolean isValidateInputs() {
            return validateInputs;
        }
        
        public void setValidateInputs(boolean validateInputs) {
            this.validateInputs = validateInputs;
        }
        
        public boolean isFailFast() {
            return failFast;
        }
        
        public void setFailFast(boolean failFast) {
            this.failFast = failFast;
        }
    }

    /**
     * Persistence configuration properties.
     */
    public static class PersistenceProperties {
        /**
         * Whether to enable persistence for saga state.
         */
        private boolean enabled = false;

        /**
         * Type of persistence provider to use.
         */
        private String provider = "in-memory";

        /**
         * Whether to enable automatic recovery of in-flight sagas on startup.
         */
        private boolean autoRecoveryEnabled = true;

        /**
         * Maximum age for saga executions before they are considered stale.
         */
        private Duration maxSagaAge = Duration.ofHours(24);

        /**
         * Interval for cleanup of completed saga states.
         */
        private Duration cleanupInterval = Duration.ofHours(1);

        /**
         * How long to retain completed saga states before cleanup.
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

        public Duration getMaxSagaAge() {
            return maxSagaAge;
        }

        public void setMaxSagaAge(Duration maxSagaAge) {
            this.maxSagaAge = maxSagaAge;
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
         * Key prefix for saga state storage.
         */
        private String keyPrefix = "firefly:saga:";

        /**
         * TTL for saga state keys (optional, for automatic expiration).
         */
        private Duration keyTtl;

        /**
         * Connection pool configuration.
         */
        @NestedConfigurationProperty
        private PoolProperties pool = new PoolProperties();

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

        public PoolProperties getPool() {
            return pool;
        }

        public void setPool(PoolProperties pool) {
            this.pool = pool;
        }
    }

    /**
     * Redis connection pool configuration properties.
     */
    public static class PoolProperties {
        /**
         * Maximum number of connections in the pool.
         */
        private int maxActive = 8;

        /**
         * Maximum number of idle connections in the pool.
         */
        private int maxIdle = 8;

        /**
         * Minimum number of idle connections in the pool.
         */
        private int minIdle = 0;

        /**
         * Maximum time to wait for a connection from the pool.
         */
        private Duration maxWait = Duration.ofSeconds(5);

        /**
         * Whether to validate connections when borrowing from the pool.
         */
        private boolean testOnBorrow = true;

        /**
         * Whether to validate connections when returning to the pool.
         */
        private boolean testOnReturn = false;

        /**
         * Whether to validate idle connections in the pool.
         */
        private boolean testWhileIdle = true;

        public int getMaxActive() {
            return maxActive;
        }

        public void setMaxActive(int maxActive) {
            this.maxActive = maxActive;
        }

        public int getMaxIdle() {
            return maxIdle;
        }

        public void setMaxIdle(int maxIdle) {
            this.maxIdle = maxIdle;
        }

        public int getMinIdle() {
            return minIdle;
        }

        public void setMinIdle(int minIdle) {
            this.minIdle = minIdle;
        }

        public Duration getMaxWait() {
            return maxWait;
        }

        public void setMaxWait(Duration maxWait) {
            this.maxWait = maxWait;
        }

        public boolean isTestOnBorrow() {
            return testOnBorrow;
        }

        public void setTestOnBorrow(boolean testOnBorrow) {
            this.testOnBorrow = testOnBorrow;
        }

        public boolean isTestOnReturn() {
            return testOnReturn;
        }

        public void setTestOnReturn(boolean testOnReturn) {
            this.testOnReturn = testOnReturn;
        }

        public boolean isTestWhileIdle() {
            return testWhileIdle;
        }

        public void setTestWhileIdle(boolean testWhileIdle) {
            this.testWhileIdle = testWhileIdle;
        }
    }
}
