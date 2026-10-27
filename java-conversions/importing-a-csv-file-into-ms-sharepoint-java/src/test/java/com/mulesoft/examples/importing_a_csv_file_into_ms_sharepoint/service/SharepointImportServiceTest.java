/**
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
 * with the step, its target and a reason that carries no response body, cookie, form digest or password.
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

    /** The reason prefix of the client's own failures of type {@link IllegalStateException}. */
    private static final String ISE = "IllegalStateException: ";

    /** The step that fails, and the start of the ERROR line the service logs for it. */
    enum Step {
        SIGN_IN("SharePoint step 'sign-in' failed: "),
        FOLDER_CREATE("SharePoint step 'folder create' failed for " + FOLDER + ": "),
        FILE_ADD("SharePoint step 'file add' failed for " + FILE + ": ");

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
        assertThat(event.getFormattedMessage()).doesNotContain(PASSWORD, RTFA_VALUE, FEDAUTH_VALUE, FORM_DIGEST);

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
    void folderTargetIsLoggedOnOneLineWhileTheClientGetsItUnchanged() {
        String folder = "/Shared Documents/evil\r\nERROR forged\tline";
        service = serviceFor("evil\r\nERROR forged\tline");
        IllegalStateException failure = new IllegalStateException("folder failed");
        givenSignInSucceeds();
        doThrow(failure).when(client).createFolder(SESSION, folder);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.importFile(bytes)));

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("SharePoint step 'folder create' failed for /Shared Documents/evil  ERROR forged line: "
                        + ISE + "folder failed");
        verify(client, never()).addFile(any(), anyString(), any(), anyBoolean());
    }

    /**
     * One row per failing step and reason: the step, a scenario label, the exception the client throws and the reason
     * the ERROR line must end with.
     */
    static Stream<Arguments> stepFailures() {
        IllegalStateException missingConfiguration = assertThrows(IllegalStateException.class,
                new SharepointProperties("user@example.onmicrosoft.com", "", "http://127.0.0.1:9", "",
                        "http://127.0.0.1:9/extSTS.srf")::requireValid);
        IllegalStateException anonymousType = new IllegalStateException("anonymous failure") {
            private static final long serialVersionUID = 1L;
        };
        String addFileUrl = "http://127.0.0.1:9/_api/web/GetFolderByServerRelativeUrl('/Shared%20Documents/test-folder')"
                + "/Files/add(url='contacts.csv',overwrite=true)";
        return Stream.of(
                Arguments.of(Step.SIGN_IN, "missing configuration", missingConfiguration,
                        ISE + "Missing required SharePoint configuration: Sharepoint.Password (SHAREPOINT_PASSWORD),"
                                + " csvImportFolder (CSVIMPORTFOLDER)."
                                + " # TODO: supply them in application.yml or as environment variables."),
                Arguments.of(Step.SIGN_IN, "security token service fault reason",
                        new IllegalStateException("SharePoint Online sign-in failed: AADSTS50126: Error validating"
                                + " credentials due to invalid username or password."),
                        ISE + "SharePoint Online sign-in failed: AADSTS50126: Error validating credentials due to"
                                + " invalid username or password."),
                Arguments.of(Step.SIGN_IN, "missing federation cookie",
                        new IllegalStateException("SharePoint Online sign-in failed: missing cookie(s) FedAuth"),
                        ISE + "SharePoint Online sign-in failed: missing cookie(s) FedAuth"),
                Arguments.of(Step.SIGN_IN, "security token service error status, body withheld by the client",
                        HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                                new HttpHeaders(), null, null),
                        "HTTP 503 Service Unavailable"),
                Arguments.of(Step.SIGN_IN, "contextinfo 403 with an OData verbose error",
                        httpError(HttpStatus.FORBIDDEN, "{\"error\":{\"code\":\"-2147024891, System.UnauthorizedAccess"
                                + "Exception\",\"message\":{\"lang\":\"en-US\",\"value\":\"Access denied.\"}}}"),
                        "HTTP 403 Forbidden: Access denied."),
                Arguments.of(Step.SIGN_IN, "contextinfo timeout",
                        new ResourceAccessException("I/O error on POST request for \"http://127.0.0.1:9/_api/contextinfo"
                                + "\": SharePoint response not received in full within 10000 ms"),
                        "ResourceAccessException: I/O error on POST request for \"http://127.0.0.1:9/_api/contextinfo"
                                + "\": SharePoint response not received in full within 10000 ms"),
                Arguments.of(Step.SIGN_IN, "anonymous exception type", anonymousType,
                        anonymousType.getClass().getName() + ": anonymous failure"),
                Arguments.of(Step.SIGN_IN, "exception whose message cannot be read", new UnreadableFailure(),
                        UnreadableFailure.class.getName()),
                Arguments.of(Step.FOLDER_CREATE, "500 with an OData verbose error",
                        httpError(HttpStatus.INTERNAL_SERVER_ERROR,
                                "{\"error\":{\"message\":{\"lang\":\"en-US\",\"value\":\"QA simulated folder failure\"}}}"),
                        "HTTP 500 Internal Server Error: QA simulated folder failure"),
                Arguments.of(Step.FOLDER_CREATE, "500 with an OData JSON light error",
                        httpError(HttpStatus.INTERNAL_SERVER_ERROR, "{\"odata.error\":{\"code\":\"-1, Microsoft.Share"
                                + "Point.SPException\",\"message\":{\"lang\":\"en-US\",\"value\":\"QA light failure\"}}}"),
                        "HTTP 500 Internal Server Error: QA light failure"),
                Arguments.of(Step.FOLDER_CREATE, "404 with an OData v4 error",
                        httpError(HttpStatus.NOT_FOUND,
                                "{\"error\":{\"code\":\"itemNotFound\",\"message\":\"QA v4 failure\"}}"),
                        "HTTP 404 Not Found: QA v4 failure"),
                Arguments.of(Step.FOLDER_CREATE, "OData error message holding CR LF",
                        httpError(HttpStatus.INTERNAL_SERVER_ERROR,
                                "{\"error\":{\"message\":{\"value\":\"first line\\r\\nERROR forged line\"}}}"),
                        "HTTP 500 Internal Server Error: first line  ERROR forged line"),
                Arguments.of(Step.FOLDER_CREATE, "unexpected exception type",
                        new UnsupportedOperationException("do-not-log-me " + PASSWORD),
                        "java.lang.UnsupportedOperationException"),
                Arguments.of(Step.FOLDER_CREATE, "message holding control characters and line and paragraph separators",
                        new IllegalStateException("line one\r\nline two\ttabbed\u2028separated\u2029end"),
                        ISE + "line one  line two tabbed separated end"),
                Arguments.of(Step.FILE_ADD, "500 with an HTML body",
                        httpError(HttpStatus.INTERNAL_SERVER_ERROR, "<html>secret-body " + FORM_DIGEST + "</html>"),
                        "HTTP 500 Internal Server Error"),
                Arguments.of(Step.FILE_ADD, "500 without a body",
                        httpError(HttpStatus.INTERNAL_SERVER_ERROR, null),
                        "HTTP 500 Internal Server Error"),
                Arguments.of(Step.FILE_ADD, "OData error whose message is not a string",
                        httpError(HttpStatus.INTERNAL_SERVER_ERROR,
                                "{\"error\":{\"code\":\"-1\",\"message\":{\"lang\":\"en-US\",\"value\":42}}}"),
                        "HTTP 500 Internal Server Error"),
                Arguments.of(Step.FILE_ADD, "OData error with a blank message",
                        httpError(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":{\"message\":{\"value\":\"  \"}}}"),
                        "HTTP 500 Internal Server Error"),
                Arguments.of(Step.FILE_ADD, "status with an empty reason phrase",
                        HttpServerErrorException.create(HttpStatusCode.valueOf(599), "", new HttpHeaders(), null, null),
                        "HTTP 599"),
                Arguments.of(Step.FILE_ADD, "status without a reason phrase",
                        HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, null, new HttpHeaders(), null, null),
                        "HTTP 502"),
                Arguments.of(Step.FILE_ADD, "connection refused",
                        new ResourceAccessException("I/O error on POST request for \"" + addFileUrl
                                + "\": Connection refused"),
                        "ResourceAccessException: I/O error on POST request for \"" + addFileUrl
                                + "\": Connection refused"),
                Arguments.of(Step.FILE_ADD, "response that cannot be extracted",
                        new RestClientException("Error while extracting response for type [byte[]]"),
                        "RestClientException: Error while extracting response for type [byte[]]"),
                Arguments.of(Step.FILE_ADD, "malformed file URL",
                        new IllegalArgumentException("fileServerRelativeUrl must be <folder>/<name>: contacts.csv"),
                        "IllegalArgumentException: fileServerRelativeUrl must be <folder>/<name>: contacts.csv"),
                Arguments.of(Step.FILE_ADD, "client failure without a message", new IllegalStateException(),
                        "IllegalStateException"),
                Arguments.of(Step.FILE_ADD, "message longer than the limit",
                        new IllegalStateException("x".repeat(1200)),
                        ISE + "x".repeat(1000 - ISE.length()) + "...[truncated]"),
                Arguments.of(Step.FILE_ADD, "surrogate pair across the limit",
                        new IllegalStateException("x".repeat(999 - ISE.length()) + "\uD83D\uDE00 tail"),
                        ISE + "x".repeat(999 - ISE.length()) + "...[truncated]"));
    }

    /**
     * An HTTP error status as RestClient's default handler raises it: the message quotes the whole body, which the
     * service must never log.
     */
    private static RestClientResponseException httpError(HttpStatus status, String body) {
        byte[] content = body == null ? null : body.getBytes(UTF_8);
        String message = status.value() + " " + status.getReasonPhrase() + (body == null ? "" : ": \"" + body + "\"");
        return status.is4xxClientError()
                ? HttpClientErrorException.create(message, status, status.getReasonPhrase(), new HttpHeaders(), content,
                        UTF_8)
                : HttpServerErrorException.create(message, status, status.getReasonPhrase(), new HttpHeaders(), content,
                        UTF_8);
    }

    /** A client failure whose message accessor itself fails. */
    private static final class UnreadableFailure extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            throw new UnsupportedOperationException("message unavailable");
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
