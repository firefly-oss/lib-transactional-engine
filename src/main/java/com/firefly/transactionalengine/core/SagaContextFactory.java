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


package com.firefly.transactionalengine.core;

import com.firefly.transactionalengine.registry.SagaDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating SagaContext instances with explicit execution mode support.
 *
 * <p>Provides multiple execution modes for different scenarios:
 * <ul>
 *   <li><b>SEQUENTIAL:</b> Single-threaded execution with optimized HashMap-based context</li>
 *   <li><b>CONCURRENT:</b> Multi-threaded execution with thread-safe ConcurrentHashMap-based context</li>
 *   <li><b>AUTO:</b> Automatically selects based on saga topology analysis</li>
 *   <li><b>HIGH_PERFORMANCE:</b> Optimized for maximum throughput scenarios</li>
 *   <li><b>LOW_MEMORY:</b> Optimized for memory-constrained environments</li>
 * </ul>
 *
 * <p>This provides explicit control over context creation while maintaining backward compatibility.
 */
public class SagaContextFactory {
    private static final Logger log = LoggerFactory.getLogger(SagaContextFactory.class);

    private final boolean optimizationEnabled;
    private final ExecutionMode defaultMode;

    /**
     * Execution modes for saga context creation.
     */
    public enum ExecutionMode {
        /**
         * Sequential execution mode - uses optimized HashMap-based context.
         * Best for single-threaded sagas with no concurrent step execution.
         */
        SEQUENTIAL,

        /**
         * Concurrent execution mode - uses thread-safe ConcurrentHashMap-based context.
         * Required for sagas with concurrent step execution within layers.
         */
        CONCURRENT,

        /**
         * Automatic mode - analyzes saga topology to choose optimal context type.
         * Provides convenience while maintaining performance.
         */
        AUTO,

        /**
         * High performance mode - optimized for maximum throughput.
         * Uses the most efficient context type available.
         */
        HIGH_PERFORMANCE,

        /**
         * Low memory mode - optimized for memory-constrained environments.
         * Uses context implementations with minimal memory overhead.
         */
        LOW_MEMORY
    }
    
    /**
     * Creates a factory with optimization enabled and AUTO execution mode by default.
     */
    public SagaContextFactory() {
        this(true, ExecutionMode.AUTO);
    }

    /**
     * Creates a factory with configurable optimization and AUTO execution mode.
     *
     * @param optimizationEnabled whether to enable automatic optimization
     */
    public SagaContextFactory(boolean optimizationEnabled) {
        this(optimizationEnabled, ExecutionMode.AUTO);
    }

    /**
     * Creates a factory with explicit execution mode.
     *
     * @param optimizationEnabled whether to enable automatic optimization
     * @param defaultMode the default execution mode to use
     */
    public SagaContextFactory(boolean optimizationEnabled, ExecutionMode defaultMode) {
        this.optimizationEnabled = optimizationEnabled;
        this.defaultMode = defaultMode != null ? defaultMode : ExecutionMode.AUTO;
    }
    
    /**
     * Creates a new SagaContext using the default execution mode.
     *
     * @param saga the saga definition to analyze for optimization potential
     * @return context optimized for the default execution mode
     */
    public SagaContext createContext(SagaDefinition saga) {
        return createContext(saga, null, null, defaultMode);
    }

    /**
     * Creates a new SagaContext with explicit execution mode.
     *
     * @param saga the saga definition
     * @param mode the execution mode to use
     * @return context optimized for the specified execution mode
     */
    public SagaContext createContext(SagaDefinition saga, ExecutionMode mode) {
        return createContext(saga, null, null, mode);
    }
    
    /**
     * Creates a new SagaContext with the specified correlation ID using the default execution mode.
     *
     * @param saga the saga definition to analyze for optimization potential
     * @param correlationId the correlation ID for the context
     * @return context optimized for the default execution mode
     */
    public SagaContext createContext(SagaDefinition saga, String correlationId) {
        return createContext(saga, correlationId, null, defaultMode);
    }

    /**
     * Creates a new SagaContext with the specified correlation ID and saga name using the default execution mode.
     *
     * @param saga the saga definition to analyze for optimization potential
     * @param correlationId the correlation ID for the context
     * @param sagaName the saga name for the context (overrides saga definition name if provided)
     * @return context optimized for the default execution mode
     */
    public SagaContext createContext(SagaDefinition saga, String correlationId, String sagaName) {
        return createContext(saga, correlationId, sagaName, defaultMode);
    }

    /**
     * Creates a new SagaContext with explicit execution mode and all parameters.
     *
     * @param saga the saga definition to analyze for optimization potential
     * @param correlationId the correlation ID for the context
     * @param sagaName the saga name for the context (overrides saga definition name if provided)
     * @param mode the execution mode to use
     * @return context optimized for the specified execution mode
     */
    public SagaContext createContext(SagaDefinition saga, String correlationId, String sagaName, ExecutionMode mode) {
        String effectiveSagaName = sagaName != null ? sagaName : (saga != null ? saga.name : null);
        ExecutionMode effectiveMode = mode != null ? mode : defaultMode;

        // Determine the actual context type to create based on mode
        ContextType contextType = determineContextType(saga, effectiveMode);

        log.debug("Creating context for saga '{}' with mode {} -> type {}",
                 effectiveSagaName, effectiveMode, contextType);

        switch (contextType) {
            case OPTIMIZED:
                return createOptimizedContextInternal(correlationId, effectiveSagaName);
            case STANDARD:
            default:
                return createStandardContextInternal(correlationId, effectiveSagaName);
        }
    }

    /**
     * Determines the appropriate context type based on saga and execution mode.
     */
    private ContextType determineContextType(SagaDefinition saga, ExecutionMode mode) {
        if (!optimizationEnabled) {
            return ContextType.STANDARD;
        }

        switch (mode) {
            case SEQUENTIAL:
            case HIGH_PERFORMANCE:
            case LOW_MEMORY:
                return ContextType.OPTIMIZED;
            case CONCURRENT:
                return ContextType.STANDARD;
            case AUTO:
            default:
                return SagaOptimizationDetector.canOptimize(saga) ? ContextType.OPTIMIZED : ContextType.STANDARD;
        }
    }

    /**
     * Internal context types.
     */
    private enum ContextType {
        STANDARD,
        OPTIMIZED
    }
    
    /**
     * Creates a context optimized for the given saga, returning the actual implementation type.
     * This method is useful when you need access to the specific implementation.
     * 
     * @param saga the saga definition to analyze
     * @param correlationId the correlation ID
     * @param sagaName the saga name
     * @return either OptimizedSagaContext or SagaContext depending on optimization potential
     */
    public Object createContextWithType(SagaDefinition saga, String correlationId, String sagaName) {
        if (!optimizationEnabled || !SagaOptimizationDetector.canOptimize(saga)) {
            return createStandardContext(correlationId, sagaName);
        } else {
            return createOptimizedContext(correlationId, sagaName);
        }
    }
    
    /**
     * Analyzes the optimization potential of a saga without creating a context.
     * 
     * @param saga the saga definition to analyze
     * @return detailed analysis of optimization potential
     */
    public SagaOptimizationDetector.OptimizationAnalysis analyzeOptimization(SagaDefinition saga) {
        return SagaOptimizationDetector.analyze(saga);
    }
    
    /**
     * Checks if optimization is enabled for this factory.
     * 
     * @return true if optimization is enabled
     */
    public boolean isOptimizationEnabled() {
        return optimizationEnabled;
    }
    
    /**
     * Gets the default execution mode for this factory.
     */
    public ExecutionMode getDefaultMode() {
        return defaultMode;
    }

    /**
     * Creates a factory configured for sequential execution.
     */
    public static SagaContextFactory forSequentialExecution() {
        return new SagaContextFactory(true, ExecutionMode.SEQUENTIAL);
    }

    /**
     * Creates a factory configured for concurrent execution.
     */
    public static SagaContextFactory forConcurrentExecution() {
        return new SagaContextFactory(true, ExecutionMode.CONCURRENT);
    }

    /**
     * Creates a factory configured for high performance scenarios.
     */
    public static SagaContextFactory forHighPerformance() {
        return new SagaContextFactory(true, ExecutionMode.HIGH_PERFORMANCE);
    }

    /**
     * Creates a factory configured for low memory scenarios.
     */
    public static SagaContextFactory forLowMemory() {
        return new SagaContextFactory(true, ExecutionMode.LOW_MEMORY);
    }

    // Helper methods
    private SagaContext createStandardContextInternal(String correlationId, String sagaName) {
        if (correlationId != null && sagaName != null) {
            return new SagaContext(correlationId, sagaName);
        } else if (correlationId != null) {
            return new SagaContext(correlationId);
        } else {
            SagaContext context = new SagaContext();
            if (sagaName != null) {
                context.setSagaName(sagaName);
            }
            return context;
        }
    }

    private SagaContext createOptimizedContextInternal(String correlationId, String sagaName) {
        OptimizedSagaContext optimized;
        if (correlationId != null && sagaName != null) {
            optimized = new OptimizedSagaContext(correlationId, sagaName);
        } else if (correlationId != null) {
            optimized = new OptimizedSagaContext(correlationId);
        } else {
            optimized = new OptimizedSagaContext();
            if (sagaName != null) {
                optimized.setSagaName(sagaName);
            }
        }
        return optimized.toStandardContext(); // Return as SagaContext interface
    }

    // Legacy helper methods for backward compatibility
    private SagaContext createStandardContext(String correlationId, String sagaName) {
        return createStandardContextInternal(correlationId, sagaName);
    }

    private OptimizedSagaContext createOptimizedContext(String correlationId, String sagaName) {
        if (correlationId != null && sagaName != null) {
            return new OptimizedSagaContext(correlationId, sagaName);
        } else if (correlationId != null) {
            return new OptimizedSagaContext(correlationId);
        } else {
            OptimizedSagaContext context = new OptimizedSagaContext();
            if (sagaName != null) {
                context.setSagaName(sagaName);
            }
            return context;
        }
    }
}