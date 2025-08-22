package com.catalis.transactionalengine.azure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for Azure integration with Transactional Engine.
 */
@ConfigurationProperties(prefix = "transactional-engine.azure")
public class AzureTransactionalEngineProperties {

    private CosmosDbProperties cosmosdb = new CosmosDbProperties();
    private ApplicationInsightsProperties applicationInsights = new ApplicationInsightsProperties();
    private EventHubsProperties eventhubs = new EventHubsProperties();
    private ServiceBusProperties servicebus = new ServiceBusProperties();

    public CosmosDbProperties getCosmosdb() {
        return cosmosdb;
    }

    public void setCosmosdb(CosmosDbProperties cosmosdb) {
        this.cosmosdb = cosmosdb;
    }

    public ApplicationInsightsProperties getApplicationInsights() {
        return applicationInsights;
    }

    public void setApplicationInsights(ApplicationInsightsProperties applicationInsights) {
        this.applicationInsights = applicationInsights;
    }

    public EventHubsProperties getEventhubs() {
        return eventhubs;
    }

    public void setEventhubs(EventHubsProperties eventhubs) {
        this.eventhubs = eventhubs;
    }

    public ServiceBusProperties getServicebus() {
        return servicebus;
    }

    public void setServicebus(ServiceBusProperties servicebus) {
        this.servicebus = servicebus;
    }

    /**
     * Properties for Cosmos DB integration.
     */
    public static class CosmosDbProperties {
        private boolean enabled = true;
        private String endpoint;
        private String key;
        private String databaseName = "transactional-engine";
        private String containerName = "saga-executions";
        private Duration timeout = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public void setDatabaseName(String databaseName) {
            this.databaseName = databaseName;
        }

        public String getContainerName() {
            return containerName;
        }

        public void setContainerName(String containerName) {
            this.containerName = containerName;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /**
     * Properties for Application Insights integration.
     */
    public static class ApplicationInsightsProperties {
        private boolean enabled = false;
        private String instrumentationKey;
        private String connectionString;
        private Duration publishInterval = Duration.ofMinutes(1);
        private int maxBatchSize = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getInstrumentationKey() {
            return instrumentationKey;
        }

        public void setInstrumentationKey(String instrumentationKey) {
            this.instrumentationKey = instrumentationKey;
        }

        public String getConnectionString() {
            return connectionString;
        }

        public void setConnectionString(String connectionString) {
            this.connectionString = connectionString;
        }

        public Duration getPublishInterval() {
            return publishInterval;
        }

        public void setPublishInterval(Duration publishInterval) {
            this.publishInterval = publishInterval;
        }

        public int getMaxBatchSize() {
            return maxBatchSize;
        }

        public void setMaxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
        }
    }

    /**
     * Properties for Event Hubs integration.
     */
    public static class EventHubsProperties {
        private boolean enabled = false;
        private String connectionString;
        private String eventHubName;
        private String consumerGroup = "$Default";
        private int maxRecordsPerBatch = 100;
        private Duration batchTimeout = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getConnectionString() {
            return connectionString;
        }

        public void setConnectionString(String connectionString) {
            this.connectionString = connectionString;
        }

        public String getEventHubName() {
            return eventHubName;
        }

        public void setEventHubName(String eventHubName) {
            this.eventHubName = eventHubName;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }

        public int getMaxRecordsPerBatch() {
            return maxRecordsPerBatch;
        }

        public void setMaxRecordsPerBatch(int maxRecordsPerBatch) {
            this.maxRecordsPerBatch = maxRecordsPerBatch;
        }

        public Duration getBatchTimeout() {
            return batchTimeout;
        }

        public void setBatchTimeout(Duration batchTimeout) {
            this.batchTimeout = batchTimeout;
        }
    }

    /**
     * Properties for Service Bus integration.
     */
    public static class ServiceBusProperties {
        private boolean enabled = false;
        private String connectionString;
        private String queueName;
        private String topicName;
        private String subscriptionName;
        private int maxBatchSize = 10;
        private Duration lockDuration = Duration.ofMinutes(5);
        private int maxDeliveryCount = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getConnectionString() {
            return connectionString;
        }

        public void setConnectionString(String connectionString) {
            this.connectionString = connectionString;
        }

        public String getQueueName() {
            return queueName;
        }

        public void setQueueName(String queueName) {
            this.queueName = queueName;
        }

        public String getTopicName() {
            return topicName;
        }

        public void setTopicName(String topicName) {
            this.topicName = topicName;
        }

        public String getSubscriptionName() {
            return subscriptionName;
        }

        public void setSubscriptionName(String subscriptionName) {
            this.subscriptionName = subscriptionName;
        }

        public int getMaxBatchSize() {
            return maxBatchSize;
        }

        public void setMaxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
        }

        public Duration getLockDuration() {
            return lockDuration;
        }

        public void setLockDuration(Duration lockDuration) {
            this.lockDuration = lockDuration;
        }

        public int getMaxDeliveryCount() {
            return maxDeliveryCount;
        }

        public void setMaxDeliveryCount(int maxDeliveryCount) {
            this.maxDeliveryCount = maxDeliveryCount;
        }
    }
}