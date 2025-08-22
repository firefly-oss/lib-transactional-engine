# Monitoring & Observability

This guide covers monitoring, observability, metrics collection, logging, and distributed tracing for the Firefly Transactional Engine.

## Overview

The Transactional Engine provides comprehensive observability features to help you monitor distributed transactions, track performance, and troubleshoot issues in production environments.

## Metrics Collection

### Built-in Metrics

The engine automatically collects the following metrics:

- **Saga Execution Metrics**
  - `saga.executions.total` - Total number of saga executions
  - `saga.executions.duration` - Execution duration histogram
  - `saga.executions.success.rate` - Success rate percentage
  - `saga.executions.failures.total` - Total number of failures

- **Step Metrics**
  - `saga.steps.executions.total` - Total step executions
  - `saga.steps.duration` - Step execution duration
  - `saga.steps.retries.total` - Number of step retries
  - `saga.steps.compensations.total` - Number of compensations executed

- **System Metrics**
  - `saga.engine.active.instances` - Currently active saga instances
  - `saga.engine.queue.size` - Processing queue size
  - `saga.engine.thread.pool.utilization` - Thread pool utilization

### AWS CloudWatch Integration

When using the AWS starter, metrics are automatically published to CloudWatch:

```yaml
transactional-engine:
  aws:
    cloudwatch:
      enabled: true
      namespace: "TransactionalEngine"
      dimension-filters:
        - sagaId
        - stepId
        - environment
      publish-interval: 30s
```

### Azure Application Insights Integration

For Azure environments:

```yaml
transactional-engine:
  azure:
    application-insights:
      enabled: true
      instrumentation-key: ${AZURE_INSIGHTS_KEY}
      custom-dimensions:
        - sagaId
        - stepId
        - correlationId
```

## Logging

### Structured Logging

The engine uses structured logging with correlation IDs for distributed tracing:

```java
@SagaStep(id = "process-payment")
public Mono<PaymentResult> processPayment(@Input("orderId") String orderId) {
    log.info("Processing payment for order: {}", orderId);
    // Automatic correlation ID propagation
    return paymentService.process(orderId);
}
```

### Log Levels

Configure logging levels for different components:

```yaml
logging:
  level:
    com.catalis.transactionalengine: INFO
    com.catalis.transactionalengine.engine: DEBUG
    com.catalis.transactionalengine.compensation: WARN
```

### AWS CloudWatch Logs

Automatic log aggregation with the AWS starter:

```yaml
transactional-engine:
  aws:
    cloudwatch:
      logs:
        enabled: true
        log-group: "/aws/transactional-engine"
        log-stream: "${spring.application.name}-${random.uuid}"
```

## Distributed Tracing

### Correlation ID Propagation

The engine automatically propagates correlation IDs across:
- HTTP requests
- Message queue communications
- Database operations
- Compensation flows

### Integration with Tracing Systems

#### Zipkin Integration

```xml
<dependency>
    <groupId>io.zipkin.brave</groupId>
    <artifactId>brave-spring-boot-starter</artifactId>
</dependency>
```

#### Jaeger Integration

```xml
<dependency>
    <groupId>io.opentracing.contrib</groupId>
    <artifactId>opentracing-spring-jaeger-starter</artifactId>
</dependency>
```

## Event Publishing

### Saga Events

Monitor saga lifecycle through events:

```java
@EventListener
public void handleSagaStarted(SagaStartedEvent event) {
    metricsCollector.incrementSagaStarted(event.getSagaId());
}

@EventListener
public void handleSagaCompleted(SagaCompletedEvent event) {
    metricsCollector.recordSagaDuration(
        event.getSagaId(), 
        event.getDuration()
    );
}
```

### Step Events

Track individual step execution:

```java
@EventListener
public void handleStepExecuted(StepExecutedEvent event) {
    if (event.isCompensation()) {
        alertingService.sendCompensationAlert(event);
    }
}
```

## Health Checks

### Built-in Health Indicators

The engine provides health check endpoints:

- `/actuator/health/saga-engine` - Engine health status
- `/actuator/health/saga-persistence` - Persistence layer health
- `/actuator/health/saga-messaging` - Message bus health

### Custom Health Checks

```java
@Component
public class CustomSagaHealthIndicator implements HealthIndicator {
    
    @Override
    public Health health() {
        // Custom health logic
        return Health.up()
            .withDetail("activeSagas", getActiveSagaCount())
            .withDetail("queueDepth", getQueueDepth())
            .build();
    }
}
```

## Alerting

### Failure Alerts

Configure alerts for saga failures:

```yaml
transactional-engine:
  alerting:
    enabled: true
    failure-threshold: 5
    time-window: 300s
    channels:
      - slack
      - email
    
  slack:
    webhook-url: ${SLACK_WEBHOOK_URL}
    channel: "#alerts"
```

### Performance Alerts

Set up performance monitoring:

```yaml
transactional-engine:
  alerting:
    performance:
      saga-duration-threshold: 30s
      step-duration-threshold: 10s
      queue-depth-threshold: 1000
```

## Dashboard Setup

### Grafana Dashboard

Import the provided Grafana dashboard template:

```json
{
  "dashboard": {
    "title": "Transactional Engine Monitoring",
    "panels": [
      {
        "title": "Saga Execution Rate",
        "type": "graph",
        "targets": [
          {
            "expr": "rate(saga_executions_total[5m])"
          }
        ]
      }
    ]
  }
}
```

### AWS CloudWatch Dashboard

Example CloudWatch dashboard configuration:

```yaml
Resources:
  TransactionalEngineDashboard:
    Type: AWS::CloudWatch::Dashboard
    Properties:
      DashboardName: TransactionalEngine
      DashboardBody: |
        {
          "widgets": [
            {
              "type": "metric",
              "properties": {
                "metrics": [
                  ["TransactionalEngine", "saga.executions.total"]
                ],
                "period": 300,
                "stat": "Sum",
                "region": "us-east-1",
                "title": "Saga Executions"
              }
            }
          ]
        }
```

## Troubleshooting

### Common Issues

1. **High Compensation Rate**
   - Check saga step failure patterns
   - Review retry policies
   - Analyze step dependencies

2. **Performance Degradation**
   - Monitor queue depths
   - Check database connection pools
   - Review thread pool utilization

3. **Memory Leaks**
   - Monitor heap usage patterns
   - Check for stuck saga instances
   - Review cleanup policies

### Debug Mode

Enable detailed debugging:

```yaml
transactional-engine:
  debug:
    enabled: true
    log-step-inputs: true
    log-step-outputs: true
    log-compensation-details: true
```

## Best Practices

1. **Metric Naming**: Use consistent naming conventions
2. **Alert Thresholds**: Set appropriate thresholds based on SLAs
3. **Dashboard Design**: Focus on key business and technical metrics
4. **Log Retention**: Configure appropriate retention policies
5. **Correlation IDs**: Ensure correlation ID propagation across all systems
6. **Performance Baselines**: Establish performance baselines for alerting

## See Also

- [Performance Optimization](performance.md)
- [Production Deployment](production.md)
- [AWS Integration](aws-integration.md)
- [Azure Integration](azure-integration.md)