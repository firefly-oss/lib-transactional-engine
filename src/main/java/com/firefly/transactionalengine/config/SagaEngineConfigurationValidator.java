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

package com.firefly.transactionalengine.config;

import com.firefly.transactionalengine.engine.backpressure.BackpressureStrategyFactory;
import com.firefly.transactionalengine.engine.compensation.CompensationErrorHandlerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates saga engine configuration properties at startup.
 * Provides warnings for potentially problematic configurations and
 * fails fast for invalid configurations.
 */
@Component
public class SagaEngineConfigurationValidator {
    
    private static final Logger log = LoggerFactory.getLogger(SagaEngineConfigurationValidator.class);
    
    private final SagaEngineProperties properties;
    
    public SagaEngineConfigurationValidator(SagaEngineProperties properties) {
        this.properties = properties;
    }
    
    /**
     * Validates configuration after application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validateConfiguration() {
        log.info("Validating Saga Engine configuration...");
        
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        
        validateBasicProperties(warnings, errors);
        validateContextProperties(warnings, errors);
        validateBackpressureProperties(warnings, errors);
        validateCompensationProperties(warnings, errors);
        validateObservabilityProperties(warnings, errors);
        validateValidationProperties(warnings, errors);
        
        // Log warnings
        if (!warnings.isEmpty()) {
            log.warn("Saga Engine configuration warnings:");
            warnings.forEach(warning -> log.warn("  - {}", warning));
        }
        
        // Fail on errors
        if (!errors.isEmpty()) {
            log.error("Saga Engine configuration errors:");
            errors.forEach(error -> log.error("  - {}", error));
            throw new IllegalStateException("Invalid Saga Engine configuration. See errors above.");
        }
        
        log.info("Saga Engine configuration validation completed successfully");
    }
    
    private void validateBasicProperties(List<String> warnings, List<String> errors) {
        // Validate compensation policy
        if (properties.getCompensationPolicy() == null) {
            errors.add("Compensation policy cannot be null");
        }
        
        // Validate timeout
        if (properties.getDefaultTimeout() == null || properties.getDefaultTimeout().isNegative()) {
            errors.add("Default timeout must be positive");
        } else if (properties.getDefaultTimeout().toMinutes() > 60) {
            warnings.add("Default timeout is very long (" + properties.getDefaultTimeout().toMinutes() + " minutes). Consider shorter timeouts for better responsiveness.");
        }
        
        // Validate max concurrent sagas
        if (properties.getMaxConcurrentSagas() <= 0) {
            errors.add("Max concurrent sagas must be positive");
        } else if (properties.getMaxConcurrentSagas() > 1000) {
            warnings.add("Max concurrent sagas is very high (" + properties.getMaxConcurrentSagas() + "). This may impact system performance.");
        }
    }
    
    private void validateContextProperties(List<String> warnings, List<String> errors) {
        SagaEngineProperties.ContextProperties context = properties.getContext();
        
        if (context.getExecutionMode() == null) {
            errors.add("Context execution mode cannot be null");
        }
        
        // Warn about potential performance implications
        if (!context.isOptimizationEnabled()) {
            warnings.add("Context optimization is disabled. This may impact performance for sequential sagas.");
        }
    }
    
    private void validateBackpressureProperties(List<String> warnings, List<String> errors) {
        SagaEngineProperties.BackpressureProperties backpressure = properties.getBackpressure();
        
        // Validate strategy
        if (backpressure.getStrategy() == null || backpressure.getStrategy().trim().isEmpty()) {
            errors.add("Backpressure strategy cannot be null or empty");
        } else if (!BackpressureStrategyFactory.isStrategyRegistered(backpressure.getStrategy())) {
            errors.add("Unknown backpressure strategy: " + backpressure.getStrategy() + 
                      ". Available strategies: " + String.join(", ", BackpressureStrategyFactory.getAvailableStrategies()));
        }
        
        // Validate concurrency
        if (backpressure.getConcurrency() <= 0) {
            errors.add("Backpressure concurrency must be positive");
        } else if (backpressure.getConcurrency() > 100) {
            warnings.add("Backpressure concurrency is very high (" + backpressure.getConcurrency() + "). This may overwhelm downstream systems.");
        }
        
        // Validate batch size
        if (backpressure.getBatchSize() <= 0) {
            errors.add("Backpressure batch size must be positive");
        } else if (backpressure.getBatchSize() > 1000) {
            warnings.add("Backpressure batch size is very large (" + backpressure.getBatchSize() + "). This may impact memory usage.");
        }
        
        // Validate timeout
        if (backpressure.getTimeout() == null || backpressure.getTimeout().isNegative()) {
            errors.add("Backpressure timeout must be positive");
        }
    }
    
    private void validateCompensationProperties(List<String> warnings, List<String> errors) {
        SagaEngineProperties.CompensationProperties compensation = properties.getCompensation();
        
        // Validate error handler
        if (compensation.getErrorHandler() == null || compensation.getErrorHandler().trim().isEmpty()) {
            errors.add("Compensation error handler cannot be null or empty");
        } else if (!CompensationErrorHandlerFactory.isHandlerRegistered(compensation.getErrorHandler())) {
            errors.add("Unknown compensation error handler: " + compensation.getErrorHandler() + 
                      ". Available handlers: " + String.join(", ", CompensationErrorHandlerFactory.getAvailableHandlers()));
        }
        
        // Validate max retries
        if (compensation.getMaxRetries() < 0) {
            errors.add("Compensation max retries cannot be negative");
        } else if (compensation.getMaxRetries() > 10) {
            warnings.add("Compensation max retries is very high (" + compensation.getMaxRetries() + "). This may cause long delays on failures.");
        }
        
        // Validate retry delay
        if (compensation.getRetryDelay() == null || compensation.getRetryDelay().isNegative()) {
            errors.add("Compensation retry delay must be positive");
        }
    }
    
    private void validateObservabilityProperties(List<String> warnings, List<String> errors) {
        SagaEngineProperties.ObservabilityProperties observability = properties.getObservability();
        
        // Validate metrics interval
        if (observability.getMetricsInterval() == null || observability.getMetricsInterval().isNegative()) {
            errors.add("Metrics interval must be positive");
        } else if (observability.getMetricsInterval().toSeconds() < 5) {
            warnings.add("Metrics interval is very short (" + observability.getMetricsInterval().toSeconds() + " seconds). This may impact performance.");
        }
        
        // Warn about disabled observability
        if (!observability.isMetricsEnabled() && !observability.isTracingEnabled()) {
            warnings.add("Both metrics and tracing are disabled. This will limit observability into saga execution.");
        }
    }
    
    private void validateValidationProperties(List<String> warnings, List<String> errors) {
        SagaEngineProperties.ValidationProperties validation = properties.getValidation();
        
        // Warn about disabled validation
        if (!validation.isEnabled()) {
            warnings.add("Runtime validation is disabled. This may allow invalid saga configurations to cause runtime errors.");
        }
        
        if (!validation.isValidateAtStartup()) {
            warnings.add("Startup validation is disabled. Invalid saga definitions may not be detected until runtime.");
        }
    }
    
    /**
     * Validates configuration programmatically (useful for testing).
     * 
     * @return validation result
     */
    public ValidationResult validate() {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        
        validateBasicProperties(warnings, errors);
        validateContextProperties(warnings, errors);
        validateBackpressureProperties(warnings, errors);
        validateCompensationProperties(warnings, errors);
        validateObservabilityProperties(warnings, errors);
        validateValidationProperties(warnings, errors);
        
        return new ValidationResult(warnings, errors);
    }
    
    /**
     * Result of configuration validation.
     */
    public static class ValidationResult {
        private final List<String> warnings;
        private final List<String> errors;
        
        public ValidationResult(List<String> warnings, List<String> errors) {
            this.warnings = List.copyOf(warnings);
            this.errors = List.copyOf(errors);
        }
        
        public List<String> getWarnings() {
            return warnings;
        }
        
        public List<String> getErrors() {
            return errors;
        }
        
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
        
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
        
        public boolean isValid() {
            return !hasErrors();
        }
    }
}
