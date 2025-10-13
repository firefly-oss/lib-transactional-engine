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

import com.firefly.transactional.shared.persistence.TransactionalPersistenceProvider;

/**
 * TCC-specific persistence provider interface.
 * <p>
 * This interface extends the generic TransactionalPersistenceProvider with
 * TCC-specific state management capabilities. It provides type-safe access
 * to TCC execution states while leveraging the common persistence infrastructure.
 */
public interface TccPersistenceProvider extends TransactionalPersistenceProvider<TccExecutionState> {
    
    // This interface inherits all methods from TransactionalPersistenceProvider
    // with TccExecutionState as the type parameter.
    // 
    // Additional TCC-specific methods can be added here if needed in the future,
    // such as:
    // - Finding transactions by TCC phase
    // - Querying by participant status
    // - TCC-specific recovery queries
}
