package com.catalis.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * When placed on a saga step method, stores the step's return value into SagaContext.variables()
 * under the given name.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SetVariable {
    String value();
}