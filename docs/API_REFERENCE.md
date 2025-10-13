# API Reference

This document provides a comprehensive reference for all public APIs in the Firefly Transactional Engine.

## Core Engines

### SagaEngine

Main orchestrator for SAGA pattern execution.

```java
public class SagaEngine {
    
    // Execute by saga name
    public Mono<SagaResult> execute(String sagaName, StepInputs inputs);
    public Mono<SagaResult> execute(String sagaName, StepInputs inputs, SagaContext context);
    
    // Execute by saga class
    public Mono<SagaResult> execute(Class<?> sagaClass, StepInputs inputs);
    public Mono<SagaResult> execute(Class<?> sagaClass, StepInputs inputs, SagaContext context);
    
    // Execute by method reference
    public <T> Mono<SagaResult> execute(Function<T, Mono<?>> methodRef, StepInputs inputs);
    public <T> Mono<SagaResult> execute(Function<T, Mono<?>> methodRef, StepInputs inputs, SagaContext context);
    
    // Legacy methods (deprecated)
    @Deprecated
    public Mono<Map<String, Object>> run(String sagaName, Map<String, Object> stepInputs);
}
```

### TccEngine

Main orchestrator for TCC pattern execution. Uses TCC-specific infrastructure components for complete isolation from SAGA pattern.

```java
public class TccEngine {

    // Constructors
    public TccEngine(TccRegistry registry, TccEvents tccEvents);
    public TccEngine(TccRegistry registry, TccEvents tccEvents,
                    TccPersistenceProvider persistenceProvider, boolean persistenceEnabled);
    public TccEngine(TccRegistry registry, TccEvents tccEvents,
                    TccPersistenceProvider persistenceProvider, boolean persistenceEnabled,
                    TccEventPublisher tccEventPublisher);

    // Execute TCC transaction
    public Mono<TccResult> execute(String tccName, TccInputs inputs);
    public Mono<TccResult> execute(String tccName, TccInputs inputs, TccContext context);

    // Execute by TCC definition
    public Mono<TccResult> execute(TccDefinition tccDef, TccInputs inputs, TccContext context);

    // Getters
    public TccRegistry getRegistry();
    public TccPersistenceProvider getTccPersistenceProvider();
    public boolean isPersistenceEnabled();
    public TccEvents getTccEvents();
}
```

### SagaCompositor

Orchestrates multiple sagas into coordinated workflows.

```java
public class SagaCompositor {
    
    // Create composition builder
    public static SagaCompositionBuilder compose(String name);
    
    // Execute composition
    public Mono<SagaCompositionResult> execute(SagaComposition composition, SagaContext context);
}
```

### TccCompositor

Orchestrates multiple TCC transactions into coordinated workflows.

```java
public class TccCompositor {

    // Create composition builder
    public static TccCompositionBuilder compose(String name);

    // Execute composition
    public Mono<TccCompositionResult> execute(TccComposition composition, TccContext context);

    // Validate composition
    public void validate(TccComposition composition);
}
```

#### TccCompositionBuilder

```java
public class TccCompositionBuilder {

    // Set compensation policy
    public TccCompositionBuilder compensationPolicy(CompensationPolicy policy);

    // Add TCC to composition
    public TccBuilder tcc(String tccName);

    // Build final composition
    public TccComposition build();

    public static class TccBuilder {
        // Set TCC ID
        public TccBuilder withId(String id);

        // Set dependencies
        public TccBuilder dependsOn(String tccId);
        public TccBuilder executeInParallelWith(String tccId);

        // Set inputs
        public TccBuilder withInput(String key, Object value);

        // Set data mappings
        public TccBuilder withDataFrom(String sourceTccId, String sourceParticipantId,
                                      String sourceKey, String targetKey);

        // Set execution conditions
        public TccBuilder when(Function<TccCompositionContext, Boolean> condition);
        public TccBuilder optional();
        public TccBuilder timeout(int timeoutMs);

        // Add to composition
        public TccCompositionBuilder add();
    }
}
```

#### TccCompositionResult

```java
public class TccCompositionResult {

    // Overall result
    public boolean isSuccess();
    public String getCompositionName();
    public String getCompositionId();
    public Instant getStartedAt();
    public Instant getCompletedAt();
    public Duration getDuration();

    // TCC results
    public Map<String, TccResult> getTccResults();
    public TccResult getTccResult(String tccId);
    public Object getTccParticipantResult(String tccId, String participantId);

    // Execution statistics
    public Set<String> getCompletedTccs();
    public Set<String> getFailedTccs();
    public Set<String> getSkippedTccs();
    public int getTotalTccCount();
    public int getCompletedTccCount();
    public int getFailedTccCount();
    public int getSkippedTccCount();

    // Error information
    public Map<String, Throwable> getTccErrors();
    public Throwable getCompositionError();

    // Shared data
    public Map<String, Object> getSharedVariables();
}
```

## Input Builders

### StepInputs

Builder for SAGA step inputs.

```java
public class StepInputs {
    
    // Static factory methods
    public static StepInputs empty();
    public static StepInputs of(String stepId, Object input);
    public static StepInputs of(Map<String, Object> inputs);
    
    // Builder pattern
    public static Builder builder();
    
    public static class Builder {
        public Builder forStepId(String stepId, Object input);
        public <T> Builder forStep(Function<T, Mono<?>> methodRef, Object input);
        public Builder forStepId(String stepId, Function<SagaContext, Object> resolver);
        public StepInputs build();
    }
}
```

### TccInputs

Builder for TCC participant inputs.

```java
public class TccInputs {
    
    // Static factory methods
    public static TccInputs empty();
    public static TccInputs of(String participantId, Object input);
    
    // Builder pattern
    public static Builder builder();
    
    public static class Builder {
        public Builder forParticipant(String participantId, Object input);
        public Builder withGlobalTimeout(Duration timeout);
        public Builder withContext(TccContext context);
        public TccInputs build();
    }
}
```

## Context Classes

### SagaContext

Runtime context for saga execution.

```java
public class SagaContext {
    
    // Constructors
    public SagaContext();
    public SagaContext(String correlationId);
    public SagaContext(String correlationId, String sagaName);
    
    // Basic properties
    public String correlationId();
    public String sagaName();
    public Instant startedAt();
    
    // Headers
    public void putHeader(String key, String value);
    public String getHeader(String key);
    public Map<String, String> headers();
    
    // Variables
    public void setVariable(String key, Object value);
    public <T> T getVariable(String key, Class<T> type);
    public Map<String, Object> variables();
    
    // Step results
    public <T> T getResult(String stepId, Class<T> type);
    public Object getResult(String stepId);
    public Map<String, Object> stepResultsView();
    
    // Step status
    public StepStatus getStatus(String stepId);
    public int getAttempts(String stepId);
    public Map<String, StepStatus> stepStatusesView();
    
    // Idempotency
    public void addIdempotencyKey(String key);
    public boolean hasIdempotencyKey(String key);
    public Set<String> idempotencyKeys();
}
```

### TccContext

Runtime context for TCC execution (wraps SagaContext).

```java
public class TccContext {
    
    // Constructors
    public TccContext(String correlationId);
    public TccContext(SagaContext sagaContext);
    
    // TCC-specific methods
    public TccPhase getCurrentPhase();
    public void setCurrentPhase(TccPhase phase);
    
    // Participant results
    public void setParticipantTryResult(String participantId, Object result);
    public <T> Optional<T> getParticipantTryResult(String participantId, Class<T> type);
    public Map<String, Object> getAllTryResults();
    
    // Participant status
    public void setParticipantStatus(String participantId, TccParticipantStatus status);
    public TccParticipantStatus getParticipantStatus(String participantId);
    
    // Delegate to underlying SagaContext
    public String correlationId();
    public void putHeader(String key, String value);
    public String getHeader(String key);
    public void setVariable(String key, Object value);
    public <T> T getVariable(String key, Class<T> type);
}
```

## Result Classes

### SagaResult

Result of saga execution.

```java
public class SagaResult {
    
    // Basic properties
    public String sagaName();
    public String correlationId();
    public boolean isSuccess();
    public Duration duration();
    public Instant startedAt();
    public Instant completedAt();
    
    // Step results
    public <T> Optional<T> resultOf(String stepId, Class<T> type);
    public Object resultOf(String stepId);
    public Map<String, StepResult> steps();
    
    // Failure analysis
    public List<String> failedSteps();
    public List<String> compensatedSteps();
    public List<String> skippedSteps();
    
    // Error information
    public Optional<Throwable> error();
    public Optional<String> errorMessage();
}
```

### TccResult

Result of TCC execution.

```java
public class TccResult {

    // Basic properties
    public String tccName();
    public String correlationId();
    public boolean isSuccess();
    public boolean isConfirmed();
    public boolean isCanceled();
    public TccPhase getFinalPhase();
    public long getDurationMs();
    public Instant getStartedAt();
    public Instant getCompletedAt();

    // Try results
    public <T> T getTryResult(String participantId);
    public Object getTryResult(String participantId);
    public Map<String, Object> getAllTryResults();

    // Participant results
    public TccParticipantResult getParticipantResult(String participantId);
    public Map<String, TccParticipantResult> getParticipantResults();

    // Error information
    public Throwable getError();
    public String getErrorMessage();
}
```

## TCC Event System

### TccEvents

Interface for receiving TCC lifecycle events.

```java
public interface TccEvents {

    // TCC lifecycle events
    default void onTccStarted(String tccName, String correlationId) {}
    default void onTccStarted(String tccName, String correlationId, TccContext context) {}
    default void onTccCompleted(String tccName, String correlationId, TccPhase finalPhase, long durationMs) {}

    // Phase lifecycle events
    default void onPhaseStarted(String tccName, String correlationId, TccPhase phase) {}
    default void onPhaseCompleted(String tccName, String correlationId, TccPhase phase, long durationMs) {}
    default void onPhaseFailed(String tccName, String correlationId, TccPhase phase, Throwable error, long durationMs) {}

    // Participant lifecycle events
    default void onParticipantStarted(String tccName, String correlationId, String participantId, TccPhase phase) {}
    default void onParticipantSuccess(String tccName, String correlationId, String participantId, TccPhase phase, int attempts, long durationMs) {}
    default void onParticipantFailed(String tccName, String correlationId, String participantId, TccPhase phase, Throwable error, int attempts, long durationMs) {}
    default void onParticipantRetry(String tccName, String correlationId, String participantId, TccPhase phase, int attempts, Throwable lastError) {}

    // Additional lifecycle events
    default void onParticipantRegistered(String tccName, String correlationId, String participantId) {}
    default void onParticipantTimeout(String tccName, String correlationId, String participantId, TccPhase phase) {}
    default void onResourceReserved(String tccName, String correlationId, String participantId, String resourceId) {}
    default void onResourceReleased(String tccName, String correlationId, String participantId, String resourceId) {}
}
```

### TccEventPublisher

Interface for publishing TCC events to external systems.

```java
public interface TccEventPublisher {

    // Publish event envelope
    Mono<Void> publish(TccEventEnvelope event);
}
```

### TccEventEnvelope

Event envelope containing TCC event data.

```java
public class TccEventEnvelope {

    // TCC identification
    public String getTccName();
    public String getCorrelationId();
    public String getParticipantId();

    // Event metadata
    public String getTopic();
    public String getType();
    public String getKey();
    public Object getPayload();
    public Map<String, String> getHeaders();
    public Instant getTimestamp();

    // Execution details
    public TccPhase getPhase();
    public int getAttempts();
    public long getDurationMs();
    public Instant getStartedAt();
    public Instant getCompletedAt();

    // Error information
    public String getErrorClass();
    public String getErrorMessage();
    public boolean getSuccess();
}
```

### SagaCompositionResult

Result of saga composition execution.

```java
public class SagaCompositionResult {
    
    // Basic properties
    public String compositionName();
    public boolean isSuccess();
    public Duration duration();
    public int getCompletedSagaCount();
    public int getTotalSagaCount();
    
    // Saga results
    public SagaResult getSagaResult(String sagaId);
    public Map<String, SagaResult> getAllSagaResults();
    public boolean isSagaCompleted(String sagaId);
    
    // Shared data
    public Map<String, Object> getSharedVariables();
    public <T> T getSharedVariable(String key, Class<T> type);
    
    // Error information
    public List<String> getFailedSagas();
    public Optional<Throwable> error();
}
```

## Annotations

### SAGA Annotations

```java
// Mark class as saga
@Saga(name = "saga-name", layerConcurrency = 5)

// Mark method as saga step
@SagaStep(
    id = "step-id",
    dependsOn = {"step1", "step2"},
    retry = 3,
    backoffMs = 1000,
    timeoutMs = 30000,
    jitter = true,
    jitterFactor = 0.3,
    cpuBound = false,
    idempotencyKey = "key",
    compensate = "compensationMethod",
    compensationRetry = 3,
    compensationTimeoutMs = 10000,
    compensationCritical = false
)

// External saga step
@ExternalSagaStep(
    saga = "saga-name",
    id = "step-id",
    dependsOn = {"step1"},
    compensate = "compensationMethod"
    // ... other SagaStep properties
)

// Compensation step
@CompensationSagaStep(
    saga = "saga-name",
    forStepId = "step-id"
)

// Set context variables
@SetVariable(name = "varName", value = "value")
@SetVariable(name = "varName", fromHeader = "X-Header")

// Step events
@StepEvent(topic = "topic", eventType = "EVENT_TYPE")
```

### TCC Annotations

```java
// Mark class as TCC coordinator
@Tcc(name = "tcc-name", timeoutMs = 30000)

// Mark class as TCC participant
@TccParticipant(id = "participant-id", order = 1)

// Mark methods in participant
@TryMethod(timeoutMs = 10000, retry = 3, backoffMs = 1000)
@ConfirmMethod(timeoutMs = 5000, retry = 2, backoffMs = 500)
@CancelMethod(timeoutMs = 5000, retry = 5, backoffMs = 500)

// Configure event publishing for TCC participant
@TccEvent(topic = "topic-name", eventType = "EVENT_TYPE", key = "#{correlationId}")
```

### Parameter Injection Annotations

```java
// Input injection
@Input                          // Full input object
@Input("fieldName")            // Specific field from input

// Step result injection
@FromStep("stepId")            // Result from another step

// TCC try result injection
@FromTry                       // Try result in confirm/cancel methods

// Header injection
@Header("headerName")          // Single header value
@Headers                       // All headers as Map<String, String>

// Variable injection
@Variable("varName")           // Context variable
```

### Configuration Annotations

```java
// Enable transactional engine
@EnableTransactionalEngine
```

## Enums

### StepStatus

```java
public enum StepStatus {
    PENDING,        // Step not yet executed
    DONE,          // Step completed successfully
    FAILED,        // Step failed
    COMPENSATED,   // Step was compensated
    SKIPPED        // Step was skipped
}
```

### TccPhase

```java
public enum TccPhase {
    TRY,           // Try phase
    CONFIRM,       // Confirm phase
    CANCEL         // Cancel phase
}
```

### TccParticipantStatus

```java
public enum TccParticipantStatus {
    INITIALIZED,   // Participant initialized
    TRY_SUCCEEDED, // Try method succeeded
    TRY_FAILED,    // Try method failed
    CONFIRMED,     // Confirm method succeeded
    CANCELED,      // Cancel method succeeded
    CONFIRM_FAILED,// Confirm method failed
    CANCEL_FAILED  // Cancel method failed
}
```

### CompensationPolicy

```java
public enum CompensationPolicy {
    STRICT_SEQUENTIAL,     // Compensate in reverse order
    GROUPED_PARALLEL,      // Compensate by dependency layers
    RETRY_WITH_BACKOFF,    // Retry failed compensations
    CIRCUIT_BREAKER,       // Skip compensation after threshold
    BEST_EFFORT_PARALLEL   // Parallel compensation, continue on errors
}
```

## Interfaces

### SagaEvents

Event interface for observability.

```java
public interface SagaEvents {
    
    // Saga lifecycle
    void onStart(String sagaName, String sagaId);
    void onCompleted(String sagaName, String sagaId, boolean success);
    
    // Step lifecycle
    void onStepStarted(String sagaName, String sagaId, String stepId);
    void onStepSuccess(String sagaName, String sagaId, String stepId, int attempts, long latencyMs);
    void onStepFailed(String sagaName, String sagaId, String stepId, Throwable error, int attempts, long latencyMs);
    
    // Compensation
    void onCompensationStarted(String sagaName, String sagaId, String stepId);
    void onCompensationSuccess(String sagaName, String sagaId, String stepId, long latencyMs);
    void onCompensationFailed(String sagaName, String sagaId, String stepId, Throwable error, long latencyMs);
    
    // Context
    void onStartContext(String sagaName, String sagaId, int headersCount, int variablesCount);
}
```

### StepEventPublisher

Interface for custom event publishing.

```java
public interface StepEventPublisher {
    
    // Publish step event
    Mono<Void> publish(StepEventEnvelope event);
}
```

### SagaPersistenceProvider

Interface for custom persistence providers.

```java
public interface SagaPersistenceProvider {

    // Persist saga state
    Mono<Void> persistSagaState(SagaExecutionState sagaState);

    // Load saga state
    Mono<SagaExecutionState> loadSagaState(String correlationId);

    // Delete saga state
    Mono<Void> deleteSagaState(String correlationId);

    // Find in-flight sagas
    Flux<SagaExecutionState> findInFlightSagas();

    // Health check
    Mono<Boolean> isHealthy();
}
```

## Utility Classes

### HttpCall

Utility for HTTP calls with context propagation.

```java
public class HttpCall {

    // Exchange with error handling
    public static <T, E> Mono<T> exchangeOrError(
        WebClient.RequestHeadersSpec<?> spec,
        SagaContext context,
        Class<T> responseType,
        Class<E> errorType,
        BiFunction<HttpStatus, E, RuntimeException> errorMapper
    );

    // Propagate headers
    public static WebClient.RequestHeadersSpec<?> propagate(
        WebClient.RequestHeadersSpec<?> spec,
        SagaContext context
    );

    public static WebClient.RequestHeadersSpec<?> propagate(
        WebClient.RequestHeadersSpec<?> spec,
        SagaContext context,
        Map<String, String> additionalHeaders
    );
}
```

### ExpandEach

Utility for step expansion with collections.

```java
public class ExpandEach<T> {

    // Create expansion
    public static <T> ExpandEach<T> of(Collection<T> items);

    // Configure expansion
    public ExpandEach<T> withIdSuffix(Function<T, String> suffixMapper);
    public ExpandEach<T> withInputMapper(Function<T, Object> inputMapper);

    // Get expanded inputs
    public Map<String, Object> expand(String baseStepId);
}
```

### SagaBuilder

Programmatic saga definition builder.

```java
public class SagaBuilder {

    // Create builder
    public static SagaBuilder named(String sagaName);

    // Add step
    public StepBuilder step(String stepId);

    // Build saga definition
    public SagaDefinition build();

    public static class StepBuilder {
        public StepBuilder dependsOn(String... stepIds);
        public StepBuilder handler(BiFunction<Object, SagaContext, Mono<?>> handler);
        public StepBuilder compensation(BiFunction<Object, SagaContext, Mono<Void>> compensation);
        public StepBuilder retry(int attempts);
        public StepBuilder timeout(Duration timeout);
        public SagaBuilder add();
    }
}
```

### SagaCompositionBuilder

Builder for saga compositions.

```java
public class SagaCompositionBuilder {

    // Add saga to composition
    public SagaCompositionBuilder saga(String sagaId, String sagaName);
    public SagaCompositionBuilder saga(String sagaId, String sagaName, Object input);

    // Configure dependencies
    public SagaCompositionBuilder dependsOn(String... sagaIds);

    // Configure execution
    public SagaCompositionBuilder parallel();
    public SagaCompositionBuilder sequential();
    public SagaCompositionBuilder optional();
    public SagaCompositionBuilder conditional(Predicate<SagaContext> condition);

    // Shared data
    public SagaCompositionBuilder shareVariable(String key, Object value);
    public SagaCompositionBuilder shareVariableFrom(String sourceSagaId, String sourceKey, String targetKey);

    // Build composition
    public SagaComposition build();
}
```

## Configuration Classes

### TransactionalEngineProperties

Main configuration properties.

```java
@ConfigurationProperties("firefly.tx")
public class TransactionalEngineProperties {

    // Nested properties
    private SagaSpecificProperties saga = new SagaSpecificProperties();
    private TccSpecificProperties tcc = new TccSpecificProperties();
    private PersistenceProperties persistence = new PersistenceProperties();
    private ObservabilityProperties observability = new ObservabilityProperties();
    private ValidationProperties validation = new ValidationProperties();
    private EventProperties events = new EventProperties();
    private HttpProperties http = new HttpProperties();

    // Getters and setters
    public SagaSpecificProperties getSaga() { return saga; }
    public TccSpecificProperties getTcc() { return tcc; }
    public PersistenceProperties getPersistence() { return persistence; }
    // ... other getters/setters
}
```

### SagaSpecificProperties

SAGA-specific configuration.

```java
public class SagaSpecificProperties {

    private CompensationPolicy compensationPolicy = CompensationPolicy.STRICT_SEQUENTIAL;
    private boolean autoOptimizationEnabled = true;
    private Duration defaultTimeout = Duration.ofMinutes(5);
    private int maxConcurrentSagas = 100;
    private int layerConcurrency = 5;

    // Nested properties
    private ContextProperties context = new ContextProperties();
    private BackpressureProperties backpressure = new BackpressureProperties();
    private CompensationProperties compensation = new CompensationProperties();

    // Getters and setters
}
```

### TccSpecificProperties

TCC-specific configuration.

```java
public class TccSpecificProperties {

    private Duration defaultTimeout = Duration.ofSeconds(30);
    private boolean autoRecoveryEnabled = true;
    private int maxConcurrentTransactions = 50;

    // Getters and setters
}
```

## TCC Infrastructure Components

### TccPersistenceProvider

TCC-specific persistence interface that extends the shared persistence layer.

```java
public interface TccPersistenceProvider extends TransactionalPersistenceProvider<TccExecutionState> {
    // Inherits all methods from TransactionalPersistenceProvider
    // Specialized for TccExecutionState persistence
}
```

### InMemoryTccPersistenceProvider

In-memory implementation of TCC persistence.

```java
public class InMemoryTccPersistenceProvider implements TccPersistenceProvider {

    // Save TCC execution state
    public Mono<Void> saveState(TccExecutionState state);

    // Load TCC execution state
    public Mono<TccExecutionState> loadState(String correlationId);

    // Delete TCC execution state
    public Mono<Void> deleteState(String correlationId);

    // Find states by criteria
    public Flux<TccExecutionState> findStatesStartedBefore(Instant cutoff);
    public Flux<TccExecutionState> findStatesUpdatedBefore(Instant cutoff);

    // Management operations
    public Flux<String> listActiveTransactions();
    public Mono<Long> countActiveTransactions();
    public Mono<Long> cleanup(Instant olderThan);
}
```

### RedisTccPersistenceProvider

Redis-based implementation of TCC persistence.

```java
public class RedisTccPersistenceProvider implements TccPersistenceProvider {

    public RedisTccPersistenceProvider(ReactiveRedisTemplate<String, String> redisTemplate,
                                      ObjectMapper objectMapper);

    // Same interface as InMemoryTccPersistenceProvider
    // Uses Redis for persistent storage with "tcc:" key prefix
}
```

### TccArgumentResolver

TCC-specific argument resolver for method parameter injection.

```java
public class TccArgumentResolver {

    // Resolve arguments for TCC participant methods
    public Object[] resolveArguments(Method method, Object input, TccContext context);
    public Object[] resolveArguments(Method method, Object input, TccContext context, Object participantTryResult);
}
```

**Supported Parameter Types:**
- `TccContext` - The current TCC context
- `@Input` - Input data for the participant
- `@Header` - Header values from the TCC context
- `@FromTry` - Results from the try phase (for confirm/cancel methods)
- Implicit input parameter (first parameter without annotation)

## TCC Annotations

### @FromTry

Injects the result from the Try phase into Confirm or Cancel method parameters.

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface FromTry {

    /**
     * Optional field name to extract from the Try method result.
     * If not specified, the entire result object is injected.
     */
    String value() default "";
}
```

### @Header

Injects a header value from the TCC context.

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Header {

    /**
     * The name of the header to inject.
     */
    String value();

    /**
     * Whether this parameter is required.
     */
    boolean required() default true;
}
```

### @Input

Injects input data for TCC participant methods.

```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Input {

    /**
     * The key to extract from the input if it's a Map.
     * If empty, the entire input object is injected.
     */
    String value() default "";

    /**
     * Whether this parameter is required.
     */
    boolean required() default true;
}
```

## Exception Classes

### SagaException

Base exception for saga-related errors.

```java
public class SagaException extends RuntimeException {

    private final String sagaName;
    private final String correlationId;

    public SagaException(String message, String sagaName, String correlationId);
    public SagaException(String message, Throwable cause, String sagaName, String correlationId);

    public String getSagaName() { return sagaName; }
    public String getCorrelationId() { return correlationId; }
}
```

### TccException

Base exception for TCC-related errors.

```java
public class TccException extends RuntimeException {

    private final String tccName;
    private final String correlationId;
    private final TccPhase phase;

    public TccException(String message, String tccName, String correlationId, TccPhase phase);
    public TccException(String message, Throwable cause, String tccName, String correlationId, TccPhase phase);

    public String getTccName() { return tccName; }
    public String getCorrelationId() { return correlationId; }
    public TccPhase getPhase() { return phase; }
}
```

### StepExecutionException

Exception during step execution.

```java
public class StepExecutionException extends SagaException {

    private final String stepId;
    private final int attempts;

    public StepExecutionException(String message, Throwable cause, String sagaName,
                                 String correlationId, String stepId, int attempts);

    public String getStepId() { return stepId; }
    public int getAttempts() { return attempts; }
}
```

## Factory Classes

### SagaContextFactory

Factory for creating optimized saga contexts.

```java
public class SagaContextFactory {

    // Create context with automatic optimization
    public static SagaContext createOptimized(String sagaName, SagaDefinition definition);

    // Create specific context types
    public static SagaContext createSequential(String sagaName);
    public static SagaContext createConcurrent(String sagaName);
    public static SagaContext createHighPerformance(String sagaName);
    public static SagaContext createLowMemory(String sagaName);

    // Create with correlation ID
    public static SagaContext createOptimized(String correlationId, String sagaName, SagaDefinition definition);
}
```
```
