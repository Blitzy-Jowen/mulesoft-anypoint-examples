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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import javax.xml.namespace.QName;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.MockReset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingApplication;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.client.UpstreamSystem;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamRateLimitException;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamUnavailableException;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.model.BatchJobResult;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service.ContactSyncBatchService;
import com.sforce.soap.partner.fault.ApiFault;
import com.sforce.soap.partner.fault.ExceptionCode;
import com.sforce.soap.partner.fault.LoginFault;
import com.sforce.soap.partner.fault.UnexpectedErrorFault;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.SoapFaultException;

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
 * does not stop the schedule. The {@code poll.frequency} check runs on plain instances and in a minimal context that
 * resolves the placeholder, as start-up does.
 */
class ContactPollSchedulerTest {

    private static final String JOB_NAME = "watermarkingBatch";

    /** The packaged {@code poll.frequency}, which the plain instances below are created with. */
    private static final String POLL_FREQUENCY = "10000";

    private static final String FAILED_POLL_PREFIX = "Poll of triggerFlow failed: ";

    private static final String SECRET_MARKER = "SENTINEL_SECRET_7f3";

    private static final String PII_MARKER = "jane.roe@example.com";

    private static final String FORGED_LINE_MARKER = "FORGED_LOG_LINE";

    /** Failure text carrying a secret, an e-mail address and a CR-LF that would start a forged log line. */
    private static final String SENSITIVE_TEXT = "session " + SECRET_MARKER + " of " + PII_MARKER
            + "\r\nERROR c.m.e.s.trigger.ContactPollScheduler     : " + FORGED_LINE_MARKER;

    private static final String PARTNER_FAULT_NAMESPACE = "urn:fault.partner.soap.sforce.com";

    /** The single frame of every failure built through {@link #thrownAt}. */
    private static final StackTraceElement THROW_SITE =
            new StackTraceElement("com.example.vendor.VendorClient", "call", "VendorClient.java", 42);

    /** {@link #THROW_SITE} as the ERROR line renders it. */
    private static final String THROW_SITE_TEXT = " at com.example.vendor.VendorClient.call(VendorClient.java:42)";

    private Logger schedulerLogger;

    private boolean previousAdditive;

    private ListAppender<ILoggingEvent> appender;

    // Attached per test rather than once: Spring Boot resets the Logback context whenever an application context
    // starts, which detaches every appender added earlier.
    @BeforeEach
    void attachLogAppender() {
        schedulerLogger = (Logger) LoggerFactory.getLogger(ContactPollScheduler.class);
        previousAdditive = schedulerLogger.isAdditive();
        // The appender records every line; detaching the console keeps the expected failed-poll ERROR lines out of
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

        assertThat(new ContactPollScheduler(service, POLL_FREQUENCY).pollOnce()).isSameAs(result);

        verify(service, times(1)).synchronize();
        verifyNoMoreInteractions(service);
    }

    @Test
    void pollOncePropagatesTheFailureUnchanged() throws Exception {
        ContactSyncBatchService service = mock(ContactSyncBatchService.class);
        Exception failure = thrownAt(new Exception("query failed"));
        when(service.synchronize()).thenAnswer(throwing(failure));
        ContactPollScheduler scheduler = new ContactPollScheduler(service, POLL_FREQUENCY);

        assertThatThrownBy(scheduler::pollOnce).isSameAs(failure);

        verify(service, times(1)).synchronize();
        // pollOnce is the on-demand run: the failure reaches its caller and is not logged as a failed poll.
        assertThat(errorsWithMessage(FAILED_POLL_PREFIX + "java.lang.Exception" + THROW_SITE_TEXT)).isEmpty();
    }

    @Test
    void pollLogsUpstreamUnavailableAndDoesNotRethrow() throws Exception {
        ContactSyncBatchService service = mock(ContactSyncBatchService.class);
        UpstreamUnavailableException failure = thrownAt(new UpstreamUnavailableException(UpstreamSystem.SALESFORCE,
                "connect timed out", new SocketTimeoutException("connect timed out")));
        when(service.synchronize()).thenAnswer(throwing(failure));
        ContactPollScheduler scheduler = new ContactPollScheduler(service, POLL_FREQUENCY);

        // A poll-triggered flow has no caller to answer: the failure is logged and the next poll proceeds.
        assertThatCode(scheduler::poll).doesNotThrowAnyException();

        // The upstream and the type identifiers only: neither the message nor the exception reaches the logger.
        List<ILoggingEvent> errors = errorsWithMessage(FAILED_POLL_PREFIX + UpstreamUnavailableException.class.getName()
                + " [upstream Salesforce]" + THROW_SITE_TEXT + " <- java.net.SocketTimeoutException");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getThrowableProxy()).isNull();
        assertThat(errors.get(0).getFormattedMessage()).doesNotContain("connect timed out");
        verify(service).synchronize();
        verifyNoMoreInteractions(service);
    }

    @Test
    void pollLogsAnyRuntimeExceptionAndDoesNotRethrow() throws Exception {
        ContactSyncBatchService service = mock(ContactSyncBatchService.class);
        when(service.synchronize()).thenAnswer(throwing(thrownAt(new RuntimeException("boom"))));
        ContactPollScheduler scheduler = new ContactPollScheduler(service, POLL_FREQUENCY);

        assertThatCode(scheduler::poll).doesNotThrowAnyException();

        List<ILoggingEvent> errors =
                errorsWithMessage(FAILED_POLL_PREFIX + RuntimeException.class.getName() + THROW_SITE_TEXT);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getThrowableProxy()).isNull();
        assertThat(errors.get(0).getFormattedMessage()).doesNotContain("boom");
        verify(service).synchronize();
        verifyNoMoreInteractions(service);
    }

    /**
     * Failures whose text carries a secret, an e-mail address and a CR-LF forged log line: in the message, in a
     * cause's message, in a suppressed exception, in a WSC fault's exception message or fault string, and in an SC-9
     * upstream exception's message. Each comes with the description its ERROR line carries instead.
     */
    static Stream<Arguments> failuresCarryingSensitiveText() {
        RuntimeException withSuppressed = thrownAt(new RuntimeException("poll failed"));
        withSuppressed.addSuppressed(new IOException(SENSITIVE_TEXT));
        return Stream.of(
                Arguments.of(Named.of("message", thrownAt(new IllegalStateException(SENSITIVE_TEXT))),
                        IllegalStateException.class.getName() + THROW_SITE_TEXT),
                Arguments.of(Named.of("cause message", thrownAt(new RuntimeException(new IOException(SENSITIVE_TEXT)))),
                        RuntimeException.class.getName() + THROW_SITE_TEXT + " <- java.io.IOException"),
                Arguments.of(Named.of("suppressed exception", withSuppressed),
                        RuntimeException.class.getName() + THROW_SITE_TEXT),
                Arguments.of(Named.of("UnexpectedErrorFault exceptionMessage",
                                thrownAt(fault(new UnexpectedErrorFault(), ExceptionCode.MALFORMED_QUERY))),
                        UnexpectedErrorFault.class.getName() + " [MALFORMED_QUERY]" + THROW_SITE_TEXT),
                Arguments.of(Named.of("LoginFault exceptionMessage",
                                thrownAt(fault(new LoginFault(), ExceptionCode.INVALID_LOGIN))),
                        LoginFault.class.getName() + " [INVALID_LOGIN]" + THROW_SITE_TEXT),
                Arguments.of(Named.of("SOAP fault string", thrownAt(new SoapFaultException(
                                new QName(PARTNER_FAULT_NAMESPACE, "INVALID_SESSION_ID", "sf"), SENSITIVE_TEXT))),
                        SoapFaultException.class.getName() + " [INVALID_SESSION_ID]" + THROW_SITE_TEXT),
                Arguments.of(Named.of("upstream unavailable message", thrownAt(new UpstreamUnavailableException(
                                UpstreamSystem.SALESFORCE, SENSITIVE_TEXT,
                                new ConnectionException(SENSITIVE_TEXT, new ConnectException(SENSITIVE_TEXT))))),
                        UpstreamUnavailableException.class.getName() + " [upstream Salesforce]" + THROW_SITE_TEXT
                                + " <- " + ConnectionException.class.getName() + " <- java.net.ConnectException"),
                Arguments.of(Named.of("upstream authentication message", thrownAt(new UpstreamAuthenticationException(
                                UpstreamSystem.SALESFORCE, SENSITIVE_TEXT,
                                fault(new UnexpectedErrorFault(), ExceptionCode.INVALID_SESSION_ID)))),
                        UpstreamAuthenticationException.class.getName() + " [upstream Salesforce]" + THROW_SITE_TEXT
                                + " <- " + UnexpectedErrorFault.class.getName() + " [INVALID_SESSION_ID]"),
                Arguments.of(Named.of("upstream rate limit message", thrownAt(new UpstreamRateLimitException(
                                UpstreamSystem.SALESFORCE, SENSITIVE_TEXT, null,
                                fault(new UnexpectedErrorFault(), ExceptionCode.REQUEST_LIMIT_EXCEEDED)))),
                        UpstreamRateLimitException.class.getName() + " [upstream Salesforce]" + THROW_SITE_TEXT
                                + " <- " + UnexpectedErrorFault.class.getName() + " [REQUEST_LIMIT_EXCEEDED]"));
    }

    @ParameterizedTest
    @MethodSource("failuresCarryingSensitiveText")
    void pollLogsTypeIdentifiersButNoFailureText(Exception failure, String description) throws Exception {
        ContactSyncBatchService service = mock(ContactSyncBatchService.class);
        when(service.synchronize()).thenAnswer(throwing(failure)).thenReturn(successfulResult());
        ContactPollScheduler scheduler = new ContactPollScheduler(service, POLL_FREQUENCY);

        assertThatCode(scheduler::poll).doesNotThrowAnyException();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).isEqualTo(FAILED_POLL_PREFIX + description);
        // The exception is not handed to the logger, so no appender or layout can print its text or stack trace.
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(Stream.concat(Stream.of(event.getFormattedMessage()),
                        Stream.ofNullable(event.getArgumentArray()).flatMap(Arrays::stream).map(String::valueOf)))
                .allSatisfy(logged -> assertThat(logged)
                        .doesNotContain(SECRET_MARKER, PII_MARKER, FORGED_LINE_MARKER, "\r", "\n"));

        // The failed poll leaves the trigger usable: the next poll runs the job again and logs nothing.
        assertThatCode(scheduler::poll).doesNotThrowAnyException();
        verify(service, times(2)).synchronize();
        verifyNoMoreInteractions(service);
        assertThat(appender.list).hasSize(1);
    }

    /**
     * Cause chains the ERROR line must cut short, and throw-site frames it must escape or render without a source
     * line, each with the description the line carries.
     */
    static Stream<Arguments> failuresWithBoundedDescriptions() {
        RuntimeException circular = thrownAt(new RuntimeException("first"));
        circular.initCause(new IllegalStateException("second", circular));
        Exception cause = new IOException("cause 12");
        for (int depth = 11; depth >= 1; depth--) {
            cause = new IOException("cause " + depth, cause);
        }
        RuntimeException twelveCauses = thrownAt(new RuntimeException("outer", cause));
        return Stream.of(
                Arguments.of(Named.of("circular cause chain", circular), RuntimeException.class.getName()
                        + THROW_SITE_TEXT + " <- java.lang.IllegalStateException <- [circular]"),
                Arguments.of(Named.of("twelve causes", twelveCauses), RuntimeException.class.getName()
                        + THROW_SITE_TEXT + " <- java.io.IOException".repeat(10) + " <- ..."),
                Arguments.of(Named.of("line break and backslash in the throw-site frame", failureWithFrames(
                                new StackTraceElement("com.example.Forged\r\nERROR", "call\\", "Forged.java", 7))),
                        "java.lang.RuntimeException at com.example.Forged\\u000d\\u000aERROR.call\\u005c(Forged.java:7)"),
                Arguments.of(Named.of("native method frame", failureWithFrames(
                                new StackTraceElement("java.lang.Object", "wait", null, -2))),
                        "java.lang.RuntimeException at java.lang.Object.wait(Native Method)"),
                Arguments.of(Named.of("frame without source file", failureWithFrames(
                                new StackTraceElement("com.example.vendor.VendorClient", "call", null, -1))),
                        "java.lang.RuntimeException at com.example.vendor.VendorClient.call(Unknown Source)"),
                Arguments.of(Named.of("no stack trace", failureWithFrames()), "java.lang.RuntimeException"));
    }

    @ParameterizedTest
    @MethodSource("failuresWithBoundedDescriptions")
    void pollBoundsAndEscapesTheLoggedDescription(Exception failure, String description) throws Exception {
        ContactSyncBatchService service = mock(ContactSyncBatchService.class);
        when(service.synchronize()).thenAnswer(throwing(failure));
        ContactPollScheduler scheduler = new ContactPollScheduler(service, POLL_FREQUENCY);

        assertThatCode(scheduler::poll).doesNotThrowAnyException();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).isEqualTo(FAILED_POLL_PREFIX + description);
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void pollIsScheduledEveryTenSecondsByDefault() throws Exception {
        Scheduled scheduled = ContactPollScheduler.class.getMethod("poll").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        // A fixed rate, as the original fixed-frequency-scheduler measured its period between poll starts.
        assertThat(scheduled.fixedRateString()).isEqualTo("${poll.frequency:10000}");
        // The constructor checks the very placeholder the schedule resolves.
        assertThat(ContactPollScheduler.FREQUENCY).isEqualTo(scheduled.fixedRateString());
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

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-5", "PT0S", "PT-1S", "abc", ""})
    void constructorRejectsAFrequencyTheScheduleCannotUse(String frequency) {
        ContactSyncBatchService service = mock(ContactSyncBatchService.class);

        // Named key and value, in place of the executor's IllegalArgumentException without a message.
        assertThatThrownBy(() -> new ContactPollScheduler(service, frequency))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(invalidFrequencyMessage(frequency));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "10000", "PT10S", "pt0.5s"})
    void constructorAcceptsAPositiveFrequency(String frequency) {
        assertThatCode(() -> new ContactPollScheduler(mock(ContactSyncBatchService.class), frequency))
                .doesNotThrowAnyException();
    }

    /** The values the failing start-ups used: the bean's creation fails the context, naming the key and the value. */
    @ParameterizedTest
    @ValueSource(strings = {"-5", "0"})
    void startUpFailsNamingTheKeyAndTheValueOfANonPositiveFrequency(String frequency) {
        new ApplicationContextRunner()
                .withBean(ContactSyncBatchService.class, () -> mock(ContactSyncBatchService.class))
                .withBean(ContactPollScheduler.class)
                .withPropertyValues("poll.frequency=" + frequency)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage(invalidFrequencyMessage(frequency));
                });
    }

    private static String invalidFrequencyMessage(String frequency) {
        return "Invalid poll.frequency value \"" + frequency + "\": the polling frequency must be a positive number"
                + " of milliseconds or a positive ISO-8601 duration";
    }

    private List<ILoggingEvent> errorsWithMessage(String message) {
        // Matched on the exact text, so a line logged by another thread cannot disturb the count.
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR && event.getFormattedMessage().equals(message))
                .toList();
    }

    /** Gives {@code failure} the single frame {@link #THROW_SITE}, so its ERROR line is known exactly. */
    private static <T extends Throwable> T thrownAt(T failure) {
        failure.setStackTrace(new StackTraceElement[] {THROW_SITE});
        return failure;
    }

    private static RuntimeException failureWithFrames(StackTraceElement... frames) {
        RuntimeException failure = new RuntimeException("failure with a chosen stack trace");
        failure.setStackTrace(frames);
        return failure;
    }

    /** Throws {@code failure} itself; Mockito's {@code thenThrow} would refill its stack trace. */
    private static Answer<BatchJobResult> throwing(Exception failure) {
        return invocation -> {
            throw failure;
        };
    }

    /** A WSC fault with {@code code} whose free-text exception message is {@link #SENSITIVE_TEXT}. */
    private static <T extends ApiFault> T fault(T fault, ExceptionCode code) {
        fault.setExceptionCode(code);
        fault.setExceptionMessage(SENSITIVE_TEXT);
        return fault;
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
