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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

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
 * {@link BlankCredentials} context blanks them to cover the fail-closed check and the requester's failure path.
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

    @LocalServerPort
    int port;

    HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    @Test
    void getLoginReturnsIndexPageWithoutContentType() {
        HttpTestClient.Response response = client().get(LOGIN_PATH);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(List.of(), response.headers(CONTENT_TYPE), response::toString);
        assertNull(response.header(CONTENT_TYPE), response::toString);
        assertArrayEquals(template(INDEX_PAGE), response.body(), response::toString);
    }

    @Test
    void postLoginWithValidCredentialsReturnsSuccessPage() {
        HttpTestClient.Response response = client().send("POST", LOGIN_PATH, Map.of(CONTENT_TYPE, FORM),
                "username=mule&password=mule".getBytes(UTF_8));

        assertSuccessPage(response);
    }

    @Test
    void postLoginWithWrongPasswordReturnsFailurePage() {
        HttpTestClient.Response response = client().post(LOGIN_PATH, FORM, "username=mule&password=wrong");

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
     * The two {@code /login} listeners accept GET and POST only ({@code allowedMethods}, xml:9 and 16); every
     * other method gets the listener's 405.
     *
     * @param method a method neither listener accepts
     */
    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE", "OPTIONS", "PATCH", "TRACE"})
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
        List<String> values = response.headers(CONTENT_TYPE);
        assertEquals(1, values.size(), response::toString);
        MediaType mediaType = MediaType.parseMediaType(values.get(0));
        assertEquals("text", mediaType.getType(), response::toString);
        assertEquals("plain", mediaType.getSubtype(), response::toString);
        assertTrue(mediaType.getCharset() == null || UTF_8.equals(mediaType.getCharset()), response::toString);
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
