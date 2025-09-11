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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating and managing compensation error handlers.
 * Provides pre-configured handlers for common scenarios.
 */
public class CompensationErrorHandlerFactory {
    
    private static final Map<String, CompensationErrorHandler> handlers = new ConcurrentHashMap<>();
    
    static {
        // Register default handlers
        registerHandler("log-and-continue", new LogAndContinueErrorHandler());
        registerHandler("fail-fast", new FailFastErrorHandler());
        registerHandler("retry", new RetryWithBackoffErrorHandler());
        registerHandler("retry-network", RetryWithBackoffErrorHandler.forNetworkErrors(3));
        
        // Register composite handlers for common scenarios
        registerHandler("robust", createRobustHandler());
        registerHandler("strict", createStrictHandler());
        registerHandler("network-aware", createNetworkAwareHandler());
    }
    
    /**
     * Gets a compensation error handler by name.
     * 
     * @param handlerName the name of the handler
     * @return the handler instance
     * @throws IllegalArgumentException if handler is not found
     */
    public static CompensationErrorHandler getHandler(String handlerName) {
        CompensationErrorHandler handler = handlers.get(handlerName);
        if (handler == null) {
            throw new IllegalArgumentException("Unknown compensation error handler: " + handlerName);
        }
        return handler;
    }
    
    /**
     * Registers a custom compensation error handler.
     * 
     * @param name the name to register the handler under
     * @param handler the handler implementation
     */
    public static void registerHandler(String name, CompensationErrorHandler handler) {
        handlers.put(name, handler);
    }
    
    /**
     * Gets the default log-and-continue handler.
     */
    public static CompensationErrorHandler defaultHandler() {
        return getHandler("log-and-continue");
    }
    
    /**
     * Gets a fail-fast handler that fails the saga on any compensation error.
     */
    public static CompensationErrorHandler failFast() {
        return getHandler("fail-fast");
    }
    
    /**
     * Gets a retry handler that retries compensation operations.
     */
    public static CompensationErrorHandler retry() {
        return getHandler("retry");
    }
    
    /**
     * Gets a robust handler that retries transient errors and logs others.
     */
    public static CompensationErrorHandler robust() {
        return getHandler("robust");
    }
    
    /**
     * Gets a strict handler that fails fast on critical errors.
     */
    public static CompensationErrorHandler strict() {
        return getHandler("strict");
    }
    
    /**
     * Gets a network-aware handler optimized for network operations.
     */
    public static CompensationErrorHandler networkAware() {
        return getHandler("network-aware");
    }
    
    /**
     * Creates a robust handler that handles most scenarios gracefully.
     */
    private static CompensationErrorHandler createRobustHandler() {
        return CompositeCompensationErrorHandler.builder()
                .retryOn(java.net.ConnectException.class, 
                        java.net.SocketTimeoutException.class,
                        java.io.IOException.class)
                .addHandler(new RetryWithBackoffErrorHandler(2, false, 
                           java.util.Set.of(RuntimeException.class)))
                .withFallback(new LogAndContinueErrorHandler())
                .build();
    }
    
    /**
     * Creates a strict handler that fails fast on critical errors.
     */
    private static CompensationErrorHandler createStrictHandler() {
        return CompositeCompensationErrorHandler.builder()
                .failOn(IllegalStateException.class, 
                       SecurityException.class,
                       IllegalArgumentException.class)
                .retryOn(java.net.ConnectException.class, 
                        java.net.SocketTimeoutException.class)
                .withFallback(new FailFastErrorHandler())
                .build();
    }
    
    /**
     * Creates a network-aware handler optimized for network operations.
     */
    private static CompensationErrorHandler createNetworkAwareHandler() {
        return CompositeCompensationErrorHandler.builder()
                .addHandler(RetryWithBackoffErrorHandler.forNetworkErrors(5))
                .retryOn(java.util.concurrent.TimeoutException.class)
                .withFallback(new LogAndContinueErrorHandler())
                .build();
    }
    
    /**
     * Gets all available handler names.
     * 
     * @return array of handler names
     */
    public static String[] getAvailableHandlers() {
        return handlers.keySet().toArray(new String[0]);
    }
    
    /**
     * Checks if a handler is registered.
     * 
     * @param handlerName the handler name to check
     * @return true if the handler is registered
     */
    public static boolean isHandlerRegistered(String handlerName) {
        return handlers.containsKey(handlerName);
    }
    
    /**
     * Removes a registered handler.
     * 
     * @param handlerName the name of the handler to remove
     * @return the removed handler, or null if not found
     */
    public static CompensationErrorHandler removeHandler(String handlerName) {
        return handlers.remove(handlerName);
    }
    
    /**
     * Clears all registered handlers and resets to defaults.
     */
    public static void resetToDefaults() {
        handlers.clear();
        
        // Re-register default handlers
        registerHandler("log-and-continue", new LogAndContinueErrorHandler());
        registerHandler("fail-fast", new FailFastErrorHandler());
        registerHandler("retry", new RetryWithBackoffErrorHandler());
        registerHandler("retry-network", RetryWithBackoffErrorHandler.forNetworkErrors(3));
        registerHandler("robust", createRobustHandler());
        registerHandler("strict", createStrictHandler());
        registerHandler("network-aware", createNetworkAwareHandler());
    }
}
