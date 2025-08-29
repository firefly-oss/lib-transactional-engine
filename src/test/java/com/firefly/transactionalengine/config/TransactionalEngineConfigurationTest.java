package com.firefly.transactionalengine.config;

import com.firefly.transactionalengine.annotations.EnableTransactionalEngine;
import com.firefly.transactionalengine.annotations.Saga;
import com.firefly.transactionalengine.annotations.SagaStep;
import com.firefly.transactionalengine.engine.SagaEngine;
import com.firefly.transactionalengine.observability.SagaEvents;
import com.firefly.transactionalengine.registry.SagaRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class TransactionalEngineConfigurationTest {

    @Configuration
    @EnableTransactionalEngine
    static class AppConfig {
        @Bean public DemoSaga demoSaga() { return new DemoSaga(); }
    }

    @Saga(name = "Demo")
    static class DemoSaga {
        @SagaStep(id = "x", compensate = "ux") public Mono<String> x() { return Mono.just("ok"); }
        public Mono<Void> ux(String res) { return Mono.empty(); }
    }

    @Test
    void beansAreWired() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        assertNotNull(ctx.getBean(SagaRegistry.class));
        assertNotNull(ctx.getBean(SagaEngine.class));
        assertNotNull(ctx.getBean(SagaEvents.class));
        assertNotNull(ctx.getBean(WebClient.Builder.class));
        // Aspect bean exists
        assertNotNull(ctx.getBean("stepLoggingAspect"));
        // Registry can load our saga
        assertNotNull(ctx.getBean(SagaRegistry.class).getSaga("Demo"));
        ctx.close();
    }
}
