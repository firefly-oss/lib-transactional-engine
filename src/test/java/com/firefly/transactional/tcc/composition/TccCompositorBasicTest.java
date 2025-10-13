/*
 * Copyright 2024 Firefly Authors
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
import com.firefly.transactional.tcc.core.TccInputs;
import com.firefly.transactional.tcc.core.TccResult;
import com.firefly.transactional.tcc.engine.TccEngine;
import com.firefly.transactional.tcc.observability.TccEvents;
import com.firefly.transactional.tcc.registry.TccDefinition;
import com.firefly.transactional.tcc.registry.TccRegistry;
import com.firefly.transactional.shared.engine.CompensationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Basic tests for TCC Compositor functionality.
 */
@ExtendWith(MockitoExtension.class)
class TccCompositorBasicTest {

    @Mock
    private TccEngine tccEngine;
    
    @Mock
    private TccRegistry tccRegistry;
    
    @Mock
    private TccEvents tccEvents;
    
    private TccCompositor tccCompositor;
    
    @BeforeEach
    void setUp() {
        tccCompositor = new TccCompositor(tccEngine, tccRegistry, tccEvents);
    }
    
    @Test
    void testCreateCompositionBuilder() {
        // Test that we can create a composition builder
        TccCompositionBuilder builder = TccCompositor.compose("test-composition");
        
        assertThat(builder).isNotNull();
    }
    
    @Test
    void testBuildSimpleComposition() {
        // Test building a simple composition
        TccComposition composition = TccCompositor.compose("test-composition")
                .compensationPolicy(CompensationPolicy.STRICT_SEQUENTIAL)
                .tcc("payment")
                    .withId("payment-1")
                    .add()
                .build();
        
        assertThat(composition).isNotNull();
        assertThat(composition.name).isEqualTo("test-composition");
        assertThat(composition.compensationPolicy).isEqualTo(CompensationPolicy.STRICT_SEQUENTIAL);
        assertThat(composition.tccs).hasSize(1);
        assertThat(composition.tccs).containsKey("payment-1");
    }
    
    @Test
    void testBuildCompositionWithDependencies() {
        // Test building a composition with dependencies
        TccComposition composition = TccCompositor.compose("order-workflow")
                .compensationPolicy(CompensationPolicy.GROUPED_PARALLEL)
                .tcc("payment")
                    .withId("payment-1")
                    .add()
                .tcc("inventory")
                    .withId("inventory-1")
                    .dependsOn("payment-1")
                    .add()
                .tcc("shipping")
                    .withId("shipping-1")
                    .dependsOn("inventory-1")
                    .add()
                .build();
        
        assertThat(composition).isNotNull();
        assertThat(composition.name).isEqualTo("order-workflow");
        assertThat(composition.tccs).hasSize(3);
        
        // Check dependencies
        TccComposition.CompositionTcc inventory = composition.tccs.get("inventory-1");
        assertThat(inventory.dependencies).contains("payment-1");
        
        TccComposition.CompositionTcc shipping = composition.tccs.get("shipping-1");
        assertThat(shipping.dependencies).contains("inventory-1");
    }
    
    @Test
    void testValidateComposition() {
        // Mock TCC definitions
        TccDefinition paymentDef = createMockTccDefinition("payment");
        TccDefinition inventoryDef = createMockTccDefinition("inventory");
        
        when(tccRegistry.getTcc("payment")).thenReturn(paymentDef);
        when(tccRegistry.getTcc("inventory")).thenReturn(inventoryDef);
        
        TccComposition composition = TccCompositor.compose("test-composition")
                .tcc("payment")
                    .withId("payment-1")
                    .add()
                .tcc("inventory")
                    .withId("inventory-1")
                    .dependsOn("payment-1")
                    .add()
                .build();
        
        // Should not throw exception
        tccCompositor.validate(composition);
    }
    
    @Test
    void testExecuteSimpleComposition() {
        // Mock TCC definition and result
        TccDefinition paymentDef = createMockTccDefinition("payment");
        TccResult successResult = createMockSuccessResult("payment", "test-correlation");
        
        when(tccRegistry.getTcc("payment")).thenReturn(paymentDef);
        when(tccEngine.execute(anyString(), any(TccInputs.class), any(TccContext.class)))
                .thenReturn(Mono.just(successResult));
        
        TccComposition composition = TccCompositor.compose("test-composition")
                .tcc("payment")
                    .withId("payment-1")
                    .add()
                .build();
        
        TccContext rootContext = new TccContext("test-correlation");
        
        // Execute composition
        StepVerifier.create(tccCompositor.execute(composition, rootContext))
                .assertNext(result -> {
                    assertThat(result).isNotNull();
                    assertThat(result.getCompositionName()).isEqualTo("test-composition");
                    assertThat(result.isSuccess()).isTrue();
                    assertThat(result.getCompletedTccCount()).isEqualTo(1);
                })
                .verifyComplete();
    }
    
    private TccDefinition createMockTccDefinition(String name) {
        // Create a minimal TccDefinition for testing
        // Note: This might need adjustment based on actual TccDefinition constructor
        return new TccDefinition(name, null, null, 5000L, false, 3, 1000L);
    }
    
    private TccResult createMockSuccessResult(String tccName, String correlationId) {
        return TccResult.builder(correlationId)
                .tccName(tccName)
                .success(true)
                .build();
    }
}
