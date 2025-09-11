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
    com.firefly.transactional: INFO
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
    com.firefly.transactional: INFO
    com.firefly.transactional.persistence: DEBUG
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
    com.firefly.transactional: INFO
    com.firefly.transactional.persistence: WARN
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
    com.firefly.transactional: DEBUG
    com.firefly.transactional.persistence: DEBUG
    com.firefly.transactional.engine: DEBUG
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

## Testing Configuration

### application-test.yml
```yaml
spring:
  application:
    name: saga-engine-test
  profiles:
    active: test

firefly:
  saga:
    engine:
      # Test engine settings
      compensation-policy: STRICT_SEQUENTIAL
      auto-optimization-enabled: false
      max-concurrent-sagas: 5

      # Test persistence (in-memory by default)
      persistence:
        enabled: false  # Use in-memory for unit tests

      # Test observability
      observability:
        metrics-enabled: false
        tracing-enabled: false
        detailed-logging-enabled: true

      # Test validation
      validation:
        enabled: true
        validate-at-startup: true
        fail-fast: true

# Test logging
logging:
  level:
    com.firefly.transactionalengine: DEBUG
    org.springframework.test: INFO
```

### Integration Test with Testcontainers
```java
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
    "firefly.saga.engine.persistence.enabled=true",
    "firefly.saga.engine.persistence.redis.key-prefix=test:saga:"
})
class SagaPersistenceIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("firefly.saga.engine.persistence.redis.host", redis::getHost);
        registry.add("firefly.saga.engine.persistence.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private SagaEngine sagaEngine;

    @Autowired
    private SagaPersistenceProvider persistenceProvider;

    @Test
    void shouldPersistAndRecoverSaga() {
        // Execute saga
        SagaResult result = sagaEngine.execute("test-saga", StepInputs.empty()).block();
        assertThat(result.isSuccess()).isTrue();

        // Verify persistence
        Optional<SagaExecutionState> state = persistenceProvider
            .getSagaState(result.getCorrelationId()).block();
        assertThat(state).isPresent();
        assertThat(state.get().getStatus().isCompleted()).isTrue();
    }
}
```

## Docker Compose Setup

### docker-compose.yml
```yaml
version: '3.8'

services:
  redis:
    image: redis:7-alpine
    container_name: saga-redis
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
    volumes:
      - redis_data:/data
    environment:
      - REDIS_REPLICATION_MODE=master
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  redis-commander:
    image: rediscommander/redis-commander:latest
    container_name: saga-redis-commander
    ports:
      - "8081:8081"
    environment:
      - REDIS_HOSTS=local:redis:6379
    depends_on:
      - redis
    restart: unless-stopped

  app:
    build: .
    container_name: saga-app
    ports:
      - "8080:8080"
    environment:
      - SPRING_PROFILES_ACTIVE=docker
      - REDIS_HOST=redis
      - REDIS_PORT=6379
    depends_on:
      redis:
        condition: service_healthy
    restart: unless-stopped

volumes:
  redis_data:
    driver: local
```

### application-docker.yml
```yaml
spring:
  application:
    name: saga-engine-docker

firefly:
  saga:
    engine:
      persistence:
        enabled: true
        redis:
          host: ${REDIS_HOST:redis}
          port: ${REDIS_PORT:6379}
          database: 0
          key-prefix: "docker:saga:"
          key-ttl: PT24H

logging:
  level:
    com.firefly.transactionalengine: INFO
```

## Spring Boot Integration

### Complete Application Example
```java
@SpringBootApplication
@EnableTransactionalEngine
@EnableScheduling
public class SagaApplication {

    public static void main(String[] args) {
        SpringApplication.run(SagaApplication.class, args);
    }

    @Bean
    public SagaHealthIndicator sagaHealthIndicator(SagaEngine sagaEngine) {
        return new SagaHealthIndicator(sagaEngine);
    }

    @Component
    public static class SagaHealthIndicator implements HealthIndicator {

        private final SagaEngine sagaEngine;

        public SagaHealthIndicator(SagaEngine sagaEngine) {
            this.sagaEngine = sagaEngine;
        }

        @Override
        public Health health() {
            try {
                boolean healthy = sagaEngine.isPersistenceHealthy()
                    .block(Duration.ofSeconds(5));

                return healthy ?
                    Health.up()
                        .withDetail("provider", sagaEngine.getPersistenceProvider().getProviderType())
                        .build() :
                    Health.down()
                        .withDetail("reason", "Persistence provider is not healthy")
                        .build();
            } catch (Exception e) {
                return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
            }
        }
    }
}
```

### Custom Configuration Bean
```java
@Configuration
public class CustomSagaConfiguration {

    @Bean
    @Primary
    public SagaEngineProperties customSagaEngineProperties() {
        SagaEngineProperties properties = new SagaEngineProperties();

        // Customize engine properties
        properties.setCompensationPolicy(SagaEngine.CompensationPolicy.BEST_EFFORT_PARALLEL);
        properties.setMaxConcurrentSagas(200);

        // Customize persistence properties
        SagaEngineProperties.PersistenceProperties persistence = properties.getPersistence();
        persistence.setEnabled(true);
        persistence.setMaxSagaAge(Duration.ofHours(2));

        // Customize Redis properties
        SagaEngineProperties.RedisProperties redis = persistence.getRedis();
        redis.setKeyPrefix("custom:saga:");
        redis.setKeyTtl(Duration.ofHours(48));

        return properties;
    }
}
```

## Environment Variables

### Common Environment Variables
```bash
# Redis Configuration
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_DATABASE=0
REDIS_PASSWORD=your-password

# Saga Engine Configuration
SAGA_MAX_CONCURRENT=100
SAGA_MAX_AGE=PT24H
SAGA_CLEANUP_INTERVAL=PT1H

# Logging
LOGGING_LEVEL_SAGA=INFO
LOGGING_LEVEL_PERSISTENCE=DEBUG

# Management
MANAGEMENT_ENDPOINTS_ENABLED=true
MANAGEMENT_HEALTH_SHOW_DETAILS=always
```

### Docker Environment File (.env)
```bash
# Redis
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_DATABASE=0

# Application
SPRING_PROFILES_ACTIVE=docker
SERVER_PORT=8080

# Saga Engine
FIREFLY_SAGA_ENGINE_PERSISTENCE_ENABLED=true
FIREFLY_SAGA_ENGINE_PERSISTENCE_REDIS_KEY_PREFIX=docker:saga:
FIREFLY_SAGA_ENGINE_MAX_CONCURRENT_SAGAS=50
```
