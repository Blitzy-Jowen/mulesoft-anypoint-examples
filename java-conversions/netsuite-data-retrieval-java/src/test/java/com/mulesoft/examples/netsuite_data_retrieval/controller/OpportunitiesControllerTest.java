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

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * Wire-level test of {@link OpportunitiesController}, the RAML resource {@code /opportunities} of
 * {@code api/netsuite-api.raml} (lines 37-48). The original APIkit router mapped it to the flow
 * {@code get:/oportunities:netsuite-api-config} (netsuite-data-retrieval/src/main/app/netsuite-api.xml:16,
 * 79-106), whose name is misspelt; the RAML path governs, so {@code /api/oportunities} is not an endpoint.
 *
 * <p>The whole application runs on Undertow on a random port with {@link NetsuiteSearchService} mocked, so no
 * NetSuite host or credential is involved. Every request but {@code HEAD} goes through ST-1 {@link HttpTestClient},
 * so the status code, Undertow's reason phrase, {@code Content-Type} and the body bytes are asserted as written to
 * the wire:
 * <ul>
 *   <li>the {@code title} query parameter reaches the service decoded and unchanged ({@code null} when absent,
 *       {@code ""} when empty), and the service's text is returned byte for byte with 200. The committed RAML
 *       example carries raw line breaks inside its {@code addrText} values, so it is not strict JSON and is only
 *       ever compared as bytes;</li>
 *   <li>a repeated {@code title}, a method the RAML does not declare, an {@code Accept} header excluding JSON and
 *       a path no resource serves under {@code /api} get APIkit's 400, 405, 406 and 404 mappings with their
 *       literal bodies (netsuite-api.xml:107-133), and the service is never called;</li>
 *   <li>the three approved upstream failure modes answer 502, 429 and 503 through {@code UpstreamExceptionHandler}
 *       ahead of {@code GlobalExceptionHandler}'s catch-all, whose 500 answers any other service failure.</li>
 * </ul>
 *
 * <p>The listener's no-listener 404 outside {@code /api} is asserted once, by {@code CustomersControllerTest}.
 * The context configuration (profile {@code test}, the single {@code @MockBean}) is the one
 * {@code CustomersControllerTest} and {@code ItemsControllerTest} declare, so the three share one cached
 * application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpportunitiesControllerTest {

    private static final String OPPORTUNITIES = "/api/opportunities";

    /** The committed RAML example of {@code GET /opportunities}, the service's rendering of the recorded record. */
    private static final String OPPORTUNITIES_EXAMPLE = "api/opportunities-response.json";

    /**
     * The committed opportunity's {@code title}, also {@code nets.opportunity.title} of {@code application-test.yml}.
     */
    private static final String COMMITTED_TITLE = "Short wave";

    /** Body of APIkit's 404 mapping (netsuite-api.xml:111). */
    private static final String RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    /** Body of APIkit's 405 mapping (netsuite-api.xml:116). */
    private static final String METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    /** Body of APIkit's 406 mapping (netsuite-api.xml:126). */
    private static final String NOT_ACCEPTABLE = "{ \"message\": \"Not acceptable\" }";

    /** Body of APIkit's 400 mapping (netsuite-api.xml:131). */
    private static final String BAD_REQUEST = "{ \"message\": \"Bad request\" }";

    /** The {@code title} the upstream failure-mode tests send. */
    private static final String FAILING_TITLE = "x";

    @MockBean
    private NetsuiteSearchService searchService;

    @LocalServerPort
    private int port;

    private HttpTestClient client;

    @BeforeEach
    void createClient() {
        client = HttpTestClient.localhost(port);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Happy path and parameter binding
    // ---------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "GET " + OPPORTUNITIES + "?title=Short%20wave with Accept {0} -> 200 example bytes")
    @NullSource
    @ValueSource(strings = {MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE})
    void getOpportunitiesReturnsServiceTextUnchanged(String accept) throws Exception {
        byte[] expected = new ClassPathResource(OPPORTUNITIES_EXAMPLE).getContentAsByteArray();
        String expectedText = new String(expected, StandardCharsets.UTF_8);
        // Guards the premise of the byte comparison: the example's addrText values hold raw line feeds, which a
        // JSON parser would reject and a re-serialization would escape.
        assertTrue(expectedText.contains("\"addrText\": \"Williams Electronics and Communications\n"),
                () -> OPPORTUNITIES_EXAMPLE + " no longer carries its raw addrText line breaks");
        when(searchService.getOpportunities(COMMITTED_TITLE)).thenReturn(expectedText);

        HttpTestClient.Response response = client.get(OPPORTUNITIES + "?title=Short%20wave",
                accept == null ? null : Map.of("Accept", accept));

        assertStatus(response, 200, "OK");
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertArrayEquals(expected, response.body(), response::toString);
        verify(searchService).getOpportunities(COMMITTED_TITLE);
        verifyNoMoreInteractions(searchService);
    }

    @Test
    void absentTitleReachesServiceAsNull() throws Exception {
        when(searchService.getOpportunities(any())).thenReturn("[]");

        HttpTestClient.Response response = client.get(OPPORTUNITIES);

        assertStatus(response, 200, "OK");
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertBody(response, "[]");
        verify(searchService).getOpportunities(isNull());
        verifyNoMoreInteractions(searchService);
    }

    /**
     * Each query string and the {@code title} value the service must receive: an empty value stays {@code ""},
     * which the flow's {@code title != null} test treats as a criterion, and percent-encoding is decoded without
     * any escaping, which belongs to the service's SuiteQL literal.
     */
    private static Stream<Arguments> titleBindings() {
        return Stream.of(
                Arguments.of("title=", ""),
                Arguments.of("title=O%27Neil", "O'Neil"));
    }

    @ParameterizedTest(name = "GET " + OPPORTUNITIES + "?{0} -> getOpportunities(\"{1}\")")
    @MethodSource("titleBindings")
    void titleReachesServiceDecodedAndUnchanged(String query, String expectedTitle) throws Exception {
        when(searchService.getOpportunities(expectedTitle)).thenReturn("[]");

        HttpTestClient.Response response = client.get(OPPORTUNITIES + "?" + query);

        assertStatus(response, 200, "OK");
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertBody(response, "[]");
        verify(searchService).getOpportunities(expectedTitle);
        verifyNoMoreInteractions(searchService);
    }

    // ---------------------------------------------------------------------------------------------------------
    // APIkit mappings (SC-3): the request never reaches the service
    // ---------------------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "GET " + OPPORTUNITIES + "?{0} -> APIkit 400")
    @ValueSource(strings = {"title=a&title=b", "title=Short%20wave&title=Short%20wave"})
    void repeatedTitleReturnsApiKit400(String query) {
        HttpTestClient.Response response = client.get(OPPORTUNITIES + "?" + query);

        assertApiKitError(response, 400, "Bad Request", BAD_REQUEST);
        verifyNoInteractions(searchService);
    }

    /** Each method the RAML does not declare for {@code /opportunities}, with the body it is sent with. */
    private static Stream<Arguments> undeclaredMethods() {
        byte[] json = "{}".getBytes(StandardCharsets.UTF_8);
        return Stream.of(
                Arguments.of("POST", json),
                Arguments.of("PUT", json),
                Arguments.of("PATCH", json),
                Arguments.of("DELETE", null),
                Arguments.of("OPTIONS", null));
    }

    @ParameterizedTest(name = "{0} " + OPPORTUNITIES + " -> APIkit 405")
    @MethodSource("undeclaredMethods")
    void undeclaredMethodReturnsApiKit405(String method, byte[] body) {
        Map<String, String> headers = body == null ? null : Map.of("Content-Type", MediaType.APPLICATION_JSON_VALUE);

        HttpTestClient.Response response = client.send(method, OPPORTUNITIES, headers, body);

        assertApiKitError(response, 405, "Method Not Allowed", METHOD_NOT_ALLOWED);
        verifyNoInteractions(searchService);
    }

    /**
     * HEAD goes through the JDK client, which reads no body for it, because the 405 announces the
     * {@code Content-Length} of a body a HEAD response never carries; HTTP/1.1 is forced so no h2c upgrade is
     * attempted.
     */
    @Test
    void headReturns405() throws IOException, InterruptedException {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(HttpTestClient.TIMEOUT_MILLIS))
                .build();
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + OPPORTUNITIES + "?title=Short%20wave"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofMillis(HttpTestClient.TIMEOUT_MILLIS))
                .build();

        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());

        assertEquals(405, response.statusCode(), () -> response.headers().map().toString());
        verifyNoInteractions(searchService);
    }

    @ParameterizedTest(name = "GET " + OPPORTUNITIES + "?title=x with Accept {0} -> APIkit 406")
    @ValueSource(strings = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_XML_VALUE})
    void acceptExcludingJsonReturnsApiKit406(String accept) {
        HttpTestClient.Response response = client.get(OPPORTUNITIES + "?title=x", Map.of("Accept", accept));

        assertApiKitError(response, 406, "Not Acceptable", NOT_ACCEPTABLE);
        verifyNoInteractions(searchService);
    }

    /**
     * {@code /api/foo} is a resource the RAML never declares; {@code /api/oportunities} is the misspelt name of the
     * original flow, which the RAML path {@code /opportunities} overrides, so neither is served.
     */
    @ParameterizedTest(name = "GET {0} -> APIkit 404")
    @ValueSource(strings = {"/api/foo", "/api/oportunities"})
    void undeclaredResourceUnderApiReturnsApiKit404(String path) {
        HttpTestClient.Response response = client.get(path);

        assertApiKitError(response, 404, "Not Found", RESOURCE_NOT_FOUND);
        verifyNoInteractions(searchService);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Approved upstream failure modes (SC-9) and the unhandled-exception 500 (SC-1)
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Each upstream failure the service can raise: a label, the exception, the status with Undertow's reason
     * phrase, the exact body, and the {@code Retry-After} values the response must carry.
     */
    private static Stream<Arguments> upstreamFailures() {
        String unknownHost = "nonexistent.suitetalk.api.netsuite.com";
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
                Arguments.of("DNS failure",
                        new UpstreamUnavailableException(UpstreamSystem.NETSUITE, unknownHost,
                                new UnknownHostException(unknownHost)),
                        503, "Service Unavailable",
                        "{ \"message\": \"" + unknownHost + "\", \"upstream\": \"NetSuite\" }",
                        List.of()));
    }

    @ParameterizedTest(name = "{0} -> {2} {3}")
    @MethodSource("upstreamFailures")
    void upstreamFailureModeIsAnsweredOnceWithoutRetry(String label, RuntimeException failure, int status,
                                                       String reason, String body, List<String> retryAfter)
            throws Exception {
        when(searchService.getOpportunities(FAILING_TITLE)).thenThrow(failure);

        HttpTestClient.Response response = client.get(OPPORTUNITIES + "?title=" + FAILING_TITLE);

        assertStatus(response, status, reason);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertBody(response, body);
        assertEquals(retryAfter, response.headers("Retry-After"), response::toString);
        // The executor behind the service owns the single re-authentication retry; the controller adds none.
        verify(searchService, times(1)).getOpportunities(FAILING_TITLE);
        verifyNoMoreInteractions(searchService);
    }

    /**
     * An unresolved select {@code typeId} of the record-shape mapping (AAP 0.7.4) is not an APIkit or upstream
     * case.
     */
    @Test
    void unhandledServiceFailureReturnsGlobal500() throws Exception {
        when(searchService.getOpportunities(FAILING_TITLE))
                .thenThrow(new IllegalStateException("No select source ID for custom field custentity8"));

        HttpTestClient.Response response = client.get(OPPORTUNITIES + "?title=" + FAILING_TITLE);

        assertStatus(response, 500, "Internal Server Error");
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertBody(response, "{ \"message\": \"No select source ID for custom field custentity8\" }");
        verify(searchService, times(1)).getOpportunities(FAILING_TITLE);
        verifyNoMoreInteractions(searchService);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Assertions
    // ---------------------------------------------------------------------------------------------------------

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
