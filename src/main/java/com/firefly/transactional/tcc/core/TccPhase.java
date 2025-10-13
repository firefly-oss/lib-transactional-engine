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

package com.firefly.transactional.tcc.core;

/**
 * Represents the current phase of a TCC transaction.
 * <p>
 * The TCC pattern consists of three phases:
 * <ul>
 *   <li><b>TRY</b>: Reserve resources and perform preliminary operations</li>
 *   <li><b>CONFIRM</b>: Commit the reserved resources (if all try operations succeed)</li>
 *   <li><b>CANCEL</b>: Release the reserved resources (if any try operation fails)</li>
 * </ul>
 */
public enum TccPhase {
    
    /**
     * Try phase - reserving resources and performing preliminary operations.
     * This is the first phase where all participants attempt to reserve
     * the necessary resources for the transaction.
     */
    TRY,
    
    /**
     * Confirm phase - committing the reserved resources.
     * This phase is executed only if all participants' try operations succeed.
     * It makes the changes permanent.
     */
    CONFIRM,
    
    /**
     * Cancel phase - releasing the reserved resources.
     * This phase is executed if any participant's try operation fails.
     * It rolls back the changes made during the try phase.
     */
    CANCEL;
    
    /**
     * Checks if this is the try phase.
     *
     * @return true if this is the TRY phase
     */
    public boolean isTry() {
        return this == TRY;
    }
    
    /**
     * Checks if this is the confirm phase.
     *
     * @return true if this is the CONFIRM phase
     */
    public boolean isConfirm() {
        return this == CONFIRM;
    }
    
    /**
     * Checks if this is the cancel phase.
     *
     * @return true if this is the CANCEL phase
     */
    public boolean isCancel() {
        return this == CANCEL;
    }
}

