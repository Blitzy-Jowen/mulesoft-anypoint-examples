/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.munit_short_tutorial.controller;

import static com.mulesoft.examples.munit_short_tutorial.service.ExampleFlowService.PAYLOAD_1;
import static com.mulesoft.examples.munit_short_tutorial.service.ExampleFlowService.RESPONSE_PAYLOAD_1;
import static com.mulesoft.examples.munit_short_tutorial.service.ExampleFlowService.RESPONSE_PAYLOAD_2;
import static com.mulesoft.examples.munit_short_tutorial.service.ExampleFlowService.URL_KEY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

import com.mulesoft.examples.munit_short_tutorial.service.ExampleFlowService;
import com.mulesoft.examples.munit_short_tutorial.support.HttpTestClient;

/**
 * Wire-level test of {@link ExampleController}, the HTTP trigger of flow {@code exampleFlow}
 * (munit-short-tutorial/src/main/app/production-code.xml:6-21), on the application's own Undertow listener.
 *
 * <p>Every request goes through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from
 * the socket, so the HTTP version, status code, reason phrase, {@code Content-Type} header and body bytes are
 * asserted exactly as the listener writes them. The happy paths cover both branches of {@code exampleFlow2}
 * ({@code 'payload_1'.equals(payload)}) with the real {@link ExampleFlowService}; the error cases are the
 * listener-level platform defaults of the exception package: 405 for every method outside the listener's
 * {@code allowedMethods="GET"}, the no-listener 404 for paths other than {@code /}, and the JSON 500 for an
 * exception no branch handles.
 *
 * <p>The MUnit suite's verify-call and mock assertions are ported separately in {@code ProductionCodeTestSuiteTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExampleControllerTest {

    private static final String ROOT = "/";

    @LocalServerPort
    private int port;

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    @Test
    void urlKeyPayload1RoutesToSubFlow1() {
        assertFlowResponse(client().get("/?url_key=payload_1"), RESPONSE_PAYLOAD_1);
    }

    @Test
    void urlKeyPayload2RoutesToSubFlow2() {
        assertFlowResponse(client().get("/?url_key=payload_2"), RESPONSE_PAYLOAD_2);
    }

    /** A missing {@code url_key} makes the payload {@code null}, which {@code exampleFlow2} routes to sub-flow 2. */
    @Test
    void missingUrlKeyRoutesToSubFlow2() {
        assertFlowResponse(client().get(ROOT), RESPONSE_PAYLOAD_2);
    }

    /** Mule 3's query parameter map returned the last value of a repeated name for a single-valued lookup. */
    @Test
    void repeatedUrlKeyUsesItsLastValue() {
        assertFlowResponse(client().get("/?url_key=payload_2&url_key=payload_1"), RESPONSE_PAYLOAD_1);
    }

    /** The query value is percent-decoded before the comparison, as Mule 3 decoded the query string. */
    @Test
    void percentEncodedUrlKeyIsDecodedBeforeRouting() {
        assertFlowResponse(client().get("/?url_key=payload%5F1"), RESPONSE_PAYLOAD_1);
    }

    /**
     * {@code 'payload_1'.equals(payload)} is exact and case-sensitive, and only {@code url_key} is read: an empty
     * value, a value without {@code =}, another letter case and another parameter name all select sub-flow 2.
     *
     * @param target request target sent byte for byte
     */
    @ParameterizedTest
    @ValueSource(strings = {"/?url_key=", "/?url_key", "/?url_key=PAYLOAD_1", "/?other=payload_1"})
    void everyOtherQueryRoutesToSubFlow2(String target) {
        assertFlowResponse(client().get(target), RESPONSE_PAYLOAD_2);
    }

    /** The listener compared the method with {@code allowedMethods} ignoring case, so {@code get} runs the flow. */
    @Test
    void lowerCaseGetRunsTheFlow() {
        assertFlowResponse(client().send("get", "/?url_key=payload_1", null, null), RESPONSE_PAYLOAD_1);
    }

    /**
     * Every method other than {@code GET} is outside the listener's {@code allowedMethods}; methods that carry a
     * body send a small {@code text/plain} one, the others none. No {@code Allow} header is asserted either way.
     *
     * @param method request method
     * @param body   request body, or {@code null} for none
     */
    @ParameterizedTest
    @CsvSource({"POST,x", "PUT,x", "PATCH,x", "DELETE,", "OPTIONS,", "TRACE,"})
    void methodOutsideAllowedMethodsReturns405(String method, String body) {
        HttpTestClient.Response response = body == null
                ? client().send(method, ROOT, null, null)
                : client().send(method, ROOT, Map.of("Content-Type", MediaType.TEXT_PLAIN_VALUE),
                        body.getBytes(StandardCharsets.UTF_8));

        assertStatus(response, 405, "Method Not Allowed");
        assertContentType(response, MediaType.TEXT_PLAIN);
        assertBody(response, "Method not allowed for endpoint: /");
    }

    /** A query does not make another method acceptable, and the 405 body names the path without it. */
    @Test
    void postWithUrlKeyReturns405WithoutRunningTheFlow() {
        HttpTestClient.Response response = client().post("/?url_key=payload_1", MediaType.TEXT_PLAIN_VALUE, "x");

        assertStatus(response, 405, "Method Not Allowed");
        assertContentType(response, MediaType.TEXT_PLAIN);
        assertBody(response, "Method not allowed for endpoint: /");
    }

    /**
     * {@code HEAD} is outside {@code allowedMethods} too, although Spring MVC would otherwise serve it through the
     * {@code GET} handler. A {@code HEAD} response carries no body, which ST-1 does not try to read, so only the
     * status line is asserted.
     */
    @Test
    void headReturns405() {
        assertStatus(client().send("HEAD", ROOT, null, null), 405, "Method Not Allowed");
    }

    /**
     * Only {@code /} is served; every other path gets the no-listener 404, whose body names the path without its
     * query.
     *
     * @param target request target sent byte for byte
     * @param path   path the 404 body must name
     */
    @ParameterizedTest
    @CsvSource({"/unknown,/unknown", "/other/path,/other/path", "/unknown?url_key=payload_1,/unknown"})
    void unservedPathReturnsNoListener404(String target, String path) {
        HttpTestClient.Response response = client().get(target);

        assertStatus(response, 404, "Not Found");
        assertContentType(response, MediaType.TEXT_PLAIN);
        assertBody(response, "No listener for endpoint: " + path);
    }

    /**
     * A failing {@link ExampleFlowService} reaches the generic 500 of the exception package. The mock gives this
     * class its own application context, so its listener runs on its own random port and the outer tests keep the
     * real service.
     */
    @Nested
    class WhenFlowServiceFails {

        @MockBean
        private ExampleFlowService flowService;

        @LocalServerPort
        private int failingPort;

        @BeforeEach
        void stubFailure() {
            when(flowService.exampleFlow(any())).thenThrow(new RuntimeException("boom"));
        }

        @Test
        void flowFailureReturns500JsonMessage() {
            HttpTestClient.Response response = HttpTestClient.localhost(failingPort).get("/?url_key=payload_1");

            assertStatus(response, 500, "Internal Server Error");
            assertContentType(response, MediaType.APPLICATION_JSON);
            assertBody(response, "{ \"message\": \"boom\" }");
            verify(flowService).exampleFlow(Map.of(URL_KEY, PAYLOAD_1));
        }
    }

    /**
     * Asserts the flow's 200 reply. It carries its {@code Content-Length} and no {@code Content-Type}: the flow's
     * {@code set-payload} processors declare no MIME type, and the Mule listener sent no Content-Type for such a
     * payload. The byte-for-byte behaviour of the original governs here over the {@code text/plain;charset=UTF-8}
     * the test plan expected, because the controller deliberately reproduces the original's headers.
     */
    private static void assertFlowResponse(HttpTestClient.Response response, String payload) {
        byte[] expected = payload.getBytes(StandardCharsets.UTF_8);
        assertStatus(response, 200, "OK");
        assertEquals(List.of(), response.headers("Content-Type"), response::toString);
        assertEquals(List.of(String.valueOf(expected.length)), response.headers("Content-Length"),
                response::toString);
        assertArrayEquals(expected, response.body(), response::toString);
    }

    /** Asserts the raw status line: HTTP version, status code and reason phrase as written on the wire. */
    private static void assertStatus(HttpTestClient.Response response, int status, String reason) {
        assertEquals("HTTP/1.1", response.httpVersion(), response::toString);
        assertEquals(status, response.status(), response::toString);
        assertEquals(reason, response.reason(), response::toString);
        assertEquals("HTTP/1.1 " + status + " " + reason, response.statusLine(), response::toString);
    }

    /** Asserts the body bytes exactly, with no trimming or decoding. */
    private static void assertBody(HttpTestClient.Response response, String expected) {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
    }

    /**
     * Asserts a single {@code Content-Type} header of the expected type and subtype; a charset parameter, which
     * Spring's String converter may append to the preset media type, must be UTF-8 when present.
     */
    private static void assertContentType(HttpTestClient.Response response, MediaType expected) {
        List<String> values = response.headers("Content-Type");
        assertEquals(1, values.size(), response::toString);
        MediaType actual = MediaType.parseMediaType(values.get(0));
        assertTrue(actual.equalsTypeAndSubtype(expected), response::toString);
        if (actual.getCharset() != null) {
            assertEquals(StandardCharsets.UTF_8, actual.getCharset(), response::toString);
        }
    }
}
