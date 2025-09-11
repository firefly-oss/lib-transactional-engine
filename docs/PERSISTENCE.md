# Saga Persistence and Recovery

The Firefly Transactional Engine provides robust persistence and recovery capabilities to ensure saga reliability in production environments. This document covers the persistence layer, configuration options, and recovery mechanisms.

## Overview

The persistence layer enables:
- **State Persistence**: Automatic persistence of saga execution state
- **Recovery**: Automatic recovery of in-flight sagas after application restarts
- **Monitoring**: Health checks and state validation
- **Cleanup**: Automatic cleanup of completed saga states

## Persistence Providers

### In-Memory Provider (Default)

The in-memory provider is the default option, providing zero-configuration operation:

```java
@Configuration
public class SagaConfiguration {
    
    @Bean
    public SagaEngine sagaEngine(SagaRegistry sagaRegistry) {
        // In-memory persistence is enabled by default
        return new SagaEngine(sagaRegistry);
    }
}
```

**Features:**
- Zero configuration required
- High performance
- Suitable for development and testing
- No external dependencies

**Limitations:**
- State is lost on application restart
- Not suitable for production environments requiring durability

### Redis Provider

The Redis provider offers durable persistence with high performance:

```yaml
# application.yml
firefly:
  saga:
    persistence:
      enabled: true
      provider: redis
      redis:
        host: localhost
        port: 6379
        database: 0
        password: ${REDIS_PASSWORD:}
        key-prefix: "saga:"
        key-ttl: PT24H
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 0
          max-wait: PT10S
```

```java
@Configuration
public class SagaConfiguration {
    
    @Bean
    public SagaEngine sagaEngine(
            SagaRegistry sagaRegistry,
            SagaPersistenceProvider persistenceProvider) {
        return new SagaEngine(
            sagaRegistry,
            new SagaLoggerEvents(),
            SagaEngine.CompensationPolicy.STRICT_SEQUENTIAL,
            null,
            true,
            null,
            persistenceProvider,
            true // Enable persistence
        );
    }
}
```

**Features:**
- Durable persistence
- High availability with Redis clustering
- Automatic state serialization/deserialization
- TTL-based cleanup
- Health monitoring

## Configuration Properties

### Basic Persistence Configuration

```yaml
firefly:
  saga:
    persistence:
      enabled: true                    # Enable persistence (default: true)
      provider: redis                  # Provider type: in-memory, redis
      recovery:
        enabled: true                  # Enable automatic recovery (default: true)
        startup-delay: PT30S           # Delay before recovery starts
        stale-threshold: PT1H          # Threshold for identifying stale sagas
        cleanup-interval: PT6H         # Interval for cleanup operations
```

### Redis-Specific Configuration

```yaml
firefly:
  saga:
    persistence:
      redis:
        host: localhost                # Redis host
        port: 6379                     # Redis port
        database: 0                    # Redis database number
        password: ${REDIS_PASSWORD:}   # Redis password (optional)
        timeout: PT5S                  # Connection timeout
        key-prefix: "saga:"            # Key prefix for saga data
        key-ttl: PT24H                 # TTL for saga keys
        pool:
          max-active: 8                # Maximum active connections
          max-idle: 8                  # Maximum idle connections
          min-idle: 0                  # Minimum idle connections
          max-wait: PT10S              # Maximum wait time for connection
```

## Recovery Mechanisms

### Automatic Recovery

The recovery service automatically identifies and recovers in-flight sagas on application startup:

```java
@Component
public class SagaRecoveryManager {
    
    private final SagaRecoveryService recoveryService;
    
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        recoveryService.recoverInFlightSagas()
            .subscribe(result -> {
                log.info("Recovery completed: {} sagas found, {} recovered, {} failed",
                    result.getTotalFound(),
                    result.getRecovered(),
                    result.getFailed());
            });
    }
}
```

### Manual Recovery

You can also trigger recovery manually:

```java
@RestController
@RequestMapping("/admin/sagas")
public class SagaAdminController {
    
    private final SagaRecoveryService recoveryService;
    
    @PostMapping("/recover")
    public Mono<RecoveryResult> recoverAllSagas() {
        return recoveryService.recoverInFlightSagas();
    }
    
    @PostMapping("/recover/{correlationId}")
    public Mono<SingleRecoveryResult> recoverSaga(@PathVariable String correlationId) {
        return recoveryService.recoverSaga(correlationId);
    }
    
    @PostMapping("/cleanup-stale")
    public Mono<Long> cleanupStaleSagas() {
        return recoveryService.cancelStaleSagas(Duration.ofHours(2));
    }
}
```

## Monitoring and Health Checks

### Health Checks

Monitor persistence provider health:

```java
@Component
public class SagaHealthIndicator implements HealthIndicator {
    
    private final SagaEngine sagaEngine;
    
    @Override
    public Health health() {
        return sagaEngine.isPersistenceHealthy()
            .map(healthy -> healthy ? Health.up() : Health.down())
            .block(Duration.ofSeconds(5));
    }
}
```

### State Validation

Validate persisted saga states:

```java
@Scheduled(fixedRate = 3600000) // Every hour
public void validatePersistedStates() {
    recoveryService.validatePersistedState()
        .subscribe(result -> {
            if (!result.isValid()) {
                log.warn("Found {} corrupted saga states out of {} checked",
                    result.getCorruptedStates(),
                    result.getTotalChecked());
            }
        });
}
```

## Best Practices

### 1. Production Configuration

For production environments:

```yaml
firefly:
  saga:
    persistence:
      enabled: true
      provider: redis
      recovery:
        enabled: true
        startup-delay: PT60S           # Allow time for dependencies
        stale-threshold: PT2H          # Conservative threshold
        cleanup-interval: PT12H        # Regular cleanup
      redis:
        host: redis-cluster.internal
        port: 6379
        database: 1                    # Dedicated database
        key-prefix: "prod:saga:"       # Environment-specific prefix
        key-ttl: PT72H                 # Longer retention
        pool:
          max-active: 20               # Higher connection pool
          max-idle: 10
          min-idle: 2
```

### 2. Error Handling

Implement proper error handling for persistence operations:

```java
@Component
public class SagaExecutionService {
    
    private final SagaEngine sagaEngine;
    
    public Mono<SagaResult> executeSaga(String sagaName, StepInputs inputs) {
        return sagaEngine.execute(sagaName, inputs)
            .doOnError(error -> {
                if (error instanceof PersistenceException) {
                    log.error("Persistence error during saga execution", error);
                    // Implement fallback strategy
                }
            })
            .onErrorResume(PersistenceException.class, error -> {
                // Fallback to in-memory execution if persistence fails
                return executeWithoutPersistence(sagaName, inputs);
            });
    }
}
```

### 3. Monitoring

Set up comprehensive monitoring:

```java
@Component
public class SagaMetrics {
    
    private final MeterRegistry meterRegistry;
    private final SagaRecoveryService recoveryService;
    
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void collectMetrics() {
        recoveryService.identifyStaleSagas(Duration.ofHours(1))
            .count()
            .subscribe(count -> 
                meterRegistry.gauge("saga.stale.count", count));
    }
}
```

### 4. Testing

Use Testcontainers for integration testing:

```java
@Testcontainers
class SagaPersistenceIntegrationTest {
    
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);
    
    @Test
    void shouldPersistAndRecoverSaga() {
        // Test implementation
    }
}
```

## Troubleshooting

### Common Issues

1. **Recovery Failures**
   - Check saga definitions are available during recovery
   - Verify persistence provider connectivity
   - Review recovery service logs

2. **Performance Issues**
   - Monitor Redis connection pool usage
   - Adjust TTL settings for cleanup
   - Consider Redis clustering for high load

3. **State Corruption**
   - Run state validation regularly
   - Check serialization compatibility
   - Monitor for schema changes

### Debugging

Enable debug logging:

```yaml
logging:
  level:
    com.firefly.transactionalengine.persistence: DEBUG
    com.firefly.transactionalengine.recovery: DEBUG
```

## Migration Guide

### From In-Memory to Redis

1. Add Redis dependency and configuration
2. Update SagaEngine bean configuration
3. Test recovery functionality
4. Deploy with gradual rollout

### Schema Evolution

When updating saga context structure:

1. Maintain backward compatibility
2. Use versioned serialization
3. Implement migration scripts if needed
4. Test with existing persisted states

## Security Considerations

1. **Redis Security**
   - Use authentication (password/ACL)
   - Enable TLS for network encryption
   - Restrict network access

2. **Data Encryption**
   - Consider encrypting sensitive saga data
   - Use Redis encryption at rest
   - Implement key rotation

3. **Access Control**
   - Limit access to recovery endpoints
   - Implement proper authentication
   - Audit recovery operations
