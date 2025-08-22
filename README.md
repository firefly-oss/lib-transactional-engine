# Firefly Transactional Engine

A powerful, reactive Saga orchestration engine for Spring Boot 3 applications, providing distributed transaction management with compensation-based error recovery and comprehensive AWS cloud integrations.

## 🚀 Overview

The Transactional Engine is a modern, annotation-driven framework that simplifies distributed transaction management in microservices architectures. Built on Spring Boot 3 and Project Reactor, it provides:

- **Saga Pattern Implementation**: Orchestrate complex distributed transactions with automatic compensation
- **Reactive Programming**: Built on Project Reactor for non-blocking, scalable operations
- **Rich DSL**: Annotation-based configuration with programmatic alternatives
- **AWS Integration**: Native support for CloudWatch, Kinesis, SQS, and DynamoDB
- **Comprehensive Observability**: Built-in metrics, logging, and event publishing
- **Production Ready**: Optimizations, resilience patterns, and monitoring capabilities

## 📦 Modules

### Core Module (`lib-transactional-engine-core`)
The foundation library providing:
- Saga execution engine
- Annotation-based DSL
- Step orchestration and compensation
- Event publishing framework
- Observability and monitoring
- Redis, ActiveMQ, and RabbitMQ integrations

### AWS Starter (`lib-transactional-engine-aws-starter`)
Spring Boot auto-configuration for AWS services:
- CloudWatch metrics and logging
- Kinesis event streaming
- SQS message publishing
- DynamoDB persistence support
- Auto-configuration with sensible defaults

### Azure Starter (`lib-transactional-engine-azure-starter`)
Spring Boot auto-configuration for Azure services:
- Application Insights metrics and logging
- Event Hubs event streaming
- Service Bus message publishing
- Cosmos DB persistence support
- Auto-configuration with sensible defaults

### In-Memory Starter (`lib-transactional-engine-inmemory-starter`)
Vanilla Spring Boot implementation for development and small-scale deployments:
- Structured JSON logging for production-ready observability
- In-memory event storage for debugging
- Configurable logging levels (step details, timing, compensation)
- No external dependencies - perfect for getting started
- Ideal for development, testing, and proof-of-concept projects

## 🏃 Quick Start

### 1. Add Dependencies

For basic usage:
```xml
<dependency>
    <groupId>com.catalis</groupId>
    <artifactId>lib-transactional-engine-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

For AWS integration:
```xml
<dependency>
    <groupId>com.catalis</groupId>
    <artifactId>lib-transactional-engine-aws-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

For Azure integration:
```xml
<dependency>
    <groupId>com.catalis</groupId>
    <artifactId>lib-transactional-engine-azure-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

For in-memory development/testing (recommended for getting started):
```xml
<dependency>
    <groupId>com.catalis</groupId>
    <artifactId>lib-transactional-engine-inmemory-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Enable the Engine

```java
@SpringBootApplication
@EnableTransactionalEngine
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3. Define Your First Saga

```java
@Component
@Saga("order-processing")
public class OrderProcessingSaga {
    
    @SagaStep(id = "validate-payment")
    public Mono<PaymentResult> validatePayment(@Input("orderId") String orderId) {
        return paymentService.validate(orderId);
    }
    
    @SagaStep(id = "reserve-inventory", 
              dependsOn = "validate-payment",
              compensate = "releaseInventory")
    public Mono<ReservationResult> reserveInventory(
            @FromStep("validate-payment") PaymentResult payment) {
        return inventoryService.reserve(payment.getItems());
    }
    
    @CompensationSagaStep
    public Mono<Void> releaseInventory(
            @FromStep("reserve-inventory") ReservationResult reservation) {
        return inventoryService.release(reservation.getReservationId());
    }
}
```

### 4. Execute the Saga

```java
@RestController
public class OrderController {
    
    private final SagaEngine sagaEngine;
    
    @PostMapping("/orders")
    public Mono<SagaResult> createOrder(@RequestBody CreateOrderRequest request) {
        return sagaEngine.execute("order-processing", 
            StepInputs.of("orderId", request.getOrderId()));
    }
}
```

## 📚 Documentation

### Core Concepts
- **[Getting Started Guide](docs/getting-started.md)** - Step-by-step tutorial with examples
- **[Architecture Overview](docs/architecture.md)** - Engine internals and design principles
- **[Saga Patterns](docs/saga-patterns.md)** - Saga vs TCC patterns and when to use each

### Development
- **[API Reference](docs/api-reference.md)** - Complete annotation and method reference
- **[Configuration Guide](docs/configuration.md)** - Properties, customization, and tuning
- **[Examples](docs/examples.md)** - Real-world use cases and patterns

### AWS Integration
- **[AWS Setup](docs/aws-integration.md)** - CloudWatch, Kinesis, SQS, and DynamoDB configuration
- **[Monitoring & Observability](docs/monitoring.md)** - Metrics, logging, and distributed tracing

### Azure Integration
- **[Azure Setup](docs/azure-integration.md)** - Application Insights, Event Hubs, Service Bus, and Cosmos DB configuration

### Advanced Topics
- **[Performance Optimization](docs/performance.md)** - Tuning for high-throughput scenarios
- **[Production Deployment](docs/production.md)** - Best practices and operational considerations

## 🔧 Key Features

### Annotation-Driven DSL
Define complex workflows with simple annotations:
```java
@SagaStep(stepId = "process-payment", 
          dependsOn = {"validate-order", "check-inventory"},
          compensation = "refundPayment",
          retryPolicy = @Retry(maxAttempts = 3))
public Mono<PaymentResult> processPayment(@Input("amount") BigDecimal amount) {
    // Implementation
}
```

### Programmatic API
Alternative to annotations for dynamic workflows:
```java
sagaEngine.execute(OrderProcessingSaga::validatePayment, inputs)
    .flatMap(result -> sagaEngine.execute(OrderProcessingSaga::processPayment, 
        StepInputs.from(result)));
```

### Event-Driven Architecture
Publish and consume step events:
```java
@SagaStep(id = "create-order")
@StepEvent(topic = "order-events", 
           type = "ORDER_CREATED")
public Mono<Order> createOrder(@Input("orderData") OrderData data) {
    return orderService.create(data);
}
```

### Compensation Strategies
Multiple compensation policies:
- `STRICT_SEQUENTIAL`: Execute compensations in strict reverse order
- `GROUPED_PARALLEL`: Execute compensations in parallel groups
- `RETRY_WITH_BACKOFF`: Retry failed compensations with exponential backoff
- `CIRCUIT_BREAKER`: Use circuit breaker pattern for compensations
- `BEST_EFFORT_PARALLEL`: Execute all compensations in parallel with best effort

### Reactive & Non-Blocking
Built on Project Reactor:
- Non-blocking I/O
- Backpressure handling
- Parallel execution support
- Resource efficient

## 🌟 Why Choose Transactional Engine?

- **🎯 Focused**: Purpose-built for distributed transaction orchestration
- **⚡ Performant**: Reactive architecture with optimization features
- **🔧 Flexible**: Annotation DSL with programmatic alternatives
- **☁️ Cloud Native**: First-class AWS integration with auto-configuration
- **📊 Observable**: Comprehensive metrics, logging, and event publishing
- **🛡️ Resilient**: Built-in retry policies, circuit breakers, and compensation
- **🧪 Testable**: Rich testing support with mocking and simulation
- **📈 Scalable**: Designed for high-throughput, low-latency scenarios

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guidelines](CONTRIBUTING.md) for details.

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 🆘 Support

- **Documentation**: [Full documentation](docs/)
- **Issues**: [GitHub Issues](https://github.com/catalis/lib-transactional-engine/issues)
- **Discussions**: [GitHub Discussions](https://github.com/catalis/lib-transactional-engine/discussions)

---

**Built with ❤️ by the Firefly Team**