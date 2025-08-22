# Azure Integration Guide

Complete guide to integrating the Transactional Engine with Azure services for cloud-native deployments.

## Table of Contents

1. [Overview](#overview)
2. [Auto-Configuration](#auto-configuration)
3. [Application Insights Integration](#application-insights-integration)
4. [Event Hubs Integration](#event-hubs-integration)
5. [Service Bus Integration](#service-bus-integration)
6. [Cosmos DB Integration](#cosmos-db-integration)
7. [Configuration Properties](#configuration-properties)
8. [Security and Authentication](#security-and-authentication)
9. [Deployment Patterns](#deployment-patterns)
10. [Monitoring and Alerts](#monitoring-and-alerts)
11. [Best Practices](#best-practices)
12. [Troubleshooting](#troubleshooting)

## Overview

The Azure starter module provides seamless integration with Azure services through Spring Boot auto-configuration. When Azure dependencies are detected on the classpath, the engine automatically configures:

- **Application Insights**: Metrics, logging, and telemetry for saga observability
- **Event Hubs**: Event streaming for step events and saga lifecycle events
- **Service Bus**: Message queuing for step events and async processing
- **Cosmos DB**: Persistence for saga state and execution history

## Auto-Configuration

### Dependencies

Add the Azure starter dependency:

```xml
<dependency>
    <groupId>com.catalis</groupId>
    <artifactId>lib-transactional-engine-azure-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Azure SDK dependencies -->
<dependency>
    <groupId>com.azure</groupId>
    <artifactId>azure-cosmos</artifactId>
</dependency>
<dependency>
    <groupId>com.azure</groupId>
    <artifactId>azure-messaging-eventhubs</artifactId>
</dependency>
<dependency>
    <groupId>com.azure</groupId>
    <artifactId>azure-messaging-servicebus</artifactId>
</dependency>
<dependency>
    <groupId>com.microsoft.azure</groupId>
    <artifactId>applicationinsights-spring-boot-starter</artifactId>
</dependency>
```

### Auto-Configuration Classes

The starter automatically configures Azure services when detected:

```java
@Configuration
@ConditionalOnClass({
    CosmosAsyncClient.class,
    EventHubProducerAsyncClient.class,
    ServiceBusSenderAsyncClient.class
})
@EnableConfigurationProperties(AzureTransactionalEngineProperties.class)
public class AzureTransactionalEngineAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean(SagaEvents.class)
    @ConditionalOnProperty(name = "transactional-engine.azure.application-insights.enabled", havingValue = "true")
    public SagaEvents applicationInsightsSagaEvents(TelemetryClient telemetryClient, AzureTransactionalEngineProperties properties) {
        return new ApplicationInsightsSagaEvents(telemetryClient, properties.getApplicationInsights());
    }
    
    @Bean
    @ConditionalOnMissingBean(name = "eventHubsStepEventPublisher")
    @ConditionalOnProperty(name = "transactional-engine.azure.eventhubs.enabled", havingValue = "true")
    public StepEventPublisher eventHubsStepEventPublisher(EventHubProducerAsyncClient eventHub, AzureTransactionalEngineProperties properties) {
        return new EventHubsStepEventPublisher(eventHub, properties.getEventhubs());
    }
}
```

## Application Insights Integration

### Metrics and Telemetry

The Application Insights integration publishes comprehensive metrics and telemetry:

#### Saga Metrics
- `saga_started` - Number of sagas started
- `saga_completed_success` - Number of sagas completed successfully
- `saga_completed_failure` - Number of sagas that failed
- `saga_duration` - Duration of saga execution

#### Step Metrics
- `step_started` - Number of steps started
- `step_completed_success` - Number of steps completed
- `step_completed_failure` - Number of steps that failed
- `step_duration` - Duration of step execution
- `step_retry_count` - Number of retries per step

#### Custom Events
- `SagaStarted` - Saga initiation event
- `SagaCompleted` - Saga completion event
- `StepStarted` - Step initiation event
- `StepSucceeded` - Step success event
- `StepFailed` - Step failure event (includes exception tracking)

### Configuration

```yaml
transactional-engine:
  azure:
    application-insights:
      enabled: true
      instrumentation-key: "${APPINSIGHTS_INSTRUMENTATIONKEY}"
      connection-string: "${APPLICATIONINSIGHTS_CONNECTION_STRING}"
      publish-interval: PT1M
      max-batch-size: 100
```

### Custom Telemetry

You can add custom properties to telemetry:

```java
@Component
public class CustomSagaEvents extends ApplicationInsightsSagaEvents {
    
    @Override
    public void onStart(String sagaName, String sagaId, SagaContext context) {
        super.onStart(sagaName, sagaId, context);
        
        EventTelemetry customEvent = new EventTelemetry("CustomSagaStart");
        customEvent.getProperties().put("businessUnit", context.getHeader("businessUnit"));
        customEvent.getProperties().put("region", context.getHeader("region"));
        telemetryClient.trackEvent(customEvent);
    }
}
```

## Event Hubs Integration

### Event Streaming

Event Hubs provides high-throughput event streaming for step events:

#### Configuration

```yaml
transactional-engine:
  azure:
    eventhubs:
      enabled: true
      connection-string: "${EVENTHUB_CONNECTION_STRING}"
      event-hub-name: "saga-events"
      consumer-group: "$Default"
      max-records-per-batch: 100
      batch-timeout: PT5S
```

#### Event Schema

Events are published as JSON with the following structure:

```json
{
  "sagaId": "order-123",
  "sagaName": "order-processing",
  "stepId": "validate-payment",
  "eventType": "STEP_STARTED",
  "timestamp": "2023-08-22T10:15:30Z",
  "input": {...},
  "output": {...},
  "error": null,
  "metadata": {
    "correlationId": "req-456",
    "userId": "user-789"
  }
}
```

#### Consumer Example

```java
@Component
public class SagaEventConsumer {
    
    @EventHubConsumer(
        eventHubName = "${transactional-engine.azure.eventhubs.event-hub-name}",
        consumerGroup = "${transactional-engine.azure.eventhubs.consumer-group}"
    )
    public void handleSagaEvent(EventData eventData) {
        String eventJson = eventData.getBodyAsString();
        StepEventEnvelope event = objectMapper.readValue(eventJson, StepEventEnvelope.class);
        
        // Process event
        log.info("Received saga event: {}.{}", event.sagaName, event.stepId);
    }
}
```

## Service Bus Integration

### Message Queuing

Service Bus provides reliable message queuing for step events and saga coordination:

#### Configuration

```yaml
transactional-engine:
  azure:
    servicebus:
      enabled: true
      connection-string: "${SERVICEBUS_CONNECTION_STRING}"
      queue-name: "saga-events"          # For queue-based messaging
      topic-name: "saga-events"          # For topic-based messaging
      subscription-name: "processor"     # Required for topics
      max-batch-size: 10
      lock-duration: PT5M
      max-delivery-count: 3
```

#### Queue vs Topic Pattern

**Queue Pattern** - Direct point-to-point messaging:
```yaml
servicebus:
  queue-name: "saga-events"
  # topic-name should be empty
```

**Topic Pattern** - Publish-subscribe with multiple consumers:
```yaml
servicebus:
  topic-name: "saga-events"
  subscription-name: "order-processor"
  # queue-name should be empty
```

#### Message Properties

Messages include rich metadata for routing and processing:

```java
// Automatic message properties
message.getApplicationProperties().put("sagaName", envelope.sagaName);
message.getApplicationProperties().put("stepId", envelope.stepId);
message.getApplicationProperties().put("eventType", envelope.eventType.toString());
message.setSubject(envelope.sagaName + "." + envelope.stepId);
message.setCorrelationId(envelope.sagaId);
```

## Cosmos DB Integration

### Persistence

Cosmos DB provides globally distributed persistence for saga state:

#### Configuration

```yaml
transactional-engine:
  azure:
    cosmosdb:
      enabled: true
      endpoint: "${COSMOS_DB_ENDPOINT}"
      key: "${COSMOS_DB_KEY}"
      database-name: "transactional-engine"
      container-name: "saga-executions"
      timeout: PT30S
```

#### Container Setup

Create a Cosmos DB container with the following configuration:

- **Partition Key**: `/sagaName`
- **Throughput**: 400 RU/s (minimum) or Autoscale
- **TTL**: Optional, for automatic cleanup of completed sagas

#### Custom Cosmos Configuration

```java
@Configuration
public class CustomCosmosConfiguration {
    
    @Bean
    @Primary
    public CosmosAsyncClient customCosmosClient(AzureTransactionalEngineProperties properties) {
        return new CosmosClientBuilder()
            .endpoint(properties.getCosmosdb().getEndpoint())
            .key(properties.getCosmosdb().getKey())
            .consistencyLevel(ConsistencyLevel.SESSION)
            .connectionSharingAcrossClientsEnabled(true)
            .buildAsyncClient();
    }
}
```

## Configuration Properties

### Complete Configuration Reference

```yaml
transactional-engine:
  azure:
    # Cosmos DB configuration
    cosmosdb:
      enabled: true
      endpoint: "${COSMOS_DB_ENDPOINT}"
      key: "${COSMOS_DB_KEY}"
      database-name: "transactional-engine"
      container-name: "saga-executions"
      timeout: PT30S
    
    # Application Insights configuration
    application-insights:
      enabled: false
      instrumentation-key: "${APPINSIGHTS_INSTRUMENTATIONKEY}"
      connection-string: "${APPLICATIONINSIGHTS_CONNECTION_STRING}"
      publish-interval: PT1M
      max-batch-size: 100
    
    # Event Hubs configuration
    eventhubs:
      enabled: false
      connection-string: "${EVENTHUB_CONNECTION_STRING}"
      event-hub-name: "saga-events"
      consumer-group: "$Default"
      max-records-per-batch: 100
      batch-timeout: PT5S
    
    # Service Bus configuration
    servicebus:
      enabled: false
      connection-string: "${SERVICEBUS_CONNECTION_STRING}"
      queue-name: ""                    # Use for queue pattern
      topic-name: ""                    # Use for topic pattern
      subscription-name: ""             # Required for topic pattern
      max-batch-size: 10
      lock-duration: PT5M
      max-delivery-count: 3
```

### Environment-Specific Configurations

#### Development
```yaml
transactional-engine:
  azure:
    cosmosdb:
      enabled: true
      # Use Azure Cosmos DB Emulator
      endpoint: "https://localhost:8081"
      key: "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw=="
    application-insights:
      enabled: false  # Disable in development
```

#### Production
```yaml
transactional-engine:
  azure:
    cosmosdb:
      enabled: true
      endpoint: "${COSMOS_DB_ENDPOINT}"
      key: "${COSMOS_DB_KEY}"
    application-insights:
      enabled: true
      connection-string: "${APPLICATIONINSIGHTS_CONNECTION_STRING}"
    eventhubs:
      enabled: true
      connection-string: "${EVENTHUB_CONNECTION_STRING}"
```

## Security and Authentication

### Managed Identity

Use Azure Managed Identity for secure, password-less authentication:

```java
@Configuration
public class AzureManagedIdentityConfiguration {
    
    @Bean
    public CosmosAsyncClient cosmosClientWithManagedIdentity() {
        return new CosmosClientBuilder()
            .endpoint(cosmosEndpoint)
            .credential(new DefaultAzureCredentialBuilder().build())
            .buildAsyncClient();
    }
    
    @Bean  
    public EventHubProducerAsyncClient eventHubClientWithManagedIdentity() {
        return new EventHubClientBuilder()
            .fullyQualifiedNamespace(eventHubNamespace)
            .eventHubName(eventHubName)
            .credential(new DefaultAzureCredentialBuilder().build())
            .buildAsyncProducerClient();
    }
}
```

### Key Vault Integration

Store sensitive configuration in Azure Key Vault:

```yaml
azure:
  keyvault:
    uri: "${AZURE_KEYVAULT_URI}"
    
transactional-engine:
  azure:
    cosmosdb:
      endpoint: "@Microsoft.KeyVault(VaultName=${KEYVAULT_NAME};SecretName=cosmos-endpoint)"
      key: "@Microsoft.KeyVault(VaultName=${KEYVAULT_NAME};SecretName=cosmos-key)"
```

### RBAC Configuration

Required Azure RBAC roles for the application identity:

- **Cosmos DB**: `Cosmos DB Built-in Data Contributor`
- **Event Hubs**: `Azure Event Hubs Data Sender`
- **Service Bus**: `Azure Service Bus Data Sender`
- **Application Insights**: `Monitoring Metrics Publisher`

## Deployment Patterns

### Azure Container Apps

```yaml
apiVersion: containerapps.io/v1beta2
kind: ContainerApp
metadata:
  name: saga-processor
spec:
  configuration:
    secrets:
      - name: cosmos-connection-string
        value: "${COSMOS_DB_CONNECTION_STRING}"
    ingress:
      external: true
      targetPort: 8080
  template:
    containers:
      - name: saga-processor
        image: saga-processor:latest
        env:
          - name: COSMOS_DB_ENDPOINT
            secretRef: cosmos-connection-string
        resources:
          cpu: 0.5
          memory: 1Gi
    scale:
      minReplicas: 1
      maxReplicas: 10
      rules:
        - name: eventhub-scaling
          custom:
            type: azure-eventhub
            metadata:
              eventHubName: saga-events
              consumerGroup: processor
```

### Azure Kubernetes Service (AKS)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: saga-processor
spec:
  replicas: 3
  template:
    spec:
      serviceAccountName: saga-processor
      containers:
        - name: app
          image: saga-processor:latest
          env:
            - name: AZURE_CLIENT_ID
              value: "${MANAGED_IDENTITY_CLIENT_ID}"
          resources:
            requests:
              memory: 512Mi
              cpu: 250m
            limits:
              memory: 1Gi
              cpu: 500m
```

## Monitoring and Alerts

### Application Insights Dashboards

Create custom dashboards for saga monitoring:

```kusto
// Saga success rate
customEvents
| where name == "SagaCompleted"
| summarize 
    Total = count(),
    Success = countif(customMeasurements.success == 1.0),
    SuccessRate = (countif(customMeasurements.success == 1.0) * 100.0) / count()
by bin(timestamp, 5m)
| render timechart
```

### Alert Rules

Set up alerts for critical scenarios:

```kusto
// High failure rate alert
customEvents
| where name == "SagaCompleted"
| where timestamp > ago(5m)
| summarize 
    FailureRate = (countif(customMeasurements.success == 0.0) * 100.0) / count()
| where FailureRate > 10
```

## Best Practices

### Performance Optimization

1. **Cosmos DB**:
   - Use appropriate partition keys (e.g., sagaName)
   - Enable autoscale for variable workloads
   - Use session consistency for better performance

2. **Event Hubs**:
   - Batch events for better throughput
   - Use multiple partitions for parallelism
   - Configure appropriate retention policies

3. **Service Bus**:
   - Use sessions for ordered processing
   - Configure dead letter queues
   - Set appropriate lock durations

### Error Handling

```java
@Component
public class ResilientSagaEvents extends ApplicationInsightsSagaEvents {
    
    @Override
    protected void publishMetrics() {
        try {
            super.publishMetrics();
        } catch (Exception e) {
            log.warn("Failed to publish metrics to Application Insights, falling back to logging", e);
            // Fallback to local metrics or logging
        }
    }
}
```

### Cost Optimization

1. **Use appropriate tiers**:
   - Cosmos DB: Start with serverless or provisioned throughput
   - Event Hubs: Use Basic tier for development
   - Application Insights: Configure sampling

2. **Configure TTL**:
   ```java
   // Automatically delete completed sagas after 30 days
   cosmosContainer.upsertItem(sagaState)
       .map(response -> response.getItem())
       .doOnNext(item -> item.setTtl(Duration.ofDays(30).getSeconds()));
   ```

## Troubleshooting

### Common Issues

#### Connection Problems

```java
// Connection timeout
@Bean
public CosmosAsyncClient cosmosClientWithRetry() {
    return new CosmosClientBuilder()
        .endpoint(endpoint)
        .key(key)
        .directMode(DirectConnectionConfig.getDefaultConfig()
            .setConnectTimeout(Duration.ofSeconds(10))
            .setRequestTimeout(Duration.ofSeconds(30)))
        .buildAsyncClient();
}
```

#### Performance Issues

```yaml
# Increase throughput for high-volume scenarios
transactional-engine:
  azure:
    cosmosdb:
      timeout: PT60S  # Increase timeout
    eventhubs:
      max-records-per-batch: 500  # Increase batch size
      batch-timeout: PT10S        # Increase timeout
```

### Diagnostics

Enable detailed logging:

```yaml
logging:
  level:
    com.catalis.transactionalengine.azure: DEBUG
    com.azure: INFO
    reactor.netty: INFO
```

### Health Checks

```java
@Component
public class AzureHealthIndicator implements HealthIndicator {
    
    @Override
    public Health health() {
        try {
            // Check Cosmos DB connectivity
            cosmosClient.readDatabase(databaseName).block(Duration.ofSeconds(5));
            return Health.up()
                .withDetail("cosmosdb", "Connected")
                .build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("cosmosdb", "Connection failed: " + e.getMessage())
                .build();
        }
    }
}
```

---

For more detailed examples and advanced configurations, see the [examples repository](https://github.com/catalis/transactional-engine-examples) and [API documentation](api-reference.md).