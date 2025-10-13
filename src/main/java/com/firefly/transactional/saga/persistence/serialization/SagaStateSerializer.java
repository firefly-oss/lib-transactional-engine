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

package com.firefly.transactional.saga.persistence.serialization;

import com.firefly.transactional.saga.persistence.SagaExecutionState;

/**
 * Interface for serializing and deserializing saga execution state.
 * <p>
 * This abstraction allows for different serialization strategies (JSON, Avro, Protobuf, etc.)
 * while maintaining a consistent interface for persistence providers.
 * <p>
 * Implementations must handle:
 * <ul>
 *   <li>Complete saga execution state including context and metadata</li>
 *   <li>Type safety and version compatibility</li>
 *   <li>Error handling for corrupted or incompatible data</li>
 *   <li>Performance optimization for large saga states</li>
 * </ul>
 */
public interface SagaStateSerializer {

    /**
     * Serializes a saga execution state to bytes.
     *
     * @param state the saga execution state to serialize
     * @return serialized bytes
     * @throws SerializationException if serialization fails
     */
    byte[] serialize(SagaExecutionState state) throws SerializationException;

    /**
     * Deserializes bytes back to a saga execution state.
     *
     * @param data the serialized bytes
     * @return the deserialized saga execution state
     * @throws SerializationException if deserialization fails
     */
    SagaExecutionState deserialize(byte[] data) throws SerializationException;

    /**
     * Gets the content type identifier for this serializer.
     * This can be used for versioning and compatibility checks.
     *
     * @return content type identifier (e.g., "application/json", "application/x-protobuf")
     */
    String getContentType();

    /**
     * Gets the version of this serializer.
     * This is used for backward compatibility and migration support.
     *
     * @return serializer version
     */
    String getVersion();

    /**
     * Checks if this serializer can deserialize data with the given content type and version.
     *
     * @param contentType the content type of the data
     * @param version the version of the data
     * @return true if this serializer can handle the data, false otherwise
     */
    boolean canDeserialize(String contentType, String version);

    /**
     * Exception thrown when serialization or deserialization fails.
     */
    class SerializationException extends Exception {
        public SerializationException(String message) {
            super(message);
        }

        public SerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
