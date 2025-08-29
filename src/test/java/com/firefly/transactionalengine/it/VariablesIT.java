package com.firefly.transactionalengine.it;

import com.firefly.transactionalengine.annotations.*;
import com.firefly.transactionalengine.core.SagaContext;
import com.firefly.transactionalengine.core.SagaResult;
import com.firefly.transactionalengine.engine.SagaEngine;
import com.firefly.transactionalengine.engine.StepInputs;
import com.firefly.transactionalengine.registry.SagaRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VariablesIT {

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

    @Saga(name = "VarsSaga")
    static class Orchestrator {
        @SagaStep(id = "r1", compensate = "cr1")
        @SetVariable("foo")
        public Mono<String> r1() { return Mono.just("F"); }
        public Mono<Void> cr1(String res) { return Mono.empty(); }

        @SagaStep(id = "p1", compensate = "cp1", dependsOn = {"r1"})
        public Mono<String> p1(@Variable("foo") String foo, SagaContext ctx) {
            ctx.putVariable("bar", "B:" + foo);
            return Mono.just("P:" + foo);
        }
        public Mono<Void> cp1(String res, SagaContext ctx) { return Mono.empty(); }

        @SagaStep(id = "c1", compensate = "cc1", dependsOn = {"p1"})
        public Mono<String> c1(@Variables Map<String,Object> vars) {
            return Mono.just("C:" + vars.get("foo") + ":" + vars.get("bar"));
        }
        public Mono<Void> cc1(String res) { return Mono.empty(); }
    }

    @Test
    void variables_annotations_work() {
        ctx = new AnnotationConfigApplicationContext(AppConfig.class);
        SagaRegistry reg = ctx.getBean(SagaRegistry.class);
        assertNotNull(reg.getSaga("VarsSaga"));

        SagaEngine engine = ctx.getBean(SagaEngine.class);
        SagaContext sctx = new SagaContext("corr-vars-1");

        StepInputs inputs = StepInputs.builder().build();

        SagaResult result = engine.execute("VarsSaga", inputs, sctx).block();
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("VarsSaga", result.sagaName());
        assertEquals("VarsSaga", sctx.sagaName());

        assertEquals("F", result.resultOf("r1", String.class).orElse(null));
        assertEquals("P:F", result.resultOf("p1", String.class).orElse(null));
        assertEquals("C:F:B:F", result.resultOf("c1", String.class).orElse(null));

        assertEquals("F", sctx.getVariable("foo"));
        assertEquals("B:F", sctx.getVariable("bar"));
    }
}
