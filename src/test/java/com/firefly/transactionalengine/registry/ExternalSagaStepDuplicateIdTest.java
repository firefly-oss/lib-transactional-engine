package com.firefly.transactionalengine.registry;

import com.firefly.transactionalengine.annotations.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class ExternalSagaStepDuplicateIdTest {

    @Configuration
    static class AppConfig {
        @Bean public Orchestrator orchestrator() { return new Orchestrator(); }
        @Bean public ExternalSteps externalSteps() { return new ExternalSteps(); }
    }

    @Saga(name = "DupSaga")
    static class Orchestrator {
        @SagaStep(id = "a", compensate = "u")
        public Mono<String> a() { return Mono.just("in-class"); }
        public Mono<Void> u(String res) { return Mono.empty(); }
    }

    static class ExternalSteps {
        @ExternalSagaStep(saga = "DupSaga", id = "a")
        public Mono<String> a() { return Mono.just("external"); }
    }

    @Test
    void duplicate_step_id_between_in_class_and_external_throws() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        try {
            SagaRegistry reg = new SagaRegistry(ctx);
            IllegalStateException ex = assertThrows(IllegalStateException.class, reg::getAll);
            assertTrue(ex.getMessage().contains("Duplicate step id 'a'"));
        } finally {
            ctx.close();
        }
    }
}
