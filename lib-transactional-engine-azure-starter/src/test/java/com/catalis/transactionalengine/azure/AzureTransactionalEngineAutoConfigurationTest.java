package com.catalis.transactionalengine.azure;

import com.catalis.transactionalengine.observability.SagaEvents;
import com.azure.cosmos.CosmosAsyncClient;
import com.azure.messaging.eventhubs.EventHubProducerAsyncClient;
import com.azure.messaging.servicebus.ServiceBusSenderAsyncClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link AzureTransactionalEngineAutoConfiguration}.
 */
class AzureTransactionalEngineAutoConfigurationTest {
    
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AzureTransactionalEngineAutoConfiguration.class));
    
    @Test
    void autoConfigurationIsAppliedWhenAzureClassesArePresent() {
        // When Azure classes are on the classpath, auto-configuration should be applied
        contextRunner
            .withUserConfiguration(MockAzureClientsConfiguration.class)
            .withPropertyValues(
                "transactional-engine.azure.cosmosdb.enabled=true",
                "transactional-engine.azure.cosmosdb.endpoint=https://test.documents.azure.com:443/",
                "transactional-engine.azure.cosmosdb.key=test-key"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(AzureTransactionalEngineAutoConfiguration.class);
                assertThat(context).hasSingleBean(CosmosAsyncClient.class);
                // Application Insights functionality has been removed
            });
    }
    
    @Test
    void cosmosClientIsCreatedWhenEnabledAndPropertiesAreSet() {
        contextRunner
            .withUserConfiguration(MockAzureClientsConfiguration.class)
            .withPropertyValues(
                "transactional-engine.azure.cosmosdb.enabled=true",
                "transactional-engine.azure.cosmosdb.endpoint=https://test.documents.azure.com:443/",
                "transactional-engine.azure.cosmosdb.key=test-key"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(CosmosAsyncClient.class);
                assertThat(context).hasSingleBean(AzureTransactionalEngineProperties.class);
            });
    }
    
    @Test
    void applicationInsightsSagaEventsIsCreatedWhenEnabled() {
        contextRunner
            .withUserConfiguration(MockAzureClientsConfiguration.class)
            .withPropertyValues(
                "transactional-engine.azure.application-insights.enabled=true",
                "transactional-engine.azure.application-insights.instrumentation-key=test-key"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(SagaEvents.class);
                assertThat(context.getBean(SagaEvents.class))
                    .isInstanceOf(ApplicationInsightsSagaEvents.class);
            });
    }
    
    @Test
    void eventHubsStepEventPublisherIsCreatedWhenEnabled() {
        contextRunner
            .withUserConfiguration(MockAzureClientsConfiguration.class)
            .withPropertyValues(
                "transactional-engine.azure.eventhubs.enabled=true",
                "transactional-engine.azure.eventhubs.connection-string=Endpoint=sb://test.servicebus.windows.net/;SharedAccessKeyName=test;SharedAccessKey=test",
                "transactional-engine.azure.eventhubs.event-hub-name=test-hub"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(EventHubProducerAsyncClient.class);
                assertThat(context).hasBean("eventHubsStepEventPublisher");
                assertThat(context.getBean("eventHubsStepEventPublisher"))
                    .isInstanceOf(EventHubsStepEventPublisher.class);
            });
    }
    
    @Test
    void serviceBusStepEventPublisherIsCreatedWhenEnabled() {
        contextRunner
            .withUserConfiguration(MockAzureClientsConfiguration.class)
            .withPropertyValues(
                "transactional-engine.azure.servicebus.enabled=true",
                "transactional-engine.azure.servicebus.connection-string=Endpoint=sb://test.servicebus.windows.net/;SharedAccessKeyName=test;SharedAccessKey=test",
                "transactional-engine.azure.servicebus.queue-name=test-queue"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ServiceBusSenderAsyncClient.class);
                assertThat(context).hasBean("serviceBusStepEventPublisher");
                assertThat(context.getBean("serviceBusStepEventPublisher"))
                    .isInstanceOf(ServiceBusStepEventPublisher.class);
            });
    }
    
    @Test
    void beansAreNotCreatedWhenDisabled() {
        contextRunner
            .withUserConfiguration(MockAzureClientsConfiguration.class)
            .withPropertyValues(
                "transactional-engine.azure.cosmosdb.enabled=false",
                "transactional-engine.azure.application-insights.enabled=false",
                "transactional-engine.azure.eventhubs.enabled=false",
                "transactional-engine.azure.servicebus.enabled=false"
            )
            .run(context -> {
                assertThat(context).doesNotHaveBean("applicationInsightsSagaEvents");
                assertThat(context).doesNotHaveBean("eventHubsStepEventPublisher");
                assertThat(context).doesNotHaveBean("serviceBusStepEventPublisher");
            });
    }
    
    @Test
    void propertiesAreCorrectlyBound() {
        contextRunner
            .withUserConfiguration(MockAzureClientsConfiguration.class)
            .withPropertyValues(
                "transactional-engine.azure.cosmosdb.database-name=test-db",
                "transactional-engine.azure.cosmosdb.container-name=test-container",
                // Application Insights properties removed
                "transactional-engine.azure.eventhubs.max-records-per-batch=150",
                "transactional-engine.azure.eventhubs.batch-timeout=PT10S",
                "transactional-engine.azure.servicebus.max-batch-size=20",
                "transactional-engine.azure.servicebus.lock-duration=PT10M"
            )
            .run(context -> {
                AzureTransactionalEngineProperties properties = context.getBean(AzureTransactionalEngineProperties.class);
                
                assertThat(properties.getCosmosdb().getDatabaseName()).isEqualTo("test-db");
                assertThat(properties.getCosmosdb().getContainerName()).isEqualTo("test-container");
                // Application Insights property assertions removed
                assertThat(properties.getEventhubs().getMaxRecordsPerBatch()).isEqualTo(150);
                assertThat(properties.getEventhubs().getBatchTimeout().getSeconds()).isEqualTo(10);
                assertThat(properties.getServicebus().getMaxBatchSize()).isEqualTo(20);
                assertThat(properties.getServicebus().getLockDuration().toMinutes()).isEqualTo(10);
            });
    }
    
    @Configuration(proxyBeanMethods = false)
    static class MockAzureClientsConfiguration {
        
        @Bean
        CosmosAsyncClient cosmosAsyncClient() {
            return mock(CosmosAsyncClient.class);
        }
        
        // TelemetryClient removed - Application Insights functionality disabled
        // @Bean
        // TelemetryClient telemetryClient() {
        //     return mock(TelemetryClient.class);
        // }
        
        @Bean
        EventHubProducerAsyncClient eventHubProducerAsyncClient() {
            return mock(EventHubProducerAsyncClient.class);
        }
        
        @Bean
        ServiceBusSenderAsyncClient serviceBusSenderAsyncClient() {
            return mock(ServiceBusSenderAsyncClient.class);
        }
    }
}