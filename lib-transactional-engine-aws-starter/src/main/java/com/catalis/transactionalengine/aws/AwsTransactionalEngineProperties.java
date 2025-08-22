package com.catalis.transactionalengine.aws;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for AWS integration with Transactional Engine.
 */
@ConfigurationProperties(prefix = "transactional-engine.aws")
public class AwsTransactionalEngineProperties {

    private DynamoDbProperties dynamodb = new DynamoDbProperties();
    private CloudWatchProperties cloudwatch = new CloudWatchProperties();
    private KinesisProperties kinesis = new KinesisProperties();
    private SqsProperties sqs = new SqsProperties();

    public DynamoDbProperties getDynamodb() {
        return dynamodb;
    }

    public void setDynamodb(DynamoDbProperties dynamodb) {
        this.dynamodb = dynamodb;
    }

    public CloudWatchProperties getCloudwatch() {
        return cloudwatch;
    }

    public void setCloudwatch(CloudWatchProperties cloudwatch) {
        this.cloudwatch = cloudwatch;
    }

    public KinesisProperties getKinesis() {
        return kinesis;
    }

    public void setKinesis(KinesisProperties kinesis) {
        this.kinesis = kinesis;
    }

    public SqsProperties getSqs() {
        return sqs;
    }

    public void setSqs(SqsProperties sqs) {
        this.sqs = sqs;
    }

    /**
     * DynamoDB-specific configuration properties.
     */
    public static class DynamoDbProperties {
        private boolean enabled = true;
        private String tableName = "saga-context";
        private String region = "us-east-1";
        private Duration timeout = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /**
     * CloudWatch-specific configuration properties.
     */
    public static class CloudWatchProperties {
        private boolean enabled = false;
        private String namespace = "TransactionalEngine";
        private Duration publishInterval = Duration.ofMinutes(1);
        private int maxBatchSize = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
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
     * Kinesis-specific configuration properties.
     */
    public static class KinesisProperties {
        private boolean enabled = false;
        private String streamName = "saga-step-events";
        private String partitionKey = "sagaId";
        private int maxRecordsPerBatch = 500;
        private Duration batchTimeout = Duration.ofSeconds(1);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getStreamName() {
            return streamName;
        }

        public void setStreamName(String streamName) {
            this.streamName = streamName;
        }

        public String getPartitionKey() {
            return partitionKey;
        }

        public void setPartitionKey(String partitionKey) {
            this.partitionKey = partitionKey;
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
     * SQS-specific configuration properties.
     */
    public static class SqsProperties {
        private boolean enabled = false;
        private String queueName = "saga-step-events";
        private int maxBatchSize = 10;
        private Duration visibilityTimeout = Duration.ofSeconds(30);
        private int maxReceiveCount = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getQueueName() {
            return queueName;
        }

        public void setQueueName(String queueName) {
            this.queueName = queueName;
        }

        public int getMaxBatchSize() {
            return maxBatchSize;
        }

        public void setMaxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
        }

        public Duration getVisibilityTimeout() {
            return visibilityTimeout;
        }

        public void setVisibilityTimeout(Duration visibilityTimeout) {
            this.visibilityTimeout = visibilityTimeout;
        }

        public int getMaxReceiveCount() {
            return maxReceiveCount;
        }

        public void setMaxReceiveCount(int maxReceiveCount) {
            this.maxReceiveCount = maxReceiveCount;
        }
    }
}