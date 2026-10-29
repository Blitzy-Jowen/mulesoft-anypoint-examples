/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.login_form_using_the_http_connector.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.filter.FormContentFilter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.login_form_using_the_http_connector.client.LoginRequesterClient;
import com.mulesoft.examples.login_form_using_the_http_connector.service.LoginService;
import com.mulesoft.examples.login_form_using_the_http_connector.support.HttpTestClient;

/**
 * Wire-level test of {@link LoginController}, the HTTP triggers of the three flows of
 * {@code login-form-using-the-http-connector.xml}, on the application's own Undertow listener:
 * {@code GetLoginPageFlow} (GET {@code /login}, xml:9), {@code DoLoginFlow} (POST {@code /login}, xml:16) and
 * {@code CallLoginFlowUsingRequester} ({@code /requesterLogin}, every method, xml:35).
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the
 * socket, so reason phrases, the exact spelling of each {@code Content-Type} header and the body bytes are asserted
 * as the listener writes them. Expected pages are computed from the {@code login/*.html} resources alone, never
 * hand-copied. Profile {@code test} supplies the original demo credentials {@code mule}/{@code mule}; the nested
 * {@link BlankCredentials} context blanks them to cover the fail-closed check, its WARN and the requester's failure
 * path, and the nested {@link SegmentParameters} context spies on the service and the requester to show that a
 * listener path carrying {@code ;} segment parameters runs neither.
 *
 * <p>The original {@code HttpLoginHtmlFormIT} assertions are ported separately in {@code HttpLoginHtmlFormTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LoginControllerTest {

    /** The only expression of {@code loginSuccessful.html} and {@code loginFailure.html} (line 13 of each). */
    static final String USERNAME_EXPRESSION = "#[payload.username]";

    /** The text the original test looked for in the success page ({@code HttpLoginHtmlFormIT.REPLY}). */
    static final String REPLY = "User mule has been logged successfully!";

    static final String FORM = "application/x-www-form-urlencoded";

    private static final String CONTENT_TYPE = "Content-Type";

    private static final String LOGIN_PATH = "/login";

    private static final String REQUESTER_LOGIN_PATH = "/requesterLogin";

    private static final String INDEX_PAGE = "login/index.html";

    private static final String SUCCESS_PAGE = "login/loginSuccessful.html";

    private static final String FAILURE_PAGE = "login/loginFailure.html";

    /** The value of {@code set-property propertyName="Content-Type"} on the success path (xml:25), space included. */
    private static final String HTML_UTF8 = "text/html; charset=UTF-8";

    /**
     * The failure page's {@code Content-Type} for a form request. The catch branch (xml:27-30) sets none, so the
     * original listener derived it from the request's media type and its default charset.
     */
    private static final String FORM_UTF8 = FORM + "; charset=UTF-8";

    /**
     * The catch branch's status line: it sets {@code http.status} 403 without {@code http.reason}, so the original
     * listener wrote the code followed by one space and an empty reason phrase, not Undertow's {@code Forbidden}.
     */
    private static final String FAILURE_STATUS_LINE = "HTTP/1.1 403 ";

    /** A form body large enough that reading it whole before dispatch keeps the response back measurably. */
    private static final int LARGE_FORM_BYTES = 300_000;

    /** Bytes of a large form body written before its response is read; the rest is written only afterwards. */
    private static final int LARGE_FORM_PREFIX_BYTES = 1024;

    @LocalServerPort
    int port;

    /** The running application's context, inspected for the servlet filters it registers. */
    @Autowired
    ApplicationContext applicationContext;

    HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    /**
     * {@code GetLoginPageFlow} for {@code GET} in any letter case: the Mule 3.8 listener compared the request method
     * with {@code allowedMethods="GET"} (xml:9) ignoring case.
     *
     * @param method {@code GET} in some letter case
     */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "get", "Get", "gET"})
    void getLoginReturnsIndexPageWithoutContentType(String method) {
        HttpTestClient.Response response = client().send(method, LOGIN_PATH, null, null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(List.of(), response.headers(CONTENT_TYPE), response::toString);
        assertNull(response.header(CONTENT_TYPE), response::toString);
        assertArrayEquals(template(INDEX_PAGE), response.body(), response::toString);
    }

    /**
     * {@code DoLoginFlow} for {@code POST} in any letter case: the Mule 3.8 listener compared the request method
     * with {@code allowedMethods="POST"} (xml:16) ignoring case.
     *
     * @param method {@code POST} in some letter case
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "post", "Post", "pOST"})
    void postLoginWithValidCredentialsReturnsSuccessPage(String method) {
        HttpTestClient.Response response = client().send(method, LOGIN_PATH, Map.of(CONTENT_TYPE, FORM),
                "username=mule&password=mule".getBytes(UTF_8));

        assertSuccessPage(response);
    }

    /**
     * The catch branch for {@code POST} in any letter case.
     *
     * @param method {@code POST} in some letter case
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "post", "Post", "pOST"})
    void postLoginWithWrongPasswordReturnsFailurePage(String method) {
        HttpTestClient.Response response = client().send(method, LOGIN_PATH, Map.of(CONTENT_TYPE, FORM),
                "username=mule&password=wrong".getBytes(UTF_8));

        assertFailurePage(response, "mule");
        assertTrue(response.bodyAsString().contains("Wrong password for username mule"), response::toString);
    }

    /** The form is the request body alone, as the Mule payload was: credentials in the query string never count. */
    @Test
    void credentialsInQueryStringAreIgnored() {
        HttpTestClient.Response response = client().send("POST", LOGIN_PATH + "?username=mule&password=mule",
                Map.of(CONTENT_TYPE, FORM), new byte[0]);

        assertEquals(403, response.status(), response::toString);
        assertFailurePage(response, "null");
    }

    /**
     * A repeated field takes its last value, as Mule 3.8's {@code ParameterMap#get} returned {@code values.getLast()},
     * so a valid last value logs in whatever came before it.
     *
     * @param form the posted form body, whose last {@code username} and {@code password} are the demo credentials
     */
    @ParameterizedTest
    @ValueSource(strings = {"username=mule&password=wrong&password=mule", "username=bob&username=mule&password=mule"})
    void repeatedFieldWithValidLastValueLogsIn(String form) {
        HttpTestClient.Response response = client().post(LOGIN_PATH, FORM, form);

        assertSuccessPage(response);
    }

    /**
     * A repeated field takes its last value, so an invalid last value is rejected whatever came before it, and the
     * failure page shows the last {@code username}; a last pair without {@code =} is the value {@code null}.
     *
     * @param form             the posted form body
     * @param renderedUsername the username the failure page shows
     */
    @ParameterizedTest
    @CsvSource({"'username=mule&password=mule&password=x', mule", "'username=mule&password=mule&password', mule",
            "'username=alice&username=bob&password=wrong', bob", "'username=mule&username=bob&password=mule', bob"})
    void repeatedFieldWithInvalidLastValueIsRejected(String form, String renderedUsername) {
        HttpTestClient.Response response = client().post(LOGIN_PATH, FORM, form);

        assertFailurePage(response, renderedUsername);
    }

    @Test
    void missingUsernameRendersAsNull() {
        HttpTestClient.Response response = client().post(LOGIN_PATH, FORM, "password=mule");

        assertEquals(403, response.status(), response::toString);
        assertTrue(response.bodyAsString().contains("Wrong password for username null"), response::toString);
        assertFailurePage(response, "null");
    }

    /**
     * A body is a form only under the form media type, so valid credentials posted as {@code text/plain} reach the
     * filter as an empty form and are rejected; the failure page takes the request's media type.
     */
    @Test
    void credentialsOutsideFormMediaTypeAreRejected() {
        HttpTestClient.Response response = client().post(LOGIN_PATH, "text/plain", "username=mule&password=mule");

        assertEquals(FAILURE_STATUS_LINE, response.statusLine(), response::toString);
        assertEquals(List.of("text/plain; charset=UTF-8"), response.headers(CONTENT_TYPE), response::toString);
        assertArrayEquals(failurePage("null"), response.body(), response::toString);
    }

    /** A form body that cannot be URL-decoded gets the listener's 400 before the flow runs. */
    @Test
    void undecodableFormBodyReturnsBadRequest() {
        HttpTestClient.Response response = client().post(LOGIN_PATH, FORM, "username=%ZZ&password=mule");

        assertEquals("HTTP/1.1 400 Bad Request", response.statusLine(), response::toString);
        assertEquals(List.of(), response.headers(CONTENT_TYPE), response::toString);
        assertEquals("Cannot decode x-www-form-urlencoded payload", response.bodyAsString(), response::toString);
    }

    /**
     * Form pairs are not trimmed, as the Mule 3.8 listener did not trim them: literal and percent-encoded or
     * {@code +} whitespace in a name or value reaches the exact credential check and is rejected, whitespace before
     * a name makes it another field, and a whitespace-only body is the empty form. The failure page shows the
     * decoded {@code username}, whitespace included.
     *
     * @param form             the posted form body
     * @param renderedUsername the username the failure page shows
     */
    @ParameterizedTest
    @MethodSource("whitespaceMismatchedForms")
    void whitespaceInFormIsKeptAndRejected(String form, String renderedUsername) {
        HttpTestClient.Response response = client().post(LOGIN_PATH, FORM, form);

        assertFailurePage(response, renderedUsername);
    }

    /** Forms whose untrimmed whitespace fails the demo credentials, each with the username its failure page shows. */
    static Stream<Arguments> whitespaceMismatchedForms() {
        return Stream.of(
                Arguments.of("username=mule&password=mule ", "mule"),
                Arguments.of("username=mule&password= mule", "mule"),
                Arguments.of("username=mule &password=mule", "mule "),
                Arguments.of("username=mule&password=mule\t", "mule"),
                Arguments.of("username=mule&password=mule\r\n", "mule"),
                Arguments.of("username=mule&password=mule%20", "mule"),
                Arguments.of("username=mule&password=mule+", "mule"),
                Arguments.of("username=mule%20&password=mule", "mule "),
                Arguments.of(" username=mule&password=mule", "null"),
                Arguments.of("username=mule& password=mule", "mule"),
                Arguments.of("   ", "null"));
    }

    /**
     * Names and values are still URL-decoded, and the empty pairs that splitting keeps, from a leading or doubled
     * {@code &}, map the name {@code ""} without touching the credentials; a trailing {@code &} and the page's
     * {@code Submit} field are harmless as well.
     *
     * @param form the posted form body, carrying the demo credentials
     */
    @ParameterizedTest
    @ValueSource(strings = {"username=%6Dule&password=mul%65", "username=mule&&password=mule",
            "&username=mule&password=mule", "username=mule&password=mule&",
            "username=mule&password=mule&Submit=Submit"})
    void decodedFormWithEmptyOrExtraPairsLogsIn(String form) {
        HttpTestClient.Response response = client().post(LOGIN_PATH, FORM, form);

        assertSuccessPage(response);
    }

    /**
     * The two {@code /login} listeners accept GET and POST only ({@code allowedMethods}, xml:9 and 16), compared
     * ignoring case; every other method, in any letter case, gets the listener's 405.
     *
     * @param method a method neither listener accepts
     */
    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE", "OPTIONS", "options", "Options", "PATCH", "TRACE"})
    void methodsOutsideAllowedMethodsReturn405(String method) {
        HttpTestClient.Response response = client().send(method, LOGIN_PATH, null, null);

        assertEquals(405, response.status(), response::toString);
        assertEquals("Method Not Allowed", response.reason(), response::toString);
        assertPlainText(response);
        assertEquals("Method not allowed for endpoint: " + LOGIN_PATH, response.bodyAsString(), response::toString);
    }

    /** HEAD gets the same 405; ST-1 reads a HEAD response as bodiless, so only the head is asserted. */
    @Test
    void headOnLoginReturns405() {
        HttpTestClient.Response response = client().send("HEAD", LOGIN_PATH, null, null);

        assertEquals(405, response.status(), response::toString);
        assertEquals("Method Not Allowed", response.reason(), response::toString);
        assertPlainText(response);
    }

    /**
     * {@code HEAD} in another letter case gets the same 405: the method is compared ignoring case, so it is no more
     * allowed than {@code HEAD}. Undertow also treats such a method as {@code HEAD} and sends the head alone, with the
     * {@code Content-Length} of the 405 text, while ST-1 expects a bodiless response only for {@code HEAD} spelled in
     * upper case, so the head is read by {@link #responseHead}.
     *
     * @param method {@code HEAD} in a letter case other than upper case
     */
    @ParameterizedTest
    @ValueSource(strings = {"head", "Head"})
    void headInOtherLetterCaseOnLoginReturns405(String method) {
        List<String> head = responseHead(method, LOGIN_PATH);

        assertEquals("HTTP/1.1 405 Method Not Allowed", head.get(0), head::toString);
        assertPlainText(headerValues(head, CONTENT_TYPE), head::toString);
        assertEquals(List.of(String.valueOf(("Method not allowed for endpoint: " + LOGIN_PATH).length())),
                headerValues(head, "Content-Length"), head::toString);
    }

    /**
     * The {@code /login} listeners checked the method before they read the body, so a large form body on a method
     * neither accepts is never read: a form declaring {@value #LARGE_FORM_BYTES} bytes, of which only the first
     * {@value #LARGE_FORM_PREFIX_BYTES} are written, gets SC-1's whole 405 before the rest is sent. Boot's
     * form-content filter, which {@code spring.mvc.formcontent.filter.enabled: false} switches off, would read the
     * whole body into memory before dispatch, so no response would arrive until the last byte had been sent.
     *
     * @param method a method whose form body Boot's form-content filter would read
     * @throws IOException when the connection fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void largeFormOnMethodOutsideAllowedMethodsReturns405BeforeItsBodyIsSent(String method) throws IOException {
        byte[] form = ("username=" + "x".repeat(LARGE_FORM_BYTES - "username=".length())).getBytes(UTF_8);
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + LOGIN_PATH + " HTTP/1.1\r\nHost: localhost:" + port + "\r\n" + CONTENT_TYPE + ": "
                    + FORM + "\r\nContent-Length: " + form.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(form, 0, LARGE_FORM_PREFIX_BYTES);
            out.flush();

            RawResponse refused;
            try {
                refused = readResponse(in);
            } catch (SocketTimeoutException timeout) {
                refused = fail(String.format("No response to %s %s within %d ms while %d of its %d form bytes were "
                        + "sent: the body was buffered before dispatch", method, LOGIN_PATH,
                        HttpTestClient.TIMEOUT_MILLIS, LARGE_FORM_PREFIX_BYTES, form.length), timeout);
            }
            writeRemainderIgnoringFailure(out, form, LARGE_FORM_PREFIX_BYTES);

            assertEquals("HTTP/1.1 405 Method Not Allowed", refused.head().get(0), refused::toString);
            assertEquals(List.of(MediaType.TEXT_PLAIN_VALUE), headerValues(refused.head(), CONTENT_TYPE),
                    refused::toString);
            assertArrayEquals(("Method not allowed for endpoint: " + LOGIN_PATH).getBytes(UTF_8), refused.body(),
                    refused::toString);
        }
    }

    /**
     * The application registers no {@link FormContentFilter}, the filter that reads a {@code PUT}, {@code PATCH} or
     * {@code DELETE} form body before dispatch, because {@code spring.mvc.formcontent.filter.enabled} is
     * {@code false}.
     */
    @Test
    void contextHoldsNoFormContentFilter() {
        assertArrayEquals(new String[0], applicationContext.getBeanNamesForType(FormContentFilter.class),
                "FormContentFilter beans");
    }

    @Test
    void unknownPathReturnsNoListener404() {
        HttpTestClient.Response response = client().get("/nope");

        assertEquals(404, response.status(), response::toString);
        assertEquals("Not Found", response.reason(), response::toString);
        assertPlainText(response);
        assertEquals("No listener for endpoint: /nope", response.bodyAsString(), response::toString);
    }

    /**
     * The requester listener declares no {@code allowedMethods} (xml:35), so every method runs the flow, which
     * ignores the inbound body and relays the response of {@code POST /login} with the configured credentials.
     *
     * @param method the request method; POST is sent with an empty body
     */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"})
    void requesterLoginRelaysSuccessPageForEveryMethod(String method) {
        byte[] body = "POST".equals(method) ? new byte[0] : null;

        HttpTestClient.Response response = client().send(method, REQUESTER_LOGIN_PATH, null, body);

        assertSuccessPage(response);
    }

    /**
     * A path carrying {@code ;} segment parameters is neither listener's path. {@link LoginService} and
     * {@link LoginRequesterClient} are spies, so a flow run or an outbound request would be recorded; the spies give
     * this class its own context and random port.
     */
    @Nested
    class SegmentParameters {

        /** The real service, recording any flow a request ran; reset after every test. */
        @SpyBean
        LoginService loginService;

        /** The real requester, recording any outbound {@code POST /login}; reset after every test. */
        @SpyBean
        LoginRequesterClient requesterClient;

        /** The random port of this context's listener. */
        @LocalServerPort
        int segmentParametersPort;

        /**
         * The Mule listener compared the raw path, query removed, literally, so every method gets the no-listener
         * 404 naming the path with its parameters, and neither a {@code /login} flow nor the requester runs
         * ({@code config/ListenerPathConfig}). ST-1 reads a {@code HEAD} response as bodiless, so for {@code HEAD}
         * the {@code Content-Length} of the 404 text is asserted in place of the body.
         *
         * @param method the request method; {@code POST} carries the demo credentials as a form body
         * @param path   {@code /login} or {@code /requesterLogin} followed by its segment parameters
         */
        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("segmentParameterRequests")
        void pathWithSegmentParametersReturnsNoListener404(String method, String path) {
            boolean withBody = "POST".equals(method);
            HttpTestClient.Response response = HttpTestClient.localhost(segmentParametersPort).send(method, path,
                    withBody ? Map.of(CONTENT_TYPE, FORM) : null,
                    withBody ? "username=mule&password=mule".getBytes(UTF_8) : null);

            byte[] noListener = ("No listener for endpoint: " + path).getBytes(UTF_8);
            assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
            assertEquals(List.of(MediaType.TEXT_PLAIN_VALUE), response.headers(CONTENT_TYPE), response::toString);
            if ("HEAD".equals(method)) {
                assertEquals(List.of(String.valueOf(noListener.length)), response.headers("Content-Length"),
                        response::toString);
                assertArrayEquals(new byte[0], response.body(), response::toString);
            } else {
                assertArrayEquals(noListener, response.body(), response::toString);
            }
            verifyNoInteractions(loginService);
            verify(requesterClient, never()).postLogin(anyMap());
        }

        /**
         * @return each method with each listener path followed by {@code ;x=1}, {@code ;jsessionid=ABC} or {@code ;}
         */
        static Stream<Arguments> segmentParameterRequests() {
            return Stream.of("GET", "POST", "PUT", "HEAD", "OPTIONS").flatMap(method ->
                    Stream.of(LOGIN_PATH, REQUESTER_LOGIN_PATH).flatMap(path ->
                            Stream.of(path + ";x=1", path + ";jsessionid=ABC", path + ";")
                                    .map(aliased -> Arguments.of(method, aliased))));
        }
    }

    /**
     * Configured credentials blanked over the {@code test} profile, as with the unset placeholders of
     * {@code application.yml}. The extra property source gives this class its own context and random port.
     */
    @Nested
    @TestPropertySource(properties = {"login.username=", "login.password="})
    class BlankCredentials {

        @LocalServerPort
        int nestedPort;

        HttpTestClient nestedClient() {
            return HttpTestClient.localhost(nestedPort);
        }

        /**
         * The credential check fails closed: a blank configuration rejects every form, blank input included.
         *
         * @param form             the posted form body
         * @param renderedUsername the username the failure page shows
         */
        @ParameterizedTest
        @CsvSource({"'username=&password=', ''", "'username=mule&password=mule', mule"})
        void everyFormIsRejected(String form, String renderedUsername) {
            HttpTestClient.Response response = nestedClient().post(LOGIN_PATH, FORM, form);

            assertEquals(403, response.status(), response::toString);
            assertFailurePage(response, renderedUsername);
        }

        /**
         * A login checked against the blank configuration logs the original INFO line, then one WARN naming both
         * blank keys and no value. The appender is read under its own lock, which every append on the listener's
         * worker thread takes before the response is written.
         */
        @Test
        void rejectionLogsOneWarningNamingTheBlankKeys() {
            Logger serviceLogger = (Logger) LoggerFactory.getLogger(LoginService.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            serviceLogger.addAppender(appender);
            HttpTestClient.Response response;
            try {
                response = nestedClient().post(LOGIN_PATH, FORM, "username=mule&password=mule");
            } finally {
                serviceLogger.detachAppender(appender);
                appender.stop();
            }
            List<String> lines;
            synchronized (appender) {
                lines = appender.list.stream().map(event -> event.getLevel() + " " + event.getFormattedMessage())
                        .toList();
            }

            assertFailurePage(response, "mule");
            assertEquals(List.of("INFO User mule is trying to login",
                    "WARN Login rejected because login.username and login.password are not configured"), lines);
        }

        /** The requester's own call is rejected with 403, which it maps as a failure for the generic 500. */
        @Test
        void requesterLoginFailureReturns500Json() {
            HttpTestClient.Response response = nestedClient().get(REQUESTER_LOGIN_PATH);

            assertEquals(500, response.status(), response::toString);
            assertEquals("Internal Server Error", response.reason(), response::toString);
            assertEquals(List.of(MediaType.APPLICATION_JSON_VALUE), response.headers(CONTENT_TYPE),
                    response::toString);
            assertEquals("{ \"message\": \"Response code 403 mapped as failure.\" }", response.bodyAsString(),
                    response::toString);
        }
    }

    private static void assertSuccessPage(HttpTestClient.Response response) {
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(List.of(HTML_UTF8), response.headers(CONTENT_TYPE), response::toString);
        assertArrayEquals(templateText(SUCCESS_PAGE).replace(USERNAME_EXPRESSION, "mule").getBytes(UTF_8),
                response.body(), response::toString);
        assertTrue(response.bodyAsString().contains(REPLY), response::toString);
    }

    /**
     * The catch branch's response to a form request, exactly as the original listener sent it: the empty reason
     * phrase and the request-derived {@code Content-Type} described at {@link #FAILURE_STATUS_LINE} and
     * {@link #FORM_UTF8}, and {@code loginFailure.html} showing {@code username}.
     */
    private static void assertFailurePage(HttpTestClient.Response response, String username) {
        assertEquals(FAILURE_STATUS_LINE, response.statusLine(), response::toString);
        assertEquals("", response.reason(), response::toString);
        assertEquals(List.of(FORM_UTF8), response.headers(CONTENT_TYPE), response::toString);
        assertArrayEquals(failurePage(username), response.body(), response::toString);
    }

    /**
     * Exactly one {@code Content-Type} whose media type is {@code text/plain}; a charset parameter, when present,
     * is UTF-8.
     */
    private static void assertPlainText(HttpTestClient.Response response) {
        assertPlainText(response.headers(CONTENT_TYPE), response::toString);
    }

    /** {@link #assertPlainText(HttpTestClient.Response)} over the {@code Content-Type} values of a response. */
    private static void assertPlainText(List<String> values, Supplier<String> response) {
        assertEquals(1, values.size(), response);
        MediaType mediaType = MediaType.parseMediaType(values.get(0));
        assertEquals("text", mediaType.getType(), response);
        assertEquals("plain", mediaType.getSubtype(), response);
        assertTrue(mediaType.getCharset() == null || UTF_8.equals(mediaType.getCharset()), response);
    }

    /**
     * Sends {@code method path HTTP/1.1} with no body on a new connection and returns the response head: the status
     * line, then each header line, up to the empty line that ends the head. Nothing after the head is read.
     */
    private List<String> responseHead(String method, String path) {
        String request = method + " " + path + " HTTP/1.1\r\nHost: localhost:" + port + "\r\nConnection: close\r\n\r\n";
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            List<String> lines = new ArrayList<>();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int b = in.read(); b != -1; b = in.read()) {
                if (b != '\n') {
                    line.write(b);
                    continue;
                }
                String text = line.toString(StandardCharsets.ISO_8859_1);
                text = text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
                if (text.isEmpty()) {
                    return lines;
                }
                lines.add(text);
                line.reset();
            }
            throw new UncheckedIOException(new EOFException("connection closed inside the response head " + lines));
        } catch (IOException e) {
            throw new UncheckedIOException(method + " " + path + " to localhost:" + port + " failed", e);
        }
    }

    /** The trimmed values of the header lines of {@code head} named {@code name}, compared ignoring case. */
    private static List<String> headerValues(List<String> head, String name) {
        return head.stream().skip(1)
                .filter(line -> line.regionMatches(true, 0, name + ":", 0, name.length() + 1))
                .map(line -> line.substring(name.length() + 1).trim())
                .toList();
    }

    /**
     * Writes the bytes of {@code body} from {@code offset} to its end once the response has been read, so the server
     * can finish the exchange; a failed write is ignored, because the server may close a connection whose body it
     * refused unread.
     */
    private static void writeRemainderIgnoringFailure(OutputStream out, byte[] body, int offset) {
        try {
            out.write(body, offset, body.length - offset);
            out.flush();
        } catch (IOException closedByServer) {
            // The response is already read; the server need not take the rest of a body it never reads.
        }
    }

    /**
     * Reads one response off the connection: its head, the status line followed by each header line, and the
     * {@code Content-Length} bytes of its body.
     */
    private static RawResponse readResponse(InputStream in) throws IOException {
        List<String> head = new ArrayList<>();
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            head.add(line);
        }
        List<String> contentLength = headerValues(head, "Content-Length");
        assertEquals(1, contentLength.size(), head::toString);
        int length = Integer.parseInt(contentLength.get(0));
        byte[] body = in.readNBytes(length);
        assertEquals(length, body.length, head::toString);
        return new RawResponse(head, body);
    }

    /** One line of a response head without its CRLF, one character per byte; fails when the connection ends first. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c = in.read(); c != '\n'; c = in.read()) {
            if (c < 0) {
                throw new EOFException("connection closed in a response head after \"" + line + "\"");
            }
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }

    /**
     * A response read off a raw connection.
     *
     * @param head the status line followed by the header lines, in arrival order
     * @param body the body bytes
     */
    private record RawResponse(List<String> head, byte[] body) {

        @Override
        public String toString() {
            return head + " " + new String(body, UTF_8);
        }
    }

    private static byte[] failurePage(String username) {
        return templateText(FAILURE_PAGE).replace(USERNAME_EXPRESSION, username).getBytes(UTF_8);
    }

    static byte[] template(String location) {
        try (InputStream in = LoginControllerTest.class.getClassLoader().getResourceAsStream(location)) {
            assertNotNull(in, () -> location + " not found on the test classpath");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + location, e);
        }
    }

    static String templateText(String location) {
        return new String(template(location), UTF_8);
    }
}
