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


package com.firefly.transactional.shared.annotations;

import com.firefly.transactional.saga.config.SagaPersistenceAutoConfiguration;
import com.firefly.transactional.saga.config.SagaRedisAutoConfiguration;
import com.firefly.transactional.shared.config.TransactionalEngineConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Enables the Transactional Engine (Saga orchestrator) components in a Spring application.
 * <p>
 * Imports {@link com.firefly.transactional.config.TransactionalEngineConfiguration} that wires:
 * - {@code SagaRegistry}: scans for @Saga beans and indexes steps
 * - {@code SagaEngine}: the in-memory orchestrator
 * - {@code SagaEvents}: default implementation {@code SagaLoggerEvents} (override by declaring your own bean)
 * - {@code StepLoggingAspect}: AOP aspect for additional logging
 * - {@code WebClient.Builder}: convenience bean for HTTP clients
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Import({TransactionalEngineConfiguration.class, SagaPersistenceAutoConfiguration.class, SagaRedisAutoConfiguration.class})
public @interface EnableTransactionalEngine {
}
