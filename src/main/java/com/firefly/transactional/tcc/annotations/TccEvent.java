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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to configure event publishing for TCC participants.
 * 
 * <p>When applied to a TCC participant class or individual methods, this annotation
 * configures the publishing of events for participant execution. Events are published
 * through the configured {@link com.firefly.transactional.tcc.events.TccEventPublisher}.
 * 
 * <p>Events are published for all phases (TRY, CONFIRM, CANCEL) and include:
 * <ul>
 *   <li>Participant started</li>
 *   <li>Participant succeeded</li>
 *   <li>Participant failed</li>
 *   <li>Participant retry</li>
 * </ul>
 * 
 * <p>Example usage:
 * <pre>{@code
 * @TccParticipant(id = "payment")
 * @TccEvent(topic = "tcc-events", eventType = "PAYMENT_PARTICIPANT")
 * public static class PaymentParticipant {
 *     
 *     @TryMethod
 *     public Mono<PaymentReservation> tryReservePayment(PaymentRequest request) {
 *         // Implementation
 *     }
 *     
 *     @ConfirmMethod
 *     public Mono<Void> confirmPayment(@FromTry PaymentReservation reservation) {
 *         // Implementation
 *     }
 *     
 *     @CancelMethod
 *     public Mono<Void> cancelPayment(@FromTry PaymentReservation reservation) {
 *         // Implementation
 *     }
 * }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TccEvent {
    
    /**
     * The topic or destination where events should be published.
     * 
     * @return the topic name
     */
    String topic();
    
    /**
     * The event type identifier for this participant.
     * This will be included in the event envelope for filtering and routing.
     * 
     * @return the event type
     */
    String eventType();
    
    /**
     * Optional key for partitioning events in messaging systems.
     * Supports SpEL expressions with access to participant context.
     * 
     * <p>Example: {@code "#{correlationId}"} or {@code "#{participantId}"}
     * 
     * @return the partition key expression
     */
    String key() default "";
}
