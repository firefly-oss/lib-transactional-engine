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

package com.firefly.transactional.saga.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firefly.transactional.saga.engine.SagaEngine;
import com.firefly.transactional.saga.registry.SagaRegistry;
import com.firefly.transactional.shared.config.TransactionalEngineProperties;
import com.firefly.transactional.saga.persistence.SagaPersistenceProvider;
import com.firefly.transactional.saga.persistence.SagaRecoveryService;
import com.firefly.transactional.saga.persistence.impl.DefaultSagaRecoveryService;
import com.firefly.transactional.saga.persistence.impl.InMemorySagaPersistenceProvider;
import com.firefly.transactional.saga.persistence.serialization.JsonSagaStateSerializer;
import com.firefly.transactional.saga.persistence.serialization.SagaStateSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

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
 * Configuration is controlled through the {@code firefly.tx.persistence} properties.
 * Legacy {@code firefly.saga.persistence} properties are also supported for backward compatibility.
 * When persistence is disabled, the engine uses in-memory storage with no persistence
 * across application restarts (maintaining backward compatibility).
 */
@AutoConfiguration
@EnableConfigurationProperties({
    TransactionalEngineProperties.class,
    SagaEngineProperties.class  // Keep for backward compatibility
})
public class SagaPersistenceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SagaPersistenceAutoConfiguration.class);

    /**
     * Default ObjectMapper for saga serialization.
     */
    @Bean
    @ConditionalOnMissingBean
    @Primary
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
    @ConditionalOnProperty(
        name = "firefly.tx.persistence.enabled",
        havingValue = "false",
        matchIfMissing = true
    )
    public SagaPersistenceProvider inMemorySagaPersistenceProvider() {
        log.info("Configuring in-memory saga persistence provider (no external persistence)");
        return new InMemorySagaPersistenceProvider();
    }


    /**
     * Default saga recovery service.
     * Only created when persistence is enabled (Redis mode).
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        name = "firefly.tx.persistence.enabled",
        havingValue = "true",
        matchIfMissing = false
    )
    public SagaRecoveryService sagaRecoveryService(
            SagaPersistenceProvider persistenceProvider,
            SagaEngine sagaEngine,
            SagaRegistry sagaRegistry,
            TransactionalEngineProperties txProperties) {

        log.info("Configuring saga recovery service");

        // Create a compatible PersistenceProperties instance for the recovery service
        TransactionalEngineProperties.PersistenceProperties newPersistence = txProperties.getPersistence();
        SagaEngineProperties.PersistenceProperties compatiblePersistence = new SagaEngineProperties.PersistenceProperties();
        compatiblePersistence.setEnabled(newPersistence.isEnabled());
        compatiblePersistence.setProvider(newPersistence.getProvider());
        compatiblePersistence.setAutoRecoveryEnabled(newPersistence.isAutoRecoveryEnabled());
        compatiblePersistence.setMaxSagaAge(newPersistence.getMaxTransactionAge());
        compatiblePersistence.setCleanupInterval(newPersistence.getCleanupInterval());
        compatiblePersistence.setRetentionPeriod(newPersistence.getRetentionPeriod());

        // Copy Redis properties
        SagaEngineProperties.RedisProperties compatibleRedis = new SagaEngineProperties.RedisProperties();
        TransactionalEngineProperties.RedisProperties newRedis = newPersistence.getRedis();
        compatibleRedis.setHost(newRedis.getHost());
        compatibleRedis.setPort(newRedis.getPort());
        compatibleRedis.setDatabase(newRedis.getDatabase());
        compatibleRedis.setPassword(newRedis.getPassword());
        compatibleRedis.setConnectionTimeout(newRedis.getConnectionTimeout());
        compatibleRedis.setCommandTimeout(newRedis.getCommandTimeout());
        compatibleRedis.setKeyPrefix(newRedis.getKeyPrefix());
        compatibleRedis.setKeyTtl(newRedis.getKeyTtl());
        compatiblePersistence.setRedis(compatibleRedis);

        return new DefaultSagaRecoveryService(
                persistenceProvider,
                sagaEngine,
                sagaRegistry,
                compatiblePersistence
        );
    }

    /**
     * Configuration validation and startup checks.
     */
    @Bean
    @ConditionalOnProperty(
        name = "firefly.tx.persistence.enabled",
        havingValue = "true",
        matchIfMissing = false
    )
    public SagaPersistenceStartupValidator sagaPersistenceStartupValidator(
            SagaPersistenceProvider persistenceProvider,
            TransactionalEngineProperties txProperties) {

        return new SagaPersistenceStartupValidator(persistenceProvider, txProperties);
    }

    /**
     * Startup validator to ensure persistence configuration is correct.
     */
    public static class SagaPersistenceStartupValidator {
        private static final Logger log = LoggerFactory.getLogger(SagaPersistenceStartupValidator.class);

        private final SagaPersistenceProvider persistenceProvider;
        private final TransactionalEngineProperties properties;

        public SagaPersistenceStartupValidator(SagaPersistenceProvider persistenceProvider,
                                             TransactionalEngineProperties properties) {
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
            TransactionalEngineProperties.RedisProperties redis = properties.getPersistence().getRedis();

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
