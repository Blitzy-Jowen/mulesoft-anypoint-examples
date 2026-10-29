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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
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
 * Wire-level test of {@link ItemsController}, the RAML resource {@code /items} of {@code api/netsuite-api.raml}
 * that replaces the flow {@code get:/items:netsuite-api-config} routed by the APIkit router on the original
 * {@code /api/*} listener (netsuite-data-retrieval/src/main/app/netsuite-api.xml:54-78).
 *
 * <p>The whole application runs on Undertow on a random port with {@link NetsuiteSearchService} mocked, so no
 * NetSuite host or credential is involved. Every request goes through ST-1 {@link HttpTestClient},
 * so the status code, Undertow's reason phrase, {@code Content-Type} and the body bytes are asserted as written to
 * the wire:
 * <ul>
 *   <li>{@code quantity} and {@code operator} reach the service as bound, with the RAML defaults {@code 0} and
 *       {@code GREATER_THAN} for an absent parameter, and the service's JSON is returned byte for byte with 200;</li>
 *   <li>a {@code quantity} that is not a RAML integer, an {@code operator} outside the RAML enumeration, an empty or
 *       repeated value of either, a method the RAML does not declare, an {@code Accept} header excluding JSON and a
 *       path no resource serves under {@code /api} get APIkit's 400, 405, 406 and 404 mappings with their literal
 *       bodies (netsuite-api.xml:107-133), and the service is never called;</li>
 *   <li>the three approved upstream failure modes answer 502, 429 and 503 through {@code UpstreamExceptionHandler}
 *       ahead of {@code GlobalExceptionHandler}'s catch-all, whose 500 answers any other service failure.</li>
 * </ul>
 *
 * <p>The context configuration (profile {@code test}, the single {@code @MockBean}) is the one
 * {@code CustomersControllerTest} and {@code OpportunitiesControllerTest} declare, so the three share one cached
 * application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ItemsControllerTest {

    private static final String ITEMS = "/api/items";

    /** The committed RAML example of {@code GET /items}, the service's rendering of the recorded supply plans. */
    private static final String ITEMS_EXAMPLE = "api/items-response.json";

    /** Body of APIkit's 404 mapping (netsuite-api.xml:111). */
    private static final String RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    /** Body of APIkit's 405 mapping (netsuite-api.xml:116). */
    private static final String METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    /** Body of APIkit's 406 mapping (netsuite-api.xml:126). */
    private static final String NOT_ACCEPTABLE = "{ \"message\": \"Not acceptable\" }";

    /** Body of APIkit's 400 mapping (netsuite-api.xml:131). */
    private static final String BAD_REQUEST = "{ \"message\": \"Bad request\" }";

    /** The RAML default of {@code quantity}. */
    private static final int DEFAULT_QUANTITY = 0;

    /** The RAML default of {@code operator}. */
    private static final String DEFAULT_OPERATOR = "GREATER_THAN";

    /** The query the upstream failure-mode tests send, and the arguments the service receives for it. */
    private static final String FAILING_QUERY = "?quantity=5&operator=LESS_THAN";

    private static final int FAILING_QUANTITY = 5;

    private static final String FAILING_OPERATOR = "LESS_THAN";

    @MockBean
    private NetsuiteSearchService searchService;

    @LocalServerPort
    private int port;

    private HttpTestClient client;

    @BeforeEach
    void createClient() {
        client = HttpTestClient.localhost(port);
    }

    // Happy path, RAML defaults and parameter binding

    @ParameterizedTest(name = "GET " + ITEMS + "?quantity=5&operator=LESS_THAN with Accept {0} -> 200 example bytes")
    @NullSource
    @ValueSource(strings = MediaType.APPLICATION_JSON_VALUE)
    void getItemsReturnsServiceJsonUnchanged(String accept) throws Exception {
        byte[] expected = new ClassPathResource(ITEMS_EXAMPLE).getContentAsByteArray();
        when(searchService.getItems(5, "LESS_THAN")).thenReturn(new String(expected, StandardCharsets.UTF_8));

        HttpTestClient.Response response = client.get(ITEMS + "?quantity=5&operator=LESS_THAN",
                accept == null ? null : Map.of("Accept", accept));

        assertStatus(response, 200, "OK");
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertArrayEquals(expected, response.body(), response::toString);
        verify(searchService).getItems(5, "LESS_THAN");
        verifyNoMoreInteractions(searchService);
    }

    /**
     * Each query string and the arguments the service must receive: an absent parameter takes its RAML default, a
     * present one is passed unchanged, and a negative {@code quantity} is accepted because the RAML declares no
     * minimum.
     */
    private static Stream<Arguments> parameterBindings() {
        return Stream.of(
                Arguments.of("", DEFAULT_QUANTITY, DEFAULT_OPERATOR),
                Arguments.of("?quantity=10", 10, DEFAULT_OPERATOR),
                Arguments.of("?operator=EQUAL_TO", DEFAULT_QUANTITY, "EQUAL_TO"),
                Arguments.of("?quantity=-3", -3, DEFAULT_OPERATOR));
    }

    @ParameterizedTest(name = "GET " + ITEMS + "{0} -> getItems({1}, \"{2}\")")
    @MethodSource("parameterBindings")
    void parametersReachServiceWithRamlDefaults(String query, int expectedQuantity, String expectedOperator)
            throws Exception {
        when(searchService.getItems(expectedQuantity, expectedOperator)).thenReturn("[]");

        HttpTestClient.Response response = client.get(ITEMS + query);

        assertStatus(response, 200, "OK");
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertBody(response, "[]");
        verify(searchService).getItems(expectedQuantity, expectedOperator);
        verifyNoMoreInteractions(searchService);
    }

    @ParameterizedTest(name = "GET " + ITEMS + "?quantity=1&operator={0} -> getItems(1, \"{0}\")")
    @ValueSource(strings = {
            "LESS_THAN", "GREATER_THAN", "EQUAL_TO", "LESS_THAN_OR_EQUAL_TO", "GREATER_THAN_OR_EQUAL_TO"})
    void everyRamlOperatorReachesServiceUnchanged(String operator) throws Exception {
        when(searchService.getItems(1, operator)).thenReturn("[]");

        HttpTestClient.Response response = client.get(ITEMS + "?quantity=1&operator=" + operator);

        assertStatus(response, 200, "OK");
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertBody(response, "[]");
        verify(searchService).getItems(1, operator);
        verifyNoMoreInteractions(searchService);
    }

    // APIkit mappings (SC-3): the request never reaches the service

    /**
     * Each query string APIkit's RAML validation rejected, with the reason. The ST-1 client sends the target as raw
     * bytes, so {@code %20} and {@code %23} reach the server encoded and decode to a space and {@code #}.
     */
    private static Stream<Arguments> invalidQueries() {
        return Stream.of(
                Arguments.of("quantity=abc", "quantity is not a number"),
                Arguments.of("quantity=1.5", "quantity is not an integer"),
                Arguments.of("quantity=2147483648", "quantity is outside the int range"),
                Arguments.of("quantity=%205", "quantity has a leading space Spring's conversion would strip"),
                Arguments.of("quantity=0x10", "quantity is hexadecimal, which Spring's conversion would decode"),
                Arguments.of("quantity=%2310", "quantity is '#'-hexadecimal, which Spring's conversion would decode"),
                Arguments.of("quantity=", "quantity is sent but empty"),
                Arguments.of("quantity=1&quantity=2", "quantity is repeated"),
                Arguments.of("operator=FOO", "operator is outside the enumeration"),
                Arguments.of("operator=greater_than", "operator values are case-sensitive"),
                Arguments.of("operator=", "operator is sent but empty"),
                Arguments.of("operator=LESS_THAN&operator=EQUAL_TO", "operator is repeated"));
    }

    /** The body is APIkit's literal text, never an exception message, so SC-3 and not SC-1's 500 answered. */
    @ParameterizedTest(name = "GET " + ITEMS + "?{0} ({1}) -> APIkit 400")
    @MethodSource("invalidQueries")
    void invalidQueryParameterReturnsApiKit400(String query, String reason) {
        HttpTestClient.Response response = client.get(ITEMS + "?" + query);

        assertApiKitError(response, 400, "Bad Request", BAD_REQUEST);
        verifyNoInteractions(searchService);
    }

    /** Each method the RAML does not declare for {@code /items}, with the body it is sent with. */
    private static Stream<Arguments> undeclaredMethods() {
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        return Stream.of(
                Arguments.of("POST", json),
                Arguments.of("PUT", json),
                Arguments.of("PATCH", json),
                Arguments.of("DELETE", null),
                Arguments.of("OPTIONS", null));
    }

    @ParameterizedTest(name = "{0} " + ITEMS + " -> APIkit 405")
    @MethodSource("undeclaredMethods")
    void undeclaredMethodReturnsApiKit405(String method, byte[] body) {
        Map<String, String> headers = body == null ? null : Map.of("Content-Type", MediaType.APPLICATION_JSON_VALUE);

        HttpTestClient.Response response = client.send(method, ITEMS, headers, body);

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
        HttpTestClient.Response response = client.send("HEAD", ITEMS + "?quantity=1", null, null);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.APPLICATION_JSON_VALUE), response.headers("Content-Type"), response::toString);
        assertEquals(0, response.body().length, response::toString);
        assertEquals(List.of(String.valueOf(METHOD_NOT_ALLOWED.getBytes(StandardCharsets.UTF_8).length)),
                response.headers("Content-Length"), response::toString);
        verifyNoInteractions(searchService);
    }

    @Test
    void acceptExcludingJsonReturnsApiKit406() {
        HttpTestClient.Response response = client.get(ITEMS + "?quantity=1",
                Map.of("Accept", MediaType.TEXT_PLAIN_VALUE));

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
                Arguments.of("rate limit",
                        new UpstreamRateLimitException(UpstreamSystem.NETSUITE, "Request rate limit exceeded.", "30",
                                null),
                        429, "Too Many Requests",
                        "{ \"message\": \"Request rate limit exceeded.\", \"upstream\": \"NetSuite\" }",
                        List.of("30")),
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
        when(searchService.getItems(FAILING_QUANTITY, FAILING_OPERATOR)).thenThrow(failure);

        HttpTestClient.Response response = client.get(ITEMS + FAILING_QUERY);

        assertStatus(response, status, reason);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertBody(response, body);
        assertEquals(retryAfter, response.headers("Retry-After"), response::toString);
        // The executor behind the service owns the single re-authentication retry; the controller adds none.
        verify(searchService, times(1)).getItems(FAILING_QUANTITY, FAILING_OPERATOR);
        verifyNoMoreInteractions(searchService);
    }

    /** An unresolved select {@code typeId} of the record-shape mapping (AAP 0.7.4) is neither APIkit nor upstream. */
    @Test
    void unhandledServiceFailureReturnsGlobal500() throws Exception {
        when(searchService.getItems(FAILING_QUANTITY, FAILING_OPERATOR))
                .thenThrow(new IllegalStateException("No select source ID for custom field custentity8"));

        HttpTestClient.Response response = client.get(ITEMS + FAILING_QUERY);

        assertStatus(response, 500, "Internal Server Error");
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertBody(response, "{ \"message\": \"No select source ID for custom field custentity8\" }");
        verify(searchService, times(1)).getItems(FAILING_QUANTITY, FAILING_OPERATOR);
        verifyNoMoreInteractions(searchService);
    }

    // Assertions

    /** One of APIkit's error mappings, which all answer {@code application/json} with a literal body. */
    private static void assertApiKitError(HttpTestClient.Response response, int status, String reason,
                                          String body) {
        assertStatus(response, status, reason);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertBody(response, body);
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

