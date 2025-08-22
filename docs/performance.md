# Performance Optimization

This guide covers performance optimization techniques and tuning strategies for the Firefly Transactional Engine in high-throughput scenarios.

## Overview

The Transactional Engine is designed for high-performance distributed transaction processing. This guide provides recommendations for optimizing performance, reducing latency, and scaling to handle thousands of concurrent saga executions.

## Core Performance Principles

### 1. Reactive Architecture Benefits

The engine leverages Project Reactor's non-blocking I/O:
- **No thread-per-request overhead**
- **Efficient resource utilization**
- **Built-in backpressure handling**
- **Parallel execution support**

### 2. Asynchronous Processing

All operations are asynchronous by default:
- Step executions don't block threads
- Compensation flows run independently
- Event publishing is non-blocking
- Database operations use reactive drivers

## Configuration Tuning

### Thread Pool Optimization

Configure thread pools for optimal performance:

```yaml
transactional-engine:
  execution:
    # Core thread pool for saga execution
    core-pool-size: ${SAGA_CORE_THREADS:10}
    max-pool-size: ${SAGA_MAX_THREADS:50}
    queue-capacity: ${SAGA_QUEUE_CAPACITY:1000}
    
    # Thread pool for compensation processing
    compensation:
      core-pool-size: ${COMPENSATION_CORE_THREADS:5}
      max-pool-size: ${COMPENSATION_MAX_THREADS:20}
      queue-capacity: ${COMPENSATION_QUEUE_CAPACITY:500}
    
    # Event publishing thread pool
    events:
      core-pool-size: ${EVENT_CORE_THREADS:3}
      max-pool-size: ${EVENT_MAX_THREADS:10}
      async: true
```

### Memory Management

Optimize memory usage for high-throughput scenarios:

```yaml
transactional-engine:
  memory:
    # Saga instance cache settings
    instance-cache:
      max-size: 10000
      expire-after-access: 30m
      expire-after-write: 1h
    
    # Step result caching
    step-cache:
      max-size: 50000
      expire-after-access: 15m
      
    # Compensation cache
    compensation-cache:
      max-size: 5000
      expire-after-access: 1h
```

### Batch Processing

Enable batch processing for better throughput:

```yaml
transactional-engine:
  batch:
    enabled: true
    # Process multiple steps in batches
    step-batch-size: 100
    step-batch-timeout: 50ms
    
    # Batch event publishing
    event-batch-size: 500
    event-batch-timeout: 100ms
    
    # Compensation batching
    compensation-batch-size: 50
    compensation-batch-timeout: 200ms
```

## Database Optimization

### Connection Pool Tuning

Optimize database connection pools:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
      connection-timeout: 3000
      idle-timeout: 300000
      max-lifetime: 900000
      leak-detection-threshold: 60000
  
  r2dbc:
    pool:
      initial-size: 10
      max-size: 50
      max-idle-time: 30m
      max-acquire-time: 3s
      validation-query: SELECT 1
```

### Query Optimization

Optimize database queries:

```sql
-- Index for saga lookups
CREATE INDEX idx_saga_status_created ON saga_instances (status, created_at);
CREATE INDEX idx_saga_correlation ON saga_instances (correlation_id);

-- Index for step executions
CREATE INDEX idx_step_saga_status ON saga_steps (saga_id, status);
CREATE INDEX idx_step_execution_time ON saga_steps (execution_start_time);

-- Partitioning for large datasets
CREATE TABLE saga_instances_current PARTITION OF saga_instances 
FOR VALUES FROM ('2024-01-01') TO ('2024-12-31');
```

### Saga State Management

Optimize saga state persistence:

```yaml
transactional-engine:
  persistence:
    # Batch saga state updates
    batch-updates: true
    batch-size: 200
    batch-timeout: 100ms
    
    # Async state persistence
    async-persistence: true
    persistence-queue-size: 5000
    
    # Cleanup old saga instances
    cleanup:
      enabled: true
      retention-period: 30d
      cleanup-interval: 1h
      batch-size: 1000
```

## Step Execution Optimization

### Parallel Step Execution

Maximize parallelism in step execution:

```java
@Saga("high-performance-order")
public class HighPerformanceOrderSaga {
    
    // Parallel execution of independent steps
    @SagaStep(id = "validate-payment")
    public Mono<PaymentResult> validatePayment(@Input("orderId") String orderId) {
        return paymentService.validate(orderId)
            .subscribeOn(Schedulers.boundedElastic());
    }
    
    @SagaStep(id = "check-inventory")
    public Mono<InventoryResult> checkInventory(@Input("items") List<Item> items) {
        return inventoryService.checkAvailability(items)
            .subscribeOn(Schedulers.boundedElastic());
    }
    
    // Dependent step runs after both complete
    @SagaStep(id = "process-order", 
              dependsOn = {"validate-payment", "check-inventory"})
    public Mono<OrderResult> processOrder(
            @FromStep("validate-payment") PaymentResult payment,
            @FromStep("check-inventory") InventoryResult inventory) {
        return orderService.process(payment, inventory);
    }
}
```

### Step Result Caching

Cache expensive step results:

```java
@SagaStep(id = "expensive-calculation", 
          cacheResults = true, 
          cacheExpiry = "10m")
public Mono<CalculationResult> performExpensiveCalculation(
        @Input("inputData") InputData data) {
    return calculationService.compute(data)
        .cache(Duration.ofMinutes(10));
}
```

### Retry Policy Optimization

Configure efficient retry policies:

```java
@SagaStep(id = "external-service-call",
          retryPolicy = @Retry(
              maxAttempts = 3,
              delay = "100ms",
              backoff = @Backoff(multiplier = 2.0, maxDelay = "5s"),
              retryOn = {IOException.class, TimeoutException.class}
          ))
public Mono<ServiceResult> callExternalService(@Input("request") ServiceRequest request) {
    return externalService.call(request)
        .timeout(Duration.ofSeconds(5));
}
```

## Event System Optimization

### Asynchronous Event Publishing

Optimize event publishing for high throughput:

```yaml
transactional-engine:
  events:
    # Async event publishing
    async: true
    buffer-size: 10000
    overflow-strategy: DROP_LATEST
    
    # Event serialization optimization
    serialization:
      use-binary: true
      compress: true
      compression-level: 6
    
    # AWS Kinesis optimization
    aws:
      kinesis:
        batch-size: 500
        linger-ms: 100
        compression: gzip
        
    # Azure Event Hubs optimization  
    azure:
      event-hubs:
        batch-size: 300
        batch-timeout: 50ms
        compression: true
```

### Event Processing Optimization

Optimize event consumers:

```java
@EventListener
@Async("eventProcessorExecutor")
public void handleSagaEvent(SagaEvent event) {
    // Process event asynchronously
    eventProcessor.process(event)
        .subscribeOn(Schedulers.parallel())
        .subscribe();
}
```

## Monitoring and Profiling

### JVM Optimization

Optimize JVM settings for high throughput:

```bash
# G1GC for low latency
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200
-XX:G1HeapRegionSize=16m

# Heap sizing
-Xms4g
-Xmx8g
-XX:NewRatio=1

# GC logging
-Xlog:gc*:gc.log:time,level,tags

# JIT optimization
-XX:+TieredCompilation
-XX:+UseCompressedOops
```

### Performance Monitoring

Monitor key performance indicators:

```yaml
management:
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: transactional-engine
      
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
        
transactional-engine:
  metrics:
    # Detailed performance metrics
    detailed: true
    histogram-buckets: [0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0]
    
    # Performance alerts
    alerts:
      saga-duration-p99: 30s
      step-duration-p95: 5s
      queue-depth-threshold: 1000
```

### Profiling Configuration

Enable profiling for performance analysis:

```java
@Configuration
@Profile("performance-profiling")
public class PerformanceProfilingConfig {
    
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
            .commonTags("service", "transactional-engine")
            .meterFilter(MeterFilter.maximumExpectedValue(Timer.class, Duration.ofMinutes(5)));
    }
    
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
```

## Load Testing

### Test Configuration

Configure the engine for load testing:

```yaml
transactional-engine:
  test:
    # Disable detailed logging during load tests
    debug: false
    
    # Increase batch sizes for load testing
    batch:
      step-batch-size: 1000
      event-batch-size: 2000
      
    # Disable non-essential features
    features:
      detailed-metrics: false
      audit-logging: false
      
  # Load test specific persistence
  persistence:
    async-persistence: true
    batch-updates: true
    batch-size: 500
```

### Performance Benchmarks

Target performance benchmarks:

- **Throughput**: 10,000+ saga executions per second
- **Latency**: P95 < 100ms, P99 < 500ms
- **Compensation**: < 1s for simple compensations
- **Memory**: < 2GB heap for 100,000 active sagas
- **CPU**: < 80% utilization under peak load

## Scaling Strategies

### Horizontal Scaling

Deploy multiple instances:

```yaml
# Kubernetes deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: transactional-engine
spec:
  replicas: 5
  selector:
    matchLabels:
      app: transactional-engine
  template:
    metadata:
      labels:
        app: transactional-engine
    spec:
      containers:
      - name: transactional-engine
        resources:
          requests:
            memory: "2Gi"
            cpu: "1000m"
          limits:
            memory: "4Gi"
            cpu: "2000m"
        env:
        - name: SAGA_CORE_THREADS
          value: "20"
        - name: SAGA_MAX_THREADS
          value: "100"
```

### Database Sharding

Implement database sharding for massive scale:

```java
@Configuration
public class ShardingConfig {
    
    @Bean
    public ShardingStrategy sagaShardingStrategy() {
        return new ConsistentHashShardingStrategy()
            .withShardCount(10)
            .withShardKey(saga -> saga.getCorrelationId());
    }
}
```

### Caching Strategies

Implement distributed caching:

```yaml
transactional-engine:
  caching:
    # Redis cluster for distributed caching
    redis:
      cluster:
        nodes: 
          - redis-node-1:6379
          - redis-node-2:6379
          - redis-node-3:6379
      pool:
        max-active: 100
        max-idle: 50
        
    # Cache configuration
    saga-cache:
      ttl: 1h
      max-entries: 100000
    
    step-cache:
      ttl: 30m
      max-entries: 500000
```

## Best Practices

1. **Use Reactive Streams**: Leverage Project Reactor's capabilities
2. **Minimize Blocking Calls**: Avoid blocking operations in saga steps
3. **Batch Operations**: Use batching for database and event operations
4. **Cache Strategically**: Cache expensive computations and lookups
5. **Monitor Continuously**: Use metrics to identify performance bottlenecks
6. **Test Under Load**: Regular performance testing with realistic workloads
7. **Optimize Queries**: Use proper indexes and query optimization
8. **Tune JVM**: Configure garbage collection and memory settings
9. **Scale Horizontally**: Design for stateless, scalable architecture
10. **Profile Regularly**: Use profiling tools to identify optimization opportunities

## Troubleshooting Performance Issues

### Common Performance Problems

1. **High Latency**
   - Check database query performance
   - Review network latency to external services
   - Analyze thread pool utilization

2. **Memory Leaks**
   - Monitor heap usage patterns
   - Check for stuck saga instances
   - Review cache configurations

3. **Thread Starvation**
   - Increase thread pool sizes
   - Identify blocking operations
   - Review retry configurations

4. **Database Bottlenecks**
   - Optimize connection pools
   - Add proper indexes
   - Consider read replicas

### Performance Debugging

Enable performance debugging:

```yaml
transactional-engine:
  debug:
    performance: true
    timing-details: true
    resource-usage: true
    
logging:
  level:
    com.catalis.transactionalengine.performance: DEBUG
```

## See Also

- [Monitoring & Observability](monitoring.md)
- [Production Deployment](production.md)
- [Configuration Guide](configuration.md)
- [Architecture Overview](architecture.md)