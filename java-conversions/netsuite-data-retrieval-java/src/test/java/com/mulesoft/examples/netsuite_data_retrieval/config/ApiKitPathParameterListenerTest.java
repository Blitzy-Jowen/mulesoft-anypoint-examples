/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.mulesoft.examples.netsuite_data_retrieval.service.NetsuiteSearchService;
import com.mulesoft.examples.netsuite_data_retrieval.support.HttpTestClient;

/**
 * Wire-level test of {@link ApiKitPathParameterConfig} on the {@code /api/*} listener of
 * {@code api/netsuite-api.raml}: a {@code ;} in the request path is content of its segment, as it was to the original
 * listener and its APIkit router (netsuite-data-retrieval/src/main/app/netsuite-api.xml:12-21), so none of the three
 * search resources is served under an alias and no NetSuite search starts.
 *
 * <p>The cases assert the status line, {@code Content-Type} and exact body bytes of:
 * <ul>
 *   <li>{@code /api/customers;x=1}, {@code /api/items;x=1}, {@code /api/opportunities;x=1} and similar segments that
 *       name no resource: APIkit's 404 {@code { "message": "Resource not found" }} for every method, a lower-case
 *       token, a query APIkit would otherwise reject, an {@code Accept} header excluding JSON and a CORS pre-flight,
 *       with {@link NetsuiteSearchService}, the only way to the NetSuite client, never called;</li>
 *   <li>outside the base path, {@code /api;x=1/items}: the listener's no-listener 404 naming the raw path, with no
 *       search either;</li>
 *   <li>the three resources themselves, also with a {@code ;} in the query string: the search's 200, with the
 *       service called once with the bound parameters.</li>
 * </ul>
 *
 * <p>A lower-case {@code head} is not sent: Undertow compares method tokens ignoring case and so writes no body for
 * it, while ST-1 skips the body only for the exact token {@code HEAD} and would wait for one.
 *
 * <p>The whole application runs on Undertow (random port) in the context configuration of the controller tests
 * (profile {@code test}, the single {@code @MockBean}), so it shares their cached context and involves no NetSuite
 * host or credential. Requests go through ST-1 {@link HttpTestClient}, which writes the request target byte for
 * byte, so the {@code ;} reaches the listener as sent and the status line, headers and body bytes are asserted as the
 * listener writes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiKitPathParameterListenerTest {

    /** The body of APIkit's 404 mapping (netsuite-api.xml:111). */
    private static final String APIKIT_RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    private static final String OK = "HTTP/1.1 200 OK";

    private static final String NOT_FOUND = "HTTP/1.1 404 Not Found";

    private static final String JSON = "application/json";

    /** The JSON the mocked search returns for the resources themselves. */
    private static final String SEARCH_RESULT = "[]";

    /** The three RAML resources followed by {@code ;} segment parameters, each sent with every method. */
    private static final List<String> ALIASES = List.of("/api/customers;x=1", "/api/items;x=1",
            "/api/opportunities;x=1");

    /** Every method the original router dispatched, RAML-declared or not; {@code HEAD} gets no body on the wire. */
    private static final List<String> METHODS = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS",
            "TRACE");

    @MockBean
    private NetsuiteSearchService searchService;

    @LocalServerPort
    private int port;

    /**
     * Every request to a path carrying {@code ;}: the method, the path, the request headers ({@code null} for none),
     * then the status line, {@code Content-Type} and exact body the listener answers with.
     */
    private static Stream<Arguments> requestsToPathsCarryingSemicolons() {
        Stream<Arguments> everyMethodOnAnAlias = ALIASES.stream().flatMap(path -> METHODS.stream()
                .map(method -> Arguments.of(method, path, null, NOT_FOUND, JSON,
                        "HEAD".equals(method) ? "" : APIKIT_RESOURCE_NOT_FOUND)));
        Stream<Arguments> aliasWhateverTheRequest = Stream.of(
                Arguments.of("GET", "/api/customers;x=1?name=Ramsey", null, NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/api/customers;jsessionid=1", null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/api/items;", null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/api/items;x=1?quantity=abc", null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/api/items;x=1?quantity=5&operator=LESS_THAN", null, NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("get", "/api/items;x=1", null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/api/opportunities;x=1?title=t", Map.of("Accept", "text/xml"), NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("OPTIONS", "/api/opportunities;x=1", preFlight("GET"), NOT_FOUND, JSON,
                        APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/api/opportunities;x=1/", null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/api/console;x=1", null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND));
        Stream<Arguments> outsideTheBasePath = Stream.of(
                Arguments.of("GET", "/api;x=1/items", null, NOT_FOUND, "text/plain",
                        "No listener for endpoint: /api;x=1/items"));
        return Stream.of(everyMethodOnAnAlias, aliasWhateverTheRequest, outsideTheBasePath)
                .flatMap(requests -> requests);
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("requestsToPathsCarryingSemicolons")
    void semicolonIsContentOfItsPathSegmentAndStartsNoSearch(String method, String path, Map<String, String> headers,
                                                             String expectedStatusLine, String expectedContentType,
                                                             String expectedBody) {
        HttpTestClient.Response response = HttpTestClient.localhost(port).send(method, path, headers, null);

        assertEquals(expectedStatusLine, response.statusLine(), response::toString);
        assertEquals(expectedContentType, response.header("Content-Type"), response::toString);
        assertArrayEquals(expectedBody.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
        verifyNoInteractions(searchService);
    }

    /** Each search resource requested without {@code ;} in its path, and the search the service must receive. */
    private static Stream<Arguments> requestsToTheResources() {
        return Stream.of(
                Arguments.of("/api/customers?name=a;b", Search.CUSTOMERS, "a;b", 0),
                Arguments.of("/api/items", Search.ITEMS, "GREATER_THAN", 0),
                Arguments.of("/api/items?quantity=5&operator=LESS_THAN", Search.ITEMS, "LESS_THAN", 5),
                Arguments.of("/api/opportunities?title=x;y", Search.OPPORTUNITIES, "x;y", 0));
    }

    @ParameterizedTest(name = "GET {0}")
    @MethodSource("requestsToTheResources")
    void resourceItselfStillSearches(String path, Search search, String text, int quantity) throws Exception {
        when(searchService.getCustomers(text)).thenReturn(SEARCH_RESULT);
        when(searchService.getItems(quantity, text)).thenReturn(SEARCH_RESULT);
        when(searchService.getOpportunities(text)).thenReturn(SEARCH_RESULT);

        HttpTestClient.Response response = HttpTestClient.localhost(port).get(path);

        assertEquals(OK, response.statusLine(), response::toString);
        assertEquals(JSON, response.header("Content-Type"), response::toString);
        assertArrayEquals(SEARCH_RESULT.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
        switch (search) {
            case CUSTOMERS -> verify(searchService).getCustomers(text);
            case ITEMS -> verify(searchService).getItems(quantity, text);
            case OPPORTUNITIES -> verify(searchService).getOpportunities(text);
        }
        verifyNoMoreInteractions(searchService);
    }

    /** The search each resource starts. */
    private enum Search {
        CUSTOMERS, ITEMS, OPPORTUNITIES
    }

    /** The headers of a CORS pre-flight asking for {@code requestedMethod}. */
    private static Map<String, String> preFlight(String requestedMethod) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", "http://example.com");
        headers.put("Access-Control-Request-Method", requestedMethod);
        return headers;
    }
}
