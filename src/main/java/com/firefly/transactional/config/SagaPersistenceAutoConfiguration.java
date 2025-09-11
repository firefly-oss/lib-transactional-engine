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

package com.firefly.transactional.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firefly.transactional.persistence.SagaPersistenceProvider;
import com.firefly.transactional.persistence.SagaRecoveryService;
import com.firefly.transactional.persistence.impl.DefaultSagaRecoveryService;
import com.firefly.transactional.persistence.impl.InMemorySagaPersistenceProvider;
import com.firefly.transactional.persistence.impl.RedisSagaPersistenceProvider;
import com.firefly.transactional.persistence.serialization.JsonSagaStateSerializer;
import com.firefly.transactional.persistence.serialization.SagaStateSerializer;
import com.firefly.transactional.engine.SagaEngine;
import com.firefly.transactional.registry.SagaRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * Auto-configuration for Saga persistence capabilities.
 * <p>
 * This configuration automatically sets up the appropriate persistence provider
 * based on the application properties and available dependencies:
 * <ul>
 *   <li>In-memory persistence (default) - no external dependencies required</li>
 *   <li>Redis persistence - requires Redis connection configuration</li>
 * </ul>
 * <p>
 * Configuration is controlled through the {@code firefly.saga.persistence} properties.
 * When persistence is disabled, the engine uses in-memory storage with no persistence
 * across application restarts (maintaining backward compatibility).
 */
@AutoConfiguration
@EnableConfigurationProperties(SagaEngineProperties.class)
public class SagaPersistenceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SagaPersistenceAutoConfiguration.class);

    /**
     * Default ObjectMapper for saga serialization.
     */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper sagaObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules(); // This will register JSR310 module for Java 8 time types

        // Configure deserialization to ignore unknown properties for backward compatibility
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper;
    }

    /**
     * Default saga state serializer using Jackson JSON.
     */
    @Bean
    @ConditionalOnMissingBean
    public SagaStateSerializer sagaStateSerializer(ObjectMapper objectMapper) {
        return new JsonSagaStateSerializer(objectMapper);
    }

    /**
     * Default in-memory persistence provider.
     * This is always available as a fallback and provides the default zero-persistence behavior.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "firefly.saga.persistence.enabled", havingValue = "false", matchIfMissing = true)
    public SagaPersistenceProvider inMemorySagaPersistenceProvider() {
        log.info("Configuring in-memory saga persistence provider (no external persistence)");
        return new InMemorySagaPersistenceProvider();
    }

    /**
     * Redis connection factory for saga persistence.
     * Only created when Redis persistence is enabled and no custom connection factory is provided.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "firefly.saga.persistence.enabled", havingValue = "true")
    @ConditionalOnClass(LettuceConnectionFactory.class)
    public RedisConnectionFactory sagaRedisConnectionFactory(SagaEngineProperties properties) {
        SagaEngineProperties.RedisProperties redis = properties.getPersistence().getRedis();
        
        log.info("Configuring Redis connection factory for saga persistence: {}:{}", 
                redis.getHost(), redis.getPort());
        
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis.getHost(), redis.getPort());
        factory.setDatabase(redis.getDatabase());
        if (redis.getPassword() != null) {
            factory.setPassword(redis.getPassword());
        }
        factory.setValidateConnection(true);
        factory.afterPropertiesSet(); // Initialize the connection factory

        return factory;
    }

    /**
     * Reactive Redis template for saga persistence operations.
     */
    @Bean
    @ConditionalOnMissingBean(name = "sagaReactiveRedisTemplate")
    @ConditionalOnProperty(name = "firefly.saga.persistence.enabled", havingValue = "true")
    @ConditionalOnClass(ReactiveRedisTemplate.class)
    public ReactiveRedisTemplate<String, byte[]> sagaReactiveRedisTemplate(RedisConnectionFactory connectionFactory) {
        log.debug("Configuring reactive Redis template for saga persistence");

        RedisSerializationContext<String, byte[]> context = RedisSerializationContext
                .<String, byte[]>newSerializationContext()
                .key(RedisSerializationContext.SerializationPair.fromSerializer(
                        new org.springframework.data.redis.serializer.StringRedisSerializer()))
                .value(RedisSerializationContext.SerializationPair.fromSerializer(
                        org.springframework.data.redis.serializer.RedisSerializer.byteArray()))
                .hashKey(RedisSerializationContext.SerializationPair.fromSerializer(
                        new org.springframework.data.redis.serializer.StringRedisSerializer()))
                .hashValue(RedisSerializationContext.SerializationPair.fromSerializer(
                        org.springframework.data.redis.serializer.RedisSerializer.byteArray()))
                .build();

        return new ReactiveRedisTemplate<String, byte[]>((LettuceConnectionFactory) connectionFactory, context);
    }

    /**
     * Redis-based saga persistence provider.
     * Only created when Redis persistence is explicitly enabled.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "firefly.saga.persistence.enabled", havingValue = "true")
    @ConditionalOnClass(ReactiveRedisTemplate.class)
    public SagaPersistenceProvider redisSagaPersistenceProvider(
            ReactiveRedisTemplate<String, byte[]> redisTemplate,
            SagaStateSerializer serializer,
            SagaEngineProperties properties) {

        SagaEngineProperties.RedisProperties redis = properties.getPersistence().getRedis();
        log.info("Configuring Redis saga persistence provider with key prefix: {}", redis.getKeyPrefix());

        return new RedisSagaPersistenceProvider(redisTemplate, serializer, redis);
    }

    /**
     * Default saga recovery service.
     * Only created when persistence is enabled (Redis mode).
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "firefly.saga.persistence.enabled", havingValue = "true")
    public SagaRecoveryService sagaRecoveryService(
            SagaPersistenceProvider persistenceProvider,
            SagaEngine sagaEngine,
            SagaRegistry sagaRegistry,
            SagaEngineProperties properties) {

        log.info("Configuring saga recovery service");
        return new DefaultSagaRecoveryService(
                persistenceProvider,
                sagaEngine,
                sagaRegistry,
                properties.getPersistence()
        );
    }

    /**
     * Configuration validation and startup checks.
     */
    @Bean
    @ConditionalOnProperty(name = "firefly.saga.persistence.enabled", havingValue = "true")
    public SagaPersistenceStartupValidator sagaPersistenceStartupValidator(
            SagaPersistenceProvider persistenceProvider,
            SagaEngineProperties properties) {
        
        return new SagaPersistenceStartupValidator(persistenceProvider, properties);
    }

    /**
     * Startup validator to ensure persistence configuration is correct.
     */
    public static class SagaPersistenceStartupValidator {
        private static final Logger log = LoggerFactory.getLogger(SagaPersistenceStartupValidator.class);
        
        private final SagaPersistenceProvider persistenceProvider;
        private final SagaEngineProperties properties;

        public SagaPersistenceStartupValidator(SagaPersistenceProvider persistenceProvider, 
                                             SagaEngineProperties properties) {
            this.persistenceProvider = persistenceProvider;
            this.properties = properties;
            validateConfiguration();
        }

        private void validateConfiguration() {
            log.info("Validating saga persistence configuration...");
            
            // Check persistence provider type
            SagaPersistenceProvider.PersistenceProviderType providerType = persistenceProvider.getProviderType();
            log.info("Saga persistence provider type: {}", providerType);
            
            // Validate Redis configuration if using Redis
            if (providerType == SagaPersistenceProvider.PersistenceProviderType.REDIS) {
                validateRedisConfiguration();
            }
            
            // Test persistence provider health
            try {
                Boolean healthy = persistenceProvider.isHealthy().block();
                if (Boolean.TRUE.equals(healthy)) {
                    log.info("Saga persistence provider health check: PASSED");
                } else {
                    log.warn("Saga persistence provider health check: FAILED - continuing with degraded functionality");
                }
            } catch (Exception e) {
                log.warn("Saga persistence provider health check failed: {} - continuing with degraded functionality", 
                        e.getMessage());
            }
            
            log.info("Saga persistence configuration validation completed");
        }

        private void validateRedisConfiguration() {
            SagaEngineProperties.RedisProperties redis = properties.getPersistence().getRedis();
            
            if (redis.getHost() == null || redis.getHost().trim().isEmpty()) {
                throw new IllegalStateException("Redis host must be configured when using Redis persistence");
            }
            
            if (redis.getPort() <= 0 || redis.getPort() > 65535) {
                throw new IllegalStateException("Redis port must be a valid port number (1-65535)");
            }
            
            if (redis.getKeyPrefix() == null || redis.getKeyPrefix().trim().isEmpty()) {
                throw new IllegalStateException("Redis key prefix must be configured");
            }
            
            log.debug("Redis configuration validation passed: {}:{} (database: {}, prefix: {})", 
                    redis.getHost(), redis.getPort(), redis.getDatabase(), redis.getKeyPrefix());
        }
    }
}
