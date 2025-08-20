package com.catalis.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Marks a parameter to receive the step input value.
 * When a key is provided, the engine will attempt to extract the value from a Map input using that key.
 * If the key is empty, the entire input object is injected.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Input {
    String value() default "";
}