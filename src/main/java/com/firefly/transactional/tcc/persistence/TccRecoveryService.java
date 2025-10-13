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

package com.firefly.transactional.tcc.persistence;

import com.firefly.transactional.shared.persistence.TransactionalRecoveryService;
import com.firefly.transactional.tcc.core.TccResult;
import reactor.core.publisher.Flux;

/**
 * TCC-specific recovery service interface.
 * <p>
 * This interface extends the generic TransactionalRecoveryService with
 * TCC-specific recovery capabilities. It provides type-safe access to
 * TCC execution states and results while leveraging the common recovery
 * infrastructure.
 */
public interface TccRecoveryService extends TransactionalRecoveryService<TccExecutionState, TccResult> {
    
    /**
     * Recovers TCC transactions that are stuck in the TRY phase.
     * These transactions may have partially completed participants
     * and need to be either confirmed or canceled.
     *
     * @return a Flux of recovery results
     */
    Flux<TccResult> recoverTryPhaseTransactions();
    
    /**
     * Recovers TCC transactions that are stuck in the CONFIRM phase.
     * These transactions have successfully completed the TRY phase
     * but failed during confirmation.
     *
     * @return a Flux of recovery results
     */
    Flux<TccResult> recoverConfirmPhaseTransactions();
    
    /**
     * Recovers TCC transactions that are stuck in the CANCEL phase.
     * These transactions failed during the TRY phase and need to
     * complete the cancellation of all participants.
     *
     * @return a Flux of recovery results
     */
    Flux<TccResult> recoverCancelPhaseTransactions();
    
    /**
     * Checks if a TCC transaction in the given state can be safely recovered.
     * This method considers the current phase, participant statuses, and
     * timing constraints to determine recoverability.
     *
     * @param state the TCC execution state to check
     * @return true if the transaction can be recovered, false otherwise
     */
    @Override
    boolean canRecover(TccExecutionState state);
}
