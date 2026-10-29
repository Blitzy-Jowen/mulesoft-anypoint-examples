/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_mysql_database.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.mulesoft.examples.querying_a_mysql_database.client.EmployeeRepository;
import com.mulesoft.examples.querying_a_mysql_database.support.HttpTestClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/**
 * Wire-level test of {@link EmployeeQueryController}, the HTTP trigger of flow {@code database-to-jsonFlow}
 * (querying-a-mysql-database/src/main/app/database-to-json.xml:4-12), on the application's own Undertow listener
 * over the H2 database (MySQL mode) that profile {@code test} seeds from the unchanged {@code import.sql}.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the
 * socket, so status code, reason phrase, {@code Content-Type} and body bytes are asserted as the listener writes
 * them. The happy path is the original {@code QueryingMysqlDatabaseIT} request, answered alike for every method
 * because the listener declares no {@code allowedMethods}. The edge cases pin how the {@code lastname} query
 * parameter reaches the query: a missing one is rendered as the text {@code null} as MEL rendered it, the last of
 * repeated ones wins, a form body is never read, and hostile input is bound as data. The error cases are the SC-1
 * platform defaults: the no-listener 404 and the JSON 500 for a database failure, which the flow, having no
 * exception strategy, does not handle.
 *
 * <p>The repository is a spy over the real H2-backed bean, so every request runs the real query; only the last
 * name {@value #FAILING_LAST_NAME} is stubbed to fail. The stub is set before each test because the spy's default
 * reset clears stubs and recorded invocations after every test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EmployeeQueryControllerTest {

    private static final String PUCKETT_REQUEST = "/?lastname=Puckett";

    /** The reply the original {@code QueryingMysqlDatabaseIT} asserts for {@code lastname=Puckett}. */
    private static final String PUCKETT_JSON = "[{\"first_name\":\"Chava\"},{\"first_name\":\"Quentin\"}]";

    private static final String EMPTY_JSON_ARRAY = "[]";

    private static final String FAILING_LAST_NAME = "Boom";

    private static final String FAILURE_MESSAGE = "Communications link failure";

    @LocalServerPort
    private int port;

    @SpyBean
    private EmployeeRepository repository;

    private HttpTestClient client;

    @BeforeEach
    void setUp() {
        // doThrow keeps the real query from running while the stub is recorded.
        doThrow(new DataAccessResourceFailureException(FAILURE_MESSAGE))
                .when(repository).findFirstNamesByLastName(FAILING_LAST_NAME);
        client = HttpTestClient.localhost(port);
    }

    @Test
    void getWithLastnameReturnsMatchingFirstNames() {
        assertOkJson(client.get(PUCKETT_REQUEST), PUCKETT_JSON);
    }

    /**
     * The listener declares no {@code allowedMethods}, so every method runs the flow; {@code OPTIONS} reaches the
     * controller's explicit mapping instead of Spring's own {@code Allow} reply.
     *
     * @param method the request method, sent without a body
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "OPTIONS"})
    void everyMethodIsAnsweredLikeGet(String method) {
        assertOkJson(client.send(method, PUCKETT_REQUEST, null, null), PUCKETT_JSON);
    }

    @Test
    void unknownLastnameReturnsEmptyArray() {
        assertOkJson(client.get("/?lastname=Nobody"), EMPTY_JSON_ARRAY);
    }

    /** MEL rendered a missing {@code lastname} as the text {@code null}, which matches no employee. */
    @Test
    void missingLastnameIsQueriedAsNullText() {
        assertOkJson(client.get("/"), EMPTY_JSON_ARRAY);

        verify(repository).findFirstNamesByLastName("null");
    }

    /** Mule's {@code http.query.params} yielded the last occurrence of a repeated parameter. */
    @Test
    void repeatedLastnameUsesLastOccurrence() {
        assertOkJson(client.get("/?lastname=Sosa&lastname=Puckett"), PUCKETT_JSON);

        verify(repository).findFirstNamesByLastName("Puckett");
        verify(repository, never()).findFirstNamesByLastName("Sosa");
    }

    /** The original query read {@code http.query.params} only, never form fields of the body. */
    @Test
    void formBodyIsNotReadAsLastname() {
        HttpTestClient.Response response = client.send("POST", "/",
                Map.of("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE),
                "lastname=Puckett".getBytes(StandardCharsets.UTF_8));

        assertOkJson(response, EMPTY_JSON_ARRAY);
        verify(repository).findFirstNamesByLastName("null");
    }

    /**
     * The decoded value {@code Puckett" or "1"="1} is a bind parameter, so it matches no employee; the original's
     * interpolation into a double-quoted MySQL literal would have returned all 102 seeded employees.
     */
    @Test
    void injectionAttemptIsBoundAsData() {
        assertOkJson(client.get("/?lastname=Puckett%22%20or%20%221%22%3D%221"), EMPTY_JSON_ARRAY);

        verify(repository).findFirstNamesByLastName("Puckett\" or \"1\"=\"1");
    }

    @Test
    void unknownPathReturnsNoListener404() {
        HttpTestClient.Response response = client.get("/unknown");

        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertContentTypeStartsWith(response, MediaType.TEXT_PLAIN_VALUE);
        assertEquals("No listener for endpoint: /unknown", response.bodyAsString(), response::toString);
    }

    @Test
    void repositoryFailureReturnsJson500() {
        HttpTestClient.Response response = client.get("/?lastname=" + FAILING_LAST_NAME);

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertContentTypeStartsWith(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals("{ \"message\": \"" + FAILURE_MESSAGE + "\" }", response.bodyAsString(), response::toString);
    }

    private static void assertOkJson(HttpTestClient.Response response, String expectedBody) {
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertContentTypeStartsWith(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals(expectedBody, response.bodyAsString(), response::toString);
    }

    private static void assertContentTypeStartsWith(HttpTestClient.Response response, String mediaType) {
        String contentType = response.header("Content-Type");
        assertNotNull(contentType, response::toString);
        assertTrue(contentType.startsWith(mediaType), response::toString);
    }
}
