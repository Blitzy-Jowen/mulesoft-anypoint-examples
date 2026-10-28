/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_salesforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import com.mulesoft.examples.import_contacts_into_salesforce.client.SalesforcePartnerClient;
import com.sforce.soap.partner.DeleteResult;
import com.sforce.soap.partner.StatusCode;
import com.sforce.ws.ConnectionException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests the cleanup of the live-sandbox {@link ImportContactsToSfdcIT} without credentials, a Spring context or
 * Salesforce: the IT is instantiated directly and a Mockito {@link SalesforcePartnerClient} replaces its injected
 * client, so the bounded wait and {@link ImportContactsToSfdcIT#tearDown()} run exactly as they do live.
 *
 * <p>Covered: a query that answers after the wait's timeout is recorded on the test thread before the wait returns,
 * and is deleted; a failed cleanup step logs its exception by class names only and attaches no throwable, a failed
 * delete logs its status codes only, and every delete is still attempted.
 */
class ImportContactsToSfdcITCleanupTest {

    private static final String EMAIL1 = "john.doe@texasComp.com";

    private static final String EMAIL2 = "jane.doe@texasComp.com";

    /** The wait of {@link ImportContactsToSfdcIT#awaitImportedContact} in place of the live 60 s. */
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(200);

    /** How long the late query takes to answer: well past {@link #SHORT_TIMEOUT}. */
    private static final Duration LATE_ANSWER = Duration.ofMillis(800);

    private static final String LATE_ID = "003000000000LATEAAA";

    private static final String ID1 = "003000000000001AAA";

    private static final String ID2 = "003000000000002AAA";

    private static final String TOKEN = "token=s3cr3t-token";

    private static final String PASSWORD = "password=hunter2";

    private static final String PERSONAL_EMAIL = "jane.doe@example.com";

    private static final String LAST_NAME = "LastName='Doe'";

    /** Text carrying every value no logged message or argument may repeat. */
    private static final String SENSITIVE = "SELECT Id FROM Contact WHERE Email = '" + PERSONAL_EMAIL + "' AND "
            + LAST_NAME + " " + TOKEN + " " + PASSWORD;

    /** Every value no logged message or argument may contain. */
    private static final List<String> UNDISCLOSED = List.of("s3cr3t-token", "hunter2", PERSONAL_EMAIL, LAST_NAME);

    private SalesforcePartnerClient salesforce;

    private ImportContactsToSfdcIT it;

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void createTest() {
        salesforce = mock(SalesforcePartnerClient.class);
        it = new ImportContactsToSfdcIT();
        ReflectionTestUtils.setField(it, "salesforce", salesforce);
        logger = (Logger) LoggerFactory.getLogger(ImportContactsToSfdcIT.class);
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
    }

    /**
     * The late answer carries either a Contact the import created, which Awaitility accepts because the query that
     * returned it began before the timeout, or only a Contact an earlier run left, so the wait times out.
     */
    static Stream<Arguments> lateAnswers() {
        return Stream.of(
                Arguments.of("new Contact: returned by the wait", Set.of(), true),
                Arguments.of("only a Contact an earlier run left: the wait times out", Set.of(LATE_ID), false));
    }

    /**
     * A query that answers only after the wait's timeout runs on the test thread, so the Contact it returns is
     * already recorded when the wait returns or fails, and the cleanup deletes it although no later query returns it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("lateAnswers")
    void lateQueryResultIsRecordedOnTheTestThreadAndDeleted(String label, Set<String> existing, boolean imported)
            throws Exception {
        Thread testThread = Thread.currentThread();
        List<Thread> queryThreads = new CopyOnWriteArrayList<>();
        AtomicInteger queries = new AtomicInteger();
        when(salesforce.query(anyString())).thenAnswer(invocation -> {
            queryThreads.add(Thread.currentThread());
            if (queries.getAndIncrement() > 0) {
                return List.of();
            }
            Thread.sleep(LATE_ANSWER.toMillis());
            return List.of(contact(LATE_ID));
        });
        when(salesforce.delete(anyList())).thenReturn(List.of(deleted(LATE_ID)));

        if (imported) {
            assertEquals(LATE_ID, it.awaitImportedContact(EMAIL1, existing, SHORT_TIMEOUT).get("Id"));
        } else {
            assertThrows(ConditionTimeoutException.class,
                    () -> it.awaitImportedContact(EMAIL1, existing, SHORT_TIMEOUT));
        }

        assertEquals(List.of(LATE_ID), contactIds());
        assertEquals(List.of(testThread), queryThreads);

        ReflectionTestUtils.setField(it, "fileDropped", true);
        it.tearDown();

        verify(salesforce).delete(List.of(LATE_ID));
        assertTrue(contactIds().isEmpty());
        assertEquals(3, queryThreads.size());
        queryThreads.forEach(thread -> assertSame(testThread, thread));
        assertEquals(List.of("Deleted Salesforce Contact " + LATE_ID), messages());
    }

    static Stream<Arguments> cleanupFailures() {
        ConnectionException vendor = new ConnectionException("Request for " + PERSONAL_EMAIL + " failed, " + TOKEN,
                new SocketTimeoutException(SENSITIVE));
        vendor.addSuppressed(new IllegalArgumentException(SENSITIVE));

        IllegalStateException circularOuter = new IllegalStateException(SENSITIVE);
        IOException circularInner = new IOException(SENSITIVE);
        circularOuter.initCause(circularInner);
        circularInner.initCause(circularOuter);

        return Stream.of(
                Arguments.of("vendor failure with a cause and a suppressed exception", vendor,
                        "com.sforce.ws.ConnectionException <- java.net.SocketTimeoutException"),
                Arguments.of("circular cause chain", circularOuter,
                        "java.lang.IllegalStateException <- java.io.IOException <- [circular]"),
                Arguments.of("five classes, all named", causeChain(5),
                        "java.lang.RuntimeException <- java.lang.IllegalStateException"
                                + " <- java.lang.IllegalArgumentException <- java.lang.UnsupportedOperationException"
                                + " <- java.io.IOException"),
                Arguments.of("six classes, cut after five", causeChain(6),
                        "java.lang.RuntimeException <- java.lang.IllegalStateException"
                                + " <- java.lang.IllegalArgumentException <- java.lang.UnsupportedOperationException"
                                + " <- java.io.IOException <- ..."));
    }

    /**
     * The cleanup queries of both e-mails and the delete fail with an exception whose message, cause message and
     * suppressed message carry personal fields, SOQL and secrets: each failure is logged at WARN by the class names
     * of the exception and its causes alone, with no throwable attached, and the delete is still attempted.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cleanupFailures")
    void cleanupFailureIsLoggedByClassNamesOnly(String label, Exception failure, String types) throws Exception {
        when(salesforce.query(anyString())).thenReturn(List.of(contact(ID1))).thenThrow(failure);
        when(salesforce.delete(anyList())).thenThrow(failure);
        it.awaitImportedContact(EMAIL1, Set.of(), SHORT_TIMEOUT);
        ReflectionTestUtils.setField(it, "fileDropped", true);

        it.tearDown();

        verify(salesforce).delete(List.of(ID1));
        assertTrue(contactIds().isEmpty());
        assertEquals(List.of(
                "Could not query the Salesforce Contacts with e-mail " + EMAIL1 + " for deletion: " + types,
                "Could not query the Salesforce Contacts with e-mail " + EMAIL2 + " for deletion: " + types,
                "Could not delete Salesforce Contacts [" + ID1 + "]: " + types), messages());
        assertEveryEventIsSafe(Level.WARN);
    }

    static Stream<Arguments> failedDeletes() {
        return Stream.of(
                Arguments.of("errors with messages and fields",
                        new com.sforce.soap.partner.Error[] {
                            deleteError(StatusCode.ENTITY_IS_DELETED, SENSITIVE, "LastName", "Email"),
                            deleteError(StatusCode.INVALID_CROSS_REFERENCE_KEY, TOKEN + " " + PASSWORD)},
                        "ENTITY_IS_DELETED; INVALID_CROSS_REFERENCE_KEY"),
                Arguments.of("error without a status code",
                        new com.sforce.soap.partner.Error[] {deleteError(null, SENSITIVE, LAST_NAME)}, "(null)"),
                Arguments.of("no errors", null, "no error detail"),
                Arguments.of("empty errors", new com.sforce.soap.partner.Error[0], "no error detail"));
    }

    /**
     * A delete result that reports failure is logged at WARN by its status codes alone, never by the message or
     * fields of its errors, while the other Contact of the same delete is reported deleted.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("failedDeletes")
    void failedDeleteIsLoggedByStatusCodesOnly(String label, com.sforce.soap.partner.Error[] errors, String rendering)
            throws Exception {
        DeleteResult failed = new DeleteResult();
        failed.setId(ID2);
        failed.setSuccess(false);
        failed.setErrors(errors);
        when(salesforce.query(anyString())).thenReturn(List.of(contact(ID1), contact(ID2)));
        when(salesforce.delete(anyList())).thenReturn(List.of(deleted(ID1), failed));
        it.awaitImportedContact(EMAIL1, Set.of(), SHORT_TIMEOUT);
        ReflectionTestUtils.setField(it, "fileDropped", true);

        it.tearDown();

        verify(salesforce).delete(List.of(ID1, ID2));
        assertEquals(List.of(
                "Deleted Salesforce Contact " + ID1,
                "Could not delete Salesforce Contact " + ID2 + ": " + rendering), messages());
        assertEquals(List.of(Level.INFO, Level.WARN), appender.list.stream().map(ILoggingEvent::getLevel).toList());
        assertEveryEventIsSafe(null);
    }

    @Test
    void missingFailureIsNamedNull() {
        String types = ReflectionTestUtils.invokeMethod(ImportContactsToSfdcIT.class, "failureTypes", (Object) null);

        assertEquals("(null)", types);
    }

    /**
     * Asserts that no event attaches a throwable and that neither its formatted message nor any argument repeats a
     * value of {@link #UNDISCLOSED}; with a {@code level}, also that every event has it.
     */
    private void assertEveryEventIsSafe(Level level) {
        assertFalse(appender.list.isEmpty());
        for (ILoggingEvent event : appender.list) {
            if (level != null) {
                assertEquals(level, event.getLevel(), event.getFormattedMessage());
            }
            assertNull(event.getThrowableProxy(), event.getFormattedMessage());
            String arguments = Arrays.deepToString(event.getArgumentArray());
            for (String undisclosed : UNDISCLOSED) {
                assertFalse(event.getFormattedMessage().contains(undisclosed), event.getFormattedMessage());
                assertFalse(arguments.contains(undisclosed), arguments);
            }
        }
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<?> contactIds() {
        return (List<?>) ReflectionTestUtils.getField(it, "contactIds");
    }

    private static Map<String, Object> contact(String id) {
        return Map.of("Id", id, "FirstName", "John", "LastName", "Doe");
    }

    private static DeleteResult deleted(String id) {
        DeleteResult result = new DeleteResult();
        result.setId(id);
        result.setSuccess(true);
        return result;
    }

    private static com.sforce.soap.partner.Error deleteError(StatusCode statusCode, String message,
            String... fields) {
        com.sforce.soap.partner.Error error = new com.sforce.soap.partner.Error();
        error.setStatusCode(statusCode);
        error.setMessage(message);
        error.setFields(fields);
        return error;
    }

    /** A chain of {@code length} exceptions of distinct classes, each message carrying {@link #SENSITIVE}. */
    private static Exception causeChain(int length) {
        List<Function<String, Exception>> types = List.of(RuntimeException::new, IllegalStateException::new,
                IllegalArgumentException::new, UnsupportedOperationException::new, IOException::new,
                SocketTimeoutException::new);
        Exception chain = null;
        for (int i = length - 1; i >= 0; i--) {
            Exception link = types.get(i).apply(SENSITIVE);
            if (chain != null) {
                link.initCause(chain);
            }
            chain = link;
        }
        return chain;
    }
}
