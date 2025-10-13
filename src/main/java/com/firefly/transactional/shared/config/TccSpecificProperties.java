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
 * TCC-specific configuration properties.
 * These properties are specific to the TCC (Try-Confirm-Cancel) transaction pattern
 * and complement the generic {@link TransactionalEngineProperties}.
 * 
 * <p>
 * Example configuration:
 * <pre>
 * firefly.tx.tcc.default-timeout=PT30S
 * firefly.tx.tcc.retry-enabled=true
 * firefly.tx.tcc.max-retries=3
 * firefly.tx.tcc.backoff-ms=1000
 * firefly.tx.tcc.max-concurrent-tccs=50
 * firefly.tx.tcc.participant.timeout=PT10S
 * firefly.tx.tcc.participant.retry-enabled=true
 * </pre>
 */
@ConfigurationProperties(prefix = "firefly.tx.tcc")
public class TccSpecificProperties {
    
    /**
     * Default timeout for TCC transaction execution.
     * This overrides the generic defaultTimeout for TCC transactions.
     */
    private Duration defaultTimeout = Duration.ofSeconds(30);
    
    /**
     * Whether to enable automatic retry for failed TCC operations.
     */
    private boolean retryEnabled = true;
    
    /**
     * Maximum number of retry attempts for failed TCC operations.
     */
    private int maxRetries = 3;
    
    /**
     * Backoff delay in milliseconds between retry attempts.
     */
    private long backoffMs = 1000;
    
    /**
     * Maximum number of concurrent TCC transactions that can be executed.
     * This is separate from the generic maxConcurrentTransactions limit.
     */
    private int maxConcurrentTccs = 100;
    
    /**
     * Whether to enable strict ordering of participant execution.
     * When true, participants are executed in the order they are defined.
     */
    private boolean strictOrdering = true;
    
    /**
     * Whether to enable parallel execution of participants in the confirm/cancel phases.
     * When false, participants are executed sequentially.
     */
    private boolean parallelExecution = false;
    
    /**
     * Participant-specific configuration.
     */
    @NestedConfigurationProperty
    private ParticipantProperties participant = new ParticipantProperties();
    
    /**
     * Recovery configuration for TCC transactions.
     */
    @NestedConfigurationProperty
    private RecoveryProperties recovery = new RecoveryProperties();
    
    // Getters and setters
    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }
    
    public void setDefaultTimeout(Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }
    
    public boolean isRetryEnabled() {
        return retryEnabled;
    }
    
    public void setRetryEnabled(boolean retryEnabled) {
        this.retryEnabled = retryEnabled;
    }
    
    public int getMaxRetries() {
        return maxRetries;
    }
    
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
    
    public long getBackoffMs() {
        return backoffMs;
    }
    
    public void setBackoffMs(long backoffMs) {
        this.backoffMs = backoffMs;
    }
    
    public int getMaxConcurrentTccs() {
        return maxConcurrentTccs;
    }
    
    public void setMaxConcurrentTccs(int maxConcurrentTccs) {
        this.maxConcurrentTccs = maxConcurrentTccs;
    }
    
    public boolean isStrictOrdering() {
        return strictOrdering;
    }
    
    public void setStrictOrdering(boolean strictOrdering) {
        this.strictOrdering = strictOrdering;
    }
    
    public boolean isParallelExecution() {
        return parallelExecution;
    }
    
    public void setParallelExecution(boolean parallelExecution) {
        this.parallelExecution = parallelExecution;
    }
    
    public ParticipantProperties getParticipant() {
        return participant;
    }
    
    public void setParticipant(ParticipantProperties participant) {
        this.participant = participant;
    }
    
    public RecoveryProperties getRecovery() {
        return recovery;
    }
    
    public void setRecovery(RecoveryProperties recovery) {
        this.recovery = recovery;
    }
    
    /**
     * Participant-specific configuration properties.
     */
    public static class ParticipantProperties {
        /**
         * Default timeout for individual participant operations.
         */
        private Duration timeout = Duration.ofSeconds(10);
        
        /**
         * Whether to enable retry for individual participant operations.
         */
        private boolean retryEnabled = true;
        
        /**
         * Maximum retry attempts for individual participant operations.
         */
        private int maxRetries = 2;
        
        /**
         * Backoff delay for participant retry attempts.
         */
        private Duration retryBackoff = Duration.ofMillis(500);
        
        /**
         * Whether to fail fast when a participant fails.
         */
        private boolean failFast = true;
        
        public Duration getTimeout() {
            return timeout;
        }
        
        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
        
        public boolean isRetryEnabled() {
            return retryEnabled;
        }
        
        public void setRetryEnabled(boolean retryEnabled) {
            this.retryEnabled = retryEnabled;
        }
        
        public int getMaxRetries() {
            return maxRetries;
        }
        
        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
        
        public Duration getRetryBackoff() {
            return retryBackoff;
        }
        
        public void setRetryBackoff(Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
        }
        
        public boolean isFailFast() {
            return failFast;
        }
        
        public void setFailFast(boolean failFast) {
            this.failFast = failFast;
        }
    }
    
    /**
     * Recovery configuration properties for TCC transactions.
     */
    public static class RecoveryProperties {
        /**
         * Whether to enable automatic recovery of incomplete TCC transactions.
         */
        private boolean enabled = true;
        
        /**
         * Interval for scanning and recovering incomplete transactions.
         */
        private Duration scanInterval = Duration.ofMinutes(5);
        
        /**
         * Maximum age for transactions before they are considered for recovery.
         */
        private Duration maxAge = Duration.ofMinutes(30);
        
        /**
         * Maximum number of recovery attempts for a single transaction.
         */
        private int maxAttempts = 5;
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public Duration getScanInterval() {
            return scanInterval;
        }
        
        public void setScanInterval(Duration scanInterval) {
            this.scanInterval = scanInterval;
        }
        
        public Duration getMaxAge() {
            return maxAge;
        }
        
        public void setMaxAge(Duration maxAge) {
            this.maxAge = maxAge;
        }
        
        public int getMaxAttempts() {
            return maxAttempts;
        }
        
        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }
}
