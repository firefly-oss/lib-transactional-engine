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

package com.firefly.transactional.engine.backpressure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * Batched backpressure strategy that processes items in configurable batches
 * with controlled concurrency within each batch.
 * 
 * <p>This strategy is ideal for scenarios where:
 * <ul>
 *   <li>Downstream systems can handle bursts of requests</li>
 *   <li>Memory usage needs to be controlled</li>
 *   <li>Processing order within batches is not critical</li>
 * </ul>
 */
public class BatchedBackpressureStrategy implements BackpressureStrategy {
    
    private static final Logger log = LoggerFactory.getLogger(BatchedBackpressureStrategy.class);
    
    @Override
    public <T, R> Flux<R> applyBackpressure(List<T> items, 
                                           ItemProcessor<T, R> processor, 
                                           BackpressureConfig config) {
        config.validate();
        
        if (items.isEmpty()) {
            return Flux.empty();
        }
        
        log.debug("Applying batched backpressure to {} items with batch size {} and concurrency {}", 
                 items.size(), config.batchSize(), config.concurrency());
        
        return Flux.fromIterable(items)
                // Create batches of configured size
                .buffer(config.batchSize())
                // Process batches sequentially to maintain memory control
                .concatMap(batch -> processBatch(batch, processor, config), 1)
                .timeout(config.timeout())
                .doOnSubscribe(s -> log.debug("Starting batched processing of {} items", items.size()))
                .doOnComplete(() -> log.debug("Completed batched processing"))
                .doOnError(error -> log.error("Batched processing failed: {}", error.getMessage()));
    }
    
    /**
     * Processes a single batch with controlled concurrency.
     */
    private <T, R> Flux<R> processBatch(List<T> batch, 
                                       ItemProcessor<T, R> processor, 
                                       BackpressureConfig config) {
        log.debug("Processing batch of {} items", batch.size());
        
        return Flux.fromIterable(batch)
                .flatMap(item -> processItemWithRetry(item, processor, config), config.concurrency())
                .onErrorContinue((error, item) -> 
                    log.warn("Failed to process item in batch: {}", error.getMessage()))
                .doOnComplete(() -> log.debug("Completed batch processing"));
    }
    
    /**
     * Processes a single item with optional retry logic.
     */
    private <T, R> Mono<R> processItemWithRetry(T item, 
                                               ItemProcessor<T, R> processor, 
                                               BackpressureConfig config) {
        Mono<R> processing = processor.process(item)
                .subscribeOn(Schedulers.boundedElastic()); // Offload to avoid blocking
        
        if (config.enableRetry() && config.maxRetries() > 0) {
            return processing
                    .retry(config.maxRetries())
                    .delayElement(config.retryDelay())
                    .doOnError(error -> log.warn("Item processing failed after {} retries: {}", 
                              config.maxRetries(), error.getMessage()));
        } else {
            return processing;
        }
    }
    
    @Override
    public String getStrategyName() {
        return "BatchedBackpressure";
    }
}
