/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.client.SharepointRestClient;
import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config.SharepointProperties;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests {@link SharepointImportService}, the body of the original flow
 * {@code importing-a-csv-file-into-ms-sharepointFlow}: sign in, create {@code /Shared Documents/<csvImportFolder>},
 * add the polled bytes there as {@code contacts.csv} with overwrite, then log the success line, in that order.
 *
 * <p>{@link SharepointRestClient} is a Mockito mock, so no request leaves the JVM and no credential is needed. The
 * original declares no exception strategy, so a failure at any step must reach the caller unchanged, skip every
 * later step and suppress the success line. Before it is rethrown, the failure is logged once at ERROR, on one line,
 * without a throwable, naming only the step, the HTTP status code and the class names of the exception and its
 * causes; the configuration check's message, which names keys alone, is the one exception text logged. Distinctive
 * markers in OData error bodies, reason phrases, SOAP fault reasons, request URLs, exception messages and causes,
 * and in the folder path, must never reach the line.
 */
@ExtendWith(MockitoExtension.class)
class SharepointImportServiceTest {

    private static final String FOLDER = "/Shared Documents/test-folder";

    private static final String FILE = FOLDER + "/contacts.csv";

    private static final String SUCCESS_LINE =
            "Input file stored in the specified folder under the 'Shared Documents' folder";

    private static final SharepointRestClient.SharepointSession SESSION =
            new SharepointRestClient.SharepointSession("rtFa=a; FedAuth=b", "digest");

    private static final String PASSWORD = "sp-password-value-4b8e";

    private static final String RTFA_VALUE = "rtfa-cookie-value-7c1e";

    private static final String FEDAUTH_VALUE = "fedauth-cookie-value-93ab";

    private static final String FORM_DIGEST = "form-digest-value-5d2f";

    /** A session whose cookie values and form digest are distinctive, so a leak into the log is detectable. */
    private static final SharepointRestClient.SharepointSession SECRET_SESSION = new SharepointRestClient.SharepointSession(
            "rtFa=" + RTFA_VALUE + "; FedAuth=" + FEDAUTH_VALUE, FORM_DIGEST);

    /** Upstream-controlled text that must never be logged. */
    private static final String MARKER = "MARKER-UPSTREAM-SECRET-61f0";

    /** The personal data an OData error message may quote. */
    private static final String CUSTOMER_EMAIL = "customer@example.com";

    /** An OData error message quoting a federation cookie and a customer's address, as the finding's input does. */
    private static final String ODATA_MARKER_MESSAGE =
            "FedAuth=" + FEDAUTH_VALUE + "; " + CUSTOMER_EMAIL + " " + MARKER;

    /** A request URL as Spring quotes it in a {@code ResourceAccessException} message. */
    private static final String MARKER_URL = "http://127.0.0.1:9/_api/contextinfo?" + MARKER;

    private static final String ISE = "java.lang.IllegalStateException";

    /** The configuration check's exception, the one type whose message the ERROR line carries. */
    private static final String INVALID_CONFIGURATION =
            "com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config.SharepointProperties"
                    + "$InvalidConfigurationException";

    /** Every value that must never appear in an ERROR line, whichever step fails and however. */
    private static final String[] NEVER_LOGGED = {
        PASSWORD, RTFA_VALUE, FEDAUTH_VALUE, FORM_DIGEST, MARKER, CUSTOMER_EMAIL, "FedAuth=", "127.0.0.1",
        "Shared Documents", "test-folder", "contacts.csv"
    };

    /** The step that fails, and the start of the ERROR line the service logs for it. */
    enum Step {
        SIGN_IN("SharePoint step 'sign-in' failed: "),
        FOLDER_CREATE("SharePoint step 'folder create' failed: "),
        FILE_ADD("SharePoint step 'file add' failed: ");

        private final String linePrefix;

        Step(String linePrefix) {
            this.linePrefix = linePrefix;
        }
    }

    @Mock
    SharepointRestClient client;

    private SharepointImportService service;

    private byte[] bytes;

    private byte[] expectedBytes;

    private Logger serviceLogger;

    private Level savedLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        service = serviceFor("test-folder");
        bytes = "firstname;surname;phone;email\nJohn;Doe;096548763;john.doe@texasComp.com\n".getBytes(UTF_8);
        expectedBytes = bytes.clone();

        serviceLogger = (Logger) LoggerFactory.getLogger(SharepointImportService.class);
        savedLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(savedLevel);
    }

    @Test
    void importFileSignsInCreatesFolderThenAddsFileWithOverwrite() {
        givenSignInSucceeds();

        service.importFile(bytes);

        InOrder order = inOrder(client);
        order.verify(client).signIn();
        order.verify(client).createFolder(SESSION, FOLDER);
        order.verify(client).addFile(eq(SESSION), eq(FILE), aryEq(expectedBytes), eq(true));

        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<Boolean> overwrite = ArgumentCaptor.forClass(Boolean.class);
        verify(client).addFile(any(), anyString(), content.capture(), overwrite.capture());
        assertThat(overwrite.getValue()).isTrue();
        assertThat(content.getValue()).containsExactly(expectedBytes);
        verifyNoMoreInteractions(client);
    }

    @Test
    void importFileLogsTheOriginalInfoLineOnce() {
        givenSignInSucceeds();

        service.importFile(bytes);

        assertThat(infoEvents())
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(SUCCESS_LINE);
    }

    @Test
    void folderNameIsUsedVerbatim() {
        service = serviceFor("My Folder 'Q'");
        givenSignInSucceeds();

        service.importFile(bytes);

        InOrder order = inOrder(client);
        order.verify(client).signIn();
        order.verify(client).createFolder(SESSION, "/Shared Documents/My Folder 'Q'");
        order.verify(client).addFile(eq(SESSION), eq("/Shared Documents/My Folder 'Q'/contacts.csv"),
                aryEq(expectedBytes), eq(true));
        verifyNoMoreInteractions(client);
    }

    @Test
    void signInFailurePropagatesAndSkipsEveryLaterStep() {
        IllegalStateException failure = new IllegalStateException("sign-in failed");
        when(client.signIn()).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.importFile(bytes)));

        verify(client).signIn();
        verify(client, never()).createFolder(any(), anyString());
        verify(client, never()).addFile(any(), anyString(), any(), anyBoolean());
        assertThat(infoEvents()).isEmpty();
        verifyNoMoreInteractions(client);
    }

    @Test
    void createFolderFailurePropagatesAndSkipsAddFile() {
        HttpClientErrorException failure =
                HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(), null, null);
        givenSignInSucceeds();
        doThrow(failure).when(client).createFolder(SESSION, FOLDER);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.importFile(bytes)));

        InOrder order = inOrder(client);
        order.verify(client).signIn();
        order.verify(client).createFolder(SESSION, FOLDER);
        verify(client, never()).addFile(any(), anyString(), any(), anyBoolean());
        assertThat(infoEvents()).isEmpty();
        verifyNoMoreInteractions(client);
    }

    @Test
    void addFileFailurePropagatesWithoutTheSuccessLine() {
        ResourceAccessException failure = new ResourceAccessException("I/O error on POST request");
        givenSignInSucceeds();
        doThrow(failure).when(client).addFile(eq(SESSION), eq(FILE), aryEq(expectedBytes), eq(true));

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.importFile(bytes)));

        InOrder order = inOrder(client);
        order.verify(client).signIn();
        order.verify(client).createFolder(SESSION, FOLDER);
        order.verify(client).addFile(eq(SESSION), eq(FILE), aryEq(expectedBytes), eq(true));
        assertThat(infoEvents()).isEmpty();
        verifyNoMoreInteractions(client);
    }

    @Test
    void urlPartsMatchTheOriginalFlow() {
        assertThat(SharepointImportService.SHARED_DOCUMENTS).isEqualTo("/Shared Documents/");
        assertThat(SharepointImportService.TARGET_FILE_NAME).isEqualTo("contacts.csv");
    }

    @ParameterizedTest(name = "[{index}] {0}: {1}")
    @MethodSource("stepFailures")
    void failingStepIsLoggedOnceAtErrorThenRethrownUnchanged(Step step, String scenario, RuntimeException failure,
                                                            String expectedReason) {
        service = serviceWithDistinctiveSecrets();
        switch (step) {
            case SIGN_IN -> when(client.signIn()).thenThrow(failure);
            case FOLDER_CREATE -> {
                when(client.signIn()).thenReturn(SECRET_SESSION);
                doThrow(failure).when(client).createFolder(SECRET_SESSION, FOLDER);
            }
            case FILE_ADD -> {
                when(client.signIn()).thenReturn(SECRET_SESSION);
                doThrow(failure).when(client).addFile(eq(SECRET_SESSION), eq(FILE), aryEq(expectedBytes), eq(true));
            }
        }

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.importFile(bytes)));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).isEqualTo(step.linePrefix + expectedReason);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getArgumentArray()).noneMatch(Throwable.class::isInstance);
        assertThat(event.getFormattedMessage()).doesNotContain(NEVER_LOGGED).doesNotContain("\r", "\n");

        InOrder order = inOrder(client);
        order.verify(client).signIn();
        if (step != Step.SIGN_IN) {
            order.verify(client).createFolder(SECRET_SESSION, FOLDER);
        }
        if (step == Step.FILE_ADD) {
            order.verify(client).addFile(eq(SECRET_SESSION), eq(FILE), aryEq(expectedBytes), eq(true));
        }
        verifyNoMoreInteractions(client);
    }

    @Test
    void folderPathIsNeverLoggedWhileTheClientGetsItUnchanged() {
        String folderName = "evil\r\nERROR forged\tline " + MARKER;
        service = serviceFor(folderName);
        IllegalStateException failure = new IllegalStateException("folder failed");
        givenSignInSucceeds();
        doThrow(failure).when(client).createFolder(SESSION, "/Shared Documents/" + folderName);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.importFile(bytes)));

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("SharePoint step 'folder create' failed: " + ISE);
        verify(client, never()).addFile(any(), anyString(), any(), anyBoolean());
    }

    /**
     * One row per failing step and reason: the step, a scenario label, the exception the client throws and the reason
     * the ERROR line must end with. Every row also runs the {@link #NEVER_LOGGED} check, so the marker rows prove that
     * upstream text, exception messages and causes' messages are dropped while the status code and class names stay.
     */
    static Stream<Arguments> stepFailures() {
        IllegalStateException missingConfiguration = assertThrows(IllegalStateException.class,
                new SharepointProperties("user@example.onmicrosoft.com", "", "http://127.0.0.1:9", "",
                        "http://127.0.0.1:9/extSTS.srf")::requireValid);
        IllegalStateException invalidSiteUrl = assertThrows(IllegalStateException.class,
                new SharepointProperties("user@example.onmicrosoft.com", PASSWORD,
                        "https://" + CUSTOMER_EMAIL + ":" + MARKER + "@127.0.0.1:9/?" + MARKER, "test-folder",
                        "http://127.0.0.1:9/extSTS.srf")::requireValid);
        IllegalStateException anonymousType = new IllegalStateException("anonymous failure " + MARKER) {
            private static final long serialVersionUID = 1L;
        };
        IllegalStateException circular = new IllegalStateException(MARKER);
        IOException circularCause = new IOException(MARKER, circular);
        circular.initCause(circularCause);
        RuntimeException deepCause = new IllegalStateException(MARKER);
        for (int depth = 0; depth < 5; depth++) {
            deepCause = new RuntimeException(MARKER + depth, deepCause);
        }
        String odataVerbose = "{\"error\":{\"code\":\"-2130575214, Microsoft.SharePoint.SPException\",\"message\":"
                + "{\"lang\":\"en-US\",\"value\":\"" + ODATA_MARKER_MESSAGE + "\"}}}";
        String odataLight = "{\"odata.error\":{\"code\":\"-1, Microsoft.SharePoint.SPException\",\"message\":"
                + "{\"lang\":\"en-US\",\"value\":\"" + ODATA_MARKER_MESSAGE + "\"}}}";
        String odataV4 = "{\"error\":{\"code\":\"itemNotFound\",\"message\":\"" + ODATA_MARKER_MESSAGE + "\"}}";
        return Stream.of(
                Arguments.of(Step.SIGN_IN, "missing configuration: the key names are logged", missingConfiguration,
                        INVALID_CONFIGURATION + ": Missing required SharePoint configuration: Sharepoint.Password"
                                + " (SHAREPOINT_PASSWORD), csvImportFolder (CSVIMPORTFOLDER)."
                                + " # TODO: supply them in application.yml or as environment variables."),
                Arguments.of(Step.FILE_ADD, "invalid site URL holding markers: the key name is logged, not the value",
                        invalidSiteUrl,
                        INVALID_CONFIGURATION + ": Invalid SharePoint configuration: Sharepoint.SiteUrl"
                                + " (SHAREPOINT_SITEURL) must be an absolute http or https URL with a host and without"
                                + " user info, query or fragment."
                                + " # TODO: supply them in application.yml or as environment variables."),
                Arguments.of(Step.SIGN_IN, "configuration text in an ordinary IllegalStateException",
                        new IllegalStateException("Missing required SharePoint configuration: " + MARKER), ISE),
                Arguments.of(Step.SIGN_IN, "security token service fault reason holding a marker",
                        new IllegalStateException("SharePoint Online sign-in failed: AADSTS50126: Error validating"
                                + " credentials due to invalid username or password. " + MARKER + " FedAuth="
                                + FEDAUTH_VALUE),
                        ISE),
                Arguments.of(Step.SIGN_IN, "missing federation cookie",
                        new IllegalStateException("SharePoint Online sign-in failed: missing cookie(s) FedAuth"), ISE),
                Arguments.of(Step.SIGN_IN, "security token service error status with a marker reason phrase",
                        HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, MARKER, new HttpHeaders(),
                                null, null),
                        "HTTP 503 (org.springframework.web.client.HttpServerErrorException$ServiceUnavailable)"),
                Arguments.of(Step.SIGN_IN, "contextinfo 403 with an OData verbose error",
                        httpError(HttpStatus.FORBIDDEN, MARKER, odataVerbose),
                        "HTTP 403 (org.springframework.web.client.HttpClientErrorException$Forbidden)"),
                Arguments.of(Step.SIGN_IN, "contextinfo timeout with a marker URL",
                        new ResourceAccessException("I/O error on POST request for \"" + MARKER_URL
                                + "\": SharePoint response not received in full within 10000 ms",
                                new HttpTimeoutException(MARKER)),
                        "org.springframework.web.client.ResourceAccessException <- java.net.http.HttpTimeoutException"),
                Arguments.of(Step.SIGN_IN, "cause whose message holds a marker",
                        new IllegalStateException("sign-in failed", new IOException(MARKER + " " + PASSWORD)),
                        ISE + " <- java.io.IOException"),
                Arguments.of(Step.SIGN_IN, "anonymous exception type", anonymousType,
                        anonymousType.getClass().getName()),
                Arguments.of(Step.SIGN_IN, "exception whose message cannot be read", new UnreadableFailure(),
                        UnreadableFailure.class.getName()),
                Arguments.of(Step.SIGN_IN, "exception whose cause cannot be read", new UnreadableCause(),
                        UnreadableCause.class.getName() + " <- [unavailable]"),
                Arguments.of(Step.FOLDER_CREATE, "400 with an OData verbose error and a marker reason phrase",
                        httpError(HttpStatus.BAD_REQUEST, MARKER, odataVerbose),
                        "HTTP 400 (org.springframework.web.client.HttpClientErrorException$BadRequest)"),
                Arguments.of(Step.FOLDER_CREATE, "500 with an OData JSON light error and a marker reason phrase",
                        httpError(HttpStatus.INTERNAL_SERVER_ERROR, MARKER, odataLight),
                        "HTTP 500 (org.springframework.web.client.HttpServerErrorException$InternalServerError)"),
                Arguments.of(Step.FOLDER_CREATE, "unexpected exception type with a marker",
                        new UnsupportedOperationException(MARKER + " " + PASSWORD),
                        "java.lang.UnsupportedOperationException"),
                Arguments.of(Step.FOLDER_CREATE, "message holding a forged line and a marker",
                        new IllegalStateException("line one\r\nERROR forged\tline\u2028" + MARKER), ISE),
                Arguments.of(Step.FOLDER_CREATE, "circular cause chain",
                        circular, ISE + " <- java.io.IOException <- [circular]"),
                Arguments.of(Step.FOLDER_CREATE, "cause chain longer than the limit", deepCause,
                        "java.lang.RuntimeException <- java.lang.RuntimeException <- java.lang.RuntimeException"
                                + " <- java.lang.RuntimeException <- java.lang.RuntimeException <- ..."),
                Arguments.of(Step.FILE_ADD, "404 with an OData v4 error and a marker reason phrase",
                        httpError(HttpStatus.NOT_FOUND, MARKER, odataV4),
                        "HTTP 404 (org.springframework.web.client.HttpClientErrorException$NotFound)"),
                Arguments.of(Step.FILE_ADD, "500 with an HTML body",
                        httpError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                                "<html>secret-body " + FORM_DIGEST + " " + MARKER + "</html>"),
                        "HTTP 500 (org.springframework.web.client.HttpServerErrorException$InternalServerError)"),
                Arguments.of(Step.FILE_ADD, "500 without a body",
                        httpError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", null),
                        "HTTP 500 (org.springframework.web.client.HttpServerErrorException$InternalServerError)"),
                Arguments.of(Step.FILE_ADD, "status outside the known codes without a reason phrase",
                        HttpServerErrorException.create(HttpStatusCode.valueOf(599), "", new HttpHeaders(), null, null),
                        "HTTP 599 (org.springframework.web.client.HttpServerErrorException)"),
                Arguments.of(Step.FILE_ADD, "status code that cannot be read", new UnreadableStatus(),
                        "HTTP [unavailable] (" + UnreadableStatus.class.getName() + ")"),
                Arguments.of(Step.FILE_ADD, "connection refused with a marker URL",
                        new ResourceAccessException("I/O error on POST request for \"" + MARKER_URL
                                + "\": Connection refused", new ConnectException(MARKER)),
                        "org.springframework.web.client.ResourceAccessException <- java.net.ConnectException"),
                Arguments.of(Step.FILE_ADD, "response that cannot be extracted",
                        new RestClientException("Error while extracting response for type [byte[]] " + MARKER),
                        "org.springframework.web.client.RestClientException"),
                Arguments.of(Step.FILE_ADD, "malformed file URL holding a marker",
                        new IllegalArgumentException("fileServerRelativeUrl must be <folder>/<name>: " + MARKER),
                        "java.lang.IllegalArgumentException"),
                Arguments.of(Step.FILE_ADD, "client failure without a message", new IllegalStateException(), ISE));
    }

    /**
     * An HTTP error status as RestClient's default handler raises it: the message quotes the status text and the
     * whole body, neither of which the service may log.
     */
    private static RestClientResponseException httpError(HttpStatus status, String statusText, String body) {
        byte[] content = body == null ? null : body.getBytes(UTF_8);
        String message = status.value() + " " + statusText + (body == null ? "" : ": \"" + body + "\"");
        return status.is4xxClientError()
                ? HttpClientErrorException.create(message, status, statusText, new HttpHeaders(), content, UTF_8)
                : HttpServerErrorException.create(message, status, statusText, new HttpHeaders(), content, UTF_8);
    }

    /** A client failure whose message accessor itself fails, which the service must never call. */
    private static final class UnreadableFailure extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new UnsupportedOperationException("message unavailable");
        }
    }

    /** A client failure whose cause accessor fails. */
    private static final class UnreadableCause extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        UnreadableCause() {
            super(MARKER);
        }

        @Override
        public synchronized Throwable getCause() {
            throw new UnsupportedOperationException("cause unavailable");
        }
    }

    /** An HTTP error whose status code accessor fails. */
    private static final class UnreadableStatus extends HttpServerErrorException {

        private static final long serialVersionUID = 1L;

        UnreadableStatus() {
            super(HttpStatus.INTERNAL_SERVER_ERROR, MARKER);
        }

        @Override
        public HttpStatusCode getStatusCode() {
            throw new UnsupportedOperationException("status unavailable");
        }
    }

    private SharepointImportService serviceWithDistinctiveSecrets() {
        SharepointProperties props = new SharepointProperties("user@example.onmicrosoft.com", PASSWORD,
                "http://127.0.0.1:9", "test-folder", "http://127.0.0.1:9/extSTS.srf");
        return new SharepointImportService(client, props);
    }

    private SharepointImportService serviceFor(String csvImportFolder) {
        SharepointProperties props = new SharepointProperties("user@example.onmicrosoft.com", "pw",
                "http://127.0.0.1:9", csvImportFolder, "http://127.0.0.1:9/extSTS.srf");
        return new SharepointImportService(client, props);
    }

    private void givenSignInSucceeds() {
        when(client.signIn()).thenReturn(SESSION);
    }

    private List<ILoggingEvent> infoEvents() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .toList();
    }
}
