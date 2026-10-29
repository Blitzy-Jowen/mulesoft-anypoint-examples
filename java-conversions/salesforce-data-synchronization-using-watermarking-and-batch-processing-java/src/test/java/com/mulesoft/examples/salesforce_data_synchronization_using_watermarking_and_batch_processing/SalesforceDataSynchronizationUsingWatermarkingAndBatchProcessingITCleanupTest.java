/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
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

import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.client.SalesforcePartnerClient;
import com.sforce.soap.partner.DeleteResult;
import com.sforce.soap.partner.StatusCode;
import com.sforce.soap.partner.fault.ExceptionCode;
import com.sforce.soap.partner.fault.MalformedQueryFault;
import com.sforce.ws.ConnectionException;

/**
 * Tests the log safety of the {@link SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT#tearDown()}
 * cleanup in the unit tier, without credentials or a Spring context: the IT is instantiated directly with a
 * Mockito-mocked {@link SalesforcePartnerClient} whose lookup and delete calls fail with exceptions, or return failed
 * delete results, that carry a token, a password, an e-mail address and a personal field. Every cleanup failure must
 * be logged at WARN by the exception's class names or the delete error's status codes alone, with no throwable
 * attached and none of those values in any message or argument, and every delete must still be attempted.
 */
class SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingITCleanupTest {

    private static final String TOKEN = "token=s3cr3t-token";

    private static final String PASSWORD = "password=hunter2";

    private static final String EMAIL = "jane.doe@example.com";

    private static final String PERSONAL_FIELD = "LastName='Doe'";

    /** Every value a logged event must not contain. */
    private static final List<String> UNDISCLOSED = List.of("s3cr3t-token", "hunter2", EMAIL, "Doe");

    /** A {@code LastName} of the form the IT generates. */
    private static final String LAST_NAME = "WatermarkIT1700000000000";

    private static final String LOOKUP = "SELECT Id FROM Contact WHERE LastName = '" + LAST_NAME + "'";

    private static final String FIRST_ID = "003000000000001AAA";

    private static final String SECOND_ID = "003000000000002AAA";

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    private SalesforcePartnerClient client;

    private SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT liveTest;

    private List<String> createdIds;

    @BeforeEach
    void prepareCleanup() {
        logger = (Logger) LoggerFactory.getLogger(
                SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        client = mock(SalesforcePartnerClient.class);
        createdIds = new ArrayList<>();
        liveTest = new SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT();
        ReflectionTestUtils.setField(liveTest, "client", client);
        ReflectionTestUtils.setField(liveTest, "lastName", LAST_NAME);
        ReflectionTestUtils.setField(liveTest, "createdIds", createdIds);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    /**
     * Vendor and transport failures whose message, cause message and suppressed message carry the undisclosed
     * values, with the rendering {@code failureTypes} must give each.
     */
    static Stream<Arguments> sensitiveFailures() {
        MalformedQueryFault malformedQuery = new MalformedQueryFault();
        malformedQuery.setExceptionCode(ExceptionCode.MALFORMED_QUERY);
        malformedQuery.setExceptionMessage("unexpected token near " + PERSONAL_FIELD + " " + TOKEN);
        malformedQuery.initCause(new IOException(PASSWORD + " " + EMAIL));
        malformedQuery.addSuppressed(new IllegalArgumentException(TOKEN + " " + EMAIL));

        ConnectionException connection = new ConnectionException(TOKEN + " " + PASSWORD,
                new SocketTimeoutException(EMAIL + " " + PERSONAL_FIELD));
        connection.addSuppressed(new IllegalStateException(PASSWORD + " " + PERSONAL_FIELD));

        IllegalStateException circularOuter = new IllegalStateException(EMAIL);
        IOException circularInner = new IOException(TOKEN);
        circularOuter.initCause(circularInner);
        circularInner.initCause(circularOuter);

        String fiveTypes = "java.lang.RuntimeException <- java.lang.IllegalStateException"
                + " <- java.lang.IllegalArgumentException <- java.lang.UnsupportedOperationException"
                + " <- java.io.IOException";
        return Stream.of(
                Arguments.of("MALFORMED_QUERY fault echoing the SOQL, with cause and suppressed text", malformedQuery,
                        "com.sforce.soap.partner.fault.MalformedQueryFault <- java.io.IOException"),
                Arguments.of("connection failure with cause and suppressed text", connection,
                        "com.sforce.ws.ConnectionException <- java.net.SocketTimeoutException"),
                Arguments.of("circular cause chain", circularOuter,
                        "java.lang.IllegalStateException <- java.io.IOException <- [circular]"),
                Arguments.of("five failures, all named", causeChain(new IOException(TOKEN)), fiveTypes),
                Arguments.of("six failures, the sixth cut short",
                        causeChain(new IOException(TOKEN, new SocketTimeoutException(PASSWORD))),
                        fiveTypes + " <- ..."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sensitiveFailures")
    void failedLookupIsLoggedByTypeOnlyAndTheKnownContactIsStillDeleted(String label, Exception failure,
                                                                       String failureTypes) throws Exception {
        createdIds.add(FIRST_ID);
        ReflectionTestUtils.setField(liveTest, "createOutcomeUnknown", true);
        when(client.query(LOOKUP)).thenThrow(failure);
        when(client.delete(List.of(FIRST_ID))).thenReturn(List.of(deleteResult(true)));

        liveTest.tearDown();

        verify(client).query(LOOKUP);
        verify(client).delete(List.of(FIRST_ID));
        verifyNoMoreInteractions(client);
        assertThat(createdIds).isEmpty();
        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        tuple(Level.WARN, "Could not look up Salesforce Contacts with LastName " + LAST_NAME
                                + " for deletion: " + failureTypes),
                        tuple(Level.INFO, "Deleted Salesforce Contact " + FIRST_ID));
        assertDisclosesNothing();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sensitiveFailures")
    void failedDeleteIsLoggedByTypeOnlyAndTheNextContactIsStillDeleted(String label, Exception failure,
                                                                      String failureTypes) throws Exception {
        createdIds.addAll(List.of(FIRST_ID, SECOND_ID));
        when(client.delete(List.of(FIRST_ID))).thenThrow(failure);
        when(client.delete(List.of(SECOND_ID))).thenReturn(List.of(deleteResult(true)));

        liveTest.tearDown();

        verify(client).delete(List.of(FIRST_ID));
        verify(client).delete(List.of(SECOND_ID));
        verifyNoMoreInteractions(client);
        assertThat(createdIds).isEmpty();
        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        tuple(Level.WARN, "Could not delete Salesforce Contact " + FIRST_ID + ": " + failureTypes),
                        tuple(Level.INFO, "Deleted Salesforce Contact " + SECOND_ID));
        assertDisclosesNothing();
    }

    /** Failed delete outcomes whose error messages and fields carry the undisclosed values. */
    static Stream<Arguments> failedDeleteOutcomes() {
        return Stream.of(
                Arguments.of("two errors with messages and fields",
                        List.of(deleteResult(false,
                                error(StatusCode.ENTITY_IS_DELETED, TOKEN + " " + EMAIL, PERSONAL_FIELD),
                                error(StatusCode.INSUFFICIENT_ACCESS_OR_READONLY, PASSWORD + " " + PERSONAL_FIELD,
                                        EMAIL))),
                        "ENTITY_IS_DELETED; INSUFFICIENT_ACCESS_OR_READONLY"),
                Arguments.of("error without a status code",
                        List.of(deleteResult(false, error(null, PASSWORD + " " + EMAIL, PERSONAL_FIELD))), "(null)"),
                Arguments.of("errors array absent",
                        List.of(deleteResult(false, (com.sforce.soap.partner.Error[]) null)), "no error detail"),
                Arguments.of("errors array empty", List.of(deleteResult(false)), "no error detail"),
                Arguments.of("no delete result", List.of(), "no delete result"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failedDeleteOutcomes")
    void failedDeleteResultIsLoggedByStatusCodeOnlyAndTheNextContactIsStillDeleted(String label,
                                                                                  List<DeleteResult> outcome,
                                                                                  String detail) throws Exception {
        createdIds.addAll(List.of(FIRST_ID, SECOND_ID));
        when(client.delete(List.of(FIRST_ID))).thenReturn(outcome);
        when(client.delete(List.of(SECOND_ID))).thenReturn(List.of(deleteResult(true)));

        liveTest.tearDown();

        verify(client).delete(List.of(FIRST_ID));
        verify(client).delete(List.of(SECOND_ID));
        verifyNoMoreInteractions(client);
        assertThat(createdIds).isEmpty();
        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        tuple(Level.WARN, "Could not delete Salesforce Contact " + FIRST_ID + ": " + detail),
                        tuple(Level.INFO, "Deleted Salesforce Contact " + SECOND_ID));
        assertDisclosesNothing();
    }

    @Test
    void absentFailureIsRenderedAsTheNullToken() {
        Object rendering = ReflectionTestUtils.invokeMethod(
                SalesforceDataSynchronizationUsingWatermarkingAndBatchProcessingIT.class, "failureTypes",
                (Object) null);

        assertThat(rendering).isEqualTo("(null)");
    }

    /**
     * Asserts that no captured event attaches a throwable and that neither its formatted message, its pattern nor
     * any of its arguments contains an undisclosed value.
     */
    private void assertDisclosesNothing() {
        assertThat(appender.list).isNotEmpty();
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getThrowableProxy()).as("throwable of '%s'", event.getMessage()).isNull();
            List<String> rendered = new ArrayList<>(List.of(event.getFormattedMessage(), event.getMessage()));
            Object[] arguments = event.getArgumentArray();
            if (arguments != null) {
                for (Object argument : arguments) {
                    rendered.add(String.valueOf(argument));
                }
            }
            for (String text : rendered) {
                for (String undisclosed : UNDISCLOSED) {
                    assertThat(text).as("logged text").doesNotContain(undisclosed);
                }
            }
        }
    }

    /**
     * Returns a {@code RuntimeException} caused by an {@code IllegalStateException}, an
     * {@code IllegalArgumentException} and an {@code UnsupportedOperationException} in turn, the last caused by
     * {@code innermost}; every message carries an undisclosed value.
     */
    private static Exception causeChain(Exception innermost) {
        return new RuntimeException(TOKEN, new IllegalStateException(PASSWORD,
                new IllegalArgumentException(EMAIL, new UnsupportedOperationException(PERSONAL_FIELD, innermost))));
    }

    private static DeleteResult deleteResult(boolean success, com.sforce.soap.partner.Error... errors) {
        DeleteResult result = new DeleteResult();
        result.setSuccess(success);
        result.setErrors(errors);
        return result;
    }

    private static com.sforce.soap.partner.Error error(StatusCode statusCode, String message, String field) {
        com.sforce.soap.partner.Error error = new com.sforce.soap.partner.Error();
        error.setStatusCode(statusCode);
        error.setMessage(message);
        error.setFields(new String[] {field});
        return error;
    }
}
