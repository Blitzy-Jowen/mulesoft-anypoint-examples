/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.support.HttpTestClient;

/**
 * Wire-level test of {@link CurrencyController}, the RAML resource {@code /currencies} of {@code api/currency.raml}
 * served on the original {@code /api/*} listener of
 * {@code processing-orders-with-dataweave-and-APIkit/src/main/app/currency.xml} (l.33), whose flow
 * {@code get:/currencies:currency-config} is the single processor {@code parse-template location="currency.json"}
 * (l.37-39).
 *
 * <p>The whole application runs on Undertow on a random port, with the real controller, service and template
 * renderer, and every request goes through ST-1 {@link HttpTestClient}, so the raw status line (reason phrase
 * included), {@code Content-Type} and the body bytes are asserted as the listener writes them:
 * <ul>
 *   <li>GET answers 200 {@code application/json} with {@code currency.json} byte for byte, the template holding no
 *       {@code #[...]} expression to substitute;</li>
 *   <li>a method the RAML does not declare, an {@code Accept} header excluding JSON and a resource the RAML does not
 *       declare under {@code /api} get APIkit's 405, 406 and 404 mappings with their literal bodies
 *       (currency.xml l.5-31);</li>
 *   <li>a path outside {@code /api}, which the original listener never served, gets the listener's no-listener
 *       404.</li>
 * </ul>
 *
 * <p>The class only reads. The context it starts also runs the scheduled {@code OrderFileTrigger}, whose
 * directories the surefire configuration points under {@code target/test-orders/}; this class leaves them alone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CurrencyControllerTest {

    private static final String CURRENCIES_PATH = "/api/currencies";

    /** Classpath location of the template, as the original {@code parse-template location} names it. */
    private static final String TEMPLATE_LOCATION = "currency.json";

    /** Size of the original {@code processing-orders-with-dataweave-and-APIkit/src/main/resources/currency.json}. */
    private static final int ORIGINAL_TEMPLATE_LENGTH = 134;

    /** SHA-256 of the original {@code currency.json}, so a changed copy cannot pass as the expected bytes. */
    private static final String ORIGINAL_TEMPLATE_SHA256 =
            "e3e8706144f2b8caf08300a2a80354a2d74cbc6b892a09b86032fc088d7a71cd";

    /** Body of APIkit's 404 mapping (currency.xml l.9), spacing included. */
    private static final String RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    /** Body of APIkit's 405 mapping (currency.xml l.14), spacing included. */
    private static final String METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    /** Body of APIkit's 406 mapping (currency.xml l.24), spacing included. */
    private static final String NOT_ACCEPTABLE = "{ \"message\": \"Not acceptable\" }";

    /** A zero-length request body, sent with {@code Content-Length: 0}. */
    private static final byte[] EMPTY_BODY = new byte[0];

    /** The bytes of the classpath {@code currency.json}, the main resource the application renders. */
    private static byte[] template;

    @LocalServerPort
    private int port;

    private HttpTestClient client;

    @BeforeAll
    static void loadTemplate() throws IOException {
        try (InputStream in = new ClassPathResource(TEMPLATE_LOCATION).getInputStream()) {
            template = in.readAllBytes();
        }
    }

    @BeforeEach
    void createClient() {
        client = HttpTestClient.localhost(port);
    }

    /**
     * The classpath {@code currency.json} the 200 responses are compared with is the original file unchanged: same
     * length and digest, the EUR entry exactly as written, and no trailing newline after the closing brace.
     */
    @Test
    void classpathTemplateIsTheOriginalCurrencyJson() throws NoSuchAlgorithmException {
        assertEquals(ORIGINAL_TEMPLATE_LENGTH, template.length);
        assertEquals(ORIGINAL_TEMPLATE_SHA256,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(template)));
        String text = new String(template, StandardCharsets.UTF_8);
        assertTrue(text.contains("{\"currency\": \"EUR\", \"ratio\":0.92}"), text);
        assertEquals('}', template[template.length - 1], text);
    }

    /**
     * GET without an {@code Accept} header, and with the {@code application/json} the RAML declares, answers 200
     * with the bare {@code application/json} type the controller sets and the template bytes unchanged.
     */
    @ParameterizedTest(name = "GET " + CURRENCIES_PATH + " with Accept {0} -> 200 currency.json")
    @NullSource
    @ValueSource(strings = MediaType.APPLICATION_JSON_VALUE)
    void getCurrenciesReturnsTemplateBytes(String accept) {
        Map<String, String> headers = accept == null ? null : Map.of("Accept", accept);

        HttpTestClient.Response response = client.send("GET", CURRENCIES_PATH, headers, null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertArrayEquals(template, response.body(), response::toString);
    }

    /** Each method the RAML does not declare on {@code /currencies}, with the request body it is sent with. */
    private static Stream<Arguments> undeclaredMethods() {
        return Stream.of(
                Arguments.of("POST", EMPTY_BODY),
                Arguments.of("PUT", EMPTY_BODY),
                Arguments.of("DELETE", null),
                Arguments.of("OPTIONS", null));
    }

    /**
     * The RAML declares only GET, so every other method reaches APIkit's 405 mapping; OPTIONS through the
     * controller's explicit rejecting handler, which keeps Spring from answering it with 200 and {@code Allow}.
     */
    @ParameterizedTest(name = "{0} " + CURRENCIES_PATH + " -> APIkit 405")
    @MethodSource("undeclaredMethods")
    void undeclaredMethodIsMethodNotAllowed(String method, byte[] body) {
        HttpTestClient.Response response = client.send(method, CURRENCIES_PATH, null, body);

        assertApiKitError(response, 405, "Method Not Allowed", METHOD_NOT_ALLOWED);
    }

    /**
     * HEAD is not declared either: the controller keeps Spring's implicit HEAD-for-GET from serving the template,
     * so APIkit's 405 mapping answers, its {@code application/json} type sent without a body. HEAD goes through
     * ST-1 like every other request: the client reads no body for a HEAD response, so the exchange ends at the
     * empty line after the headers instead of waiting for the {@code Content-Length} bytes.
     */
    @Test
    void headCurrenciesIsMethodNotAllowed() {
        HttpTestClient.Response response = client.send("HEAD", CURRENCIES_PATH, null, null);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals(0, response.body().length, response::toString);
    }

    /** The RAML declares only {@code application/json} for the 200, so {@code Accept: text/xml} gets 406. */
    @Test
    void getCurrenciesWithXmlAcceptIsNotAcceptable() {
        HttpTestClient.Response response = client.send("GET", CURRENCIES_PATH,
                Map.of("Accept", MediaType.TEXT_XML_VALUE), null);

        assertApiKitError(response, 406, "Not Acceptable", NOT_ACCEPTABLE);
    }

    /** A resource the RAML does not declare under {@code /api} reaches APIkit's 404 mapping. */
    @Test
    void unknownApiPathIsResourceNotFound() {
        HttpTestClient.Response response = client.send("GET", "/api/unknown", null, null);

        assertApiKitError(response, 404, "Not Found", RESOURCE_NOT_FOUND);
    }

    /** Outside the listener path {@code /api/*} no APIkit router runs: the listener's own default 404 applies. */
    @Test
    void pathOutsideApiHasNoListener() {
        HttpTestClient.Response response = client.send("GET", "/other", null, null);

        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        assertEquals("No listener for endpoint: /other", response.bodyAsString(), response::toString);
    }

    /** An APIkit mapping's answer: raw status line, the bare {@code application/json} type and the literal body. */
    private static void assertApiKitError(HttpTestClient.Response response, int status, String reason,
                                          String body) {
        assertEquals("HTTP/1.1 " + status + " " + reason, response.statusLine(), response::toString);
        assertEquals(status, response.status(), response::toString);
        assertEquals(reason, response.reason(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals(body, response.bodyAsString(), response::toString);
    }

    /** Exactly one {@code Content-Type} header, equal to {@code expected} with no parameter appended. */
    private static void assertContentType(HttpTestClient.Response response, String expected) {
        assertEquals(List.of(expected), response.headers("Content-Type"), response::toString);
    }
}
