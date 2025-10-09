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

import com.firefly.transactional.persistence.SagaPersistenceProvider;
import com.firefly.transactional.persistence.impl.RedisSagaPersistenceProvider;
import com.firefly.transactional.persistence.serialization.SagaStateSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
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
 * Auto-configuration for Redis-based saga persistence.
 * <p>
 * This configuration is only loaded when Redis classes are available on the classpath,
 * preventing ClassNotFoundException when Redis is not included as a dependency.
 * <p>
 * Redis persistence is enabled when {@code firefly.saga.persistence.enabled=true}
 * and Redis classes are available.
 */
@AutoConfiguration
@AutoConfigureAfter(SagaPersistenceAutoConfiguration.class)
@EnableConfigurationProperties(SagaEngineProperties.class)
@ConditionalOnClass({RedisConnectionFactory.class, ReactiveRedisTemplate.class})
@ConditionalOnProperty(name = "firefly.saga.persistence.enabled", havingValue = "true")
public class SagaRedisAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SagaRedisAutoConfiguration.class);

    /**
     * Redis connection factory for saga persistence.
     * Only created when Redis persistence is enabled and no custom connection factory is provided.
     */
    @Bean
    @ConditionalOnMissingBean
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
     * Only created when Redis persistence is explicitly enabled and Redis classes are available.
     */
    @Bean
    @Primary
    public SagaPersistenceProvider redisSagaPersistenceProvider(
            ReactiveRedisTemplate<String, byte[]> redisTemplate,
            SagaStateSerializer serializer,
            SagaEngineProperties properties) {

        SagaEngineProperties.RedisProperties redis = properties.getPersistence().getRedis();
        log.info("Configuring Redis saga persistence provider with key prefix: {}", redis.getKeyPrefix());

        return new RedisSagaPersistenceProvider(redisTemplate, serializer, redis);
    }
}