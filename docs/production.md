# Production Deployment

This guide covers best practices and operational considerations for deploying the Firefly Transactional Engine in production environments.

## Overview

Deploying a distributed transaction engine in production requires careful planning, proper configuration, and comprehensive monitoring. This guide provides recommendations for reliable, scalable, and maintainable production deployments.

## Pre-Production Checklist

### Infrastructure Requirements

- [ ] **Compute Resources**
  - Minimum 2 CPU cores, 4GB RAM per instance
  - Recommended 4+ CPU cores, 8GB+ RAM for high throughput
  - SSD storage for optimal I/O performance

- [ ] **Database Setup**
  - Primary database with read replicas
  - Proper indexing and partitioning
  - Backup and recovery procedures
  - Connection pooling configured

- [ ] **Message Infrastructure**
  - Message broker cluster (AWS SQS, Azure Service Bus, or RabbitMQ)
  - Dead letter queues configured
  - Monitoring and alerting setup

- [ ] **Network Requirements**
  - Load balancer configuration
  - Service mesh setup (optional)
  - Security groups and firewall rules
  - SSL/TLS certificates

### Security Considerations

- [ ] **Authentication & Authorization**
  - Service-to-service authentication
  - Role-based access control (RBAC)
  - API key management
  - JWT token validation

- [ ] **Encryption**
  - Data at rest encryption
  - Data in transit encryption (TLS 1.3)
  - Database connection encryption
  - Message encryption

- [ ] **Secrets Management**
  - Externalized configuration
  - Secret rotation policies
  - Secure credential storage (AWS Secrets Manager, Azure Key Vault)

## Deployment Strategies

### Blue-Green Deployment

Implement zero-downtime deployments:

```yaml
# Blue environment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: transactional-engine-blue
  labels:
    version: blue
spec:
  replicas: 3
  selector:
    matchLabels:
      app: transactional-engine
      version: blue
  template:
    metadata:
      labels:
        app: transactional-engine
        version: blue
    spec:
      containers:
      - name: transactional-engine
        image: transactional-engine:v1.2.0
        env:
        - name: ENVIRONMENT
          value: "production"
        - name: DEPLOYMENT_VERSION
          value: "blue"
```

### Canary Deployment

Gradual rollout with traffic splitting:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: transactional-engine
spec:
  strategy:
    canary:
      steps:
      - setWeight: 10
      - pause: {duration: 10m}
      - setWeight: 25
      - pause: {duration: 10m}
      - setWeight: 50
      - pause: {duration: 10m}
      - setWeight: 75
      - pause: {duration: 10m}
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
        image: transactional-engine:latest
```

## Configuration Management

### Environment-Specific Configuration

```yaml
# Production configuration
transactional-engine:
  environment: production
  
  # Thread pool configuration for production
  execution:
    core-pool-size: 20
    max-pool-size: 100
    queue-capacity: 5000
    
  # Database settings
  persistence:
    batch-updates: true
    batch-size: 500
    async-persistence: true
    
  # Monitoring and observability
  metrics:
    enabled: true
    detailed: true
    export-interval: 30s
    
  # Security settings
  security:
    ssl-enabled: true
    mutual-tls: true
    api-key-required: true
    
  # Resilience patterns
  resilience:
    circuit-breaker:
      enabled: true
      failure-rate-threshold: 50
      wait-duration: 60s
    
    retry:
      max-attempts: 3
      exponential-backoff: true
      max-delay: 10s
```

### ConfigMaps and Secrets

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: transactional-engine-config
data:
  application-production.yml: |
    transactional-engine:
      persistence:
        cleanup:
          enabled: true
          retention-period: 90d
      monitoring:
        health-check-interval: 30s
        metrics-export-interval: 60s

---
apiVersion: v1
kind: Secret
metadata:
  name: transactional-engine-secrets
type: Opaque
data:
  database-password: <base64-encoded-password>
  api-key: <base64-encoded-api-key>
  aws-access-key: <base64-encoded-aws-key>
```

## High Availability Setup

### Multi-Region Deployment

```yaml
# Primary region deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: transactional-engine-primary
  namespace: production
spec:
  replicas: 5
  selector:
    matchLabels:
      app: transactional-engine
      region: primary
  template:
    spec:
      affinity:
        nodeAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            nodeSelectorTerms:
            - matchExpressions:
              - key: topology.kubernetes.io/zone
                operator: In
                values:
                - us-east-1a
                - us-east-1b
                - us-east-1c
      containers:
      - name: transactional-engine
        image: transactional-engine:latest
        resources:
          requests:
            memory: "4Gi"
            cpu: "2000m"
          limits:
            memory: "8Gi"
            cpu: "4000m"
```

### Database High Availability

```yaml
# PostgreSQL cluster configuration
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: postgres-transactional-engine
spec:
  instances: 3
  
  postgresql:
    parameters:
      max_connections: "500"
      shared_buffers: "256MB"
      effective_cache_size: "1GB"
      maintenance_work_mem: "64MB"
      
  storage:
    size: 1Ti
    storageClass: fast-ssd
    
  monitoring:
    enabled: true
    
  backup:
    retention: 30d
    schedule: "0 2 * * *"
```

## Monitoring and Observability

### Production Monitoring Stack

```yaml
# Prometheus configuration
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: transactional-engine
spec:
  selector:
    matchLabels:
      app: transactional-engine
  endpoints:
  - port: metrics
    interval: 30s
    path: /actuator/prometheus

---
# Alert rules
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: transactional-engine-alerts
spec:
  groups:
  - name: transactional-engine
    rules:
    - alert: HighSagaFailureRate
      expr: rate(saga_executions_failures_total[5m]) > 0.1
      for: 2m
      labels:
        severity: warning
      annotations:
        summary: "High saga failure rate detected"
        
    - alert: SagaEngineDown
      expr: up{job="transactional-engine"} == 0
      for: 1m
      labels:
        severity: critical
      annotations:
        summary: "Transactional Engine instance is down"
```

### Centralized Logging

```yaml
# Fluentd configuration for log aggregation
apiVersion: v1
kind: ConfigMap
metadata:
  name: fluentd-config
data:
  fluent.conf: |
    <source>
      @type tail
      path /var/log/containers/transactional-engine*.log
      pos_file /var/log/fluentd-containers.log.pos
      tag transactional-engine.*
      format json
    </source>
    
    <filter transactional-engine.**>
      @type parser
      key_name log
      <parse>
        @type json
      </parse>
    </filter>
    
    <match transactional-engine.**>
      @type elasticsearch
      host elasticsearch.logging.svc.cluster.local
      port 9200
      index_name transactional-engine
    </match>
```

## Backup and Recovery

### Database Backup Strategy

```bash
#!/bin/bash
# Production backup script

BACKUP_DIR="/backups/transactional-engine"
DATE=$(date +%Y%m%d_%H%M%S)
DATABASE="transactional_engine"

# Create database backup
pg_dump -h $DB_HOST -U $DB_USER -W $DATABASE | gzip > "$BACKUP_DIR/db_backup_$DATE.sql.gz"

# Upload to S3
aws s3 cp "$BACKUP_DIR/db_backup_$DATE.sql.gz" "s3://backups-bucket/transactional-engine/"

# Cleanup old backups (keep last 30 days)
find $BACKUP_DIR -name "db_backup_*.sql.gz" -mtime +30 -delete
```

### Disaster Recovery Plan

1. **RTO (Recovery Time Objective)**: 15 minutes
2. **RPO (Recovery Point Objective)**: 5 minutes

**Recovery Steps:**
1. Activate standby database
2. Update DNS/load balancer
3. Deploy application to backup region
4. Verify system functionality
5. Resume normal operations

### Configuration Backup

```yaml
# Velero backup configuration
apiVersion: velero.io/v1
kind: Backup
metadata:
  name: transactional-engine-backup
spec:
  includedNamespaces:
  - production
  includedResources:
  - configmaps
  - secrets
  - deployments
  - services
  labelSelector:
    matchLabels:
      app: transactional-engine
  schedule: "0 1 * * *"
  ttl: 720h0m0s
```

## Performance Optimization

### Production Tuning

```yaml
transactional-engine:
  # Optimized for production workloads
  execution:
    core-pool-size: 50
    max-pool-size: 200
    queue-capacity: 10000
    
  memory:
    instance-cache:
      max-size: 50000
      expire-after-access: 1h
    step-cache:
      max-size: 200000
      expire-after-access: 30m
      
  persistence:
    batch-size: 1000
    batch-timeout: 50ms
    connection-pool-size: 100
    
  # Performance monitoring
  metrics:
    jvm-metrics: true
    system-metrics: true
    custom-metrics: true
```

### JVM Production Settings

```bash
# Production JVM arguments
JAVA_OPTS="
-server
-Xms8g
-Xmx8g
-XX:+UseG1GC
-XX:MaxGCPauseMillis=100
-XX:+UseStringDeduplication
-XX:+OptimizeStringConcat
-XX:+UseCompressedOops
-XX:+UseCompressedClassPointers
-Djava.security.egd=file:/dev/./urandom
-Djava.awt.headless=true
-Dfile.encoding=UTF-8
-Duser.timezone=UTC
"
```

## Security Best Practices

### Network Security

```yaml
# Network policies
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: transactional-engine-network-policy
spec:
  podSelector:
    matchLabels:
      app: transactional-engine
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: api-gateway
    ports:
    - protocol: TCP
      port: 8080
  egress:
  - to:
    - podSelector:
        matchLabels:
          app: postgresql
    ports:
    - protocol: TCP
      port: 5432
```

### Pod Security Standards

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: transactional-engine
spec:
  securityContext:
    runAsNonRoot: true
    runAsUser: 1001
    fsGroup: 2000
    seccompProfile:
      type: RuntimeDefault
  containers:
  - name: transactional-engine
    securityContext:
      allowPrivilegeEscalation: false
      readOnlyRootFilesystem: true
      capabilities:
        drop:
        - ALL
    volumeMounts:
    - name: tmp
      mountPath: /tmp
    - name: cache
      mountPath: /app/cache
  volumes:
  - name: tmp
    emptyDir: {}
  - name: cache
    emptyDir: {}
```

## Operational Procedures

### Health Check Endpoints

```java
@Component
public class ProductionHealthIndicator implements HealthIndicator {
    
    @Override
    public Health health() {
        // Check critical dependencies
        boolean dbHealthy = checkDatabaseConnection();
        boolean messageQueueHealthy = checkMessageQueue();
        boolean cacheHealthy = checkCacheConnection();
        
        if (dbHealthy && messageQueueHealthy && cacheHealthy) {
            return Health.up()
                .withDetail("database", "UP")
                .withDetail("messageQueue", "UP")
                .withDetail("cache", "UP")
                .withDetail("activeSagas", getActiveSagaCount())
                .build();
        }
        
        return Health.down()
            .withDetail("database", dbHealthy ? "UP" : "DOWN")
            .withDetail("messageQueue", messageQueueHealthy ? "UP" : "DOWN")
            .withDetail("cache", cacheHealthy ? "UP" : "DOWN")
            .build();
    }
}
```

### Graceful Shutdown

```java
@Component
public class GracefulShutdownHandler {
    
    @PreDestroy
    public void gracefulShutdown() {
        log.info("Starting graceful shutdown...");
        
        // Stop accepting new saga requests
        sagaEngine.stopAcceptingNewRequests();
        
        // Wait for running sagas to complete
        int maxWaitSeconds = 30;
        int waited = 0;
        
        while (sagaEngine.hasActiveSagas() && waited < maxWaitSeconds) {
            try {
                Thread.sleep(1000);
                waited++;
                log.info("Waiting for {} active sagas to complete...", 
                    sagaEngine.getActiveSagaCount());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        // Force shutdown if sagas are still running
        if (sagaEngine.hasActiveSagas()) {
            log.warn("Force shutting down with {} active sagas", 
                sagaEngine.getActiveSagaCount());
        }
        
        log.info("Graceful shutdown completed");
    }
}
```

### Circuit Breaker Configuration

```yaml
transactional-engine:
  resilience:
    circuit-breaker:
      external-services:
        failure-rate-threshold: 50
        slow-call-rate-threshold: 70
        slow-call-duration-threshold: 5s
        wait-duration-in-open-state: 60s
        sliding-window-size: 100
        minimum-number-of-calls: 20
        
    bulkhead:
      max-concurrent-calls: 50
      max-wait-duration: 10s
      
    timeout:
      duration: 30s
```

## Troubleshooting

### Common Production Issues

1. **Memory Leaks**
   ```bash
   # Generate heap dump
   jcmd <PID> GC.run_finalization
   jcmd <PID> VM.gc
   jcmd <PID> GC.dump /tmp/heapdump.hprof
   ```

2. **Database Connection Issues**
   ```sql
   -- Check active connections
   SELECT count(*) FROM pg_stat_activity WHERE state = 'active';
   
   -- Check long-running queries
   SELECT pid, now() - pg_stat_activity.query_start AS duration, query 
   FROM pg_stat_activity 
   WHERE (now() - pg_stat_activity.query_start) > interval '5 minutes';
   ```

3. **High CPU Usage**
   ```bash
   # Check thread dumps
   jstack <PID> > thread_dump.txt
   
   # Monitor GC activity
   jstat -gc <PID> 5s
   ```

### Emergency Procedures

1. **Scale Out Quickly**
   ```bash
   kubectl scale deployment transactional-engine --replicas=10
   ```

2. **Emergency Circuit Breaker**
   ```bash
   kubectl set env deployment/transactional-engine CIRCUIT_BREAKER_ENABLED=true
   ```

3. **Database Failover**
   ```bash
   # Switch to read replica
   kubectl patch configmap transactional-engine-config --patch '{"data":{"database.url":"jdbc:postgresql://read-replica:5432/db"}}'
   kubectl rollout restart deployment/transactional-engine
   ```

## Maintenance Windows

### Planned Maintenance Checklist

- [ ] **Pre-maintenance**
  - Scale up instances for redundancy
  - Verify backup completion
  - Notify stakeholders
  - Enable maintenance mode

- [ ] **During Maintenance**
  - Monitor system health
  - Execute maintenance tasks
  - Validate changes incrementally

- [ ] **Post-maintenance**
  - Verify system functionality
  - Check performance metrics
  - Disable maintenance mode
  - Update documentation

## Compliance and Auditing

### Audit Logging

```yaml
transactional-engine:
  audit:
    enabled: true
    log-level: INFO
    include-request-body: false
    include-response-body: false
    events:
      - SAGA_STARTED
      - SAGA_COMPLETED
      - SAGA_FAILED
      - COMPENSATION_EXECUTED
      - SECURITY_VIOLATION
```

### Data Retention Policies

```yaml
transactional-engine:
  data-retention:
    saga-instances: 90d
    audit-logs: 7y
    metrics: 1y
    application-logs: 30d
    
  cleanup:
    enabled: true
    schedule: "0 2 * * *"
    batch-size: 1000
```

## See Also

- [Monitoring & Observability](monitoring.md)
- [Performance Optimization](performance.md)
- [Configuration Guide](configuration.md)
- [AWS Integration](aws-integration.md)
- [Azure Integration](azure-integration.md)