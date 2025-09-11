# Architectural Improvements

This document provides detailed information about the architectural improvements made to the Firefly Transactional Engine. These improvements enhance maintainability, performance, and reliability while maintaining backward compatibility.

## Overview

The improvements focus on seven key areas:

1. **Saga Execution Orchestrator** - Extracted pure execution logic
2. **Pluggable Backpressure Strategies** - Strategy pattern for reactive optimizations
3. **Enhanced Compensation Error Handling** - Sophisticated error handling strategies
4. **Optimized Context Creation** - Factory pattern with explicit execution modes
5. **Comprehensive Metrics Collection** - Advanced observability and monitoring
6. **Runtime Validation** - Comprehensive validation service
7. **Spring Boot Integration** - Enhanced configuration and actuator endpoints

## 1. Saga Execution Orchestrator

### Problem Solved
The original `SagaEngine` was handling too many responsibilities including orchestration, compensation, and event publishing, making it difficult to maintain and test.

### Solution
Extracted pure execution orchestration logic into a dedicated component:

```java
@Component
public class SagaExecutionOrchestrator {
    
    public Mono<ExecutionResult> orchestrate(
        SagaDefinition saga, 
        Map<String, Object> stepInputs, 
        SagaContext context
    ) {
        if (saga.steps == null || saga.steps.isEmpty()) {
            return Mono.just(new ExecutionResult(List.of(), null, null));
        }

        return buildExecutionDAG(saga.steps)
            .flatMap(layers -> executeLayersSequentially(layers, stepInputs, context))
            .onErrorResume(error -> handleExecutionError(error, context));
    }
    
    public static class ExecutionResult {
        private final List<String> completedSteps;
        private final String failedStep;
        private final Throwable error;
        
        public boolean isSuccess() {
            return failedStep == null && error == null;
        }
        
        // Getters...
    }
}
```

### Benefits
- **Separation of Concerns**: Clear distinction between orchestration and engine management
- **Testability**: Easier to unit test execution logic in isolation
- **Maintainability**: Simpler code structure and reduced complexity
- **Reusability**: Orchestrator can be used independently

## 2. Pluggable Backpressure Strategies

### Problem Solved
The original reactive stream optimizations had monolithic backpressure handling that was difficult to customize or extend.

### Solution
Implemented strategy pattern with pluggable backpressure implementations:

```java
public interface BackpressureStrategy {
    <T, R> Flux<R> applyBackpressure(Flux<T> source, Function<T, Mono<R>> processor);
    String getStrategyName();
}

// Built-in implementations
public class BatchedBackpressureStrategy implements BackpressureStrategy {
    private final BackpressureConfig config;
    
    @Override
    public <T, R> Flux<R> applyBackpressure(Flux<T> source, Function<T, Mono<R>> processor) {
        return source
            .buffer(config.batchSize())
            .flatMap(batch -> 
                Flux.fromIterable(batch)
                    .flatMap(processor, config.concurrency())
                    .timeout(config.timeout())
            );
    }
}

public class AdaptiveBackpressureStrategy implements BackpressureStrategy {
    // Dynamically adjusts concurrency based on performance metrics
}

public class CircuitBreakerBackpressureStrategy implements BackpressureStrategy {
    // Implements circuit breaker pattern for fault tolerance
}
```

### Configuration
```java
public record BackpressureConfig(
    int concurrency,
    int batchSize,
    Duration timeout,
    int retryAttempts
) {
    public static BackpressureConfig defaultConfig() {
        return new BackpressureConfig(10, 50, Duration.ofSeconds(30), 3);
    }
    
    public static BackpressureConfig highThroughput() {
        return new BackpressureConfig(20, 100, Duration.ofSeconds(60), 2);
    }
    
    public static BackpressureConfig lowLatency() {
        return new BackpressureConfig(5, 10, Duration.ofSeconds(5), 1);
    }
}
```

### Factory Pattern
```java
public class BackpressureStrategyFactory {
    private static final Map<String, BackpressureStrategy> strategies = new ConcurrentHashMap<>();
    
    static {
        registerStrategy("batched", new BatchedBackpressureStrategy(BackpressureConfig.defaultConfig()));
        registerStrategy("adaptive", new AdaptiveBackpressureStrategy(BackpressureConfig.defaultConfig()));
        registerStrategy("circuit-breaker", new CircuitBreakerBackpressureStrategy(BackpressureConfig.defaultConfig()));
    }
    
    public static BackpressureStrategy getStrategy(String name) {
        BackpressureStrategy strategy = strategies.get(name);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown backpressure strategy: " + name);
        }
        return strategy;
    }
    
    public static void registerStrategy(String name, BackpressureStrategy strategy) {
        strategies.put(name, strategy);
    }
}
```

### Benefits
- **Flexibility**: Easy to switch between different backpressure strategies
- **Extensibility**: Custom strategies can be easily added
- **Performance**: Optimized strategies for different use cases
- **Configuration**: Configurable parameters for fine-tuning

## 3. Enhanced Compensation Error Handling

### Problem Solved
The original compensation logic was silently swallowing errors, making it difficult to diagnose and handle compensation failures properly.

### Solution
Implemented sophisticated error handling strategies with configurable behavior:

```java
public interface CompensationErrorHandler {
    Mono<CompensationErrorResult> handleError(
        String stepId, 
        Throwable error, 
        SagaContext context, 
        int attemptNumber
    );
    
    boolean canHandle(Throwable error, String stepId, SagaContext context);
    String getStrategyName();
    
    enum CompensationErrorResult {
        CONTINUE,           // Continue with next compensation step
        RETRY,             // Retry the current compensation step
        FAIL_SAGA,         // Fail the entire saga
        SKIP_STEP,         // Skip current step and continue
        MARK_COMPENSATED   // Mark step as compensated and continue
    }
}
```

### Built-in Handlers

#### Log and Continue Handler
```java
public class LogAndContinueErrorHandler implements CompensationErrorHandler {
    @Override
    public Mono<CompensationErrorResult> handleError(String stepId, Throwable error, SagaContext context, int attemptNumber) {
        log.warn("Compensation error in step '{}' (attempt {}): {}", stepId, attemptNumber, error.getMessage());
        return Mono.just(CompensationErrorResult.CONTINUE);
    }
}
```

#### Retry with Backoff Handler
```java
public class RetryWithBackoffErrorHandler implements CompensationErrorHandler {
    private final int maxAttempts;
    private final boolean exponentialBackoff;
    private final Set<Class<? extends Throwable>> retryableExceptions;
    
    @Override
    public Mono<CompensationErrorResult> handleError(String stepId, Throwable error, SagaContext context, int attemptNumber) {
        if (attemptNumber >= maxAttempts) {
            return Mono.just(CompensationErrorResult.CONTINUE);
        }
        
        if (canHandle(error, stepId, context)) {
            return Mono.just(CompensationErrorResult.RETRY);
        }
        
        return Mono.just(CompensationErrorResult.CONTINUE);
    }
    
    public static RetryWithBackoffErrorHandler forNetworkErrors(int maxAttempts) {
        return new RetryWithBackoffErrorHandler(maxAttempts, true, Set.of(
            ConnectException.class,
            SocketTimeoutException.class,
            IOException.class
        ));
    }
}
```

#### Composite Handler
```java
public class CompositeCompensationErrorHandler implements CompensationErrorHandler {
    private final List<CompensationErrorHandler> handlers;
    private final CompensationErrorHandler fallbackHandler;
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        public Builder failOn(Class<? extends Throwable>... errorTypes) {
            handlers.add(FailFastErrorHandler.forCriticalErrors(errorTypes));
            return this;
        }
        
        public Builder retryOn(Class<? extends Throwable>... errorTypes) {
            handlers.add(RetryWithBackoffErrorHandler.forRetryableErrors(3, errorTypes));
            return this;
        }
        
        public Builder withFallback(CompensationErrorHandler fallback) {
            this.fallbackHandler = fallback;
            return this;
        }
    }
}
```

### Usage Examples
```java
// Simple configuration
CompensationErrorHandler handler = CompensationErrorHandlerFactory.getHandler("robust");

// Custom composite handler
CompensationErrorHandler custom = CompositeCompensationErrorHandler.builder()
    .failOn(IllegalStateException.class, SecurityException.class)
    .retryOn(ConnectException.class, SocketTimeoutException.class)
    .withFallback(new LogAndContinueErrorHandler())
    .build();
```

### Benefits
- **Visibility**: Proper error handling and logging
- **Flexibility**: Multiple strategies for different error types
- **Reliability**: Configurable retry and fallback mechanisms
- **Composability**: Combine multiple handlers for complex scenarios

## 4. Optimized Context Creation

### Problem Solved
The original context creation was not optimized for different execution scenarios and lacked explicit configuration options.

### Solution
Enhanced `SagaContextFactory` with explicit execution modes and factory methods:

```java
public class SagaContextFactory {
    
    public enum ExecutionMode {
        SEQUENTIAL,        // Sequential execution, lowest memory usage
        CONCURRENT,        // Concurrent execution with moderate parallelism
        AUTO,             // Automatically choose based on saga characteristics
        HIGH_PERFORMANCE, // Maximum parallelism and performance
        LOW_MEMORY        // Optimized for minimal memory footprint
    }
    
    // Factory methods for common scenarios
    public static SagaContext createHighPerformance(String sagaName) {
        return builder()
            .sagaName(sagaName)
            .executionMode(ExecutionMode.HIGH_PERFORMANCE)
            .optimizationEnabled(true)
            .build();
    }
    
    public static SagaContext createLowMemory(String sagaName) {
        return builder()
            .sagaName(sagaName)
            .executionMode(ExecutionMode.LOW_MEMORY)
            .optimizationEnabled(false)
            .build();
    }
    
    public static SagaContext createConcurrent(String sagaName, int maxConcurrency) {
        return builder()
            .sagaName(sagaName)
            .executionMode(ExecutionMode.CONCURRENT)
            .maxConcurrency(maxConcurrency)
            .optimizationEnabled(true)
            .build();
    }
}
```

### Benefits
- **Performance**: Optimized contexts for different scenarios
- **Memory Efficiency**: Low-memory mode for resource-constrained environments
- **Flexibility**: Explicit control over execution characteristics
- **Ease of Use**: Factory methods for common use cases

## 5. Comprehensive Metrics Collection

### Problem Solved
Limited observability into saga execution performance and behavior.

### Solution
Advanced metrics collection with detailed tracking:

```java
@Component
public class SagaMetricsCollector {
    
    // Saga lifecycle metrics
    public void recordSagaStarted(String sagaName, String correlationId);
    public void recordSagaCompleted(String sagaName, String correlationId, Duration executionTime);
    public void recordSagaFailed(String sagaName, String correlationId, Throwable error);
    public void recordSagaCompensated(String sagaName, String correlationId);
    
    // Step lifecycle metrics
    public void recordStepStarted(String stepId, String sagaName, String correlationId);
    public void recordStepSucceeded(String stepId, String sagaName, String correlationId);
    public void recordStepFailed(String stepId, String sagaName, String correlationId, Throwable error);
    public void recordStepCompensated(String stepId, String sagaName, String correlationId);
    
    // Optimization metrics
    public void recordOptimizedContextCreated(String sagaName);
    public void recordStandardContextCreated(String sagaName);
    
    // Backpressure metrics
    public void recordBackpressureApplied(String strategy, int itemsProcessed);
    
    // Thread-safe metrics snapshot
    public MetricsSnapshot getMetrics();
    
    public static class MetricsSnapshot {
        // Saga metrics
        public long getSagasStarted();
        public long getSagasCompleted();
        public long getSagasFailed();
        public long getSagasCompensated();
        public long getActiveSagas();
        public double getSagaSuccessRate();
        
        // Step metrics
        public long getStepsExecuted();
        public long getStepsSucceeded();
        public long getStepsFailed();
        public long getStepsCompensated();
        public long getActiveSteps();
        public double getStepSuccessRate();
        
        // Performance metrics
        public long getAverageExecutionTimeMs();
        public long getMaxExecutionTimeMs();
        public long getMinExecutionTimeMs();
        
        // Optimization metrics
        public long getOptimizedContextsCreated();
        public long getStandardContextsCreated();
        public double getOptimizationRate();
        
        // Backpressure metrics
        public long getBackpressureApplications();
    }
}
```

### Spring Boot Actuator Integration
```java
@Component
@Endpoint(id = "saga-metrics")
public class SagaMetricsEndpoint {
    
    @ReadOperation
    public Map<String, Object> metrics() {
        MetricsSnapshot snapshot = metricsCollector.getMetrics();
        
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("sagas", Map.of(
            "started", snapshot.getSagasStarted(),
            "completed", snapshot.getSagasCompleted(),
            "failed", snapshot.getSagasFailed(),
            "compensated", snapshot.getSagasCompensated(),
            "active", snapshot.getActiveSagas(),
            "successRate", snapshot.getSagaSuccessRate()
        ));
        
        metrics.put("steps", Map.of(
            "executed", snapshot.getStepsExecuted(),
            "succeeded", snapshot.getStepsSucceeded(),
            "failed", snapshot.getStepsFailed(),
            "compensated", snapshot.getStepsCompensated(),
            "active", snapshot.getActiveSteps(),
            "successRate", snapshot.getStepSuccessRate()
        ));
        
        metrics.put("performance", Map.of(
            "averageExecutionTimeMs", snapshot.getAverageExecutionTimeMs(),
            "maxExecutionTimeMs", snapshot.getMaxExecutionTimeMs(),
            "minExecutionTimeMs", snapshot.getMinExecutionTimeMs()
        ));
        
        return metrics;
    }
}
```

### Benefits
- **Comprehensive Monitoring**: Detailed metrics for all aspects of saga execution
- **Performance Insights**: Execution time tracking and optimization metrics
- **Operational Visibility**: Real-time monitoring through actuator endpoints
- **Thread Safety**: Safe concurrent access to metrics

## 6. Runtime Validation

### Problem Solved
Lack of comprehensive validation for saga definitions and runtime inputs.

### Solution
Comprehensive validation service with detailed error reporting:

```java
public class SagaValidator {
    
    public static ValidationResult validateSagaDefinition(SagaDefinition saga) {
        ValidationResult.Builder result = ValidationResult.builder();
        
        if (saga == null) {
            return result.addError("Saga definition cannot be null").build();
        }
        
        // Validate saga name
        validateSagaName(saga, result);
        
        // Validate steps
        validateSteps(saga, result);
        
        // Validate dependencies and detect circular dependencies
        validateDependencies(saga, result);
        
        return result.build();
    }
    
    public static ValidationResult validateSagaInputs(SagaDefinition saga, Object inputs) {
        ValidationResult.Builder result = ValidationResult.builder();
        
        if (saga == null) {
            return result.addError("Saga definition cannot be null").build();
        }
        
        if (inputs == null) {
            result.addWarning("No inputs provided. Ensure all steps can handle null inputs.");
        }
        
        return result.build();
    }
    
    public static class ValidationResult {
        private final List<String> errors;
        private final List<String> warnings;
        
        public boolean isValid() {
            return errors.isEmpty();
        }
        
        public boolean hasErrors() {
            return !errors.isEmpty();
        }
        
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
        
        public String getSummary() {
            if (isValid() && !hasWarnings()) {
                return "Valid";
            }
            
            StringBuilder summary = new StringBuilder();
            if (hasErrors()) {
                summary.append("Errors: ").append(errors.size());
            }
            if (hasWarnings()) {
                if (summary.length() > 0) summary.append(", ");
                summary.append("Warnings: ").append(warnings.size());
            }
            
            return summary.toString();
        }
    }
}
```

### Validation Service
```java
@Service
public class SagaValidationService {
    
    @EventListener
    public void validateAtStartup(ApplicationReadyEvent event) {
        if (properties.getValidation().isValidateAtStartup()) {
            validateAllRegisteredSagas();
        }
    }
    
    public ValidationResult validateSagaDefinition(SagaDefinition saga) {
        return SagaValidator.validateSagaDefinition(saga);
    }
    
    public ValidationResult validateSagaInputs(SagaDefinition saga, Object inputs) {
        return SagaValidator.validateSagaInputs(saga, inputs);
    }
    
    private void validateAllRegisteredSagas() {
        // Validate all registered sagas and fail fast if configured
    }
}
```

### Benefits
- **Early Detection**: Catch configuration errors at startup
- **Comprehensive Validation**: Validates all aspects of saga definitions
- **Detailed Reporting**: Clear error and warning messages
- **Fail-Fast Option**: Optional immediate failure on validation errors

## 7. Spring Boot Integration

### Enhanced Configuration Properties
```java
@ConfigurationProperties(prefix = "firefly.saga-engine")
public class SagaEngineProperties {
    
    private SagaEngine.CompensationPolicy compensationPolicy = SagaEngine.CompensationPolicy.STRICT_SEQUENTIAL;
    private boolean autoOptimizationEnabled = true;
    private Duration defaultTimeout = Duration.ofMinutes(5);
    private int maxConcurrentSagas = 100;
    
    private ContextProperties context = new ContextProperties();
    private BackpressureProperties backpressure = new BackpressureProperties();
    private CompensationProperties compensation = new CompensationProperties();
    private ObservabilityProperties observability = new ObservabilityProperties();
    private ValidationProperties validation = new ValidationProperties();
    
    // Nested configuration classes with defaults...
}
```

### Benefits
- **Centralized Configuration**: All engine settings in one place
- **Type Safety**: Strongly typed configuration properties
- **IDE Support**: Auto-completion and validation in IDEs
- **Spring Boot Integration**: Seamless integration with Spring Boot configuration

## Backward Compatibility

All architectural improvements maintain full backward compatibility:

- Existing constructors and methods are preserved
- Default behavior remains unchanged
- New features are opt-in through configuration
- Existing sagas continue to work without modification

## Migration Guide

No migration is required for existing applications. To take advantage of new features:

1. **Update Configuration**: Add new configuration properties as needed
2. **Enable Metrics**: Configure observability settings
3. **Customize Strategies**: Register custom backpressure or error handling strategies
4. **Enable Validation**: Configure validation settings for enhanced error detection

## Performance Impact

The architectural improvements provide performance benefits:

- **Reduced Memory Usage**: Optimized context creation
- **Better Throughput**: Advanced backpressure strategies
- **Improved Reliability**: Enhanced error handling
- **Better Observability**: Comprehensive metrics with minimal overhead

All improvements are designed to have minimal performance impact while providing significant benefits in maintainability and reliability.
