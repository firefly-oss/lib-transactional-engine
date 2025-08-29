package com.firefly.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Injects the Throwable captured during compensation of a given step id from SagaContext.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CompensationError {
    String value();
}
