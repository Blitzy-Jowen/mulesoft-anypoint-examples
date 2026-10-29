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
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.filter.FormContentFilter;

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
 * {@code allowedMethods="GET"}, also before a large form body has been sent, since the context holds no
 * {@link FormContentFilter} that would read it, the no-listener 404 for paths other than {@code /}, and the JSON 500
 * for an exception no branch handles.
 *
 * <p>The MUnit suite's verify-call and mock assertions are ported separately in {@code ProductionCodeTestSuiteTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExampleControllerTest {

    private static final String ROOT = "/";

    /** A form body large enough that reading it whole before dispatch keeps the response back measurably. */
    private static final int LARGE_FORM_BYTES = 300_000;

    /** Bytes of a large form body written before its response is read; the rest is written only afterwards. */
    private static final int LARGE_FORM_PREFIX_BYTES = 1024;

    @LocalServerPort
    private int port;

    /** The running application's context, inspected for the servlet filters it registers. */
    @Autowired
    private ApplicationContext applicationContext;

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
     * The listener checked the method before it read the body, so a large form body on a method outside
     * {@code allowedMethods} is never read: a form declaring {@value #LARGE_FORM_BYTES} bytes, of which only the first
     * {@value #LARGE_FORM_PREFIX_BYTES} are written, gets the whole 405 before the rest is sent; the drain filter's
     * later read of the rest does not hold it back. Boot's form-content filter, which
     * {@code spring.mvc.formcontent.filter.enabled: false} switches off, would read the whole body into memory before
     * dispatch, so no response would arrive until the last byte had been sent.
     *
     * @param method a method whose form body Boot's form-content filter would read
     * @throws IOException when the connection fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void largeFormOnMethodOutsideAllowedMethodsReturns405BeforeItsBodyIsSent(String method) throws IOException {
        byte[] form = ("url_key=" + "x".repeat(LARGE_FORM_BYTES - "url_key=".length()))
                .getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), HttpTestClient.TIMEOUT_MILLIS);
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            out.write((method + " " + ROOT + " HTTP/1.1\r\nHost: localhost:" + port + "\r\nContent-Type: "
                    + MediaType.APPLICATION_FORM_URLENCODED_VALUE + "\r\nContent-Length: " + form.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(form, 0, LARGE_FORM_PREFIX_BYTES);
            out.flush();

            RawResponse refused;
            try {
                refused = readResponse(in);
            } catch (SocketTimeoutException timeout) {
                refused = fail(String.format("No response to %s %s within %d ms while %d of its %d form bytes were "
                        + "sent: the body was buffered before dispatch", method, ROOT, HttpTestClient.TIMEOUT_MILLIS,
                        LARGE_FORM_PREFIX_BYTES, form.length), timeout);
            }
            writeRemainderIgnoringFailure(out, form, LARGE_FORM_PREFIX_BYTES);

            assertEquals("HTTP/1.1 405 Method Not Allowed", refused.statusLine(), refused::toString);
            assertEquals(List.of(MediaType.TEXT_PLAIN_VALUE), refused.headers("Content-Type"), refused::toString);
            assertArrayEquals("Method not allowed for endpoint: /".getBytes(StandardCharsets.UTF_8), refused.body(),
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
     * Reads one response off the connection: its status line, its header lines and the {@code Content-Length} bytes
     * of its body.
     */
    private static RawResponse readResponse(InputStream in) throws IOException {
        String statusLine = readLine(in);
        List<String> headerLines = new ArrayList<>();
        for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
            headerLines.add(line);
        }
        RawResponse head = new RawResponse(statusLine, headerLines, new byte[0]);
        List<String> contentLength = head.headers("Content-Length");
        assertEquals(1, contentLength.size(), head::toString);
        int length = Integer.parseInt(contentLength.get(0));
        byte[] body = in.readNBytes(length);
        assertEquals(length, body.length, head::toString);
        return new RawResponse(statusLine, headerLines, body);
    }

    /** One line of a response head without its CRLF; fails when the connection ends first. */
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
     * @param statusLine  the status line, such as {@code HTTP/1.1 405 Method Not Allowed}
     * @param headerLines the header lines in arrival order
     * @param body        the body bytes
     */
    private record RawResponse(String statusLine, List<String> headerLines, byte[] body) {

        /** The trimmed values, in arrival order, of the header lines named {@code name}, compared ignoring case. */
        List<String> headers(String name) {
            List<String> values = new ArrayList<>();
            for (String line : headerLines) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                    values.add(line.substring(colon + 1).trim());
                }
            }
            return values;
        }

        @Override
        public String toString() {
            return statusLine + " " + headerLines + " " + new String(body, StandardCharsets.UTF_8);
        }
    }
}
