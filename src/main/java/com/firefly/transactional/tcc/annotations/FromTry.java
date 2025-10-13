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
 * Injects the result from the Try phase into Confirm or Cancel method parameters.
 * <p>
 * This annotation is used in {@link ConfirmMethod} and {@link CancelMethod} to access
 * the result returned by the corresponding {@link TryMethod}.
 * <p>
 * Example usage:
 * <pre>{@code
 * @TccParticipant(id = "payment")
 * public class PaymentParticipant {
 *     
 *     @TryMethod
 *     public Mono<ReservationId> tryReserve(@Input PaymentRequest request) {
 *         return paymentService.reserve(request);
 *     }
 *     
 *     @ConfirmMethod
 *     public Mono<Void> confirm(@FromTry ReservationId reservationId) {
 *         // reservationId is injected from the try method result
 *         return paymentService.commit(reservationId);
 *     }
 *     
 *     @CancelMethod
 *     public Mono<Void> cancel(@FromTry ReservationId reservationId) {
 *         // reservationId is injected from the try method result
 *         return paymentService.release(reservationId);
 *     }
 * }
 * }</pre>
 * <p>
 * If the Try method returns a complex object and you only need a specific field,
 * you can specify the field name:
 * <pre>{@code
 * @ConfirmMethod
 * public Mono<Void> confirm(@FromTry("reservationId") String id) {
 *     return paymentService.commit(id);
 * }
 * }</pre>
 *
 * @see TryMethod
 * @see ConfirmMethod
 * @see CancelMethod
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FromTry {
    
    /**
     * Optional field name to extract from the Try method result.
     * If not specified, the entire result object is injected.
     * <p>
     * This is useful when the Try method returns a complex object
     * but you only need a specific field in the Confirm/Cancel method.
     *
     * @return the field name to extract, or empty string for the entire result
     */
    String value() default "";
}

