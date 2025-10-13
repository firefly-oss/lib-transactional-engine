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

package com.firefly.transactional.saga.persistence.impl;

import com.firefly.transactional.saga.core.SagaContext;
import com.firefly.transactional.saga.engine.SagaEngine;
import com.firefly.transactional.saga.engine.StepInputs;
import com.firefly.transactional.saga.registry.SagaRegistry;
import com.firefly.transactional.saga.config.SagaEngineProperties;
import com.firefly.transactional.saga.persistence.SagaExecutionState;
import com.firefly.transactional.saga.persistence.SagaPersistenceProvider;
import com.firefly.transactional.saga.persistence.SagaRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of SagaRecoveryService.
 * <p>
 * This service handles the recovery of in-flight sagas after application restarts
 * by working with the persistence provider to identify interrupted sagas and
 * resume their execution from the last checkpoint.
 * <p>
 * Recovery process:
 * <ol>
 *   <li>Scan persistence store for in-flight sagas</li>
 *   <li>Validate saga definitions are still available</li>
 *   <li>Reconstruct execution context from persisted state</li>
 *   <li>Resume execution from the last completed step</li>
 *   <li>Handle recovery failures gracefully</li>
 * </ol>
 * <p>
 * The service supports different recovery strategies and provides detailed
 * reporting of recovery results for monitoring and troubleshooting.
 */
public class DefaultSagaRecoveryService implements SagaRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSagaRecoveryService.class);

    private final SagaPersistenceProvider persistenceProvider;
    private final SagaEngine sagaEngine;
    private final SagaRegistry sagaRegistry;
    private final SagaEngineProperties.PersistenceProperties persistenceProperties;

    /**
     * Creates a new recovery service.
     *
     * @param persistenceProvider the persistence provider for accessing saga state
     * @param sagaEngine the saga engine for resuming execution
     * @param sagaRegistry the saga registry for validating saga definitions
     * @param persistenceProperties the persistence configuration
     */
    public DefaultSagaRecoveryService(SagaPersistenceProvider persistenceProvider,
                                     SagaEngine sagaEngine,
                                     SagaRegistry sagaRegistry,
                                     SagaEngineProperties.PersistenceProperties persistenceProperties) {
        this.persistenceProvider = persistenceProvider;
        this.sagaEngine = sagaEngine;
        this.sagaRegistry = sagaRegistry;
        this.persistenceProperties = persistenceProperties;
    }

    @Override
    public Mono<RecoveryResult> recoverInFlightSagas() {
        Instant startTime = Instant.now();
        AtomicInteger totalFound = new AtomicInteger(0);
        AtomicInteger recovered = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicInteger skipped = new AtomicInteger(0);

        log.info("Starting recovery of in-flight sagas");

        // Only recover sagas that are stale (older than maxSagaAge)
        Instant staleThreshold = Instant.now().minus(persistenceProperties.getMaxSagaAge());
        log.debug("Using stale threshold: {} (maxSagaAge: {})", staleThreshold, persistenceProperties.getMaxSagaAge());

        return persistenceProvider.getStaleSagas(staleThreshold)
                .doOnNext(state -> {
                    totalFound.incrementAndGet();
                    log.debug("Found stale saga for recovery: {} ({})",
                            state.getCorrelationId(), state.getSagaName());
                })
                .flatMap(this::recoverSingleSaga)
                .doOnNext(result -> {
                    switch (result.getStatus()) {
                        case SUCCESS -> recovered.incrementAndGet();
                        case FAILED -> failed.incrementAndGet();
                        case SKIPPED -> skipped.incrementAndGet();
                        default -> { /* no-op */ }
                    }
                })
                .then(Mono.fromCallable(() -> {
                    Duration recoveryTime = Duration.between(startTime, Instant.now());
                    RecoveryResult result = new RecoveryResult(
                            totalFound.get(),
                            recovered.get(),
                            failed.get(),
                            skipped.get(),
                            recoveryTime
                    );
                    
                    log.info("Saga recovery completed: {}", result);
                    return result;
                }));
    }

    @Override
    public Mono<SingleRecoveryResult> recoverSaga(String correlationId) {
        log.debug("Attempting to recover specific saga: {}", correlationId);
        
        return persistenceProvider.getSagaState(correlationId)
                .flatMap(optionalState -> {
                    if (optionalState.isPresent()) {
                        return recoverSingleSaga(optionalState.get());
                    } else {
                        return Mono.just(new SingleRecoveryResult(
                                correlationId,
                                SingleRecoveryResult.RecoveryStatus.NOT_FOUND,
                                "Saga state not found in persistence store",
                                null
                        ));
                    }
                });
    }

    @Override
    public Flux<StaleSagaInfo> identifyStaleSagas(Duration staleThreshold) {
        Instant cutoff = Instant.now().minus(staleThreshold);
        log.debug("Identifying stale sagas older than: {}", cutoff);
        
        return persistenceProvider.getStaleSagas(cutoff)
                .map(state -> new StaleSagaInfo(
                        state.getCorrelationId(),
                        state.getSagaName(),
                        state.getStatus(),
                        state.getLastUpdatedAt(),
                        Duration.between(state.getLastUpdatedAt(), Instant.now())
                ))
                .doOnNext(info -> log.debug("Found stale saga: {} (age: {})", 
                        info.getCorrelationId(), info.getAge()));
    }

    @Override
    public Mono<Long> cancelStaleSagas(Duration maxAge) {
        AtomicLong cancelledCount = new AtomicLong(0);
        
        return identifyStaleSagas(maxAge)
                .flatMap(staleSaga -> {
                    log.info("Cancelling stale saga: {} (age: {})", 
                            staleSaga.getCorrelationId(), staleSaga.getAge());
                    
                    return persistenceProvider.markSagaCompleted(staleSaga.getCorrelationId(), false)
                            .doOnSuccess(v -> cancelledCount.incrementAndGet())
                            .onErrorContinue((error, obj) -> 
                                    log.warn("Failed to cancel stale saga: {}", staleSaga.getCorrelationId(), error));
                })
                .then(Mono.fromCallable(cancelledCount::get))
                .doOnSuccess(count -> log.info("Cancelled {} stale sagas", count));
    }

    @Override
    public Mono<ValidationResult> validatePersistedState() {
        AtomicInteger totalChecked = new AtomicInteger(0);
        AtomicInteger corruptedStates = new AtomicInteger(0);
        StringBuilder details = new StringBuilder();

        return persistenceProvider.getInFlightSagas()
                .doOnNext(state -> totalChecked.incrementAndGet())
                .flatMap(state -> validateSagaState(state)
                        .doOnNext(isValid -> {
                            if (!isValid) {
                                corruptedStates.incrementAndGet();
                                details.append("Corrupted state: ").append(state.getCorrelationId()).append("; ");
                            }
                        })
                        .onErrorReturn(false)
                )
                .then(Mono.fromCallable(() -> {
                    boolean allValid = corruptedStates.get() == 0;
                    return new ValidationResult(
                            allValid,
                            totalChecked.get(),
                            corruptedStates.get(),
                            details.toString()
                    );
                }))
                .doOnSuccess(result -> log.info("State validation completed: {}", result));
    }

    /**
     * Recovers a single saga execution.
     */
    private Mono<SingleRecoveryResult> recoverSingleSaga(SagaExecutionState state) {
        String correlationId = state.getCorrelationId();
        String sagaName = state.getSagaName();
        
        try {
            // Validate that the saga definition still exists
            if (!sagaRegistry.hasSaga(sagaName)) {
                return Mono.just(new SingleRecoveryResult(
                        correlationId,
                        SingleRecoveryResult.RecoveryStatus.SKIPPED,
                        "Saga definition not found: " + sagaName,
                        null
                ));
            }

            // Check if saga is actually recoverable
            if (!state.getStatus().isRecoverable()) {
                return Mono.just(new SingleRecoveryResult(
                        correlationId,
                        SingleRecoveryResult.RecoveryStatus.SKIPPED,
                        "Saga is not in recoverable state: " + state.getStatus(),
                        null
                ));
            }

            // Reconstruct the saga context
            SagaContext recoveredContext = state.toSagaContext();

            // Reconstruct step inputs from persisted state
            Map<String, Object> persistedInputs = state.getStepInputs();
            StepInputs stepInputs = persistedInputs != null && !persistedInputs.isEmpty()
                    ? StepInputs.of(persistedInputs)
                    : StepInputs.empty();

            // Resume saga execution
            log.info("Resuming saga execution: {} ({}) with {} step inputs",
                    correlationId, sagaName, persistedInputs != null ? persistedInputs.size() : 0);

            return sagaEngine.execute(sagaName, stepInputs, recoveredContext)
                    .map(result -> new SingleRecoveryResult(
                            correlationId,
                            SingleRecoveryResult.RecoveryStatus.SUCCESS,
                            "Saga recovered and resumed successfully",
                            null
                    ))
                    .onErrorResume(error -> {
                        log.error("Failed to recover saga: {}", correlationId, error);
                        return Mono.just(new SingleRecoveryResult(
                                correlationId,
                                SingleRecoveryResult.RecoveryStatus.FAILED,
                                "Recovery failed: " + error.getMessage(),
                                error
                        ));
                    });

        } catch (Exception e) {
            log.error("Error during saga recovery setup for: {}", correlationId, e);
            return Mono.just(new SingleRecoveryResult(
                    correlationId,
                    SingleRecoveryResult.RecoveryStatus.FAILED,
                    "Recovery setup failed: " + e.getMessage(),
                    e
            ));
        }
    }

    /**
     * Validates the integrity of a saga state.
     */
    private Mono<Boolean> validateSagaState(SagaExecutionState state) {
        return Mono.fromCallable(() -> {
            try {
                // Basic validation checks
                if (state.getCorrelationId() == null || state.getCorrelationId().isEmpty()) {
                    return false;
                }
                
                if (state.getSagaName() == null || state.getSagaName().isEmpty()) {
                    return false;
                }
                
                if (state.getStatus() == null) {
                    return false;
                }
                
                if (state.getContext() == null) {
                    return false;
                }
                
                // Try to convert to SagaContext to ensure it's valid
                SagaContext context = state.toSagaContext();
                if (!state.getCorrelationId().equals(context.correlationId())) {
                    return false;
                }
                
                return true;
                
            } catch (Exception e) {
                log.warn("Saga state validation failed for: {}", state.getCorrelationId(), e);
                return false;
            }
        });
    }
}
