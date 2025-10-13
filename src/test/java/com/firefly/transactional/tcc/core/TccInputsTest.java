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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TccInputs.
 */
class TccInputsTest {
    
    @Test
    void shouldCreateEmptyInputs() {
        TccInputs inputs = TccInputs.empty();
        
        assertNotNull(inputs);
        assertTrue(inputs.getAllInputs().isEmpty());
        assertFalse(inputs.hasInput("any-participant"));
    }
    
    @Test
    void shouldCreateInputsWithSingleParticipant() {
        TccInputs inputs = TccInputs.of("participant1", "input-data");
        
        assertTrue(inputs.hasInput("participant1"));
        assertEquals("input-data", inputs.getInput("participant1"));
    }
    
    @Test
    void shouldBuildInputsWithMultipleParticipants() {
        TccInputs inputs = TccInputs.builder()
                .forParticipant("payment", "payment-request")
                .forParticipant("inventory", "inventory-request")
                .forParticipant("shipping", "shipping-request")
                .build();
        
        assertEquals(3, inputs.getAllInputs().size());
        assertEquals("payment-request", inputs.getInput("payment"));
        assertEquals("inventory-request", inputs.getInput("inventory"));
        assertEquals("shipping-request", inputs.getInput("shipping"));
    }
    
    @Test
    void shouldGetInputWithTypeChecking() {
        TccInputs inputs = TccInputs.builder()
                .forParticipant("p1", "string-input")
                .forParticipant("p2", 42)
                .build();
        
        String stringInput = inputs.getInput("p1", String.class);
        assertEquals("string-input", stringInput);
        
        Integer intInput = inputs.getInput("p2", Integer.class);
        assertEquals(42, intInput);
        
        // Wrong type should return null
        Integer wrongType = inputs.getInput("p1", Integer.class);
        assertNull(wrongType);
    }
    
    @Test
    void shouldCheckIfInputExists() {
        TccInputs inputs = TccInputs.builder()
                .forParticipant("p1", "data")
                .build();
        
        assertTrue(inputs.hasInput("p1"));
        assertFalse(inputs.hasInput("p2"));
    }
    
    @Test
    void shouldReturnNullForNonExistentInput() {
        TccInputs inputs = TccInputs.empty();
        
        assertNull(inputs.getInput("non-existent"));
        assertNull(inputs.getInput("non-existent", String.class));
    }
    
    @Test
    void shouldBuildInputsFromMap() {
        Map<String, Object> inputMap = Map.of(
                "p1", "data1",
                "p2", "data2"
        );
        
        TccInputs inputs = TccInputs.builder()
                .withInputs(inputMap)
                .build();
        
        assertEquals(2, inputs.getAllInputs().size());
        assertEquals("data1", inputs.getInput("p1"));
        assertEquals("data2", inputs.getInput("p2"));
    }
    
    @Test
    void shouldCombineBuilderMethodsAndMap() {
        Map<String, Object> inputMap = Map.of("p1", "data1");
        
        TccInputs inputs = TccInputs.builder()
                .withInputs(inputMap)
                .forParticipant("p2", "data2")
                .build();
        
        assertEquals(2, inputs.getAllInputs().size());
        assertEquals("data1", inputs.getInput("p1"));
        assertEquals("data2", inputs.getInput("p2"));
    }
    
    @Test
    void shouldReturnImmutableInputsMap() {
        TccInputs inputs = TccInputs.builder()
                .forParticipant("p1", "data1")
                .build();
        
        Map<String, Object> allInputs = inputs.getAllInputs();
        
        assertThrows(UnsupportedOperationException.class, () -> {
            allInputs.put("p2", "data2");
        });
    }
}

