# API Reference

Complete reference for all annotations, classes, and methods in the Transactional Engine framework.

## Table of Contents

1. [Core Annotations](#core-annotations)
2. [Core Classes](#core-classes)
3. [Configuration Properties](#configuration-properties)
4. [AWS Integration](#aws-integration)
5. [Events and Observability](#events-and-observability)
6. [Utility Classes](#utility-classes)

## Core Annotations

### @EnableTransactionalEngine

Enables the Transactional Engine for the Spring Boot application.

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(TransactionalEngineConfiguration.class)
public @interface EnableTransactionalEngine {
    /**
     * Whether to enable auto-optimization of saga execution graphs.
     * Default: true
     */
    boolean autoOptimization() default true;
    
    /**
     * Default compensation policy for sagas.
     * Default: COMPENSATE_COMPLETED
     */
    CompensationPolicy compensationPolicy() default CompensationPolicy.COMPENSATE_COMPLETED;
}
```

**Usage:**
```java
@SpringBootApplication
@EnableTransactionalEngine(
    autoOptimization = true,
    compensationPolicy = CompensationPolicy.COMPENSATE_ALL
)
public class Application {
    // ...
}
```

### @Saga

Marks a class as a saga definition containing orchestrated steps.

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Saga {
    /**
     * Saga identifier used to execute the saga via SagaEngine.execute(name, ...).
     */
    String name();
    
    /**
     * Optional cap for the number of steps executed concurrently within the same layer. 0 means unbounded.
     */
    int layerConcurrency() default 0;
}
```

**Usage:**
```java
@Saga(name = "order-processing")
public class OrderProcessingSaga {
    // Saga steps defined here
}
```

### @SagaStep

Defines a step within a saga workflow.

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SagaStep {
    /**
     * Unique step identifier within the saga.
     */
    String id();
    
    /**
     * Optional name of an in-class compensation method. Leave empty when using @CompensationSagaStep.
     */
    String compensate() default "";
    
    /**
     * Steps that must complete before this step can execute.
     */
    String[] dependsOn() default {};
    
    /**
     * Number of retry attempts. Default is 0 (no retries).
     */
    int retry() default 0;
    
    /**
     * Backoff between retries (milliseconds). -1 = inherit default.
     */
    long backoffMs() default -1;
    
    /**
     * Per-attempt timeout (milliseconds). 0 = disabled; -1 = inherit default.
     */
    long timeoutMs() default -1;
    
    /**
     * Optional jitter configuration: when true, backoff delay will be randomized by jitterFactor.
     */
    boolean jitter() default false;
    
    /**
     * Jitter factor in range [0.0, 1.0]. e.g., 0.5 means +/-50% around backoff.
     */
    double jitterFactor() default 0.5d;
    
    /**
     * Idempotency key for the step.
     */
    String idempotencyKey() default "";
    
    /**
     * Hint that this step performs CPU-bound work and can be scheduled on a CPU scheduler.
     */
    boolean cpuBound() default false;
    
    // Compensation-specific overrides (optional). Use negative values to indicate "inherit from step".
    int compensationRetry() default -1;
    long compensationTimeoutMs() default -1;
    long compensationBackoffMs() default -1;
    boolean compensationCritical() default false;
}
```

**Usage:**
```java
@SagaStep(
    id = "validate-payment",
    dependsOn = {"lookup-customer"},
    compensate = "cancelPaymentValidation",
    retry = 3,
    backoffMs = 1000,
    timeoutMs = 30000,
    jitter = true
)
public Mono<PaymentValidationResult> validatePayment(@Input("customerId") String customerId) {
    // Implementation
}
```

### @CompensationSagaStep

Declares a compensation method for a saga step that may live outside of the orchestrator class.

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CompensationSagaStep {
    /**
     * The saga name as declared in @Saga#name().
     */
    String saga();
    
    /**
     * The step id to compensate, as declared in @SagaStep#id().
     */
    String forStepId();
}
```

**Usage:**
```java
@CompensationSagaStep(saga = "OrderSaga", forStepId = "reserveFunds")
public Mono<Void> releaseFunds(ReserveCmd cmd, SagaContext ctx) {
    // Compensation logic
}
```

### Input/Output Annotations

#### @Input
Injects saga input parameters into step methods.

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Input {
    /**
     * Input parameter name. Supports nested properties using dot notation.
     */
    String value();
    
    /**
     * Whether this input is required.
     */
    boolean required() default true;
    
    /**
     * Default value if input is missing.
     */
    String defaultValue() default "";
}
```

**Usage:**
```java
public Mono<Result> processOrder(
    @Input("orderId") String orderId,
    @Input("customer.email") String customerEmail,
    @Input("metadata.priority") @Required String priority
) {
    // Implementation
}
```

#### @FromStep
Injects the result of a previous step.

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FromStep {
    /**
     * Step ID whose result to inject.
     */
    String value();
    
    /**
     * Property path within the step result.
     */
    String property() default "";
    
    /**
     * Whether this dependency is required.
     */
    boolean required() default true;
}
```

#### @Variable / @Variables
Accesses saga context variables.

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Variable {
    String value();
    boolean required() default true;
    String defaultValue() default "";
}

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Variables {
    // Injects all variables as Map<String, Object>
}
```

#### @SetVariable
Sets variables in the saga context from step results.

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(SetVariables.class)
public @interface SetVariable {
    /**
     * Variable name to set.
     */
    String name();
    
    /**
     * Property path from step result to extract value.
     */
    String fromResult() default "";
    
    /**
     * Static value to set.
     */
    String value() default "";
}
```

#### @Header / @Headers
Accesses saga context headers.

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Header {
    String value();
    boolean required() default false;
    String defaultValue() default "";
}

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Headers {
    // Injects all headers as Map<String, String>
}
```

### Event Annotations

#### @StepEvent
Publishes events during step execution.

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface StepEvent {
    /**
     * Logical destination (e.g., topic/queue/exchange). Adapter interprets accordingly.
     */
    String topic();
    
    /**
     * Optional event type name for consumers.
     */
    String type() default "";
    
    /**
     * Optional partition/routing key; adapters may use or ignore it.
     */
    String key() default "";
    
    /**
     * Allows turning off publication without removing the annotation.
     */
    boolean enabled() default true;
}
```

### Error Handling Annotations

#### @Retry
Configures retry behavior for steps.

```java
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Retry {
    /**
     * Maximum number of retry attempts.
     */
    int maxAttempts() default 3;
    
    /**
     * Initial backoff delay.
     */
    String backoffDelay() default "1s";
    
    /**
     * Backoff multiplier for exponential backoff.
     */
    double backoffMultiplier() default 2.0;
    
    /**
     * Maximum backoff delay.
     */
    String maxBackoffDelay() default "30s";
    
    /**
     * Exception types that should trigger retry.
     */
    Class<? extends Throwable>[] retryOn() default {};
    
    /**
     * Exception types that should NOT trigger retry.
     */
    Class<? extends Throwable>[] noRetryOn() default {};
}
```

#### @CompensationError
Injects the error that triggered compensation.

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CompensationError {
    /**
     * Whether to include the full exception stack trace.
     */
    boolean includeStackTrace() default false;
}
```

## Core Classes

### SagaEngine

The main orchestration engine for executing sagas.

```java
public class SagaEngine {
    // Constructors
    public SagaEngine(SagaRegistry registry, SagaEvents events);
    public SagaEngine(SagaRegistry registry, SagaEvents events, CompensationPolicy policy);
    public SagaEngine(SagaRegistry registry, SagaEvents events, StepEventPublisher stepEventPublisher);
    public SagaEngine(SagaRegistry registry, SagaEvents events, CompensationPolicy policy, 
                     StepEventPublisher stepEventPublisher, boolean autoOptimizationEnabled);
    
    // Execution methods
    public Mono<SagaResult> execute(String sagaName, StepInputs inputs);
    public Mono<SagaResult> execute(String sagaName, StepInputs inputs, SagaContext ctx);
    public Mono<SagaResult> execute(Class<?> sagaClass, StepInputs inputs);
    public Mono<SagaResult> execute(Class<?> sagaClass, StepInputs inputs, SagaContext ctx);
    
    // Method reference execution
    public <A, R> Mono<SagaResult> execute(Fn1<A, R> methodRef, StepInputs inputs, SagaContext ctx);
    public <A, B, R> Mono<SagaResult> execute(Fn2<A, B, R> methodRef, StepInputs inputs, SagaContext ctx);
    public <A, B, C, R> Mono<SagaResult> execute(Fn3<A, B, C, R> methodRef, StepInputs inputs, SagaContext ctx);
    public <A, B, C, D, R> Mono<SagaResult> execute(Fn4<A, B, C, D, R> methodRef, StepInputs inputs, SagaContext ctx);
    
    // Convenience methods without context
    public <A, R> Mono<SagaResult> execute(Fn1<A, R> methodRef, StepInputs inputs);
    public <A, B, R> Mono<SagaResult> execute(Fn2<A, B, R> methodRef, StepInputs inputs);
    public <A, B, C, R> Mono<SagaResult> execute(Fn3<A, B, C, R> methodRef, StepInputs inputs);
    public <A, B, C, D, R> Mono<SagaResult> execute(Fn4<A, B, C, D, R> methodRef, StepInputs inputs);
    
    // Legacy method
    @Deprecated
    public Mono<SagaResult> execute(String sagaName, Map<String, Object> stepInputs);
}
```

### StepInputs

Builder for saga input parameters.

```java
public class StepInputs {
    // Static factory methods
    public static StepInputs empty();
    public static StepInputs of(String key, Object value);
    public static StepInputs of(String key1, Object value1, String key2, Object value2);
    public static StepInputs from(Map<String, Object> inputs);
    public static StepInputs from(Object bean); // Uses reflection to extract properties
    
    // Builder
    public static Builder builder();
    
    // Instance methods
    public StepInputs input(String key, Object value);
    public StepInputs inputs(Map<String, Object> inputs);
    public StepInputs header(String key, String value);
    public StepInputs headers(Map<String, String> headers);
    public StepInputs variable(String key, Object value);
    public StepInputs variables(Map<String, Object> variables);
    
    // Access methods
    public Object getInput(String key);
    public <T> T getInput(String key, Class<T> type);
    public String getHeader(String key);
    public Object getVariable(String key);
    public <T> T getVariable(String key, Class<T> type);
    
    public Map<String, Object> getAllInputs();
    public Map<String, String> getAllHeaders();
    public Map<String, Object> getAllVariables();
    
    // Builder class
    public static class Builder {
        public Builder input(String key, Object value);
        public Builder inputs(Map<String, Object> inputs);
        public Builder header(String key, String value);
        public Builder headers(Map<String, String> headers);
        public Builder variable(String key, Object value);
        public Builder variables(Map<String, Object> variables);
        public StepInputs build();
    }
}
```

### SagaResult

Result of saga execution containing step results and status information.

```java
public class SagaResult {
    // Status methods
    public boolean isSuccessful();
    public boolean isCompensated();
    public boolean hasErrors();
    
    // Step information
    public List<String> getCompletedSteps();
    public List<String> getFailedSteps();
    public List<String> getCompensatedSteps();
    public List<String> getSkippedSteps();
    
    // Results access
    public <T> T getStepResult(String stepId, Class<T> type);
    public Object getStepResult(String stepId);
    public Map<String, Object> getAllStepResults();
    
    // Error information
    public List<StepError> getErrors();
    public Optional<Throwable> getLastError();
    
    // Timing information
    public Duration getExecutionTime();
    public Instant getStartTime();
    public Instant getEndTime();
    
    // Context information
    public SagaContext getContext();
    public String getSagaId();
    public String getSagaName();
}
```

### SagaContext

Runtime context for saga execution.

```java
public class SagaContext {
    // Identification
    public String getSagaId();
    public String getSagaName();
    public String getCorrelationId();
    
    // Variables
    public void setVariable(String key, Object value);
    public <T> T getVariable(String key, Class<T> type);
    public Object getVariable(String key);
    public Map<String, Object> getAllVariables();
    public boolean hasVariable(String key);
    public void removeVariable(String key);
    
    // Headers
    public void setHeader(String key, String value);
    public String getHeader(String key);
    public Map<String, String> getAllHeaders();
    public boolean hasHeader(String key);
    public void removeHeader(String key);
    
    // Step results
    public void setStepResult(String stepId, Object result);
    public <T> T getStepResult(String stepId, Class<T> type);
    public Object getStepResult(String stepId);
    public boolean hasStepResult(String stepId);
    
    // Status tracking
    public StepStatus getStepStatus(String stepId);
    public List<String> getCompletedSteps();
    public List<String> getFailedSteps();
    public List<String> getPendingSteps();
    
    // Timing
    public Instant getStartTime();
    public Duration getElapsedTime();
    
    // Factory methods
    public static SagaContext create(String sagaName);
    public static SagaContext create(String sagaName, String correlationId);
    public SagaContext copy();
}
```

### CompensationPolicy

Enumeration defining compensation strategies.

```java
public enum CompensationPolicy {
    /**
     * Execute compensations in strict reverse order.
     */
    STRICT_SEQUENTIAL,
    
    /**
     * Execute compensations in parallel groups.
     */
    GROUPED_PARALLEL,
    
    /**
     * Retry failed compensations with exponential backoff.
     */
    RETRY_WITH_BACKOFF,
    
    /**
     * Use circuit breaker pattern for compensations.
     */
    CIRCUIT_BREAKER,
    
    /**
     * Execute all compensations in parallel with best effort.
     */
    BEST_EFFORT_PARALLEL
}
```

### StepStatus

Enumeration representing step execution status.

```java
public enum StepStatus {
    PENDING,        // Step is waiting to execute
    RUNNING,        // Step is currently executing
    DONE,           // Step completed successfully
    FAILED,         // Step failed with an error
    COMPENSATED     // Step compensation completed
}
```

## Configuration Properties

### Core Properties

```yaml
transactional-engine:
  # Default compensation policy
  compensation-policy: COMPENSATE_COMPLETED
  
  # Enable auto-optimization of saga graphs
  auto-optimization-enabled: true
  
  # Default step timeout
  default-step-timeout: 30s
  
  # Default retry configuration
  default-retry:
    max-attempts: 3
    backoff-delay: 1s
    backoff-multiplier: 2.0
    max-backoff-delay: 30s
  
  # Thread pool configuration
  executor:
    core-pool-size: 10
    max-pool-size: 50
    queue-capacity: 1000
    thread-name-prefix: "saga-"
  
  # Observability
  observability:
    enabled: true
    metrics-enabled: true
    tracing-enabled: true
```

## AWS Integration

### AWS Auto-Configuration

The AWS starter provides auto-configuration for AWS services:

```java
@ConfigurationProperties(prefix = "transactional-engine.aws")
public class AwsTransactionalEngineProperties {
    private DynamoDbProperties dynamodb = new DynamoDbProperties();
    private CloudWatchProperties cloudwatch = new CloudWatchProperties();
    private KinesisProperties kinesis = new KinesisProperties();
    private SqsProperties sqs = new SqsProperties();
    
    // Getters and setters...
}
```

### AWS Properties

```yaml
transactional-engine:
  aws:
    # DynamoDB configuration
    dynamodb:
      enabled: true
      table-name: saga-execution
      region: us-east-1
      
    # CloudWatch configuration
    cloudwatch:
      enabled: true
      namespace: TransactionalEngine
      region: us-east-1
      
    # Kinesis configuration
    kinesis:
      enabled: true
      stream-name: saga-events
      region: us-east-1
      partition-key: sagaId
      
    # SQS configuration
    sqs:
      enabled: true
      queue-name: saga-step-events
      region: us-east-1
```

### AWS Components

#### CloudWatchSagaEvents
Publishes saga metrics to CloudWatch.

```java
@Component
@ConditionalOnProperty(name = "transactional-engine.aws.cloudwatch.enabled", havingValue = "true")
public class CloudWatchSagaEvents implements SagaEvents {
    // Metrics published:
    // - saga_started
    // - saga_completed  
    // - saga_failed
    // - saga_compensated
    // - step_started
    // - step_completed
    // - step_failed
    // - step_compensated
    // - step_duration
    // - saga_duration
}
```

#### KinesisStepEventPublisher
Publishes step events to Kinesis streams.

```java
@Component
@ConditionalOnProperty(name = "transactional-engine.aws.kinesis.enabled", havingValue = "true")
public class KinesisStepEventPublisher implements StepEventPublisher {
    // Publishes StepEventEnvelope to configured Kinesis stream
}
```

#### SqsStepEventPublisher
Publishes step events to SQS queues.

```java
@Component
@ConditionalOnProperty(name = "transactional-engine.aws.sqs.enabled", havingValue = "true")
public class SqsStepEventPublisher implements StepEventPublisher {
    // Publishes StepEventEnvelope to configured SQS queue
}
```

## Events and Observability

### SagaEvents Interface

```java
public interface SagaEvents {
    void sagaStarted(String sagaName, String sagaId, SagaContext context);
    void sagaCompleted(String sagaName, String sagaId, Duration duration, SagaContext context);
    void sagaFailed(String sagaName, String sagaId, Throwable error, Duration duration, SagaContext context);
    void sagaCompensated(String sagaName, String sagaId, Duration duration, SagaContext context);
    
    void stepStarted(String sagaName, String stepId, String sagaId, SagaContext context);
    void stepCompleted(String sagaName, String stepId, String sagaId, Object result, Duration duration, SagaContext context);
    void stepFailed(String sagaName, String stepId, String sagaId, Throwable error, Duration duration, SagaContext context);
    void stepCompensated(String sagaName, String stepId, String sagaId, Duration duration, SagaContext context);
}
```

### StepEventPublisher Interface

```java
public interface StepEventPublisher {
    Mono<Void> publishEvent(StepEventEnvelope event);
}
```

### StepEventEnvelope

```java
public class StepEventEnvelope {
    private String eventId;
    private String eventType;
    private String sagaId;
    private String sagaName;
    private String stepId;
    private String topic;
    private Instant timestamp;
    private Map<String, Object> properties;
    private Object result;
    private SagaContext context;
    
    // Getters, setters, builders...
}
```

## Utility Classes

### MethodRefs

Utility for extracting method references and saga information.

```java
public class MethodRefs {
    public static Class<?> extractDeclaringClass(Serializable methodRef);
    public static String extractMethodName(Serializable methodRef);
    public static String extractSagaName(Class<?> sagaClass);
}
```

### SagaGraphGenerator

Generates DOT format graphs for saga visualization.

```java
public class SagaGraphGenerator {
    public static String generateDotGraph(SagaDefinition saga);
    public static String generateDotGraph(String sagaName, SagaRegistry registry);
    public static void saveGraphToFile(SagaDefinition saga, String filePath);
}
```

## Type-Safe Method References

The engine supports type-safe method references using functional interfaces:

```java
// Function interfaces for type-safe method references
@FunctionalInterface
public interface Fn1<A, R> extends Serializable {
    R apply(A arg);
}

@FunctionalInterface  
public interface Fn2<A, B, R> extends Serializable {
    R apply(A arg1, B arg2);
}

@FunctionalInterface
public interface Fn3<A, B, C, R> extends Serializable {
    R apply(A arg1, B arg2, C arg3);
}

@FunctionalInterface
public interface Fn4<A, B, C, D, R> extends Serializable {
    R apply(A arg1, B arg2, C arg3, D arg4);
}
```

**Usage:**
```java
// Type-safe execution
Mono<SagaResult> result = sagaEngine.execute(OrderSaga::processOrder, inputs);

// Instead of string-based
Mono<SagaResult> result = sagaEngine.execute("order-processing", inputs);
```

This completes the comprehensive API reference for the Transactional Engine framework.