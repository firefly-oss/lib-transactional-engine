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

package com.firefly.transactional.tcc.events;

import com.firefly.transactional.saga.events.StepEventPublisher;
import reactor.core.publisher.Mono;

/**
 * Port interface for publishing TCC transaction events.
 * 
 * <p>Microservices must implement this interface to handle event publishing
 * through their chosen messaging infrastructure (Kafka, SQS, RabbitMQ, etc.).
 * 
 * <p>The library provides a default no-op implementation when no custom
 * publisher is configured.
 * 
 * <p>This interface is similar to {@link StepEventPublisher}
 * but specifically designed for TCC transaction events.
 */
public interface TccEventPublisher {
    
    /**
     * Publishes a TCC event to the configured messaging infrastructure.
     * 
     * @param event The TCC event to publish
     * @return A Mono that completes when the event is successfully published
     */
    Mono<Void> publish(TccEventEnvelope event);
}
