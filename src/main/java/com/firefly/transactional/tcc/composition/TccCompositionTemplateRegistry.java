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

package com.firefly.transactional.tcc.composition;

import com.firefly.transactional.tcc.config.TccCompositionProperties;
import com.firefly.transactional.shared.engine.CompensationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Registry for pre-built TCC composition templates and patterns.
 * <p>
 * Provides common TCC composition patterns that can be used as starting points
 * for building complex distributed transaction workflows. Templates include best practices for
 * three-phase protocol management, error handling, compensation, and performance optimization.
 */
public class TccCompositionTemplateRegistry {
    
    private static final Logger log = LoggerFactory.getLogger(TccCompositionTemplateRegistry.class);
    
    private final Map<String, TccCompositionTemplate> templates = new HashMap<>();
    private final TccCompositionProperties.TemplatesProperties properties;
    
    public TccCompositionTemplateRegistry(TccCompositionProperties.TemplatesProperties properties) {
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
    public TccCompositionTemplate getTemplate(String templateName) {
        return templates.get(templateName);
    }
    
    /**
     * Registers a custom template.
     * 
     * @param name the template name
     * @param template the template
     */
    public void registerTemplate(String name, TccCompositionTemplate template) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(template, "template cannot be null");
        
        templates.put(name, template);
        log.info("Registered TCC composition template: {}", name);
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
    public TccCompositionBuilder fromTemplate(String templateName, String compositionName) {
        TccCompositionTemplate template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Template not found: " + templateName);
        }
        
        return template.createBuilder(compositionName);
    }
    
    private void initializeBuiltInTemplates() {
        // E-commerce Order Processing Template
        registerTemplate("order-processing", new TccCompositionTemplate(
            "E-commerce Order Processing",
            "Standard e-commerce order processing workflow with payment, inventory, and shipping using TCC pattern",
            builder -> builder
                .compensationPolicy(CompensationPolicy.GROUPED_PARALLEL)
                .tcc("payment-processing")
                    .withId("payment")
                    .timeout(30000)
                    .add()
                .tcc("inventory-reservation")
                    .withId("inventory")
                    .dependsOn("payment")
                    .withDataFrom("payment", "payment-participant", "paymentId")
                    .timeout(15000)
                    .add()
                .tcc("shipping-preparation")
                    .withId("shipping")
                    .dependsOn("inventory")
                    .withDataFrom("inventory", "inventory-participant", "reservationId")
                    .executeInParallelWith("notifications")
                    .timeout(20000)
                    .add()
                .tcc("notification-sending")
                    .withId("notifications")
                    .dependsOn("payment")
                    .withDataFrom("payment", "payment-participant", "paymentId")
                    .optional()
                    .timeout(10000)
                    .add()
        ));
        
        // Financial Transaction Template
        registerTemplate("financial-transaction", new TccCompositionTemplate(
            "Financial Transaction Processing",
            "Secure financial transaction processing with fraud detection and compliance using TCC pattern",
            builder -> builder
                .compensationPolicy(CompensationPolicy.STRICT_SEQUENTIAL)
                .tcc("fraud-detection")
                    .withId("fraud")
                    .timeout(5000)
                    .add()
                .tcc("compliance-check")
                    .withId("compliance")
                    .dependsOn("fraud")
                    .withDataFrom("fraud", "fraud-participant", "riskScore")
                    .timeout(10000)
                    .add()
                .tcc("account-debit")
                    .withId("debit")
                    .dependsOn("compliance")
                    .withDataFrom("compliance", "compliance-participant", "approvalId")
                    .timeout(15000)
                    .add()
                .tcc("account-credit")
                    .withId("credit")
                    .dependsOn("debit")
                    .withDataFrom("debit", "debit-participant", "transactionId")
                    .timeout(15000)
                    .add()
                .tcc("transaction-logging")
                    .withId("logging")
                    .dependsOn("credit")
                    .withDataFrom("credit", "credit-participant", "finalBalance")
                    .optional()
                    .timeout(5000)
                    .add()
        ));
        
        // Resource Reservation Template
        registerTemplate("resource-reservation", new TccCompositionTemplate(
            "Resource Reservation",
            "Multi-resource reservation pattern for booking systems and resource allocation",
            builder -> builder
                .compensationPolicy(CompensationPolicy.RETRY_WITH_BACKOFF)
                .tcc("availability-check")
                    .withId("availability")
                    .timeout(5000)
                    .add()
                .tcc("primary-resource-reservation")
                    .withId("primary-resource")
                    .dependsOn("availability")
                    .withDataFrom("availability", "availability-participant", "availableSlots")
                    .executeInParallelWith("secondary-resource")
                    .timeout(10000)
                    .add()
                .tcc("secondary-resource-reservation")
                    .withId("secondary-resource")
                    .dependsOn("availability")
                    .withDataFrom("availability", "availability-participant", "availableSlots")
                    .timeout(10000)
                    .add()
                .tcc("confirmation-notification")
                    .withId("confirmation")
                    .dependsOn("primary-resource")
                    .dependsOn("secondary-resource")
                    .withDataFrom("primary-resource", "primary-participant", "reservationId")
                    .withDataFrom("secondary-resource", "secondary-participant", "reservationId")
                    .timeout(5000)
                    .add()
        ));
        
        // Microservices Orchestration Template
        registerTemplate("microservices-orchestration", new TccCompositionTemplate(
            "Microservices Orchestration",
            "TCC-based orchestration pattern for coordinating multiple microservices",
            builder -> builder
                .compensationPolicy(CompensationPolicy.RETRY_WITH_BACKOFF)
                .tcc("service-discovery")
                    .withId("discovery")
                    .timeout(5000)
                    .add()
                .tcc("authentication")
                    .withId("auth")
                    .dependsOn("discovery")
                    .withDataFrom("discovery", "discovery-participant", "serviceEndpoints")
                    .timeout(10000)
                    .add()
                .tcc("business-logic-service-a")
                    .withId("service-a")
                    .dependsOn("auth")
                    .withDataFrom("auth", "auth-participant", "authToken")
                    .executeInParallelWith("service-b")
                    .timeout(20000)
                    .add()
                .tcc("business-logic-service-b")
                    .withId("service-b")
                    .dependsOn("auth")
                    .withDataFrom("auth", "auth-participant", "authToken")
                    .timeout(20000)
                    .add()
                .tcc("result-aggregation")
                    .withId("aggregation")
                    .dependsOn("service-a")
                    .dependsOn("service-b")
                    .withDataFrom("service-a", "service-a-participant", "resultA")
                    .withDataFrom("service-b", "service-b-participant", "resultB")
                    .timeout(10000)
                    .add()
        ));
        
        log.info("Initialized {} built-in TCC composition templates", templates.size());
    }
    
    private void loadCustomTemplates() {
        if (!properties.isEnabled()) {
            log.debug("Custom templates disabled");
            return;
        }
        
        for (Map.Entry<String, String> entry : properties.getCustomTemplates().entrySet()) {
            try {
                // In a real implementation, this would load templates from external sources
                // For now, we just log the custom template configuration
                log.info("Custom TCC template configured: {} -> {}", entry.getKey(), entry.getValue());
            } catch (Exception e) {
                log.warn("Failed to load custom TCC template: {}", entry.getKey(), e);
            }
        }
    }
    
    /**
     * Represents a TCC composition template with metadata and builder function.
     */
    public static class TccCompositionTemplate {
        private final String name;
        private final String description;
        private final Function<TccCompositionBuilder, TccCompositionBuilder> builderFunction;
        
        public TccCompositionTemplate(String name, String description, 
                                     Function<TccCompositionBuilder, TccCompositionBuilder> builderFunction) {
            this.name = Objects.requireNonNull(name, "name cannot be null");
            this.description = Objects.requireNonNull(description, "description cannot be null");
            this.builderFunction = Objects.requireNonNull(builderFunction, "builderFunction cannot be null");
        }
        
        public String getName() { return name; }
        public String getDescription() { return description; }
        
        public TccCompositionBuilder createBuilder(String compositionName) {
            TccCompositionBuilder builder = TccCompositor.compose(compositionName);
            return builderFunction.apply(builder);
        }
    }
}
