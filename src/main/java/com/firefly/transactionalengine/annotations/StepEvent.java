package com.firefly.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Configures event publication for a saga step.
 * 
 * When present on a @SagaStep method, an event will be published
 * when the saga completes successfully (no compensations executed).
 * 
 * The microservice's StepEventPublisher implementation determines
 * how to interpret and route the event data.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface StepEvent {
    
    /** Event destination (topic, queue, exchange, etc.) */
    String topic() default "";
    
    /** Event type identifier for consumers */
    String type() default "";
    
    /** Routing/partition key */
    String key() default "";
}
