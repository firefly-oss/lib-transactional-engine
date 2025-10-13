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

package com.firefly.transactional.saga.composition;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Exception thrown when composition validation fails.
 * <p>
 * Provides detailed information about all validation issues found
 * during composition building, including helpful suggestions for
 * resolving the problems.
 */
public class CompositionValidationException extends RuntimeException {
    
    private final String compositionName;
    private final List<ValidationIssue> validationIssues;
    
    public CompositionValidationException(String compositionName, List<ValidationIssue> validationIssues) {
        super(buildMessage(compositionName, validationIssues));
        this.compositionName = compositionName;
        this.validationIssues = List.copyOf(validationIssues);
    }
    
    private static String buildMessage(String compositionName, List<ValidationIssue> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("Validation failed for composition '").append(compositionName).append("':\n");
        
        long errorCount = issues.stream().filter(i -> i.getSeverity() == ValidationIssue.Severity.ERROR).count();
        long warningCount = issues.stream().filter(i -> i.getSeverity() == ValidationIssue.Severity.WARNING).count();
        
        sb.append("Found ").append(errorCount).append(" error(s) and ").append(warningCount).append(" warning(s)\n\n");
        
        // Group issues by severity
        List<ValidationIssue> errors = issues.stream()
                .filter(i -> i.getSeverity() == ValidationIssue.Severity.ERROR)
                .collect(Collectors.toList());
        
        List<ValidationIssue> warnings = issues.stream()
                .filter(i -> i.getSeverity() == ValidationIssue.Severity.WARNING)
                .collect(Collectors.toList());
        
        if (!errors.isEmpty()) {
            sb.append("ERRORS:\n");
            for (int i = 0; i < errors.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(errors.get(i).getFormattedMessage()).append("\n");
            }
            sb.append("\n");
        }
        
        if (!warnings.isEmpty()) {
            sb.append("WARNINGS:\n");
            for (int i = 0; i < warnings.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(warnings.get(i).getFormattedMessage()).append("\n");
            }
        }
        
        return sb.toString();
    }
    
    public String getCompositionName() {
        return compositionName;
    }
    
    public List<ValidationIssue> getValidationIssues() {
        return validationIssues;
    }
    
    public List<ValidationIssue> getErrors() {
        return validationIssues.stream()
                .filter(i -> i.getSeverity() == ValidationIssue.Severity.ERROR)
                .collect(Collectors.toList());
    }
    
    public List<ValidationIssue> getWarnings() {
        return validationIssues.stream()
                .filter(i -> i.getSeverity() == ValidationIssue.Severity.WARNING)
                .collect(Collectors.toList());
    }
    
    public boolean hasErrors() {
        return validationIssues.stream().anyMatch(i -> i.getSeverity() == ValidationIssue.Severity.ERROR);
    }
    
    public boolean hasWarnings() {
        return validationIssues.stream().anyMatch(i -> i.getSeverity() == ValidationIssue.Severity.WARNING);
    }
}
