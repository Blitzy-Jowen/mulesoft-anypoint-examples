/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.content_based_routing.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.mulesoft.examples.content_based_routing.service.LanguageRoutingService;
import com.mulesoft.examples.content_based_routing.support.HttpTestClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

/**
 * Wire-level test of {@link GreetingController}, the HTTP trigger of flow {@code content-based-routingFlow}
 * (content-based-routing/src/main/app/content-based-routing.xml:4-21), on the application's own Undertow listener.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which writes the request target byte for byte
 * and reads the raw status line from the socket, so the reason phrase, the {@code Content-Type} and
 * {@code Content-Length} headers and the body bytes are asserted exactly as the listener writes them. The service
 * behind the controller is the real {@link LanguageRoutingService}, so each case exercises the whole flow:
 * <ul>
 *   <li>the routing choice on the {@code language} query parameter, with every method, because the original
 *       listener declares no {@code allowedMethods};</li>
 *   <li>the query-parameter semantics of the Mule 3.8 listener: the last {@code language} pair wins, a bare
 *       {@code language} is {@code null}, names and values are URL-decoded, and a form field never routes;</li>
 *   <li>the favicon {@code expression-filter}, which compares the payload, so only a text body equal to
 *       {@code /favicon.ico} is rejected, with the empty 200 of a filter without {@code throwOnUnaccepted};</li>
 *   <li>the listener-level errors: the no-listener 404 of SC-1 for every path other than {@code /}, the listener's
 *       400 for a form body it cannot decode, and the JSON 500 of SC-1 for an exception no branch handles.</li>
 * </ul>
 * Expected replies are literal strings rather than the service's constants, so the wire bytes are checked
 * independently of the code that produces them.
 *
 * <p>The original {@code ContentBasedRoutingIT} assertions are ported separately in {@code ContentBasedRoutingTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GreetingControllerTest {

    private static final String FRENCH_TARGET = "/?language=French";

    private static final String FAVICON_PATH = "/favicon.ico";

    /** A well-formed multipart body, boundary {@code b}, whose only part holds the favicon path. */
    private static final String MULTIPART_FAVICON_BODY = "--b\r\n"
            + "Content-Disposition: form-data; name=\"icon\"\r\n"
            + "\r\n"
            + FAVICON_PATH + "\r\n"
            + "--b--\r\n";

    @LocalServerPort
    private int port;

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    @Test
    void spanishLanguageRepliesHola() {
        assertReply(client().get("/?language=Spanish"), "Hola!");
    }

    @Test
    void frenchLanguageRepliesBonjour() {
        assertReply(client().get(FRENCH_TARGET), "Bonjour!");
    }

    /**
     * The choice compares exactly and case-sensitively, so a missing, empty, bare, unknown or differently cased
     * {@code language} takes sub-flow {@code replyInDefaultLanguage}.
     *
     * @param target the request target, query string included
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/",
            "/?",
            "/?language=",
            "/?language",
            "/?language=German",
            "/?language=English",
            "/?language=spanish",
            "/?language=FRENCH",
            "/?lang=Spanish",
            "/?Language=Spanish"})
    void anyOtherLanguageRepliesInTheDefaultLanguage(String target) {
        assertReply(client().get(target), "Hello!");
    }

    /**
     * The listener declares no {@code allowedMethods}, so every method runs the flow; {@code OPTIONS} and
     * {@code TRACE} included, which Spring MVC would otherwise answer itself.
     *
     * @param method the request method, sent without a body
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE"})
    void everyMethodWithoutBodyIsRouted(String method) {
        assertReply(client().send(method, FRENCH_TARGET, Map.of(), null), "Bonjour!");
    }

    /**
     * A text body other than the favicon path is the payload but plays no part in the routing.
     *
     * @param method the request method, sent with the same text body each time
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "OPTIONS"})
    void everyMethodWithTextBodyIsRouted(String method) {
        HttpTestClient.Response response = client().send(method, FRENCH_TARGET,
                Map.of("Content-Type", MediaType.TEXT_PLAIN_VALUE), "ping".getBytes(StandardCharsets.UTF_8));

        assertReply(response, "Bonjour!");
    }

    @Test
    void headIsRoutedAndAnsweredWithoutBody() {
        HttpTestClient.Response response = client().send("HEAD", FRENCH_TARGET, Map.of(), null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(List.of("8"), response.headers("Content-Length"), response::toString);
        assertArrayEquals(new byte[0], response.body(), response::toString);
    }

    /** The reply is written without content negotiation, so no {@code Accept} value can turn it into a 406. */
    @Test
    void acceptHeaderIsIgnored() {
        assertReply(client().get("/?language=Spanish", Map.of("Accept", MediaType.APPLICATION_JSON_VALUE)), "Hola!");
    }

    /**
     * {@code language} is read from the raw query string as the Mule 3.8 listener filled
     * {@code http.query.params}: pairs split on {@code &} and on their first {@code =}, empty pairs skipped, name and
     * value URL-decoded as UTF-8, and the last {@code language} pair winning, a bare one giving {@code null}.
     *
     * @param target   the request target, sent byte for byte
     * @param expected the reply of the branch the decoded {@code language} selects
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/?language=French&language=Spanish,  Hola!",
            "/?language=Spanish&language=French,  Bonjour!",
            "/?language=Spanish&language,         Hello!",
            "/?language=Spanish&language=,        Hello!",
            "/?language=French&lang=Spanish,      Bonjour!",
            "/?other=1&&language=French&,         Bonjour!",
            "/?language=Spa%6Eish,                Hola!",
            "/?lang%75age=French,                 Bonjour!",
            "/?language=Spanish%20,               Hello!",
            "/?language=Spanish+,                 Hello!",
            "/?language=French=,                  Hello!"})
    void languageIsDecodedFromTheRawQueryStringAndTheLastPairWins(String target, String expected) {
        assertReply(client().send("GET", target, Map.of(), null), expected);
    }

    /**
     * Only the query string routes: a form field named {@code language} becomes part of the payload map, as the
     * Mule 3.8 listener built it, and is never read as the flow variable.
     *
     * @param target   the request target
     * @param expected the reply of the branch the query string alone selects
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/,                  Hello!",
            "/?language=French,  Bonjour!"})
    void formFieldNeverSelectsTheLanguage(String target, String expected) {
        HttpTestClient.Response response = client().post(target, MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                "language=Spanish");

        assertReply(response, expected);
    }

    /**
     * The favicon filter compares the payload with the string {@code /favicon.ico}. A text body with that content,
     * in whatever charset it declares, is rejected with the empty 200 of a filter without
     * {@code throwOnUnaccepted} (AAP 0.4.7); the Content-Type of that empty reply is a Tier 2A verification point and
     * is not asserted. The same bytes as a form body are a parameter map and as a multipart body a null payload, so
     * those pass the filter and reach the choice, as do texts that only resemble the path.
     *
     * @param contentType the {@code Content-Type} sent, or {@code null} for none
     * @param body        the request body
     * @param expected    the routed reply, or {@code null} when the filter rejects the payload
     */
    @ParameterizedTest(name = "[{index}] Content-Type {0}")
    @MethodSource("faviconPayloads")
    void faviconFilterRejectsOnlyTheFaviconText(String contentType, byte[] body, String expected) {
        Map<String, String> headers = contentType == null ? Map.of() : Map.of("Content-Type", contentType);

        HttpTestClient.Response response = client().send("POST", FRENCH_TARGET, headers, body);

        if (expected == null) {
            assertFilterRejection(response);
        } else {
            assertReply(response, expected);
        }
    }

    private static Stream<Arguments> faviconPayloads() {
        byte[] favicon = FAVICON_PATH.getBytes(StandardCharsets.UTF_8);
        return Stream.of(
                Arguments.of(MediaType.TEXT_PLAIN_VALUE, favicon, null),
                Arguments.of(null, favicon, null),
                Arguments.of(MediaType.APPLICATION_JSON_VALUE, favicon, null),
                Arguments.of("text/plain; charset=UTF-16BE", FAVICON_PATH.getBytes(StandardCharsets.UTF_16BE), null),
                Arguments.of(MediaType.APPLICATION_FORM_URLENCODED_VALUE, favicon, "Bonjour!"),
                Arguments.of("multipart/form-data; boundary=b", MULTIPART_FAVICON_BODY.getBytes(StandardCharsets.UTF_8),
                        "Bonjour!"),
                Arguments.of(MediaType.TEXT_PLAIN_VALUE, "/favicon.ico\n".getBytes(StandardCharsets.UTF_8),
                        "Bonjour!"),
                Arguments.of(MediaType.TEXT_PLAIN_VALUE, "/FAVICON.ICO".getBytes(StandardCharsets.UTF_8),
                        "Bonjour!"));
    }

    /** The filter runs before the choice, so a rejected payload gets the empty 200 whatever the language. */
    @Test
    void faviconTextIsRejectedBeforeTheLanguageIsRouted() {
        HttpTestClient.Response response = client().post("/?language=Spanish", MediaType.TEXT_PLAIN_VALUE,
                FAVICON_PATH);

        assertFilterRejection(response);
    }

    /**
     * The listener's path is exactly {@code /}: the icon path browsers request, and any other path, query string or
     * not, gets the no-listener 404 of SC-1 rather than the favicon filter.
     *
     * @param target the request target
     * @param path   the path the 404 body reports, without the query string
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "/favicon.ico,                   /favicon.ico",
            "/favicon.ico?language=Spanish,  /favicon.ico",
            "/unknown,                       /unknown",
            "/language/Spanish,              /language/Spanish"})
    void otherPathsReturnNoListener404(String target, String path) {
        assertNoListener(client().get(target), path);
    }

    @Test
    void otherPathWithBodyReturnsNoListener404() {
        assertNoListener(client().post("/unknown", MediaType.TEXT_PLAIN_VALUE, "ping"), "/unknown");
    }

    /**
     * A form-urlencoded body with a malformed percent escape fails in the listener before the flow runs: the Mule
     * 3.8 listener answered {@code BAD_REQUEST} with its decoding message and no {@code Content-Type}.
     *
     * @param method the request method, sent with the same malformed form body each time
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void undecodableFormBodyReturnsListener400(String method) {
        HttpTestClient.Response response = client().send(method, "/?language=Spanish",
                Map.of("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE),
                "language=Spanish&b=%ZZ".getBytes(StandardCharsets.UTF_8));

        byte[] expected = "Cannot decode x-www-form-urlencoded payload".getBytes(StandardCharsets.UTF_8);
        assertEquals("HTTP/1.1 400 Bad Request", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(List.of(String.valueOf(expected.length)), response.headers("Content-Length"),
                response::toString);
        assertArrayEquals(expected, response.body(), response::toString);
    }

    /** A {@code Content-Type} that cannot be parsed fails the payload binding, which reaches the SC-1 500. */
    @Test
    void unparseableContentTypeReturns500JsonMessage() {
        HttpTestClient.Response response = client().send("POST", "/?language=Spanish",
                Map.of("Content-Type", "bogus"), "ping".getBytes(StandardCharsets.UTF_8));

        assertServerError(response, "{ \"message\": \"Invalid mime type \\\"bogus\\\": does not contain '/'\" }");
    }

    /**
     * An exception the flow does not handle reaches the SC-1 catch-all. The mock gives this class its own application
     * context, so its listener runs on its own random port; the enclosing class's port serves the real service.
     */
    @Nested
    class WhenRoutingFails {

        @MockBean
        private LanguageRoutingService languageRoutingService;

        @LocalServerPort
        private int mockedPort;

        @BeforeEach
        void stubFailure() {
            when(languageRoutingService.reply(any(), any())).thenThrow(new RuntimeException("boom"));
        }

        private HttpTestClient mockedClient() {
            return HttpTestClient.localhost(mockedPort);
        }

        @Test
        void routingFailureReturns500JsonMessage() {
            HttpTestClient.Response response = mockedClient().get("/?language=Spanish");

            assertServerError(response, "{ \"message\": \"boom\" }");
            // A body-less request reaches the flow with the original's NullPayload.
            verify(languageRoutingService).reply(null, "Spanish");
        }

        @Test
        void routingFailureOfATextBodyReturns500JsonMessage() {
            HttpTestClient.Response response = mockedClient().put(FRENCH_TARGET, MediaType.TEXT_PLAIN_VALUE, "ping");

            assertServerError(response, "{ \"message\": \"boom\" }");
            verify(languageRoutingService).reply("ping", "French");
        }

        @Test
        void routingFailureOfAFormBodyReturns500JsonMessage() {
            HttpTestClient.Response response = mockedClient().post("/", MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                    "language=Spanish");

            assertServerError(response, "{ \"message\": \"boom\" }");
            // The form body is the payload map; the query string holds no language.
            verify(languageRoutingService).reply(Map.of("language", "Spanish"), null);
        }
    }

    /**
     * The original's {@code set-payload} processors declare no MIME type, so its listener sent no
     * {@code Content-Type}: each reply carries none, only the length of its UTF-8 bytes.
     */
    private static void assertReply(HttpTestClient.Response response, String expected) {
        byte[] expectedBody = expected.getBytes(StandardCharsets.UTF_8);
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(List.of(String.valueOf(expectedBody.length)), response.headers("Content-Length"),
                response::toString);
        assertArrayEquals(expectedBody, response.body(), response::toString);
    }

    /** The filter's empty 200: no body bytes, announced by {@code Content-Length: 0}. */
    private static void assertFilterRejection(HttpTestClient.Response response) {
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(List.of("0"), response.headers("Content-Length"), response::toString);
        assertArrayEquals(new byte[0], response.body(), response::toString);
    }

    private static void assertNoListener(HttpTestClient.Response response, String path) {
        assertEquals(404, response.status(), response::toString);
        assertEquals("Not Found", response.reason(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        assertArrayEquals(("No listener for endpoint: " + path).getBytes(StandardCharsets.UTF_8), response.body(),
                response::toString);
    }

    private static void assertServerError(HttpTestClient.Response response, String expectedJson) {
        assertEquals(500, response.status(), response::toString);
        assertEquals("Internal Server Error", response.reason(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertArrayEquals(expectedJson.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
    }

    /**
     * Compares the whole value: SC-1 presets the bare media type on the response entity, so the listener writes
     * exactly one {@code Content-Type} header carrying that value and no charset parameter.
     */
    private static void assertContentType(HttpTestClient.Response response, String expected) {
        assertEquals(List.of(expected), response.headers("Content-Type"), response::toString);
    }
}

