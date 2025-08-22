package com.catalis.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Injects a single variable from SagaContext.variables() by name.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Variable {
    String value();
}