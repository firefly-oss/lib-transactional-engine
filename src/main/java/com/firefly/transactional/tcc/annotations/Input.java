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
 * Injects input data for TCC participant methods.
 * <p>
 * This annotation can be used on parameters in TCC participant methods
 * to access input data. If no key is specified, the entire input object
 * is injected. If a key is specified and the input is a Map, the value
 * for that key is injected.
 * <p>
 * Example:
 * <pre>
 * {@code
 * @TccParticipant(id = "payment")
 * public class PaymentParticipant {
 *     // Inject entire input object
 *     public PaymentReservation tryPayment(@Input PaymentRequest request) {
 *         return new PaymentReservation(reservationId);
 *     }
 *     
 *     // Inject specific field from Map input
 *     public PaymentReservation tryPayment(@Input("amount") BigDecimal amount,
 *                                         @Input("currency") String currency) {
 *         return new PaymentReservation(reservationId);
 *     }
 * }
 * }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Input {
    
    /**
     * The key to extract from the input if it's a Map. If empty,
     * the entire input object is injected.
     *
     * @return the input key, or empty string for entire input
     */
    String value() default "";
    
    /**
     * Whether this parameter is required. If true and the input is null,
     * an exception will be thrown.
     *
     * @return true if required, false otherwise
     */
    boolean required() default true;
}
