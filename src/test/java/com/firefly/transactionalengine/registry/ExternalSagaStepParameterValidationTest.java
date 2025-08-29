package com.firefly.transactionalengine.registry;

import com.firefly.transactionalengine.annotations.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class ExternalSagaStepParameterValidationTest {

    @Configuration
    static class AppConfig {
        @Bean public Orchestrator orchestrator() { return new Orchestrator(); }
        @Bean public ExternalSteps externalSteps() { return new ExternalSteps(); }
    }

    @Saga(name = "ParamValidSaga")
    static class Orchestrator { }

    static class ExternalSteps {
        @ExternalSagaStep(saga = "ParamValidSaga", id = "a")
        public Mono<String> a(@Header("X-User-Id") Integer wrongType) { return Mono.just("ok"); }
    }

    @Test
    void external_step_with_wrong_parameter_annotation_type_fails_startup() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        try {
            SagaRegistry reg = new SagaRegistry(ctx);
            IllegalStateException ex = assertThrows(IllegalStateException.class, reg::getAll);
            assertTrue(ex.getMessage().contains("@Header"));
        } finally {
            ctx.close();
        }
    }
}
