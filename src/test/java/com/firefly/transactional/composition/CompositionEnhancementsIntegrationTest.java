/*
 * Copyright 2025 Firefly Software Solutions Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.firefly.transactional.saga.composition;

import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.saga.core.SagaResult;
import com.firefly.transactional.saga.engine.SagaEngine;
import com.firefly.transactional.saga.engine.StepInputs;
import com.firefly.transactional.saga.registry.SagaDefinition;
import com.firefly.transactional.saga.registry.SagaRegistry;
import com.firefly.transactional.saga.config.SagaCompositionProperties;
import com.firefly.transactional.saga.observability.SagaEvents;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Integration test for all composition enhancements working together.
 * <p>
 * Tests the complete enhanced composition system including:
 * - Enhanced builder with validation
 * - Template registry
 * - Metrics collection
 * - Health monitoring
 * - Visualization
 */
@ExtendWith(MockitoExtension.class)
class CompositionEnhancementsIntegrationTest {
    
    @Mock
    private SagaEngine sagaEngine;
    
    @Mock
    private SagaRegistry sagaRegistry;
    
    @Mock
    private SagaEvents sagaEvents;
    
    @Mock
    private SagaDefinition mockSagaDefinition;
    
    private SagaCompositor sagaCompositor;
    private CompositionTemplateRegistry templateRegistry;
    private CompositionVisualizationService visualizationService;
    private SagaCompositionProperties properties;
    
    @BeforeEach
    void setUp() {
        // Setup properties
        properties = new SagaCompositionProperties();
        properties.setStrictValidation(true);
        properties.setFailFast(false); // Allow collecting all validation issues
        
        // Setup components
        sagaCompositor = new SagaCompositor(sagaEngine, sagaRegistry, sagaEvents);
        templateRegistry = new CompositionTemplateRegistry(properties.getTemplates());
        visualizationService = new CompositionVisualizationService(sagaCompositor, properties.getDevTools());
        
        // Setup mock saga definitions (lenient to avoid unnecessary stubbing errors)
        lenient().when(sagaRegistry.getSaga(anyString())).thenReturn(mockSagaDefinition);
        lenient().when(sagaEngine.execute(any(SagaDefinition.class), any(StepInputs.class), any(SagaContext.class)))
                .thenReturn(Mono.just(createSuccessfulSagaResult()));
    }
    
    @Test
    void testEnhancedBuilderWithValidation() {
        // Test that enhanced builder provides helpful validation
        SagaCompositionBuilder builder = SagaCompositor.compose("test-composition");
        
        // Add a saga with invalid dependency
        builder.saga("payment-processing")
                .withId("payment")
                .dependsOn("non-existent-saga") // This should trigger validation
                .add();
        
        // Build should collect validation issues
        assertThrows(CompositionValidationException.class, () -> builder.build());
        
        // Check validation issues
        List<ValidationIssue> issues = builder.getValidationIssues();
        assertFalse(issues.isEmpty());
        assertTrue(builder.hasErrors());
        
        ValidationIssue issue = issues.get(0);
        assertEquals("MISSING_DEPENDENCY", issue.getCode());
        assertEquals(ValidationIssue.Severity.ERROR, issue.getSeverity());
        assertNotNull(issue.getSuggestion());
    }
    
    @Test
    void testTemplateRegistry() {
        // Test that template registry provides working templates
        assertNotNull(templateRegistry.getTemplate("order-processing"));
        assertNotNull(templateRegistry.getTemplate("financial-transaction"));
        assertNotNull(templateRegistry.getTemplate("data-pipeline"));
        
        // Test creating composition from template
        SagaCompositionBuilder builder = templateRegistry.fromTemplate("order-processing", "my-order");
        assertNotNull(builder);
        
        // The template should create a valid composition
        SagaComposition composition = builder.build();
        assertEquals("my-order", composition.name);
        assertFalse(composition.sagas.isEmpty());
        
        // Verify template structure
        assertTrue(composition.sagas.containsKey("payment"));
        assertTrue(composition.sagas.containsKey("inventory"));
        assertTrue(composition.sagas.containsKey("shipping"));
        assertTrue(composition.sagas.containsKey("notifications"));
        
        // Verify dependencies are set up correctly
        assertTrue(composition.sagas.get("inventory").dependencies.contains("payment"));
        assertTrue(composition.sagas.get("shipping").dependencies.contains("inventory"));
    }
    
    @Test
    void testCompositionExecution() {
        // Test that composition execution works with the common observability layer
        SagaComposition composition = SagaCompositor.compose("execution-test")
                .saga("test-saga")
                    .withId("test")
                    .add()
                .build();

        SagaContext context = new SagaContext("execution-test");

        // Execute composition - metrics will be handled by the common observability layer
        StepVerifier.create(sagaCompositor.execute(composition, context))
                .assertNext(result -> {
                    // Verify execution result
                    assertTrue(result.isSuccess());
                    assertEquals(1, result.getCompletedSagaCount());
                    assertEquals(0, result.getFailedSagaCount());
                    assertEquals(0, result.getSkippedSagaCount());
                    assertNotNull(result.getDuration());
                })
                .verifyComplete();
    }
    
    @Test
    void testVisualizationService() {
        // Test visualization generation
        SagaComposition composition = SagaCompositor.compose("viz-test")
                .saga("step1")
                    .withId("step1")
                    .add()
                .saga("step2")
                    .withId("step2")
                    .dependsOn("step1")
                    .withDataFrom("step1", "result")
                    .add()
                .saga("step3")
                    .withId("step3")
                    .dependsOn("step1")
                    .executeInParallelWith("step2")
                    .optional()
                    .timeout(5000)
                    .add()
                .build();
        
        // Test Mermaid diagram generation
        String mermaid = visualizationService.generateMermaidDiagram(composition);
        assertNotNull(mermaid);
        assertTrue(mermaid.contains("graph TD"));
        assertTrue(mermaid.contains("step1"));
        assertTrue(mermaid.contains("step2"));
        assertTrue(mermaid.contains("step3"));
        assertTrue(mermaid.contains("-->"));
        
        // Test DOT diagram generation
        String dot = visualizationService.generateDotDiagram(composition);
        assertNotNull(dot);
        assertTrue(dot.contains("digraph SagaComposition"));
        assertTrue(dot.contains("step1"));
        assertTrue(dot.contains("->"));
        
        // Test text tree generation
        String tree = visualizationService.generateTextTree(composition);
        assertNotNull(tree);
        assertTrue(tree.contains("Composition: viz-test"));
        assertTrue(tree.contains("Layer"));
        assertTrue(tree.contains("Dependencies:"));
        
        // Test execution stats
        String stats = visualizationService.generateExecutionStats(composition);
        assertNotNull(stats);
        assertTrue(stats.contains("Total Sagas: 3"));
        assertTrue(stats.contains("Optional Sagas: 1"));
        assertTrue(stats.contains("Sagas with Timeouts: 1"));
    }
    
    @Test
    void testCompleteWorkflow() {
        // Test complete workflow using template, validation, and visualization

        // 1. Create composition from template
        SagaCompositionBuilder builder = templateRegistry.fromTemplate("order-processing", "complete-test");

        // 2. Validate composition
        List<ValidationIssue> issues = builder.getValidationIssues();
        assertFalse(builder.hasErrors()); // Template should be valid

        // 3. Build composition
        SagaComposition composition = builder.build();

        // 4. Generate visualization
        String mermaid = visualizationService.generateMermaidDiagram(composition);
        assertNotNull(mermaid);

        // 5. Execute composition - metrics will be handled by the common observability layer
        SagaContext context = new SagaContext("complete-test");

        StepVerifier.create(sagaCompositor.execute(composition, context))
                .assertNext(result -> {
                    // 6. Verify all components worked together
                    assertTrue(result.isSuccess());
                    assertTrue(result.getCompletedSagaCount() > 0);
                    assertNotNull(result.getDuration());
                })
                .verifyComplete();
    }
    
    private SagaResult createSuccessfulSagaResult() {
        return SagaResult.from("test-saga", new SagaContext("test"), 
                              Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());
    }
}
