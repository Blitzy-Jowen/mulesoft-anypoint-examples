/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.DynamicsWebApiClient;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.UpstreamSystem;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamAuthenticationException;

/**
 * Tests the log safety of the cleanup of {@link ImportContactsIntoMSDynamicsIT}, {@code tearDown()}, under
 * {@code mvn test}: without credentials, a Spring context, Entra or Dataverse. The IT is instantiated directly, its
 * Dataverse client is a Mockito mock that fails every query and delete with an exception whose message, cause
 * message, suppressed message and response body carry a token, a client secret, a personal e-mail address and an
 * OData filter, and a Logback {@link ListAppender} on the IT's logger records what the cleanup logs.
 *
 * <p>Every event must attach no throwable, carry none of those values in its message or arguments, and name the
 * failure by the class names of it and its causes, with the HTTP status of a Dataverse error answer, at the level
 * the cleanup uses; every query and delete must still be attempted and no failure thrown.
 */
class ImportContactsIntoMSDynamicsITCleanupTest {

    /** The fixture e-mails the IT queries again before deleting, in its order. */
    private static final List<String> FIXTURE_EMAILS = List.of("john.doe@texasComp.com", "jane.doe@texasComp.com");

    /** Dataverse contact IDs the IT read back, in its order. */
    private static final List<String> CONTACT_IDS = List.of(
            "5f8e2c1a-3b4d-4e6f-8a9b-0c1d2e3f4a5b",
            "6a7b8c9d-0e1f-4a2b-9c3d-4e5f6a7b8c9d");

    private static final String TOKEN = "token=s3cr3t-token";

    private static final String CLIENT_SECRET = "client_secret=hunter2";

    private static final String PERSONAL_EMAIL = "jane.doe@example.com";

    private static final String ODATA_FILTER = "$filter=emailaddress1 eq 'john.doe@texasComp.com'";

    /** The text every failure below carries, which no logged message or argument may contain. */
    private static final List<String> MARKERS = List.of(TOKEN, "s3cr3t-token", CLIENT_SECRET, "hunter2",
            PERSONAL_EMAIL, ODATA_FILTER, "$filter");

    private static final String RESPONSE_TYPE = WebClientResponseException.class.getName();

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(ImportContactsIntoMSDynamicsIT.class);
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

    /** Each failure with the description the cleanup must log for it, once after the file drop and once without. */
    static Stream<Arguments> cleanupFailures() {
        IllegalStateException withCauseAndSuppressed =
                new IllegalStateException("Dataverse query failed: " + ODATA_FILTER + " " + TOKEN,
                        new IOException(CLIENT_SECRET + " for " + PERSONAL_EMAIL));
        withCauseAndSuppressed.addSuppressed(new IllegalArgumentException(TOKEN + " " + PERSONAL_EMAIL));

        WebClientResponseException notFound = dataverseAnswer(HttpStatus.NOT_FOUND);
        notFound.initCause(new IOException(CLIENT_SECRET));
        notFound.addSuppressed(new IllegalStateException(PERSONAL_EMAIL));

        UpstreamAuthenticationException rejected = new UpstreamAuthenticationException(
                UpstreamSystem.MICROSOFT_DYNAMICS_365, "Entra rejected " + CLIENT_SECRET + " " + TOKEN,
                dataverseAnswer(HttpStatus.UNAUTHORIZED));

        IllegalStateException circularOuter = new IllegalStateException(TOKEN);
        IOException circularInner = new IOException(PERSONAL_EMAIL, circularOuter);
        circularOuter.initCause(circularInner);

        Stream<Arguments> failures = Stream.of(
                Arguments.of("cause and suppressed exception carrying secrets", withCauseAndSuppressed,
                        "java.lang.IllegalStateException <- java.io.IOException"),
                Arguments.of("Dataverse 404 answer with its body and request URI", notFound,
                        RESPONSE_TYPE + "$NotFound (HTTP 404) <- java.io.IOException"),
                Arguments.of("SC-9 authentication failure over a Dataverse 401", rejected,
                        UpstreamAuthenticationException.class.getName() + " <- " + RESPONSE_TYPE
                                + "$Unauthorized (HTTP 401)"),
                Arguments.of("circular cause chain", circularOuter,
                        "java.lang.IllegalStateException <- java.io.IOException <- [circular]"),
                Arguments.of("five exceptions, all named", causeChain(5),
                        "java.lang.RuntimeException <- java.lang.IllegalStateException"
                                + " <- java.lang.IllegalArgumentException <- java.lang.UnsupportedOperationException"
                                + " <- java.io.IOException"),
                Arguments.of("six exceptions, cut after five", causeChain(6),
                        "java.lang.RuntimeException <- java.lang.IllegalStateException"
                                + " <- java.lang.IllegalArgumentException <- java.lang.UnsupportedOperationException"
                                + " <- java.io.IOException <- ..."),
                Arguments.of("cause that throws when read", new UnreadableCauseException(),
                        UnreadableCauseException.class.getName() + " <- [unavailable]"));
        return failures.flatMap(failure -> Stream.of(true, false).map(fileDropped -> Arguments.of(
                failure.get()[0] + (fileDropped ? ", file dropped" : ", file not dropped"),
                failure.get()[1], failure.get()[2], fileDropped)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cleanupFailures")
    void cleanupLogsFailureTypesOnlyAndStillAttemptsEveryDelete(String label, Exception failure, String types,
                                                               boolean fileDropped) throws Exception {
        assertTrue(MARKERS.stream().anyMatch(String.valueOf(failure.getMessage())::contains),
                "the failure must carry a marker for the test to prove anything: " + label);
        DynamicsWebApiClient client = mock(DynamicsWebApiClient.class);
        when(client.findContactsByEmail(anyString())).thenThrow(failure);
        doThrow(failure).when(client).deleteContact(anyString());
        ImportContactsIntoMSDynamicsIT it = new ImportContactsIntoMSDynamicsIT();
        ReflectionTestUtils.setField(it, "client", client);
        ReflectionTestUtils.setField(it, "fileDropped", fileDropped);
        List<String> contactIds = contactIds(it);
        contactIds.addAll(CONTACT_IDS);

        assertDoesNotThrow(it::tearDown);

        List<String> expected = new ArrayList<>();
        if (fileDropped) {
            for (String email : FIXTURE_EMAILS) {
                expected.add("WARN Could not query the Dataverse contacts with e-mail " + email + " for deletion: "
                        + types);
            }
        }
        for (String id : CONTACT_IDS) {
            expected.add("ERROR Failed to delete Dataverse contact " + id + ": " + types);
        }
        List<ILoggingEvent> events = appender.list;
        assertEquals(expected, events.stream().map(event -> event.getLevel() + " " + event.getFormattedMessage())
                .toList());
        for (ILoggingEvent event : events) {
            assertNull(event.getThrowableProxy(), event.getFormattedMessage());
            assertDisclosesNothing(event.getFormattedMessage());
            Object[] arguments = event.getArgumentArray();
            assertNotNull(arguments, event.getFormattedMessage());
            for (Object argument : arguments) {
                assertFalse(argument instanceof Throwable, event.getFormattedMessage());
                assertDisclosesNothing(String.valueOf(argument));
            }
        }

        InOrder order = inOrder(client);
        if (fileDropped) {
            for (String email : FIXTURE_EMAILS) {
                order.verify(client).findContactsByEmail(email);
            }
        }
        for (String id : CONTACT_IDS) {
            order.verify(client).deleteContact(id);
        }
        verifyNoMoreInteractions(client);
        assertTrue(contactIds.isEmpty(), contactIds::toString);
    }

    @Test
    void failureTypesNamesANullFailure() {
        String types = ReflectionTestUtils.invokeMethod(ImportContactsIntoMSDynamicsIT.class, "failureTypes",
                (Object) null);

        assertEquals("(null)", types);
    }

    /** The IT's own list of contact IDs to delete, which its cleanup reads and clears. */
    @SuppressWarnings("unchecked")
    private static List<String> contactIds(ImportContactsIntoMSDynamicsIT it) {
        Object contactIds = ReflectionTestUtils.getField(it, "contactIds");
        assertNotNull(contactIds);
        return (List<String>) contactIds;
    }

    private static void assertDisclosesNothing(String text) {
        for (String marker : MARKERS) {
            assertFalse(text.contains(marker), () -> "logged text discloses " + marker + ": " + text);
        }
    }

    /**
     * A Dataverse error answer as {@code retrieve()} raises it: the request URI carries the OData filter and a
     * personal e-mail, so they are part of the message, and the JSON body carries a token and a client secret.
     */
    private static WebClientResponseException dataverseAnswer(HttpStatus status) {
        URI uri = URI.create("https://org.crm.dynamics.com/api/data/v9.2/contacts?$select=contactid"
                + "&$filter=emailaddress1%20eq%20'" + PERSONAL_EMAIL + "'");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":{\"code\":\"0x80040217\",\"message\":\"" + TOKEN + " " + CLIENT_SECRET + " "
                + PERSONAL_EMAIL + "\"}}").getBytes(StandardCharsets.UTF_8);
        return WebClientResponseException.create(status, status.getReasonPhrase() + " " + TOKEN, headers, body,
                StandardCharsets.UTF_8, new MockClientHttpRequest(HttpMethod.GET, uri));
    }

    /** A chain of {@code length} exceptions, outermost first, each message carrying a marker. */
    private static Exception causeChain(int length) {
        List<Exception> chain = List.of(
                new RuntimeException(TOKEN),
                new IllegalStateException(CLIENT_SECRET),
                new IllegalArgumentException(PERSONAL_EMAIL),
                new UnsupportedOperationException(ODATA_FILTER),
                new IOException(TOKEN),
                new UncheckedIOException(PERSONAL_EMAIL, new IOException(CLIENT_SECRET)));
        for (int i = 0; i < length - 1; i++) {
            chain.get(i).initCause(chain.get(i + 1));
        }
        return chain.get(0);
    }

    /** A failure whose cause cannot be read, as a vendor exception with a broken {@code getCause()} would be. */
    private static final class UnreadableCauseException extends Exception {

        private static final long serialVersionUID = 1L;

        UnreadableCauseException() {
            super("unreadable " + TOKEN);
        }

        @Override
        public synchronized Throwable getCause() {
            throw new IllegalStateException(PERSONAL_EMAIL);
        }
    }
}
