/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.SocketException;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client.UpstreamSystem;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.DeleteResult;
import com.sforce.soap.partner.StatusCode;
import com.sforce.ws.ConnectionException;

/**
 * Tests the log safety of the cleanup of {@link SalesforceToMySQLDBUsingBatchProcessingIT} without credentials, a
 * Spring context or a Salesforce org: the IT is created with {@code new}, its {@link SalesforcePartnerClient} and
 * {@link JdbcTemplate} are Mockito mocks, and its {@code @AfterEach} method {@code tearDown()} is called directly.
 *
 * <p>Every cleanup failure carries SQL with values, a contact e-mail, a password and a token in its message, its
 * cause's message and a suppressed exception, and every failed Salesforce delete carries them in its error messages
 * and fields. The cleanup must log each failure at WARN by type identifiers or {@code StatusCode} names only, attach
 * no throwable, and still attempt the row delete and the Salesforce delete or lookup.
 */
@ExtendWith(MockitoExtension.class)
class SalesforceToMySQLDBUsingBatchProcessingITCleanupTest {

    /** A Contact {@code LastName} in the form the IT generates: {@code Contact Test Name<millis>}. */
    private static final String LAST_NAME = "Contact Test Name1700000000000";

    private static final String EMAIL = "Contact1700000000000@test.com";

    private static final String CONTACT_ID = "003000000000001AAA";

    /** The IT's row delete, bound by {@code last_name}. */
    private static final String DELETE_SQL = "delete from contact where last_name = ?";

    /** The IT's lookup of a Contact whose create timed out. */
    private static final String LOOKUP_SOQL = "SELECT Id FROM Contact WHERE LastName = '" + LAST_NAME + "'";

    private static final String TOKEN = "token=s3cr3t-token";

    private static final String PASSWORD = "password=hunter2";

    private static final String PERSONAL_EMAIL = "jane.doe@example.com";

    private static final String SQL_WITH_VALUES = "DELETE FROM contact WHERE last_name='Doe'";

    /** Every value a logged event must not contain. */
    private static final List<String> UNDISCLOSED = List.of("s3cr3t-token", "hunter2", PERSONAL_EMAIL,
            "last_name='Doe'");

    private static final String ROW_FAILURE_TYPES =
            BadSqlGrammarException.class.getName() + " <- " + SQLSyntaxErrorException.class.getName();

    private static final String ROW_FAILURE_LOG =
            "Could not delete the contact row with last_name " + LAST_NAME + ": " + ROW_FAILURE_TYPES;

    private static final String VENDOR_FAULT_TYPES =
            ConnectionException.class.getName() + " <- " + SocketException.class.getName();

    /** The Salesforce step of the cleanup whose call fails. */
    enum SalesforceStep {

        /** The Contact's {@code Id} is known, so it is deleted directly. */
        DELETE,

        /** The create call timed out without an {@code Id}, so the Contact is first looked up by its LastName. */
        LOOKUP
    }

    @Mock
    private SalesforcePartnerClient salesforcePartnerClient;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SalesforceToMySQLDBUsingBatchProcessingIT liveTest;

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void prepareLiveTest() {
        liveTest = new SalesforceToMySQLDBUsingBatchProcessingIT();
        ReflectionTestUtils.setField(liveTest, "salesforcePartnerClient", salesforcePartnerClient);
        ReflectionTestUtils.setField(liveTest, "jdbcTemplate", jdbcTemplate);
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("LastName", LAST_NAME);
        contact.put("Email", EMAIL);
        ReflectionTestUtils.setField(liveTest, "contact", contact);

        logger = (Logger) LoggerFactory.getLogger(SalesforceToMySQLDBUsingBatchProcessingIT.class);
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

    static Stream<Arguments> salesforceFailures() {
        return Arrays.stream(SalesforceStep.values()).flatMap(step -> Stream.of(
                Arguments.of(step, "vendor fault with a cause and a suppressed exception", vendorFault(),
                        VENDOR_FAULT_TYPES),
                Arguments.of(step, "SC-9 upstream exception over the vendor fault",
                        new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, TOKEN + " " + PERSONAL_EMAIL,
                                vendorFault()),
                        UpstreamUnavailableException.class.getName() + " <- " + VENDOR_FAULT_TYPES),
                Arguments.of(step, "five classes, all named", causeChain(5),
                        "java.lang.RuntimeException <- java.lang.IllegalStateException"
                                + " <- java.lang.IllegalArgumentException <- java.lang.UnsupportedOperationException"
                                + " <- java.io.IOException"),
                Arguments.of(step, "six classes, cut after five", causeChain(6),
                        "java.lang.RuntimeException <- java.lang.IllegalStateException"
                                + " <- java.lang.IllegalArgumentException <- java.lang.UnsupportedOperationException"
                                + " <- java.io.IOException <- ..."),
                Arguments.of(step, "circular cause chain", circularChain(),
                        "java.lang.IllegalStateException <- java.io.IOException <- [circular]")));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("salesforceFailures")
    void failedCleanupLogsTypeIdentifiersOnlyAndStillAttemptsEveryStep(SalesforceStep step, String label,
                                                                      Exception failure, String failureTypes)
            throws Exception {
        doThrow(rowDeleteFailure()).when(jdbcTemplate).update(DELETE_SQL, LAST_NAME);
        String salesforceLog;
        if (step == SalesforceStep.DELETE) {
            ReflectionTestUtils.setField(liveTest, "contactId", CONTACT_ID);
            when(salesforcePartnerClient.delete(List.of(CONTACT_ID))).thenThrow(failure);
            salesforceLog = "Could not delete Salesforce Contact " + CONTACT_ID + ": " + failureTypes;
        } else {
            ReflectionTestUtils.setField(liveTest, "createOutcomeUnknown", true);
            when(salesforcePartnerClient.query(LOOKUP_SOQL)).thenThrow(failure);
            salesforceLog = "Could not look up the Salesforce Contact with LastName " + LAST_NAME + " for deletion: "
                    + failureTypes;
        }

        liveTest.tearDown();

        verify(jdbcTemplate).update(DELETE_SQL, LAST_NAME);
        if (step == SalesforceStep.DELETE) {
            verify(salesforcePartnerClient).delete(List.of(CONTACT_ID));
        } else {
            // The lookup found no Id, so there is no Contact the cleanup could delete.
            verify(salesforcePartnerClient).query(LOOKUP_SOQL);
        }
        verifyNoMoreInteractions(jdbcTemplate, salesforcePartnerClient);
        assertEquals(List.of(ROW_FAILURE_LOG, salesforceLog), formattedMessages());
        assertEquals(List.of(Level.WARN, Level.WARN), levels());
        assertDisclosesNothing();
    }

    static Stream<Arguments> failedDeleteResults() {
        return Stream.of(
                Arguments.of("two errors with messages and fields",
                        List.of(failedResult(error(StatusCode.INVALID_CROSS_REFERENCE_KEY),
                                error(StatusCode.ENTITY_IS_DELETED))),
                        "id=" + CONTACT_ID + ", success=false", "INVALID_CROSS_REFERENCE_KEY; ENTITY_IS_DELETED"),
                Arguments.of("an error without a status code", List.of(failedResult(error(null))),
                        "id=" + CONTACT_ID + ", success=false", "(null)"),
                Arguments.of("no error", List.of(failedResult()), "id=" + CONTACT_ID + ", success=false",
                        "no error detail"),
                Arguments.of("no delete result", List.of(), "no delete result", "no delete result"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failedDeleteResults")
    void failedDeleteResultLogsStatusCodesOnly(String label, List<DeleteResult> results, String resultLog,
                                              String errorsLog) throws Exception {
        doThrow(rowDeleteFailure()).when(jdbcTemplate).update(DELETE_SQL, LAST_NAME);
        ReflectionTestUtils.setField(liveTest, "contactId", CONTACT_ID);
        when(salesforcePartnerClient.delete(List.of(CONTACT_ID))).thenReturn(results);

        liveTest.tearDown();

        verify(jdbcTemplate).update(DELETE_SQL, LAST_NAME);
        verify(salesforcePartnerClient).delete(List.of(CONTACT_ID));
        verifyNoMoreInteractions(jdbcTemplate, salesforcePartnerClient);
        assertEquals(List.of(ROW_FAILURE_LOG, "Remove Contact from Salesforce result: " + resultLog,
                "Could not delete Salesforce Contact " + CONTACT_ID + ": " + errorsLog), formattedMessages());
        assertEquals(List.of(Level.WARN, Level.INFO, Level.WARN), levels());
        assertDisclosesNothing();
    }

    @Test
    void failureTypesNamesAMissingFailure() {
        String rendered = ReflectionTestUtils.invokeMethod(SalesforceToMySQLDBUsingBatchProcessingIT.class,
                "failureTypes", (Object) null);

        assertEquals("(null)", rendered);
    }

    /** A JDBC failure as Spring translates it: the SQL with its values in the message and in the cause's message. */
    private static BadSqlGrammarException rowDeleteFailure() {
        SQLSyntaxErrorException cause = new SQLSyntaxErrorException(
                SQL_WITH_VALUES + " AND email='" + PERSONAL_EMAIL + "' /* " + PASSWORD + " */");
        BadSqlGrammarException failure = new BadSqlGrammarException("deleteContactFromDB", SQL_WITH_VALUES, cause);
        failure.addSuppressed(new IllegalStateException(TOKEN + " " + PERSONAL_EMAIL));
        return failure;
    }

    /** A WSC failure carrying a token, a password, a contact e-mail and SQL in its message, cause and suppressed. */
    private static ConnectionException vendorFault() {
        ConnectionException fault = new ConnectionException("Session " + TOKEN + " rejected for " + PERSONAL_EMAIL,
                new SocketException("reset while sending " + PASSWORD));
        fault.addSuppressed(new IllegalStateException(SQL_WITH_VALUES));
        return fault;
    }

    /** A chain of {@code length} exceptions of distinct classes, each message carrying a sensitive value. */
    private static Exception causeChain(int length) {
        List<Exception> chain = List.of(new RuntimeException(TOKEN), new IllegalStateException(PASSWORD),
                new IllegalArgumentException(PERSONAL_EMAIL), new UnsupportedOperationException(SQL_WITH_VALUES),
                new IOException(TOKEN), new SocketException(PASSWORD));
        for (int i = 0; i < length - 1; i++) {
            chain.get(i).initCause(chain.get(i + 1));
        }
        return chain.get(0);
    }

    /** Two exceptions that are each other's cause. */
    private static Exception circularChain() {
        IllegalStateException outer = new IllegalStateException(PERSONAL_EMAIL);
        IOException inner = new IOException(TOKEN);
        outer.initCause(inner);
        inner.initCause(outer);
        return outer;
    }

    private static DeleteResult failedResult(com.sforce.soap.partner.Error... errors) {
        DeleteResult result = new DeleteResult();
        result.setId(CONTACT_ID);
        result.setSuccess(false);
        result.setErrors(errors);
        return result;
    }

    /** A Salesforce error whose message and fields repeat submitted values. */
    private static com.sforce.soap.partner.Error error(StatusCode statusCode) {
        com.sforce.soap.partner.Error error = new com.sforce.soap.partner.Error();
        error.setStatusCode(statusCode);
        error.setMessage("entity is deleted: " + PERSONAL_EMAIL + " " + TOKEN);
        error.setFields(new String[] {"Email=" + PERSONAL_EMAIL, SQL_WITH_VALUES, PASSWORD});
        return error;
    }

    private List<String> formattedMessages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<Level> levels() {
        return appender.list.stream().map(ILoggingEvent::getLevel).toList();
    }

    /** Asserts that no event attaches a throwable or renders a sensitive value in its message or its arguments. */
    private void assertDisclosesNothing() {
        for (ILoggingEvent event : appender.list) {
            assertNull(event.getThrowableProxy(), event::getFormattedMessage);
            List<String> rendered = new ArrayList<>();
            rendered.add(event.getFormattedMessage());
            Object[] arguments = event.getArgumentArray();
            if (arguments != null) {
                for (Object argument : arguments) {
                    assertFalse(argument instanceof Throwable, event::getFormattedMessage);
                    rendered.add(String.valueOf(argument));
                }
            }
            for (String text : rendered) {
                for (String value : UNDISCLOSED) {
                    assertFalse(text.contains(value), () -> "Logged '" + value + "': " + text);
                }
            }
        }
    }
}
