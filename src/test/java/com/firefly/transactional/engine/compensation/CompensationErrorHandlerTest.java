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

package com.firefly.transactional.engine.compensation;

import com.firefly.transactional.core.SagaContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class CompensationErrorHandlerTest {

    private SagaContext mockContext;
    private String stepId;
    private RuntimeException testError;
    private IOException networkError;

    @BeforeEach
    void setUp() {
        mockContext = new SagaContext();
        stepId = "test-step";
        testError = new RuntimeException("Test error");
        networkError = new IOException("Network error");
    }

    @Test
    void testLogAndContinueErrorHandler() {
        LogAndContinueErrorHandler handler = new LogAndContinueErrorHandler();

        StepVerifier.create(handler.handleError(stepId, testError, mockContext, 1))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.CONTINUE)
                .verifyComplete();

        assertThat(handler.canHandle(testError, stepId, mockContext)).isTrue();
        assertThat(handler.getStrategyName()).isEqualTo("LogAndContinue");
    }

    @Test
    void testFailFastErrorHandler() {
        FailFastErrorHandler handler = new FailFastErrorHandler();

        StepVerifier.create(handler.handleError(stepId, testError, mockContext, 1))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.FAIL_SAGA)
                .verifyComplete();

        assertThat(handler.canHandle(testError, stepId, mockContext)).isTrue();
        assertThat(handler.getStrategyName()).isEqualTo("FailFast");
    }

    @Test
    void testFailFastErrorHandlerWithSpecificErrors() {
        FailFastErrorHandler handler = FailFastErrorHandler.forCriticalErrors(
                IllegalStateException.class, SecurityException.class
        );

        IllegalStateException criticalError = new IllegalStateException("Critical error");
        
        StepVerifier.create(handler.handleError(stepId, criticalError, mockContext, 1))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.FAIL_SAGA)
                .verifyComplete();

        assertThat(handler.canHandle(criticalError, stepId, mockContext)).isTrue();
        assertThat(handler.canHandle(testError, stepId, mockContext)).isFalse();
    }

    @Test
    void testRetryWithBackoffErrorHandler() {
        RetryWithBackoffErrorHandler handler = new RetryWithBackoffErrorHandler();

        StepVerifier.create(handler.handleError(stepId, testError, mockContext, 1))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.RETRY)
                .verifyComplete();

        assertThat(handler.canHandle(testError, stepId, mockContext)).isTrue();
        assertThat(handler.getStrategyName()).isEqualTo("RetryWithBackoff");
    }

    @Test
    void testRetryWithBackoffErrorHandlerMaxAttempts() {
        // Create handler that retries all errors up to 2 times
        RetryWithBackoffErrorHandler handler = new RetryWithBackoffErrorHandler(2, true, Set.of());

        // First attempt should retry
        StepVerifier.create(handler.handleError(stepId, testError, mockContext, 1))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.RETRY)
                .verifyComplete();

        // Second attempt should retry
        StepVerifier.create(handler.handleError(stepId, testError, mockContext, 2))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.RETRY)
                .verifyComplete();

        // Third attempt should continue (max attempts reached)
        StepVerifier.create(handler.handleError(stepId, testError, mockContext, 3))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.CONTINUE)
                .verifyComplete();
    }

    @Test
    void testRetryWithBackoffErrorHandlerForNetworkErrors() {
        RetryWithBackoffErrorHandler handler = RetryWithBackoffErrorHandler.forNetworkErrors(3);

        // Network error should be retryable
        StepVerifier.create(handler.handleError(stepId, networkError, mockContext, 1))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.RETRY)
                .verifyComplete();

        assertThat(handler.canHandle(networkError, stepId, mockContext)).isTrue();

        // Non-network error should not be retryable
        assertThat(handler.canHandle(testError, stepId, mockContext)).isFalse();
    }

    @Test
    void testRetryWithBackoffErrorHandlerForRetryableErrors() {
        RetryWithBackoffErrorHandler handler = RetryWithBackoffErrorHandler.forRetryableErrors(
                2, ConnectException.class, SocketTimeoutException.class
        );

        ConnectException connectError = new ConnectException("Connection failed");
        
        StepVerifier.create(handler.handleError(stepId, connectError, mockContext, 1))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.RETRY)
                .verifyComplete();

        assertThat(handler.canHandle(connectError, stepId, mockContext)).isTrue();
        assertThat(handler.canHandle(testError, stepId, mockContext)).isFalse();
    }

    @Test
    void testCompositeCompensationErrorHandler() {
        FailFastErrorHandler failFastHandler = FailFastErrorHandler.forCriticalErrors(IllegalStateException.class);
        RetryWithBackoffErrorHandler retryHandler = RetryWithBackoffErrorHandler.forNetworkErrors(2);
        LogAndContinueErrorHandler fallbackHandler = new LogAndContinueErrorHandler();

        CompositeCompensationErrorHandler composite = new CompositeCompensationErrorHandler(
                java.util.List.of(failFastHandler, retryHandler),
                fallbackHandler
        );

        // Test critical error - should fail fast
        IllegalStateException criticalError = new IllegalStateException("Critical");
        StepVerifier.create(composite.handleError(stepId, criticalError, mockContext, 1))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.FAIL_SAGA)
                .verifyComplete();

        // Test network error - should retry
        StepVerifier.create(composite.handleError(stepId, networkError, mockContext, 1))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.RETRY)
                .verifyComplete();

        // Test other error - should use fallback (continue)
        StepVerifier.create(composite.handleError(stepId, testError, mockContext, 1))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.CONTINUE)
                .verifyComplete();

        assertThat(composite.canHandle(testError, stepId, mockContext)).isTrue();
        assertThat(composite.getStrategyName()).contains("Composite");
    }

    @Test
    void testCompositeCompensationErrorHandlerBuilder() {
        CompositeCompensationErrorHandler composite = CompositeCompensationErrorHandler.builder()
                .failOn(IllegalStateException.class, SecurityException.class)
                .retryOn(ConnectException.class, SocketTimeoutException.class)
                .withFallback(new LogAndContinueErrorHandler())
                .build();

        // Test fail-fast error
        IllegalStateException criticalError = new IllegalStateException("Critical");
        StepVerifier.create(composite.handleError(stepId, criticalError, mockContext, 1))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.FAIL_SAGA)
                .verifyComplete();

        // Test retryable error
        ConnectException connectError = new ConnectException("Connection failed");
        StepVerifier.create(composite.handleError(stepId, connectError, mockContext, 1))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.RETRY)
                .verifyComplete();

        // Test fallback error
        StepVerifier.create(composite.handleError(stepId, testError, mockContext, 1))
                .expectNext(CompensationErrorHandler.CompensationErrorResult.CONTINUE)
                .verifyComplete();
    }

    @Test
    void testCompensationErrorHandlerFactory() {
        // Test getting default handlers
        CompensationErrorHandler defaultHandler = CompensationErrorHandlerFactory.defaultHandler();
        assertThat(defaultHandler).isInstanceOf(LogAndContinueErrorHandler.class);

        CompensationErrorHandler failFastHandler = CompensationErrorHandlerFactory.failFast();
        assertThat(failFastHandler).isInstanceOf(FailFastErrorHandler.class);

        CompensationErrorHandler retryHandler = CompensationErrorHandlerFactory.retry();
        assertThat(retryHandler).isInstanceOf(RetryWithBackoffErrorHandler.class);

        // Test getting by name
        CompensationErrorHandler robustHandler = CompensationErrorHandlerFactory.getHandler("robust");
        assertThat(robustHandler).isInstanceOf(CompositeCompensationErrorHandler.class);

        CompensationErrorHandler strictHandler = CompensationErrorHandlerFactory.getHandler("strict");
        assertThat(strictHandler).isInstanceOf(CompositeCompensationErrorHandler.class);

        CompensationErrorHandler networkAwareHandler = CompensationErrorHandlerFactory.getHandler("network-aware");
        assertThat(networkAwareHandler).isInstanceOf(CompositeCompensationErrorHandler.class);
    }

    @Test
    void testCompensationErrorHandlerFactoryRegistration() {
        // Test custom handler registration
        CompensationErrorHandler customHandler = new LogAndContinueErrorHandler();
        CompensationErrorHandlerFactory.registerHandler("custom", customHandler);

        CompensationErrorHandler retrieved = CompensationErrorHandlerFactory.getHandler("custom");
        assertThat(retrieved).isSameAs(customHandler);

        // Test available handlers
        String[] availableHandlers = CompensationErrorHandlerFactory.getAvailableHandlers();
        assertThat(availableHandlers).contains("custom", "log-and-continue", "fail-fast", "retry");

        // Test handler registration check
        assertThat(CompensationErrorHandlerFactory.isHandlerRegistered("custom")).isTrue();
        assertThat(CompensationErrorHandlerFactory.isHandlerRegistered("nonexistent")).isFalse();

        // Test unknown handler
        assertThatThrownBy(() -> CompensationErrorHandlerFactory.getHandler("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown compensation error handler: unknown");

        // Clean up
        CompensationErrorHandlerFactory.removeHandler("custom");
        assertThat(CompensationErrorHandlerFactory.isHandlerRegistered("custom")).isFalse();
    }

    @Test
    void testCompensationErrorHandlerFactoryReset() {
        // Register a custom handler
        CompensationErrorHandler customHandler = new LogAndContinueErrorHandler();
        CompensationErrorHandlerFactory.registerHandler("custom", customHandler);
        assertThat(CompensationErrorHandlerFactory.isHandlerRegistered("custom")).isTrue();

        // Reset to defaults
        CompensationErrorHandlerFactory.resetToDefaults();
        assertThat(CompensationErrorHandlerFactory.isHandlerRegistered("custom")).isFalse();

        // Verify default handlers are still available
        assertThat(CompensationErrorHandlerFactory.isHandlerRegistered("log-and-continue")).isTrue();
        assertThat(CompensationErrorHandlerFactory.isHandlerRegistered("fail-fast")).isTrue();
        assertThat(CompensationErrorHandlerFactory.isHandlerRegistered("retry")).isTrue();
    }

    @Test
    void testCompensationErrorResults() {
        // Test all enum values exist and are accessible
        CompensationErrorHandler.CompensationErrorResult[] results = 
                CompensationErrorHandler.CompensationErrorResult.values();
        
        assertThat(results).contains(
                CompensationErrorHandler.CompensationErrorResult.CONTINUE,
                CompensationErrorHandler.CompensationErrorResult.RETRY,
                CompensationErrorHandler.CompensationErrorResult.FAIL_SAGA,
                CompensationErrorHandler.CompensationErrorResult.SKIP_STEP,
                CompensationErrorHandler.CompensationErrorResult.MARK_COMPENSATED
        );
    }
}
