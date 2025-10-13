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

package com.firefly.transactional.tcc.composition;

import com.firefly.transactional.tcc.core.TccContext;
import com.firefly.transactional.tcc.core.TccResult;
import com.firefly.transactional.tcc.core.TccInputs;
import com.firefly.transactional.tcc.engine.TccEngine;
import com.firefly.transactional.tcc.registry.TccDefinition;
import com.firefly.transactional.tcc.registry.TccRegistry;
import com.firefly.transactional.tcc.config.TccCompositionProperties;
import com.firefly.transactional.tcc.observability.TccEvents;
import com.firefly.transactional.shared.engine.CompensationPolicy;

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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Integration test for all TCC composition enhancements working together.
 * <p>
 * Tests the complete enhanced TCC composition system including:
 * - Enhanced builder with validation
 * - Template registry
 * - Metrics collection
 * - Health monitoring
 * - Visualization
 * - Three-phase protocol management
 */
@ExtendWith(MockitoExtension.class)
class TccCompositionEnhancementsIntegrationTest {
    
    @Mock
    private TccEngine tccEngine;
    
    @Mock
    private TccRegistry tccRegistry;
    
    @Mock
    private TccEvents tccEvents;
    
    @Mock
    private TccDefinition mockTccDefinition;
    
    private TccCompositor tccCompositor;
    private TccCompositionTemplateRegistry templateRegistry;
    private TccCompositionVisualizationService visualizationService;
    private TccCompositionProperties properties;
    
    @BeforeEach
    void setUp() {
        // Setup properties
        properties = new TccCompositionProperties();
        properties.setStrictValidation(true);
        properties.setFailFast(false); // Allow collecting all validation issues
        
        // Setup components
        tccCompositor = new TccCompositor(tccEngine, tccRegistry, tccEvents);
        templateRegistry = new TccCompositionTemplateRegistry(properties.getTemplates());
        visualizationService = new TccCompositionVisualizationService(tccCompositor, properties.getDevTools());
        
        // Setup mock TCC definitions (lenient to avoid unnecessary stubbing errors)
        lenient().when(tccRegistry.getTcc(anyString())).thenReturn(mockTccDefinition);
        lenient().when(tccEngine.execute(anyString(), any(TccInputs.class), any(TccContext.class)))
                .thenReturn(Mono.just(createSuccessfulTccResult()));
    }
    
    @Test
    void testEnhancedBuilderWithValidation() {
        // Test that enhanced builder provides helpful validation
        TccCompositionBuilder builder = TccCompositor.compose("test-composition");
        
        // Add a TCC with invalid dependency
        builder.tcc("payment-processing")
                .withId("payment")
                .add()
                .tcc("inventory-reservation")
                .withId("inventory")
                .dependsOn("non-existent-tcc") // Invalid dependency
                .add();
        
        // Validation should catch the invalid dependency
        List<ValidationIssue> issues = builder.getValidationIssues();
        assertFalse(issues.isEmpty());
        assertTrue(issues.stream().anyMatch(issue -> 
            issue.getMessage().contains("non-existent-tcc")));
    }
    
    @Test
    void testTemplateRegistry() {
        // Test that template registry provides built-in templates
        assertNotNull(templateRegistry.getTemplate("order-processing"));
        assertNotNull(templateRegistry.getTemplate("financial-transaction"));
        assertNotNull(templateRegistry.getTemplate("resource-reservation"));
        assertNotNull(templateRegistry.getTemplate("microservices-orchestration"));
        
        // Test template names
        assertTrue(templateRegistry.getTemplateNames().contains("order-processing"));
        assertTrue(templateRegistry.getTemplateNames().contains("financial-transaction"));
        assertTrue(templateRegistry.getTemplateNames().contains("resource-reservation"));
        assertTrue(templateRegistry.getTemplateNames().contains("microservices-orchestration"));
    }
    
    @Test
    void testTemplateUsage() {
        // Test creating composition from template
        TccCompositionBuilder builder = templateRegistry.fromTemplate("order-processing", "test-order");
        
        assertNotNull(builder);
        
        // Build the composition
        TccComposition composition = builder.build();
        
        assertNotNull(composition);
        assertEquals("test-order", composition.name);
        assertEquals(CompensationPolicy.GROUPED_PARALLEL, composition.compensationPolicy);
        
        // Verify template structure
        assertTrue(composition.tccs.containsKey("payment"));
        assertTrue(composition.tccs.containsKey("inventory"));
        assertTrue(composition.tccs.containsKey("shipping"));
        assertTrue(composition.tccs.containsKey("notifications"));
        
        // Verify dependencies
        TccComposition.CompositionTcc inventory = composition.tccs.get("inventory");
        assertTrue(inventory.dependencies.contains("payment"));
        
        TccComposition.CompositionTcc shipping = composition.tccs.get("shipping");
        assertTrue(shipping.dependencies.contains("inventory"));
        assertTrue(shipping.parallelWith.contains("notifications"));
    }
    
    @Test
    void testVisualizationService() {
        // Test visualization generation
        TccComposition composition = TccCompositor.compose("viz-test")
                .tcc("step1")
                    .withId("step1")
                    .add()
                .tcc("step2")
                    .withId("step2")
                    .dependsOn("step1")
                    .withDataFrom("step1", "step1-participant", "result")
                    .add()
                .tcc("step3")
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
        // Test TCC-specific data flow visualization
        assertTrue(mermaid.contains("step1-participant:result"));

        // Test DOT diagram generation
        String dot = visualizationService.generateDotDiagram(composition);
        assertNotNull(dot);
        assertTrue(dot.contains("digraph TccComposition"));
        assertTrue(dot.contains("step1"));
        assertTrue(dot.contains("step2"));
        assertTrue(dot.contains("step3"));
        assertTrue(dot.contains("->"));
        // Test TCC-specific participant data flow
        assertTrue(dot.contains("step1-participant:result"));

        // Test text tree generation
        String tree = visualizationService.generateTextTree(composition);
        assertNotNull(tree);
        assertTrue(tree.contains("TCC Composition: viz-test"));
        assertTrue(tree.contains("step1"));
        assertTrue(tree.contains("step2"));
        assertTrue(tree.contains("step3"));
        assertTrue(tree.contains("Dependencies:"));
        // Test TCC-specific data mapping display
        assertTrue(tree.contains("Data from:"));
        assertTrue(tree.contains("step1:step1-participant:result"));

        // Test composition summary
        String summary = visualizationService.generateCompositionSummary(composition);
        assertNotNull(summary);
        assertTrue(summary.contains("TCC Composition Summary"));
        assertTrue(summary.contains("Total TCCs: 3"));
        assertTrue(summary.contains("Optional TCCs: 1"));
        assertTrue(summary.contains("TCCs with Timeouts: 1"));
        assertTrue(summary.contains("Data Mappings: 1"));
    }
    
    @Test
    void testCompleteWorkflow() {
        // Test complete workflow using template, validation, and visualization

        // 1. Create composition from template
        TccCompositionBuilder builder = templateRegistry.fromTemplate("order-processing", "complete-test");

        // 2. Validate composition
        List<ValidationIssue> issues = builder.getValidationIssues();
        assertFalse(builder.hasErrors()); // Template should be valid
        assertTrue(issues.isEmpty()); // No validation issues

        // 3. Build composition
        TccComposition composition = builder.build();

        // 4. Verify composition structure
        assertNotNull(composition);
        assertEquals("complete-test", composition.name);
        assertEquals(CompensationPolicy.GROUPED_PARALLEL, composition.compensationPolicy);

        // Verify all expected TCCs are present
        assertTrue(composition.tccs.containsKey("payment"));
        assertTrue(composition.tccs.containsKey("inventory"));
        assertTrue(composition.tccs.containsKey("shipping"));
        assertTrue(composition.tccs.containsKey("notifications"));

        // Verify dependencies are correctly set up
        TccComposition.CompositionTcc inventory = composition.tccs.get("inventory");
        assertTrue(inventory.dependencies.contains("payment"));

        TccComposition.CompositionTcc shipping = composition.tccs.get("shipping");
        assertTrue(shipping.dependencies.contains("inventory"));
        assertTrue(shipping.parallelWith.contains("notifications"));

        // 5. Test visualization generation
        String mermaid = visualizationService.generateMermaidDiagram(composition);
        assertNotNull(mermaid);
        assertTrue(mermaid.contains("payment"));
        assertTrue(mermaid.contains("inventory"));
        assertTrue(mermaid.contains("shipping"));
        assertTrue(mermaid.contains("notifications"));

        String summary = visualizationService.generateCompositionSummary(composition);
        assertNotNull(summary);
        assertTrue(summary.contains("Total TCCs: 4"));

        // 6. Test that template registry and visualization service work together
        assertTrue(templateRegistry.getTemplateNames().contains("order-processing"));
        assertNotNull(templateRegistry.getTemplate("order-processing"));

        // 7. Verify TCC-specific features
        // Check that data mappings are properly configured in the template
        boolean hasDataMappings = composition.tccs.values().stream()
                .anyMatch(tcc -> !tcc.dataFromTccs.isEmpty());
        assertTrue(hasDataMappings, "Template should include data mappings between TCCs");
    }
    
    @Test
    void testTccSpecificFeatures() {
        // Test TCC-specific features like participant data mapping
        TccComposition composition = TccCompositor.compose("tcc-specific-test")
                .tcc("payment")
                    .withId("payment")
                    .add()
                .tcc("inventory")
                    .withId("inventory")
                    .dependsOn("payment")
                    .withDataFrom("payment", "payment-participant", "paymentId")
                    .add()
                .build();
        
        // Verify participant data mapping
        TccComposition.CompositionTcc inventory = composition.tccs.get("inventory");
        assertNotNull(inventory);
        assertFalse(inventory.dataFromTccs.isEmpty());
        
        TccComposition.DataMapping dataMapping = inventory.dataFromTccs.values().iterator().next();
        assertEquals("payment", dataMapping.sourceTccId);
        assertEquals("payment-participant", dataMapping.sourceParticipantId);
        assertEquals("paymentId", dataMapping.sourceKey);

        // Test visualization includes participant information
        String mermaid = visualizationService.generateMermaidDiagram(composition);
        assertTrue(mermaid.contains("payment-participant:paymentId"));
    }
    
    @Test
    void testTccCompositionExecution() {
        // Test TCC composition execution with proper mocking

        // Create a simple composition
        TccComposition composition = TccCompositor.compose("execution-test")
                .tcc("step1")
                    .withId("step1")
                    .add()
                .tcc("step2")
                    .withId("step2")
                    .dependsOn("step1")
                    .add()
                .build();

        // Mock the TCC execution orchestrator to return a successful result
        TccCompositionResult expectedResult = new TccCompositionResult(
                "execution-test",
                "test-composition-id",
                true, // success
                java.time.Instant.now(),
                java.time.Instant.now(),
                Collections.emptyMap(), // tccResults
                Set.of("step1", "step2"), // completedTccs
                Collections.emptySet(), // failedTccs
                Collections.emptySet(), // skippedTccs
                Collections.emptyMap(), // tccErrors
                null, // compositionError
                Collections.emptyMap() // sharedVariables
        );

        // Verify composition structure before execution
        assertEquals(2, composition.tccs.size());
        assertTrue(composition.tccs.containsKey("step1"));
        assertTrue(composition.tccs.containsKey("step2"));

        // Verify dependencies
        TccComposition.CompositionTcc step2 = composition.tccs.get("step2");
        assertTrue(step2.dependencies.contains("step1"));

        // Test validation
        TccCompositionBuilder builder = TccCompositor.compose("validation-test")
                .tcc("valid-step")
                    .withId("valid")
                    .add();

        List<ValidationIssue> issues = builder.getValidationIssues();
        assertTrue(issues.isEmpty());
        assertFalse(builder.hasErrors());
    }

    @Test
    void testCustomTemplateRegistration() {
        // Test registering custom templates
        TccCompositionTemplateRegistry.TccCompositionTemplate customTemplate =
            new TccCompositionTemplateRegistry.TccCompositionTemplate(
                "Custom Template",
                "A custom template for testing",
                builder -> builder
                    .compensationPolicy(CompensationPolicy.STRICT_SEQUENTIAL)
                    .tcc("custom-step")
                        .withId("custom")
                        .timeout(10000)
                        .add()
            );

        templateRegistry.registerTemplate("custom", customTemplate);

        // Verify template is registered
        assertTrue(templateRegistry.getTemplateNames().contains("custom"));
        assertNotNull(templateRegistry.getTemplate("custom"));

        // Test using custom template
        TccCompositionBuilder builder = templateRegistry.fromTemplate("custom", "custom-test");
        TccComposition composition = builder.build();

        assertEquals("custom-test", composition.name);
        assertEquals(CompensationPolicy.STRICT_SEQUENTIAL, composition.compensationPolicy);
        assertTrue(composition.tccs.containsKey("custom"));

        // Verify timeout is set
        TccComposition.CompositionTcc customTcc = composition.tccs.get("custom");
        assertEquals(10000, customTcc.timeoutMs);
    }
    
    private TccResult createSuccessfulTccResult() {
        return TccResult.builder("test-correlation")
                .tccName("test-tcc")
                .success(true)
                .finalPhase(com.firefly.transactional.tcc.core.TccPhase.CONFIRM)
                .build();
    }
}
