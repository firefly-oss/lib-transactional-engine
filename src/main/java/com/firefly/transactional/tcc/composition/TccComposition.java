/*
 * Copyright 2024 Firefly Authors
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

package com.firefly.transactional.tcc.composition;

import com.firefly.transactional.tcc.core.TccInputs;
import com.firefly.transactional.shared.engine.CompensationPolicy;

import java.util.*;
import java.util.function.Function;

/**
 * Immutable definition of a TCC composition workflow.
 * <p>
 * A TccComposition represents a coordinated execution of multiple TCC coordinators with
 * defined dependencies, data flow, and execution patterns. It serves as the
 * blueprint for the TccCompositionExecutionOrchestrator to execute the workflow.
 * <p>
 * Unlike SAGA compositions which handle forward execution and compensation,
 * TCC compositions manage the three-phase protocol (TRY, CONFIRM, CANCEL) across
 * multiple TCC coordinators with proper dependency management.
 */
public class TccComposition {
    
    public final String name;
    public final CompensationPolicy compensationPolicy;
    public final Map<String, CompositionTcc> tccs;
    public final List<String> executionOrder;
    
    /**
     * Creates a new TCC composition.
     * 
     * @param name the composition name
     * @param compensationPolicy the compensation policy for the composition
     * @param tccs the TCC coordinators in the composition
     * @param executionOrder the execution order of TCC coordinators
     */
    public TccComposition(String name, 
                          CompensationPolicy compensationPolicy,
                          Map<String, CompositionTcc> tccs,
                          List<String> executionOrder) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.compensationPolicy = Objects.requireNonNull(compensationPolicy, "compensationPolicy cannot be null");
        this.tccs = Collections.unmodifiableMap(new LinkedHashMap<>(tccs));
        this.executionOrder = Collections.unmodifiableList(new ArrayList<>(executionOrder));
    }
    
    /**
     * Represents a TCC coordinator within a composition with its execution configuration.
     */
    public static class CompositionTcc {
        public final String tccName;
        public final String compositionId;
        public final Set<String> dependencies;
        public final Set<String> parallelWith;
        public final TccInputs inputs;
        public final Map<String, DataMapping> dataFromTccs;
        public final Function<TccCompositionContext, Boolean> executionCondition;
        public final boolean optional;
        public final int timeoutMs;
        
        public CompositionTcc(String tccName,
                              String compositionId,
                              Set<String> dependencies,
                              Set<String> parallelWith,
                              TccInputs inputs,
                              Map<String, DataMapping> dataFromTccs,
                              Function<TccCompositionContext, Boolean> executionCondition,
                              boolean optional,
                              int timeoutMs) {
            this.tccName = Objects.requireNonNull(tccName, "tccName cannot be null");
            this.compositionId = Objects.requireNonNull(compositionId, "compositionId cannot be null");
            this.dependencies = Collections.unmodifiableSet(new HashSet<>(dependencies));
            this.parallelWith = Collections.unmodifiableSet(new HashSet<>(parallelWith));
            this.inputs = inputs;
            this.dataFromTccs = Collections.unmodifiableMap(new HashMap<>(dataFromTccs));
            this.executionCondition = executionCondition;
            this.optional = optional;
            this.timeoutMs = timeoutMs;
        }
        
        /**
         * Checks if this TCC can be executed given the current completion state.
         * 
         * @param completedTccs the set of completed TCC IDs
         * @return true if this TCC can be executed
         */
        public boolean canExecute(Set<String> completedTccs) {
            return dependencies.isEmpty() || completedTccs.containsAll(dependencies);
        }
    }
    
    /**
     * Represents data mapping from one TCC to another within a composition.
     */
    public static class DataMapping {
        public final String sourceTccId;
        public final String sourceParticipantId;
        public final String sourceKey;
        public final String targetKey;
        
        public DataMapping(String sourceTccId, String sourceParticipantId, String sourceKey, String targetKey) {
            this.sourceTccId = Objects.requireNonNull(sourceTccId, "sourceTccId cannot be null");
            this.sourceParticipantId = Objects.requireNonNull(sourceParticipantId, "sourceParticipantId cannot be null");
            this.sourceKey = Objects.requireNonNull(sourceKey, "sourceKey cannot be null");
            this.targetKey = Objects.requireNonNull(targetKey, "targetKey cannot be null");
        }
    }
    
    /**
     * Gets the TCC coordinators that can be executed in parallel at the current execution layer.
     * 
     * @param completedTccs the set of already completed TCC IDs
     * @return a list of TCC IDs that can be executed in parallel
     */
    public List<String> getExecutableParallelTccs(Set<String> completedTccs) {
        List<String> executable = new ArrayList<>();
        
        for (String tccId : executionOrder) {
            if (completedTccs.contains(tccId)) {
                continue;
            }
            
            CompositionTcc tcc = tccs.get(tccId);
            if (tcc == null) {
                continue;
            }
            
            // Check if all dependencies are satisfied
            boolean dependenciesSatisfied = tcc.dependencies.isEmpty() || 
                                          completedTccs.containsAll(tcc.dependencies);
            
            if (dependenciesSatisfied) {
                executable.add(tccId);
                
                // Add all TCCs that should execute in parallel with this one
                for (String parallelTccId : tcc.parallelWith) {
                    CompositionTcc parallelTcc = tccs.get(parallelTccId);
                    if (parallelTcc != null && 
                        !completedTccs.contains(parallelTccId) && 
                        !executable.contains(parallelTccId) &&
                        parallelTcc.canExecute(completedTccs)) {
                        executable.add(parallelTccId);
                    }
                }
            }
        }
        
        return executable;
    }
    
    /**
     * Checks if there is remaining work to be done in the composition.
     * 
     * @param completedTccs the set of completed TCC IDs
     * @return true if there are TCCs that haven't been executed yet
     */
    public boolean hasRemainingWork(Set<String> completedTccs) {
        return tccs.keySet().stream().anyMatch(tccId -> !completedTccs.contains(tccId));
    }
    
    /**
     * Gets all TCC IDs in the composition.
     * 
     * @return an unmodifiable set of TCC IDs
     */
    public Set<String> getAllTccIds() {
        return tccs.keySet();
    }
    
    /**
     * Gets a TCC by its composition ID.
     * 
     * @param tccId the TCC composition ID
     * @return the CompositionTcc, or null if not found
     */
    public CompositionTcc getTcc(String tccId) {
        return tccs.get(tccId);
    }
    
    /**
     * Gets the total number of TCCs in the composition.
     * 
     * @return the total TCC count
     */
    public int getTotalTccCount() {
        return tccs.size();
    }
}
