/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
 * Injects a header value from the TCC context.
 * <p>
 * This annotation can be used on parameters in TCC participant methods
 * to access header values that were set in the TCC context.
 * <p>
 * Example:
 * <pre>
 * {@code
 * @TccParticipant(id = "payment")
 * public class PaymentParticipant {
 *     public PaymentReservation tryPayment(PaymentRequest request, 
 *                                         @Header("userId") String userId,
 *                                         @Header("correlationId") String correlationId) {
 *         // Try phase logic with header values
 *         return new PaymentReservation(reservationId);
 *     }
 * }
 * }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Header {
    
    /**
     * The name of the header to inject.
     *
     * @return the header name
     */
    String value();
    
    /**
     * Whether this parameter is required. If true and the header is null,
     * an exception will be thrown.
     *
     * @return true if required, false otherwise
     */
    boolean required() default true;
}
