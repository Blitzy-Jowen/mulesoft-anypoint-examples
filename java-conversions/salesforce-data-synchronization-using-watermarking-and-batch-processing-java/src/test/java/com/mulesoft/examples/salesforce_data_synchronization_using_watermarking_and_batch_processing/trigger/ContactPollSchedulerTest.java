/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.MockReset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingApplication;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.client.UpstreamSystem;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamUnavailableException;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.model.BatchJobResult;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service.ContactSyncBatchService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Trigger test of {@link ContactPollScheduler}, the replacement of the original flow {@code triggerFlow}'s
 * {@code <poll>} with its 10 s {@code fixed-frequency-scheduler} ({@code watermarking.xml}). The original has no
 * HTTP listener, so this test stands in for an endpoint test.
 *
 * <p>The original IT stopped the flow's scheduler and drove it with {@code runSchedulersOnce("triggerFlow")}; here
 * {@link ContactPollScheduler#pollOnce()} is that single run, and the outer tests call it, and the scheduled
 * {@link ContactPollScheduler#poll()}, on a plain instance without a Spring context. {@link ScheduledPolling} then
 * runs the real {@code @Scheduled} task in the application context, at a 100 ms rate, to prove that a failed poll
 * does not stop the schedule.
 */
class ContactPollSchedulerTest {

    private static final String JOB_NAME = "watermarkingBatch";

    private Logger schedulerLogger;

    private boolean previousAdditive;

    private ListAppender<ILoggingEvent> appender;

    // Attached per test rather than once: Spring Boot resets the Logback context whenever an application context
    // starts, which detaches every appender added earlier.
    @BeforeEach
    void attachLogAppender() {
        schedulerLogger = (Logger) LoggerFactory.getLogger(ContactPollScheduler.class);
        previousAdditive = schedulerLogger.isAdditive();
        // The appender records every line; detaching the console keeps the expected failure stack traces out of
        // the build log.
        schedulerLogger.setAdditive(false);
        appender = new ListAppender<>();
        appender.start();
        schedulerLogger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        schedulerLogger.detachAppender(appender);
        appender.stop();
        schedulerLogger.setAdditive(previousAdditive);
    }

    @Test
    void pollOnceReturnsTheBatchResultUnchanged() throws Exception {
        ContactSyncBatchService service = mock(ContactSyncBatchService.class);
        BatchJobResult result = successfulResult();
        when(service.synchronize()).thenReturn(result);

        assertThat(new ContactPollScheduler(service).pollOnce()).isSameAs(result);

        verify(service, times(1)).synchronize();
        verifyNoMoreInteractions(service);
    }

    @Test
    void pollOncePropagatesTheFailureUnchanged() throws Exception {
        ContactSyncBatchService service = mock(ContactSyncBatchService.class);
        Exception failure = new Exception("query failed");
        when(service.synchronize()).thenThrow(failure);
        ContactPollScheduler scheduler = new ContactPollScheduler(service);

        assertThatThrownBy(scheduler::pollOnce).isSameAs(failure);

        verify(service, times(1)).synchronize();
        // pollOnce is the on-demand run: the failure reaches its caller and is not logged as a failed poll.
        assertThat(errorsWithMessage("Poll of triggerFlow failed: query failed")).isEmpty();
    }

    @Test
    void pollLogsUpstreamUnavailableAndDoesNotRethrow() throws Exception {
        ContactSyncBatchService service = mock(ContactSyncBatchService.class);
        UpstreamUnavailableException failure = new UpstreamUnavailableException(UpstreamSystem.SALESFORCE,
                "connect timed out", new SocketTimeoutException("connect timed out"));
        when(service.synchronize()).thenThrow(failure);
        ContactPollScheduler scheduler = new ContactPollScheduler(service);

        // A poll-triggered flow has no caller to answer: the failure is logged and the next poll proceeds.
        assertThatCode(scheduler::poll).doesNotThrowAnyException();

        List<ILoggingEvent> errors = errorsWithMessage("Poll of triggerFlow failed: connect timed out");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getThrowableProxy()).isNotNull();
        assertThat(errors.get(0).getThrowableProxy().getClassName())
                .isEqualTo(UpstreamUnavailableException.class.getName());
        verify(service).synchronize();
        verifyNoMoreInteractions(service);
    }

    @Test
    void pollLogsAnyRuntimeExceptionAndDoesNotRethrow() throws Exception {
        ContactSyncBatchService service = mock(ContactSyncBatchService.class);
        when(service.synchronize()).thenThrow(new RuntimeException("boom"));
        ContactPollScheduler scheduler = new ContactPollScheduler(service);

        assertThatCode(scheduler::poll).doesNotThrowAnyException();

        List<ILoggingEvent> errors = errorsWithMessage("Poll of triggerFlow failed: boom");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getThrowableProxy().getClassName()).isEqualTo(RuntimeException.class.getName());
        verify(service).synchronize();
        verifyNoMoreInteractions(service);
    }

    @Test
    void pollIsScheduledEveryTenSecondsByDefault() throws Exception {
        Scheduled scheduled = ContactPollScheduler.class.getMethod("poll").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        // A fixed rate, as the original fixed-frequency-scheduler measured its period between poll starts.
        assertThat(scheduled.fixedRateString()).isEqualTo("${poll.frequency:10000}");
        assertThat(scheduled.initialDelayString()).isEqualTo("${poll.initial-delay:0}");
        assertThat(scheduled.fixedDelay()).isEqualTo(-1L);
        assertThat(scheduled.fixedDelayString()).isEmpty();
        assertThat(scheduled.cron()).isEmpty();

        // The packaged configuration keeps the original frequency="10" timeUnit="SECONDS" and polls from start-up.
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties configuration = yaml.getObject();
        assertThat(configuration).isNotNull();
        assertThat(configuration.getProperty("poll.frequency")).isEqualTo("10000");
        assertThat(configuration.getProperty("poll.initial-delay")).isEqualTo("0");
    }

    private List<ILoggingEvent> errorsWithMessage(String message) {
        // Matched on the exact text, so a line logged by another thread cannot disturb the count.
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR && event.getFormattedMessage().equals(message))
                .toList();
    }

    private static BatchJobResult successfulResult() {
        return new BatchJobResult(JOB_NAME, UUID.randomUUID().toString(), BatchJobResult.Status.SUCCESSFUL,
                0, 0, 0, 0, 0);
    }

    /** Marker bean whose creation completes the stubbing of the mocked service. */
    static final class FirstPollStubbed {
    }

    /**
     * Stubs the mocked {@link ContactSyncBatchService} while the context refreshes. {@code @Scheduled} tasks start
     * only once the context has refreshed, after every singleton, so the stub is complete before the scheduler
     * thread first calls the mock; stubbing in the test method would race that thread.
     *
     * <p>Spring Boot's test type filter keeps this class out of component scanning; only {@link ScheduledPolling}
     * imports it.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingFirstPollConfig {

        @Bean
        FirstPollStubbed firstPollStubbed(ContactSyncBatchService service) throws Exception {
            assertThat(Mockito.mockingDetails(service).isMock()).isTrue();
            when(service.synchronize()).thenThrow(new RuntimeException("first poll fails"))
                    .thenReturn(successfulResult());
            return new FirstPollStubbed();
        }
    }

    /**
     * The real schedule in the application context. Inlined test properties outrank surefire's
     * {@code poll.initial-delay=3600000} system property, so the task fires at once and then every 100 ms. The
     * context is closed after the class, which stops the scheduler before any later test class runs.
     */
    @Nested
    @SpringBootTest(classes = SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @TestPropertySource(properties = {
            "poll.frequency=100",
            "poll.initial-delay=0",
            "watermark.store-path=target/test-watermark/contact-poll-scheduler-test/watermark.properties"})
    @Import(ContactPollSchedulerTest.FailingFirstPollConfig.class)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class ScheduledPolling {

        // Never reset: the scheduler thread keeps calling the mock until the context closes, and the stub comes
        // from FailingFirstPollConfig.
        @MockBean(reset = MockReset.NONE)
        ContactSyncBatchService service;

        @Test
        void scheduleKeepsPollingAfterAFailedPoll() throws Exception {
            // The first scheduled call throws and poll() swallows it; three calls prove the schedule survived, as
            // Mule's poll kept firing after a failed run.
            Awaitility.await()
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> verify(service, atLeast(3)).synchronize());
        }
    }
}
