# Architecture Guide

This document describes the architecture and design principles of the Firefly Transactional Engine.

## Overview

The Firefly Transactional Engine is built on a modular, reactive architecture that supports both SAGA and TCC distributed transaction patterns. The system is designed for high performance, reliability, and ease of use in Spring Boot applications.

## Core Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Application Layer                        │
├─────────────────────────────────────────────────────────────┤
│  @Saga Classes    │  @Tcc Classes    │  SagaComposition     │
├─────────────────────────────────────────────────────────────┤
│                    Engine Layer                             │
├─────────────────────────────────────────────────────────────┤
│  SagaEngine       │  TccEngine       │  SagaCompositor      │
├─────────────────────────────────────────────────────────────┤
│                    Orchestration Layer                      │
├─────────────────────────────────────────────────────────────┤
│  SagaExecutionOrchestrator  │  TccExecutionOrchestrator     │
├─────────────────────────────────────────────────────────────┤
│                    Infrastructure Layer                     │
├─────────────────────────────────────────────────────────────┤
│  Persistence  │  Events  │  Context  │  Validation  │ AOP   │
└─────────────────────────────────────────────────────────────┘
```

## Package Structure

```
com.firefly.transactional/
├── saga/                          # SAGA pattern implementation
│   ├── annotations/               # @Saga, @SagaStep annotations
│   ├── core/                      # SagaContext, SagaResult
│   ├── engine/                    # SagaEngine, orchestration
│   ├── registry/                  # SagaRegistry, definitions
│   └── composition/               # SagaCompositor, workflows
├── tcc/                           # TCC pattern implementation (isolated)
│   ├── annotations/               # @Tcc, @TccParticipant, @FromTry, @Header, @Input
│   ├── core/                      # TccContext, TccResult
│   ├── engine/                    # TccEngine, TccArgumentResolver, orchestration
│   ├── registry/                  # TccRegistry, definitions
│   └── persistence/               # TCC-specific persistence providers and state models
└── shared/                        # Shared infrastructure
    ├── annotations/               # @EnableTransactionalEngine
    ├── config/                    # Spring Boot auto-configuration
    ├── persistence/               # Persistence providers
    ├── events/                    # Event system
    ├── observability/             # Metrics, tracing
    ├── validation/                # Runtime validation
    └── util/                      # Utilities
```

## Key Components

### SagaEngine

The main orchestrator for SAGA pattern execution:

- **SagaRegistry**: Discovers and indexes @Saga annotated classes
- **SagaExecutionOrchestrator**: Handles step execution topology
- **SagaCompensator**: Manages compensation logic
- **StepInvoker**: Invokes step methods with retry/timeout

### TccEngine

The main orchestrator for TCC pattern execution with complete infrastructure isolation:

- **TccRegistry**: Discovers and indexes @Tcc annotated classes
- **TccExecutionOrchestrator**: Handles try-confirm-cancel phases
- **TccParticipantInvoker**: Invokes participant methods with TCC-specific argument resolution
- **TccArgumentResolver**: Resolves method parameters using TCC-specific annotations
- **TccPersistenceProvider**: TCC-specific persistence interface (isolated from SAGA)

### SagaCompositor

Orchestrates multiple sagas into coordinated workflows:

- **CompositionBuilder**: Fluent API for building compositions
- **CompositionExecutionOrchestrator**: Executes composition workflows
- **CompositionValidator**: Validates composition definitions

### Persistence Layer

Provides state persistence and recovery with pattern-specific implementations:

**Shared Infrastructure:**
- **TransactionalPersistenceProvider<T>**: Generic persistence interface
- **SagaRecoveryService**: Handles recovery of in-flight transactions

**SAGA-Specific:**
- **SagaPersistenceProvider**: SAGA persistence interface
- **InMemorySagaPersistenceProvider**: In-memory SAGA implementation
- **RedisSagaPersistenceProvider**: Redis-based SAGA persistence

**TCC-Specific:**
- **TccPersistenceProvider**: TCC persistence interface (isolated from SAGA)
- **InMemoryTccPersistenceProvider**: In-memory TCC implementation
- **RedisTccPersistenceProvider**: Redis-based TCC persistence

### Event System

Comprehensive observability and integration:

- **SagaEvents**: Core event interface
- **SagaLoggerEvents**: Default logging implementation
- **SagaTracingEvents**: Micrometer tracing integration
- **StepEventPublisher**: Custom event publishing

## Design Principles

### 1. Pattern Isolation

Complete infrastructure isolation between SAGA and TCC patterns:

**Isolation Principle:**
- Each pattern has its own infrastructure components
- No cross-pattern dependencies in core execution logic
- Only persistence layer interface and observability interfaces are shared

**SAGA Infrastructure:**
```
SagaEngine → SagaExecutionOrchestrator → SagaParticipantInvoker → SagaArgumentResolver
                                      ↓
                                 SagaPersistenceProvider
```

**TCC Infrastructure:**
```
TccEngine → TccExecutionOrchestrator → TccParticipantInvoker → TccArgumentResolver
                                    ↓
                               TccPersistenceProvider
```

**Shared Components:**
- `TransactionalPersistenceProvider<T>` - Generic persistence interface
- `SagaEvents` / `TccEvents` - Observability interfaces
- Configuration and validation utilities

### 2. Reactive Architecture

Built on Project Reactor for non-blocking operations:

```java
public Mono<SagaResult> execute(String sagaName, StepInputs inputs) {
    return Mono.defer(() -> {
        SagaDefinition saga = registry.getSaga(sagaName);
        return orchestrator.orchestrate(saga, inputs, context);
    });
}
```

### 2. Type Safety

Method reference support for compile-time validation:

```java
// Type-safe execution
Mono<SagaResult> result = sagaEngine.execute(PaymentSaga::processPayment, inputs);

// Type-safe input building
StepInputs inputs = StepInputs.builder()
    .forStep(PaymentSaga::validate, request)
    .forStep(PaymentSaga::process, processData)
    .build();
```

### 3. Immutable State

All state objects are immutable for thread safety:

```java
public final class SagaExecutionState implements Serializable {
    private final String correlationId;
    private final String sagaName;
    private final SagaExecutionStatus status;
    // ... other immutable fields
}
```

### 4. Pluggable Architecture

Key components are pluggable via interfaces:

```java
// Custom persistence provider
@Component
public class CustomPersistenceProvider implements SagaPersistenceProvider {
    // Implementation
}

// Custom event publisher
@Component
public class KafkaEventPublisher implements StepEventPublisher {
    // Implementation
}
```

### 5. Spring Boot Integration

Comprehensive auto-configuration:

```java
@AutoConfiguration
@ConditionalOnClass(SagaEngine.class)
@EnableConfigurationProperties(TransactionalEngineProperties.class)
public class TransactionalEngineConfiguration {
    // Auto-configuration beans
}
```

## Execution Flow

### SAGA Execution

1. **Discovery**: SagaRegistry scans for @Saga classes at startup
2. **Validation**: SagaValidationService validates definitions
3. **Execution**: SagaExecutionOrchestrator executes steps in topology order
4. **Persistence**: State is persisted at key checkpoints
5. **Compensation**: Failed sagas trigger compensation in reverse order
6. **Events**: Observability events are emitted throughout

### TCC Execution

1. **Discovery**: TccRegistry scans for @Tcc classes at startup
2. **Try Phase**: All participants execute try methods
3. **Decision**: If all try methods succeed, proceed to confirm; otherwise cancel
4. **Confirm/Cancel Phase**: Execute appropriate methods on all participants
5. **Persistence**: State is persisted using SAGA persistence infrastructure
6. **Events**: Observability events are emitted throughout

## Performance Characteristics

### Context Optimization

Automatic optimization based on saga topology:

```java
// Sequential execution uses HashMap for better performance
SagaContext sequential = SagaContextFactory.createSequential(sagaName);

// Concurrent execution uses ConcurrentHashMap for thread safety
SagaContext concurrent = SagaContextFactory.createConcurrent(sagaName);
```

### Backpressure Handling

Pluggable backpressure strategies:

```java
// Batched processing
BackpressureStrategy batched = new BatchedBackpressureStrategy(config);

// Adaptive concurrency
BackpressureStrategy adaptive = new AdaptiveBackpressureStrategy(config);
```

### Memory Management

Efficient memory usage through:

- Immutable state objects
- Lazy evaluation of step inputs
- Configurable cleanup of completed sagas
- Optional persistence to external storage

## Error Handling

### Compensation Strategies

Multiple compensation policies available:

- **STRICT_SEQUENTIAL**: Compensate in reverse order
- **GROUPED_PARALLEL**: Compensate by dependency layers
- **RETRY_WITH_BACKOFF**: Retry failed compensations
- **BEST_EFFORT_PARALLEL**: Continue on compensation errors

### Validation

Comprehensive validation at multiple levels:

- **Compile-time**: Annotation processor validation
- **Startup**: Definition validation during application startup
- **Runtime**: Input validation before execution

### Recovery

Automatic recovery mechanisms:

- **In-flight Recovery**: Resume interrupted sagas after restart
- **Stale Detection**: Identify and handle aged sagas
- **Health Monitoring**: Built-in health checks for persistence

## Testing Strategy

### Unit Testing

Comprehensive unit tests for all components:

```java
@Test
void shouldExecuteSuccessfulSaga() {
    SagaResult result = sagaEngine.execute("test-saga", inputs).block();
    assertTrue(result.isSuccess());
}
```

### Integration Testing

Full Spring Boot context integration tests:

```java
@SpringBootTest
@EnableTransactionalEngine
class SagaIntegrationTest {
    // Integration tests
}
```

### Testcontainers

Redis persistence testing with Testcontainers:

```java
@Testcontainers
class RedisPersistenceTest {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine");
}
```

## Monitoring and Observability

### Metrics

Built-in metrics collection:

- Saga execution counts and durations
- Step success/failure rates
- Compensation execution statistics
- Context optimization rates

### Tracing

Micrometer tracing integration:

```java
@Component
public class SagaTracingEvents implements SagaEvents {
    // Tracing implementation
}
```

### Health Checks

Spring Boot Actuator integration:

```java
@Component
@Endpoint(id = "saga-health")
public class SagaHealthEndpoint {
    // Health check implementation
}
```

## Security Considerations

### Input Validation

All inputs are validated before execution:

```java
@Service
public class SagaValidationService {
    public void validateSagaInputs(SagaDefinition saga, Object inputs) {
        // Validation logic
    }
}
```

### Context Isolation

Each saga execution has isolated context:

```java
public class SagaContext {
    // Thread-safe, isolated context per execution
}
```

### Serialization Security

Safe serialization with Jackson:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
public class SerializableSagaContext {
    // Safe serialization
}
```
