package com.catalis.transactionalengine.it;

import com.catalis.transactionalengine.annotations.*;
import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.core.SagaResult;
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

class ParameterInjectionMethodRefIT {

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

    @Saga(name = "ParamSagaMR")
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
    void method_reference_overload_compiles_and_runs() {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        SagaRegistry reg = ctx.getBean(SagaRegistry.class);
        assertNotNull(reg.getSaga("ParamSagaMR"));

        SagaEngine engine = ctx.getBean(SagaEngine.class);
        SagaContext sctx = new SagaContext("corr-param-mr-1");
        sctx.putHeader("X-User-Id", "u1");

        // Use Class::method directly
        StepInputs inputs = StepInputs.builder()
                .forStep(Orchestrator::c1, Map.of("extra", "E"))
                .build();

        SagaResult result = engine.execute("ParamSagaMR", inputs, sctx).block();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("R1", result.resultOf("r1", String.class).orElse(null));
        assertTrue(result.resultOf("p1", String.class).orElse("").startsWith("P1:R1:u1:"));
        assertEquals("C1:u1:E", result.resultOf("c1", String.class).orElse(null));
    }
}
