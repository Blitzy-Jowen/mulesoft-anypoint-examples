/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.cache_scope_with_fibonacci.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.http.MediaType;

import com.github.benmanes.caffeine.cache.Policy;
import com.mulesoft.examples.cache_scope_with_fibonacci.model.FibonacciResult;
import com.mulesoft.examples.cache_scope_with_fibonacci.service.FibonacciCacheService;
import com.mulesoft.examples.cache_scope_with_fibonacci.support.HttpTestClient;
import com.mulesoft.examples.cache_scope_with_fibonacci.support.HttpTestClient.Response;

/**
 * Wire-level test of {@link FibonacciController}, the HTTP trigger of flow {@code cache-exampleFlow1}
 * (cache-scope-with-fibonacci/src/main/app/cache-scope.xml:40-59), on the application's own Undertow listener.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the socket,
 * so the reason phrase, the {@code cost}, {@code Content-Type} and {@code Content-Length} headers and the body bytes
 * are asserted exactly as the listener writes them. The happy paths cover the calculation, every method (the listener
 * declares no {@code allowedMethods}) and the cache states the raw-string key and the two {@code nocache} controls
 * select; the error cases are the flow's {@code otherwise} branch, its favicon filter on each kind of listener payload,
 * the listener's 400 for a form body it cannot decode and the SC-1 platform defaults: the no-listener 404,
 * {@code /fibonacci} with {@code ;} segment parameters included, and the JSON 500.
 *
 * <p>Every case starts on an empty cache and issues any request whose cached results it depends on itself, so the
 * expected costs follow the cost model of {@code FibonacciCacheService}: {@code n < 2} costs 1, a cache hit costs 0,
 * and any other {@code n} costs its two sub-results plus one, so a cold {@code n >= 2} costs {@code n + 1}.
 *
 * <p>Two cases check the {@code fibonacci} cache as {@code application.yml} configures it: bounded at the Mule 3.8.0
 * in-memory object-store default ({@code maxEntries} 4000, {@code entryTTL} -1), that is 4000 entries without
 * expiry, so the distinct spellings of {@code n} callers choose are evicted past the bound instead of growing the heap.
 *
 * <p>The class declares nothing that changes the test context, so it shares the application context, and with it
 * the single {@code fibonacci} cache, with the project's other {@code @SpringBootTest} classes; clearing the cache
 * before each case keeps it independent of their order.
 *
 * <p>Two binding concerns are asserted elsewhere: a repeated query parameter takes its last occurrence, as Mule
 * 3.8.0's {@code ParameterMap.get} returned {@code http.query.params.n}, and {@code OPTIONS} runs the flow, both in
 * {@code FibonacciControllerBindingTest}. The original {@code CacheScopeWithFibonacciIT} assertions are ported
 * separately in {@code CacheScopeWithFibonacciTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FibonacciControllerTest {

    /** Payload of the flow's {@code otherwise} branch (cache-scope.xml:56). */
    private static final String TOO_HIGH = "ERROR: n must be less than 20";

    /** Pattern of the flow's favicon {@code wildcard-filter} (cache-scope.xml:47). */
    private static final String FAVICON = "/favicon.ico";

    /** A well-formed multipart body, boundary {@code b}, whose only part holds the favicon path. */
    private static final String MULTIPART_FAVICON_BODY = "--b\r\n"
            + "Content-Disposition: form-data; name=\"icon\"\r\n"
            + "\r\n"
            + FAVICON + "\r\n"
            + "--b--\r\n";

    /**
     * The Mule 3.8.0 in-memory object-store default {@code maxEntries} ({@code AbstractMonitoredObjectStore.java:41}),
     * which {@code application.yml} applies as the cache's maximum size.
     */
    private static final long STORE_MAX_ENTRIES = 4000;

    @LocalServerPort
    private int port;

    @Autowired
    private CacheManager cacheManager;

    private Cache cache;

    private HttpTestClient client;

    @BeforeEach
    void setUp() {
        cache = cacheManager.getCache(FibonacciCacheService.CACHE_NAME);
        assertNotNull(cache, "cache " + FibonacciCacheService.CACHE_NAME);
        cache.clear();
        client = HttpTestClient.localhost(port);
    }

    /**
     * A cold cache computes every value through the recursion. The base case echoes the raw {@code n}, a negative
     * one included, at cost 1; 19 is the largest {@code n} the choice accepts.
     */
    @ParameterizedTest(name = "GET /fibonacci?n={0} -> {1}, cost {2}")
    @CsvSource({
            "10, 55, 11",
            "19, 4181, 20",
            "1, 1, 1",
            "-3, -3, 1"})
    void coldCacheComputesValueAndCost(String n, String value, String cost) {
        assertFlowReply(get("/fibonacci?n=" + n), "Fibonacci(" + n + ") = " + value + "\nCOST: " + cost, cost);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void everyMethodRunsTheFlow(String method) {
        Response response = send(method, "/fibonacci?n=10", Map.of(), new byte[0]);

        assertFlowReply(response, "Fibonacci(10) = 55\nCOST: 11", "11");
    }

    @Test
    void cacheHitReportsCostZero() {
        assertFlowReply(get("/fibonacci?n=10"), "Fibonacci(10) = 55\nCOST: 11", "11");

        // FibonacciResponseGenerator sets the cost of a cached response to 0, and the header carries it.
        assertFlowReply(get("/fibonacci?n=5"), "Fibonacci(5) = 5\nCOST: 0", "0");
    }

    @Test
    void cacheKeyIsTheRawQueryValue() {
        assertFlowReply(get("/fibonacci?n=10"), "Fibonacci(10) = 55\nCOST: 11", "11");

        // Key "05" is not the stored "5": it misses, while its children 4 and 3 hit the entries stored above.
        assertFlowReply(get("/fibonacci?n=05"), "Fibonacci(05) = 5\nCOST: 1", "1");
    }

    @Test
    void nocacheHeaderBypassesTheCacheForTheTopLevelCallOnly() {
        assertFlowReply(get("/fibonacci?n=10"), "Fibonacci(10) = 55\nCOST: 11", "11");

        // 10 is recomputed; its children 9 and 8 are requested without the header and hit at cost 0.
        Response response = send("GET", "/fibonacci?n=10", Map.of("nocache", "true"), new byte[0]);

        assertFlowReply(response, "Fibonacci(10) = 55\nCOST: 1", "1");
    }

    @Test
    void nocacheQueryParameterBypassesTheCacheForTheDirectChildren() {
        assertFlowReply(get("/fibonacci?n=8"), "Fibonacci(8) = 21\nCOST: 9", "9");

        // 10 misses; its children 9 and 8 bypass the cache and cost 1 each, because their own children (8 and 7,
        // then 7 and 6) are requested with n alone and hit.
        assertFlowReply(get("/fibonacci?n=10&nocache=true"), "Fibonacci(10) = 55\nCOST: 3", "3");

        // The bypassing child 9 was never stored, so it is computed again from the hits 8 and 7.
        assertFlowReply(get("/fibonacci?n=9"), "Fibonacci(9) = 34\nCOST: 1", "1");

        // The top-level call itself used the cache, so its result was stored.
        assertFlowReply(get("/fibonacci?n=10"), "Fibonacci(10) = 55\nCOST: 0", "0");
    }

    /**
     * The bound is the Mule 3.8.0 in-memory object-store default, {@code maxEntries} 4000 and {@code entryTTL} -1,
     * which never expires an entry ({@code AbstractMonitoredObjectStore.java:41,47}): the cache counts entries up to
     * the same maximum and neither expires nor refreshes them.
     */
    @Test
    void cacheIsBoundedAtTheStoreMaximumWithoutExpiry() {
        Policy<Object, Object> policy = nativeCache().policy();

        assertEquals(Optional.of(STORE_MAX_ENTRIES), policy.eviction().map(Policy.Eviction::getMaximum), "maximum");
        assertEquals(Optional.of(false), policy.eviction().map(Policy.Eviction::isWeighted), "weighted");
        assertEquals(Optional.empty(), policy.expireAfterWrite(), "expireAfterWrite");
        assertEquals(Optional.empty(), policy.expireAfterAccess(), "expireAfterAccess");
        assertEquals(Optional.empty(), policy.expireVariably(), "expireVariably");
        assertEquals(Optional.empty(), policy.refreshAfterWrite(), "refreshAfterWrite");
    }

    /**
     * Each spelling of {@code n} is its own key, so 1000 distinct keys past the maximum leave the cache at the
     * maximum once Caffeine's pending maintenance has run. Which keys survive is Caffeine's eviction policy, not the
     * in-memory store's oldest-first trim, so only the count is asserted.
     */
    @Test
    void distinctKeysBeyondTheStoreMaximumAreEvicted() {
        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache = nativeCache();
        try {
            for (long i = 1; i <= STORE_MAX_ENTRIES + 1000; i++) {
                String key = "1." + i;
                cache.put(key, new FibonacciResult(key, 1));
            }
            nativeCache.cleanUp();

            assertEquals(STORE_MAX_ENTRIES, nativeCache.estimatedSize());
        } finally {
            // The cache belongs to the shared application context: leave it empty for the classes that follow.
            cache.clear();
        }
    }

    /** {@code n < 20} is false for 20 and for an absent {@code n}, so both take the {@code otherwise} branch. */
    @ParameterizedTest
    @ValueSource(strings = {"/fibonacci?n=20", "/fibonacci"})
    void nOfTwentyOrMissingTakesTheOtherwiseBranch(String path) {
        assertFlowReply(get(path), TOO_HIGH, null);
    }

    /**
     * The favicon wildcard-filter tests the payload (cache-scope.xml:45-49): a text body that reads as exactly
     * {@code /favicon.ico} in the charset its {@code Content-Type} declares, UTF-8 when it declares none or no
     * {@code Content-Type} is sent, ends the flow before the calculation, answered with an empty 200 (AAP 0.4.7), and
     * nothing is cached.
     *
     * @param contentType the {@code Content-Type} sent, or {@code null} for none
     * @param body        the request body
     */
    @ParameterizedTest(name = "[{index}] Content-Type {0}")
    @MethodSource("faviconTextBodies")
    void faviconPayloadIsFilteredToAnEmpty200(String contentType, byte[] body) {
        Map<String, String> headers = contentType == null ? Map.of() : Map.of("Content-Type", contentType);

        Response response = send("POST", "/fibonacci?n=10", headers, body);

        assertFlowReply(response, "", null);
        assertNull(cache.get("10"), "the filtered request must not reach the cache scope");
    }

    /**
     * @return {@code Content-Type} (or {@code null} for none) and body pairs whose text payload is
     *         {@code /favicon.ico}
     */
    private static Stream<Arguments> faviconTextBodies() {
        return Stream.of(
                Arguments.of(MediaType.TEXT_PLAIN_VALUE, bytes(FAVICON)),
                Arguments.of(null, bytes(FAVICON)),
                Arguments.of("text/plain; charset=UTF-16BE", FAVICON.getBytes(StandardCharsets.UTF_16BE)));
    }

    /**
     * The Mule 3.8 listener handed the flow a form-urlencoded body as its decoded parameter map and a multipart body
     * as a null payload, so neither equals the filter's text, even when it carries exactly {@code /favicon.ico}:
     * both pass the filter and reach the calculation. curl's {@code --data-binary} declares the form type unless
     * told otherwise.
     *
     * @param contentType the {@code Content-Type} sent
     * @param body        the request body
     */
    @ParameterizedTest(name = "[{index}] Content-Type {0}")
    @MethodSource("faviconNonTextBodies")
    void faviconFormOrMultipartBodyPassesTheFilter(String contentType, byte[] body) {
        Response response = send("POST", "/fibonacci?n=10", Map.of("Content-Type", contentType), body);

        assertFlowReply(response, "Fibonacci(10) = 55\nCOST: 11", "11");
    }

    /**
     * @return {@code Content-Type} and body pairs carrying {@code /favicon.ico} that the listener did not hand over
     *         as text: a form body and a multipart body
     */
    private static Stream<Arguments> faviconNonTextBodies() {
        return Stream.of(
                Arguments.of(MediaType.APPLICATION_FORM_URLENCODED_VALUE, bytes(FAVICON)),
                Arguments.of("multipart/form-data; boundary=b", bytes(MULTIPART_FAVICON_BODY)));
    }

    /**
     * A form-urlencoded body with a malformed percent escape fails in the listener before the flow runs, whatever the
     * method: the Mule 3.8 listener answered {@code BAD_REQUEST} with its decoding message and no
     * {@code Content-Type}, so no {@code cost} header is set and nothing is cached. The committed reply also leaves
     * Spring MVC's own {@code OPTIONS} answer out.
     *
     * @param method the request method, sent with the same malformed form body each time
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void undecodableFormBodyReturnsListener400(String method) {
        Response response = send(method, "/fibonacci?n=10",
                Map.of("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE), bytes("a=1&b=%ZZ"));

        byte[] expected = bytes("Cannot decode x-www-form-urlencoded payload");
        assertEquals("HTTP/1.1 400 Bad Request", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(List.of(Integer.toString(expected.length)), response.headers("Content-Length"),
                response::toString);
        assertEquals(List.of(), response.headers("cost"), response::toString);
        assertEquals(List.of(), response.headers("Allow"), response::toString);
        assertArrayEquals(expected, response.body(), response::toString);
        assertNull(cache.get("10"), "the rejected request must not reach the cache scope");
    }

    /** A {@code Content-Type} that cannot be parsed fails the payload binding, which reaches the SC-1 500. */
    @Test
    void unparseableContentTypeReturnsGeneric500() {
        Response response = send("POST", "/fibonacci?n=10", Map.of("Content-Type", "bogus"), bytes("ping"));

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.APPLICATION_JSON_VALUE), response.headers("Content-Type"), response::toString);
        assertEquals(List.of(), response.headers("cost"), response::toString);
        assertArrayEquals(bytes("{ \"message\": \"Invalid mime type \\\"bogus\\\": does not contain '/'\" }"),
                response.body(), response::toString);
        assertNull(cache.get("10"), "the failed request must not reach the cache scope");
    }

    /**
     * Only {@code /fibonacci} has a listener, so the browser's {@code /favicon.ico} request, the root and a sub-path
     * get the SC-1 no-listener 404; static resource serving is off in {@code application.yml}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/favicon.ico", "/", "/fibonacci/10"})
    void unservedPathReturnsNoListener404(String path) {
        Response response = get(path);

        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.TEXT_PLAIN_VALUE), response.headers("Content-Type"), response::toString);
        assertEquals(List.of(), response.headers("cost"), response::toString);
        assertArrayEquals(bytes("No listener for endpoint: " + path), response.body(), response::toString);
    }

    /**
     * A path carrying {@code ;} segment parameters is not the listener's path: the Mule listener compared the raw
     * path, query removed, literally, so every method gets the no-listener 404 naming the path with its parameters
     * and without the query, and the flow never runs, so nothing is calculated or cached
     * ({@code config/ListenerPathConfig}). {@code HEAD} gets the same status line and headers without the body.
     *
     * @param method the request method; {@code POST} carries a {@code text/plain} body
     * @param path   {@code /fibonacci} followed by its segment parameters, sent with the query {@code ?n=10}
     */
    @ParameterizedTest(name = "{0} {1}?n=10")
    @MethodSource("pathParameterRequests")
    void pathWithSegmentParametersReturnsNoListener404(String method, String path) {
        boolean withBody = "POST".equals(method);
        Response response = send(method, path + "?n=10",
                withBody ? Map.of("Content-Type", MediaType.TEXT_PLAIN_VALUE) : Map.of(),
                withBody ? bytes("ping") : new byte[0]);

        byte[] expected = bytes("No listener for endpoint: " + path);
        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.TEXT_PLAIN_VALUE), response.headers("Content-Type"), response::toString);
        assertEquals(List.of(), response.headers("cost"), response::toString);
        assertEquals(List.of(Integer.toString(expected.length)), response.headers("Content-Length"),
                response::toString);
        assertArrayEquals("HEAD".equals(method) ? new byte[0] : expected, response.body(), response::toString);
        assertNull(cache.get("10"), "the rejected request must not reach the cache scope");
    }

    /** @return every method the case is sent with, each paired with every form of segment parameters */
    private static Stream<Arguments> pathParameterRequests() {
        return Stream.of("GET", "POST", "HEAD", "OPTIONS").flatMap(method ->
                Stream.of("/fibonacci;x=1", "/fibonacci;jsessionid=ABC", "/fibonacci;")
                        .map(path -> Arguments.of(method, path)));
    }

    /** The flow has no exception strategy, so a non-numeric {@code n} reaches the SC-1 JSON 500. */
    @Test
    void nonNumericNReturnsGeneric500() {
        Response response = get("/fibonacci?n=abc");

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.APPLICATION_JSON_VALUE), response.headers("Content-Type"), response::toString);
        assertEquals(List.of(), response.headers("cost"), response::toString);
        assertArrayEquals(bytes("{ \"message\": \"Invalid value for n: abc\" }"), response.body(),
                response::toString);
    }

    private Response send(String method, String path, Map<String, String> headers, byte[] body) {
        return client.send(method, path, headers, body);
    }

    private Response get(String path) {
        return send("GET", path, Map.of(), new byte[0]);
    }

    /** @return the Caffeine cache Boot built for the {@code fibonacci} cache from {@code spring.cache.caffeine.spec} */
    private com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache() {
        return assertInstanceOf(CaffeineCache.class, cache).getNativeCache();
    }

    /**
     * Asserts a reply the flow produced: {@code HTTP/1.1 200 OK}, the {@code cost} header once with
     * {@code expectedCost} or absent when it is {@code null}, no {@code Content-Type} (the original reply declared no
     * MIME type), a {@code Content-Length} equal to the body length, and the exact body bytes.
     */
    private static void assertFlowReply(Response response, String expectedBody, String expectedCost) {
        byte[] expectedBytes = bytes(expectedBody);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(expectedCost == null ? List.of() : List.of(expectedCost), response.headers("cost"),
                response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(List.of(Integer.toString(expectedBytes.length)), response.headers("Content-Length"),
                response::toString);
        assertArrayEquals(expectedBytes, response.body(), response::toString);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
