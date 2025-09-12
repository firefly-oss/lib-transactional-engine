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

package com.firefly.transactional.composition;

import java.util.Objects;

/**
 * Represents a validation issue found during composition building.
 * <p>
 * Provides detailed information about validation problems including
 * severity, error codes, descriptions, and suggested fixes to help
 * developers quickly resolve issues.
 */
public class ValidationIssue {
    
    public enum Severity {
        ERROR,    // Prevents composition from being built
        WARNING,  // Composition can be built but may have issues
        INFO      // Informational message for optimization
    }
    
    private final Severity severity;
    private final String code;
    private final String message;
    private final String suggestion;
    private final String sagaId;
    private final String location;
    
    public ValidationIssue(Severity severity, String code, String message, String suggestion) {
        this(severity, code, message, suggestion, null, null);
    }
    
    public ValidationIssue(Severity severity, String code, String message, String suggestion, 
                          String sagaId, String location) {
        this.severity = Objects.requireNonNull(severity, "severity cannot be null");
        this.code = Objects.requireNonNull(code, "code cannot be null");
        this.message = Objects.requireNonNull(message, "message cannot be null");
        this.suggestion = suggestion;
        this.sagaId = sagaId;
        this.location = location;
    }
    
    /**
     * Creates an error validation issue.
     */
    public static ValidationIssue error(String code, String message, String suggestion) {
        return new ValidationIssue(Severity.ERROR, code, message, suggestion);
    }
    
    /**
     * Creates an error validation issue for a specific saga.
     */
    public static ValidationIssue error(String code, String message, String suggestion, String sagaId) {
        return new ValidationIssue(Severity.ERROR, code, message, suggestion, sagaId, null);
    }
    
    /**
     * Creates a warning validation issue.
     */
    public static ValidationIssue warning(String code, String message, String suggestion) {
        return new ValidationIssue(Severity.WARNING, code, message, suggestion);
    }
    
    /**
     * Creates a warning validation issue for a specific saga.
     */
    public static ValidationIssue warning(String code, String message, String suggestion, String sagaId) {
        return new ValidationIssue(Severity.WARNING, code, message, suggestion, sagaId, null);
    }
    
    /**
     * Creates an info validation issue.
     */
    public static ValidationIssue info(String code, String message, String suggestion) {
        return new ValidationIssue(Severity.INFO, code, message, suggestion);
    }
    
    /**
     * Creates an info validation issue for a specific saga.
     */
    public static ValidationIssue info(String code, String message, String suggestion, String sagaId) {
        return new ValidationIssue(Severity.INFO, code, message, suggestion, sagaId, null);
    }
    
    // Getters
    public Severity getSeverity() { return severity; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public String getSuggestion() { return suggestion; }
    public String getSagaId() { return sagaId; }
    public String getLocation() { return location; }
    
    /**
     * Returns a formatted string representation of the validation issue.
     */
    public String getFormattedMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(severity).append("] ");
        sb.append(code).append(": ");
        sb.append(message);
        
        if (sagaId != null) {
            sb.append(" (saga: ").append(sagaId).append(")");
        }
        
        if (location != null) {
            sb.append(" at ").append(location);
        }
        
        if (suggestion != null) {
            sb.append("\n  Suggestion: ").append(suggestion);
        }
        
        return sb.toString();
    }
    
    @Override
    public String toString() {
        return getFormattedMessage();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ValidationIssue that = (ValidationIssue) o;
        return severity == that.severity &&
               Objects.equals(code, that.code) &&
               Objects.equals(message, that.message) &&
               Objects.equals(sagaId, that.sagaId) &&
               Objects.equals(location, that.location);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(severity, code, message, sagaId, location);
    }
}
