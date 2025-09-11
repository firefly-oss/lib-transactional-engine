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

package com.firefly.transactional.persistence.impl;

import com.firefly.transactional.config.SagaEngineProperties;
import com.firefly.transactional.persistence.SagaExecutionState;
import com.firefly.transactional.persistence.SagaPersistenceProvider;
import com.firefly.transactional.persistence.StepExecutionStatus;
import com.firefly.transactional.persistence.serialization.SagaStateSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Redis-based implementation of SagaPersistenceProvider.
 * <p>
 * This implementation provides true persistence for saga execution state using Redis
 * as the storage backend. It supports automatic recovery of in-flight sagas after
 * application restarts and provides configurable retention policies.
 * <p>
 * Key features:
 * <ul>
 *   <li>Persistent storage across application restarts</li>
 *   <li>Configurable key prefixes and TTL</li>
 *   <li>Efficient serialization using pluggable serializers</li>
 *   <li>Connection health monitoring</li>
 *   <li>Automatic cleanup of old saga states</li>
 * </ul>
 * <p>
 * Redis key structure:
 * <ul>
 *   <li>{prefix}:state:{correlationId} - Complete saga execution state</li>
 *   <li>{prefix}:completed:{correlationId} - Completion timestamp for cleanup</li>
 *   <li>{prefix}:metadata:{correlationId} - Lightweight metadata for queries</li>
 * </ul>
 */
public class RedisSagaPersistenceProvider implements SagaPersistenceProvider {

    private static final Logger log = LoggerFactory.getLogger(RedisSagaPersistenceProvider.class);

    private final ReactiveRedisTemplate<String, byte[]> redisTemplate;
    private final SagaStateSerializer serializer;
    private final SagaEngineProperties.RedisProperties redisProperties;
    
    // Key patterns
    private final String stateKeyPrefix;
    private final String completedKeyPrefix;
    private final String metadataKeyPrefix;

    /**
     * Creates a new Redis persistence provider.
     *
     * @param redisTemplate the reactive Redis template for data operations
     * @param serializer the serializer for saga state
     * @param redisProperties the Redis configuration properties
     */
    public RedisSagaPersistenceProvider(ReactiveRedisTemplate<String, byte[]> redisTemplate,
                                       SagaStateSerializer serializer,
                                       SagaEngineProperties.RedisProperties redisProperties) {
        this.redisTemplate = redisTemplate;
        this.serializer = serializer;
        this.redisProperties = redisProperties;
        
        String basePrefix = redisProperties.getKeyPrefix();
        this.stateKeyPrefix = basePrefix + "state:";
        this.completedKeyPrefix = basePrefix + "completed:";
        this.metadataKeyPrefix = basePrefix + "metadata:";
        
        log.info("Initialized Redis saga persistence provider with key prefix: {}", basePrefix);
    }

    @Override
    public Mono<Void> persistSagaState(SagaExecutionState sagaState) {
        String correlationId = sagaState.getCorrelationId();
        String stateKey = stateKeyPrefix + correlationId;
        String metadataKey = metadataKeyPrefix + correlationId;
        
        return Mono.fromCallable(() -> {
            try {
                byte[] serializedState = serializer.serialize(sagaState);
                log.debug("Serialized saga state for correlation ID: {} ({} bytes)", 
                        correlationId, serializedState.length);
                return serializedState;
            } catch (SagaStateSerializer.SerializationException e) {
                throw new RuntimeException("Failed to serialize saga state for " + correlationId, e);
            }
        })
        .flatMap(serializedState -> {
            // Store the complete state
            Mono<Boolean> stateOp = redisTemplate.opsForValue().set(stateKey, serializedState);
            
            // Store lightweight metadata for efficient queries
            SagaMetadata metadata = SagaMetadata.from(sagaState);
            Mono<Boolean> metadataOp = redisTemplate.opsForValue().set(metadataKey, metadata.toBytes());
            
            // Apply TTL if configured
            Mono<Void> ttlOp = Mono.empty();
            if (redisProperties.getKeyTtl() != null) {
                ttlOp = redisTemplate.expire(stateKey, redisProperties.getKeyTtl())
                        .then(redisTemplate.expire(metadataKey, redisProperties.getKeyTtl()))
                        .then();
            }
            
            return Mono.when(stateOp, metadataOp).then(ttlOp);
        })
        .doOnSuccess(v -> log.debug("Successfully persisted saga state to Redis for correlation ID: {}", correlationId))
        .doOnError(error -> log.error("Failed to persist saga state to Redis for correlation ID: {}", correlationId, error))
        .onErrorMap(throwable -> new RuntimeException("Redis persistence failed for " + correlationId, throwable));
    }

    @Override
    public Mono<Optional<SagaExecutionState>> getSagaState(String correlationId) {
        String stateKey = stateKeyPrefix + correlationId;
        
        return redisTemplate.opsForValue().get(stateKey)
                .map(serializedState -> {
                    try {
                        SagaExecutionState state = serializer.deserialize(serializedState);
                        log.debug("Successfully retrieved saga state from Redis for correlation ID: {}", correlationId);
                        return Optional.of(state);
                    } catch (SagaStateSerializer.SerializationException e) {
                        log.error("Failed to deserialize saga state for correlation ID: {}", correlationId, e);
                        return Optional.<SagaExecutionState>empty();
                    }
                })
                .defaultIfEmpty(Optional.empty())
                .doOnNext(result -> {
                    if (result.isEmpty()) {
                        log.debug("No saga state found in Redis for correlation ID: {}", correlationId);
                    }
                })
                .onErrorReturn(Optional.empty());
    }

    @Override
    public Mono<Void> updateStepStatus(String correlationId, String stepId, StepExecutionStatus status) {
        // For Redis implementation, we update the complete state rather than individual fields
        // This ensures consistency and simplifies the implementation
        return getSagaState(correlationId)
                .flatMap(optionalState -> {
                    if (optionalState.isPresent()) {
                        SagaExecutionState currentState = optionalState.get();
                        SagaExecutionState updatedState = currentState.withStepStatus(stepId, status);
                        return persistSagaState(updatedState);
                    } else {
                        log.warn("Attempted to update step status for non-existent saga: {}", correlationId);
                        return Mono.empty();
                    }
                });
    }

    @Override
    public Mono<Void> markSagaCompleted(String correlationId, boolean successful) {
        String completedKey = completedKeyPrefix + correlationId;
        
        return getSagaState(correlationId)
                .flatMap(optionalState -> {
                    if (optionalState.isPresent()) {
                        SagaExecutionState currentState = optionalState.get();
                        
                        // Update the saga status
                        var newStatus = successful ? 
                            com.firefly.transactional.persistence.SagaExecutionStatus.COMPLETED_SUCCESS :
                            com.firefly.transactional.persistence.SagaExecutionStatus.FAILED;
                        
                        SagaExecutionState updatedState = currentState.withStatus(newStatus);
                        
                        // Persist updated state and mark completion time
                        Mono<Void> persistOp = persistSagaState(updatedState);
                        Mono<Boolean> completedOp = redisTemplate.opsForValue()
                                .set(completedKey, Instant.now().toString().getBytes());
                        
                        return Mono.when(persistOp, completedOp).then();
                    } else {
                        log.warn("Attempted to mark non-existent saga as completed: {}", correlationId);
                        return Mono.empty();
                    }
                });
    }

    @Override
    public Flux<SagaExecutionState> getInFlightSagas() {
        String pattern = metadataKeyPrefix + "*";
        
        return redisTemplate.scan(ScanOptions.scanOptions().match(pattern).build())
                .flatMap(metadataKey -> redisTemplate.opsForValue().get(metadataKey))
                .map(SagaMetadata::fromBytes)
                .filter(metadata -> metadata.getStatus().isRecoverable())
                .flatMap(metadata -> getSagaState(metadata.getCorrelationId()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .doOnSubscribe(subscription -> log.debug("Scanning Redis for in-flight sagas"))
                .onErrorContinue((throwable, obj) -> 
                    log.warn("Error processing saga metadata during in-flight scan", throwable));
    }

    @Override
    public Flux<SagaExecutionState> getStaleSagas(Instant before) {
        String pattern = metadataKeyPrefix + "*";

        return redisTemplate.scan(ScanOptions.scanOptions().match(pattern).build())
                .flatMap(metadataKey -> redisTemplate.opsForValue().get(metadataKey))
                .map(SagaMetadata::fromBytes)
                .filter(metadata -> metadata.getLastUpdated().isBefore(before))
                .filter(metadata -> metadata.getStatus().isRecoverable()) // Only include recoverable sagas
                .flatMap(metadata -> getSagaState(metadata.getCorrelationId()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .doOnSubscribe(subscription -> log.debug("Scanning Redis for stale sagas before: {}", before))
                .onErrorContinue((throwable, obj) ->
                    log.warn("Error processing saga metadata during stale scan", throwable));
    }

    @Override
    public Mono<Long> cleanupCompletedSagas(Duration olderThan) {
        Instant cutoff = Instant.now().minus(olderThan);
        String completedPattern = completedKeyPrefix + "*";
        
        return redisTemplate.scan(ScanOptions.scanOptions().match(completedPattern).build())
                .flatMap(completedKey -> 
                    redisTemplate.opsForValue().get(completedKey)
                            .map(timestampBytes -> {
                                try {
                                    Instant completedAt = Instant.parse(new String(timestampBytes));
                                    String correlationId = completedKey.substring(completedKeyPrefix.length());
                                    return new CompletedSagaInfo(correlationId, completedAt);
                                } catch (Exception e) {
                                    log.warn("Failed to parse completion timestamp for key: {}", completedKey, e);
                                    return null;
                                }
                            })
                            .filter(info -> info != null && info.completedAt.isBefore(cutoff))
                )
                .flatMap(info -> cleanupSagaKeys(info.correlationId))
                .count()
                .doOnSuccess(count -> log.debug("Cleaned up {} completed saga states from Redis", count));
    }

    /**
     * Removes all keys associated with a saga.
     */
    private Mono<Void> cleanupSagaKeys(String correlationId) {
        String stateKey = stateKeyPrefix + correlationId;
        String completedKey = completedKeyPrefix + correlationId;
        String metadataKey = metadataKeyPrefix + correlationId;
        
        return Mono.when(
                redisTemplate.delete(stateKey),
                redisTemplate.delete(completedKey),
                redisTemplate.delete(metadataKey)
        ).then();
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return redisTemplate.opsForValue()
                .set("firefly:health:check", "ok".getBytes(), Duration.ofSeconds(10))
                .map(result -> {
                    log.debug("Redis health check result: {}", result);
                    return result;
                })
                .onErrorReturn(false);
    }

    @Override
    public PersistenceProviderType getProviderType() {
        return PersistenceProviderType.REDIS;
    }

    /**
     * Lightweight metadata for efficient queries.
     */
    private static class SagaMetadata {
        private final String correlationId;
        private final com.firefly.transactional.persistence.SagaExecutionStatus status;
        private final Instant lastUpdated;

        public SagaMetadata(String correlationId, 
                           com.firefly.transactional.persistence.SagaExecutionStatus status,
                           Instant lastUpdated) {
            this.correlationId = correlationId;
            this.status = status;
            this.lastUpdated = lastUpdated;
        }

        public static SagaMetadata from(SagaExecutionState state) {
            return new SagaMetadata(
                    state.getCorrelationId(),
                    state.getStatus(),
                    state.getLastUpdatedAt()
            );
        }

        public byte[] toBytes() {
            String data = correlationId + "|" + status + "|" + lastUpdated;
            return data.getBytes();
        }

        public static SagaMetadata fromBytes(byte[] data) {
            String[] parts = new String(data).split("\\|");
            return new SagaMetadata(
                    parts[0],
                    com.firefly.transactional.persistence.SagaExecutionStatus.valueOf(parts[1]),
                    Instant.parse(parts[2])
            );
        }

        public String getCorrelationId() { return correlationId; }
        public com.firefly.transactional.persistence.SagaExecutionStatus getStatus() { return status; }
        public Instant getLastUpdated() { return lastUpdated; }
    }

    /**
     * Information about completed sagas for cleanup.
     */
    private static class CompletedSagaInfo {
        final String correlationId;
        final Instant completedAt;

        CompletedSagaInfo(String correlationId, Instant completedAt) {
            this.correlationId = correlationId;
            this.completedAt = completedAt;
        }
    }
}
