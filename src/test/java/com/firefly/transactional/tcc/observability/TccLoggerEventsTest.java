/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.firefly.transactional.tcc.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.firefly.transactional.tcc.core.TccContext;
import com.firefly.transactional.tcc.core.TccPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for TccLoggerEvents implementation.
 */
class TccLoggerEventsTest {

    private TccLoggerEvents tccEvents;
    private ListAppender<ILoggingEvent> listAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        tccEvents = new TccLoggerEvents();
        
        // Set up log capture
        logger = (Logger) LoggerFactory.getLogger(TccLoggerEvents.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(listAppender);
    }

    @Test
    void shouldLogTccStartedEvent() {
        // When
        tccEvents.onTccStarted("TestTcc", "corr-123");

        // Then
        List<ILoggingEvent> logEvents = listAppender.list;
        assertThat(logEvents).hasSize(1);
        
        ILoggingEvent event = logEvents.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("tcc_event\":\"started");
        assertThat(event.getFormattedMessage()).contains("TestTcc");
        assertThat(event.getFormattedMessage()).contains("corr-123");
    }

    @Test
    void shouldLogTccStartedWithContextEvent() {
        // Given
        TccContext context = new TccContext("corr-123");
        context.setHeader("user-id", "user123");
        context.setVariable("amount", 100.0);

        // When
        tccEvents.onTccStarted("TestTcc", "corr-123", context);

        // Then
        List<ILoggingEvent> logEvents = listAppender.list;
        assertThat(logEvents).hasSize(1);
        
        ILoggingEvent event = logEvents.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("tcc_event\":\"started_with_context");
        assertThat(event.getFormattedMessage()).contains("headers_count\":\"1");
        assertThat(event.getFormattedMessage()).contains("variables_count\":\"1");
    }

    @Test
    void shouldLogTccCompletedEvent() {
        // When
        tccEvents.onTccCompleted("TestTcc", "corr-123", true, TccPhase.CONFIRM, 1500L);

        // Then
        List<ILoggingEvent> logEvents = listAppender.list;
        assertThat(logEvents).hasSize(1);
        
        ILoggingEvent event = logEvents.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("tcc_event\":\"completed");
        assertThat(event.getFormattedMessage()).contains("success\":\"true");
        assertThat(event.getFormattedMessage()).contains("final_phase\":\"CONFIRM");
        assertThat(event.getFormattedMessage()).contains("duration_ms\":\"1500");
    }

    @Test
    void shouldLogPhaseStartedEvent() {
        // When
        tccEvents.onPhaseStarted("TestTcc", "corr-123", TccPhase.TRY);

        // Then
        List<ILoggingEvent> logEvents = listAppender.list;
        assertThat(logEvents).hasSize(1);
        
        ILoggingEvent event = logEvents.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("tcc_event\":\"phase_started");
        assertThat(event.getFormattedMessage()).contains("phase\":\"TRY");
    }

    @Test
    void shouldLogPhaseCompletedEvent() {
        // When
        tccEvents.onPhaseCompleted("TestTcc", "corr-123", TccPhase.TRY, 500L);

        // Then
        List<ILoggingEvent> logEvents = listAppender.list;
        assertThat(logEvents).hasSize(1);
        
        ILoggingEvent event = logEvents.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("tcc_event\":\"phase_completed");
        assertThat(event.getFormattedMessage()).contains("phase\":\"TRY");
        assertThat(event.getFormattedMessage()).contains("duration_ms\":\"500");
    }

    @Test
    void shouldLogPhaseFailedEvent() {
        // Given
        RuntimeException error = new RuntimeException("Phase failed");

        // When
        tccEvents.onPhaseFailed("TestTcc", "corr-123", TccPhase.TRY, error, 300L);

        // Then
        List<ILoggingEvent> logEvents = listAppender.list;
        assertThat(logEvents).hasSize(1);
        
        ILoggingEvent event = logEvents.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("tcc_event\":\"phase_failed");
        assertThat(event.getFormattedMessage()).contains("phase\":\"TRY");
        assertThat(event.getFormattedMessage()).contains("error_class\":\"RuntimeException");
        assertThat(event.getFormattedMessage()).contains("Phase failed");
    }

    @Test
    void shouldLogParticipantStartedEvent() {
        // When
        tccEvents.onParticipantStarted("TestTcc", "corr-123", "payment", TccPhase.TRY);

        // Then
        List<ILoggingEvent> logEvents = listAppender.list;
        assertThat(logEvents).hasSize(1);
        
        ILoggingEvent event = logEvents.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("tcc_event\":\"participant_started");
        assertThat(event.getFormattedMessage()).contains("participant\":\"payment");
        assertThat(event.getFormattedMessage()).contains("phase\":\"TRY");
    }

    @Test
    void shouldLogParticipantSuccessEvent() {
        // When
        tccEvents.onParticipantSuccess("TestTcc", "corr-123", "payment", TccPhase.TRY, 1, 200L);

        // Then
        List<ILoggingEvent> logEvents = listAppender.list;
        assertThat(logEvents).hasSize(1);
        
        ILoggingEvent event = logEvents.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("tcc_event\":\"participant_success");
        assertThat(event.getFormattedMessage()).contains("participant\":\"payment");
        assertThat(event.getFormattedMessage()).contains("attempts\":\"1");
        assertThat(event.getFormattedMessage()).contains("duration_ms\":\"200");
    }

    @Test
    void shouldLogParticipantFailedEvent() {
        // Given
        RuntimeException error = new RuntimeException("Payment failed");

        // When
        tccEvents.onParticipantFailed("TestTcc", "corr-123", "payment", TccPhase.TRY, error, 2, 400L);

        // Then
        List<ILoggingEvent> logEvents = listAppender.list;
        assertThat(logEvents).hasSize(1);
        
        ILoggingEvent event = logEvents.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("tcc_event\":\"participant_failed");
        assertThat(event.getFormattedMessage()).contains("participant\":\"payment");
        assertThat(event.getFormattedMessage()).contains("attempts\":\"2");
        assertThat(event.getFormattedMessage()).contains("error_class\":\"RuntimeException");
        assertThat(event.getFormattedMessage()).contains("Payment failed");
    }

    @Test
    void shouldLogParticipantRetryEvent() {
        // Given
        RuntimeException error = new RuntimeException("Temporary failure");

        // When
        tccEvents.onParticipantRetry("TestTcc", "corr-123", "payment", TccPhase.TRY, 2, error);

        // Then
        List<ILoggingEvent> logEvents = listAppender.list;
        assertThat(logEvents).hasSize(1);
        
        ILoggingEvent event = logEvents.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("tcc_event\":\"participant_retry");
        assertThat(event.getFormattedMessage()).contains("participant\":\"payment");
        assertThat(event.getFormattedMessage()).contains("attempt\":\"2");
        assertThat(event.getFormattedMessage()).contains("error_class\":\"RuntimeException");
    }
}
