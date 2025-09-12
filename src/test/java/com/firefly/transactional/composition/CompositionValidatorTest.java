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

package com.firefly.transactional.composition;

import com.firefly.transactional.engine.CompensationPolicy;
import com.firefly.transactional.engine.StepInputs;
import com.firefly.transactional.registry.SagaBuilder;
import com.firefly.transactional.registry.SagaDefinition;
import com.firefly.transactional.registry.SagaRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Tests for CompositionValidator.
 */
class CompositionValidatorTest {

    @Mock
    private SagaRegistry sagaRegistry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        setupMockSagaRegistry();
    }

    private void setupMockSagaRegistry() {
        SagaDefinition paymentSaga = SagaBuilder.saga("payment-saga").build();
        SagaDefinition inventorySaga = SagaBuilder.saga("inventory-saga").build();
        SagaDefinition shippingSaga = SagaBuilder.saga("shipping-saga").build();

        when(sagaRegistry.getSaga("payment-saga")).thenReturn(paymentSaga);
        when(sagaRegistry.getSaga("inventory-saga")).thenReturn(inventorySaga);
        when(sagaRegistry.getSaga("shipping-saga")).thenReturn(shippingSaga);
        when(sagaRegistry.getSaga("unknown-saga")).thenReturn(null);
    }

    @Test
    void testValidComposition() {
        // Given: A valid composition
        SagaComposition composition = createValidComposition();

        // When/Then: Validation should pass without exception
        assertDoesNotThrow(() -> CompositionValidator.validate(composition, sagaRegistry));
    }

    @Test
    void testCompositionWithNullName() {
        // When/Then: Creating composition with null name should fail
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> new SagaComposition(null, CompensationPolicy.STRICT_SEQUENTIAL, Map.of(), java.util.List.of()));
        assertTrue(exception.getMessage().contains("name cannot be null"));
    }

    @Test
    void testCompositionWithEmptyName() {
        // Given: A composition with empty name
        SagaComposition composition = new SagaComposition(
                "", CompensationPolicy.STRICT_SEQUENTIAL, Map.of(), java.util.List.of());

        // When/Then: Validation should fail
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> CompositionValidator.validate(composition, sagaRegistry));
        assertTrue(exception.getMessage().contains("name cannot be null or empty"));
    }

    @Test
    void testCompositionWithNoSagas() {
        // Given: A composition with no sagas
        SagaComposition composition = new SagaComposition(
                "empty-composition", CompensationPolicy.STRICT_SEQUENTIAL, Map.of(), java.util.List.of());

        // When/Then: Validation should fail
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> CompositionValidator.validate(composition, sagaRegistry));
        assertTrue(exception.getMessage().contains("must contain at least one saga"));
    }

    @Test
    void testCompositionWithUnknownSaga() {
        // Given: A composition referencing an unknown saga
        SagaComposition.CompositionSaga unknownSaga = new SagaComposition.CompositionSaga(
                "unknown-saga", "unknown-id", Set.of(), Set.of(),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        SagaComposition composition = new SagaComposition(
                "invalid-composition", CompensationPolicy.STRICT_SEQUENTIAL,
                Map.of("unknown-id", unknownSaga), java.util.List.of("unknown-id"));

        // When/Then: Validation should fail
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> CompositionValidator.validate(composition, sagaRegistry));
        assertTrue(exception.getMessage().contains("Saga definition not found: unknown-saga"));
    }

    @Test
    void testCompositionWithCircularDependency() {
        // Given: A composition with circular dependencies
        SagaComposition.CompositionSaga sagaA = new SagaComposition.CompositionSaga(
                "payment-saga", "saga-a", Set.of("saga-b"), Set.of(),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        SagaComposition.CompositionSaga sagaB = new SagaComposition.CompositionSaga(
                "inventory-saga", "saga-b", Set.of("saga-a"), Set.of(),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        SagaComposition composition = new SagaComposition(
                "circular-composition", CompensationPolicy.STRICT_SEQUENTIAL,
                Map.of("saga-a", sagaA, "saga-b", sagaB), java.util.List.of("saga-a", "saga-b"));

        // When/Then: Validation should fail
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> CompositionValidator.validate(composition, sagaRegistry));
        assertTrue(exception.getMessage().contains("Circular dependency detected"));
    }

    @Test
    void testCompositionWithSelfDependency() {
        // Given: A saga that depends on itself
        SagaComposition.CompositionSaga selfDependentSaga = new SagaComposition.CompositionSaga(
                "payment-saga", "self-dependent", Set.of("self-dependent"), Set.of(),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        SagaComposition composition = new SagaComposition(
                "self-dependent-composition", CompensationPolicy.STRICT_SEQUENTIAL,
                Map.of("self-dependent", selfDependentSaga), java.util.List.of("self-dependent"));

        // When/Then: Validation should fail
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> CompositionValidator.validate(composition, sagaRegistry));
        assertTrue(exception.getMessage().contains("cannot depend on itself"));
    }

    @Test
    void testCompositionWithUnknownDependency() {
        // Given: A saga that depends on an unknown saga
        SagaComposition.CompositionSaga sagaWithUnknownDep = new SagaComposition.CompositionSaga(
                "payment-saga", "saga-with-unknown-dep", Set.of("unknown-dependency"), Set.of(),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        SagaComposition composition = new SagaComposition(
                "unknown-dep-composition", CompensationPolicy.STRICT_SEQUENTIAL,
                Map.of("saga-with-unknown-dep", sagaWithUnknownDep), 
                java.util.List.of("saga-with-unknown-dep"));

        // When/Then: Validation should fail
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> CompositionValidator.validate(composition, sagaRegistry));
        assertTrue(exception.getMessage().contains("depends on unknown saga: unknown-dependency"));
    }

    @Test
    void testCompositionWithConflictingParallelAndDependency() {
        // Given: Sagas that are both parallel and dependent
        SagaComposition.CompositionSaga sagaA = new SagaComposition.CompositionSaga(
                "payment-saga", "saga-a", Set.of("saga-b"), Set.of("saga-b"),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        SagaComposition.CompositionSaga sagaB = new SagaComposition.CompositionSaga(
                "inventory-saga", "saga-b", Set.of(), Set.of(),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        SagaComposition composition = new SagaComposition(
                "conflicting-composition", CompensationPolicy.STRICT_SEQUENTIAL,
                Map.of("saga-a", sagaA, "saga-b", sagaB), java.util.List.of("saga-b", "saga-a"));

        // When/Then: Validation should fail
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> CompositionValidator.validate(composition, sagaRegistry));
        assertTrue(exception.getMessage().contains("cannot be both dependent and parallel"));
    }

    @Test
    void testCompositionWithInvalidDataMapping() {
        // Given: A saga with data mapping to unknown source saga
        Map<String, SagaComposition.DataMapping> invalidMappings = Map.of(
                "data", new SagaComposition.DataMapping("unknown-source", "key", "data"));

        SagaComposition.CompositionSaga sagaWithInvalidMapping = new SagaComposition.CompositionSaga(
                "payment-saga", "saga-with-invalid-mapping", Set.of(), Set.of(),
                StepInputs.builder().build(), invalidMappings, ctx -> true, false, 0);

        SagaComposition composition = new SagaComposition(
                "invalid-mapping-composition", CompensationPolicy.STRICT_SEQUENTIAL,
                Map.of("saga-with-invalid-mapping", sagaWithInvalidMapping), 
                java.util.List.of("saga-with-invalid-mapping"));

        // When/Then: Validation should fail
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> CompositionValidator.validate(composition, sagaRegistry));
        assertTrue(exception.getMessage().contains("references unknown source saga: unknown-source"));
    }

    @Test
    void testCompositionWithDataMappingToNonDependency() {
        // Given: A saga with data mapping to a saga that's not a dependency
        Map<String, SagaComposition.DataMapping> invalidMappings = Map.of(
                "data", new SagaComposition.DataMapping("shipping", "key", "data"));

        SagaComposition.CompositionSaga paymentSaga = new SagaComposition.CompositionSaga(
                "payment-saga", "payment", Set.of(), Set.of(),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        SagaComposition.CompositionSaga inventorySaga = new SagaComposition.CompositionSaga(
                "inventory-saga", "inventory", Set.of("payment"), Set.of(),
                StepInputs.builder().build(), invalidMappings, ctx -> true, false, 0);

        SagaComposition.CompositionSaga shippingSaga = new SagaComposition.CompositionSaga(
                "shipping-saga", "shipping", Set.of("inventory"), Set.of(),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        SagaComposition composition = new SagaComposition(
                "invalid-mapping-composition", CompensationPolicy.STRICT_SEQUENTIAL,
                Map.of("payment", paymentSaga, "inventory", inventorySaga, "shipping", shippingSaga),
                java.util.List.of("payment", "inventory", "shipping"));

        // When/Then: Validation should fail because inventory tries to map from shipping, but shipping is not a dependency
        try {
            CompositionValidator.validate(composition, sagaRegistry);
            fail("Expected validation to fail");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("which is not a dependency"),
                      "Expected error message about dependency, but got: " + e.getMessage());
        }
    }

    private SagaComposition createValidComposition() {
        SagaComposition.CompositionSaga paymentSaga = new SagaComposition.CompositionSaga(
                "payment-saga", "payment", Set.of(), Set.of(),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        SagaComposition.CompositionSaga inventorySaga = new SagaComposition.CompositionSaga(
                "inventory-saga", "inventory", Set.of("payment"), Set.of(),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        SagaComposition.CompositionSaga shippingSaga = new SagaComposition.CompositionSaga(
                "shipping-saga", "shipping", Set.of("inventory"), Set.of(),
                StepInputs.builder().build(), Map.of(), ctx -> true, false, 0);

        return new SagaComposition(
                "valid-composition", CompensationPolicy.STRICT_SEQUENTIAL,
                Map.of("payment", paymentSaga, "inventory", inventorySaga, "shipping", shippingSaga),
                java.util.List.of("payment", "inventory", "shipping"));
    }
}
