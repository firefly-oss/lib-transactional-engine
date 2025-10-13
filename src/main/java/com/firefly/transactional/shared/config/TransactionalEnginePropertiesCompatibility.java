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

package com.firefly.transactional.shared.config;

import com.firefly.transactional.saga.config.SagaSpecificProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Compatibility layer that maps old property names to new generic properties.
 * This ensures backward compatibility with existing configurations while
 * encouraging migration to the new generic property structure.
 * 
 * <p>
 * Mapping rules:
 * <ul>
 *   <li>{@code firefly.saga.engine.*} → {@code firefly.tx.*} (generic properties)</li>
 *   <li>{@code firefly.saga.engine.*} → {@code firefly.tx.saga.*} (SAGA-specific properties)</li>
 *   <li>{@code firefly.saga.persistence.*} → {@code firefly.tx.persistence.*}</li>
 * </ul>
 */
@Component
public class TransactionalEnginePropertiesCompatibility {
    
    private static final Logger log = LoggerFactory.getLogger(TransactionalEnginePropertiesCompatibility.class);
    
    private final Environment environment;
    private final Binder binder;
    
    public TransactionalEnginePropertiesCompatibility(Environment environment) {
        this.environment = environment;
        this.binder = Binder.get(environment);
    }
    
    /**
     * Creates a merged TransactionalEngineProperties instance that combines
     * new generic properties with backward-compatible legacy properties.
     */
    public TransactionalEngineProperties createMergedProperties() {
        TransactionalEngineProperties properties = new TransactionalEngineProperties();
        
        // Bind new generic properties first
        binder.bind("firefly.tx", TransactionalEngineProperties.class)
                .ifBound(bound -> copyGenericProperties(bound, properties));
        
        // Apply backward compatibility mappings from legacy properties
        applyLegacyMappings(properties);
        
        return properties;
    }
    
    /**
     * Creates a merged SagaSpecificProperties instance that combines
     * new SAGA-specific properties with backward-compatible legacy properties.
     */
    public SagaSpecificProperties createMergedSagaProperties() {
        SagaSpecificProperties properties = new SagaSpecificProperties();
        
        // Bind new SAGA-specific properties first
        binder.bind("firefly.tx.saga", SagaSpecificProperties.class)
                .ifBound(bound -> copySagaProperties(bound, properties));
        
        // Apply backward compatibility mappings from legacy properties
        applyLegacySagaMappings(properties);
        
        return properties;
    }
    
    /**
     * Creates a TccSpecificProperties instance.
     * No legacy mapping needed as TCC is new.
     */
    public TccSpecificProperties createTccProperties() {
        TccSpecificProperties properties = new TccSpecificProperties();
        
        binder.bind("firefly.tx.tcc", TccSpecificProperties.class)
                .ifBound(bound -> copyTccProperties(bound, properties));
        
        return properties;
    }
    
    private void copyGenericProperties(TransactionalEngineProperties source, TransactionalEngineProperties target) {
        target.setDefaultTimeout(source.getDefaultTimeout());
        target.setMaxConcurrentTransactions(source.getMaxConcurrentTransactions());
        target.setObservability(source.getObservability());
        target.setValidation(source.getValidation());
        target.setPersistence(source.getPersistence());
    }
    
    private void copySagaProperties(SagaSpecificProperties source, SagaSpecificProperties target) {
        target.setCompensationPolicy(source.getCompensationPolicy());
        target.setAutoOptimizationEnabled(source.isAutoOptimizationEnabled());
        target.setMaxConcurrentSagas(source.getMaxConcurrentSagas());
        target.setContext(source.getContext());
        target.setBackpressure(source.getBackpressure());
        target.setCompensation(source.getCompensation());
    }
    
    private void copyTccProperties(TccSpecificProperties source, TccSpecificProperties target) {
        target.setDefaultTimeout(source.getDefaultTimeout());
        target.setRetryEnabled(source.isRetryEnabled());
        target.setMaxRetries(source.getMaxRetries());
        target.setBackoffMs(source.getBackoffMs());
        target.setMaxConcurrentTccs(source.getMaxConcurrentTccs());
        target.setStrictOrdering(source.isStrictOrdering());
        target.setParallelExecution(source.isParallelExecution());
        target.setParticipant(source.getParticipant());
        target.setRecovery(source.getRecovery());
    }
    
    private void applyLegacyMappings(TransactionalEngineProperties properties) {
        boolean hasLegacyProperties = false;
        
        // Map firefly.saga.engine.default-timeout → firefly.tx.default-timeout
        // Only apply if new property is not already set
        if (environment.containsProperty("firefly.saga.engine.default-timeout") &&
            !environment.containsProperty("firefly.tx.default-timeout")) {
            String timeoutStr = environment.getProperty("firefly.saga.engine.default-timeout");
            if (timeoutStr != null) {
                try {
                    Duration timeout = Duration.parse(timeoutStr);
                    properties.setDefaultTimeout(timeout);
                    hasLegacyProperties = true;
                } catch (Exception e) {
                    // If parsing fails, try using the environment's converter (for Spring Boot contexts)
                    try {
                        Duration timeout = environment.getProperty("firefly.saga.engine.default-timeout", Duration.class);
                        if (timeout != null) {
                            properties.setDefaultTimeout(timeout);
                            hasLegacyProperties = true;
                        }
                    } catch (Exception ignored) {
                        // Ignore conversion errors in test environments
                    }
                }
            }
        }
        
        // Map firefly.saga.engine.max-concurrent-sagas → firefly.tx.max-concurrent-transactions
        if (environment.containsProperty("firefly.saga.engine.max-concurrent-sagas")) {
            Integer maxConcurrent = environment.getProperty("firefly.saga.engine.max-concurrent-sagas", Integer.class);
            if (maxConcurrent != null) {
                properties.setMaxConcurrentTransactions(maxConcurrent);
                hasLegacyProperties = true;
            }
        }
        
        // Map persistence properties
        applyLegacyPersistenceMappings(properties);
        
        // Map observability properties
        applyLegacyObservabilityMappings(properties);
        
        // Map validation properties
        applyLegacyValidationMappings(properties);
        
        if (hasLegacyProperties) {
            log.warn("Using legacy property names. Please migrate to the new 'firefly.tx.*' property structure. " +
                    "See documentation for migration guide.");
        }
    }
    
    private void applyLegacySagaMappings(SagaSpecificProperties properties) {
        boolean hasLegacyProperties = false;
        
        // Map firefly.saga.engine.compensation-policy → firefly.tx.saga.compensation-policy
        String compensationPolicy = environment.getProperty("firefly.saga.engine.compensation-policy");
        if (compensationPolicy != null) {
            try {
                properties.setCompensationPolicy(
                    com.firefly.transactional.saga.engine.SagaEngine.CompensationPolicy.valueOf(compensationPolicy)
                );
                hasLegacyProperties = true;
            } catch (IllegalArgumentException e) {
                log.warn("Invalid compensation policy value: {}", compensationPolicy);
            }
        }
        
        // Map firefly.saga.engine.auto-optimization-enabled → firefly.tx.saga.auto-optimization-enabled
        if (environment.containsProperty("firefly.saga.engine.auto-optimization-enabled")) {
            Boolean autoOptimization = environment.getProperty("firefly.saga.engine.auto-optimization-enabled", Boolean.class);
            if (autoOptimization != null) {
                properties.setAutoOptimizationEnabled(autoOptimization);
                hasLegacyProperties = true;
            }
        }
        
        // Map firefly.saga.engine.max-concurrent-sagas → firefly.tx.saga.max-concurrent-sagas
        if (environment.containsProperty("firefly.saga.engine.max-concurrent-sagas")) {
            Integer maxConcurrent = environment.getProperty("firefly.saga.engine.max-concurrent-sagas", Integer.class);
            if (maxConcurrent != null) {
                properties.setMaxConcurrentSagas(maxConcurrent);
                hasLegacyProperties = true;
            }
        }
        
        if (hasLegacyProperties) {
            log.warn("Using legacy SAGA property names. Please migrate to the new 'firefly.tx.saga.*' property structure.");
        }
    }
    
    private void applyLegacyPersistenceMappings(TransactionalEngineProperties properties) {
        // Map both firefly.saga.engine.persistence.* and firefly.saga.persistence.*
        String[] legacyPrefixes = {"firefly.saga.engine.persistence", "firefly.saga.persistence"};
        
        for (String prefix : legacyPrefixes) {
            // Only apply legacy persistence.enabled if new property is not set
            if (environment.containsProperty(prefix + ".enabled") &&
                !environment.containsProperty("firefly.tx.persistence.enabled")) {
                Boolean enabled = environment.getProperty(prefix + ".enabled", Boolean.class);
                if (enabled != null) {
                    properties.getPersistence().setEnabled(enabled);
                }
            }

            // Only apply legacy persistence.provider if new property is not set
            if (environment.containsProperty(prefix + ".provider") &&
                !environment.containsProperty("firefly.tx.persistence.provider")) {
                String provider = environment.getProperty(prefix + ".provider");
                if (provider != null) {
                    properties.getPersistence().setProvider(provider);
                }
            }
            
            // Map Redis properties
            if (environment.containsProperty(prefix + ".redis.host")) {
                String host = environment.getProperty(prefix + ".redis.host");
                if (host != null) {
                    properties.getPersistence().getRedis().setHost(host);
                }
            }
            
            if (environment.containsProperty(prefix + ".redis.port")) {
                Integer port = environment.getProperty(prefix + ".redis.port", Integer.class);
                if (port != null) {
                    properties.getPersistence().getRedis().setPort(port);
                }
            }
            
            if (environment.containsProperty(prefix + ".redis.database")) {
                Integer database = environment.getProperty(prefix + ".redis.database", Integer.class);
                if (database != null) {
                    properties.getPersistence().getRedis().setDatabase(database);
                }
            }
            
            if (environment.containsProperty(prefix + ".redis.key-prefix")) {
                String keyPrefix = environment.getProperty(prefix + ".redis.key-prefix");
                if (keyPrefix != null) {
                    properties.getPersistence().getRedis().setKeyPrefix(keyPrefix);
                }
            }
        }
    }
    
    private void applyLegacyObservabilityMappings(TransactionalEngineProperties properties) {
        if (environment.containsProperty("firefly.saga.engine.observability.metrics-enabled")) {
            Boolean metricsEnabled = environment.getProperty("firefly.saga.engine.observability.metrics-enabled", Boolean.class);
            if (metricsEnabled != null) {
                properties.getObservability().setMetricsEnabled(metricsEnabled);
            }
        }
    }
    
    private void applyLegacyValidationMappings(TransactionalEngineProperties properties) {
        if (environment.containsProperty("firefly.saga.engine.validation.enabled")) {
            Boolean validationEnabled = environment.getProperty("firefly.saga.engine.validation.enabled", Boolean.class);
            if (validationEnabled != null) {
                properties.getValidation().setEnabled(validationEnabled);
            }
        }
    }
}
