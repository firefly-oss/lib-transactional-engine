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
 * Marks a Spring bean as a TCC (Try-Confirm-Cancel) transaction coordinator.
 * <p>
 * The TCC pattern is a distributed transaction pattern that provides strong consistency
 * through a two-phase commit protocol with resource reservation:
 * <ul>
 *   <li><b>Try Phase</b>: Reserve resources and perform preliminary operations</li>
 *   <li><b>Confirm Phase</b>: Commit the reserved resources if all try operations succeed</li>
 *   <li><b>Cancel Phase</b>: Release reserved resources if any try operation fails</li>
 * </ul>
 * <p>
 * Classes annotated with {@code @Tcc} are discovered by {@link com.firefly.transactional.tcc.registry.TccRegistry}
 * at application startup. The TCC coordinator contains one or more participants, each defining
 * try, confirm, and cancel methods.
 * <p>
 * Example usage:
 * <pre>{@code
 * @Component
 * @Tcc(name = "order-payment")
 * public class OrderPaymentTcc {
 *     
 *     @TccParticipant(id = "payment-service")
 *     public static class PaymentParticipant {
 *         
 *         @TryMethod
 *         public Mono<ReservationId> tryReservePayment(@Input PaymentRequest request) {
 *             return paymentService.reserve(request);
 *         }
 *         
 *         @ConfirmMethod
 *         public Mono<Void> confirmPayment(@FromTry ReservationId reservationId) {
 *             return paymentService.commit(reservationId);
 *         }
 *         
 *         @CancelMethod
 *         public Mono<Void> cancelPayment(@FromTry ReservationId reservationId) {
 *             return paymentService.release(reservationId);
 *         }
 *     }
 * }
 * }</pre>
 *
 * @see TccParticipant
 * @see TryMethod
 * @see ConfirmMethod
 * @see CancelMethod
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tcc {
    
    /**
     * The unique name of this TCC transaction coordinator.
     * This name is used to execute the TCC transaction via {@code TccEngine.execute(name, ...)}.
     *
     * @return the TCC transaction name
     */
    String name();
    
    /**
     * Optional timeout in milliseconds for the entire TCC transaction.
     * If not specified, uses the default timeout from configuration.
     * A value of 0 or negative means no timeout.
     *
     * @return the transaction timeout in milliseconds
     */
    long timeoutMs() default -1;
    
    /**
     * Whether to enable automatic retry for failed operations.
     * When enabled, failed try/confirm/cancel operations will be retried
     * according to the retry configuration.
     *
     * @return true to enable retry, false otherwise
     */
    boolean retryEnabled() default true;
    
    /**
     * Maximum number of retry attempts for failed operations.
     * Only applicable when {@link #retryEnabled()} is true.
     *
     * @return the maximum number of retries
     */
    int maxRetries() default 3;
    
    /**
     * Backoff delay in milliseconds between retry attempts.
     * Only applicable when {@link #retryEnabled()} is true.
     *
     * @return the backoff delay in milliseconds
     */
    long backoffMs() default 1000;
}

