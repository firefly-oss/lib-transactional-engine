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
 * Marks a method as the Confirm phase operation of a TCC participant.
 * <p>
 * The Confirm method is responsible for:
 * <ul>
 *   <li>Committing the reserved resources</li>
 *   <li>Finalizing the transaction</li>
 *   <li>Making changes permanent</li>
 * </ul>
 * <p>
 * The Confirm method is called only if all participants' Try methods succeed.
 * <p>
 * The Confirm method must be:
 * <ul>
 *   <li><b>Idempotent</b>: Can be called multiple times with the same result</li>
 *   <li><b>Non-blocking</b>: Returns a {@code Mono} for reactive execution</li>
 *   <li><b>Reliable</b>: Should not fail under normal circumstances</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>{@code
 * @TccParticipant(id = "payment")
 * public class PaymentParticipant {
 *     
 *     @ConfirmMethod
 *     public Mono<Void> confirmPayment(
 *             @FromTry ReservationId reservationId,
 *             TccContext context) {
 *         // Commit the reserved payment
 *         return paymentService.commit(reservationId);
 *     }
 * }
 * }</pre>
 *
 * @see TccParticipant
 * @see TryMethod
 * @see CancelMethod
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConfirmMethod {
    
    /**
     * Optional timeout in milliseconds for this confirm operation.
     * If not specified, uses the participant's or TCC transaction's timeout.
     * A value of 0 or negative means no timeout.
     *
     * @return the timeout in milliseconds
     */
    long timeoutMs() default -1;
    
    /**
     * Number of retry attempts for this confirm operation.
     * Confirm operations should be highly reliable and idempotent,
     * so retries are recommended.
     * <p>
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

