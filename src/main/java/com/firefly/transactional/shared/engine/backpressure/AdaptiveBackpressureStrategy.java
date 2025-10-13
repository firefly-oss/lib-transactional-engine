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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptive backpressure strategy that dynamically adjusts concurrency and batch size
 * based on processing performance and error rates.
 * 
 * <p>This strategy monitors:
 * <ul>
 *   <li>Processing latency</li>
 *   <li>Error rates</li>
 *   <li>Throughput</li>
 * </ul>
 * 
 * <p>And adapts by:
 * <ul>
 *   <li>Reducing concurrency when error rates are high</li>
 *   <li>Increasing concurrency when performance is good</li>
 *   <li>Adjusting batch sizes based on memory pressure</li>
 * </ul>
 */
public class AdaptiveBackpressureStrategy implements BackpressureStrategy {
    
    private static final Logger log = LoggerFactory.getLogger(AdaptiveBackpressureStrategy.class);
    
    private final AtomicInteger currentConcurrency = new AtomicInteger();
    private final AtomicInteger currentBatchSize = new AtomicInteger();
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final AtomicLong lastAdaptationTime = new AtomicLong(System.currentTimeMillis());
    
    // Adaptation parameters
    private static final double ERROR_THRESHOLD = 0.1; // 10% error rate threshold
    private static final long ADAPTATION_INTERVAL_MS = 5000; // 5 seconds
    private static final int MIN_CONCURRENCY = 1;
    private static final int MAX_CONCURRENCY = 100;
    private static final int MIN_BATCH_SIZE = 5;
    private static final int MAX_BATCH_SIZE = 200;
    
    @Override
    public <T, R> Flux<R> applyBackpressure(List<T> items, 
                                           ItemProcessor<T, R> processor, 
                                           BackpressureConfig config) {
        config.validate();
        
        if (items.isEmpty()) {
            return Flux.empty();
        }
        
        // Initialize adaptive parameters
        currentConcurrency.set(config.concurrency());
        currentBatchSize.set(config.batchSize());
        
        log.debug("Applying adaptive backpressure to {} items with initial concurrency {} and batch size {}", 
                 items.size(), currentConcurrency.get(), currentBatchSize.get());
        
        return Flux.fromIterable(items)
                .buffer(currentBatchSize.get())
                .concatMap(batch -> processAdaptiveBatch(batch, processor, config), 1)
                .timeout(config.timeout())
                .doOnSubscribe(s -> log.debug("Starting adaptive processing of {} items", items.size()))
                .doOnComplete(() -> {
                    log.debug("Completed adaptive processing. Final stats - Processed: {}, Errors: {}, Error Rate: {:.2f}%", 
                             totalProcessed.get(), totalErrors.get(), getErrorRate() * 100);
                })
                .doOnError(error -> log.error("Adaptive processing failed: {}", error.getMessage()));
    }
    
    /**
     * Processes a batch with adaptive concurrency control.
     */
    private <T, R> Flux<R> processAdaptiveBatch(List<T> batch, 
                                               ItemProcessor<T, R> processor, 
                                               BackpressureConfig config) {
        // Adapt parameters before processing batch
        adaptParameters();
        
        int concurrency = currentConcurrency.get();
        log.debug("Processing adaptive batch of {} items with concurrency {}", batch.size(), concurrency);
        
        return Flux.fromIterable(batch)
                .flatMap(item -> processItemWithMetrics(item, processor, config), concurrency)
                .onErrorContinue((error, item) -> {
                    totalErrors.incrementAndGet();
                    log.warn("Failed to process item in adaptive batch: {}", error.getMessage());
                })
                .doOnComplete(() -> log.debug("Completed adaptive batch processing"));
    }
    
    /**
     * Processes a single item while collecting performance metrics.
     */
    private <T, R> Mono<R> processItemWithMetrics(T item, 
                                                 ItemProcessor<T, R> processor, 
                                                 BackpressureConfig config) {
        Instant start = Instant.now();
        
        return processor.process(item)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(result -> {
                    totalProcessed.incrementAndGet();
                    long latency = Duration.between(start, Instant.now()).toMillis();
                    log.trace("Item processed successfully in {}ms", latency);
                })
                .doOnError(error -> {
                    totalErrors.incrementAndGet();
                    long latency = Duration.between(start, Instant.now()).toMillis();
                    log.warn("Item processing failed after {}ms: {}", latency, error.getMessage());
                });
    }
    
    /**
     * Adapts concurrency and batch size based on current performance metrics.
     */
    private void adaptParameters() {
        long now = System.currentTimeMillis();
        long lastAdaptation = lastAdaptationTime.get();
        
        // Only adapt if enough time has passed
        if (now - lastAdaptation < ADAPTATION_INTERVAL_MS) {
            return;
        }
        
        if (!lastAdaptationTime.compareAndSet(lastAdaptation, now)) {
            return; // Another thread is adapting
        }
        
        double errorRate = getErrorRate();
        long processed = totalProcessed.get();
        
        if (processed < 10) {
            return; // Not enough data to adapt
        }
        
        int oldConcurrency = currentConcurrency.get();
        int oldBatchSize = currentBatchSize.get();
        
        // Adapt concurrency based on error rate
        if (errorRate > ERROR_THRESHOLD) {
            // High error rate - reduce concurrency
            int newConcurrency = Math.max(MIN_CONCURRENCY, oldConcurrency - 1);
            currentConcurrency.set(newConcurrency);
            log.debug("High error rate ({:.2f}%) - reducing concurrency from {} to {}", 
                     errorRate * 100, oldConcurrency, newConcurrency);
        } else if (errorRate < ERROR_THRESHOLD / 2) {
            // Low error rate - increase concurrency
            int newConcurrency = Math.min(MAX_CONCURRENCY, oldConcurrency + 1);
            currentConcurrency.set(newConcurrency);
            log.debug("Low error rate ({:.2f}%) - increasing concurrency from {} to {}", 
                     errorRate * 100, oldConcurrency, newConcurrency);
        }
        
        // Adapt batch size based on memory pressure (simplified heuristic)
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        double memoryUsage = (double) (totalMemory - freeMemory) / totalMemory;
        
        if (memoryUsage > 0.8) {
            // High memory usage - reduce batch size
            int newBatchSize = Math.max(MIN_BATCH_SIZE, oldBatchSize - 5);
            currentBatchSize.set(newBatchSize);
            log.debug("High memory usage ({:.2f}%) - reducing batch size from {} to {}", 
                     memoryUsage * 100, oldBatchSize, newBatchSize);
        } else if (memoryUsage < 0.5) {
            // Low memory usage - increase batch size
            int newBatchSize = Math.min(MAX_BATCH_SIZE, oldBatchSize + 5);
            currentBatchSize.set(newBatchSize);
            log.debug("Low memory usage ({:.2f}%) - increasing batch size from {} to {}", 
                     memoryUsage * 100, oldBatchSize, newBatchSize);
        }
    }
    
    /**
     * Calculates the current error rate.
     */
    private double getErrorRate() {
        long processed = totalProcessed.get();
        long errors = totalErrors.get();
        
        if (processed == 0) {
            return 0.0;
        }
        
        return (double) errors / (processed + errors);
    }
    
    @Override
    public String getStrategyName() {
        return "AdaptiveBackpressure";
    }
    
    /**
     * Gets current performance metrics.
     */
    public PerformanceMetrics getMetrics() {
        return new PerformanceMetrics(
            totalProcessed.get(),
            totalErrors.get(),
            getErrorRate(),
            currentConcurrency.get(),
            currentBatchSize.get()
        );
    }
    
    /**
     * Performance metrics record.
     */
    public record PerformanceMetrics(
        long totalProcessed,
        long totalErrors,
        double errorRate,
        int currentConcurrency,
        int currentBatchSize
    ) {}
}
