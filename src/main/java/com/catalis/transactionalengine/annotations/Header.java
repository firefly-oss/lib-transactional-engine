package com.catalis.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Injects a single outbound header from SagaContext.headers().
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Header {
    String value();
}