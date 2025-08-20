package com.catalis.transactionalengine.it;

import com.catalis.transactionalengine.annotations.*;
import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.engine.SagaEngine;
import com.catalis.transactionalengine.engine.StepInputs;
import com.catalis.transactionalengine.registry.SagaRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ParameterInjectionIT {

    private AnnotationConfigApplicationContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
    }

    @Configuration
    @EnableTransactionalEngine
    static class AppConfig {
        @Bean public Orchestrator orchestrator() { return new Orchestrator(); }
    }

    @Saga(name = "ParamSaga")
    static class Orchestrator {
        @SagaStep(id = "r1", compensate = "cr1")
        public Mono<String> r1() { return Mono.just("R1"); }
        public Mono<Void> cr1(String res) { return Mono.empty(); }

        @SagaStep(id = "p1", compensate = "cp1", dependsOn = {"r1"})
        public Mono<String> p1(@FromStep("r1") String r1, @Header("X-User-Id") String user, SagaContext ctx) {
            return Mono.just("P1:" + r1 + ":" + user + ":" + ctx.correlationId());
        }
        public Mono<Void> cp1(String res, SagaContext ctx) { return Mono.empty(); }

        @SagaStep(id = "c1", compensate = "cc1", dependsOn = {"p1"})
        public Mono<String> c1(@Headers Map<String,String> headers, @Input("extra") String extra) {
            return Mono.just("C1:" + headers.get("X-User-Id") + ":" + extra);
        }
        public Mono<Void> cc1(String res) { return Mono.empty(); }
    }

    @Test
    void multiparameter_injection_works() {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        SagaRegistry reg = ctx.getBean(SagaRegistry.class);
        assertNotNull(reg.getSaga("ParamSaga"));

        SagaEngine engine = ctx.getBean(SagaEngine.class);
        SagaContext sctx = new SagaContext("corr-param-1");
        sctx.putHeader("X-User-Id", "u1");

        StepInputs inputs = StepInputs.builder()
                .forStepId("c1", Map.of("extra", "E"))
                .build();

        var results = engine.run("ParamSaga", inputs, sctx).block();
        assertNotNull(results);
        assertEquals("R1", results.get("r1"));
        assertTrue(((String) results.get("p1")).startsWith("P1:R1:u1:"));
        assertEquals("C1:u1:E", results.get("c1"));
    }
}
