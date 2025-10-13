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
 * Marks a method as the Try phase operation of a TCC participant.
 * <p>
 * The Try method is responsible for:
 * <ul>
 *   <li>Validating the operation can be performed</li>
 *   <li>Reserving necessary resources</li>
 *   <li>Performing preliminary checks</li>
 *   <li>Returning a reservation identifier or result</li>
 * </ul>
 * <p>
 * The Try method must be:
 * <ul>
 *   <li><b>Idempotent</b>: Can be called multiple times with the same result</li>
 *   <li><b>Reversible</b>: Can be undone by the Cancel method</li>
 *   <li><b>Non-blocking</b>: Returns a {@code Mono} for reactive execution</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>{@code
 * @TccParticipant(id = "payment")
 * public class PaymentParticipant {
 *     
 *     @TryMethod
 *     public Mono<ReservationId> tryReservePayment(
 *             @Input PaymentRequest request,
 *             TccContext context) {
 *         // Validate payment details
 *         // Reserve payment amount
 *         // Return reservation ID
 *         return paymentService.reserve(request);
 *     }
 * }
 * }</pre>
 *
 * @see TccParticipant
 * @see ConfirmMethod
 * @see CancelMethod
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TryMethod {
    
    /**
     * Optional timeout in milliseconds for this try operation.
     * If not specified, uses the participant's or TCC transaction's timeout.
     * A value of 0 or negative means no timeout.
     *
     * @return the timeout in milliseconds
     */
    long timeoutMs() default -1;
    
    /**
     * Number of retry attempts for this try operation.
     * If not specified, uses the TCC transaction's retry configuration.
     * A value of 0 means no retries.
     *
     * @return the number of retry attempts
     */
    int retry() default -1;
    
    /**
     * Backoff delay in milliseconds between retry attempts.
     * Only applicable when retry is enabled.
     *
     * @return the backoff delay in milliseconds
     */
    long backoffMs() default -1;
}

