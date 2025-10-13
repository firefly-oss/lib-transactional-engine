# Configuration Reference

This document provides a comprehensive reference for all configuration options available in the Firefly Transactional Engine.

## Property Structure

The engine uses a hierarchical property structure under `firefly.tx.*` with backward compatibility for legacy `firefly.saga.*` properties.

```yaml
firefly:
  tx:                              # New unified structure
    saga:                          # SAGA-specific configuration
      # SAGA properties
    tcc:                           # TCC-specific configuration
      # TCC properties
    persistence:                   # Shared persistence configuration
      # Persistence properties
```

## Core Engine Configuration

### Basic Engine Settings

```yaml
firefly:
  tx:
    saga:
      compensation-policy: STRICT_SEQUENTIAL    # Compensation execution policy
      auto-optimization-enabled: true           # Enable automatic context optimization
      default-timeout: PT5M                     # Default step timeout (ISO-8601 duration)
      max-concurrent-sagas: 100                 # Maximum concurrent saga executions
      layer-concurrency: 5                     # Default layer concurrency
    
    tcc:
      default-timeout: PT30S                    # Default TCC transaction timeout
      retry-enabled: true                       # Enable automatic retry for failed TCC operations
      max-retries: 3                            # Maximum number of retry attempts
      backoff-ms: 1000                          # Backoff delay in milliseconds between retries
      max-concurrent-tccs: 100                  # Maximum concurrent TCC transactions
      strict-ordering: true                     # Enable strict ordering of participant execution
      parallel-execution: false                 # Enable parallel execution in confirm/cancel phases

      # Participant-specific configuration
      participant:
        timeout: PT10S                          # Default timeout for participant operations
        retry-enabled: true                     # Enable retry for participant operations
        max-retries: 2                          # Maximum retry attempts for participants
        retry-backoff: PT0.5S                   # Backoff delay for participant retries
        fail-fast: true                         # Fail fast when a participant fails

      # Recovery configuration
      recovery:
        enabled: true                           # Enable automatic recovery
        scan-interval: PT5M                     # Interval for scanning incomplete transactions
        max-age: PT30M                          # Maximum age before recovery consideration
        max-attempts: 5                         # Maximum recovery attempts per transaction
```

### Compensation Policies

Available compensation policies:

- `STRICT_SEQUENTIAL`: Execute compensations in reverse order of completion
- `GROUPED_PARALLEL`: Execute compensations in parallel by dependency layers
- `RETRY_WITH_BACKOFF`: Retry failed compensations with exponential backoff
- `CIRCUIT_BREAKER`: Skip compensation after threshold failures
- `BEST_EFFORT_PARALLEL`: Execute compensations in parallel, continue on errors

```yaml
firefly:
  tx:
    saga:
      compensation-policy: GROUPED_PARALLEL
      compensation:
        error-handler: log-and-continue         # Error handling strategy
        max-retries: 3                          # Maximum retry attempts
        retry-delay: PT0.1S                     # Delay between retries
        fail-fast-on-critical-errors: false     # Fail immediately on critical errors
```

## Context Configuration

### Execution Modes

```yaml
firefly:
  tx:
    saga:
      context:
        execution-mode: AUTO                    # AUTO, SEQUENTIAL, CONCURRENT, HIGH_PERFORMANCE, LOW_MEMORY
        optimization-enabled: true              # Enable context optimization
```

Execution modes:
- `AUTO`: Automatically select based on saga topology
- `SEQUENTIAL`: Single-threaded execution with HashMap-based context
- `CONCURRENT`: Multi-threaded execution with ConcurrentHashMap-based context
- `HIGH_PERFORMANCE`: Optimized for maximum throughput
- `LOW_MEMORY`: Optimized for memory-constrained environments

### Backpressure Configuration

```yaml
firefly:
  tx:
    saga:
      backpressure:
        strategy: adaptive                      # adaptive, batched, circuit-breaker
        concurrency: 10                         # Concurrency level
        timeout: PT30S                          # Backpressure timeout
        buffer-size: 1000                       # Buffer size
```

## Persistence Configuration

The engine provides pattern-specific persistence providers with shared configuration:

### In-Memory Persistence (Default)

```yaml
firefly:
  tx:
    persistence:
      enabled: false                            # Disable external persistence (default)
```

**Pattern-Specific Providers:**
- SAGA: `InMemorySagaPersistenceProvider`
- TCC: `InMemoryTccPersistenceProvider`

### Redis Persistence

```yaml
firefly:
  tx:
    persistence:
      enabled: true                             # Enable external persistence
      provider: redis                           # Persistence provider type
      auto-recovery-enabled: true               # Enable automatic recovery on startup
      max-saga-age: PT24H                       # Maximum age for active sagas
      cleanup-interval: PT1H                    # Cleanup interval for completed sagas
      retention-period: P7D                     # Retention period for completed sagas
      
      redis:
        host: localhost                         # Redis host
        port: 6379                              # Redis port
        database: 0                             # Redis database number
        username: null                          # Redis username (optional)
        password: null                          # Redis password (optional)
        key-prefix: "firefly:tx:"               # Key prefix for all keys
        key-ttl: PT24H                          # TTL for keys (optional)
        connection-timeout: PT5S                # Connection timeout
        command-timeout: PT10S                  # Command timeout
        
        # Connection pool settings
        pool:
          max-active: 8                         # Maximum active connections
          max-idle: 8                           # Maximum idle connections
          min-idle: 0                           # Minimum idle connections
          max-wait: PT10S                       # Maximum wait time for connection
```

**Pattern-Specific Providers:**
- SAGA: `RedisSagaPersistenceProvider` (uses "saga:" key prefix)
- TCC: `RedisTccPersistenceProvider` (uses "tcc:" key prefix)

Both providers share the same Redis configuration but use different key prefixes to ensure data isolation.

### Custom Persistence Provider

```yaml
firefly:
  tx:
    persistence:
      enabled: true
      provider: custom                          # Use custom provider bean
      custom-provider-bean: myPersistenceProvider
```

## Observability Configuration

### Metrics

```yaml
firefly:
  tx:
    observability:
      metrics-enabled: true                     # Enable metrics collection
      metrics-interval: PT30S                   # Metrics collection interval
      detailed-metrics: false                   # Enable detailed step-level metrics
      
      # Micrometer integration
      micrometer:
        enabled: true                           # Enable Micrometer integration
        registry: prometheus                    # Metrics registry type
        common-tags:                            # Common tags for all metrics
          application: my-app
          environment: production
```

### Tracing

```yaml
firefly:
  tx:
    observability:
      tracing-enabled: true                     # Enable distributed tracing
      tracing-provider: micrometer              # Tracing provider
      
      # Micrometer tracing
      micrometer-tracing:
        enabled: true
        sampling-probability: 0.1               # Sampling probability (0.0 to 1.0)
        baggage-keys:                           # Baggage keys to propagate
          - user-id
          - tenant-id
```

### Logging

```yaml
firefly:
  tx:
    observability:
      detailed-logging-enabled: false          # Enable detailed logging
      log-step-inputs: false                   # Log step inputs (be careful with sensitive data)
      log-step-outputs: false                  # Log step outputs
      log-compensation-details: true           # Log compensation execution details
```

## Validation Configuration

```yaml
firefly:
  tx:
    validation:
      enabled: true                             # Enable validation
      validate-at-startup: true                 # Validate definitions at startup
      validate-inputs: true                     # Validate inputs before execution
      fail-fast: true                          # Fail fast on validation errors
      
      # Annotation processor validation
      compile-time-validation: true             # Enable compile-time validation
      strict-mode: false                        # Enable strict validation mode
```

## Event Publishing Configuration

### Default Event Publisher

```yaml
firefly:
  tx:
    events:
      publisher: noop                           # noop, logger, custom
      async: true                               # Publish events asynchronously
      buffer-size: 1000                         # Event buffer size
      batch-size: 100                           # Event batch size
```

### Custom Event Publisher

```yaml
firefly:
  tx:
    events:
      publisher: custom
      custom-publisher-bean: myEventPublisher   # Custom event publisher bean name
      
      # Event filtering
      filters:
        include-events:                         # Include only these event types
          - SAGA_STARTED
          - SAGA_COMPLETED
          - STEP_FAILED
        exclude-events:                         # Exclude these event types
          - STEP_STARTED
```

## HTTP Integration Configuration

```yaml
firefly:
  tx:
    http:
      enabled: true                             # Enable HTTP utilities
      default-timeout: PT30S                   # Default HTTP timeout
      retry-attempts: 3                         # Default retry attempts
      
      # Header propagation
      propagate-headers:                        # Headers to propagate
        - X-Correlation-ID
        - X-User-ID
        - X-Tenant-ID
      
      # Circuit breaker
      circuit-breaker:
        enabled: true                           # Enable circuit breaker
        failure-threshold: 5                    # Failure threshold
        timeout: PT60S                          # Circuit breaker timeout
```

## Development and Testing Configuration

### Development Profile

```yaml
# application-dev.yml
firefly:
  tx:
    persistence:
      enabled: false                            # Use in-memory for development
    observability:
      detailed-logging-enabled: true            # Enable detailed logging
      metrics-enabled: false                    # Disable metrics in development
    validation:
      strict-mode: true                         # Enable strict validation
```

### Testing Profile

```yaml
# application-test.yml
firefly:
  tx:
    persistence:
      enabled: false                            # Use in-memory for tests
    observability:
      metrics-enabled: false                    # Disable metrics in tests
      tracing-enabled: false                    # Disable tracing in tests
    validation:
      fail-fast: true                          # Fail fast in tests
```

### Production Profile

```yaml
# application-prod.yml
firefly:
  tx:
    persistence:
      enabled: true                             # Enable Redis persistence
      auto-recovery-enabled: true
      redis:
        host: redis-cluster.prod.example.com
        port: 6379
        key-prefix: "prod:firefly:tx:"
    
    observability:
      metrics-enabled: true                     # Enable metrics
      tracing-enabled: true                     # Enable tracing
      detailed-logging-enabled: false          # Disable detailed logging
    
    saga:
      compensation-policy: RETRY_WITH_BACKOFF   # Robust compensation policy
      max-concurrent-sagas: 200                 # Higher concurrency
```

## Legacy Configuration Support

The engine maintains backward compatibility with legacy property names:

```yaml
# Legacy properties (still supported)
firefly:
  saga:
    engine:
      compensation-policy: STRICT_SEQUENTIAL
      persistence:
        enabled: true
        redis:
          host: localhost
```

**Migration Notice**: Legacy properties are deprecated. Please migrate to the new `firefly.tx.*` structure.

## Environment Variables

All properties can be overridden using environment variables:

```bash
# Basic configuration
FIREFLY_TX_SAGA_COMPENSATION_POLICY=GROUPED_PARALLEL
FIREFLY_TX_PERSISTENCE_ENABLED=true

# Redis configuration
FIREFLY_TX_PERSISTENCE_REDIS_HOST=redis.example.com
FIREFLY_TX_PERSISTENCE_REDIS_PORT=6379
FIREFLY_TX_PERSISTENCE_REDIS_PASSWORD=secret

# Observability
FIREFLY_TX_OBSERVABILITY_METRICS_ENABLED=true
FIREFLY_TX_OBSERVABILITY_TRACING_ENABLED=true
```

## Configuration Validation

The engine validates configuration at startup and provides helpful error messages:

```java
@Component
public class ConfigurationValidator {
    
    @EventListener(ApplicationReadyEvent.class)
    public void validateConfiguration() {
        // Configuration validation logic
    }
}
```

Common validation errors:
- Invalid compensation policy
- Missing Redis configuration when persistence is enabled
- Invalid timeout durations
- Conflicting configuration options

## Best Practices

### Production Configuration

1. **Enable Persistence**: Always use Redis persistence in production
2. **Configure Monitoring**: Enable metrics and tracing
3. **Set Appropriate Timeouts**: Configure realistic timeouts for your use case
4. **Use Appropriate Compensation Policy**: Choose based on your consistency requirements
5. **Configure Resource Limits**: Set appropriate concurrency limits

### Development Configuration

1. **Use In-Memory Persistence**: Faster startup and no external dependencies
2. **Enable Detailed Logging**: Helpful for debugging
3. **Enable Strict Validation**: Catch issues early
4. **Disable Metrics**: Reduce overhead during development

### Testing Configuration

1. **Use In-Memory Persistence**: Faster test execution
2. **Disable External Integrations**: Focus on business logic
3. **Enable Fail-Fast**: Catch issues quickly
4. **Use Testcontainers**: For integration tests requiring Redis

## Custom Bean Configuration

### Custom Event Publishers

You can provide custom implementations for event publishing:

```java
@Configuration
public class EventConfiguration {

    @Bean
    public StepEventPublisher customStepEventPublisher() {
        return new KafkaStepEventPublisher();
    }

    @Bean
    public TccEventPublisher customTccEventPublisher() {
        return new KafkaTccEventPublisher();
    }
}
```

### Custom Event Handlers

You can provide custom implementations for event handling:

```java
@Configuration
public class EventHandlerConfiguration {

    @Bean
    public SagaEvents customSagaEvents() {
        return new MetricsSagaEvents();
    }

    @Bean
    public TccEvents customTccEvents() {
        return new MetricsTccEvents();
    }
}
```

The framework will automatically detect and use your custom implementations when available. If no custom implementation is provided, the framework uses default no-op implementations.

### Compositor Configuration

The framework automatically configures both SAGA and TCC Compositors:

```java
@Configuration
@EnableAutoConfiguration
public class TransactionalEngineConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SagaCompositor sagaCompositor(SagaEngine sagaEngine,
                                        SagaRegistry sagaRegistry,
                                        SagaEvents sagaEvents) {
        return new SagaCompositor(sagaEngine, sagaRegistry, sagaEvents);
    }

    @Bean
    @ConditionalOnMissingBean
    public TccCompositor tccCompositor(TccEngine tccEngine,
                                      TccRegistry tccRegistry,
                                      TccEvents tccEvents) {
        return new TccCompositor(tccEngine, tccRegistry, tccEvents);
    }
}
```

You can provide custom implementations if needed:

```java
@Configuration
public class CustomCompositorConfiguration {

    @Bean
    @Primary
    public TccCompositor customTccCompositor(TccEngine tccEngine,
                                            TccRegistry tccRegistry,
                                            TccEvents tccEvents) {
        return new EnhancedTccCompositor(tccEngine, tccRegistry, tccEvents);
    }
}
```

### Composition Validation

The framework provides built-in validation for compositions:

- **Dependency validation**: Detects circular dependencies
- **TCC existence validation**: Ensures all referenced TCCs exist
- **Data mapping validation**: Validates cross-TCC data mappings
- **Execution order validation**: Ensures proper dependency ordering

Validation occurs automatically when building compositions and can be disabled if needed:

```yaml
firefly:
  tx:
    tcc:
      composition:
        validation:
          enabled: false  # Disable composition validation (not recommended)
```
