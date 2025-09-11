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


package com.firefly.transactionalengine.engine;

import com.firefly.transactionalengine.core.SagaContext;
import com.firefly.transactionalengine.events.StepEventPublisher;
import com.firefly.transactionalengine.observability.SagaEvents;
import com.firefly.transactionalengine.registry.SagaDefinition;
import com.firefly.transactionalengine.registry.StepDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for SagaExecutionCommand to ensure proper saga execution flow.
 */
class SagaExecutionCommandTest {

    @Mock
    private SagaEvents sagaEvents;
    
    @Mock
    private StepEventPublisher stepEventPublisher;
    
    @Mock
    private SagaCompensator compensator;
    
    @Mock
    private StepInvoker stepInvoker;

    private SagaDefinition testSaga;
    private StepInputs testInputs;
    private SagaContext testContext;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Set up test saga with simple step
        testSaga = new SagaDefinition("TestSaga", null, null, 0);
        
        // Get method reference for mock step
        Method mockMethod;
        try {
            mockMethod = this.getClass().getDeclaredMethod("mockStepMethod", String.class, SagaContext.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Mock method not found", e);
        }
        
        StepDefinition step = new StepDefinition(
            "testStep", 
            "", 
            java.util.List.of(), 
            0, 
            Duration.ZERO, 
            Duration.ZERO, 
            "", 
            false, 
            0.0, 
            false, 
            mockMethod
        );
        testSaga.steps.put("testStep", step);
        
        testInputs = StepInputs.builder()
            .forStepId("testStep", "test-input")
            .build();
            
        testContext = new SagaContext();
        
        // Mock step event publisher to return empty Mono
        when(stepEventPublisher.publish(any())).thenReturn(Mono.empty());
        
        // Mock compensator to return empty Mono
        when(compensator.compensate(anyString(), any(), any(), any(), any())).thenReturn(Mono.empty());
        
        // Mock step invoker to return successful execution
        when(stepInvoker.attemptCall(any(), any(), any(), any(), any(), anyLong(), anyInt(), anyLong(), anyBoolean(), anyDouble(), anyString()))
            .thenReturn(Mono.just("step-result"));
        when(stepInvoker.attemptCallHandler(any(), any(), any(), anyLong(), anyInt(), anyLong(), anyBoolean(), anyDouble(), anyString()))
            .thenReturn(Mono.just("step-result"));
    }

    @Test
    void shouldExecuteSimpleSagaSuccessfully() {
        // Given
        SagaExecutionCommand command = new SagaExecutionCommand(
            testSaga, testInputs, testContext, sagaEvents, stepEventPublisher, compensator, stepInvoker, java.util.Map.of()
        );

        // When & Then
        StepVerifier.create(command.execute())
            .assertNext(result -> {
                assertThat(result).isNotNull();
                assertThat(result.sagaName()).isEqualTo("TestSaga");
                assertThat(result.isSuccess()).isTrue();
                assertThat(result.steps()).containsKey("testStep");
            })
            .verifyComplete();

        // Verify saga lifecycle events were called
        verify(sagaEvents).onStart("TestSaga", testContext.correlationId());
        verify(sagaEvents).onStart("TestSaga", testContext.correlationId(), testContext);
        verify(sagaEvents).onCompleted("TestSaga", testContext.correlationId(), true);
        verify(sagaEvents).onStepStarted("TestSaga", testContext.correlationId(), "testStep");
        verify(sagaEvents).onStepSuccess(eq("TestSaga"), eq(testContext.correlationId()), eq("testStep"), anyInt(), anyLong());
    }

    @Test
    void shouldHandleStepFailureAndCompensate() {
        // Given
        when(stepInvoker.attemptCall(any(), any(), any(), any(), any(), anyLong(), anyInt(), anyLong(), anyBoolean(), anyDouble(), anyString()))
            .thenReturn(Mono.error(new RuntimeException("Step failed")));
        when(stepInvoker.attemptCallHandler(any(), any(), any(), anyLong(), anyInt(), anyLong(), anyBoolean(), anyDouble(), anyString()))
            .thenReturn(Mono.error(new RuntimeException("Step failed")));

        SagaExecutionCommand command = new SagaExecutionCommand(
            testSaga, testInputs, testContext, sagaEvents, stepEventPublisher, compensator, stepInvoker, java.util.Map.of()
        );

        // When & Then
        StepVerifier.create(command.execute())
            .assertNext(result -> {
                assertThat(result).isNotNull();
                assertThat(result.sagaName()).isEqualTo("TestSaga");
                assertThat(result.isSuccess()).isFalse();
                // Add debug output
                System.out.println("DEBUG: Result success: " + result.isSuccess());
                System.out.println("DEBUG: Result steps: " + result.steps().keySet());
            })
            .verifyComplete();

        // Verify compensation was called
        verify(compensator).compensate(eq("TestSaga"), eq(testSaga), any(), any(), eq(testContext));
        
        // Verify failure events were called
        verify(sagaEvents).onCompleted("TestSaga", testContext.correlationId(), false);
        verify(sagaEvents).onStepFailed(eq("TestSaga"), eq(testContext.correlationId()), eq("testStep"), any(RuntimeException.class), anyInt(), anyLong());
    }

    @Test
    void shouldHandleExpandEachInputWithoutExpansion() {
        // Given - SagaExecutionCommand doesn't handle expansion, that's done by SagaEngine
        // This test verifies that ExpandEach inputs are handled gracefully without expansion
        java.util.List<String> items = java.util.List.of("item1", "item2", "item3");
        ExpandEach expandEach = ExpandEach.of(items);
        
        StepInputs expandingInputs = StepInputs.builder()
            .forStepId("testStep", expandEach)
            .build();

        SagaExecutionCommand command = new SagaExecutionCommand(
            testSaga, expandingInputs, testContext, sagaEvents, stepEventPublisher, compensator, stepInvoker, java.util.Map.of()
        );

        // When & Then - should execute normally without expansion
        StepVerifier.create(command.execute())
            .assertNext(result -> {
                assertThat(result).isNotNull();
                assertThat(result.sagaName()).isEqualTo("TestSaga");
                assertThat(result.isSuccess()).isTrue();
                // Should have only the original step (no expansion at this level)
                assertThat(result.steps()).hasSize(1);
                assertThat(result.steps()).containsKey("testStep");
            })
            .verifyComplete();

        // Verify the single step was executed
        verify(stepInvoker, times(1)).attemptCall(any(), any(), any(), any(), any(), anyLong(), anyInt(), anyLong(), anyBoolean(), anyDouble(), anyString());
    }

    @Test
    void shouldHandleEmptyStepInputs() {
        // Given
        SagaExecutionCommand command = new SagaExecutionCommand(
            testSaga, null, testContext, sagaEvents, stepEventPublisher, compensator, stepInvoker, java.util.Map.of()
        );

        // When & Then
        StepVerifier.create(command.execute())
            .assertNext(result -> {
                assertThat(result).isNotNull();
                assertThat(result.sagaName()).isEqualTo("TestSaga");
                assertThat(result.isSuccess()).isTrue();
            })
            .verifyComplete();
    }

    @Test
    void shouldHandleContextPropagation() {
        // Given
        testContext.putHeader("X-Test-Header", "test-value");
        testContext.putVariable("testVar", "test-variable");

        SagaExecutionCommand command = new SagaExecutionCommand(
            testSaga, testInputs, testContext, sagaEvents, stepEventPublisher, compensator, stepInvoker, java.util.Map.of()
        );

        // When
        StepVerifier.create(command.execute())
            .assertNext(result -> {
                assertThat(result).isNotNull();
                assertThat(result.isSuccess()).isTrue();
            })
            .verifyComplete();

        // Then
        assertThat(testContext.headers()).containsEntry("X-Test-Header", "test-value");
        assertThat(testContext.variables()).containsEntry("testVar", "test-variable");
        assertThat(testContext.sagaName()).isEqualTo("TestSaga");
    }
    
    // Mock method for testing step execution
    public Mono<String> mockStepMethod(String input, SagaContext ctx) {
        return Mono.just("mock-result");
    }
}