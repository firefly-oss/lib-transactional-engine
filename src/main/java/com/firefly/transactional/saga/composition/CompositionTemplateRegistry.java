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

import com.firefly.transactional.saga.config.SagaCompositionProperties;
import com.firefly.transactional.shared.engine.CompensationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Registry for pre-built composition templates and patterns.
 * <p>
 * Provides common composition patterns that can be used as starting points
 * for building complex workflows. Templates include best practices for
 * error handling, compensation, and performance optimization.
 */
public class CompositionTemplateRegistry {
    
    private static final Logger log = LoggerFactory.getLogger(CompositionTemplateRegistry.class);
    
    private final Map<String, CompositionTemplate> templates = new HashMap<>();
    private final SagaCompositionProperties.TemplatesProperties properties;
    
    public CompositionTemplateRegistry(SagaCompositionProperties.TemplatesProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        initializeBuiltInTemplates();
        loadCustomTemplates();
    }
    
    /**
     * Gets a template by name.
     * 
     * @param templateName the template name
     * @return the template, or null if not found
     */
    public CompositionTemplate getTemplate(String templateName) {
        return templates.get(templateName);
    }
    
    /**
     * Registers a custom template.
     * 
     * @param name the template name
     * @param template the template
     */
    public void registerTemplate(String name, CompositionTemplate template) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(template, "template cannot be null");
        
        templates.put(name, template);
        log.info("Registered composition template: {}", name);
    }
    
    /**
     * Gets all available template names.
     * 
     * @return set of template names
     */
    public java.util.Set<String> getTemplateNames() {
        return templates.keySet();
    }
    
    /**
     * Creates a composition builder from a template.
     * 
     * @param templateName the template name
     * @param compositionName the name for the new composition
     * @return the composition builder
     * @throws IllegalArgumentException if template not found
     */
    public SagaCompositionBuilder fromTemplate(String templateName, String compositionName) {
        CompositionTemplate template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Template not found: " + templateName);
        }
        
        return template.createBuilder(compositionName);
    }
    
    private void initializeBuiltInTemplates() {
        // E-commerce Order Processing Template
        registerTemplate("order-processing", new CompositionTemplate(
            "E-commerce Order Processing",
            "Standard e-commerce order processing workflow with payment, inventory, and shipping",
            builder -> builder
                .compensationPolicy(CompensationPolicy.GROUPED_PARALLEL)
                .saga("payment-processing")
                    .withId("payment")
                    .timeout(30000)
                    .add()
                .saga("inventory-reservation")
                    .withId("inventory")
                    .dependsOn("payment")
                    .withDataFrom("payment", "paymentId")
                    .timeout(15000)
                    .add()
                .saga("shipping-preparation")
                    .withId("shipping")
                    .dependsOn("inventory")
                    .withDataFrom("inventory", "reservationId")
                    .executeInParallelWith("notifications")
                    .timeout(20000)
                    .add()
                .saga("notification-sending")
                    .withId("notifications")
                    .dependsOn("payment")
                    .withDataFrom("payment", "paymentId")
                    .optional()
                    .timeout(10000)
                    .add()
        ));
        
        // Financial Transaction Template
        registerTemplate("financial-transaction", new CompositionTemplate(
            "Financial Transaction Processing",
            "Secure financial transaction processing with fraud detection and compliance",
            builder -> builder
                .compensationPolicy(CompensationPolicy.STRICT_SEQUENTIAL)
                .saga("fraud-detection")
                    .withId("fraud")
                    .timeout(5000)
                    .add()
                .saga("compliance-check")
                    .withId("compliance")
                    .dependsOn("fraud")
                    .timeout(10000)
                    .add()
                .saga("account-debit")
                    .withId("debit")
                    .dependsOn("compliance")
                    .withDataFrom("compliance", "approvalId")
                    .timeout(15000)
                    .add()
                .saga("account-credit")
                    .withId("credit")
                    .dependsOn("debit")
                    .withDataFrom("debit", "transactionId")
                    .timeout(15000)
                    .add()
                .saga("transaction-logging")
                    .withId("logging")
                    .dependsOn("credit")
                    .withDataFrom("credit", "transactionId")
                    .optional()
                    .timeout(5000)
                    .add()
        ));
        
        // Data Pipeline Template
        registerTemplate("data-pipeline", new CompositionTemplate(
            "Data Processing Pipeline",
            "ETL data processing pipeline with validation and transformation",
            builder -> builder
                .compensationPolicy(CompensationPolicy.BEST_EFFORT_PARALLEL)
                .saga("data-extraction")
                    .withId("extract")
                    .timeout(60000)
                    .add()
                .saga("data-validation")
                    .withId("validate")
                    .dependsOn("extract")
                    .withDataFrom("extract", "extractedData")
                    .timeout(30000)
                    .add()
                .saga("data-transformation")
                    .withId("transform")
                    .dependsOn("validate")
                    .withDataFrom("validate", "validatedData")
                    .executeInParallelWith("quality-check")
                    .timeout(45000)
                    .add()
                .saga("data-quality-check")
                    .withId("quality-check")
                    .dependsOn("validate")
                    .withDataFrom("validate", "validatedData")
                    .optional()
                    .timeout(20000)
                    .add()
                .saga("data-loading")
                    .withId("load")
                    .dependsOn("transform")
                    .withDataFrom("transform", "transformedData")
                    .timeout(30000)
                    .add()
        ));
        
        // Microservices Orchestration Template
        registerTemplate("microservices-orchestration", new CompositionTemplate(
            "Microservices Orchestration",
            "Orchestration pattern for coordinating multiple microservices",
            builder -> builder
                .compensationPolicy(CompensationPolicy.RETRY_WITH_BACKOFF)
                .saga("service-discovery")
                    .withId("discovery")
                    .timeout(5000)
                    .add()
                .saga("authentication")
                    .withId("auth")
                    .dependsOn("discovery")
                    .timeout(10000)
                    .add()
                .saga("business-logic-service-a")
                    .withId("service-a")
                    .dependsOn("auth")
                    .withDataFrom("auth", "authToken")
                    .executeInParallelWith("service-b")
                    .timeout(20000)
                    .add()
                .saga("business-logic-service-b")
                    .withId("service-b")
                    .dependsOn("auth")
                    .withDataFrom("auth", "authToken")
                    .timeout(20000)
                    .add()
                .saga("result-aggregation")
                    .withId("aggregation")
                    .dependsOn("service-a")
                    .dependsOn("service-b")
                    .withDataFrom("service-a", "resultA")
                    .withDataFrom("service-b", "resultB")
                    .timeout(10000)
                    .add()
        ));
        
        log.info("Initialized {} built-in composition templates", templates.size());
    }
    
    private void loadCustomTemplates() {
        // Load custom templates from configuration
        for (Map.Entry<String, String> entry : properties.getCustomTemplates().entrySet()) {
            try {
                // In a real implementation, this would load templates from external sources
                // For now, we just log the custom template configuration
                log.info("Custom template configured: {} -> {}", entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("Failed to load custom template: {}", entry.getKey(), e);
            }
        }
    }
    
    /**
     * Represents a composition template with metadata and builder function.
     */
    public static class CompositionTemplate {
        private final String name;
        private final String description;
        private final Function<SagaCompositionBuilder, SagaCompositionBuilder> builderFunction;
        
        public CompositionTemplate(String name, String description, 
                                 Function<SagaCompositionBuilder, SagaCompositionBuilder> builderFunction) {
            this.name = Objects.requireNonNull(name, "name cannot be null");
            this.description = Objects.requireNonNull(description, "description cannot be null");
            this.builderFunction = Objects.requireNonNull(builderFunction, "builderFunction cannot be null");
        }
        
        public String getName() { return name; }
        public String getDescription() { return description; }
        
        public SagaCompositionBuilder createBuilder(String compositionName) {
            SagaCompositionBuilder builder = SagaCompositor.compose(compositionName);
            return builderFunction.apply(builder);
        }
    }
}
