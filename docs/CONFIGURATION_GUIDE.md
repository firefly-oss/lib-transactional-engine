# Configuration Guide

This guide provides comprehensive information about configuring the Firefly Transactional Engine for optimal performance and reliability.

## Table of Contents

- [Basic Configuration](#basic-configuration)
- [Context Configuration](#context-configuration)
- [Backpressure Configuration](#backpressure-configuration)
- [Compensation Configuration](#compensation-configuration)
- [Persistence Configuration](#persistence-configuration)
- [Observability Configuration](#observability-configuration)
- [Validation Configuration](#validation-configuration)
- [Environment-Specific Configurations](#environment-specific-configurations)
- [Performance Tuning](#performance-tuning)

## Basic Configuration

### Core Engine Settings

```yaml
firefly:
  saga:
    engine:
      # Compensation policy for failed sagas
      compensation-policy: STRICT_SEQUENTIAL  # STRICT_SEQUENTIAL, BEST_EFFORT_PARALLEL

      # Enable automatic optimization of saga contexts
      auto-optimization-enabled: true

      # Default timeout for saga execution
      default-timeout: PT5M  # 5 minutes (ISO-8601 duration format)

      # Maximum number of concurrent sagas
      max-concurrent-sagas: 100
```

### Compensation Policies

- **STRICT_SEQUENTIAL**: Compensate steps in strict reverse order (default)
- **BEST_EFFORT_PARALLEL**: Attempt parallel compensation where possible

## Context Configuration

Configure how saga contexts are created and optimized:

```yaml
firefly:
  saga:
    engine:
      context:
        # Execution mode for saga contexts
        execution-mode: AUTO  # AUTO, SEQUENTIAL, CONCURRENT, HIGH_PERFORMANCE, LOW_MEMORY
      
      # Enable context optimization
      optimization-enabled: true
```

### Execution Modes

- **AUTO**: Automatically choose based on saga characteristics (default)
- **SEQUENTIAL**: Sequential execution, lowest memory usage
- **CONCURRENT**: Concurrent execution with moderate parallelism
- **HIGH_PERFORMANCE**: Maximum parallelism and performance
- **LOW_MEMORY**: Optimized for minimal memory footprint

## Backpressure Configuration

Configure reactive stream backpressure handling:

```yaml
firefly:
  saga:
    engine:
      backpressure:
        # Backpressure strategy
        strategy: batched  # batched, adaptive, circuit-breaker

        # Maximum concurrent operations
        concurrency: 10

        # Batch size for batched strategy
        batch-size: 50

        # Timeout for operations
        timeout: PT30S  # 30 seconds
```

### Backpressure Strategies

#### Batched Strategy
Groups items into batches for processing:
```yaml
firefly:
  saga:
    engine:
      backpressure:
        strategy: batched
        concurrency: 10
        batch-size: 50
        timeout: PT30S
```

#### Adaptive Strategy
Dynamically adjusts concurrency based on performance:
```yaml
firefly:
  saga:
    engine:
    backpressure:
      strategy: adaptive
      concurrency: 20  # Starting concurrency
      timeout: PT45S
```

#### Circuit Breaker Strategy
Implements circuit breaker pattern for fault tolerance:
```yaml
firefly:
  saga:
    engine:
    backpressure:
      strategy: circuit-breaker
      concurrency: 5
      timeout: PT15S
```

## Compensation Configuration

Configure error handling during compensation:

```yaml
firefly:
  saga:
    engine:
    compensation:
      # Error handling strategy
      error-handler: log-and-continue  # log-and-continue, fail-fast, retry, robust, strict, network-aware
      
      # Maximum retry attempts
      max-retries: 3
      
      # Delay between retries
      retry-delay: PT0.1S  # 100 milliseconds
      
      # Fail fast on critical errors
      fail-fast-on-critical-errors: false
```

### Error Handling Strategies

#### Log and Continue
Logs errors and continues with compensation:
```yaml
firefly:
  saga:
    engine:
    compensation:
      error-handler: log-and-continue
```

#### Fail Fast
Immediately fails the saga on any compensation error:
```yaml
firefly:
  saga:
    engine:
    compensation:
      error-handler: fail-fast
      fail-fast-on-critical-errors: true
```

#### Retry
Retries failed compensation steps:
```yaml
firefly:
  saga:
    engine:
    compensation:
      error-handler: retry
      max-retries: 5
      retry-delay: PT0.5S
```

#### Robust (Composite)
Combines multiple strategies for comprehensive error handling:
```yaml
firefly:
  saga:
    engine:
    compensation:
      error-handler: robust
      max-retries: 3
      fail-fast-on-critical-errors: true
```

#### Network Aware
Specialized handling for network-related errors:
```yaml
firefly:
  saga:
    engine:
    compensation:
      error-handler: network-aware
      max-retries: 5
      retry-delay: PT1S
```

## Persistence Configuration

Configure saga state persistence and recovery:

```yaml
firefly:
  saga:
    engine:
      persistence:
        # Enable persistence for saga state
        enabled: true                    # Default: false (in-memory only)

        # Automatic recovery settings
        auto-recovery-enabled: true      # Enable automatic recovery on startup
        max-saga-age: PT24H              # Maximum age before saga is considered stale
        cleanup-interval: PT1H           # Interval for cleanup operations
        retention-period: P7D            # How long to retain completed saga states

        # Redis-specific configuration
        redis:
          host: localhost                # Redis server host
          port: 6379                     # Redis server port
          database: 0                    # Redis database index
          password: ${REDIS_PASSWORD:}   # Redis password (optional)
          connection-timeout: PT5S       # Connection timeout
          command-timeout: PT10S         # Command timeout
          key-prefix: "firefly:saga:"    # Key prefix for saga data
          key-ttl: PT24H                 # TTL for saga keys (optional)
```

### Persistence Providers

#### In-Memory Provider (Default)
Zero-configuration persistence for development:
```yaml
firefly:
  saga:
    engine:
      persistence:
        enabled: false  # Default - uses in-memory storage
```

#### Redis Provider
Production-ready persistence with Redis:
```yaml
firefly:
  saga:
    engine:
      persistence:
        enabled: true
        redis:
          host: redis-cluster.internal
          port: 6379
          database: 1
          key-prefix: "prod:saga:"
          key-ttl: PT72H
          connection-timeout: PT10S
          command-timeout: PT30S
```

### Recovery Configuration

Configure automatic saga recovery behavior:

```yaml
firefly:
  saga:
    engine:
      persistence:
        auto-recovery-enabled: true      # Enable automatic recovery
        max-saga-age: PT2H               # Consider sagas stale after 2 hours
        cleanup-interval: PT6H           # Run cleanup every 6 hours
        retention-period: P3D            # Keep completed sagas for 3 days
```

## Observability Configuration

Configure metrics, tracing, and logging:

```yaml
firefly:
  saga:
    engine:
    observability:
      # Enable metrics collection
      metrics-enabled: true
      
      # Enable distributed tracing
      tracing-enabled: true
      
      # Enable detailed logging
      detailed-logging-enabled: false
      
      # Metrics collection interval
      metrics-interval: PT30S  # 30 seconds
```

### Metrics Endpoint

When metrics are enabled, the saga metrics are available at:
```
GET /actuator/saga-metrics
```

Example response:
```json
{
  "sagas": {
    "started": 150,
    "completed": 145,
    "failed": 3,
    "compensated": 2,
    "active": 0,
    "successRate": 0.9667
  },
  "steps": {
    "executed": 450,
    "succeeded": 435,
    "failed": 15,
    "compensated": 10,
    "active": 0,
    "successRate": 0.9667
  },
  "performance": {
    "averageExecutionTimeMs": 1250,
    "maxExecutionTimeMs": 5000,
    "minExecutionTimeMs": 100
  }
}
```

## Validation Configuration

Configure saga definition and input validation:

```yaml
firefly:
  saga:
    engine:
    validation:
      # Enable validation
      enabled: true
      
      # Validate all sagas at startup
      validate-at-startup: true
      
      # Validate inputs before execution
      validate-inputs: true
      
      # Fail fast on validation errors
      fail-fast: true
```

### Validation Features

- **Circular Dependency Detection**: Automatically detects circular dependencies in saga steps
- **Step Definition Validation**: Validates step configuration and dependencies
- **Input Validation**: Validates saga inputs before execution
- **Startup Validation**: Validates all registered sagas at application startup

## Environment-Specific Configurations

### Development Environment
```yaml
firefly:
  saga:
    engine:
    compensation-policy: STRICT_SEQUENTIAL
    auto-optimization-enabled: true
    max-concurrent-sagas: 10
    
    context:
      execution-mode: AUTO
      optimization-enabled: true
    
    backpressure:
      strategy: batched
      concurrency: 5
      batch-size: 10
    
    observability:
      metrics-enabled: true
      detailed-logging-enabled: true
      metrics-interval: PT10S
    
    validation:
      enabled: true
      validate-at-startup: true
      fail-fast: true
```

### Production Environment
```yaml
firefly:
  saga:
    engine:
    compensation-policy: STRICT_SEQUENTIAL
    auto-optimization-enabled: true
    max-concurrent-sagas: 200
    
    context:
      execution-mode: HIGH_PERFORMANCE
      optimization-enabled: true
    
    backpressure:
      strategy: adaptive
      concurrency: 20
      batch-size: 100
      timeout: PT60S
    
    compensation:
      error-handler: robust
      max-retries: 5
      retry-delay: PT1S
    
    observability:
      metrics-enabled: true
      tracing-enabled: true
      detailed-logging-enabled: false
      metrics-interval: PT60S
    
    validation:
      enabled: true
      validate-at-startup: true
      fail-fast: false
```

### High-Throughput Environment
```yaml
firefly:
  saga:
    engine:
    compensation-policy: BEST_EFFORT_PARALLEL
    auto-optimization-enabled: true
    max-concurrent-sagas: 500
    
    context:
      execution-mode: HIGH_PERFORMANCE
      optimization-enabled: true
    
    backpressure:
      strategy: adaptive
      concurrency: 50
      batch-size: 200
      timeout: PT120S
    
    compensation:
      error-handler: network-aware
      max-retries: 3
      retry-delay: PT0.5S
    
    observability:
      metrics-enabled: true
      tracing-enabled: false  # Disabled for performance
      detailed-logging-enabled: false
      metrics-interval: PT120S
```

### Resource-Constrained Environment
```yaml
firefly:
  saga:
    engine:
    compensation-policy: STRICT_SEQUENTIAL
    auto-optimization-enabled: false
    max-concurrent-sagas: 20
    
    context:
      execution-mode: LOW_MEMORY
      optimization-enabled: false
    
    backpressure:
      strategy: batched
      concurrency: 2
      batch-size: 5
      timeout: PT30S
    
    compensation:
      error-handler: log-and-continue
      max-retries: 1
    
    observability:
      metrics-enabled: false
      tracing-enabled: false
      detailed-logging-enabled: false
```

## Performance Tuning

### Memory Optimization
```yaml
firefly:
  saga:
    engine:
    context:
      execution-mode: LOW_MEMORY
      optimization-enabled: false
    
    backpressure:
      batch-size: 10  # Smaller batches
      concurrency: 2  # Lower concurrency
    
    observability:
      metrics-enabled: false  # Disable if not needed
```

### Throughput Optimization
```yaml
firefly:
  saga:
    engine:
    context:
      execution-mode: HIGH_PERFORMANCE
      optimization-enabled: true
    
    backpressure:
      strategy: adaptive
      concurrency: 50  # Higher concurrency
      batch-size: 200  # Larger batches
    
    compensation-policy: BEST_EFFORT_PARALLEL
```

### Latency Optimization
```yaml
firefly:
  saga:
    engine:
    context:
      execution-mode: CONCURRENT
    
    backpressure:
      strategy: circuit-breaker
      concurrency: 10
      batch-size: 1  # Process immediately
      timeout: PT5S  # Short timeout
```

## Configuration Validation

The engine validates configuration at startup. Common validation errors:

- **Invalid Duration Format**: Use ISO-8601 format (e.g., `PT30S`, `PT5M`)
- **Invalid Strategy Names**: Use predefined strategy names
- **Invalid Execution Modes**: Use predefined execution modes
- **Negative Values**: Concurrency, batch size, and retry counts must be positive

## Custom Configuration

### Programmatic Configuration
```java
@Configuration
public class SagaEngineConfig {
    
    @Bean
    @Primary
    public SagaEngineProperties customSagaEngineProperties() {
        SagaEngineProperties properties = new SagaEngineProperties();
        
        // Customize properties
        properties.setCompensationPolicy(SagaEngine.CompensationPolicy.BEST_EFFORT_PARALLEL);
        properties.setMaxConcurrentSagas(500);
        
        // Customize nested properties
        properties.getBackpressure().setStrategy("adaptive");
        properties.getBackpressure().setConcurrency(20);
        
        return properties;
    }
}
```

### Custom Strategies
```java
@Configuration
public class CustomStrategiesConfig {
    
    @PostConstruct
    public void registerCustomStrategies() {
        // Register custom backpressure strategy
        BackpressureStrategy customStrategy = new CustomBackpressureStrategy();
        BackpressureStrategyFactory.registerStrategy("custom", customStrategy);
        
        // Register custom error handler
        CompensationErrorHandler customHandler = new CustomErrorHandler();
        CompensationErrorHandlerFactory.registerHandler("custom", customHandler);
    }
}
```

## Troubleshooting

### Common Issues

1. **High Memory Usage**: Use `LOW_MEMORY` execution mode and disable optimization
2. **Poor Performance**: Use `HIGH_PERFORMANCE` mode and `adaptive` backpressure strategy
3. **Compensation Failures**: Use `robust` error handler with appropriate retry settings
4. **Validation Errors**: Enable detailed logging and check saga definitions

### Monitoring

Monitor these key metrics:
- Saga success rate
- Average execution time
- Active saga count
- Step failure rate
- Compensation rate

Use the `/actuator/saga-metrics` endpoint for real-time monitoring.
