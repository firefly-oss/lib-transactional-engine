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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firefly.transactional.tcc.persistence.TccExecutionState;
import com.firefly.transactional.tcc.persistence.TccPersistenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis-based implementation of TCC persistence provider.
 * <p>
 * This implementation stores TCC execution states in Redis using JSON serialization.
 * It's suitable for production deployments with multiple instances where persistence
 * and state sharing across instances is required.
 * <p>
 * Key features:
 * <ul>
 *   <li>Uses "tcc:" prefix for all keys to separate from SAGA states</li>
 *   <li>JSON serialization for human-readable storage</li>
 *   <li>TTL support for automatic cleanup</li>
 *   <li>Reactive operations using Spring Data Redis</li>
 * </ul>
 */
public class RedisTccPersistenceProvider implements TccPersistenceProvider {
    
    private static final Logger log = LoggerFactory.getLogger(RedisTccPersistenceProvider.class);
    private static final String KEY_PREFIX = "tcc:";
    private static final String ACTIVE_SET_KEY = "tcc:active";
    
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration defaultTtl;
    
    /**
     * Creates a new Redis TCC persistence provider.
     *
     * @param redisTemplate the reactive Redis template
     * @param objectMapper the JSON object mapper
     * @param defaultTtl the default TTL for TCC states (null for no TTL)
     */
    public RedisTccPersistenceProvider(ReactiveRedisTemplate<String, String> redisTemplate,
                                       ObjectMapper objectMapper,
                                       Duration defaultTtl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.defaultTtl = defaultTtl;
    }
    
    /**
     * Creates a new Redis TCC persistence provider with no TTL.
     *
     * @param redisTemplate the reactive Redis template
     * @param objectMapper the JSON object mapper
     */
    public RedisTccPersistenceProvider(ReactiveRedisTemplate<String, String> redisTemplate,
                                       ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, null);
    }
    
    @Override
    public Mono<Void> saveState(TccExecutionState state) {
        String key = buildKey(state.getCorrelationId());
        
        return Mono.fromCallable(() -> {
            try {
                return objectMapper.writeValueAsString(state);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize TCC state", e);
            }
        })
        .flatMap(json -> {
            Mono<Boolean> setOperation = defaultTtl != null ?
                    redisTemplate.opsForValue().set(key, json, defaultTtl) :
                    redisTemplate.opsForValue().set(key, json);
            
            return setOperation
                    .then(redisTemplate.opsForSet().add(ACTIVE_SET_KEY, state.getCorrelationId()))
                    .then();
        })
        .doOnSuccess(v -> log.debug("Saved TCC state for correlation ID: {}", state.getCorrelationId()))
        .doOnError(error -> log.error("Failed to save TCC state for correlation ID: {}", 
                state.getCorrelationId(), error));
    }
    
    @Override
    public Mono<TccExecutionState> loadState(String correlationId) {
        String key = buildKey(correlationId);
        
        return redisTemplate.opsForValue().get(key)
                .flatMap(json -> Mono.fromCallable(() -> {
                    try {
                        return objectMapper.readValue(json, TccExecutionState.class);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException("Failed to deserialize TCC state", e);
                    }
                }))
                .doOnNext(state -> log.debug("Loaded TCC state for correlation ID: {}", correlationId))
                .doOnError(error -> log.error("Failed to load TCC state for correlation ID: {}", 
                        correlationId, error));
    }
    
    @Override
    public Mono<Void> deleteState(String correlationId) {
        String key = buildKey(correlationId);
        
        return redisTemplate.opsForValue().delete(key)
                .then(redisTemplate.opsForSet().remove(ACTIVE_SET_KEY, correlationId))
                .then()
                .doOnSuccess(v -> log.debug("Deleted TCC state for correlation ID: {}", correlationId))
                .doOnError(error -> log.error("Failed to delete TCC state for correlation ID: {}", 
                        correlationId, error));
    }
    
    @Override
    public Flux<TccExecutionState> findStatesStartedBefore(Instant before) {
        return listActiveTransactions()
                .flatMap(this::loadState)
                .filter(state -> state.getStartedAt().isBefore(before))
                .doOnNext(state -> log.debug("Found TCC state started before {}: {}", 
                        before, state.getCorrelationId()));
    }
    
    @Override
    public Flux<TccExecutionState> findStatesUpdatedBefore(Instant before) {
        return listActiveTransactions()
                .flatMap(this::loadState)
                .filter(state -> state.getLastUpdatedAt().isBefore(before))
                .doOnNext(state -> log.debug("Found TCC state updated before {}: {}", 
                        before, state.getCorrelationId()));
    }
    
    @Override
    public Flux<String> listActiveTransactions() {
        return redisTemplate.opsForSet().members(ACTIVE_SET_KEY)
                .doOnNext(correlationId -> log.debug("Active TCC transaction: {}", correlationId));
    }
    
    @Override
    public Mono<Long> countActiveTransactions() {
        return redisTemplate.opsForSet().size(ACTIVE_SET_KEY)
                .doOnNext(count -> log.debug("Total active TCC transactions: {}", count));
    }
    
    @Override
    public Mono<Long> cleanup(Instant olderThan) {
        return findStatesUpdatedBefore(olderThan)
                .flatMap(state -> deleteState(state.getCorrelationId()).thenReturn(1L))
                .reduce(0L, Long::sum)
                .doOnNext(removed -> log.debug("Cleaned up {} TCC states older than {}", removed, olderThan));
    }
    
    private String buildKey(String correlationId) {
        return KEY_PREFIX + correlationId;
    }
}
