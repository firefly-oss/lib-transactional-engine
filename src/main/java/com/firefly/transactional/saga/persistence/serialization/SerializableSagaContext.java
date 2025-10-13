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

package com.firefly.transactional.saga.persistence.serialization;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.shared.core.StepStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serializable representation of SagaContext for persistence.
 * <p>
 * This class provides a JSON-serializable version of SagaContext that can be
 * safely persisted and reconstructed. It handles the conversion between the
 * runtime SagaContext and its serializable form.
 * <p>
 * The serializable context captures all the essential state needed to
 * reconstruct a SagaContext during recovery, including:
 * <ul>
 *   <li>Basic identification and metadata</li>
 *   <li>Headers and variables</li>
 *   <li>Step execution state and results</li>
 *   <li>Compensation state</li>
 *   <li>Topology information</li>
 * </ul>
 */
public class SerializableSagaContext {

    private final String correlationId;
    private final String sagaName;
    private final Map<String, String> headers;
    private final Map<String, Object> variables;
    private final Map<String, Object> stepResults;
    private final Map<String, StepStatus> stepStatuses;
    private final Map<String, Integer> stepAttempts;
    private final Map<String, Long> stepLatenciesMs;
    private final Map<String, Instant> stepStartedAt;
    private final Map<String, Object> compensationResults;
    private final Map<String, String> compensationErrors; // Throwable -> String for serialization
    private final Set<String> idempotencyKeys;
    private final Instant startedAt;
    private final List<List<String>> topologyLayers;
    private final Map<String, List<String>> stepDependencies;

    @JsonCreator
    public SerializableSagaContext(
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("sagaName") String sagaName,
            @JsonProperty("headers") Map<String, String> headers,
            @JsonProperty("variables") Map<String, Object> variables,
            @JsonProperty("stepResults") Map<String, Object> stepResults,
            @JsonProperty("stepStatuses") Map<String, StepStatus> stepStatuses,
            @JsonProperty("stepAttempts") Map<String, Integer> stepAttempts,
            @JsonProperty("stepLatenciesMs") Map<String, Long> stepLatenciesMs,
            @JsonProperty("stepStartedAt") Map<String, Instant> stepStartedAt,
            @JsonProperty("compensationResults") Map<String, Object> compensationResults,
            @JsonProperty("compensationErrors") Map<String, String> compensationErrors,
            @JsonProperty("idempotencyKeys") Set<String> idempotencyKeys,
            @JsonProperty("startedAt") Instant startedAt,
            @JsonProperty("topologyLayers") List<List<String>> topologyLayers,
            @JsonProperty("stepDependencies") Map<String, List<String>> stepDependencies) {
        
        this.correlationId = correlationId;
        this.sagaName = sagaName;
        this.headers = headers;
        this.variables = variables;
        this.stepResults = stepResults;
        this.stepStatuses = stepStatuses;
        this.stepAttempts = stepAttempts;
        this.stepLatenciesMs = stepLatenciesMs;
        this.stepStartedAt = stepStartedAt;
        this.compensationResults = compensationResults;
        this.compensationErrors = compensationErrors;
        this.idempotencyKeys = idempotencyKeys;
        this.startedAt = startedAt;
        this.topologyLayers = topologyLayers;
        this.stepDependencies = stepDependencies;
    }

    /**
     * Creates a serializable context from a runtime SagaContext.
     */
    public static SerializableSagaContext fromSagaContext(SagaContext context) {
        return new SerializableSagaContext(
                context.correlationId(),
                context.sagaName(),
                Map.copyOf(context.headers()),
                Map.copyOf(context.variables()),
                Map.copyOf(context.stepResultsView()),
                Map.copyOf(context.stepStatusesView()),
                Map.copyOf(context.stepAttemptsView()),
                Map.copyOf(context.stepLatenciesView()),
                Map.copyOf(context.stepStartedAtView()),
                Map.copyOf(context.compensationResultsView()),
                convertCompensationErrors(context.compensationErrorsView()),
                Set.copyOf(context.idempotencyKeys()),
                context.startedAt(),
                context.topologyLayersView() != null ? List.copyOf(context.topologyLayersView()) : List.of(),
                context.stepDependenciesView() != null ? Map.copyOf(context.stepDependenciesView()) : Map.of()
        );
    }

    /**
     * Converts this serializable context back to a runtime SagaContext.
     */
    public SagaContext toSagaContext() {
        SagaContext context = new SagaContext(correlationId, sagaName);
        
        // Restore headers and variables
        headers.forEach(context::putHeader);
        variables.forEach(context::putVariable);
        
        // Restore step state
        stepResults.forEach(context::putResult);
        stepStatuses.forEach(context::setStatus);
        stepAttempts.forEach((stepId, attempts) -> {
            for (int i = 0; i < attempts; i++) {
                context.incrementAttempts(stepId);
            }
        });
        stepLatenciesMs.forEach(context::setLatency);
        stepStartedAt.forEach(context::markStepStarted);
        
        // Restore compensation state
        compensationResults.forEach(context::putCompensationResult);
        // Note: Compensation errors are converted to RuntimeException for simplicity
        compensationErrors.forEach((stepId, errorMessage) -> {
            context.putCompensationError(stepId, new RuntimeException(errorMessage));
        });
        
        // Restore idempotency keys
        idempotencyKeys.forEach(context::markIdempotent);
        
        // Restore topology information
        if (topologyLayers != null && !topologyLayers.isEmpty()) {
            context.setTopologyLayers(topologyLayers);
        }
        if (stepDependencies != null && !stepDependencies.isEmpty()) {
            context.setStepDependencies(stepDependencies);
        }
        
        return context;
    }

    /**
     * Converts compensation errors from Throwable to String for serialization.
     */
    private static Map<String, String> convertCompensationErrors(Map<String, Throwable> errors) {
        if (errors == null || errors.isEmpty()) {
            return Map.of();
        }
        
        return errors.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            Throwable throwable = entry.getValue();
                            return throwable != null ? throwable.getMessage() : "Unknown error";
                        }
                ));
    }



    // Getters for JSON serialization
    public String getCorrelationId() { return correlationId; }
    public String getSagaName() { return sagaName; }
    public Map<String, String> getHeaders() { return headers; }
    public Map<String, Object> getVariables() { return variables; }
    public Map<String, Object> getStepResults() { return stepResults; }
    public Map<String, StepStatus> getStepStatuses() { return stepStatuses; }
    public Map<String, Integer> getStepAttempts() { return stepAttempts; }
    public Map<String, Long> getStepLatenciesMs() { return stepLatenciesMs; }
    public Map<String, Instant> getStepStartedAt() { return stepStartedAt; }
    public Map<String, Object> getCompensationResults() { return compensationResults; }
    public Map<String, String> getCompensationErrors() { return compensationErrors; }
    public Set<String> getIdempotencyKeys() { return idempotencyKeys; }
    public Instant getStartedAt() { return startedAt; }
    public List<List<String>> getTopologyLayers() { return topologyLayers; }
    public Map<String, List<String>> getStepDependencies() { return stepDependencies; }
}
