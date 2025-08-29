package com.firefly.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Injects the compensation result of a given step id from SagaContext.
 * Useful inside compensation methods that need to consume the output of another compensation.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FromCompensationResult {
    String value();
}
