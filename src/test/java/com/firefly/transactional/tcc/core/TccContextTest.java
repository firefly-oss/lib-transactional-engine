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

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TccContext.
 */
class TccContextTest {
    
    @Test
    void shouldCreateContextWithCorrelationId() {
        TccContext context = new TccContext("test-correlation-id");
        
        assertEquals("test-correlation-id", context.correlationId());
        assertEquals(TccPhase.TRY, context.getCurrentPhase());
    }
    
    @Test
    void shouldStoreAndRetrieveTryResults() {
        TccContext context = new TccContext("test-id");
        
        context.putTryResult("participant1", "result1");
        context.putTryResult("participant2", 42);
        
        assertEquals("result1", context.getTryResult("participant1"));
        assertEquals(42, context.getTryResult("participant2"));
    }
    
    @Test
    void shouldRetrieveTryResultsWithTypeChecking() {
        TccContext context = new TccContext("test-id");
        
        context.putTryResult("participant1", "string-result");
        context.putTryResult("participant2", 123);
        
        Optional<String> stringResult = context.getTryResult("participant1", String.class);
        assertTrue(stringResult.isPresent());
        assertEquals("string-result", stringResult.get());
        
        Optional<Integer> intResult = context.getTryResult("participant2", Integer.class);
        assertTrue(intResult.isPresent());
        assertEquals(123, intResult.get());
        
        // Wrong type should return empty
        Optional<Integer> wrongType = context.getTryResult("participant1", Integer.class);
        assertFalse(wrongType.isPresent());
    }
    
    @Test
    void shouldGetAllTryResults() {
        TccContext context = new TccContext("test-id");
        
        context.putTryResult("p1", "r1");
        context.putTryResult("p2", "r2");
        
        Map<String, Object> allResults = context.getAllTryResults();
        assertEquals(2, allResults.size());
        assertEquals("r1", allResults.get("p1"));
        assertEquals("r2", allResults.get("p2"));
    }
    
    @Test
    void shouldManagePhaseTransitions() {
        TccContext context = new TccContext("test-id");
        
        assertEquals(TccPhase.TRY, context.getCurrentPhase());
        
        context.setCurrentPhase(TccPhase.CONFIRM);
        assertEquals(TccPhase.CONFIRM, context.getCurrentPhase());
        
        context.setCurrentPhase(TccPhase.CANCEL);
        assertEquals(TccPhase.CANCEL, context.getCurrentPhase());
    }
    
    @Test
    void shouldManageHeadersAndVariables() {
        TccContext context = new TccContext("test-id");
        
        // Headers
        context.setHeader("X-User-Id", "user123");
        context.setHeader("X-Request-Id", "req456");
        
        assertEquals("user123", context.getHeader("X-User-Id"));
        assertEquals("req456", context.getHeader("X-Request-Id"));
        
        Map<String, String> headers = context.getHeaders();
        assertEquals(2, headers.size());
        
        // Variables
        context.setVariable("key1", "value1");
        context.setVariable("key2", 42);
        
        assertEquals("value1", context.getVariable("key1"));
        assertEquals(42, context.getVariable("key2"));
        
        Map<String, Object> variables = context.getVariables();
        assertEquals(2, variables.size());
    }
    
    @Test
    void shouldManageTccName() {
        TccContext context = new TccContext("test-id");
        
        context.setTccName("order-payment");
        assertEquals("order-payment", context.getTccName());
    }
    
    @Test
    void shouldProvideAccessToUnderlyingSagaContext() {
        TccContext context = new TccContext("test-id");
        
        assertNotNull(context.getSagaContext());
        assertEquals("test-id", context.getSagaContext().correlationId());
    }
    
    @Test
    void shouldStoreResultsInBothTccAndSagaContext() {
        TccContext context = new TccContext("test-id");
        
        context.putTryResult("participant1", "result1");
        
        // Should be available in TCC context
        assertEquals("result1", context.getTryResult("participant1"));
        
        // Should also be available in underlying SAGA context
        assertEquals("result1", context.getSagaContext().getResult("participant1"));
    }
}

