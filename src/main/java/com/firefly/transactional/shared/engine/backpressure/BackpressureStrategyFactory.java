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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating and managing backpressure strategies.
 * Provides pre-configured strategies for common use cases and allows
 * registration of custom strategies.
 */
public class BackpressureStrategyFactory {
    
    private static final Map<String, BackpressureStrategy> strategies = new ConcurrentHashMap<>();
    
    static {
        // Register default strategies
        registerStrategy("batched", new BatchedBackpressureStrategy());
        registerStrategy("adaptive", new AdaptiveBackpressureStrategy());
        registerStrategy("circuit-breaker", new CircuitBreakerBackpressureStrategy());
        registerStrategy("circuit-breaker-aggressive", new CircuitBreakerBackpressureStrategy(3, Duration.ofSeconds(30), 2));
        registerStrategy("circuit-breaker-conservative", new CircuitBreakerBackpressureStrategy(10, Duration.ofMinutes(5), 5));
    }
    
    /**
     * Gets a backpressure strategy by name.
     * 
     * @param strategyName the name of the strategy
     * @return the strategy instance
     * @throws IllegalArgumentException if strategy is not found
     */
    public static BackpressureStrategy getStrategy(String strategyName) {
        BackpressureStrategy strategy = strategies.get(strategyName);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown backpressure strategy: " + strategyName);
        }
        return strategy;
    }
    
    /**
     * Registers a custom backpressure strategy.
     * 
     * @param name the name to register the strategy under
     * @param strategy the strategy implementation
     */
    public static void registerStrategy(String name, BackpressureStrategy strategy) {
        strategies.put(name, strategy);
    }
    
    /**
     * Gets the default batched strategy with standard configuration.
     */
    public static BackpressureStrategy defaultBatched() {
        return getStrategy("batched");
    }
    
    /**
     * Gets the adaptive strategy that automatically adjusts parameters.
     */
    public static BackpressureStrategy adaptive() {
        return getStrategy("adaptive");
    }
    
    /**
     * Gets the circuit breaker strategy with default settings.
     */
    public static BackpressureStrategy circuitBreaker() {
        return getStrategy("circuit-breaker");
    }
    
    /**
     * Gets an aggressive circuit breaker strategy for sensitive systems.
     */
    public static BackpressureStrategy aggressiveCircuitBreaker() {
        return getStrategy("circuit-breaker-aggressive");
    }
    
    /**
     * Gets a conservative circuit breaker strategy for robust systems.
     */
    public static BackpressureStrategy conservativeCircuitBreaker() {
        return getStrategy("circuit-breaker-conservative");
    }
    
    /**
     * Creates a custom circuit breaker strategy with specific parameters.
     * 
     * @param failureThreshold number of failures before opening circuit
     * @param recoveryTimeout time to wait before attempting recovery
     * @param halfOpenMaxCalls maximum calls to allow in half-open state
     * @return configured circuit breaker strategy
     */
    public static BackpressureStrategy customCircuitBreaker(int failureThreshold, 
                                                           Duration recoveryTimeout, 
                                                           int halfOpenMaxCalls) {
        return new CircuitBreakerBackpressureStrategy(failureThreshold, recoveryTimeout, halfOpenMaxCalls);
    }
    
    /**
     * Gets all available strategy names.
     * 
     * @return array of strategy names
     */
    public static String[] getAvailableStrategies() {
        return strategies.keySet().toArray(new String[0]);
    }
    
    /**
     * Checks if a strategy is registered.
     * 
     * @param strategyName the strategy name to check
     * @return true if the strategy is registered
     */
    public static boolean isStrategyRegistered(String strategyName) {
        return strategies.containsKey(strategyName);
    }
    
    /**
     * Removes a registered strategy.
     * 
     * @param strategyName the name of the strategy to remove
     * @return the removed strategy, or null if not found
     */
    public static BackpressureStrategy removeStrategy(String strategyName) {
        return strategies.remove(strategyName);
    }
    
    /**
     * Clears all registered strategies and resets to defaults.
     */
    public static void resetToDefaults() {
        strategies.clear();
        
        // Re-register default strategies
        registerStrategy("batched", new BatchedBackpressureStrategy());
        registerStrategy("adaptive", new AdaptiveBackpressureStrategy());
        registerStrategy("circuit-breaker", new CircuitBreakerBackpressureStrategy());
        registerStrategy("circuit-breaker-aggressive", new CircuitBreakerBackpressureStrategy(3, Duration.ofSeconds(30), 2));
        registerStrategy("circuit-breaker-conservative", new CircuitBreakerBackpressureStrategy(10, Duration.ofMinutes(5), 5));
    }
}
