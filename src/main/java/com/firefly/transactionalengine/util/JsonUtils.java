package com.firefly.transactionalengine.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility class for JSON operations using Jackson ObjectMapper.
 * Provides a convenient method to create JSON strings from key-value pairs.
 */
public final class JsonUtils {
    private static final Logger log = LoggerFactory.getLogger(JsonUtils.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private JsonUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Creates a JSON string from key-value pairs.
     * 
     * @param keyValuePairs varargs of key-value pairs (key1, value1, key2, value2, ...)
     * @return JSON string representation of the key-value pairs
     * @throws IllegalArgumentException if the number of arguments is odd
     */
    public static String json(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Key-value pairs must be provided in pairs (even number of arguments)");
        }

        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            String key = keyValuePairs[i];
            String value = keyValuePairs[i + 1];
            map.put(key, value != null ? value : "");
        }

        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("Failed to create JSON from key-value pairs", e);
            // Fallback to empty JSON object
            return "{}";
        }
    }
}