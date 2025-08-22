package com.catalis.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Injects the whole headers map from SagaContext.headers().
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Headers {
}