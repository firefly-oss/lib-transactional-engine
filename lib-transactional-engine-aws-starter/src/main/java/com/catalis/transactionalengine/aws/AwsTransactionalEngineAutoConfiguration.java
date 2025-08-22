package com.catalis.transactionalengine.aws;

import com.catalis.transactionalengine.observability.SagaEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;

/**
 * Auto-configuration for AWS integration with Transactional Engine.
 * 
 * This configuration provides AWS-specific beans when AWS dependencies are on the classpath:
 * - AWS SDK clients (DynamoDB, CloudWatch, Kinesis) 
 * - AWS-specific SagaEvents implementations for metrics and logging
 * - AWS-specific StepEventPublisher implementations for SQS/Kinesis
 */
@AutoConfiguration
@ConditionalOnClass({
    DynamoDbAsyncClient.class,
    CloudWatchAsyncClient.class,
    KinesisAsyncClient.class
})
@EnableConfigurationProperties(AwsTransactionalEngineProperties.class)
public class AwsTransactionalEngineAutoConfiguration {
    
    private static final Logger log = LoggerFactory.getLogger(AwsTransactionalEngineAutoConfiguration.class);

    /**
     * Creates a DynamoDB async client with default configuration.
     * Users can override this bean to provide custom configuration.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "transactional-engine.aws.dynamodb", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DynamoDbAsyncClient dynamoDbAsyncClient() {
        log.info("Creating DynamoDB async client for Transactional Engine");
        return DynamoDbAsyncClient.builder().build();
    }

    /**
     * Creates a CloudWatch async client for publishing saga metrics.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "transactional-engine.aws.cloudwatch", name = "enabled", havingValue = "true", matchIfMissing = false)
    public CloudWatchAsyncClient cloudWatchAsyncClient() {
        log.info("Creating CloudWatch async client for Transactional Engine metrics");
        return CloudWatchAsyncClient.builder().build();
    }

    /**
     * Creates a Kinesis async client for step event publishing.
     */
    @Bean
    @ConditionalOnMissingBean  
    @ConditionalOnProperty(prefix = "transactional-engine.aws.kinesis", name = "enabled", havingValue = "true", matchIfMissing = false)
    public KinesisAsyncClient kinesisAsyncClient() {
        log.info("Creating Kinesis async client for Transactional Engine step events");
        return KinesisAsyncClient.builder().build();
    }

    /**
     * CloudWatch-based SagaEvents implementation that publishes metrics to CloudWatch.
     * This replaces the default logging-only implementation when CloudWatch is enabled.
     */
    @Bean
    @ConditionalOnMissingBean(SagaEvents.class)
    @ConditionalOnProperty(prefix = "transactional-engine.aws.cloudwatch", name = "enabled", havingValue = "true")
    public SagaEvents cloudWatchSagaEvents(CloudWatchAsyncClient cloudWatchClient, 
                                         AwsTransactionalEngineProperties properties) {
        log.info("Creating CloudWatch-based SagaEvents implementation");
        return new CloudWatchSagaEvents(cloudWatchClient, properties.getCloudwatch());
    }

    /**
     * Kinesis-based StepEventPublisher that publishes step events to a Kinesis stream.
     */
    @Bean
    @ConditionalOnMissingBean(name = "kinesisStepEventPublisher")
    @ConditionalOnProperty(prefix = "transactional-engine.aws.kinesis", name = "enabled", havingValue = "true")
    public KinesisStepEventPublisher kinesisStepEventPublisher(KinesisAsyncClient kinesisClient,
                                                             AwsTransactionalEngineProperties properties) {
        log.info("Creating Kinesis-based StepEventPublisher");
        return new KinesisStepEventPublisher(kinesisClient, properties.getKinesis());
    }

    /**
     * SQS-based StepEventPublisher that publishes step events to SQS queues.
     * Requires Spring Cloud AWS SQS starter to be on the classpath.
     */
    @Bean
    @ConditionalOnMissingBean(name = "sqsStepEventPublisher")
    @ConditionalOnClass(name = "io.awspring.cloud.sqs.operations.SqsTemplate")
    @ConditionalOnProperty(prefix = "transactional-engine.aws.sqs", name = "enabled", havingValue = "true")
    public SqsStepEventPublisher sqsStepEventPublisher(AwsTransactionalEngineProperties properties) {
        log.info("Creating SQS-based StepEventPublisher");
        return new SqsStepEventPublisher(properties.getSqs());
    }
}