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

package com.firefly.transactional.tcc.annotations;

import java.lang.annotation.*;

/**
 * Marks a class or nested class as a TCC participant within a TCC transaction.
 * <p>
 * A TCC participant represents a service or resource that participates in the
 * distributed transaction. Each participant must define three methods:
 * <ul>
 *   <li>A {@link TryMethod} that reserves resources</li>
 *   <li>A {@link ConfirmMethod} that commits the reservation</li>
 *   <li>A {@link CancelMethod} that releases the reservation</li>
 * </ul>
 * <p>
 * Participants can be defined as:
 * <ul>
 *   <li>Nested static classes within a {@link Tcc} annotated class</li>
 *   <li>Separate Spring beans referenced by the TCC coordinator</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>{@code
 * @Component
 * @Tcc(name = "order-processing")
 * public class OrderProcessingTcc {
 *     
 *     @TccParticipant(id = "inventory", order = 1)
 *     public static class InventoryParticipant {
 *         
 *         @TryMethod
 *         public Mono<ReservationId> tryReserve(@Input InventoryRequest request) {
 *             return inventoryService.reserve(request);
 *         }
 *         
 *         @ConfirmMethod
 *         public Mono<Void> confirm(@FromTry ReservationId id) {
 *             return inventoryService.commit(id);
 *         }
 *         
 *         @CancelMethod
 *         public Mono<Void> cancel(@FromTry ReservationId id) {
 *             return inventoryService.release(id);
 *         }
 *     }
 * }
 * }</pre>
 *
 * @see Tcc
 * @see TryMethod
 * @see ConfirmMethod
 * @see CancelMethod
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TccParticipant {
    
    /**
     * The unique identifier for this participant within the TCC transaction.
     * This ID is used to reference the participant and its results.
     *
     * @return the participant ID
     */
    String id();
    
    /**
     * The execution order of this participant in the try phase.
     * Participants with lower order values are executed first.
     * Participants with the same order may be executed in parallel (future enhancement).
     * <p>
     * Default is 0, which means no specific ordering.
     *
     * @return the execution order
     */
    int order() default 0;
    
    /**
     * Optional timeout in milliseconds for this participant's operations.
     * If not specified, uses the TCC transaction's default timeout.
     * A value of 0 or negative means no timeout.
     *
     * @return the participant timeout in milliseconds
     */
    long timeoutMs() default -1;
    
    /**
     * Whether this participant is optional.
     * If true, failure of this participant's try method will not cause
     * the entire TCC transaction to fail.
     * <p>
     * Default is false (participant is required).
     *
     * @return true if optional, false if required
     */
    boolean optional() default false;
}

