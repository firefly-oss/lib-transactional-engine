package com.firefly.transactionalengine.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StepEventEnvelopeTest {

    @Test
    void testToStringReturnsJsonWithAllFields() {
        // Given
        String sagaName = "TestSaga";
        String sagaId = "saga-123";
        String stepId = "step-456";
        String topic = "test-topic";
        String type = "TEST_EVENT";
        String key = "test-key";
        String payload = "test payload";
        Integer attempts = 5;
        Long latencyMs = 1234L;
        Instant startedAt = Instant.now();
        Instant completedAt = Instant.now().plusSeconds(10);
        String resultType = "com.firefly.test.TestPayload";
        Map<String, String> headers = Map.of("header1", "value1", "header2", "value2");

        // When
        StepEventEnvelope envelope = new StepEventEnvelope(
            sagaName, sagaId, stepId, topic, type, key, payload, headers, attempts, latencyMs, startedAt, completedAt, resultType
        );

        String json = envelope.toString();
        
        // Then
        System.out.println("[DEBUG_LOG] Generated JSON: " + json);
        
        // Verify it's valid JSON by checking it contains all expected fields
        assertTrue(json.contains("\"sagaName\":\"" + sagaName + "\""), "JSON should contain sagaName");
        assertTrue(json.contains("\"sagaId\":\"" + sagaId + "\""), "JSON should contain sagaId");
        assertTrue(json.contains("\"stepId\":\"" + stepId + "\""), "JSON should contain stepId");
        assertTrue(json.contains("\"topic\":\"" + topic + "\""), "JSON should contain topic");
        assertTrue(json.contains("\"type\":\"" + type + "\""), "JSON should contain type");
        assertTrue(json.contains("\"key\":\"" + key + "\""), "JSON should contain key");
        assertTrue(json.contains("\"payload\":\"" + payload + "\""), "JSON should contain payload");
        assertTrue(json.contains("\"headers\":{"), "JSON should contain headers");
        assertTrue(json.contains("\"timestamp\":"), "JSON should contain timestamp");
        
        // Verify it starts with { and ends with } (valid JSON format)
        assertTrue(json.startsWith("{"), "JSON should start with {");
        assertTrue(json.endsWith("}"), "JSON should end with }");
    }

    @Test
    void testToStringWithNullHeaders() {
        // Given
        StepEventEnvelope envelope = new StepEventEnvelope(
            "TestSaga", "saga-123", "step-456", "test-topic", 
            "TEST_EVENT", "test-key", "test payload", null, 5, 1234L,
                Instant.now(), Instant.now().plusSeconds(10), "com.firefly.test.TestPayload"
        );

        // When
        String json = envelope.toString();

        // Then
        System.out.println("[DEBUG_LOG] JSON with null headers: " + json);
        assertTrue(json.contains("\"headers\":{}"), "JSON should contain empty headers object");
    }
}