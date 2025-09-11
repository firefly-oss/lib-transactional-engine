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

package com.firefly.transactionalengine.config;

import com.firefly.transactionalengine.core.SagaContextFactory;
import com.firefly.transactionalengine.engine.SagaEngine;
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
}
