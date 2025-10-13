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


package com.firefly.transactional.saga.annotations;

import java.lang.annotation.*;

/**
 * Declares a compensation method for a saga step that may live outside of the orchestrator class.
 *
 * Usage example:
 *
 *  @CompensationSagaStep(saga = "OrderSaga", forStepId = "reserveFunds")
 *  public Mono<Void> releaseFunds(ReserveCmd cmd, SagaContext ctx) { ... }
 *
 * The method signature follows the same conventions as in-class compensation for @SagaStep:
 * - Parameters can include the original step input or the step result (engine decides by type), and/or SagaContext.
 * - Return type can be Mono<Void> or void (void will be adapted to Mono.empty()).
 *
 * When both in-class and external compensations are provided for the same step, the external declaration takes precedence.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CompensationSagaStep {
    /** The saga name as declared in {@link Saga#name()}. */
    String saga();
    /** The step id to compensate, as declared in {@link SagaStep#id()}. */
    String forStepId();
}
