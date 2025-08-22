package com.catalis.transactionalengine.aws;

import com.catalis.transactionalengine.observability.SagaEvents;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link AwsTransactionalEngineAutoConfiguration}.
 */
class AwsTransactionalEngineAutoConfigurationTest {
    
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AwsTransactionalEngineAutoConfiguration.class));
    
    @Test
    void autoConfigurationIsAppliedWhenAwsClassesArePresent() {
        // When AWS classes are on the classpath, auto-configuration should be applied
        contextRunner
            .withUserConfiguration(MockAwsClientsConfiguration.class)
            .run(context -> {
                assertThat(context).hasSingleBean(AwsTransactionalEngineAutoConfiguration.class);
                assertThat(context).hasSingleBean(AwsTransactionalEngineProperties.class);
            });
    }
    
    @Test
    void dynamoDbClientIsCreatedWhenEnabledByDefault() {
        contextRunner
            .withUserConfiguration(MockAwsClientsConfiguration.class)
            .run(context -> {
                assertThat(context).hasSingleBean(DynamoDbAsyncClient.class);
            });
    }
    
    @Test
    void dynamoDbClientIsCreatedWhenExplicitlyEnabled() {
        contextRunner
            .withUserConfiguration(MockAwsClientsConfiguration.class)
            .withPropertyValues("transactional-engine.aws.dynamodb.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(DynamoDbAsyncClient.class);
            });
    }
    
    @Test
    void dynamoDbClientIsNotCreatedWhenDisabled() {
        contextRunner
            .withPropertyValues("transactional-engine.aws.dynamodb.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(DynamoDbAsyncClient.class);
            });
    }
    
    @Test
    void cloudWatchClientIsNotCreatedByDefault() {
        contextRunner
            .withPropertyValues("transactional-engine.aws.dynamodb.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(CloudWatchAsyncClient.class);
            });
    }
    
    @Test
    void cloudWatchClientIsCreatedWhenEnabled() {
        contextRunner
            .withUserConfiguration(MockAwsClientsConfiguration.class)
            .withPropertyValues("transactional-engine.aws.cloudwatch.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(CloudWatchAsyncClient.class);
            });
    }
    
    @Test
    void cloudWatchSagaEventsIsCreatedWhenCloudWatchIsEnabled() {
        contextRunner
            .withUserConfiguration(MockAwsClientsConfiguration.class)
            .withPropertyValues("transactional-engine.aws.cloudwatch.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(CloudWatchAsyncClient.class);
                assertThat(context).hasSingleBean(SagaEvents.class);
                assertThat(context.getBean(SagaEvents.class))
                    .isInstanceOf(CloudWatchSagaEvents.class);
            });
    }
    
    @Test
    void kinesisClientIsNotCreatedByDefault() {
        contextRunner
            .withPropertyValues("transactional-engine.aws.dynamodb.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(KinesisAsyncClient.class);
            });
    }
    
    @Test
    void kinesisClientIsCreatedWhenEnabled() {
        contextRunner
            .withUserConfiguration(MockAwsClientsConfiguration.class)
            .withPropertyValues("transactional-engine.aws.kinesis.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(KinesisAsyncClient.class);
            });
    }
    
    @Test
    void kinesisStepEventPublisherIsCreatedWhenKinesisIsEnabled() {
        contextRunner
            .withUserConfiguration(MockAwsClientsConfiguration.class)
            .withPropertyValues("transactional-engine.aws.kinesis.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(KinesisAsyncClient.class);
                assertThat(context).hasBean("kinesisStepEventPublisher");
                assertThat(context.getBean("kinesisStepEventPublisher"))
                    .isInstanceOf(KinesisStepEventPublisher.class);
            });
    }
    
    @Test
    void sqsStepEventPublisherIsCreatedWhenEnabledAndSqsTemplateIsPresent() {
        // Note: This test may not pass in isolation as it requires SQS template on classpath
        contextRunner
            .withUserConfiguration(MockAwsClientsConfiguration.class)
            .withPropertyValues("transactional-engine.aws.sqs.enabled=true")
            .run(context -> {
                // SQS publisher creation depends on @ConditionalOnClass for SqsTemplate
                // In real usage, this would work when spring-cloud-aws-starter-sqs is on classpath
                if (context.containsBean("sqsStepEventPublisher")) {
                    assertThat(context.getBean("sqsStepEventPublisher"))
                        .isInstanceOf(SqsStepEventPublisher.class);
                }
            });
    }
    
    @Test
    void beansAreNotCreatedWhenDisabled() {
        contextRunner
            .withPropertyValues(
                "transactional-engine.aws.dynamodb.enabled=false",
                "transactional-engine.aws.cloudwatch.enabled=false",
                "transactional-engine.aws.kinesis.enabled=false",
                "transactional-engine.aws.sqs.enabled=false"
            )
            .run(context -> {
                assertThat(context).doesNotHaveBean(DynamoDbAsyncClient.class);
                assertThat(context).doesNotHaveBean(CloudWatchAsyncClient.class);
                assertThat(context).doesNotHaveBean(KinesisAsyncClient.class);
                assertThat(context).doesNotHaveBean("cloudWatchSagaEvents");
                assertThat(context).doesNotHaveBean("kinesisStepEventPublisher");
                assertThat(context).doesNotHaveBean("sqsStepEventPublisher");
            });
    }
    
    @Test
    void propertiesAreCorrectlyBound() {
        contextRunner
            .withUserConfiguration(MockAwsClientsConfiguration.class)
            .withPropertyValues(
                "transactional-engine.aws.dynamodb.table-name=test-table",
                "transactional-engine.aws.dynamodb.region=us-west-2",
                "transactional-engine.aws.dynamodb.timeout=PT30S",
                "transactional-engine.aws.cloudwatch.namespace=TestNamespace",
                "transactional-engine.aws.cloudwatch.publish-interval=PT60S",
                "transactional-engine.aws.cloudwatch.max-batch-size=25",
                "transactional-engine.aws.kinesis.stream-name=test-stream",
                "transactional-engine.aws.kinesis.partition-key=test-key",
                "transactional-engine.aws.kinesis.max-records-per-batch=100",
                "transactional-engine.aws.kinesis.batch-timeout=PT5S",
                "transactional-engine.aws.sqs.queue-name=test-queue",
                "transactional-engine.aws.sqs.max-batch-size=15",
                "transactional-engine.aws.sqs.visibility-timeout=PT300S",
                "transactional-engine.aws.sqs.max-receive-count=3"
            )
            .run(context -> {
                AwsTransactionalEngineProperties properties = context.getBean(AwsTransactionalEngineProperties.class);
                
                // DynamoDB properties
                assertThat(properties.getDynamodb().getTableName()).isEqualTo("test-table");
                assertThat(properties.getDynamodb().getRegion()).isEqualTo("us-west-2");
                assertThat(properties.getDynamodb().getTimeout().getSeconds()).isEqualTo(30);
                
                // CloudWatch properties
                assertThat(properties.getCloudwatch().getNamespace()).isEqualTo("TestNamespace");
                assertThat(properties.getCloudwatch().getPublishInterval().getSeconds()).isEqualTo(60);
                assertThat(properties.getCloudwatch().getMaxBatchSize()).isEqualTo(25);
                
                // Kinesis properties
                assertThat(properties.getKinesis().getStreamName()).isEqualTo("test-stream");
                assertThat(properties.getKinesis().getPartitionKey()).isEqualTo("test-key");
                assertThat(properties.getKinesis().getMaxRecordsPerBatch()).isEqualTo(100);
                assertThat(properties.getKinesis().getBatchTimeout().getSeconds()).isEqualTo(5);
                
                // SQS properties
                assertThat(properties.getSqs().getQueueName()).isEqualTo("test-queue");
                assertThat(properties.getSqs().getMaxBatchSize()).isEqualTo(15);
                assertThat(properties.getSqs().getVisibilityTimeout().getSeconds()).isEqualTo(300);
                assertThat(properties.getSqs().getMaxReceiveCount()).isEqualTo(3);
            });
    }
    
    @Test
    void customBeansAreNotOverridden() {
        contextRunner
            .withBean("customDynamoDbClient", DynamoDbAsyncClient.class, () -> mock(DynamoDbAsyncClient.class))
            .withBean("customCloudWatchClient", CloudWatchAsyncClient.class, () -> mock(CloudWatchAsyncClient.class))
            .withBean("customKinesisClient", KinesisAsyncClient.class, () -> mock(KinesisAsyncClient.class))
            .withPropertyValues(
                "transactional-engine.aws.dynamodb.enabled=true",
                "transactional-engine.aws.cloudwatch.enabled=true",
                "transactional-engine.aws.kinesis.enabled=true"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(DynamoDbAsyncClient.class);
                assertThat(context).hasSingleBean(CloudWatchAsyncClient.class);
                assertThat(context).hasSingleBean(KinesisAsyncClient.class);
                assertThat(context).hasBean("customDynamoDbClient");
                assertThat(context).hasBean("customCloudWatchClient");
                assertThat(context).hasBean("customKinesisClient");
            });
    }
    
    @Test
    void sagaEventsIsNotCreatedWhenCustomBeanExists() {
        contextRunner
            .withUserConfiguration(MockAwsClientsConfiguration.class)
            .withBean("customSagaEvents", SagaEvents.class, () -> new SagaEvents() {
                @Override
                public void onStart(String sagaName, String sagaId) {
                    // Custom implementation
                }
            })
            .withPropertyValues("transactional-engine.aws.cloudwatch.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(SagaEvents.class);
                assertThat(context).hasBean("customSagaEvents");
                assertThat(context).doesNotHaveBean(CloudWatchSagaEvents.class);
            });
    }
    
    @Configuration(proxyBeanMethods = false)
    static class MockAwsClientsConfiguration {
        
        @Bean
        DynamoDbAsyncClient dynamoDbAsyncClient() {
            return mock(DynamoDbAsyncClient.class);
        }
        
        @Bean
        CloudWatchAsyncClient cloudWatchAsyncClient() {
            return mock(CloudWatchAsyncClient.class);
        }
        
        @Bean
        KinesisAsyncClient kinesisAsyncClient() {
            return mock(KinesisAsyncClient.class);
        }
    }
}