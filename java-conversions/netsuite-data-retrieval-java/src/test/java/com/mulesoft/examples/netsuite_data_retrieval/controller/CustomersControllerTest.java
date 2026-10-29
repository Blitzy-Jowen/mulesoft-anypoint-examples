/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.mulesoft.examples.netsuite_data_retrieval.client.UpstreamSystem;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamRateLimitException;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamUnavailableException;
import com.mulesoft.examples.netsuite_data_retrieval.service.NetsuiteSearchService;
import com.mulesoft.examples.netsuite_data_retrieval.support.HttpTestClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/**
 * Wire-level test of {@link CustomersController}, the RAML resource {@code /customers} of
 * {@code api/netsuite-api.raml} that replaces the flow {@code get:/customers:netsuite-api-config} routed by the
 * APIkit router on the original {@code /api/*} listener (netsuite-data-retrieval/src/main/app/netsuite-api.xml:20-53).
 *
 * <p>The whole application runs on Undertow on a random port with {@link NetsuiteSearchService} mocked, so no
 * NetSuite host or credential is involved. Every request goes through ST-1 {@link HttpTestClient},
 * so the status code, Undertow's reason phrase, {@code Content-Type} and the body bytes are asserted as written to
 * the wire:
 * <ul>
 *   <li>the {@code name} query parameter reaches the service decoded and unchanged ({@code null} when absent,
 *       {@code ""} when empty), and the service's JSON is returned byte for byte with 200,
 *       {@code Cache-Control: no-store} and {@code X-Content-Type-Options: nosniff};</li>
 *   <li>a repeated {@code name}, a method the RAML does not declare, an {@code Accept} header excluding JSON and
 *       a path no resource serves under {@code /api} get APIkit's 400, 405, 406 and 404 mappings with their
 *       literal bodies (netsuite-api.xml:107-133), and the service is never called;</li>
 *   <li>no error reply below carries either of the two headers the 200 reply adds;</li>
 *   <li>the three approved upstream failure modes answer 502, 429 and 503 through {@code UpstreamExceptionHandler}
 *       ahead of {@code GlobalExceptionHandler}'s catch-all, whose 500 answers any other service failure;</li>
 *   <li>a path outside {@code /api}, which the original listener never served, gets the listener's no-listener
 *       404.</li>
 * </ul>
 *
 * <p>The context configuration (profile {@code test}, the single {@code @MockBean}) is the one
 * {@code ItemsControllerTest} and {@code OpportunitiesControllerTest} declare, so the three share one cached
 * application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CustomersControllerTest {

    private static final String CUSTOMERS = "/api/customers";

    /** The committed RAML example of {@code GET /customers}, the service's rendering of the recorded records. */
    private static final String CUSTOMERS_EXAMPLE = "api/customers-response.json";

    /** Body of APIkit's 404 mapping (netsuite-api.xml:111). */
    private static final String RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    /** Body of APIkit's 405 mapping (netsuite-api.xml:116). */
    private static final String METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    /** Body of APIkit's 406 mapping (netsuite-api.xml:126). */
    private static final String NOT_ACCEPTABLE = "{ \"message\": \"Not acceptable\" }";

    /** Body of APIkit's 400 mapping (netsuite-api.xml:131). */
    private static final String BAD_REQUEST = "{ \"message\": \"Bad request\" }";

    /** The {@code name} the upstream failure-mode tests send. */
    private static final String FAILING_NAME = "x";

    @MockBean
    private NetsuiteSearchService searchService;

    @LocalServerPort
    private int port;

    private HttpTestClient client;

    @BeforeEach
    void createClient() {
        client = HttpTestClient.localhost(port);
    }

    // Happy path and parameter binding

    @ParameterizedTest(name = "GET " + CUSTOMERS + "?name=Ramsey with Accept {0} -> 200 example bytes")
    @NullSource
    @ValueSource(strings = {MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE})
    void getCustomersReturnsServiceJsonUnchanged(String accept) throws Exception {
        byte[] expected = new ClassPathResource(CUSTOMERS_EXAMPLE).getContentAsByteArray();
        when(searchService.getCustomers("Ramsey")).thenReturn(new String(expected, StandardCharsets.UTF_8));

        HttpTestClient.Response response = client.get(CUSTOMERS + "?name=Ramsey",
                accept == null ? null : Map.of("Accept", accept));

        assertStatus(response, 200, "OK");
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertRecordHeaders(response);
        assertArrayEquals(expected, response.body(), response::toString);
        verify(searchService).getCustomers("Ramsey");
        verifyNoMoreInteractions(searchService);
    }

    @Test
    void absentNameReachesServiceAsNull() throws Exception {
        when(searchService.getCustomers(any())).thenReturn("[]");

        HttpTestClient.Response response = client.get(CUSTOMERS);

        assertStatus(response, 200, "OK");
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertRecordHeaders(response);
        assertBody(response, "[]");
        verify(searchService).getCustomers(isNull());
        verifyNoMoreInteractions(searchService);
    }

    /**
     * Each query string and the {@code name} value the service must receive: an empty value stays {@code ""},
     * and percent-encoding is decoded without any escaping, which belongs to the service's SuiteQL literal.
     */
    private static Stream<Arguments> nameBindings() {
        return Stream.of(
                Arguments.of("name=", ""),
                Arguments.of("name=Ramsey%20Inc.", "Ramsey Inc."),
                Arguments.of("name=O%27Brien", "O'Brien"));
    }

    @ParameterizedTest(name = "GET " + CUSTOMERS + "?{0} -> getCustomers(\"{1}\")")
    @MethodSource("nameBindings")
    void nameReachesServiceDecodedAndUnchanged(String query, String expectedName) throws Exception {
        when(searchService.getCustomers(expectedName)).thenReturn("[]");

        HttpTestClient.Response response = client.get(CUSTOMERS + "?" + query);

        assertStatus(response, 200, "OK");
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertRecordHeaders(response);
        assertBody(response, "[]");
        verify(searchService).getCustomers(expectedName);
        verifyNoMoreInteractions(searchService);
    }

    // APIkit mappings (SC-3): the request never reaches the service

    @ParameterizedTest(name = "GET " + CUSTOMERS + "?{0} -> APIkit 400")
    @ValueSource(strings = {"name=a&name=b", "name=Ramsey&name=Ramsey"})
    void repeatedNameReturnsApiKit400(String query) {
        HttpTestClient.Response response = client.get(CUSTOMERS + "?" + query);

        assertApiKitError(response, 400, "Bad Request", BAD_REQUEST);
        verifyNoInteractions(searchService);
    }

    /** Each method the RAML does not declare for {@code /customers}, with the body it is sent with. */
    private static Stream<Arguments> undeclaredMethods() {
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        return Stream.of(
                Arguments.of("POST", json),
                Arguments.of("PUT", json),
                Arguments.of("PATCH", json),
                Arguments.of("DELETE", null),
                Arguments.of("OPTIONS", null));
    }

    @ParameterizedTest(name = "{0} " + CUSTOMERS + " -> APIkit 405")
    @MethodSource("undeclaredMethods")
    void undeclaredMethodReturnsApiKit405(String method, byte[] body) {
        Map<String, String> headers = body == null ? null : Map.of("Content-Type", MediaType.APPLICATION_JSON_VALUE);

        HttpTestClient.Response response = client.send(method, CUSTOMERS, headers, body);

        assertApiKitError(response, 405, "Method Not Allowed", METHOD_NOT_ALLOWED);
        verifyNoInteractions(searchService);
    }

    /**
     * HEAD goes through ST-1 like every other request: the client reads no body for a HEAD response, so the
     * exchange ends at the empty line after the headers. APIkit's 405 answers with its bare {@code application/json}
     * type and announces the {@code Content-Length} of its 405 body, while no body bytes are sent.
     */
    @Test
    void headReturns405() {
        HttpTestClient.Response response = client.send("HEAD", CUSTOMERS + "?name=x", null, null);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.APPLICATION_JSON_VALUE), response.headers("Content-Type"), response::toString);
        assertEquals(0, response.body().length, response::toString);
        assertEquals(List.of(String.valueOf(METHOD_NOT_ALLOWED.getBytes(StandardCharsets.UTF_8).length)),
                response.headers("Content-Length"), response::toString);
        verifyNoInteractions(searchService);
    }

    @ParameterizedTest(name = "GET " + CUSTOMERS + "?name=x with Accept {0} -> APIkit 406")
    @ValueSource(strings = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_XML_VALUE})
    void acceptExcludingJsonReturnsApiKit406(String accept) {
        HttpTestClient.Response response = client.get(CUSTOMERS + "?name=x", Map.of("Accept", accept));

        assertApiKitError(response, 406, "Not Acceptable", NOT_ACCEPTABLE);
        verifyNoInteractions(searchService);
    }

    @Test
    void unknownResourceUnderApiReturnsApiKit404() {
        HttpTestClient.Response response = client.get("/api/foo");

        assertApiKitError(response, 404, "Not Found", RESOURCE_NOT_FOUND);
        verifyNoInteractions(searchService);
    }

    // Approved upstream failure modes (SC-9) and the unhandled-exception 500 (SC-1)

    /**
     * Each upstream failure the service can raise: a label, the exception, the status with Undertow's reason
     * phrase, the exact body, and the {@code Retry-After} values the response must carry.
     */
    private static Stream<Arguments> upstreamFailures() {
        return Stream.of(
                Arguments.of("authentication",
                        new UpstreamAuthenticationException(UpstreamSystem.NETSUITE, "Invalid login attempt.", null),
                        502, "Bad Gateway",
                        "{ \"message\": \"Invalid login attempt.\", \"upstream\": \"NetSuite\" }",
                        List.of()),
                Arguments.of("rate limit with Retry-After",
                        new UpstreamRateLimitException(UpstreamSystem.NETSUITE, "Request rate limit exceeded.", "30",
                                null),
                        429, "Too Many Requests",
                        "{ \"message\": \"Request rate limit exceeded.\", \"upstream\": \"NetSuite\" }",
                        List.of("30")),
                Arguments.of("rate limit without Retry-After",
                        new UpstreamRateLimitException(UpstreamSystem.NETSUITE, "Request rate limit exceeded.", null,
                                null),
                        429, "Too Many Requests",
                        "{ \"message\": \"Request rate limit exceeded.\", \"upstream\": \"NetSuite\" }",
                        List.of()),
                Arguments.of("read timeout",
                        new UpstreamUnavailableException(UpstreamSystem.NETSUITE, "Read timed out",
                                new SocketTimeoutException("Read timed out")),
                        503, "Service Unavailable",
                        "{ \"message\": \"Read timed out\", \"upstream\": \"NetSuite\" }",
                        List.of()),
                Arguments.of("connection refused",
                        new UpstreamUnavailableException(UpstreamSystem.NETSUITE, "Connection refused",
                                new ConnectException("Connection refused")),
                        503, "Service Unavailable",
                        "{ \"message\": \"Connection refused\", \"upstream\": \"NetSuite\" }",
                        List.of()));
    }

    @ParameterizedTest(name = "{0} -> {2} {3}")
    @MethodSource("upstreamFailures")
    void upstreamFailureModeIsAnsweredOnceWithoutRetry(String label, RuntimeException failure, int status,
                                                       String reason, String body, List<String> retryAfter)
            throws Exception {
        when(searchService.getCustomers(FAILING_NAME)).thenThrow(failure);

        HttpTestClient.Response response = client.get(CUSTOMERS + "?name=" + FAILING_NAME);

        assertStatus(response, status, reason);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertBody(response, body);
        assertEquals(retryAfter, response.headers("Retry-After"), response::toString);
        assertNoRecordHeaders(response);
        // The executor behind the service owns the single re-authentication retry; the controller adds none.
        verify(searchService, times(1)).getCustomers(FAILING_NAME);
        verifyNoMoreInteractions(searchService);
    }

    /** An unresolved select {@code typeId} of the record-shape mapping (AAP 0.7.4) is not an APIkit or upstream case. */
    @Test
    void unhandledServiceFailureReturnsGlobal500() throws Exception {
        when(searchService.getCustomers(FAILING_NAME))
                .thenThrow(new IllegalStateException("No select source ID for custom field custentity8"));

        HttpTestClient.Response response = client.get(CUSTOMERS + "?name=" + FAILING_NAME);

        assertStatus(response, 500, "Internal Server Error");
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertBody(response, "{ \"message\": \"No select source ID for custom field custentity8\" }");
        assertNoRecordHeaders(response);
        verify(searchService, times(1)).getCustomers(FAILING_NAME);
        verifyNoMoreInteractions(searchService);
    }

    @Test
    void pathOutsideApiReturnsNoListener404() {
        HttpTestClient.Response response = client.get("/other");

        assertStatus(response, 404, "Not Found");
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        assertBody(response, "No listener for endpoint: /other");
        assertNoRecordHeaders(response);
        verifyNoInteractions(searchService);
    }

    // Assertions

    /**
     * One of APIkit's error mappings, which all answer {@code application/json} with a literal body and without
     * the two headers of the 200 reply.
     */
    private static void assertApiKitError(HttpTestClient.Response response, int status, String reason,
                                          String body) {
        assertStatus(response, status, reason);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertBody(response, body);
        assertNoRecordHeaders(response);
    }

    /**
     * The headers the 200 reply adds to the customer records, each exactly once: {@code Cache-Control: no-store},
     * so no client or intermediary keeps a copy, and {@code X-Content-Type-Options: nosniff}.
     */
    private static void assertRecordHeaders(HttpTestClient.Response response) {
        assertEquals(List.of("no-store"), response.headers("Cache-Control"), response::toString);
        assertEquals(List.of("nosniff"), response.headers("X-Content-Type-Options"), response::toString);
    }

    /** Neither header of {@link #assertRecordHeaders}: an error reply keeps the headers its handler writes. */
    private static void assertNoRecordHeaders(HttpTestClient.Response response) {
        assertEquals(List.of(), response.headers("Cache-Control"), response::toString);
        assertEquals(List.of(), response.headers("X-Content-Type-Options"), response::toString);
    }

    /** The status code and the reason phrase exactly as the status line carries them. */
    private static void assertStatus(HttpTestClient.Response response, int code, String reason) {
        assertEquals(code, response.status(), response::toString);
        assertEquals(reason, response.reason(), response::toString);
    }

    /** {@code Content-Type} is present and names {@code typeSubtype}; parameters such as a charset are ignored. */
    private static void assertContentType(HttpTestClient.Response response, String typeSubtype) {
        String contentType = response.header("Content-Type");
        assertNotNull(contentType, () -> "no Content-Type in " + response);
        MediaType expected = MediaType.parseMediaType(typeSubtype);
        assertTrue(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(expected),
                () -> "expected " + typeSubtype + " but got Content-Type " + contentType + " in " + response);
    }

    /** The body is exactly the UTF-8 bytes of {@code expected}. */
    private static void assertBody(HttpTestClient.Response response, String expected) {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
    }
}
