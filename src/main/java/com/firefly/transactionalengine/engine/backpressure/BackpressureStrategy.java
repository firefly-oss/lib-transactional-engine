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

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Strategy interface for handling backpressure in reactive streams.
 * Implementations provide different approaches to managing flow control
 * and preventing overwhelming downstream systems.
 */
public interface BackpressureStrategy {
    
    /**
     * Applies backpressure control to a stream of items.
     * 
     * @param items the items to process
     * @param processor function to process each item
     * @param config configuration for the backpressure strategy
     * @param <T> type of items to process
     * @param <R> type of processing result
     * @return flux of processed results with backpressure applied
     */
    <T, R> Flux<R> applyBackpressure(List<T> items, 
                                     ItemProcessor<T, R> processor, 
                                     BackpressureConfig config);
    
    /**
     * Gets the name of this backpressure strategy.
     * 
     * @return strategy name
     */
    String getStrategyName();
    
    /**
     * Functional interface for processing individual items.
     */
    @FunctionalInterface
    interface ItemProcessor<T, R> {
        Mono<R> process(T item);
    }
}
