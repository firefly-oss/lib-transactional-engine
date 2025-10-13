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
 * SAGA-specific configuration properties.
 * These properties are specific to the SAGA transaction pattern and complement
 * the generic {@link TransactionalEngineProperties}.
 * 
 * <p>
 * Example configuration:
 * <pre>
 * firefly.tx.saga.compensation-policy=STRICT_SEQUENTIAL
 * firefly.tx.saga.auto-optimization-enabled=true
 * firefly.tx.saga.max-concurrent-sagas=50
 * firefly.tx.saga.context.execution-mode=AUTO
 * firefly.tx.saga.backpressure.strategy=batched
 * firefly.tx.saga.compensation.error-handler=robust
 * </pre>
 * 
 * <p>
 * For backward compatibility, the old {@code firefly.saga.engine} properties
 * are still supported and will be mapped to these properties.
 */
@ConfigurationProperties(prefix = "firefly.tx.saga")
public class SagaSpecificProperties {
    
    /**
     * Compensation policy for handling saga failures.
     */
    private SagaEngine.CompensationPolicy compensationPolicy = SagaEngine.CompensationPolicy.STRICT_SEQUENTIAL;
    
    /**
     * Whether to enable automatic optimization of saga contexts.
     */
    private boolean autoOptimizationEnabled = true;
    
    /**
     * Maximum number of concurrent sagas that can be executed.
     * This is separate from the generic maxConcurrentTransactions limit.
     */
    private int maxConcurrentSagas = 100;
    
    /**
     * Context creation configuration specific to SAGAs.
     */
    @NestedConfigurationProperty
    private ContextProperties context = new ContextProperties();
    
    /**
     * Backpressure configuration specific to SAGAs.
     */
    @NestedConfigurationProperty
    private BackpressureProperties backpressure = new BackpressureProperties();
    
    /**
     * Compensation configuration specific to SAGAs.
     */
    @NestedConfigurationProperty
    private CompensationProperties compensation = new CompensationProperties();
    
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
    
    /**
     * Context creation properties specific to SAGAs.
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
     * Backpressure configuration properties specific to SAGAs.
     */
    public static class BackpressureProperties {
        /**
         * Backpressure strategy name.
         */
        private String strategy = "adaptive";
        
        /**
         * Maximum concurrency for backpressure handling.
         */
        private int concurrency = 10;
        
        /**
         * Buffer size for backpressure handling.
         */
        private int bufferSize = 1000;
        
        /**
         * Timeout for backpressure operations.
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
        
        public int getBufferSize() {
            return bufferSize;
        }
        
        public void setBufferSize(int bufferSize) {
            this.bufferSize = bufferSize;
        }
        
        public Duration getTimeout() {
            return timeout;
        }
        
        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
    
    /**
     * Compensation configuration properties specific to SAGAs.
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
}
