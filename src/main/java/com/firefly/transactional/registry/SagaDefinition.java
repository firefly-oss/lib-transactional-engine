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


package com.firefly.transactional.registry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable metadata for a discovered Saga orchestrator.
 * Holds the saga name, the original Spring bean (possibly a proxy) and the
 * unwrapped target instance, plus an ordered map of its steps.
 */
public class SagaDefinition {
    public final String name;
    public final Object bean; // original Spring bean (possibly proxy)
    public final Object target; // unwrapped target object for direct invocation
    /** Optional cap for concurrent steps within a layer. 0 means unbounded. */
    public final int layerConcurrency;
    public final Map<String, StepDefinition> steps = new LinkedHashMap<>();

    public SagaDefinition(String name, Object bean, Object target, int layerConcurrency) {
        this.name = name;
        this.bean = bean;
        this.target = target;
        this.layerConcurrency = layerConcurrency;
    }
}
