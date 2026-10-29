package com.mulesoft.examples.testing_apikit_with_munit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.mulesoft.examples.testing_apikit_with_munit.support.HttpTestClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The MUnit suite {@code testing-apikit-with-munit/src/test/munit/api-test-suite.xml}, ported one test per
 * {@code munit:test} with its assertions, expected values and failure messages unchanged.
 *
 * <p>The suite runs the real application ({@code mock-connectors="false" mock-inbounds="false"}, importing
 * {@code api.xml}), so this class starts the whole Spring Boot application and nothing is mocked: every request
 * is answered by {@code MunitController} and {@code MunitService}.
 *
 * <p>{@link LocalServerPort} replaces {@code http:request-config host="localhost" port="${http.port}"
 * basePath="api"} (suite lines 12-13). The application binds {@code http.port} through
 * {@code server.port: ${http.port:8081}} in {@code application.yml}; the test listens on a random port instead,
 * and {@link #PATH} joins the {@code basePath} with each request's {@code path="/munit"}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiTestSuiteTest {

    /** {@code basePath="api"} (line 12) joined with {@code path="/munit"} (lines 17, 25, 33, 41). */
    private static final String PATH = "/api/munit";

    /** Message of every {@code munit:assert-on-equals} on {@code http.status} (lines 19, 27, 35, 43). */
    private static final String STATUS_MESSAGE = "The HTTP Status code is not correct!";

    /** Message of every {@code munit:assert-payload-equals} (lines 20, 28, 36, 44). */
    private static final String PAYLOAD_MESSAGE = "The response payload is not correct!";

    @LocalServerPort
    private int port;

    private HttpTestClient client;

    @BeforeEach
    void createClient() {
        client = HttpTestClient.localhost(port);
    }

    /** {@code api-test-get} (lines 15-21). */
    @Test
    @DisplayName("api-test-get")
    void apiTestGet() {
        HttpTestClient.Response response = call("GET");

        assertEquals(200, response.status(), STATUS_MESSAGE);
        assertEquals("\"GET RESPONSE\"", payload(response), PAYLOAD_MESSAGE);
    }

    /** {@code api-test-post} (lines 23-29). */
    @Test
    @DisplayName("api-test-post")
    void apiTestPost() {
        HttpTestClient.Response response = call("POST");

        assertEquals(201, response.status(), STATUS_MESSAGE);
        assertEquals("\"POST RESPONSE\"", payload(response), PAYLOAD_MESSAGE);
    }

    /** {@code api-test-put} (lines 31-37). */
    @Test
    @DisplayName("api-test-put")
    void apiTestPut() {
        HttpTestClient.Response response = call("PUT");

        assertEquals(201, response.status(), STATUS_MESSAGE);
        assertEquals("\"PUT RESPONSE\"", payload(response), PAYLOAD_MESSAGE);
    }

    /**
     * {@code api-test-delete} (lines 39-45). The status is 200, not 204: the RAML declares {@code delete:
     * responses: 200} and APIkit answers with the declared 2xx.
     */
    @Test
    @DisplayName("api-test-delete")
    void apiTestDelete() {
        HttpTestClient.Response response = call("DELETE");

        assertEquals(200, response.status(), STATUS_MESSAGE);
        assertEquals("\"DELETE RESPONSE\"", payload(response), PAYLOAD_MESSAGE);
    }

    /**
     * The request steps every test shares: {@code munit:set payload="#['']"} becomes an empty body, sent with
     * {@code Content-Length: 0}, and {@code http:request} sends it with {@code method} to {@link #PATH} with no
     * {@code Content-Type} or {@code Accept}, as the MUnit request sets neither.
     */
    private HttpTestClient.Response call(String method) {
        return client.send(method, PATH, Map.of(), new byte[0]);
    }

    /**
     * {@code object-to-string-transformer}: the response body as a String, compared exactly as received, so the
     * JSON string's double quotes are part of the expected payload.
     */
    private static String payload(HttpTestClient.Response response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}
