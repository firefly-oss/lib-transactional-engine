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

package com.firefly.transactional.tcc.config;

import com.firefly.transactional.shared.engine.CompensationPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for TCC Composition functionality.
 * <p>
 * Provides comprehensive configuration options for TCC composition behavior,
 * performance tuning, monitoring, and development tools.
 * <p>
 * Example configuration:
 * <pre>
 * firefly:
 *   tx:
 *     tcc:
 *       composition:
 *         enabled: true
 *         default-timeout: 30s
 *         max-parallel-tccs: 10
 *         validation:
 *           strict-mode: true
 *           fail-fast: true
 *         metrics:
 *           enabled: true
 *           detailed: true
 *         templates:
 *           enabled: true
 *           preload: true
 * </pre>
 */
@ConfigurationProperties(prefix = "firefly.tx.tcc.composition")
public class TccCompositionProperties {
    
    /**
     * Whether TCC composition functionality is enabled.
     */
    private boolean enabled = true;
    
    /**
     * Default timeout for TCC execution within compositions.
     */
    private Duration defaultTimeout = Duration.ofSeconds(30);
    
    /**
     * Maximum number of TCCs that can execute in parallel within a composition.
     */
    private int maxParallelTccs = 10;
    
    /**
     * Default compensation policy for compositions.
     */
    private CompensationPolicy defaultCompensationPolicy = CompensationPolicy.GROUPED_PARALLEL;
    
    /**
     * Whether to enable strict validation during composition building.
     */
    private boolean strictValidation = true;
    
    /**
     * Whether to fail fast on validation errors during composition building.
     */
    private boolean failFast = true;
    
    @NestedConfigurationProperty
    private ValidationProperties validation = new ValidationProperties();
    
    @NestedConfigurationProperty
    private MetricsProperties metrics = new MetricsProperties();
    
    @NestedConfigurationProperty
    private TemplatesProperties templates = new TemplatesProperties();
    
    @NestedConfigurationProperty
    private HealthProperties health = new HealthProperties();
    
    @NestedConfigurationProperty
    private DevToolsProperties devTools = new DevToolsProperties();
    
    @NestedConfigurationProperty
    private PerformanceProperties performance = new PerformanceProperties();
    
    // Getters and setters
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    
    public Duration getDefaultTimeout() { return defaultTimeout; }
    public void setDefaultTimeout(Duration defaultTimeout) { this.defaultTimeout = defaultTimeout; }
    
    public int getMaxParallelTccs() { return maxParallelTccs; }
    public void setMaxParallelTccs(int maxParallelTccs) { this.maxParallelTccs = maxParallelTccs; }
    
    public CompensationPolicy getDefaultCompensationPolicy() { return defaultCompensationPolicy; }
    public void setDefaultCompensationPolicy(CompensationPolicy defaultCompensationPolicy) { 
        this.defaultCompensationPolicy = defaultCompensationPolicy; 
    }
    
    public boolean isStrictValidation() { return strictValidation; }
    public void setStrictValidation(boolean strictValidation) { this.strictValidation = strictValidation; }
    
    public boolean isFailFast() { return failFast; }
    public void setFailFast(boolean failFast) { this.failFast = failFast; }
    
    public ValidationProperties getValidation() { return validation; }
    public void setValidation(ValidationProperties validation) { this.validation = validation; }
    
    public MetricsProperties getMetrics() { return metrics; }
    public void setMetrics(MetricsProperties metrics) { this.metrics = metrics; }
    
    public TemplatesProperties getTemplates() { return templates; }
    public void setTemplates(TemplatesProperties templates) { this.templates = templates; }
    
    public HealthProperties getHealth() { return health; }
    public void setHealth(HealthProperties health) { this.health = health; }
    
    public DevToolsProperties getDevTools() { return devTools; }
    public void setDevTools(DevToolsProperties devTools) { this.devTools = devTools; }
    
    public PerformanceProperties getPerformance() { return performance; }
    public void setPerformance(PerformanceProperties performance) { this.performance = performance; }
    
    /**
     * Validation-specific configuration properties.
     */
    public static class ValidationProperties {
        private boolean enabled = true;
        private boolean strictMode = true;
        private boolean failFast = true;
        private boolean validateDataMappings = true;
        private boolean validateDependencies = true;
        private boolean validateParallelConstraints = true;
        private boolean validatePhaseConsistency = true;
        
        // Getters and setters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public boolean isStrictMode() { return strictMode; }
        public void setStrictMode(boolean strictMode) { this.strictMode = strictMode; }
        
        public boolean isFailFast() { return failFast; }
        public void setFailFast(boolean failFast) { this.failFast = failFast; }
        
        public boolean isValidateDataMappings() { return validateDataMappings; }
        public void setValidateDataMappings(boolean validateDataMappings) { this.validateDataMappings = validateDataMappings; }
        
        public boolean isValidateDependencies() { return validateDependencies; }
        public void setValidateDependencies(boolean validateDependencies) { this.validateDependencies = validateDependencies; }
        
        public boolean isValidateParallelConstraints() { return validateParallelConstraints; }
        public void setValidateParallelConstraints(boolean validateParallelConstraints) { this.validateParallelConstraints = validateParallelConstraints; }
        
        public boolean isValidatePhaseConsistency() { return validatePhaseConsistency; }
        public void setValidatePhaseConsistency(boolean validatePhaseConsistency) { this.validatePhaseConsistency = validatePhaseConsistency; }
    }
    
    /**
     * Metrics collection configuration properties.
     */
    public static class MetricsProperties {
        private boolean enabled = true;
        private boolean detailed = false;
        private Duration retentionPeriod = Duration.ofHours(24);
        private int maxStoredResults = 1000;
        private boolean trackPhaseMetrics = true;
        
        // Getters and setters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public boolean isDetailed() { return detailed; }
        public void setDetailed(boolean detailed) { this.detailed = detailed; }
        
        public Duration getRetentionPeriod() { return retentionPeriod; }
        public void setRetentionPeriod(Duration retentionPeriod) { this.retentionPeriod = retentionPeriod; }
        
        public int getMaxStoredResults() { return maxStoredResults; }
        public void setMaxStoredResults(int maxStoredResults) { this.maxStoredResults = maxStoredResults; }
        
        public boolean isTrackPhaseMetrics() { return trackPhaseMetrics; }
        public void setTrackPhaseMetrics(boolean trackPhaseMetrics) { this.trackPhaseMetrics = trackPhaseMetrics; }
    }
    
    /**
     * Template system configuration properties.
     */
    public static class TemplatesProperties {
        private boolean enabled = true;
        private boolean preload = true;
        private Map<String, String> customTemplates = new HashMap<>();
        
        // Getters and setters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public boolean isPreload() { return preload; }
        public void setPreload(boolean preload) { this.preload = preload; }
        
        public Map<String, String> getCustomTemplates() { return customTemplates; }
        public void setCustomTemplates(Map<String, String> customTemplates) { this.customTemplates = customTemplates; }
    }
    
    /**
     * Health check configuration properties.
     */
    public static class HealthProperties {
        private boolean enabled = true;
        private Duration checkInterval = Duration.ofMinutes(1);
        private int failureThreshold = 5;
        
        // Getters and setters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public Duration getCheckInterval() { return checkInterval; }
        public void setCheckInterval(Duration checkInterval) { this.checkInterval = checkInterval; }
        
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
    }
    
    /**
     * Development tools configuration properties.
     */
    public static class DevToolsProperties {
        private boolean enabled = false;
        private boolean visualizationEnabled = false;
        private boolean debugLogging = false;
        private String visualizationEndpoint = "/actuator/tcc-composition/visualize";
        
        // Getters and setters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        
        public boolean isVisualizationEnabled() { return visualizationEnabled; }
        public void setVisualizationEnabled(boolean visualizationEnabled) { this.visualizationEnabled = visualizationEnabled; }
        
        public boolean isDebugLogging() { return debugLogging; }
        public void setDebugLogging(boolean debugLogging) { this.debugLogging = debugLogging; }
        
        public String getVisualizationEndpoint() { return visualizationEndpoint; }
        public void setVisualizationEndpoint(String visualizationEndpoint) { this.visualizationEndpoint = visualizationEndpoint; }
    }
    
    /**
     * Performance optimization configuration properties.
     */
    public static class PerformanceProperties {
        private boolean tccPreloading = true;
        private boolean executionPlanning = true;
        private int resourcePoolSize = 20;
        private Duration planningTimeout = Duration.ofSeconds(5);
        private boolean phaseOptimization = true;
        
        // Getters and setters
        public boolean isTccPreloading() { return tccPreloading; }
        public void setTccPreloading(boolean tccPreloading) { this.tccPreloading = tccPreloading; }
        
        public boolean isExecutionPlanning() { return executionPlanning; }
        public void setExecutionPlanning(boolean executionPlanning) { this.executionPlanning = executionPlanning; }
        
        public int getResourcePoolSize() { return resourcePoolSize; }
        public void setResourcePoolSize(int resourcePoolSize) { this.resourcePoolSize = resourcePoolSize; }
        
        public Duration getPlanningTimeout() { return planningTimeout; }
        public void setPlanningTimeout(Duration planningTimeout) { this.planningTimeout = planningTimeout; }
        
        public boolean isPhaseOptimization() { return phaseOptimization; }
        public void setPhaseOptimization(boolean phaseOptimization) { this.phaseOptimization = phaseOptimization; }
    }
}
