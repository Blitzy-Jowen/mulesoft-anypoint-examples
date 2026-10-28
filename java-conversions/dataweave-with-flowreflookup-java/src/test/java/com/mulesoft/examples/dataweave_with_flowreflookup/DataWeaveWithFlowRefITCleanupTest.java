/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.dataweave_with_flowreflookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.dataweave_with_flowreflookup.client.SalesforcePartnerClient;
import com.mulesoft.examples.dataweave_with_flowreflookup.client.UpstreamSystem;
import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.DeleteResult;
import com.sforce.soap.partner.StatusCode;
import com.sforce.soap.partner.fault.ApiFault;
import com.sforce.ws.ConnectionException;

/**
 * Surefire test of the cleanup of the live test {@link DataWeaveWithFlowRefIT}: its {@code tearDown()} runs on an
 * instance made with {@code new}, against a Mockito {@link SalesforcePartnerClient}, with no credentials, Spring
 * context or Salesforce call. The live class itself still runs only under failsafe and skips without
 * {@code application-it.yml}.
 *
 * <p>Salesforce and transport failures can repeat the session token, the login, record values or the SOQL in their
 * messages, and a delete error's message and fields are vendor free text. Each failure here carries such markers in
 * its message, its cause's message and a suppressed exception's message, and each failed delete in its error message
 * and fields; a Logback {@link ListAppender} on the live test's logger proves that every cleanup line names only the
 * operation, the fixture Account names or IDs and the failure's class names or status codes, that no event carries
 * a throwable, and that the delete is still attempted after a failed query.
 */
class DataWeaveWithFlowRefITCleanupTest {

    private static final String TOKEN = "token=s3cr3t-token";

    private static final String PASSWORD = "password=hunter2";

    private static final String EMAIL = "jane.doe@example.com";

    /** The literal of the SOQL a failed query can repeat; the Account name alone is a fixture name and is logged. */
    private static final String SOQL_NAME = "Name='Universal Exports'";

    private static final List<String> MARKERS = List.of(TOKEN, PASSWORD, EMAIL, SOQL_NAME);

    private static final String SENSITIVE_TEXT = "Rejected " + SOQL_NAME + " for " + EMAIL + " " + TOKEN;

    /** IDs of the two Accounts {@code companies.csv} creates, in the 18-character form Salesforce returns. */
    private static final String UNIVERSAL_EXPORTS_ID = "001000000000001AAA";

    private static final String BEST_WIDGETS_ID = "001000000000002AAA";

    private static final List<String> CREATED_IDS = List.of(UNIVERSAL_EXPORTS_ID, BEST_WIDGETS_ID);

    private final SalesforcePartnerClient salesforce = mock(SalesforcePartnerClient.class);

    private final DataWeaveWithFlowRefIT liveTest = new DataWeaveWithFlowRefIT();

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void injectClientAndCaptureLog() {
        ReflectionTestUtils.setField(liveTest, "salesforce", salesforce);
        // Set as by importAccounts(): from here on the import may have created Accounts, so tearDown queries again.
        ReflectionTestUtils.setField(liveTest, "fileDropped", true);

        logger = (Logger) LoggerFactory.getLogger(DataWeaveWithFlowRefIT.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachLogCapture() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    /** Vendor, transport and SC-9 failures, each with markers in every message, and the type names they log as. */
    static Stream<Arguments> failures() {
        ApiFault apiFault = new ApiFault();
        apiFault.setExceptionMessage(SENSITIVE_TEXT);

        IllegalStateException circular = new IllegalStateException(SENSITIVE_TEXT);
        circular.initCause(new IOException(PASSWORD, circular));

        return Stream.of(
                Arguments.of("WSC ConnectionException with a timeout cause",
                        sensitive(new ConnectionException(SENSITIVE_TEXT,
                                new SocketTimeoutException("Read timed out " + TOKEN))),
                        "com.sforce.ws.ConnectionException <- java.net.SocketTimeoutException"),
                Arguments.of("Salesforce ApiFault",
                        sensitive(apiFault),
                        "com.sforce.soap.partner.fault.ApiFault"),
                Arguments.of("SC-9 UpstreamUnavailableException",
                        sensitive(new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, SENSITIVE_TEXT,
                                new ConnectException("Connection refused " + PASSWORD))),
                        UpstreamUnavailableException.class.getName() + " <- java.net.ConnectException"),
                Arguments.of("circular cause chain",
                        sensitive(circular),
                        "java.lang.IllegalStateException <- java.io.IOException <- [circular]"),
                Arguments.of("four causes, all named",
                        sensitive(causeChain(5)),
                        "java.lang.RuntimeException <- java.lang.IllegalStateException"
                                + " <- java.lang.IllegalArgumentException <- java.lang.UnsupportedOperationException"
                                + " <- java.io.IOException"),
                Arguments.of("five causes, cut after four",
                        sensitive(causeChain(6)),
                        "java.lang.RuntimeException <- java.lang.IllegalStateException"
                                + " <- java.lang.IllegalArgumentException <- java.lang.UnsupportedOperationException"
                                + " <- java.io.IOException <- ..."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void failedQueryIsLoggedByTypeOnlyAndCollectedAccountsAreStillDeleted(String label, Exception failure,
                                                                          String types) throws Exception {
        // The test body read both Accounts back; the queries of tearDown then fail.
        when(salesforce.query(anyString()))
                .thenReturn(accounts(UNIVERSAL_EXPORTS_ID))
                .thenReturn(accounts(BEST_WIDGETS_ID))
                .thenThrow(failure);
        when(salesforce.delete(anyList())).thenReturn(List.of(deleted(), deleted()));
        ReflectionTestUtils.invokeMethod(liveTest, "selectAccounts", "Universal Exports");
        ReflectionTestUtils.invokeMethod(liveTest, "selectAccounts", "Best Widgets");

        liveTest.tearDown();

        verify(salesforce, times(4)).query(anyString());
        verify(salesforce).delete(CREATED_IDS);
        assertEquals(List.of(
                        "WARN Could not query the Salesforce Accounts named Universal Exports for deletion: " + types,
                        "WARN Could not query the Salesforce Accounts named Best Widgets for deletion: " + types,
                        "INFO Deleted Salesforce Account " + UNIVERSAL_EXPORTS_ID,
                        "INFO Deleted Salesforce Account " + BEST_WIDGETS_ID),
                safeEvents());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void failedDeleteIsLoggedByTypeOnly(String label, Exception failure, String types) throws Exception {
        when(salesforce.query(anyString()))
                .thenReturn(accounts(UNIVERSAL_EXPORTS_ID))
                .thenReturn(accounts(BEST_WIDGETS_ID));
        when(salesforce.delete(anyList())).thenThrow(failure);

        liveTest.tearDown();

        verify(salesforce).delete(CREATED_IDS);
        assertEquals(List.of("WARN Could not delete Salesforce Accounts " + CREATED_IDS + ": " + types),
                safeEvents());
    }

    /** Errors of a failed delete result, every message and field carrying markers, and how they are logged. */
    static Stream<Arguments> deleteErrors() {
        return Stream.of(
                Arguments.of("two errors", new com.sforce.soap.partner.Error[] {
                        error(StatusCode.ENTITY_IS_DELETED), error(StatusCode.INVALID_CROSS_REFERENCE_KEY)},
                        "ENTITY_IS_DELETED; INVALID_CROSS_REFERENCE_KEY"),
                Arguments.of("error without a status code", new com.sforce.soap.partner.Error[] {
                        error(null), error(StatusCode.INSUFFICIENT_ACCESS_OR_READONLY)},
                        "INSUFFICIENT_ACCESS_OR_READONLY"),
                Arguments.of("only errors without a status code", new com.sforce.soap.partner.Error[] {
                        error(null), null},
                        "no error detail"),
                Arguments.of("no errors", new com.sforce.soap.partner.Error[0], "no error detail"),
                Arguments.of("errors absent", null, "no error detail"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("deleteErrors")
    void failedDeleteResultIsLoggedByStatusCodeOnly(String label, com.sforce.soap.partner.Error[] errors,
                                                    String statusCodes) throws Exception {
        when(salesforce.query(anyString()))
                .thenReturn(accounts(UNIVERSAL_EXPORTS_ID))
                .thenReturn(accounts(BEST_WIDGETS_ID));
        DeleteResult failed = new DeleteResult();
        failed.setSuccess(false);
        failed.setErrors(errors);
        when(salesforce.delete(anyList())).thenReturn(List.of(failed, deleted()));

        liveTest.tearDown();

        verify(salesforce).delete(CREATED_IDS);
        assertEquals(List.of(
                        "WARN Could not delete Salesforce Account " + UNIVERSAL_EXPORTS_ID + ": " + statusCodes,
                        "INFO Deleted Salesforce Account " + BEST_WIDGETS_ID),
                safeEvents());
    }

    @Test
    void failureTypesNamesAnAbsentFailure() {
        String types = ReflectionTestUtils.invokeMethod(DataWeaveWithFlowRefIT.class, "failureTypes", (Object) null);

        assertEquals("(null)", types);
    }

    /** Adds a suppressed exception with markers, which Logback would print with an attached throwable. */
    private static <T extends Exception> T sensitive(T failure) {
        failure.addSuppressed(new IllegalArgumentException("Suppressed " + EMAIL + " " + PASSWORD));
        return failure;
    }

    /** Builds {@code length} exceptions, each the cause of the one before, every message carrying a marker. */
    private static Exception causeChain(int length) {
        List<Exception> chain = List.of(
                new RuntimeException(SENSITIVE_TEXT),
                new IllegalStateException(EMAIL),
                new IllegalArgumentException(TOKEN),
                new UnsupportedOperationException(PASSWORD),
                new IOException(SOQL_NAME),
                new ArithmeticException(TOKEN));
        for (int i = 0; i + 1 < length; i++) {
            chain.get(i).initCause(chain.get(i + 1));
        }
        return chain.get(0);
    }

    private static com.sforce.soap.partner.Error error(StatusCode statusCode) {
        com.sforce.soap.partner.Error error = new com.sforce.soap.partner.Error();
        error.setStatusCode(statusCode);
        error.setMessage(SENSITIVE_TEXT + " " + PASSWORD);
        error.setFields(new String[] {EMAIL, TOKEN});
        return error;
    }

    private static DeleteResult deleted() {
        DeleteResult result = new DeleteResult();
        result.setSuccess(true);
        return result;
    }

    private static List<Map<String, Object>> accounts(String id) {
        return List.of(Map.of("Id", id, "Region__c", "South East"));
    }

    /**
     * Returns each captured event as {@code "<LEVEL> <formatted message>"} after checking that none carries a
     * throwable, which Logback would print with its message, causes and suppressed exceptions, and that neither the
     * formatted message nor any argument holds a marker.
     */
    private List<String> safeEvents() {
        List<String> events = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            assertNull(event.getThrowableProxy(), () -> "throwable attached to " + message);
            List<String> rendered = new ArrayList<>();
            rendered.add(message);
            Object[] arguments = event.getArgumentArray();
            if (arguments != null) {
                for (Object argument : arguments) {
                    rendered.add(String.valueOf(argument));
                }
            }
            for (String text : rendered) {
                for (String marker : MARKERS) {
                    assertFalse(text.contains(marker), () -> "'" + marker + "' logged in " + text);
                }
            }
            events.add(event.getLevel() + " " + message);
        }
        return events;
    }
}
