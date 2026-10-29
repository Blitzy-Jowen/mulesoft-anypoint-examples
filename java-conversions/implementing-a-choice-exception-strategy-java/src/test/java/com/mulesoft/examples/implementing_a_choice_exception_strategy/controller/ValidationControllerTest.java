/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.mulesoft.examples.implementing_a_choice_exception_strategy.support.HttpTestClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Wire-level test of {@link ValidationController}, the HTTP trigger of flow {@code choice-error-handlingFlow1}
 * ({@code implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml:3-47}), on the
 * application's own Undertow listener with every bean real.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the socket,
 * so the reason phrases the two catch branches set through {@code flowVars['reason']} are asserted as they reach the
 * wire, together with the {@code Content-Type} header and the body bytes. Covered here: the success reply, both catch
 * branches of the choice exception strategy (each reached by every validator rule that selects it), the empty 200 of
 * the email {@code regex-filter} rejection, every method being accepted (the listener declares no
 * {@code allowedMethods}), and the listener-level platform defaults of SC-1: the JSON 500 for a body the JSON
 * transformer cannot turn into a map and the no-listener 404 for an unserved path.
 *
 * <p>The error bodies render the flow's {@code java.util.HashMap} payload, so their expected texts are fixed literals
 * in the iteration order a {@code HashMap} holding these keys has on JDK 17, never values computed by the test.
 *
 * <p>The original {@code ImplementingChoiceExceptionStrategyIT} assertions are ported separately in
 * {@code ImplementingChoiceExceptionStrategyTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ValidationControllerTest {

    private static final String ROOT = "/";

    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    private static final String SUCCESS_BODY = "Input data validation passed.";

    private static final String ERROR_BRANCH_CONTENT_TYPE = "text/plain;charset=UTF-8";

    /** {@code message.json} with {@code item price per unit} {@code -1}. */
    private static final String NEGATIVE_PRICE_BODY =
            "{\"email\":\"aaa@aaa.aa\",\"item name\":\"aa\",\"item units\":10,\"item price per unit\":-1,"
                    + "\"membership\":\"free\"}";

    /** {@code message.json} with {@code item units} {@code 0}. */
    private static final String ZERO_UNITS_BODY =
            "{\"email\":\"aaa@aaa.aa\",\"item name\":\"aa\",\"item units\":0,\"item price per unit\":1,"
                    + "\"membership\":\"free\"}";

    /** {@code message.json} with the non-integer {@code item units} {@code 1.5}. */
    private static final String DECIMAL_UNITS_BODY =
            "{\"email\":\"aaa@aaa.aa\",\"item name\":\"aa\",\"item units\":1.5,\"item price per unit\":1,"
                    + "\"membership\":\"free\"}";

    /** {@code message.json} without {@code item units}. */
    private static final String MISSING_UNITS_BODY =
            "{\"email\":\"aaa@aaa.aa\",\"item name\":\"aa\",\"item price per unit\":1,\"membership\":\"free\"}";

    /** {@code message.json} with an email the {@code regex-filter} does not match. */
    private static final String INVALID_EMAIL_BODY =
            "{\"email\":\"not-an-email\",\"item name\":\"aa\",\"item units\":10,\"item price per unit\":1,"
                    + "\"membership\":\"free\"}";

    /** {@code message.json} with a present but null {@code email}. */
    private static final String NULL_EMAIL_BODY =
            "{\"email\":null,\"item name\":\"aa\",\"item units\":10,\"item price per unit\":1,"
                    + "\"membership\":\"free\"}";

    /** The 500 message for a body that is empty or whose JSON value is not an object. */
    private static final String NOT_AN_OBJECT_ERROR = "{ \"message\": \"JSON payload is not an object\" }";

    @LocalServerPort
    private int port;

    private HttpTestClient client;
    private byte[] message;
    private byte[] messageWrong;

    @BeforeEach
    void setUp() {
        client = HttpTestClient.localhost(port);
        message = readResource("/message.json");
        messageWrong = readResource("/message_wrong.json");
    }

    @Test
    void postValidMessageReturnsSuccess() {
        assertSuccess(client.send("POST", ROOT, JSON_HEADERS, message));
    }

    @Test
    void postMessageWithoutEmailReturnsMissingInputData() {
        assertChoiceBranch(client.send("POST", ROOT, JSON_HEADERS, messageWrong), "Missing input data",
                "Missing input data: {item price per unit=1, membership=free, item name=aa, item units=10}");
    }

    @Test
    void postNegativePricePerUnitReturnsInvalidInputData() {
        assertChoiceBranch(client.send("POST", ROOT, JSON_HEADERS, utf8(NEGATIVE_PRICE_BODY)), "Invalid input data",
                "Invalid input data: {item price per unit=-1, membership=free, item name=aa, item units=10, "
                        + "email=aaa@aaa.aa}");
    }

    @Test
    void postZeroItemUnitsReturnsInvalidInputData() {
        assertChoiceBranch(client.send("POST", ROOT, JSON_HEADERS, utf8(ZERO_UNITS_BODY)), "Invalid input data",
                "Invalid input data: {item price per unit=1, membership=free, item name=aa, item units=0, "
                        + "email=aaa@aaa.aa}");
    }

    /** {@code Integer.parseInt("1.5")} raises a {@link NumberFormatException}, an {@link IllegalArgumentException}. */
    @Test
    void postDecimalItemUnitsReturnsInvalidInputData() {
        assertChoiceBranch(client.send("POST", ROOT, JSON_HEADERS, utf8(DECIMAL_UNITS_BODY)), "Invalid input data",
                "Invalid input data: {item price per unit=1, membership=free, item name=aa, item units=1.5, "
                        + "email=aaa@aaa.aa}");
    }

    /** The validator calls {@code toString()} on the absent {@code item units} value, a {@link NullPointerException}. */
    @Test
    void postMessageWithoutItemUnitsReturnsMissingInputData() {
        assertChoiceBranch(client.send("POST", ROOT, JSON_HEADERS, utf8(MISSING_UNITS_BODY)), "Missing input data",
                "Missing input data: {item price per unit=1, membership=free, item name=aa, email=aaa@aaa.aa}");
    }

    @Test
    void postInvalidEmailReturnsEmptyOk() {
        assertFilterRejection(client.send("POST", ROOT, JSON_HEADERS, utf8(INVALID_EMAIL_BODY)));
    }

    /** The key is present, so the validator accepts; the {@code regex-filter} then rejects the null email. */
    @Test
    void postNullEmailReturnsEmptyOk() {
        assertFilterRejection(client.send("POST", ROOT, JSON_HEADERS, utf8(NULL_EMAIL_BODY)));
    }

    @Test
    void getValidMessageRunsTheFlow() {
        assertSuccess(client.send("GET", ROOT, JSON_HEADERS, message));
    }

    @Test
    void putValidMessageRunsTheFlow() {
        assertSuccess(client.send("PUT", ROOT, JSON_HEADERS, message));
    }

    /** No choice branch names the transformer's failure, so the platform-default 500 of SC-1 answers it. */
    @Test
    void postEmptyBodyReturnsUnhandledJson500() {
        assertUnhandled(client.send("POST", ROOT, JSON_HEADERS, new byte[0]), NOT_AN_OBJECT_ERROR);
    }

    @Test
    void postJsonArrayReturnsUnhandledJson500() {
        assertUnhandled(client.send("POST", ROOT, JSON_HEADERS, utf8("[1]")), NOT_AN_OBJECT_ERROR);
    }

    @Test
    void unknownPathReturnsNoListener404() {
        HttpTestClient.Response response = client.send("GET", "/unknown", Map.of(), null);

        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertEquals(List.of("text/plain"), response.headers("Content-Type"), response::toString);
        assertEquals("No listener for endpoint: /unknown", response.bodyAsString(), response::toString);
    }

    /**
     * The flow's success {@code set-payload} declares no MIME type, so the original listener sent no
     * {@code Content-Type}: the reply carries none, only the length of its 29 bytes.
     */
    private static void assertSuccess(HttpTestClient.Response response) {
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals("29", response.header("Content-Length"), response::toString);
        assertArrayEquals(utf8(SUCCESS_BODY), response.body(), response::toString);
    }

    /**
     * A catch branch of the choice exception strategy: status {@code 400}, the branch's {@code reason} on the status
     * line, the branch body rendering the payload map, and the Content-Type {@link ValidationController} defines for
     * both branches.
     */
    private static void assertChoiceBranch(HttpTestClient.Response response, String reason, String body) {
        assertEquals("HTTP/1.1 400 " + reason, response.statusLine(), response::toString);
        assertEquals(ERROR_BRANCH_CONTENT_TYPE, ValidationController.RESPONSE_CONTENT_TYPE.toString());
        assertEquals(List.of(ERROR_BRANCH_CONTENT_TYPE), response.headers("Content-Type"), response::toString);
        assertEquals(body, response.bodyAsString(), response::toString);
    }

    /** A filter rejection without {@code throwOnUnaccepted}: an empty 200 with no Content-Type (AAP 0.4.7). */
    private static void assertFilterRejection(HttpTestClient.Response response) {
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals("0", response.header("Content-Length"), response::toString);
        assertEquals(0, response.body().length, response::toString);
    }

    private static void assertUnhandled(HttpTestClient.Response response, String body) {
        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertEquals(List.of("application/json"), response.headers("Content-Type"), response::toString);
        assertEquals(body, response.bodyAsString(), response::toString);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads a test resource from the classpath as unchanged bytes, the files' tabs and trailing spaces included; a
     * missing or unreadable resource fails the test instead of leaving the request body unset.
     */
    private byte[] readResource(String name) {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            assertNotNull(in, "missing test resource " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read test resource " + name, e);
        }
    }
}
