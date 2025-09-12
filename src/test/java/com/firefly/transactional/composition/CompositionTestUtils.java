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

import com.firefly.transactional.core.SagaContext;
import com.firefly.transactional.registry.SagaDefinition;
import com.firefly.transactional.core.SagaResult;
import com.firefly.transactional.engine.SagaEngine;
import com.firefly.transactional.engine.StepInputs;
import com.firefly.transactional.observability.SagaEvents;
import com.firefly.transactional.registry.SagaRegistry;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Utility class for testing saga compositions.
 * <p>
 * Provides helper methods, mock builders, and assertion utilities
 * specifically designed for composition testing.
 */
public class CompositionTestUtils {
    
    /**
     * Builder for creating mock saga environments for testing.
     */
    public static class MockSagaEnvironmentBuilder {
        private final Map<String, SagaDefinition> sagaDefinitions = new HashMap<>();
        private final Map<String, Function<StepInputs, Mono<SagaResult>>> sagaBehaviors = new HashMap<>();
        private final List<String> failingSagas = new ArrayList<>();
        private final Map<String, Duration> sagaDelays = new HashMap<>();
        
        public MockSagaEnvironmentBuilder withSaga(String sagaName) {
            SagaDefinition mockDefinition = new SagaDefinition(sagaName, null, null, 0);
            sagaDefinitions.put(sagaName, mockDefinition);
            return this;
        }
        
        public MockSagaEnvironmentBuilder withSagaBehavior(String sagaName, 
                Function<StepInputs, Mono<SagaResult>> behavior) {
            sagaBehaviors.put(sagaName, behavior);
            return this;
        }
        
        public MockSagaEnvironmentBuilder withFailingSaga(String sagaName) {
            failingSagas.add(sagaName);
            return this;
        }
        
        public MockSagaEnvironmentBuilder withSagaDelay(String sagaName, Duration delay) {
            sagaDelays.put(sagaName, delay);
            return this;
        }
        
        public MockSagaEnvironment build() {
            SagaEngine mockEngine = Mockito.mock(SagaEngine.class);
            SagaRegistry mockRegistry = Mockito.mock(SagaRegistry.class);
            SagaEvents mockEvents = Mockito.mock(SagaEvents.class);
            
            // Setup registry
            for (Map.Entry<String, SagaDefinition> entry : sagaDefinitions.entrySet()) {
                when(mockRegistry.getSaga(entry.getKey())).thenReturn(entry.getValue());
            }
            
            // Setup engine behavior
            when(mockEngine.execute(any(SagaDefinition.class), any(StepInputs.class), any(SagaContext.class)))
                    .thenAnswer(invocation -> {
                        SagaDefinition definition = invocation.getArgument(0);
                        StepInputs inputs = invocation.getArgument(1);
                        SagaContext context = invocation.getArgument(2);
                        
                        String sagaName = definition.name;
                        
                        // Check if saga should fail
                        if (failingSagas.contains(sagaName)) {
                            return Mono.error(new RuntimeException("Saga " + sagaName + " failed"));
                        }
                        
                        // Apply custom behavior if defined
                        if (sagaBehaviors.containsKey(sagaName)) {
                            Mono<SagaResult> result = sagaBehaviors.get(sagaName).apply(inputs);
                            
                            // Apply delay if configured
                            if (sagaDelays.containsKey(sagaName)) {
                                result = result.delayElement(sagaDelays.get(sagaName));
                            }
                            
                            return result;
                        }
                        
                        // Default successful behavior
                        SagaResult result = createSuccessfulSagaResult(sagaName, context);
                        Mono<SagaResult> resultMono = Mono.just(result);
                        
                        // Apply delay if configured
                        if (sagaDelays.containsKey(sagaName)) {
                            resultMono = resultMono.delayElement(sagaDelays.get(sagaName));
                        }
                        
                        return resultMono;
                    });
            
            return new MockSagaEnvironment(mockEngine, mockRegistry, mockEvents);
        }
    }
    
    /**
     * Mock saga environment for testing.
     */
    public static class MockSagaEnvironment {
        public final SagaEngine sagaEngine;
        public final SagaRegistry sagaRegistry;
        public final SagaEvents sagaEvents;
        
        public MockSagaEnvironment(SagaEngine sagaEngine, SagaRegistry sagaRegistry, SagaEvents sagaEvents) {
            this.sagaEngine = sagaEngine;
            this.sagaRegistry = sagaRegistry;
            this.sagaEvents = sagaEvents;
        }
        
        public SagaCompositor createCompositor() {
            return new SagaCompositor(sagaEngine, sagaRegistry, sagaEvents);
        }
    }
    
    /**
     * Creates a mock saga environment builder.
     */
    public static MockSagaEnvironmentBuilder mockEnvironment() {
        return new MockSagaEnvironmentBuilder();
    }
    
    /**
     * Creates a successful saga result for testing.
     */
    public static SagaResult createSuccessfulSagaResult(String sagaName, SagaContext context) {
        return SagaResult.from(sagaName, context, 
                              Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());
    }
    
    /**
     * Creates a successful saga result with custom data.
     */
    public static SagaResult createSuccessfulSagaResult(String sagaName, SagaContext context,
                                                       Map<String, Object> stepResults) {
        return SagaResult.from(sagaName, context,
                              Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());
    }
    
    /**
     * Assertion helper for composition results.
     */
    public static class CompositionResultAssertions {
        private final SagaCompositionResult result;
        
        public CompositionResultAssertions(SagaCompositionResult result) {
            this.result = result;
        }
        
        public CompositionResultAssertions isSuccessful() {
            assertTrue(result.isSuccess(), "Expected composition to be successful");
            return this;
        }
        
        public CompositionResultAssertions isFailed() {
            assertFalse(result.isSuccess(), "Expected composition to be failed");
            return this;
        }
        
        public CompositionResultAssertions hasCompletedSagas(int count) {
            assertEquals(count, result.getCompletedSagaCount(), 
                        "Expected " + count + " completed sagas");
            return this;
        }
        
        public CompositionResultAssertions hasFailedSagas(int count) {
            assertEquals(count, result.getFailedSagaCount(), 
                        "Expected " + count + " failed sagas");
            return this;
        }
        
        public CompositionResultAssertions hasSkippedSagas(int count) {
            assertEquals(count, result.getSkippedSagaCount(), 
                        "Expected " + count + " skipped sagas");
            return this;
        }
        
        public CompositionResultAssertions completedWithin(Duration maxDuration) {
            assertTrue(result.getDuration().compareTo(maxDuration) <= 0,
                      "Expected composition to complete within " + maxDuration + 
                      " but took " + result.getDuration());
            return this;
        }
        
        public CompositionResultAssertions sagaCompleted(String sagaId) {
            assertTrue(result.isSagaCompleted(sagaId), 
                      "Expected saga '" + sagaId + "' to be completed");
            return this;
        }
        
        public CompositionResultAssertions sagaFailed(String sagaId) {
            assertTrue(result.isSagaFailed(sagaId), 
                      "Expected saga '" + sagaId + "' to be failed");
            return this;
        }
        
        public CompositionResultAssertions sagaSkipped(String sagaId) {
            assertTrue(result.isSagaSkipped(sagaId), 
                      "Expected saga '" + sagaId + "' to be skipped");
            return this;
        }
        
        public CompositionResultAssertions hasSharedVariable(String key, Object expectedValue) {
            Object actualValue = result.getSharedVariables().get(key);
            assertEquals(expectedValue, actualValue, 
                        "Expected shared variable '" + key + "' to have value " + expectedValue);
            return this;
        }
    }
    
    /**
     * Creates assertion helper for composition results.
     */
    public static CompositionResultAssertions assertThat(SagaCompositionResult result) {
        return new CompositionResultAssertions(result);
    }
    
    /**
     * Executes a composition and provides assertion helper.
     */
    public static void executeAndAssert(SagaCompositor compositor, SagaComposition composition, 
                                       SagaContext context, Consumer<CompositionResultAssertions> assertions) {
        StepVerifier.create(compositor.execute(composition, context))
                .assertNext(result -> assertions.accept(assertThat(result)))
                .verifyComplete();
    }
    
    /**
     * Executes a composition with timeout and provides assertion helper.
     */
    public static void executeAndAssert(SagaCompositor compositor, SagaComposition composition, 
                                       SagaContext context, Duration timeout,
                                       Consumer<CompositionResultAssertions> assertions) {
        StepVerifier.create(compositor.execute(composition, context))
                .assertNext(result -> assertions.accept(assertThat(result)))
                .verifyComplete();
    }
    
    /**
     * Creates a simple test composition for basic testing.
     */
    public static SagaComposition createSimpleTestComposition(String name) {
        return SagaCompositor.compose(name)
                .saga("step1")
                    .withId("step1")
                    .add()
                .saga("step2")
                    .withId("step2")
                    .dependsOn("step1")
                    .add()
                .build();
    }
    
    /**
     * Creates a complex test composition with various patterns.
     */
    public static SagaComposition createComplexTestComposition(String name) {
        return SagaCompositor.compose(name)
                .saga("init")
                    .withId("init")
                    .add()
                .saga("process-a")
                    .withId("processA")
                    .dependsOn("init")
                    .withDataFrom("init", "initData")
                    .add()
                .saga("process-b")
                    .withId("processB")
                    .dependsOn("init")
                    .executeInParallelWith("processA")
                    .withDataFrom("init", "initData")
                    .add()
                .saga("optional-step")
                    .withId("optional")
                    .dependsOn("processA")
                    .optional()
                    .timeout(5000)
                    .add()
                .saga("finalize")
                    .withId("finalize")
                    .dependsOn("processA")
                    .dependsOn("processB")
                    .withDataFrom("processA", "resultA")
                    .withDataFrom("processB", "resultB")
                    .add()
                .build();
    }
    
    /**
     * Validates that a composition has the expected structure.
     */
    public static void validateCompositionStructure(SagaComposition composition, 
                                                   String expectedName, int expectedSagaCount) {
        assertEquals(expectedName, composition.name);
        assertEquals(expectedSagaCount, composition.sagas.size());
        assertNotNull(composition.executionOrder);
        assertFalse(composition.executionOrder.isEmpty());
    }
}
