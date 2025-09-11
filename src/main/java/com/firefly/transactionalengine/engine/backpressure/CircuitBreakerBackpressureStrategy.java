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

package com.firefly.transactionalengine.engine.backpressure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker backpressure strategy that prevents cascading failures
 * by failing fast when downstream systems are unhealthy.
 * 
 * <p>Circuit breaker states:
 * <ul>
 *   <li><b>CLOSED:</b> Normal operation, requests pass through</li>
 *   <li><b>OPEN:</b> Circuit is open, requests fail immediately</li>
 *   <li><b>HALF_OPEN:</b> Testing if downstream has recovered</li>
 * </ul>
 */
public class CircuitBreakerBackpressureStrategy implements BackpressureStrategy {
    
    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerBackpressureStrategy.class);
    
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicReference<Instant> lastFailureTime = new AtomicReference<>();
    private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
    
    // Circuit breaker configuration
    private final int failureThreshold;
    private final Duration recoveryTimeout;
    private final int halfOpenMaxCalls;
    
    /**
     * Creates a circuit breaker strategy with default settings.
     */
    public CircuitBreakerBackpressureStrategy() {
        this(5, Duration.ofMinutes(1), 3);
    }
    
    /**
     * Creates a circuit breaker strategy with custom settings.
     * 
     * @param failureThreshold number of failures before opening circuit
     * @param recoveryTimeout time to wait before attempting recovery
     * @param halfOpenMaxCalls maximum calls to allow in half-open state
     */
    public CircuitBreakerBackpressureStrategy(int failureThreshold, Duration recoveryTimeout, int halfOpenMaxCalls) {
        this.failureThreshold = failureThreshold;
        this.recoveryTimeout = recoveryTimeout;
        this.halfOpenMaxCalls = halfOpenMaxCalls;
    }
    
    @Override
    public <T, R> Flux<R> applyBackpressure(List<T> items, 
                                           ItemProcessor<T, R> processor, 
                                           BackpressureConfig config) {
        config.validate();
        
        if (items.isEmpty()) {
            return Flux.empty();
        }
        
        log.debug("Applying circuit breaker backpressure to {} items (state: {})", 
                 items.size(), state.get());
        
        return Flux.fromIterable(items)
                .flatMap(item -> processWithCircuitBreaker(item, processor, config), config.concurrency())
                .timeout(config.timeout())
                .doOnSubscribe(s -> log.debug("Starting circuit breaker processing of {} items", items.size()))
                .doOnComplete(() -> log.debug("Completed circuit breaker processing (final state: {})", state.get()))
                .doOnError(error -> log.error("Circuit breaker processing failed: {}", error.getMessage()));
    }
    
    /**
     * Processes an item with circuit breaker protection.
     */
    private <T, R> Mono<R> processWithCircuitBreaker(T item, 
                                                    ItemProcessor<T, R> processor, 
                                                    BackpressureConfig config) {
        CircuitState currentState = updateCircuitState();
        
        switch (currentState) {
            case OPEN:
                return Mono.error(new CircuitBreakerException("Circuit breaker is OPEN"));
                
            case HALF_OPEN:
                if (successCount.get() >= halfOpenMaxCalls) {
                    return Mono.error(new CircuitBreakerException("Circuit breaker HALF_OPEN call limit exceeded"));
                }
                break;
                
            case CLOSED:
            default:
                // Normal processing
                break;
        }
        
        return processor.process(item)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(result -> handleSuccess())
                .doOnError(error -> handleFailure(error));
    }
    
    /**
     * Updates the circuit state based on current conditions.
     */
    private CircuitState updateCircuitState() {
        CircuitState currentState = state.get();
        
        switch (currentState) {
            case CLOSED:
                if (failureCount.get() >= failureThreshold) {
                    if (state.compareAndSet(CircuitState.CLOSED, CircuitState.OPEN)) {
                        lastFailureTime.set(Instant.now());
                        log.warn("Circuit breaker opened due to {} failures (threshold: {})", 
                                failureCount.get(), failureThreshold);
                    }
                    return CircuitState.OPEN;
                }
                break;
                
            case OPEN:
                Instant lastFailure = lastFailureTime.get();
                if (lastFailure != null && Instant.now().isAfter(lastFailure.plus(recoveryTimeout))) {
                    if (state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN)) {
                        successCount.set(0);
                        log.info("Circuit breaker transitioning to HALF_OPEN for recovery testing");
                    }
                    return CircuitState.HALF_OPEN;
                }
                break;
                
            case HALF_OPEN:
                if (successCount.get() >= halfOpenMaxCalls) {
                    if (state.compareAndSet(CircuitState.HALF_OPEN, CircuitState.CLOSED)) {
                        failureCount.set(0);
                        log.info("Circuit breaker closed after successful recovery");
                    }
                    return CircuitState.CLOSED;
                }
                break;
        }
        
        return currentState;
    }
    
    /**
     * Handles successful operation.
     */
    private void handleSuccess() {
        CircuitState currentState = state.get();
        
        if (currentState == CircuitState.HALF_OPEN) {
            int successes = successCount.incrementAndGet();
            log.debug("Circuit breaker HALF_OPEN success count: {}/{}", successes, halfOpenMaxCalls);
        } else if (currentState == CircuitState.CLOSED) {
            // Reset failure count on success in closed state
            if (failureCount.get() > 0) {
                failureCount.set(0);
                log.debug("Circuit breaker failure count reset after success");
            }
        }
    }
    
    /**
     * Handles failed operation.
     */
    private void handleFailure(Throwable error) {
        CircuitState currentState = state.get();
        int failures = failureCount.incrementAndGet();
        lastFailureTime.set(Instant.now());
        
        log.warn("Circuit breaker recorded failure #{} in state {}: {}", 
                failures, currentState, error.getMessage());
        
        if (currentState == CircuitState.HALF_OPEN) {
            // Failure in half-open state - go back to open
            if (state.compareAndSet(CircuitState.HALF_OPEN, CircuitState.OPEN)) {
                log.warn("Circuit breaker reopened due to failure during recovery testing");
            }
        }
    }
    
    @Override
    public String getStrategyName() {
        return "CircuitBreakerBackpressure";
    }
    
    /**
     * Gets current circuit breaker metrics.
     */
    public CircuitBreakerMetrics getMetrics() {
        return new CircuitBreakerMetrics(
            state.get(),
            failureCount.get(),
            successCount.get(),
            lastFailureTime.get()
        );
    }
    
    /**
     * Manually resets the circuit breaker to closed state.
     */
    public void reset() {
        state.set(CircuitState.CLOSED);
        failureCount.set(0);
        successCount.set(0);
        lastFailureTime.set(null);
        log.info("Circuit breaker manually reset to CLOSED state");
    }
    
    /**
     * Circuit breaker states.
     */
    public enum CircuitState {
        CLOSED,    // Normal operation
        OPEN,      // Circuit is open, failing fast
        HALF_OPEN  // Testing recovery
    }
    
    /**
     * Circuit breaker metrics.
     */
    public record CircuitBreakerMetrics(
        CircuitState state,
        int failureCount,
        int successCount,
        Instant lastFailureTime
    ) {}
    
    /**
     * Exception thrown when circuit breaker is open.
     */
    public static class CircuitBreakerException extends RuntimeException {
        public CircuitBreakerException(String message) {
            super(message);
        }
    }
}
