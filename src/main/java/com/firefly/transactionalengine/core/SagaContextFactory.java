package com.firefly.transactionalengine.core;

import com.firefly.transactionalengine.registry.SagaDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating SagaContext instances with automatic optimization.
 * 
 * <p>Automatically selects between OptimizedSagaContext (HashMap-based) and
 * standard SagaContext (ConcurrentHashMap-based) based on the saga's execution pattern:
 * <ul>
 *   <li><b>Sequential execution:</b> All layers have single step → OptimizedSagaContext</li>
 *   <li><b>Concurrent execution:</b> Any layer has multiple steps → Standard SagaContext</li>
 * </ul>
 * 
 * <p>This provides automatic performance optimization while maintaining thread safety.
 */
public class SagaContextFactory {
    private static final Logger log = LoggerFactory.getLogger(SagaContextFactory.class);
    
    private final boolean optimizationEnabled;
    
    /**
     * Creates a factory with optimization enabled by default.
     */
    public SagaContextFactory() {
        this(true);
    }
    
    /**
     * Creates a factory with configurable optimization.
     * 
     * @param optimizationEnabled whether to enable automatic optimization
     */
    public SagaContextFactory(boolean optimizationEnabled) {
        this.optimizationEnabled = optimizationEnabled;
    }
    
    /**
     * Creates a new SagaContext with automatic optimization based on saga execution pattern.
     * 
     * @param saga the saga definition to analyze for optimization potential
     * @return OptimizedSagaContext for sequential execution, standard SagaContext for concurrent execution
     */
    public SagaContext createContext(SagaDefinition saga) {
        return createContext(saga, null, null);
    }
    
    /**
     * Creates a new SagaContext with the specified correlation ID and automatic optimization.
     * 
     * @param saga the saga definition to analyze for optimization potential
     * @param correlationId the correlation ID for the context
     * @return OptimizedSagaContext for sequential execution, standard SagaContext for concurrent execution
     */
    public SagaContext createContext(SagaDefinition saga, String correlationId) {
        return createContext(saga, correlationId, null);
    }
    
    /**
     * Creates a new SagaContext with the specified correlation ID and saga name, with automatic optimization.
     * 
     * @param saga the saga definition to analyze for optimization potential
     * @param correlationId the correlation ID for the context
     * @param sagaName the saga name for the context (overrides saga definition name if provided)
     * @return OptimizedSagaContext for sequential execution, standard SagaContext for concurrent execution
     */
    public SagaContext createContext(SagaDefinition saga, String correlationId, String sagaName) {
        if (!optimizationEnabled) {
            // Optimization disabled - always use standard context
            if (correlationId != null && sagaName != null) {
                return new SagaContext(correlationId, sagaName);
            } else if (correlationId != null) {
                return new SagaContext(correlationId);
            } else {
                return new SagaContext();
            }
        }
        
        boolean canOptimize = SagaOptimizationDetector.canOptimize(saga);
        String effectiveSagaName = sagaName != null ? sagaName : (saga != null ? saga.name : null);
        
        if (canOptimize) {
            // Use optimized context for sequential execution
            OptimizedSagaContext optimized = correlationId != null ? 
                    new OptimizedSagaContext(correlationId, effectiveSagaName) :
                    new OptimizedSagaContext();
            
            if (effectiveSagaName != null && correlationId == null) {
                optimized.setSagaName(effectiveSagaName);
            }
            
            log.debug("Using OptimizedSagaContext for sequential saga: {}", effectiveSagaName);
            return optimized.toStandardContext(); // Return as SagaContext interface
        } else {
            // Use standard context for concurrent execution
            SagaContext standard;
            if (correlationId != null && effectiveSagaName != null) {
                standard = new SagaContext(correlationId, effectiveSagaName);
            } else if (correlationId != null) {
                standard = new SagaContext(correlationId);
            } else {
                standard = new SagaContext();
                if (effectiveSagaName != null) {
                    standard.setSagaName(effectiveSagaName);
                }
            }
            
            log.debug("Using standard SagaContext for concurrent saga: {}", effectiveSagaName);
            return standard;
        }
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
    
    // Helper methods
    private SagaContext createStandardContext(String correlationId, String sagaName) {
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