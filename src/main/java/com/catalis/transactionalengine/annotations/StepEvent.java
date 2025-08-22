package com.catalis.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Optional event publication metadata for a saga step.
 * Place alongside @SagaStep or @ExternalSagaStep on the same method to request
 * an event to be published when the saga completes successfully (no compensations executed).
 *
 * Minimal fields are provided to keep the API simple and transport-agnostic. Adapters
 * (Kafka/SQS/etc.) can interpret topic/key/type as needed.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface StepEvent {
    /** Logical destination (e.g., topic/queue/exchange). Adapter interprets accordingly. */
    String topic();
    /** Optional event type name for consumers. */
    String type() default "";
    /** Optional partition/routing key; adapters may use or ignore it. */
    String key() default "";
    /** Allows turning off publication without removing the annotation. */
    boolean enabled() default true;
}
