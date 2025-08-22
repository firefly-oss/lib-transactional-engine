package com.catalis.transactionalengine.azure;

import com.catalis.transactionalengine.observability.SagaEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerAsyncClient;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusSenderAsyncClient;
// Application Insights dependency removed - TelemetryClient not available
// import com.microsoft.applicationinsights.TelemetryClient;

/**
 * Auto-configuration for Azure integration with Transactional Engine.
 * 
 * This configuration provides Azure-specific beans when Azure dependencies are on the classpath:
 * - Azure SDK clients (Cosmos DB, Event Hubs, Service Bus, Application Insights)
 * - Azure-specific SagaEvents implementations for metrics and logging
 * - Azure-specific StepEventPublisher implementations for Event Hubs/Service Bus
 */
@AutoConfiguration
@ConditionalOnClass({
    CosmosAsyncClient.class,
    EventHubProducerAsyncClient.class,
    ServiceBusSenderAsyncClient.class
})
@EnableConfigurationProperties(AzureTransactionalEngineProperties.class)
public class AzureTransactionalEngineAutoConfiguration {
    
    private static final Logger log = LoggerFactory.getLogger(AzureTransactionalEngineAutoConfiguration.class);

    /**
     * Creates a Cosmos DB async client with default configuration.
     * Users can override this bean to provide custom configuration.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "transactional-engine.azure.cosmosdb", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CosmosAsyncClient cosmosAsyncClient(AzureTransactionalEngineProperties properties) {
        log.info("Creating Cosmos DB async client for Transactional Engine");
        AzureTransactionalEngineProperties.CosmosDbProperties cosmosProperties = properties.getCosmosdb();
        
        if (cosmosProperties.getEndpoint() == null || cosmosProperties.getKey() == null) {
            throw new IllegalArgumentException("Cosmos DB endpoint and key must be configured");
        }
        
        return new CosmosClientBuilder()
                .endpoint(cosmosProperties.getEndpoint())
                .key(cosmosProperties.getKey())
                .buildAsyncClient();
    }

    /**
     * Creates an Application Insights TelemetryClient for publishing saga metrics.
     * DISABLED: Application Insights dependencies are not available in Maven Central
     */
    /*
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(TelemetryClient.class)
    @ConditionalOnProperty(prefix = "transactional-engine.azure.application-insights", name = "enabled", havingValue = "true", matchIfMissing = false)
    public TelemetryClient telemetryClient(AzureTransactionalEngineProperties properties) {
        log.info("Creating Application Insights TelemetryClient for Transactional Engine metrics");
        return new TelemetryClient();
    }
    */

    /**
     * Creates an Event Hub producer async client for step event publishing.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "transactional-engine.azure.eventhubs", name = "enabled", havingValue = "true", matchIfMissing = false)
    public EventHubProducerAsyncClient eventHubProducerAsyncClient(AzureTransactionalEngineProperties properties) {
        log.info("Creating Event Hub producer async client for Transactional Engine step events");
        AzureTransactionalEngineProperties.EventHubsProperties eventHubsProperties = properties.getEventhubs();
        
        if (eventHubsProperties.getConnectionString() == null || eventHubsProperties.getEventHubName() == null) {
            throw new IllegalArgumentException("Event Hubs connection string and event hub name must be configured");
        }
        
        return new EventHubClientBuilder()
                .connectionString(eventHubsProperties.getConnectionString(), eventHubsProperties.getEventHubName())
                .buildAsyncProducerClient();
    }

    /**
     * Creates a Service Bus sender async client for step event publishing.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "transactional-engine.azure.servicebus", name = "enabled", havingValue = "true", matchIfMissing = false)
    public ServiceBusSenderAsyncClient serviceBusSenderAsyncClient(AzureTransactionalEngineProperties properties) {
        log.info("Creating Service Bus sender async client for Transactional Engine step events");
        AzureTransactionalEngineProperties.ServiceBusProperties serviceBusProperties = properties.getServicebus();
        
        if (serviceBusProperties.getConnectionString() == null) {
            throw new IllegalArgumentException("Service Bus connection string must be configured");
        }
        
        ServiceBusClientBuilder builder = new ServiceBusClientBuilder()
                .connectionString(serviceBusProperties.getConnectionString());
        
        if (serviceBusProperties.getQueueName() != null) {
            return builder.sender()
                    .queueName(serviceBusProperties.getQueueName())
                    .buildAsyncClient();
        } else if (serviceBusProperties.getTopicName() != null) {
            return builder.sender()
                    .topicName(serviceBusProperties.getTopicName())
                    .buildAsyncClient();
        } else {
            throw new IllegalArgumentException("Either Service Bus queue name or topic name must be configured");
        }
    }

    /**
     * Application Insights-based SagaEvents implementation that publishes metrics to Application Insights.
     * This replaces the default logging-only implementation when Application Insights is enabled.
     * DISABLED: Application Insights dependencies are not available in Maven Central
     */
    /*
    @Bean
    @ConditionalOnMissingBean(SagaEvents.class)
    @ConditionalOnProperty(prefix = "transactional-engine.azure.application-insights", name = "enabled", havingValue = "true")
    public SagaEvents applicationInsightsSagaEvents(TelemetryClient telemetryClient, 
                                                   AzureTransactionalEngineProperties properties) {
        log.info("Creating Application Insights-based SagaEvents implementation");
        return new ApplicationInsightsSagaEvents(telemetryClient, properties.getApplicationInsights());
    }
    */

    /**
     * Event Hubs-based StepEventPublisher that publishes step events to an Event Hub.
     */
    @Bean
    @ConditionalOnMissingBean(name = "eventHubsStepEventPublisher")
    @ConditionalOnProperty(prefix = "transactional-engine.azure.eventhubs", name = "enabled", havingValue = "true")
    public EventHubsStepEventPublisher eventHubsStepEventPublisher(EventHubProducerAsyncClient eventHubClient,
                                                                  AzureTransactionalEngineProperties properties) {
        log.info("Creating Event Hubs-based StepEventPublisher");
        return new EventHubsStepEventPublisher(eventHubClient, properties.getEventhubs());
    }

    /**
     * Service Bus-based StepEventPublisher that publishes step events to Service Bus queues or topics.
     */
    @Bean
    @ConditionalOnMissingBean(name = "serviceBusStepEventPublisher")
    @ConditionalOnProperty(prefix = "transactional-engine.azure.servicebus", name = "enabled", havingValue = "true")
    public ServiceBusStepEventPublisher serviceBusStepEventPublisher(ServiceBusSenderAsyncClient serviceBusClient,
                                                                    AzureTransactionalEngineProperties properties) {
        log.info("Creating Service Bus-based StepEventPublisher");
        return new ServiceBusStepEventPublisher(serviceBusClient, properties.getServicebus());
    }
}