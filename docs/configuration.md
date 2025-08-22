# Configuration Guide

Complete guide to configuring and customizing the Transactional Engine based on the actual implementation.

## Table of Contents

1. [Application Properties](#application-properties)
2. [Programmatic Configuration](#programmatic-configuration)
3. [Custom Components](#custom-components)
4. [Step-Level Configuration](#step-level-configuration)

## Application Properties

### Available Properties

The Transactional Engine currently supports the following configuration properties:

```properties
# Enable or disable step logging aspect (default: true)
transactionalengine.step-logging.enabled=true

# In-Memory Starter Configuration (when using lib-transactional-engine-inmemory-starter)
transactional-engine.inmemory.events.enabled=true
transactional-engine.inmemory.events.log-step-details=true
transactional-engine.inmemory.events.log-timing=true
transactional-engine.inmemory.events.log-compensation=true
transactional-engine.inmemory.events.max-events-in-memory=1000

transactional-engine.inmemory.storage.enabled=true
transactional-engine.inmemory.storage.max-saga-contexts=10000
transactional-engine.inmemory.storage.ttl=24h
transactional-engine.inmemory.storage.cleanup-interval=30m
```

```yaml
# YAML format
transactionalengine:
  step-logging:
    enabled: true  # default: true

# In-Memory Starter Configuration (YAML format)
transactional-engine:
  inmemory:
    events:
      enabled: true  # default: true
      log-step-details: true  # default: true
      log-timing: true  # default: true
      log-compensation: true  # default: true
      max-events-in-memory: 1000  # default: 1000
    storage:
      enabled: true  # default: true
      max-saga-contexts: 10000  # default: 10000
      ttl: 24h  # default: 24h
      cleanup-interval: 30m  # default: 30m
```

### In-Memory Starter Properties

When using the **In-Memory Starter** (`lib-transactional-engine-inmemory-starter`), these additional properties are available:

#### Events Configuration
- `transactional-engine.inmemory.events.enabled`: Enable/disable enhanced logging events (default: `true`)
- `transactional-engine.inmemory.events.log-step-details`: Log detailed step information (default: `true`)
- `transactional-engine.inmemory.events.log-timing`: Include timing information in logs (default: `true`)
- `transactional-engine.inmemory.events.log-compensation`: Log compensation events (default: `true`)
- `transactional-engine.inmemory.events.max-events-in-memory`: Maximum events to keep in memory for debugging (default: `1000`)

#### Storage Configuration
- `transactional-engine.inmemory.storage.enabled`: Enable in-memory storage service (default: `true`)
- `transactional-engine.inmemory.storage.max-saga-contexts`: Maximum saga contexts to keep in memory (default: `10000`)
- `transactional-engine.inmemory.storage.ttl`: Time to live for completed sagas (default: `24h`)
- `transactional-engine.inmemory.storage.cleanup-interval`: Interval for cleanup of expired saga contexts (default: `30m`)

The step logging aspect provides automatic logging for saga step execution.

## Programmatic Configuration

### Core Configuration

The engine is configured through Spring beans. The main configuration is provided by `TransactionalEngineConfiguration`:

```java
@Configuration
@EnableTransactionalEngine
public class YourAppConfig {
    // The @EnableTransactionalEngine annotation automatically imports
    // TransactionalEngineConfiguration which provides all necessary beans
}
```

### Available Beans

The engine automatically configures these beans:

#### Core Engine Components

```java
@Bean
public SagaRegistry sagaRegistry(ApplicationContext applicationContext) {
    return new SagaRegistry(applicationContext);
}

@Bean
public SagaEngine sagaEngine(SagaRegistry registry, SagaEvents events, StepEventPublisher publisher) {
    return new SagaEngine(registry, events, publisher);
}
```

#### Event and Observability Components

```java
@Bean
public SagaLoggerEvents sagaLoggerEvents() {
    return new SagaLoggerEvents();  // Always enabled
}

@Bean
@ConditionalOnMissingBean(StepEventPublisher.class)
public StepEventPublisher stepEventPublisher(ApplicationEventPublisher appPublisher) {
    return new ApplicationEventStepEventPublisher(appPublisher);
}
```

#### Optional Observability Components

These components are automatically configured if the required dependencies are present:

```java
// Micrometer metrics - enabled if MeterRegistry is available
@Bean
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
public SagaMicrometerEvents sagaMicrometerEvents(MeterRegistry registry) {
    return new SagaMicrometerEvents(registry);
}

// Distributed tracing - enabled if Tracer is available  
@Bean
@ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
public SagaTracingEvents sagaTracingEvents(Tracer tracer) {
    return new SagaTracingEvents(tracer);
}
```

## Custom Components

### Overriding Default Components

You can provide your own implementations of any component by defining beans in your configuration:

```java
@Configuration
@EnableTransactionalEngine
public class CustomTransactionalEngineConfig {

    @Bean
    @Primary
    public StepEventPublisher customStepEventPublisher() {
        // Your custom implementation
        return new CustomStepEventPublisher();
    }

    @Bean
    @Primary
    public SagaEvents customSagaEvents() {
        // Your custom implementation
        return new CustomSagaEvents();
    }
}
```

### WebClient Configuration

The engine provides a default WebClient.Builder bean:

```java
@Bean
public WebClient.Builder webClientBuilder() {
    return WebClient.builder();
}
```

You can override this to customize HTTP client behavior:

```java
@Bean
@Primary
public WebClient.Builder customWebClientBuilder() {
    return WebClient.builder()
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024))
        .defaultHeader("User-Agent", "MyApp/1.0");
}
```

## Step-Level Configuration

Most configuration is done at the step level using annotation parameters:

### @SagaStep Parameters

```java
@SagaStep(
    id = "my-step",
    compensate = "undoMyStep",           // Optional compensation method
    dependsOn = {"other-step"},          // Step dependencies
    retry = 3,                           // Number of retries (default: 0)
    timeoutMs = 30000,                   // Timeout in milliseconds (default: -1, disabled)
    backoffMs = 1000,                    // Backoff delay in ms (default: -1, inherit default)
    jitter = true,                       // Enable jitter for backoff (default: false)
    jitterFactor = 0.5,                  // Jitter factor 0.0-1.0 (default: 0.5)
    idempotencyKey = "unique-key",       // Idempotency key (default: "")
    cpuBound = false,                    // Hint for CPU-bound work (default: false)
    
    // Compensation-specific overrides
    compensationRetry = 5,               // Retry attempts for compensation (default: -1)
    compensationTimeoutMs = 60000,       // Compensation timeout (default: -1)
    compensationBackoffMs = 2000,        // Compensation backoff (default: -1)
    compensationCritical = true          // Mark compensation as critical (default: false)
)
public Mono<String> myStep(@Input("data") String data) {
    // Step implementation
}
```

### @Saga Parameters

```java
@Saga(
    name = "my-saga",              // Required: saga identifier
    layerConcurrency = 5           // Optional: max concurrent steps per layer (default: 0, unbounded)
)
public class MySaga {
    // Saga steps
}
```

### Default Values

- **Retry**: 0 (no retries)
- **Timeout**: Disabled (0)
- **Backoff**: 100ms (when using retry)
- **Jitter**: Disabled
- **Layer Concurrency**: Unbounded

### Example Configuration

```java
@Component
@Saga(name = "order-processing", layerConcurrency = 3)
public class OrderProcessingSaga {

    @SagaStep(
        id = "validate-payment",
        retry = 3,
        timeoutMs = 10000,
        backoffMs = 1000,
        jitter = true
    )
    public Mono<PaymentResult> validatePayment(@Input("paymentInfo") PaymentInfo payment) {
        return paymentService.validate(payment);
    }

    @SagaStep(
        id = "reserve-inventory", 
        compensate = "releaseInventory",
        dependsOn = {"validate-payment"},
        retry = 2,
        timeoutMs = 5000
    )
    public Mono<ReservationResult> reserveInventory(@Input("items") List<OrderItem> items) {
        return inventoryService.reserve(items);
    }

    @CompensationSagaStep
    public Mono<Void> releaseInventory(@FromStep("reserve-inventory") ReservationResult reservation) {
        return inventoryService.release(reservation.getId());
    }
}
```

## Monitoring and Observability

### Built-in Logging

The engine provides automatic logging through `SagaLoggerEvents` which is always enabled. You can disable step method logging by setting:

```properties
transactionalengine.step-logging.enabled=false
```

### Metrics Integration

If Micrometer is on the classpath, metrics are automatically enabled through `SagaMicrometerEvents`. No additional configuration is needed.

### Distributed Tracing

If Micrometer Tracing is available, distributed tracing is automatically enabled through `SagaTracingEvents`. No additional configuration is needed.

### Event Publishing

Step events are published through Spring's `ApplicationEventPublisher` by default. You can listen for these events:

```java
@EventListener
public void handleStepEvent(StepEventEnvelope event) {
    // Handle step events
    log.info("Step {} completed with status {}", event.getStepId(), event.getStatus());
}
```

## Conclusion

The Transactional Engine favors convention over configuration. Most behavior is controlled through:

1. **Annotation parameters** on `@SagaStep` and `@Saga`
2. **Bean replacement** for custom implementations
3. **Automatic detection** of observability libraries

The minimal configuration approach keeps setup simple while providing flexibility where needed.