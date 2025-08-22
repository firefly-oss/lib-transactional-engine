# AWS Integration Guide

Complete guide to integrating the Transactional Engine with AWS services for cloud-native deployments.

## Table of Contents

1. [Overview](#overview)
2. [Auto-Configuration](#auto-configuration)
3. [CloudWatch Integration](#cloudwatch-integration)
4. [Kinesis Integration](#kinesis-integration)
5. [SQS Integration](#sqs-integration)
6. [DynamoDB Integration](#dynamodb-integration)
7. [Configuration Properties](#configuration-properties)
8. [Security and IAM](#security-and-iam)
9. [Deployment Patterns](#deployment-patterns)
10. [Monitoring and Alerts](#monitoring-and-alerts)
11. [Best Practices](#best-practices)
12. [Troubleshooting](#troubleshooting)

## Overview

The AWS starter module provides seamless integration with AWS services through Spring Boot auto-configuration. When AWS dependencies are detected on the classpath, the engine automatically configures:

- **CloudWatch**: Metrics and logging for saga observability
- **Kinesis**: Event streaming for step events and saga lifecycle events
- **SQS**: Message queuing for step events and async processing
- **DynamoDB**: Persistence for saga state and execution history

## Auto-Configuration

### Dependencies

Add the AWS starter dependency:

```xml
<dependency>
    <groupId>com.catalis</groupId>
    <artifactId>lib-transactional-engine-aws-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- AWS SDK v2 dependencies -->
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>cloudwatch</artifactId>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>kinesis</artifactId>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>sqs</artifactId>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>dynamodb</artifactId>
</dependency>
```

### Auto-Configuration Classes

The starter automatically configures AWS services when detected:

```java
@Configuration
@ConditionalOnClass({
    DynamoDbAsyncClient.class,
    CloudWatchAsyncClient.class,
    KinesisAsyncClient.class
})
@EnableConfigurationProperties(AwsTransactionalEngineProperties.class)
public class AwsTransactionalEngineAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean(SagaEvents.class)
    @ConditionalOnProperty(name = "transactional-engine.aws.cloudwatch.enabled", havingValue = "true", matchIfMissing = true)
    public SagaEvents cloudWatchSagaEvents(CloudWatchAsyncClient cloudWatch, AwsTransactionalEngineProperties properties) {
        return new CloudWatchSagaEvents(cloudWatch, properties.getCloudwatch());
    }
    
    @Bean
    @ConditionalOnMissingBean(StepEventPublisher.class)
    @ConditionalOnProperty(name = "transactional-engine.aws.kinesis.enabled", havingValue = "true")
    public StepEventPublisher kinesisStepEventPublisher(KinesisAsyncClient kinesis, AwsTransactionalEngineProperties properties) {
        return new KinesisStepEventPublisher(kinesis, properties.getKinesis());
    }
}
```

## CloudWatch Integration

### Metrics Publishing

The CloudWatch integration publishes comprehensive metrics:

#### Saga Metrics
- `saga_started` - Number of sagas started
- `saga_completed` - Number of sagas completed successfully
- `saga_failed` - Number of sagas that failed
- `saga_compensated` - Number of sagas that were compensated
- `saga_duration` - Duration of saga execution

#### Step Metrics
- `step_started` - Number of steps started
- `step_completed` - Number of steps completed
- `step_failed` - Number of steps that failed
- `step_compensated` - Number of steps compensated
- `step_duration` - Duration of step execution
- `step_retry_count` - Number of retries per step

### Configuration

```yaml
transactional-engine:
  aws:
    cloudwatch:
      enabled: true
      namespace: "TransactionalEngine"
      region: "us-east-1"
      
      # Metric publication settings
      batch-size: 20
      flush-interval: 60s
      
      # Dimension configuration
      dimensions:
        environment: "production"
        application: "order-service"
        
      # Metric filters
      exclude-metrics: []
      include-only-metrics: []
```

### Custom Metrics

Add custom business metrics:

```java
@Component
public class BusinessMetricsCollector implements SagaEvents {
    
    private final CloudWatchAsyncClient cloudWatch;
    
    @Override
    public void sagaCompleted(String sagaName, String sagaId, Duration duration, SagaContext context) {
        // Publish business-specific metrics
        if ("order-processing".equals(sagaName)) {
            BigDecimal orderValue = context.getVariable("totalAmount", BigDecimal.class);
            publishOrderValueMetric(orderValue);
            
            String customerTier = context.getVariable("customerTier", String.class);
            publishCustomerTierMetric(customerTier);
        }
    }
    
    private void publishOrderValueMetric(BigDecimal orderValue) {
        MetricDatum metric = MetricDatum.builder()
            .metricName("order_value")
            .value(orderValue.doubleValue())
            .unit(StandardUnit.NONE)
            .timestamp(Instant.now())
            .dimensions(
                Dimension.builder().name("Currency").value("USD").build(),
                Dimension.builder().name("SagaType").value("order-processing").build()
            )
            .build();
            
        cloudWatch.putMetricData(PutMetricDataRequest.builder()
            .namespace("BusinessMetrics")
            .metricData(metric)
            .build());
    }
}
```

### CloudWatch Dashboards

Create dashboards to visualize saga metrics:

```json
{
  "widgets": [
    {
      "type": "metric",
      "properties": {
        "metrics": [
          ["TransactionalEngine", "saga_started", "SagaName", "order-processing"],
          [".", "saga_completed", ".", "."],
          [".", "saga_failed", ".", "."]
        ],
        "period": 300,
        "stat": "Sum",
        "region": "us-east-1",
        "title": "Order Processing Saga Metrics"
      }
    },
    {
      "type": "metric",
      "properties": {
        "metrics": [
          ["TransactionalEngine", "saga_duration", "SagaName", "order-processing"]
        ],
        "period": 300,
        "stat": "Average",
        "region": "us-east-1",
        "title": "Saga Duration"
      }
    }
  ]
}
```

## Kinesis Integration

### Event Streaming

Stream saga events to Kinesis for real-time processing:

```yaml
transactional-engine:
  aws:
    kinesis:
      enabled: true
      stream-name: "saga-events"
      region: "us-east-1"
      partition-key-strategy: "sagaId"  # sagaId, stepId, custom
      
      # Batching configuration
      batch-size: 500
      batch-timeout: 5s
      
      # Retry configuration
      max-retries: 3
      retry-backoff: 1s
```

### Event Schema

Events published to Kinesis follow this schema:

```json
{
  "eventId": "uuid",
  "eventType": "SAGA_STARTED|SAGA_COMPLETED|SAGA_FAILED|STEP_STARTED|STEP_COMPLETED|STEP_FAILED",
  "timestamp": "2023-01-01T12:00:00Z",
  "sagaId": "saga-uuid",
  "sagaName": "order-processing",
  "stepId": "validate-payment",
  "correlationId": "correlation-uuid",
  "context": {
    "customerId": "customer-123",
    "variables": {
      "customerTier": "PREMIUM"
    }
  },
  "result": {
    "paymentId": "payment-456",
    "status": "APPROVED"
  },
  "error": {
    "type": "PaymentDeclinedException",
    "message": "Insufficient funds"
  },
  "metadata": {
    "duration": "PT2.5S",
    "retryCount": 1
  }
}
```

### Event Processing

Process saga events from Kinesis:

```java
@Component
public class SagaEventProcessor {
    
    @KinesisListener(stream = "saga-events")
    public void processSagaEvent(SagaEventEnvelope event) {
        switch (event.getEventType()) {
            case "SAGA_FAILED":
                handleSagaFailure(event);
                break;
            case "STEP_FAILED":
                handleStepFailure(event);
                break;
            case "ORDER_CREATED":
                handleOrderCreated(event);
                break;
        }
    }
    
    private void handleSagaFailure(SagaEventEnvelope event) {
        // Send alert, update monitoring systems, etc.
        alertingService.sendAlert(
            "Saga Failed",
            String.format("Saga %s failed: %s", event.getSagaName(), event.getError().getMessage())
        );
    }
    
    private void handleOrderCreated(SagaEventEnvelope event) {
        // Update read models, trigger downstream processes
        OrderCreatedEvent orderEvent = objectMapper.convertValue(event.getResult(), OrderCreatedEvent.class);
        readModelUpdater.updateOrderProjection(orderEvent);
    }
}
```

## SQS Integration

### Queue Configuration

Configure SQS for step event publishing:

```yaml
transactional-engine:
  aws:
    sqs:
      enabled: true
      queue-name: "saga-step-events"
      region: "us-east-1"
      
      # Queue attributes
      visibility-timeout: 30s
      message-retention: 1209600s  # 14 days
      max-receive-count: 3
      dead-letter-queue: "saga-step-events-dlq"
      
      # Batching
      batch-size: 10
      batch-timeout: 2s
```

### Dead Letter Queue Handling

```java
@Component
public class DeadLetterQueueProcessor {
    
    @SqsListener(value = "saga-step-events-dlq")
    public void processFailedEvents(@Payload String message, @Header Map<String, Object> headers) {
        try {
            SagaEventEnvelope event = objectMapper.readValue(message, SagaEventEnvelope.class);
            
            // Log failed event for analysis
            log.error("Failed to process saga event: sagaId={}, stepId={}, error={}", 
                     event.getSagaId(), event.getStepId(), headers.get("approximate-receive-count"));
            
            // Store in database for manual review
            failedEventRepository.save(FailedEvent.from(event, headers));
            
            // Send alert if critical saga
            if (isCriticalSaga(event.getSagaName())) {
                alertingService.sendCriticalAlert("Critical saga event failed processing", event);
            }
            
        } catch (Exception e) {
            log.error("Failed to process dead letter queue message", e);
        }
    }
}
```

## DynamoDB Integration

### Schema Design

Design DynamoDB tables for saga persistence:

```yaml
# Saga Execution Table
SagaExecution:
  TableName: saga-execution
  KeySchema:
    - AttributeName: sagaId
      KeyType: HASH
    - AttributeName: timestamp
      KeyType: RANGE
  AttributeDefinitions:
    - AttributeName: sagaId
      AttributeType: S
    - AttributeName: timestamp
      AttributeType: N
    - AttributeName: sagaName
      AttributeType: S
  GlobalSecondaryIndexes:
    - IndexName: saga-name-index
      KeySchema:
        - AttributeName: sagaName
          KeyType: HASH
        - AttributeName: timestamp
          KeyType: RANGE
```

### Repository Implementation

```java
@Repository
public class DynamoDbSagaRepository implements SagaRepository {
    
    private final DynamoDbAsyncClient dynamoDb;
    private final String tableName;
    
    @Override
    public Mono<Void> saveSagaExecution(SagaExecution execution) {
        Map<String, AttributeValue> item = Map.of(
            "sagaId", AttributeValue.builder().s(execution.getSagaId()).build(),
            "timestamp", AttributeValue.builder().n(String.valueOf(execution.getTimestamp())).build(),
            "sagaName", AttributeValue.builder().s(execution.getSagaName()).build(),
            "status", AttributeValue.builder().s(execution.getStatus().name()).build(),
            "context", AttributeValue.builder().s(serializeContext(execution.getContext())).build(),
            "result", AttributeValue.builder().s(serializeResult(execution.getResult())).build()
        );
        
        PutItemRequest request = PutItemRequest.builder()
            .tableName(tableName)
            .item(item)
            .build();
            
        return Mono.fromFuture(dynamoDb.putItem(request)).then();
    }
    
    @Override
    public Flux<SagaExecution> findBySagaName(String sagaName, Instant from, Instant to) {
        QueryRequest request = QueryRequest.builder()
            .tableName(tableName)
            .indexName("saga-name-index")
            .keyConditionExpression("sagaName = :sagaName AND #timestamp BETWEEN :from AND :to")
            .expressionAttributeNames(Map.of("#timestamp", "timestamp"))
            .expressionAttributeValues(Map.of(
                ":sagaName", AttributeValue.builder().s(sagaName).build(),
                ":from", AttributeValue.builder().n(String.valueOf(from.toEpochMilli())).build(),
                ":to", AttributeValue.builder().n(String.valueOf(to.toEpochMilli())).build()
            ))
            .build();
            
        return Mono.fromFuture(dynamoDb.query(request))
            .flatMapMany(response -> Flux.fromIterable(response.items())
                .map(this::mapToSagaExecution));
    }
}
```

## Configuration Properties

### Complete AWS Configuration

```yaml
transactional-engine:
  aws:
    # Global AWS settings
    region: "us-east-1"
    profile: "default"
    
    # CloudWatch configuration
    cloudwatch:
      enabled: true
      namespace: "TransactionalEngine"
      region: ${transactional-engine.aws.region}
      batch-size: 20
      flush-interval: 60s
      dimensions:
        environment: "production"
        application: "order-service"
      
    # Kinesis configuration  
    kinesis:
      enabled: true
      stream-name: "saga-events"
      region: ${transactional-engine.aws.region}
      partition-key-strategy: "sagaId"
      batch-size: 500
      batch-timeout: 5s
      max-retries: 3
      retry-backoff: 1s
      
    # SQS configuration
    sqs:
      enabled: false
      queue-name: "saga-step-events"
      region: ${transactional-engine.aws.region}
      visibility-timeout: 30s
      message-retention: 1209600s
      max-receive-count: 3
      dead-letter-queue: "saga-step-events-dlq"
      batch-size: 10
      batch-timeout: 2s
      
    # DynamoDB configuration
    dynamodb:
      enabled: false
      table-name: "saga-execution"
      region: ${transactional-engine.aws.region}
      read-capacity: 5
      write-capacity: 5
      ttl-enabled: true
      ttl-attribute: "expiresAt"
```

## Security and IAM

### IAM Policies

Create IAM policies for the transactional engine:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "cloudwatch:PutMetricData"
      ],
      "Resource": "*",
      "Condition": {
        "StringEquals": {
          "cloudwatch:namespace": "TransactionalEngine"
        }
      }
    },
    {
      "Effect": "Allow",
      "Action": [
        "kinesis:PutRecord",
        "kinesis:PutRecords"
      ],
      "Resource": "arn:aws:kinesis:us-east-1:123456789012:stream/saga-events"
    },
    {
      "Effect": "Allow",
      "Action": [
        "sqs:SendMessage",
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage"
      ],
      "Resource": [
        "arn:aws:sqs:us-east-1:123456789012:saga-step-events",
        "arn:aws:sqs:us-east-1:123456789012:saga-step-events-dlq"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:PutItem",
        "dynamodb:GetItem",
        "dynamodb:Query",
        "dynamodb:UpdateItem"
      ],
      "Resource": "arn:aws:dynamodb:us-east-1:123456789012:table/saga-execution"
    }
  ]
}
```

### Credential Configuration

Configure AWS credentials:

```yaml
# application.yml
cloud:
  aws:
    credentials:
      access-key: ${AWS_ACCESS_KEY_ID}
      secret-key: ${AWS_SECRET_ACCESS_KEY}
    region:
      static: us-east-1
      
# Or use IAM roles for EC2/ECS
cloud:
  aws:
    credentials:
      instance-profile: true
    region:
      static: us-east-1
```

## Deployment Patterns

### ECS Deployment

Deploy with Amazon ECS:

```json
{
  "family": "saga-engine-service",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "1024",
  "memory": "2048",
  "executionRoleArn": "arn:aws:iam::123456789012:role/ecsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::123456789012:role/sagaEngineTaskRole",
  "containerDefinitions": [
    {
      "name": "saga-engine",
      "image": "your-repo/saga-engine:latest",
      "portMappings": [
        {
          "containerPort": 8080,
          "protocol": "tcp"
        }
      ],
      "environment": [
        {
          "name": "SPRING_PROFILES_ACTIVE",
          "value": "production"
        },
        {
          "name": "TRANSACTIONAL_ENGINE_AWS_CLOUDWATCH_ENABLED",
          "value": "true"
        }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/saga-engine",
          "awslogs-region": "us-east-1",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ]
}
```

### Lambda Deployment

Deploy saga steps as Lambda functions:

```java
@Component
public class LambdaSagaStep {
    
    public APIGatewayProxyResponseEvent handleValidatePayment(APIGatewayProxyRequestEvent input, Context context) {
        try {
            PaymentValidationRequest request = objectMapper.readValue(input.getBody(), PaymentValidationRequest.class);
            
            PaymentValidationResult result = paymentService.validatePayment(request);
            
            return APIGatewayProxyResponseEvent.builder()
                .statusCode(200)
                .body(objectMapper.writeValueAsString(result))
                .build();
                
        } catch (Exception e) {
            context.getLogger().log("Payment validation failed: " + e.getMessage());
            
            return APIGatewayProxyResponseEvent.builder()
                .statusCode(500)
                .body("{\"error\":\"Payment validation failed\"}")
                .build();
        }
    }
}
```

## Monitoring and Alerts

### CloudWatch Alarms

Create alarms for saga monitoring:

```json
{
  "AlarmName": "HighSagaFailureRate",
  "AlarmDescription": "Alert when saga failure rate exceeds 5%",
  "MetricName": "saga_failed",
  "Namespace": "TransactionalEngine",
  "Statistic": "Sum",
  "Period": 300,
  "EvaluationPeriods": 2,
  "Threshold": 5.0,
  "ComparisonOperator": "GreaterThanThreshold",
  "AlarmActions": [
    "arn:aws:sns:us-east-1:123456789012:saga-alerts"
  ]
}
```

### Custom Alerts

Implement custom alerting logic:

```java
@Component
public class SagaAlertManager implements SagaEvents {
    
    private final SnsAsyncClient sns;
    private final String alertTopicArn;
    
    @Override
    public void sagaFailed(String sagaName, String sagaId, Throwable error, Duration duration, SagaContext context) {
        if (isCriticalSaga(sagaName)) {
            sendCriticalAlert(sagaName, sagaId, error);
        }
        
        if (isLongRunningSaga(duration)) {
            sendPerformanceAlert(sagaName, duration);
        }
    }
    
    private void sendCriticalAlert(String sagaName, String sagaId, Throwable error) {
        String message = String.format(
            "CRITICAL: Saga %s failed\nSaga ID: %s\nError: %s\nTimestamp: %s",
            sagaName, sagaId, error.getMessage(), Instant.now()
        );
        
        PublishRequest request = PublishRequest.builder()
            .topicArn(alertTopicArn)
            .subject("Critical Saga Failure")
            .message(message)
            .build();
            
        sns.publish(request);
    }
}
```

## Best Practices

### Performance Optimization

1. **Batch Events**: Use batching for high-throughput scenarios
2. **Async Processing**: Configure async event publishing
3. **Resource Pooling**: Properly configure connection pools
4. **Monitoring**: Set up comprehensive monitoring and alerting

### Error Handling

1. **Dead Letter Queues**: Use DLQs for failed event processing
2. **Retry Logic**: Implement exponential backoff for AWS API calls
3. **Circuit Breakers**: Protect against cascading failures

### Cost Optimization

1. **Metric Filtering**: Only publish necessary metrics
2. **Data Retention**: Configure appropriate retention periods
3. **Reserved Capacity**: Use reserved capacity for predictable workloads

## Troubleshooting

### Common Issues

#### CloudWatch Metrics Not Appearing

```bash
# Check IAM permissions
aws sts get-caller-identity

# Verify CloudWatch put-metric-data permissions
aws cloudwatch put-metric-data --namespace "TestNamespace" --metric-data MetricName=TestMetric,Value=1
```

#### Kinesis Stream Throttling

```yaml
# Increase shard count or adjust batching
transactional-engine:
  aws:
    kinesis:
      batch-size: 100  # Reduce batch size
      batch-timeout: 1s # Reduce timeout
```

#### SQS Message Loss

```yaml
# Configure dead letter queue
transactional-engine:
  aws:
    sqs:
      max-receive-count: 5
      dead-letter-queue: "saga-events-dlq"
      visibility-timeout: 60s
```

This guide provides comprehensive coverage of AWS integration capabilities, enabling you to build robust, cloud-native saga orchestrations with full observability and monitoring.