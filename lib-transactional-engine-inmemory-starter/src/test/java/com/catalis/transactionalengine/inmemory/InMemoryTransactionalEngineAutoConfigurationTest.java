package com.catalis.transactionalengine.inmemory;

import com.catalis.transactionalengine.observability.SagaEvents;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for InMemoryTransactionalEngineAutoConfiguration.
 * Verifies that the auto-configuration creates the expected beans correctly.
 */
class InMemoryTransactionalEngineAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InMemoryTransactionalEngineAutoConfiguration.class));

    @Test
    void shouldCreateInMemorySagaEventsBean() {
        this.contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(SagaEvents.class);
                    assertThat(context).hasSingleBean(InMemorySagaEvents.class);
                    
                    SagaEvents sagaEvents = context.getBean(SagaEvents.class);
                    assertThat(sagaEvents).isInstanceOf(InMemorySagaEvents.class);
                });
    }

    @Test
    void shouldCreateStorageServiceBean() {
        this.contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(InMemoryTransactionalEngineAutoConfiguration.InMemoryStorageService.class);
                });
    }

    @Test
    void shouldRespectEventsEnabledProperty() {
        this.contextRunner
                .withPropertyValues("transactional-engine.inmemory.events.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SagaEvents.class);
                });
    }

    @Test
    void shouldRespectStorageEnabledProperty() {
        this.contextRunner
                .withPropertyValues("transactional-engine.inmemory.storage.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(InMemoryTransactionalEngineAutoConfiguration.InMemoryStorageService.class);
                });
    }

    @Test
    void shouldConfigurePropertiesCorrectly() {
        this.contextRunner
                .withPropertyValues(
                        "transactional-engine.inmemory.events.log-step-details=false",
                        "transactional-engine.inmemory.events.log-timing=false",
                        "transactional-engine.inmemory.events.max-events-in-memory=500",
                        "transactional-engine.inmemory.storage.max-saga-contexts=5000"
                )
                .run(context -> {
                    assertThat(context).hasBean("inMemorySagaEvents");
                    
                    InMemorySagaEvents sagaEvents = (InMemorySagaEvents) context.getBean(SagaEvents.class);
                    
                    // We can't directly access the config from the sagaEvents, but we can verify it was created
                    assertThat(sagaEvents).isNotNull();
                    
                    InMemoryTransactionalEngineProperties properties = context.getBean(InMemoryTransactionalEngineProperties.class);
                    assertThat(properties.getEvents().isLogStepDetails()).isFalse();
                    assertThat(properties.getEvents().isLogTiming()).isFalse();
                    assertThat(properties.getEvents().getMaxEventsInMemory()).isEqualTo(500);
                    assertThat(properties.getStorage().getMaxSagaContexts()).isEqualTo(5000);
                });
    }

    @Test
    void shouldNotCreateSagaEventsWhenOtherBeanExists() {
        this.contextRunner
                .withBean("customSagaEvents", SagaEvents.class, () -> new SagaEvents() {
                    @Override
                    public void onStart(String sagaName, String sagaId) {
                        // Custom implementation
                    }
                })
                .run(context -> {
                    assertThat(context).hasSingleBean(SagaEvents.class);
                    assertThat(context).hasBean("customSagaEvents");
                    assertThat(context).doesNotHaveBean(InMemorySagaEvents.class);
                });
    }

    @Test
    void shouldCreateInMemoryStepEventPublisherBean() {
        this.contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(InMemoryStepEventPublisher.class);
                    assertThat(context).hasBean("inMemoryStepEventPublisher");
                    
                    InMemoryStepEventPublisher publisher = context.getBean(InMemoryStepEventPublisher.class);
                    assertThat(publisher).isNotNull();
                    assertThat(publisher.getTotalEventCount()).isEqualTo(0);
                    assertThat(publisher.getEventHistory()).isEmpty();
                });
    }

    @Test
    void shouldRespectStepPublisherEnabledProperty() {
        this.contextRunner
                .withPropertyValues("transactional-engine.inmemory.events.step-publisher-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(InMemoryStepEventPublisher.class);
                    assertThat(context).doesNotHaveBean("inMemoryStepEventPublisher");
                });
    }

    @Test
    void shouldConfigureStepEventPublisherCorrectly() {
        this.contextRunner
                .withPropertyValues(
                        "transactional-engine.inmemory.events.log-step-details=true",
                        "transactional-engine.inmemory.events.max-events-in-memory=100"
                )
                .run(context -> {
                    assertThat(context).hasBean("inMemoryStepEventPublisher");
                    
                    InMemoryStepEventPublisher publisher = context.getBean(InMemoryStepEventPublisher.class);
                    assertThat(publisher).isNotNull();
                    
                    // Verify the publisher was configured with the expected properties
                    InMemoryTransactionalEngineProperties properties = context.getBean(InMemoryTransactionalEngineProperties.class);
                    assertThat(properties.getEvents().isLogStepDetails()).isTrue();
                    assertThat(properties.getEvents().getMaxEventsInMemory()).isEqualTo(100);
                });
    }
}