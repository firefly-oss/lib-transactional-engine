# Configuration Examples

This document provides comprehensive examples for configuring the Firefly Transactional Engine with different persistence providers and deployment scenarios.

## Table of Contents

- [Basic In-Memory Configuration](#basic-in-memory-configuration)
- [Redis Persistence Configuration](#redis-persistence-configuration)
- [Production Configuration](#production-configuration)
- [Development Configuration](#development-configuration)
- [Testing Configuration](#testing-configuration)
- [Docker Compose Setup](#docker-compose-setup)
- [Spring Boot Integration](#spring-boot-integration)

## Basic In-Memory Configuration

### application.yml
```yaml
# Basic configuration with in-memory persistence (default)
spring:
  application:
    name: saga-engine-app

firefly:
  saga:
    engine:
      compensation-policy: STRICT_SEQUENTIAL
      auto-optimization-enabled: true
      max-concurrent-sagas: 50
      
      # In-memory persistence (default)
      persistence:
        enabled: false  # Uses in-memory storage
      
      # Basic observability
      observability:
        metrics-enabled: true
        detailed-logging-enabled: true

logging:
  level:
    com.firefly.transactionalengine: INFO
```

### Java Configuration
```java
@SpringBootApplication
@EnableTransactionalEngine
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

## Redis Persistence Configuration

### application.yml
```yaml
spring:
  application:
    name: saga-redis-persistence-app

firefly:
  saga:
    engine:
      # Engine configuration
      compensation-policy: STRICT_SEQUENTIAL
      auto-optimization-enabled: true
      max-concurrent-sagas: 100
      
      # Redis persistence configuration
      persistence:
        enabled: true
        auto-recovery-enabled: true
        max-saga-age: PT24H
        cleanup-interval: PT1H
        retention-period: P7D
        
        redis:
          host: ${REDIS_HOST:localhost}
          port: ${REDIS_PORT:6379}
          database: ${REDIS_DATABASE:0}
          password: ${REDIS_PASSWORD:}
          key-prefix: "saga:"
          key-ttl: PT24H
          connection-timeout: PT5S
          command-timeout: PT10S
      
      # Enhanced observability
      observability:
        metrics-enabled: true
        tracing-enabled: true
        detailed-logging-enabled: false

# Logging configuration
logging:
  level:
    com.firefly.transactionalengine: INFO
    com.firefly.transactionalengine.persistence: DEBUG
  pattern:
    console: "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"

# Management endpoints
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always
```

### Java Configuration with Recovery
```java
@Configuration
@EnableScheduling
public class SagaConfiguration {

    @Bean
    public SagaRecoveryManager sagaRecoveryManager(SagaRecoveryService recoveryService) {
        return new SagaRecoveryManager(recoveryService);
    }

    @Component
    public static class SagaRecoveryManager {
        
        private final SagaRecoveryService recoveryService;
        
        public SagaRecoveryManager(SagaRecoveryService recoveryService) {
            this.recoveryService = recoveryService;
        }
        
        @EventListener(ApplicationReadyEvent.class)
        public void onApplicationReady() {
            recoveryService.recoverInFlightSagas()
                .subscribe(result -> {
                    log.info("Recovery completed: {} found, {} recovered, {} failed, {} skipped",
                        result.getTotalFound(),
                        result.getRecovered(),
                        result.getFailed(),
                        result.getSkipped());
                });
        }
        
        @Scheduled(fixedRate = 3600000) // Every hour
        public void cleanupStaleSagas() {
            recoveryService.cancelStaleSagas(Duration.ofHours(2))
                .subscribe(count -> {
                    if (count > 0) {
                        log.info("Cancelled {} stale sagas", count);
                    }
                });
        }
    }
}
```

## Production Configuration

### application-prod.yml
```yaml
spring:
  application:
    name: saga-engine-prod
  profiles:
    active: prod

firefly:
  saga:
    engine:
      # Production engine settings
      compensation-policy: STRICT_SEQUENTIAL
      auto-optimization-enabled: true
      max-concurrent-sagas: 500
      default-timeout: PT10M
      
      # Production context settings
      context:
        execution-mode: HIGH_PERFORMANCE
        optimization-enabled: true
      
      # Production backpressure settings
      backpressure:
        strategy: adaptive
        concurrency: 50
        batch-size: 200
        timeout: PT120S
      
      # Robust compensation handling
      compensation:
        error-handler: robust
        max-retries: 5
        retry-delay: PT1S
        fail-fast-on-critical-errors: true
      
      # Production persistence
      persistence:
        enabled: true
        auto-recovery-enabled: true
        max-saga-age: PT2H
        cleanup-interval: PT12H
        retention-period: P3D
        
        redis:
          host: ${REDIS_CLUSTER_HOST}
          port: ${REDIS_CLUSTER_PORT:6379}
          database: 1
          password: ${REDIS_PASSWORD}
          key-prefix: "prod:saga:"
          key-ttl: PT72H
          connection-timeout: PT10S
          command-timeout: PT30S
      
      # Production observability
      observability:
        metrics-enabled: true
        tracing-enabled: true
        detailed-logging-enabled: false
        metrics-interval: PT60S
      
      # Production validation
      validation:
        enabled: true
        validate-at-startup: true
        validate-inputs: true
        fail-fast: false

# Production logging
logging:
  level:
    root: WARN
    com.firefly.transactionalengine: INFO
    com.firefly.transactionalengine.persistence: WARN
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"

# Production management
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: when-authorized
  metrics:
    export:
      prometheus:
        enabled: true
```

## Development Configuration

### application-dev.yml
```yaml
spring:
  application:
    name: saga-engine-dev
  profiles:
    active: dev

firefly:
  saga:
    engine:
      # Development engine settings
      compensation-policy: STRICT_SEQUENTIAL
      auto-optimization-enabled: true
      max-concurrent-sagas: 10
      
      # Development context settings
      context:
        execution-mode: AUTO
        optimization-enabled: true
      
      # Development backpressure settings
      backpressure:
        strategy: batched
        concurrency: 5
        batch-size: 10
        timeout: PT30S
      
      # Simple compensation handling
      compensation:
        error-handler: log-and-continue
        max-retries: 3
        retry-delay: PT0.1S
      
      # Redis persistence for development
      persistence:
        enabled: true
        auto-recovery-enabled: true
        max-saga-age: PT1H
        cleanup-interval: PT30M
        retention-period: P1D
        
        redis:
          host: localhost
          port: 6379
          database: 0
          key-prefix: "dev:saga:"
          key-ttl: PT2H
          connection-timeout: PT5S
          command-timeout: PT10S
      
      # Development observability
      observability:
        metrics-enabled: true
        tracing-enabled: false
        detailed-logging-enabled: true
        metrics-interval: PT10S
      
      # Development validation
      validation:
        enabled: true
        validate-at-startup: true
        validate-inputs: true
        fail-fast: true

# Development logging
logging:
  level:
    com.firefly.transactionalengine: DEBUG
    com.firefly.transactionalengine.persistence: DEBUG
    com.firefly.transactionalengine.engine: DEBUG
  pattern:
    console: "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"

# Development management
management:
  endpoints:
    web:
      exposure:
        include: "*"
  endpoint:
    health:
      show-details: always
```
