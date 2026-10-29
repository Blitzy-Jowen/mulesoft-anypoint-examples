/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.get_customer_list_from_netsuite.client.UpstreamSystem;
import com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamRateLimitException;
import com.mulesoft.examples.get_customer_list_from_netsuite.exception.UpstreamUnavailableException;
import com.mulesoft.examples.get_customer_list_from_netsuite.service.NetsuiteCustomerService;
import com.mulesoft.examples.get_customer_list_from_netsuite.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the {@code /customers} listener of
 * {@code get-customer-list-from-netsuiteFlow} (get-customer-list-from-netsuite/src/main/app/
 * get-customer-list-from-netsuite.xml:5,8). The listener declares no {@code allowedMethods} and applies no CORS
 * policy, so a CORS pre-flight ({@code OPTIONS} with {@code Origin} and {@code Access-Control-Request-Method}) runs
 * the flow once and is answered exactly as a plain {@code OPTIONS} request to the same target is, whatever method it
 * asks for: the customer page, the filter rejection's empty 200 without {@code Content-Type}, or the 502, 429, 503 or
 * 500 of a failed NetSuite call; never Spring's 403 {@code Invalid CORS request}, and with no
 * {@code Access-Control-*} or {@code Vary} header. A pre-flight to a path the listener does not serve,
 * {@code /customers} with {@code ;} segment parameters included, gets the no-listener 404 of a plain {@code OPTIONS}
 * request and runs no flow.
 *
 * <p>The cases start the whole application on Undertow at a random port, so {@link PreflightDispatchConfig} and
 * {@link ListenerPathConfig} are the ones component scanning registers, beside the real controller and exception
 * handlers, with
 * {@link NetsuiteCustomerService} mocked: every flow run is recorded with the {@code lastName} it received and no
 * NetSuite call is made. Requests go through ST-1 {@link HttpTestClient}, so the status line, headers and body
 * bytes are asserted as the listener writes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreflightDispatchListenerTest {

    private static final String ORIGIN = "https://example.invalid";

    /** The page the mocked flow renders; non-ASCII, so its UTF-8 bytes are asserted too. */
    private static final String PAGE = "<h1>Netsuite Customer List</h1><td>\u00c6gir</td>";

    private static final String TEXT_HTML_UTF8 = "text/html;charset=UTF-8";

    private static final String APPLICATION_JSON = "application/json";

    @MockBean
    private NetsuiteCustomerService service;

    @LocalServerPort
    private int port;

    /** The {@code lastName} a request target carries, as the controller hands it to the flow. */
    private enum LastName {

        /** The original test's prefix: the flow renders the page. */
        PRESENT("/customers?lastName=a", "a", Optional.of(PAGE)),

        /** An empty value passes the filter ({@code != null}) and the flow renders the page. */
        EMPTY("/customers?lastName=", "", Optional.of(PAGE)),

        /** No query: the filter rejects the request. */
        ABSENT("/customers", null, Optional.empty()),

        /** A bare name without {@code =} is {@code null} as well: the filter rejects the request. */
        BARE("/customers?lastName", null, Optional.empty());

        private final String target;
        private final String argument;
        private final Optional<String> page;

        LastName(String target, String argument, Optional<String> page) {
            this.target = target;
            this.argument = argument;
            this.page = page;
        }
    }

    /** {@code lastName} case, requested method of the pre-flight, then its {@code Access-Control-Request-Headers}. */
    private static Stream<Arguments> preflights() {
        return Stream.of(LastName.values()).flatMap(lastName -> Stream.of(
                Arguments.of(lastName, "GET", null),
                Arguments.of(lastName, "POST", null),
                Arguments.of(lastName, "PUT", null),
                Arguments.of(lastName, "DELETE", null),
                Arguments.of(lastName, "PATCH", null),
                Arguments.of(lastName, "OPTIONS", null),
                Arguments.of(lastName, "POST", "content-type")));
    }

    @ParameterizedTest(name = "{0}: Access-Control-Request-Method {1}, Access-Control-Request-Headers {2}")
    @MethodSource("preflights")
    void preflightRunsTheFlowOnceLikeAPlainOptionsRequest(LastName lastName, String requestedMethod,
                                                          String requestedHeaders) {
        when(service.customerListPage(lastName.argument)).thenReturn(lastName.page);
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", lastName.target, null, null);
        verify(service).customerListPage(lastName.argument);
        clearInvocations(service);

        HttpTestClient.Response preflight = client.send("OPTIONS", lastName.target,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        verify(service).customerListPage(lastName.argument);
        verifyNoMoreInteractions(service);
        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 200 OK", preflight.statusLine(), preflight::toString);
        if (lastName.page.isPresent()) {
            assertEquals(TEXT_HTML_UTF8, preflight.header("Content-Type"), preflight::toString);
            assertArrayEquals(PAGE.getBytes(StandardCharsets.UTF_8), preflight.body(), preflight::toString);
        } else {
            assertNull(preflight.header("Content-Type"), preflight::toString);
            assertEquals("0", preflight.header("Content-Length"), preflight::toString);
            assertArrayEquals(new byte[0], preflight.body(), preflight::toString);
        }
        assertNoCorsHeaders(preflight);
    }

    /**
     * Failure the mocked flow raises for {@code lastName=a}, then the status line, {@code Retry-After} and body the
     * project's exception handlers answer it with, as {@code application/json}.
     */
    private static Stream<Arguments> failedFlows() {
        return Stream.of(
                Arguments.of(new UpstreamAuthenticationException(UpstreamSystem.NETSUITE, "Invalid login attempt.",
                                null),
                        "HTTP/1.1 502 Bad Gateway", null,
                        "{ \"message\": \"Invalid login attempt.\", \"upstream\": \"NetSuite\" }"),
                Arguments.of(new UpstreamRateLimitException(UpstreamSystem.NETSUITE, "Request limit exceeded.", "30",
                                null),
                        "HTTP/1.1 429 Too Many Requests", "30",
                        "{ \"message\": \"Request limit exceeded.\", \"upstream\": \"NetSuite\" }"),
                Arguments.of(new UpstreamUnavailableException(UpstreamSystem.NETSUITE, "NetSuite is unreachable",
                                null),
                        "HTTP/1.1 503 Service Unavailable", null,
                        "{ \"message\": \"NetSuite is unreachable\", \"upstream\": \"NetSuite\" }"),
                Arguments.of(new IllegalStateException("netsuite.account is not configured"),
                        "HTTP/1.1 500 Internal Server Error", null,
                        "{ \"message\": \"netsuite.account is not configured\" }"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("failedFlows")
    void preflightWhoseFlowFailsGetsTheFailureAnswerOfAPlainOptionsRequest(RuntimeException failure,
                                                                           String statusLine, String retryAfter,
                                                                           String body) {
        when(service.customerListPage(LastName.PRESENT.argument)).thenThrow(failure);
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", LastName.PRESENT.target, null, null);
        verify(service).customerListPage(LastName.PRESENT.argument);
        clearInvocations(service);

        HttpTestClient.Response preflight = client.send("OPTIONS", LastName.PRESENT.target,
                preflightHeaders("GET", null), null);

        verify(service).customerListPage(LastName.PRESENT.argument);
        verifyNoMoreInteractions(service);
        assertSameResponse(plain, preflight);
        assertEquals(statusLine, preflight.statusLine(), preflight::toString);
        assertEquals(APPLICATION_JSON, preflight.header("Content-Type"), preflight::toString);
        assertEquals(retryAfter, preflight.header("Retry-After"), preflight::toString);
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
    }

    /**
     * Request target the listener does not serve, the path its 404 names, then the requested method. The targets
     * include the listener's path with {@code ;} segment parameters, which {@link ListenerPathConfig} refuses after
     * the pre-flight has selected the {@code OPTIONS} handler.
     */
    private static Stream<Arguments> preflightsToUnservedPaths() {
        return Stream.of("/customers/", "/unknown?lastName=a", "/customers;x=1?lastName=a")
                .flatMap(target -> Stream.of("GET", "POST")
                        .map(method -> Arguments.of(target, target.split("\\?", 2)[0], method)));
    }

    @ParameterizedTest(name = "{0}: Access-Control-Request-Method {2}")
    @MethodSource("preflightsToUnservedPaths")
    void preflightToAnUnservedPathGetsTheNoListener404AndRunsNoFlow(String target, String path,
                                                                     String requestedMethod) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", target, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", target, preflightHeaders(requestedMethod, null),
                null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals(("No listener for endpoint: " + path).getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(service);
    }

    private static Map<String, String> preflightHeaders(String requestedMethod, String requestedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", ORIGIN);
        headers.put("Access-Control-Request-Method", requestedMethod);
        if (requestedHeaders != null) {
            headers.put("Access-Control-Request-Headers", requestedHeaders);
        }
        return headers;
    }

    /** Same status line, same body bytes and the same headers but {@code Date}, compared as sorted lines. */
    private static void assertSameResponse(HttpTestClient.Response expected, HttpTestClient.Response actual) {
        assertEquals(expected.statusLine(), actual.statusLine(), actual::toString);
        assertArrayEquals(expected.body(), actual.body(), actual::toString);
        assertEquals(headerLines(expected), headerLines(actual), actual::toString);
    }

    /** Every header but {@code Date} as {@code name: value} with the name lower-cased, sorted, duplicates kept. */
    private static List<String> headerLines(HttpTestClient.Response response) {
        return response.headers().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("Date"))
                .map(header -> header.getKey().toLowerCase(Locale.ROOT) + ": " + header.getValue())
                .sorted()
                .toList();
    }

    private static void assertNoCorsHeaders(HttpTestClient.Response response) {
        assertTrue(response.headers().stream().map(Map.Entry::getKey).noneMatch(name ->
                        name.regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length())
                                || name.equalsIgnoreCase("Vary")),
                response::toString);
    }
}
