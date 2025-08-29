package com.firefly.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Injects the whole variables map from SagaContext.variables().
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Variables {
}