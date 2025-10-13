/*
 * Copyright (c) 2023 Firefly Authors. All rights reserved.
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

package com.firefly.transactional.shared.observability;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Spring Boot Actuator endpoint for exposing transactional metrics across all patterns.
 * Available at /actuator/transactional-metrics when Spring Boot Actuator is on the classpath.
 */
@Component
@Endpoint(id = "transactional-metrics")
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
public class TransactionalMetricsEndpoint {

    private final TransactionalMetricsCollector metricsCollector;

    public TransactionalMetricsEndpoint(TransactionalMetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * Returns overall metrics across all transaction types.
     */
    @ReadOperation
    public Map<String, Object> metrics() {
        return metricsCollector.getOverallMetrics().toMap();
    }

    /**
     * Returns metrics for a specific transaction type.
     * 
     * @param transactionType the transaction type (e.g., "SAGA", "TCC")
     */
    @ReadOperation
    public Map<String, Object> metricsForType(@Selector String transactionType) {
        return metricsCollector.getMetrics(transactionType.toUpperCase()).toMap();
    }
}
