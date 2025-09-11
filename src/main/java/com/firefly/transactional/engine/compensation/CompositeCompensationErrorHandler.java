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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Composite error handler that chains multiple error handlers.
 * Handlers are tried in order until one can handle the error.
 */
public class CompositeCompensationErrorHandler implements CompensationErrorHandler {
    
    private static final Logger log = LoggerFactory.getLogger(CompositeCompensationErrorHandler.class);
    
    private final List<CompensationErrorHandler> handlers;
    private final CompensationErrorHandler fallbackHandler;
    
    /**
     * Creates a composite handler with the given handlers and a fallback.
     * 
     * @param handlers list of handlers to try in order
     * @param fallbackHandler handler to use if no other handler can handle the error
     */
    public CompositeCompensationErrorHandler(List<CompensationErrorHandler> handlers, 
                                           CompensationErrorHandler fallbackHandler) {
        this.handlers = handlers != null ? List.copyOf(handlers) : List.of();
        this.fallbackHandler = fallbackHandler != null ? fallbackHandler : new LogAndContinueErrorHandler();
    }
    
    /**
     * Creates a composite handler with the given handlers and default fallback.
     */
    public CompositeCompensationErrorHandler(List<CompensationErrorHandler> handlers) {
        this(handlers, new LogAndContinueErrorHandler());
    }
    
    @Override
    public Mono<CompensationErrorResult> handleError(String stepId, 
                                                   Throwable error, 
                                                   SagaContext context, 
                                                   int attempt) {
        // Try each handler in order
        for (CompensationErrorHandler handler : handlers) {
            if (handler.canHandle(error, stepId, context)) {
                log.debug("Using handler {} for compensation error in step {}", 
                         handler.getStrategyName(), stepId);
                return handler.handleError(stepId, error, context, attempt);
            }
        }
        
        // Use fallback handler if no specific handler can handle the error
        log.debug("Using fallback handler {} for compensation error in step {}", 
                 fallbackHandler.getStrategyName(), stepId);
        return fallbackHandler.handleError(stepId, error, context, attempt);
    }
    
    @Override
    public boolean canHandle(Throwable error, String stepId, SagaContext context) {
        // Composite handler can always handle errors (via fallback)
        return true;
    }
    
    @Override
    public String getStrategyName() {
        return "Composite[" + 
               handlers.stream()
                       .map(CompensationErrorHandler::getStrategyName)
                       .reduce((a, b) -> a + "," + b)
                       .orElse("") + 
               " -> " + fallbackHandler.getStrategyName() + "]";
    }
    
    /**
     * Gets the list of handlers in this composite.
     */
    public List<CompensationErrorHandler> getHandlers() {
        return handlers;
    }
    
    /**
     * Gets the fallback handler.
     */
    public CompensationErrorHandler getFallbackHandler() {
        return fallbackHandler;
    }
    
    /**
     * Builder for creating composite handlers.
     */
    public static class Builder {
        private final List<CompensationErrorHandler> handlers = new java.util.ArrayList<>();
        private CompensationErrorHandler fallbackHandler = new LogAndContinueErrorHandler();
        
        /**
         * Adds a handler to the chain.
         */
        public Builder addHandler(CompensationErrorHandler handler) {
            if (handler != null) {
                handlers.add(handler);
            }
            return this;
        }
        
        /**
         * Sets the fallback handler.
         */
        public Builder withFallback(CompensationErrorHandler fallbackHandler) {
            this.fallbackHandler = fallbackHandler;
            return this;
        }
        
        /**
         * Adds a retry handler for specific error types.
         */
        public Builder retryOn(Class<? extends Throwable>... errorTypes) {
            return addHandler(RetryWithBackoffErrorHandler.forRetryableErrors(3, errorTypes));
        }
        
        /**
         * Adds a fail-fast handler for specific error types.
         */
        public Builder failOn(Class<? extends Throwable>... errorTypes) {
            return addHandler(FailFastErrorHandler.forCriticalErrors(errorTypes));
        }
        
        /**
         * Builds the composite handler.
         */
        public CompositeCompensationErrorHandler build() {
            return new CompositeCompensationErrorHandler(handlers, fallbackHandler);
        }
    }
    
    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }
}
