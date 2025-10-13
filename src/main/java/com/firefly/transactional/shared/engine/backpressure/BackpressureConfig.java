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

package com.firefly.transactional.shared.engine.backpressure;

import java.time.Duration;

/**
 * Configuration for backpressure strategies.
 * Provides settings for controlling flow control, batching, and timeouts.
 */
public record BackpressureConfig(
    int concurrency,
    int batchSize,
    Duration timeout,
    boolean enableRetry,
    int maxRetries,
    Duration retryDelay
) {
    
    /**
     * Creates a default configuration suitable for most use cases.
     */
    public static BackpressureConfig defaultConfig() {
        return new BackpressureConfig(
            10,                          // concurrency
            50,                          // batchSize
            Duration.ofSeconds(30),      // timeout
            true,                        // enableRetry
            3,                           // maxRetries
            Duration.ofMillis(100)       // retryDelay
        );
    }
    
    /**
     * Creates a high-throughput configuration for scenarios requiring maximum performance.
     */
    public static BackpressureConfig highThroughput() {
        return new BackpressureConfig(
            50,                          // concurrency
            100,                         // batchSize
            Duration.ofMinutes(1),       // timeout
            true,                        // enableRetry
            2,                           // maxRetries
            Duration.ofMillis(50)        // retryDelay
        );
    }
    
    /**
     * Creates a low-latency configuration for scenarios requiring fast response times.
     */
    public static BackpressureConfig lowLatency() {
        return new BackpressureConfig(
            5,                           // concurrency
            10,                          // batchSize
            Duration.ofSeconds(10),      // timeout
            false,                       // enableRetry
            0,                           // maxRetries
            Duration.ZERO                // retryDelay
        );
    }
    
    /**
     * Creates a conservative configuration for resource-constrained environments.
     */
    public static BackpressureConfig conservative() {
        return new BackpressureConfig(
            2,                           // concurrency
            20,                          // batchSize
            Duration.ofMinutes(5),       // timeout
            true,                        // enableRetry
            5,                           // maxRetries
            Duration.ofSeconds(1)        // retryDelay
        );
    }
    
    /**
     * Builder for creating custom configurations.
     */
    public static class Builder {
        private int concurrency = 10;
        private int batchSize = 50;
        private Duration timeout = Duration.ofSeconds(30);
        private boolean enableRetry = true;
        private int maxRetries = 3;
        private Duration retryDelay = Duration.ofMillis(100);
        
        public Builder concurrency(int concurrency) {
            this.concurrency = concurrency;
            return this;
        }
        
        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }
        
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }
        
        public Builder enableRetry(boolean enableRetry) {
            this.enableRetry = enableRetry;
            return this;
        }
        
        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }
        
        public Builder retryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
            return this;
        }
        
        public BackpressureConfig build() {
            return new BackpressureConfig(concurrency, batchSize, timeout, enableRetry, maxRetries, retryDelay);
        }
    }
    
    /**
     * Creates a new builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Validates the configuration parameters.
     */
    public void validate() {
        if (concurrency <= 0) {
            throw new IllegalArgumentException("Concurrency must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be positive");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries cannot be negative");
        }
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("Retry delay cannot be negative");
        }
    }
}
