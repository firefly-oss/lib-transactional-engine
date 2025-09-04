/*
 * Copyright 2025 Firefly Software Solutions Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.firefly.transactionalengine.annotations;

import java.lang.annotation.*;

/**
 * Marks a Spring bean as a Saga orchestrator.
 * <p>
 * The {@code name} is the identifier used to execute the saga via {@code SagaEngine.execute(name, ...)}.
 * Classes annotated with {@link Saga} are discovered by {@link com.firefly.transactionalengine.registry.SagaRegistry}
 * at application startup.
 * <p>
 * Note: Older {@code run(...)} overloads still exist for backward compatibility but are deprecated in favor of
 * the typed {@code execute(...)} API that works with {@link com.firefly.transactionalengine.engine.StepInputs} and
 * returns a typed {@link com.firefly.transactionalengine.core.SagaResult}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Saga {
    String name();
    /** Optional cap for the number of steps executed concurrently within the same layer. 0 means unbounded. */
    int layerConcurrency() default 0;
}
