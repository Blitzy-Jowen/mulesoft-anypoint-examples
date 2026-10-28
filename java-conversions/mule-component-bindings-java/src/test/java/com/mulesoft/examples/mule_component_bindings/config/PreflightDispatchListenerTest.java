/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import com.mulesoft.examples.mule_component_bindings.service.StockStatsService;
import com.mulesoft.examples.mule_component_bindings.service.TwitterSearchService;
import com.mulesoft.examples.mule_component_bindings.support.HttpTestClient;
import com.mulesoft.examples.mule_component_bindings.support.PortInUseRetryingContextLoader;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on both listeners of the application: a CORS pre-flight gets
 * exactly the response a plain {@code OPTIONS} request to the same port and path gets, never Spring's 403
 * {@code Invalid CORS request}, and carries no {@code Access-Control-*} or {@code Vary} header, whatever method or
 * headers it asks for, because neither original listener applied a CORS policy
 * (mule-component-bindings/src/main/app/mule-component-bindings.xml:31-54).
 *
 * <ul>
 *   <li>Twitter listener ({@code localhost:8081 /}, no {@code allowedMethods}, xml:31-36): the pre-flight runs the
 *       listener flow, 200 with its payload, as every method does.</li>
 *   <li>Stock-statistics listener ({@code localhost:8180 /api/*}, xml:40-54): {@code /api/stockStats} keeps the
 *       {@code OPTIONS} answer its {@code GET} resource mapping gives a plain request, 200 with
 *       {@code Allow: GET,HEAD,OPTIONS} and no body, and the stock statistics are never computed, a requested
 *       {@code GET} included.</li>
 *   <li>A path requested on the listener that does not serve it gets SC-10's no-listener 404 from
 *       {@code PortRoutingFilter}, and an unserved path on the stock-statistics listener gets SC-1's no-listener 404,
 *       as a plain {@code OPTIONS} request does.</li>
 * </ul>
 *
 * <p>The whole application runs on Undertow under profile {@code test}, with SC-10's routing in force: the main
 * listener on a random port and the stock-statistics listener on a free port drawn for each attempt to load the
 * context, because {@code PortRoutingFilter} tells the listeners apart by their local port;
 * {@link PortInUseRetryingContextLoader} loads the context again when another process takes that port first.
 * {@link TwitterSearchService} and {@link StockStatsService} are mocked, so every run of either flow is recorded and
 * no upstream is called. Requests go through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes
 * are asserted as written.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PreflightDispatchListenerTest {

    private static final String ROOT = "/";

    private static final String STOCK_STATS = "/api/stockStats";

    private static final String UNKNOWN = "/api/unknown";

    private static final String ORIGIN = "https://example.invalid";

    /**
     * The Content-Type of the no-listener 404, the same whether SC-10's {@code PortRoutingFilter} or SC-1's
     * {@code GlobalExceptionHandler} writes it.
     */
    private static final String NO_LISTENER_CONTENT_TYPE = "text/plain";

    /** What the mocked Twitter listener flow returns; the controller writes these bytes unchanged. */
    private static final byte[] LISTENER_PAYLOAD = "Twitter listener flow payload".getBytes(StandardCharsets.UTF_8);

    @MockBean
    private TwitterSearchService twitterSearchService;

    @MockBean
    private StockStatsService stockStatsService;

    @LocalServerPort
    private int mainPort;

    /** Port of this context's stock-statistics listener. */
    @Value("${ports.stock-stats}")
    private int stockStatsPort;

    /** The two listeners: the main (Twitter) one on {@link #mainPort} and the stock-statistics one. */
    enum Listener {
        MAIN,
        STOCK_STATS
    }

    /** The stock-statistics listener ({@code ports.stock-stats}) on a free port drawn for each load attempt. */
    @DynamicPropertySource
    static void stockStatsPort(DynamicPropertyRegistry registry) {
        int drawnPort = TestSocketUtils.findAvailableTcpPort();
        registry.add("ports.stock-stats", () -> drawnPort);
    }

    /** Requested method of the pre-flight, then its {@code Access-Control-Request-Headers}, or {@code null}. */
    private static Stream<Arguments> preflights() {
        return Stream.of(
                Arguments.of("GET", null),
                Arguments.of("POST", null),
                Arguments.of("PUT", null),
                Arguments.of("DELETE", null),
                Arguments.of("PATCH", null),
                Arguments.of("OPTIONS", null),
                Arguments.of("POST", "content-type"));
    }

    /** Listener, a path it does not serve, then every pre-flight of {@link #preflights()}. */
    private static Stream<Arguments> wrongPortPreflights() {
        return Stream.of(Arguments.of(Listener.STOCK_STATS, ROOT), Arguments.of(Listener.MAIN, STOCK_STATS))
                .flatMap(target -> preflights().map(preflight -> Arguments.of(target.get()[0], target.get()[1],
                        preflight.get()[0], preflight.get()[1])));
    }

    @ParameterizedTest(name = "Access-Control-Request-Method {0}, Access-Control-Request-Headers {1}")
    @MethodSource("preflights")
    void preflightToTheTwitterListenerRunsItsFlowLikeAPlainOptionsRequest(String requestedMethod,
                                                                          String requestedHeaders) {
        when(twitterSearchService.searchListenerFlow()).thenReturn(LISTENER_PAYLOAD);
        HttpTestClient client = client(Listener.MAIN);
        HttpTestClient.Response plain = client.send("OPTIONS", ROOT, null, null);
        verify(twitterSearchService, times(1)).searchListenerFlow();

        HttpTestClient.Response preflight = client.send("OPTIONS", ROOT,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 200 OK", preflight.statusLine(), preflight::toString);
        assertEquals(String.valueOf(LISTENER_PAYLOAD.length), preflight.header("Content-Length"),
                preflight::toString);
        assertNull(preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals(LISTENER_PAYLOAD, preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
        verify(twitterSearchService, times(2)).searchListenerFlow();
        verifyNoMoreInteractions(twitterSearchService);
        verifyNoInteractions(stockStatsService);
    }

    @ParameterizedTest(name = "Access-Control-Request-Method {0}, Access-Control-Request-Headers {1}")
    @MethodSource("preflights")
    void preflightToTheStockStatsResourceGetsItsOptionsAnswerLikeAPlainOptionsRequest(String requestedMethod,
                                                                                      String requestedHeaders) {
        HttpTestClient client = client(Listener.STOCK_STATS);
        HttpTestClient.Response plain = client.send("OPTIONS", STOCK_STATS, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", STOCK_STATS,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 200 OK", preflight.statusLine(), preflight::toString);
        assertEquals("GET,HEAD,OPTIONS", preflight.header("Allow"), preflight::toString);
        assertArrayEquals(new byte[0], preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(stockStatsService, twitterSearchService);
    }

    @ParameterizedTest(name = "{1} on {0}, Access-Control-Request-Method {2}, Access-Control-Request-Headers {3}")
    @MethodSource("wrongPortPreflights")
    void preflightOnTheWrongListenerGetsTheRoutingNoListener404LikeAPlainOptionsRequest(Listener listener,
                                                                                        String path,
                                                                                        String requestedMethod,
                                                                                        String requestedHeaders) {
        HttpTestClient client = client(listener);
        HttpTestClient.Response plain = client.send("OPTIONS", path, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", path,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertNoListener404(preflight, path);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(stockStatsService, twitterSearchService);
    }

    @ParameterizedTest(name = "Access-Control-Request-Method {0}, Access-Control-Request-Headers {1}")
    @MethodSource("preflights")
    void preflightToAnUnservedPathOnTheStockStatsListenerGetsTheNoListener404LikeAPlainOptionsRequest(
            String requestedMethod, String requestedHeaders) {
        HttpTestClient client = client(Listener.STOCK_STATS);
        HttpTestClient.Response plain = client.send("OPTIONS", UNKNOWN, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", UNKNOWN,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertNoListener404(preflight, UNKNOWN);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(stockStatsService, twitterSearchService);
    }

    @Test
    void crossOriginGetStillReachesTheTwitterListenerFlow() {
        when(twitterSearchService.searchListenerFlow()).thenReturn(LISTENER_PAYLOAD);

        HttpTestClient.Response response = client(Listener.MAIN).send("GET", ROOT, Map.of("Origin", ORIGIN), null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(String.valueOf(LISTENER_PAYLOAD.length), response.header("Content-Length"), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertArrayEquals(LISTENER_PAYLOAD, response.body(), response::toString);
        assertNoCorsHeaders(response);
        verify(twitterSearchService).searchListenerFlow();
        verifyNoMoreInteractions(twitterSearchService);
        verifyNoInteractions(stockStatsService);
    }

    private HttpTestClient client(Listener listener) {
        return HttpTestClient.localhost(listener == Listener.MAIN ? mainPort : stockStatsPort);
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

    /** 404 {@code Not Found}, {@code text/plain} and the body {@code No listener for endpoint: <path>}. */
    private static void assertNoListener404(HttpTestClient.Response response, String path) {
        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertEquals(NO_LISTENER_CONTENT_TYPE, response.header("Content-Type"), response::toString);
        assertArrayEquals(("No listener for endpoint: " + path).getBytes(StandardCharsets.UTF_8), response.body(),
                response::toString);
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
