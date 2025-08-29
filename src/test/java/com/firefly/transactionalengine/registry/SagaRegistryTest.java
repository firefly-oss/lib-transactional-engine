package com.firefly.transactionalengine.registry;

import com.firefly.transactionalengine.annotations.Saga;
import com.firefly.transactionalengine.annotations.SagaStep;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

class SagaRegistryTest {

    @Configuration
    static class GoodConfig {
        @Bean
        public FooSaga fooSaga() { return new FooSaga(); }
    }

    @Saga(name = "Foo")
    static class FooSaga {
        @SagaStep(id = "a", compensate = "undoA")
        public Mono<String> a() { return Mono.just("ok"); }
        public Mono<Void> undoA(String res) { return Mono.empty(); }

        @SagaStep(id = "b", compensate = "undoB", dependsOn = {"a"})
        public Mono<Void> b() { return Mono.empty(); }
        public void undoB() {}
    }

    @Test
    void scansAndBuildsSaga() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(GoodConfig.class);
        SagaRegistry reg = new SagaRegistry(ctx);
        SagaDefinition def = reg.getSaga("Foo");
        assertEquals("Foo", def.name);
        assertTrue(def.steps.containsKey("a"));
        assertTrue(def.steps.containsKey("b"));
        assertNotNull(def.steps.get("a").compensateInvocationMethod);
        ctx.close();
    }

    @Configuration
    static class DuplicateIdConfig {
        @Bean public BadDupIdSaga badDupIdSaga() { return new BadDupIdSaga(); }
    }

    @Saga(name = "BadDup")
    static class BadDupIdSaga {
        @SagaStep(id = "x", compensate = "u") public void x() {}
        public void u() {}
        @SagaStep(id = "x", compensate = "u") public void y() {}
    }

    @Test
    void duplicateStepIdThrows() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(DuplicateIdConfig.class);
        SagaRegistry reg = new SagaRegistry(ctx);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> reg.getAll());
        assertTrue(ex.getMessage().contains("Duplicate step id"));
        ctx.close();
    }

    @Configuration
    static class MissingDepConfig { @Bean public MissingDepSaga s() { return new MissingDepSaga(); } }

    @Saga(name = "MissingDep")
    static class MissingDepSaga {
        @SagaStep(id = "x", compensate = "u", dependsOn = {"nope"}) public void x() {}
        public void u() {}
    }

    @Test
    void missingDependencyThrows() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(MissingDepConfig.class);
        SagaRegistry reg = new SagaRegistry(ctx);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> reg.getAll());
        assertTrue(ex.getMessage().contains("depends on missing"));
        ctx.close();
    }

    @Configuration
    static class CycleConfig { @Bean public CycleSaga s() { return new CycleSaga(); } }

    @Saga(name = "Cycle")
    static class CycleSaga {
        @SagaStep(id = "a", compensate = "ua", dependsOn = {"b"}) public void a() {}
        public void ua() {}
        @SagaStep(id = "b", compensate = "ub", dependsOn = {"a"}) public void b() {}
        public void ub() {}
    }

    @Test
    void cycleThrows() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(CycleConfig.class);
        SagaRegistry reg = new SagaRegistry(ctx);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> reg.getAll());
        assertTrue(ex.getMessage().contains("Cycle detected"));
        ctx.close();
    }

    @Configuration
    static class MissingCompConfig { @Bean public MissingCompSaga s() { return new MissingCompSaga(); } }

    @Saga(name = "MissingComp")
    static class MissingCompSaga {
        @SagaStep(id = "a", compensate = "noSuch") public void a() {}
    }

    @Test
    void missingCompensationMethodThrows() {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(MissingCompConfig.class);
        SagaRegistry reg = new SagaRegistry(ctx);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> reg.getAll());
        assertTrue(ex.getMessage().contains("Compensation method"));
        ctx.close();
    }
}
