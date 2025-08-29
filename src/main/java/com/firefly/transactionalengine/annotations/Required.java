package com.firefly.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Marks a parameter as required (non-null) after resolution.
 * If the engine resolves the parameter value to null at invocation time, it will throw an IllegalStateException
 * with a descriptive message to aid debugging.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Required {
}
