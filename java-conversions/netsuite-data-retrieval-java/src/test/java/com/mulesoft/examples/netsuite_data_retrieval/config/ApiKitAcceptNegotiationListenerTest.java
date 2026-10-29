/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.invocation.Invocation;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.mulesoft.examples.netsuite_data_retrieval.service.NetsuiteSearchService;
import com.mulesoft.examples.netsuite_data_retrieval.support.HttpTestClient;

/**
 * Wire-level test of {@link ApiKitAcceptNegotiator} on the {@code /api/*} listener of {@code api/netsuite-api.raml}:
 * APIkit's router negotiated the response of the three GETs, whose success responses all declare an
 * {@code application/json} body, and answered an {@code Accept} header for which it chose no declared type with its
 * 406 mapping {@code { "message": "Not acceptable" }} (netsuite-data-retrieval/src/main/app/netsuite-api.xml:123-127),
 * after it validated the query parameters and before the flow, and so before any NetSuite search, ran.
 *
 * <p>The cases assert that:
 * <ul>
 *   <li>every form that gives JSON quality 0 gets that 406 on each GET, and the search service is never called;</li>
 *   <li>a query parameter APIkit rejected (not an integer, outside the enumeration, empty or repeated) gets its 400
 *       {@code { "message": "Bad request" }} with each of those forms, exactly as without {@code Accept}, because
 *       the query parameter validation preceded negotiation, and no search runs;</li>
 *   <li>the forms APIkit accepted, {@code *}{@code /*;q=0} among them, get the service's JSON with 200 after one
 *       search;</li>
 *   <li>the methods the RAML does not declare, unserved paths, the console and the RAML descriptor get with
 *       {@code application/json;q=0} exactly what they get without it, and no search runs.</li>
 * </ul>
 *
 * <p>The whole application runs on Undertow on a random port with {@link NetsuiteSearchService} mocked, so no
 * NetSuite host or credential is involved; the context configuration (profile {@code test}, the single
 * {@code @MockBean}) is the one the controller tests declare, so they share one cached application context. Requests
 * go through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the listener
 * writes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiKitAcceptNegotiationListenerTest {

    /** The body of APIkit's 406 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_NOT_ACCEPTABLE = "{ \"message\": \"Not acceptable\" }";

    /** The body of APIkit's 405 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    /** The body of APIkit's 404 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    /** The body of APIkit's 400 mapping, as the original flow's exception strategy wrote it. */
    private static final String APIKIT_BAD_REQUEST = "{ \"message\": \"Bad request\" }";

    /** What the mocked search returns, and so what an accepted GET answers. */
    private static final String SEARCH_RESULT = "[\n  {\n    \"internalId\": \"1\"\n  }\n]";

    private static final String OK = "HTTP/1.1 200 OK";

    private static final String NOT_ACCEPTABLE = "HTTP/1.1 406 Not Acceptable";

    private static final String NOT_FOUND = "HTTP/1.1 404 Not Found";

    private static final String BAD_REQUEST = "HTTP/1.1 400 Bad Request";

    private static final String METHOD_NOT_ALLOWED = "HTTP/1.1 405 Method Not Allowed";

    private static final String JSON = "application/json";

    private static final String RAML = "application/raml+yaml";

    private static final String JSON_Q0 = "application/json;q=0";

    /** The three GETs, each with the search method its flow runs. */
    private static final Map<String, String> NEGOTIATED_GETS = Map.of(
            "/api/customers", "getCustomers",
            "/api/items", "getItems",
            "/api/opportunities", "getOpportunities");

    /** {@code Accept} header lines for which APIkit chose no type: JSON at quality 0, however combined. */
    private static final List<List<String>> NOT_ACCEPTED = List.of(
            List.of(JSON_Q0),
            List.of("text/xml, application/json;q=0"),
            List.of("application/*;q=0"),
            List.of("application/json;q=0.0"),
            List.of("application/json;q=0, */*"),
            List.of("application/json;q=0, application/json"),
            List.of("APPLICATION/JSON"),
            List.of("text/xml", JSON_Q0));

    /** {@code Accept} header lines for which APIkit chose JSON, quality-0 ranges it outweighed included. */
    private static final List<List<String>> ACCEPTED = List.of(
            List.of("*/*;q=0"),
            List.of("*;q=0"),
            List.of("application/json;q=0.5"),
            List.of("application/json;q=0.001"),
            List.of("application/json, application/json;q=0"),
            List.of("application/json;charset=UTF-8"),
            List.of("*/*"),
            List.of(""),
            List.of("text/xml", JSON));

    @MockBean
    private NetsuiteSearchService searchService;

    @LocalServerPort
    private int port;

    private HttpTestClient client;

    @BeforeEach
    void createClient() {
        client = HttpTestClient.localhost(port);
    }

    /** Each GET with each header that APIkit did not accept. */
    private static Stream<Arguments> notAcceptedRequests() {
        return NEGOTIATED_GETS.keySet().stream().sorted()
                .flatMap(path -> NOT_ACCEPTED.stream().map(accept -> Arguments.of(path, accept)));
    }

    @ParameterizedTest(name = "GET {0} with Accept {1} -> APIkit 406, no search")
    @MethodSource("notAcceptedRequests")
    void jsonAtQualityZeroGetsApiKitNotAcceptableWithoutSearching(String path, List<String> acceptLines) {
        HttpTestClient.Response response = client.sendHeaderPairs("GET", path, accept(acceptLines), null);

        assertEquals(NOT_ACCEPTABLE, response.statusLine(), response::toString);
        assertEquals(JSON, response.header("Content-Type"), response::toString);
        assertArrayEquals(bytes(APIKIT_NOT_ACCEPTABLE), response.body(), response::toString);
        verifyNoInteractions(searchService);
    }

    /**
     * Each GET with a query string APIkit's parameter validation rejected, with each header that APIkit did not
     * accept.
     */
    private static Stream<Arguments> invalidParameterRequests() {
        return Stream.of("/api/items?quantity=abc", "/api/items?quantity=", "/api/items?quantity=0x10",
                        "/api/items?operator=INVALID", "/api/items?operator=", "/api/customers?name=a&name=b",
                        "/api/opportunities?title=a&title=b", "/api/items?quantity=1&quantity=2")
                .flatMap(target -> NOT_ACCEPTED.stream().map(accept -> Arguments.of(target, accept)));
    }

    @ParameterizedTest(name = "GET {0} with Accept {1} -> APIkit 400, as without Accept, no search")
    @MethodSource("invalidParameterRequests")
    void invalidQueryParameterGetsApiKitBadRequestBeforeNegotiation(String target, List<String> acceptLines) {
        HttpTestClient.Response withoutAccept = client.sendHeaderPairs("GET", target, List.of(), null);
        HttpTestClient.Response response = client.sendHeaderPairs("GET", target, accept(acceptLines), null);

        assertEquals(BAD_REQUEST, withoutAccept.statusLine(), withoutAccept::toString);
        assertEquals(JSON, withoutAccept.header("Content-Type"), withoutAccept::toString);
        assertArrayEquals(bytes(APIKIT_BAD_REQUEST), withoutAccept.body(), withoutAccept::toString);
        assertEquals(withoutAccept.statusLine(), response.statusLine(), response::toString);
        assertArrayEquals(withoutAccept.body(), response.body(), response::toString);
        assertEquals(headerLines(withoutAccept), headerLines(response), response::toString);
        verifyNoInteractions(searchService);
    }

    /** Each GET with its search method and each header that APIkit accepted. */
    private static Stream<Arguments> acceptedRequests() {
        return NEGOTIATED_GETS.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .flatMap(get -> ACCEPTED.stream().map(accept -> Arguments.of(get.getKey(), get.getValue(), accept)));
    }

    @ParameterizedTest(name = "GET {0} with Accept {2} -> 200 after {1}")
    @MethodSource("acceptedRequests")
    void acceptedFormsGetTheSearchResult(String path, String searchMethod, List<String> acceptLines)
            throws Exception {
        when(searchService.getCustomers(isNull())).thenReturn(SEARCH_RESULT);
        when(searchService.getItems(0, "GREATER_THAN")).thenReturn(SEARCH_RESULT);
        when(searchService.getOpportunities(isNull())).thenReturn(SEARCH_RESULT);

        HttpTestClient.Response response = client.sendHeaderPairs("GET", path, accept(acceptLines), null);

        assertEquals(OK, response.statusLine(), response::toString);
        assertEquals(JSON, response.header("Content-Type"), response::toString);
        assertArrayEquals(bytes(SEARCH_RESULT), response.body(), response::toString);
        List<String> searches = mockingDetails(searchService).getInvocations().stream()
                .map(Invocation::getMethod)
                .map(Method::getName)
                .toList();
        assertEquals(List.of(searchMethod), searches);
    }

    /**
     * Requests APIkit did not negotiate: the method, the path, the {@code Accept} value sent without and then with
     * {@code application/json;q=0} appended ({@code null} for none), then the status line, {@code Content-Type} and
     * exact body both get, or {@code null} where the body is only compared between the two.
     */
    private static Stream<Arguments> requestsNotNegotiated() {
        Stream<Arguments> undeclaredMethods = Stream.of(
                Arguments.of("OPTIONS", "/api/customers", null, METHOD_NOT_ALLOWED, JSON, APIKIT_METHOD_NOT_ALLOWED),
                Arguments.of("HEAD", "/api/items", null, METHOD_NOT_ALLOWED, JSON, ""),
                Arguments.of("POST", "/api/opportunities", null, METHOD_NOT_ALLOWED, JSON,
                        APIKIT_METHOD_NOT_ALLOWED));
        Stream<Arguments> unservedPaths = Stream.of(
                Arguments.of("GET", "/api/unknown", null, NOT_FOUND, JSON, APIKIT_RESOURCE_NOT_FOUND),
                Arguments.of("GET", "/other", null, NOT_FOUND, "text/plain", "No listener for endpoint: /other"),
                Arguments.of("GET", "/apix", null, NOT_FOUND, "text/plain", "No listener for endpoint: /apix"));
        Stream<Arguments> console = Stream.of(
                Arguments.of("GET", "/api/", RAML, OK, RAML, null),
                Arguments.of("GET", "/api/console/?raml", null, OK, RAML, null),
                Arguments.of("GET", "/api/console", null, "HTTP/1.1 301 Moved Permanently", null, ""),
                Arguments.of("GET", "/api/console/", null, OK, "text/html", null));
        return Stream.of(undeclaredMethods, unservedPaths, console).flatMap(requests -> requests);
    }

    @ParameterizedTest(name = "{0} {1} with Accept {2} and application/json;q=0")
    @MethodSource("requestsNotNegotiated")
    void requestNotNegotiatedIsAnsweredAsWithoutQualityZero(String method, String path, String acceptPrefix,
                                                            String expectedStatusLine, String expectedContentType,
                                                            String expectedBody) {
        String withQualityZero = acceptPrefix == null ? JSON_Q0 : acceptPrefix + ", " + JSON_Q0;

        HttpTestClient.Response plain = client.sendHeaderPairs(method, path,
                acceptPrefix == null ? List.of() : accept(List.of(acceptPrefix)), null);
        HttpTestClient.Response response = client.sendHeaderPairs(method, path, accept(List.of(withQualityZero)),
                null);

        assertEquals(expectedStatusLine, plain.statusLine(), plain::toString);
        assertEquals(expectedContentType, plain.header("Content-Type"), plain::toString);
        if (expectedBody != null) {
            assertArrayEquals(bytes(expectedBody), plain.body(), plain::toString);
        }
        assertEquals(plain.statusLine(), response.statusLine(), response::toString);
        assertArrayEquals(plain.body(), response.body(), response::toString);
        assertEquals(headerLines(plain), headerLines(response), response::toString);
        verifyNoInteractions(searchService);
    }

    /** One {@code Accept} header line per element of {@code lines}. */
    private static List<Map.Entry<String, String>> accept(List<String> lines) {
        return lines.stream()
                .<Map.Entry<String, String>>map(line -> new AbstractMap.SimpleImmutableEntry<>("Accept", line))
                .toList();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Every header but {@code Date} as {@code name: value} with the name lower-cased, sorted, duplicates kept. */
    private static List<String> headerLines(HttpTestClient.Response response) {
        return response.headers().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("Date"))
                .map(header -> header.getKey().toLowerCase(Locale.ROOT) + ": " + header.getValue())
                .sorted()
                .toList();
    }
}
