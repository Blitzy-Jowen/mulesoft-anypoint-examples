/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_leads_into_salesforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import com.mulesoft.examples.import_leads_into_salesforce.client.SalesforcePartnerClient;
import com.sforce.soap.partner.DeleteResult;
import com.sforce.soap.partner.IError;
import com.sforce.soap.partner.StatusCode;
import com.sforce.soap.partner.fault.ExceptionCode;
import com.sforce.soap.partner.fault.MalformedQueryFault;
import com.sforce.ws.ConnectionException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests the log safety of the cleanup of the live test {@link ImportLeadsToSfdcIT}, {@code tearDown()}, without
 * credentials, a Spring context or Salesforce: the class is created with {@code new}, a Mockito-mocked
 * {@link SalesforcePartnerClient} takes the place of its autowired client, and the state its test method leaves
 * behind (the dropped {@code leads.csv}, the Lead IDs read back) is set directly.
 *
 * <p>Each failed query or delete carries a token, a password and Lead fields in its message, its causes' messages
 * and its suppressed exception, and each failed delete result carries them in its errors' messages and fields. Every
 * event must name the failure by its class names or status codes alone and attach no throwable, which Logback would
 * print with all of that text, while every Lead is still deleted and nothing is thrown.
 *
 * <p>The live test's wait, {@code awaitLead}, is tested for the lifetime of its query: a query answered only after
 * the wait's timeout must still be answered before the wait ends, on the thread that runs the wait, so that
 * {@code tearDown()} finds the Lead ID it read back and deletes that Lead.
 */
class ImportLeadsToSfdcITCleanupTest {

    /** The two e-mails of {@code leads.csv} the live test reads its Leads back by. */
    private static final String EMAIL1 = "faucibus@egetmetus.org";

    private static final String LEAD_ID1 = "00Q5e00000Ab1CdEAA";

    private static final String LEAD_ID2 = "00Q5e00000Ab2CdEAA";

    private static final String TOKEN = "token=s3cr3t-token";

    private static final String PASSWORD = "password=hunter2";

    private static final String PERSONAL_EMAIL = "ishmael.burks@example.com";

    private static final String LAST_NAME = "LastName='Burks'";

    /**
     * Every value a logged event must not contain: the sensitive values and the text that marks each part of a
     * failure, so that no message, cause message, suppressed message or error detail is repeated.
     */
    private static final List<String> UNDISCLOSED = List.of("s3cr3t-token", "hunter2", PERSONAL_EMAIL, "Burks",
            "vendor-message", "cause-message", "suppressed-message", "error-message", "error-field");

    /** The timeout the late-query case gives the live test's wait, far shorter than the query it holds. */
    private static final Duration LATE_QUERY_TIMEOUT = Duration.ofMillis(200);

    /** Bounds each wait of the late-query case on a latch or a thread, so that a broken case fails, never hangs. */
    private static final Duration LATCH_TIMEOUT = Duration.ofSeconds(10);

    private SalesforcePartnerClient client;

    private ImportLeadsToSfdcIT liveTest;

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void prepareCleanup() {
        client = mock(SalesforcePartnerClient.class);
        liveTest = new ImportLeadsToSfdcIT();
        ReflectionTestUtils.setField(liveTest, "salesforcePartnerClient", client);
        ReflectionTestUtils.setField(liveTest, "fileDropped", true);
        leadIds().clear();
        leadIds().addAll(List.of(LEAD_ID1, LEAD_ID2));

        logger = (Logger) LoggerFactory.getLogger(ImportLeadsToSfdcIT.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
        leadIds().clear();
    }

    static Stream<Arguments> failures() {
        String connection = ConnectionException.class.getName();
        return Stream.of(
                Arguments.of("connection failure with cause and suppressed exception", sensitiveFailure(),
                        connection + " <- " + SocketTimeoutException.class.getName()),
                Arguments.of("query fault repeating the SOQL literal", queryFault(),
                        MalformedQueryFault.class.getName()),
                Arguments.of("circular cause chain", circularFailure(),
                        connection + " <- " + IOException.class.getName() + " <- [circular]"),
                Arguments.of("five classes, all named", causeChain(5),
                        connection + " <- java.lang.IllegalStateException <- java.lang.IllegalArgumentException"
                                + " <- java.lang.UnsupportedOperationException <- java.io.IOException"),
                Arguments.of("six classes, cut after five", causeChain(6),
                        connection + " <- java.lang.IllegalStateException <- java.lang.IllegalArgumentException"
                                + " <- java.lang.UnsupportedOperationException <- java.io.IOException <- ..."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void failedQueryIsLoggedByItsTypesAndEveryLeadIsStillDeleted(String label, Exception failure, String types)
            throws Exception {
        when(client.query(anyString())).thenThrow(failure);
        when(client.delete(anyList())).thenReturn(List.of(deleted(LEAD_ID1), deleted(LEAD_ID2)));

        liveTest.tearDown();

        verify(client, times(1)).query(anyString());
        verify(client).delete(List.of(LEAD_ID1, LEAD_ID2));
        assertEquals(List.of(
                        "WARN Could not query the Salesforce Leads with e-mail " + EMAIL1 + " for deletion: " + types,
                        "INFO Deleted Salesforce Lead " + LEAD_ID1,
                        "INFO Deleted Salesforce Lead " + LEAD_ID2),
                loggedEvents());
        assertTrue(leadIds().isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void failedDeleteIsLoggedByItsTypesOnly(String label, Exception failure, String types) throws Exception {
        Map<String, Object> lead = Map.of("Id", LEAD_ID1, "FirstName", "Ishmael", "LastName", LAST_NAME,
                "Email", PERSONAL_EMAIL);
        when(client.query(anyString())).thenReturn(List.of(lead)).thenReturn(List.of());
        when(client.delete(anyList())).thenThrow(failure);

        liveTest.tearDown();

        verify(client, times(2)).query(anyString());
        verify(client).delete(List.of(LEAD_ID1, LEAD_ID2));
        assertEquals(List.of("WARN Could not delete Salesforce Leads [" + LEAD_ID1 + ", " + LEAD_ID2 + "]: " + types),
                loggedEvents());
        assertTrue(leadIds().isEmpty());
    }

    static Stream<Arguments> failedDeleteErrors() {
        return Stream.of(
                Arguments.of("two errors carrying Lead values and a token",
                        new IError[] {
                            error(StatusCode.ENTITY_IS_DELETED), error(StatusCode.INVALID_CROSS_REFERENCE_KEY)},
                        "ENTITY_IS_DELETED; INVALID_CROSS_REFERENCE_KEY"),
                Arguments.of("error without a status code", new IError[] {error(null)}, "(null)"),
                Arguments.of("no error", new IError[0], "no error detail"),
                Arguments.of("errors never set", null, "no error detail"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failedDeleteErrors")
    void failedDeleteResultIsLoggedByItsStatusCodesOnly(String label, IError[] errors, String rendering)
            throws Exception {
        when(client.query(anyString())).thenReturn(List.of());
        DeleteResult failed = new DeleteResult();
        failed.setId(LEAD_ID1);
        failed.setSuccess(false);
        if (errors != null) {
            failed.setErrors(errors);
        }
        when(client.delete(anyList())).thenReturn(List.of(failed, deleted(LEAD_ID2)));

        liveTest.tearDown();

        verify(client).delete(List.of(LEAD_ID1, LEAD_ID2));
        assertEquals(List.of(
                        "WARN Could not delete Salesforce Lead " + LEAD_ID1 + ": " + rendering,
                        "INFO Deleted Salesforce Lead " + LEAD_ID2),
                loggedEvents());
    }

    @Test
    void nullFailureIsNamedNull() {
        assertEquals("(null)",
                ReflectionTestUtils.invokeMethod(ImportLeadsToSfdcIT.class, "failureTypes", (Throwable) null));
    }

    @Test
    void leadQueryAnsweredAfterTheTimeoutIsRecordedBeforeTheWaitEndsAndDeletedInTearDown() throws Exception {
        leadIds().clear();
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        AtomicReference<Thread> queryThread = new AtomicReference<>();
        Map<String, Object> lead = Map.of("Id", LEAD_ID1, "FirstName", "Ishmael");
        when(client.query(anyString())).thenAnswer(invocation -> {
            queryThread.set(Thread.currentThread());
            queryStarted.countDown();
            assertTrue(releaseQuery.await(LATCH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "query never released");
            return List.of(lead);
        }).thenReturn(List.of());
        when(client.delete(anyList())).thenReturn(List.of(deleted(LEAD_ID1)));

        // Stands in for JUnit's test thread, which runs tearDown() only once the wait has returned or thrown.
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread testThread = new Thread(() -> {
            try {
                outcome.set(liveTest.awaitLead(EMAIL1, LATE_QUERY_TIMEOUT));
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        }, "late-query-test-thread");
        testThread.setDaemon(true);
        testThread.start();
        try {
            assertTrue(queryStarted.await(LATCH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "query never started");
            testThread.join(LATE_QUERY_TIMEOUT.multipliedBy(3).toMillis());
            assertTrue(testThread.isAlive(), "the wait ended while its query was still running");
            assertTrue(leadIds().isEmpty());

            releaseQuery.countDown();
            testThread.join(LATCH_TIMEOUT.toMillis());
            assertFalse(testThread.isAlive(), "the wait did not end once its query was answered");
        } finally {
            releaseQuery.countDown();
            if (testThread.isAlive()) {
                testThread.interrupt();
                testThread.join(LATCH_TIMEOUT.toMillis());
            }
        }
        assertSame(testThread, queryThread.get());
        // Awaitility 4.2.2 times a condition that holds from its first success, so a query answered after the
        // timeout still ends the wait with its Lead, not with a ConditionTimeoutException.
        assertEquals(lead, outcome.get());
        assertEquals(List.of(LEAD_ID1), leadIds());

        liveTest.tearDown();

        // The wait's one query and the two re-queries of tearDown(); the wait's thread has ended, so none can follow.
        verify(client, times(3)).query(anyString());
        verify(client).delete(List.of(LEAD_ID1));
        verifyNoMoreInteractions(client);
        assertTrue(leadIds().isEmpty());
        assertEquals(List.of("INFO Deleted Salesforce Lead " + LEAD_ID1), loggedEvents());
    }

    /**
     * A connection failure whose message, cause message and suppressed exception carry a token, a password and Lead
     * fields.
     */
    private static ConnectionException sensitiveFailure() {
        ConnectionException failure = new ConnectionException("vendor-message " + TOKEN + " for " + PERSONAL_EMAIL,
                new SocketTimeoutException("cause-message " + PASSWORD + " " + LAST_NAME));
        failure.addSuppressed(new IllegalStateException("suppressed-message " + PERSONAL_EMAIL + " " + TOKEN));
        return failure;
    }

    /** The fault Salesforce returns for a rejected query, its exception message repeating the SOQL it received. */
    private static MalformedQueryFault queryFault() {
        MalformedQueryFault fault = new MalformedQueryFault();
        fault.setExceptionCode(ExceptionCode.MALFORMED_QUERY);
        fault.setExceptionMessage("vendor-message SELECT Id FROM Lead WHERE " + LAST_NAME + " AND Email = '"
                + PERSONAL_EMAIL + "' " + TOKEN);
        fault.addSuppressed(new IllegalStateException("suppressed-message " + PASSWORD));
        return fault;
    }

    /** A connection failure and its cause, each the cause of the other. */
    private static ConnectionException circularFailure() {
        ConnectionException outer = new ConnectionException("vendor-message " + TOKEN);
        IOException inner = new IOException("cause-message " + PERSONAL_EMAIL, outer);
        outer.initCause(inner);
        return outer;
    }

    /** Builds {@code length} exceptions, each the cause of the one before, with sensitive messages. */
    private static Exception causeChain(int length) {
        List<Exception> chain = List.of(
                new ConnectionException("vendor-message " + TOKEN),
                new IllegalStateException("cause-message " + PASSWORD),
                new IllegalArgumentException("cause-message " + PERSONAL_EMAIL),
                new UnsupportedOperationException("cause-message " + LAST_NAME),
                new IOException("cause-message " + TOKEN),
                new SocketTimeoutException("cause-message " + PASSWORD));
        for (int i = 0; i + 1 < length; i++) {
            chain.get(i).initCause(chain.get(i + 1));
        }
        return chain.get(0);
    }

    /** An error of a failed delete whose message and fields carry Lead values and a token. */
    private static IError error(StatusCode statusCode) {
        com.sforce.soap.partner.Error error = new com.sforce.soap.partner.Error();
        error.setStatusCode(statusCode);
        error.setMessage("error-message " + TOKEN + " " + PASSWORD + " " + PERSONAL_EMAIL + " " + LAST_NAME);
        error.setFields(new String[] {"error-field " + LAST_NAME, "error-field " + PERSONAL_EMAIL});
        return error;
    }

    private static DeleteResult deleted(String id) {
        DeleteResult result = new DeleteResult();
        result.setId(id);
        result.setSuccess(true);
        return result;
    }

    /** The live test's static list of the Lead IDs its cleanup deletes, an {@code ArrayList<String>}. */
    @SuppressWarnings("unchecked")
    private static List<String> leadIds() {
        return (List<String>) ReflectionTestUtils.getField(ImportLeadsToSfdcIT.class, "leadIds");
    }

    /**
     * Returns each captured event as {@code <level> <formatted message>} after checking that none attaches a
     * throwable and that neither its formatted message nor any of its arguments discloses an undisclosed value.
     */
    private List<String> loggedEvents() {
        List<String> rendered = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            assertNull(event.getThrowableProxy(), message);
            assertDisclosesNothing(message);
            Object[] arguments = event.getArgumentArray();
            if (arguments != null) {
                for (Object argument : arguments) {
                    assertDisclosesNothing(String.valueOf(argument));
                }
            }
            rendered.add(event.getLevel() + " " + message);
        }
        return rendered;
    }

    private static void assertDisclosesNothing(String text) {
        for (String undisclosed : UNDISCLOSED) {
            assertFalse(text.contains(undisclosed), () -> "'" + undisclosed + "' disclosed in " + text);
        }
    }
}
