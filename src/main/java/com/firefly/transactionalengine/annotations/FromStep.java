package com.firefly.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Injects the result of another step by its id.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FromStep {
    String value();
}