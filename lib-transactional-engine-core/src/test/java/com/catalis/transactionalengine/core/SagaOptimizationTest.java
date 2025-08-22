package com.catalis.transactionalengine.core;

import com.catalis.transactionalengine.registry.SagaDefinition;
import com.catalis.transactionalengine.registry.StepDefinition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for automatic saga optimization based on execution patterns.
 */
public class SagaOptimizationTest {
    
    @Test
    public void testSequentialSagaCanBeOptimized() {
        // Create a saga with sequential steps (each step depends on the previous)
        SagaDefinition saga = createSequentialSaga();
        
        boolean canOptimize = SagaOptimizationDetector.canOptimize(saga);
        assertTrue(canOptimize, "Sequential saga should be optimizable");
        
        var analysis = SagaOptimizationDetector.analyze(saga);
        assertTrue(analysis.canOptimize(), "Analysis should confirm optimization is possible");
        assertEquals(3, analysis.totalSteps(), "Should have 3 steps");
        assertEquals(3, analysis.layerCount(), "Should have 3 layers (sequential)");
        assertEquals(1, analysis.maxLayerSize(), "Max layer size should be 1 (sequential)");
    }
    
    @Test
    public void testConcurrentSagaCannotBeOptimized() {
        // Create a saga with concurrent steps (multiple steps can run in parallel)
        SagaDefinition saga = createConcurrentSaga();
        
        boolean canOptimize = SagaOptimizationDetector.canOptimize(saga);
        assertFalse(canOptimize, "Concurrent saga should not be optimizable");
        
        var analysis = SagaOptimizationDetector.analyze(saga);
        assertFalse(analysis.canOptimize(), "Analysis should confirm optimization is not possible");
        assertEquals(3, analysis.totalSteps(), "Should have 3 steps");
        assertEquals(2, analysis.layerCount(), "Should have 2 layers");
        assertEquals(2, analysis.maxLayerSize(), "Max layer size should be 2 (concurrent steps)");
    }
    
    @Test
    public void testSagaContextFactoryCreatesCorrectContext() {
        SagaContextFactory factory = new SagaContextFactory(true); // optimization enabled
        
        // Test sequential saga - should use optimization
        SagaDefinition sequentialSaga = createSequentialSaga();
        SagaContext sequentialContext = factory.createContext(sequentialSaga);
        assertNotNull(sequentialContext, "Should create context for sequential saga");
        
        // Test concurrent saga - should use standard context
        SagaDefinition concurrentSaga = createConcurrentSaga();
        SagaContext concurrentContext = factory.createContext(concurrentSaga);
        assertNotNull(concurrentContext, "Should create context for concurrent saga");
        
        // Test with optimization disabled
        SagaContextFactory disabledFactory = new SagaContextFactory(false);
        SagaContext disabledContext = disabledFactory.createContext(sequentialSaga);
        assertNotNull(disabledContext, "Should create standard context when optimization disabled");
    }
    
    @Test
    public void testOptimizationAnalysisDetails() {
        SagaDefinition saga = createConcurrentSaga();
        var analysis = SagaOptimizationDetector.analyze(saga);
        
        assertNotNull(analysis.layers(), "Should provide layer details");
        assertEquals(2, analysis.layers().size(), "Should have 2 layers");
        
        // First layer should have 1 step
        assertEquals(1, analysis.layers().get(0).stepCount(), "First layer should have 1 step");
        assertEquals(List.of("step1"), analysis.layers().get(0).stepIds(), "First layer should contain step1");
        
        // Second layer should have 2 concurrent steps
        assertEquals(2, analysis.layers().get(1).stepCount(), "Second layer should have 2 steps");
        assertTrue(analysis.layers().get(1).stepIds().contains("step2"), "Second layer should contain step2");
        assertTrue(analysis.layers().get(1).stepIds().contains("step3"), "Second layer should contain step3");
        
        assertTrue(analysis.reason().contains("concurrent"), "Reason should mention concurrent execution");
    }
    
    @Test
    public void testEmptySagaCanBeOptimized() {
        SagaDefinition emptySaga = new SagaDefinition("empty", null, null, 1);
        // steps map is already initialized, no need to reassign
        
        assertTrue(SagaOptimizationDetector.canOptimize(emptySaga), "Empty saga should be optimizable");
        
        var analysis = SagaOptimizationDetector.analyze(emptySaga);
        assertTrue(analysis.canOptimize(), "Empty saga analysis should confirm optimization");
        assertEquals(0, analysis.totalSteps(), "Empty saga should have 0 steps");
    }
    
    /**
     * Creates a sequential saga: step1 -> step2 -> step3
     */
    private SagaDefinition createSequentialSaga() {
        SagaDefinition saga = new SagaDefinition("sequential-saga", null, null, 1);
        
        // step1 (no dependencies)
        saga.steps.put("step1", new StepDefinition("step1", null, List.of(), 0, null, null, null, false, 0.0, false, null));
        
        // step2 depends on step1
        saga.steps.put("step2", new StepDefinition("step2", null, List.of("step1"), 0, null, null, null, false, 0.0, false, null));
        
        // step3 depends on step2
        saga.steps.put("step3", new StepDefinition("step3", null, List.of("step2"), 0, null, null, null, false, 0.0, false, null));
        
        return saga;
    }
    
    /**
     * Creates a concurrent saga: step1 -> (step2, step3) in parallel
     */
    private SagaDefinition createConcurrentSaga() {
        SagaDefinition saga = new SagaDefinition("concurrent-saga", null, null, 1);
        
        // step1 (no dependencies)
        saga.steps.put("step1", new StepDefinition("step1", null, List.of(), 0, null, null, null, false, 0.0, false, null));
        
        // step2 and step3 both depend on step1 (will execute concurrently)
        saga.steps.put("step2", new StepDefinition("step2", null, List.of("step1"), 0, null, null, null, false, 0.0, false, null));
        saga.steps.put("step3", new StepDefinition("step3", null, List.of("step1"), 0, null, null, null, false, 0.0, false, null));
        
        return saga;
    }
}