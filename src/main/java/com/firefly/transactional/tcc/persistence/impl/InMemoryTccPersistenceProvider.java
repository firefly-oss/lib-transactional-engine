/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.firefly.transactional.tcc.persistence.impl;

import com.firefly.transactional.tcc.persistence.TccExecutionState;
import com.firefly.transactional.tcc.persistence.TccPersistenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory implementation of TCC persistence provider.
 * <p>
 * This implementation stores TCC execution states in memory using a concurrent hash map.
 * It's suitable for development, testing, and single-instance deployments where
 * persistence across restarts is not required.
 * <p>
 * For production deployments with multiple instances or persistence requirements,
 * use a Redis-based implementation instead.
 */
public class InMemoryTccPersistenceProvider implements TccPersistenceProvider {
    
    private static final Logger log = LoggerFactory.getLogger(InMemoryTccPersistenceProvider.class);
    
    private final ConcurrentMap<String, TccExecutionState> states = new ConcurrentHashMap<>();
    
    @Override
    public Mono<Void> saveState(TccExecutionState state) {
        return Mono.fromRunnable(() -> {
            states.put(state.getCorrelationId(), state);
            log.debug("Saved TCC state for correlation ID: {}", state.getCorrelationId());
        });
    }
    
    @Override
    public Mono<TccExecutionState> loadState(String correlationId) {
        return Mono.fromCallable(() -> {
            TccExecutionState state = states.get(correlationId);
            if (state != null) {
                log.debug("Loaded TCC state for correlation ID: {}", correlationId);
            } else {
                log.debug("No TCC state found for correlation ID: {}", correlationId);
            }
            return state;
        });
    }
    
    @Override
    public Mono<Void> deleteState(String correlationId) {
        return Mono.fromRunnable(() -> {
            TccExecutionState removed = states.remove(correlationId);
            if (removed != null) {
                log.debug("Deleted TCC state for correlation ID: {}", correlationId);
            } else {
                log.debug("No TCC state to delete for correlation ID: {}", correlationId);
            }
        });
    }
    
    @Override
    public Flux<TccExecutionState> findStatesStartedBefore(Instant before) {
        return Flux.fromIterable(states.values())
                .filter(state -> state.getStartedAt().isBefore(before))
                .doOnNext(state -> log.debug("Found TCC state started before {}: {}", 
                        before, state.getCorrelationId()));
    }
    
    @Override
    public Flux<TccExecutionState> findStatesUpdatedBefore(Instant before) {
        return Flux.fromIterable(states.values())
                .filter(state -> state.getLastUpdatedAt().isBefore(before))
                .doOnNext(state -> log.debug("Found TCC state updated before {}: {}", 
                        before, state.getCorrelationId()));
    }
    
    @Override
    public Flux<String> listActiveTransactions() {
        return Flux.fromIterable(states.keySet())
                .doOnNext(correlationId -> log.debug("Active TCC transaction: {}", correlationId));
    }
    
    @Override
    public Mono<Long> countActiveTransactions() {
        return Mono.fromCallable(() -> {
            long count = states.size();
            log.debug("Total active TCC transactions: {}", count);
            return count;
        });
    }
    
    @Override
    public Mono<Long> cleanup(Instant olderThan) {
        return Mono.fromCallable(() -> {
            long removed = 0;
            var iterator = states.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getValue().getLastUpdatedAt().isBefore(olderThan)) {
                    iterator.remove();
                    removed++;
                }
            }
            log.debug("Cleaned up {} TCC states older than {}", removed, olderThan);
            return removed;
        });
    }
    
    /**
     * Gets the current number of stored states (for testing/monitoring).
     *
     * @return the number of stored states
     */
    public int size() {
        return states.size();
    }
    
    /**
     * Clears all stored states (for testing).
     */
    public void clear() {
        states.clear();
        log.debug("Cleared all TCC states");
    }
}
