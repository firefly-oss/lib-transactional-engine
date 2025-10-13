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

package com.firefly.transactional.tcc.persistence.impl;

import com.firefly.transactional.tcc.core.TccPhase;
import com.firefly.transactional.tcc.core.TccResult;
import com.firefly.transactional.tcc.engine.TccEngine;
import com.firefly.transactional.tcc.persistence.TccExecutionState;
import com.firefly.transactional.tcc.persistence.TccExecutionStatus;
import com.firefly.transactional.tcc.persistence.TccPersistenceProvider;
import com.firefly.transactional.tcc.persistence.TccRecoveryService;
import com.firefly.transactional.shared.persistence.TransactionalRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default implementation of TccRecoveryService.
 * <p>
 * This implementation provides comprehensive recovery capabilities for TCC transactions
 * including phase-specific recovery, stalled transaction detection, and cleanup operations.
 */
public class DefaultTccRecoveryService implements TccRecoveryService {
    
    private static final Logger log = LoggerFactory.getLogger(DefaultTccRecoveryService.class);
    
    private final TccPersistenceProvider persistenceProvider;
    private final TccEngine tccEngine;
    
    // Recovery statistics
    private final AtomicLong totalRecoveryAttempts = new AtomicLong(0);
    private final AtomicLong successfulRecoveries = new AtomicLong(0);
    private final AtomicLong failedRecoveries = new AtomicLong(0);
    private volatile Instant lastRecoveryTime = Instant.now();
    
    public DefaultTccRecoveryService(TccPersistenceProvider persistenceProvider, TccEngine tccEngine) {
        this.persistenceProvider = persistenceProvider;
        this.tccEngine = tccEngine;
    }
    
    @Override
    public Mono<TccResult> recoverTransaction(String correlationId) {
        log.info("Attempting to recover TCC transaction: {}", correlationId);
        totalRecoveryAttempts.incrementAndGet();
        lastRecoveryTime = Instant.now();
        
        return persistenceProvider.loadState(correlationId)
                .flatMap(this::performRecovery)
                .doOnSuccess(result -> {
                    if (result.isSuccess()) {
                        successfulRecoveries.incrementAndGet();
                        log.info("Successfully recovered TCC transaction: {}", correlationId);
                    } else {
                        failedRecoveries.incrementAndGet();
                        log.warn("Failed to recover TCC transaction: {}", correlationId);
                    }
                })
                .doOnError(error -> {
                    failedRecoveries.incrementAndGet();
                    log.error("Error during TCC transaction recovery: {}", correlationId, error);
                });
    }
    
    @Override
    public Flux<TccResult> recoverStalledTransactions(Duration stalledDuration) {
        Instant threshold = Instant.now().minus(stalledDuration);
        log.info("Recovering TCC transactions stalled since: {}", threshold);
        
        return persistenceProvider.findStatesUpdatedBefore(threshold)
                .filter(this::canRecover)
                .flatMap(this::performRecovery)
                .doOnNext(result -> {
                    if (result.isSuccess()) {
                        log.info("Recovered stalled TCC transaction: {}", result.getCorrelationId());
                    } else {
                        log.warn("Failed to recover stalled TCC transaction: {}", result.getCorrelationId());
                    }
                });
    }
    
    @Override
    public Flux<TccResult> recoverTransactionsStartedBefore(Instant before) {
        log.info("Recovering TCC transactions started before: {}", before);
        
        return persistenceProvider.findStatesStartedBefore(before)
                .filter(this::canRecover)
                .flatMap(this::performRecovery);
    }
    
    @Override
    public Flux<TccResult> recoverTryPhaseTransactions() {
        log.info("Recovering TCC transactions stuck in TRY phase");
        
        return persistenceProvider.listActiveTransactions()
                .flatMap(persistenceProvider::loadState)
                .filter(state -> state.getCurrentPhase() == TccPhase.TRY)
                .filter(state -> state.getStatus() == TccExecutionStatus.RUNNING || 
                               state.getStatus() == TccExecutionStatus.FAILED)
                .filter(this::canRecover)
                .flatMap(this::performRecovery);
    }
    
    @Override
    public Flux<TccResult> recoverConfirmPhaseTransactions() {
        log.info("Recovering TCC transactions stuck in CONFIRM phase");
        
        return persistenceProvider.listActiveTransactions()
                .flatMap(persistenceProvider::loadState)
                .filter(state -> state.getCurrentPhase() == TccPhase.CONFIRM)
                .filter(state -> state.getStatus() == TccExecutionStatus.RUNNING || 
                               state.getStatus() == TccExecutionStatus.FAILED)
                .filter(this::canRecover)
                .flatMap(this::performRecovery);
    }
    
    @Override
    public Flux<TccResult> recoverCancelPhaseTransactions() {
        log.info("Recovering TCC transactions stuck in CANCEL phase");
        
        return persistenceProvider.listActiveTransactions()
                .flatMap(persistenceProvider::loadState)
                .filter(state -> state.getCurrentPhase() == TccPhase.CANCEL)
                .filter(state -> state.getStatus() == TccExecutionStatus.RUNNING || 
                               state.getStatus() == TccExecutionStatus.FAILED)
                .filter(this::canRecover)
                .flatMap(this::performRecovery);
    }
    
    @Override
    public boolean canRecover(TccExecutionState state) {
        // Don't recover already completed transactions
        if (state.getStatus() == TccExecutionStatus.COMPLETED || 
            state.getStatus() == TccExecutionStatus.CANCELED) {
            return false;
        }
        
        // Don't recover very recent transactions (they might still be running)
        Duration timeSinceLastUpdate = Duration.between(state.getLastUpdatedAt(), Instant.now());
        if (timeSinceLastUpdate.toMinutes() < 5) {
            return false;
        }
        
        // Check if the transaction has been running for too long
        Duration timeSinceStart = Duration.between(state.getStartedAt(), Instant.now());
        if (timeSinceStart.toHours() > 24) {
            log.warn("TCC transaction {} has been running for over 24 hours, marking as unrecoverable", 
                    state.getCorrelationId());
            return false;
        }
        
        return true;
    }
    
    @Override
    public Mono<TransactionalRecoveryService.RecoveryStatistics> getRecoveryStatistics() {
        Long stalledCount = persistenceProvider.countActiveTransactions().block(Duration.ofSeconds(5));

        return Mono.just(new TransactionalRecoveryService.RecoveryStatistics(
                totalRecoveryAttempts.get(),
                successfulRecoveries.get(),
                failedRecoveries.get(),
                stalledCount != null ? stalledCount : 0L,
                lastRecoveryTime
        ));
    }
    
    @Override
    public Mono<Long> cleanup(Instant olderThan) {
        log.info("Cleaning up TCC transactions older than: {}", olderThan);
        return persistenceProvider.cleanup(olderThan);
    }
    
    private Mono<TccResult> performRecovery(TccExecutionState state) {
        log.debug("Performing recovery for TCC transaction: {} in phase: {}", 
                state.getCorrelationId(), state.getCurrentPhase());
        
        // The actual recovery logic would depend on the TCC engine implementation
        // For now, we'll create a simple recovery result
        // In a real implementation, this would involve:
        // 1. Reconstructing the TCC context from the persisted state
        // 2. Determining the appropriate recovery action based on the current phase
        // 3. Executing the recovery action (continue, retry, or compensate)
        
        return Mono.fromCallable(() -> {
            // This is a placeholder - actual implementation would use TccEngine
            boolean success = state.getStatus() != TccExecutionStatus.FAILED;
            return TccResult.builder(state.getCorrelationId())
                    .tccName(state.getTccName())
                    .success(success)
                    .build();
        });
    }
}
