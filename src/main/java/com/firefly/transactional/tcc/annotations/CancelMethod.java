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
 * Marks a method as the Cancel phase operation of a TCC participant.
 * <p>
 * The Cancel method is responsible for:
 * <ul>
 *   <li>Releasing reserved resources</li>
 *   <li>Rolling back the try operation</li>
 *   <li>Cleaning up any preliminary changes</li>
 * </ul>
 * <p>
 * The Cancel method is called if any participant's Try method fails.
 * It is only called for participants whose Try method succeeded.
 * <p>
 * The Cancel method must be:
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
 *     @CancelMethod
 *     public Mono<Void> cancelPayment(
 *             @FromTry ReservationId reservationId,
 *             TccContext context) {
 *         // Release the reserved payment
 *         return paymentService.release(reservationId);
 *     }
 * }
 * }</pre>
 *
 * @see TccParticipant
 * @see TryMethod
 * @see ConfirmMethod
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CancelMethod {
    
    /**
     * Optional timeout in milliseconds for this cancel operation.
     * If not specified, uses the participant's or TCC transaction's timeout.
     * A value of 0 or negative means no timeout.
     *
     * @return the timeout in milliseconds
     */
    long timeoutMs() default -1;
    
    /**
     * Number of retry attempts for this cancel operation.
     * Cancel operations should be highly reliable and idempotent,
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

