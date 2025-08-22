package com.catalis.transactionalengine.registry;

import com.catalis.transactionalengine.annotations.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class RegistryParameterValidationTest {

    @Configuration
    static class BadHeaderTypeConfig { @Bean public BadHeaderType s() { return new BadHeaderType(); } }

    @Saga(name = "BadHeaderType")
    static class BadHeaderType {
        @SagaStep(id = "a", compensate = "u")
        public Mono<String> a(@Header("X-User-Id") Integer wrong) { return Mono.just("x"); }
        public void u() {}
    }

    @Test
    void headerWithWrongTypeFailsStartup() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(BadHeaderTypeConfig.class);
        SagaRegistry reg = new SagaRegistry(ctx);
        IllegalStateException ex = assertThrows(IllegalStateException.class, reg::getAll);
        assertTrue(ex.getMessage().contains("@Header"));
        ctx.close();
    }

    @Configuration
    static class MissingFromStepConfig { @Bean public MissingFromStep s() { return new MissingFromStep(); } }

    @Saga(name = "MissingFrom")
    static class MissingFromStep {
        @SagaStep(id = "a", compensate = "u")
        public Mono<String> a(@FromStep("nope") String missing) { return Mono.just("x"); }
        public void u() {}
    }

    @Test
    void fromStepMissingReferenceFailsStartup() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(MissingFromStepConfig.class);
        SagaRegistry reg = new SagaRegistry(ctx);
        IllegalStateException ex = assertThrows(IllegalStateException.class, reg::getAll);
        assertTrue(ex.getMessage().contains("references missing step"));
        ctx.close();
    }
}
