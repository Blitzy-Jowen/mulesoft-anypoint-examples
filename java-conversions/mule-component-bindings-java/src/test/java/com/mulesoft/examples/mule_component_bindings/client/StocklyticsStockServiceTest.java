/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.mule_component_bindings.config.StockStatsProperties;
import com.mulesoft.examples.mule_component_bindings.exception.InvalidStockSymbol;
import com.mulesoft.examples.mule_component_bindings.model.FinancialStats;
import com.mulesoft.examples.mule_component_bindings.model.StockStats;

/**
 * The contract of {@link StocklyticsStockService}, the port of the original
 * {@code stockstats.impl.stocklytics.StocklyticsStockService}, against a WireMock stand-in for
 * {@code http://api.stocklytics.com/}: the historical prices are requested with a bare GET carrying the original
 * headers, the closing date's open, close, high, low and volume are read from the body with absent values as 0 and
 * numeric text coerced, a status other than 200 fails with the original message, a 200 carrying Stocklytics' error
 * text fails with {@link InvalidStockSymbol}, an unreadable body fails with a plain {@link RuntimeException}, an API
 * key without text fails naming {@code stockstats.stocklyticsApiKey} before any request is sent, and
 * through the timed transport an upstream that never answers, or that sends its headers and stalls its body, fails
 * once with a {@link SocketTimeoutException} cause within the timeout; a connection fault fails the call after one
 * request; redirects are followed as {@code HttpURLConnection} followed them, but only on the configured origin: a
 * redirect to another host, port or scheme, at once or after a followed one, fails with its status, and a second
 * WireMock server standing for that origin receives no request, so neither the API key nor the request data reach
 * it; the constructor accepts only a timeout the transport can enforce.
 */
class StocklyticsStockServiceTest {

    private static final String HISTORICAL_PRICES = "/historicalPrices/1.0";

    /** Where the redirect cases move the historical prices. */
    private static final String MOVED_HISTORICAL_PRICES = "/moved/historicalPrices/1.0";

    /** The prices served where a redirect case moved them. */
    private static final String MOVED_PRICES = "{\"2012-10-26\":{\"open\":1.0,\"close\":2.0,\"high\":3.0,\"low\":0.5,"
            + "\"volume\":7}}";

    /** The API key of the origin cases, distinct from every other test value, so its arrival anywhere is its own. */
    private static final String MARKER_API_KEY = "marker-stocklytics-api-key";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    /** Another origin, on its own port: where a hostile or misconfigured Stocklytics would redirect the GET. */
    @RegisterExtension
    static WireMockExtension foreign = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @BeforeEach
    void setUp() {
        wm.resetAll();
        foreign.resetAll();
    }

    @Test
    void sendsOriginalRequestAndParsesPrices() throws Exception {
        // Stubbed loosely and verified strictly, so a header mismatch fails the verification instead of a 404.
        wm.stubFor(get(urlPathEqualTo(HISTORICAL_PRICES)).willReturn(okJson(
                "{\"2012-10-26\":{\"open\":609.43,\"close\":604.0,\"high\":614.0,\"low\":591.0,"
                        + "\"volume\":5000000000}}")));

        StockStats stats = newService().getHistoricalPrices("AAPL", date("2012-10-26"));

        assertThat(stats.getClosingDate()).isEqualTo("2012-10-26");
        FinancialStats financial = stats.getFinancialStats();
        assertThat(financial.getOpen()).isEqualTo(609.43);
        assertThat(financial.getClose()).isEqualTo(604.0);
        assertThat(financial.getHigh()).isEqualTo(614.0);
        assertThat(financial.getLow()).isEqualTo(591.0);
        // Beyond the int range, so the volume is read as a long.
        assertThat(financial.getVolume()).isEqualTo(5_000_000_000L);
        assertThat(stats.getSocialStats().getTweetsLoaded()).isZero();
        // urlEqualTo proves the request carries no query string: every parameter travels as a header, the
        // closing date as both start and end.
        wm.verify(1, getRequestedFor(urlEqualTo(HISTORICAL_PRICES))
                .withHeader("api_key", equalTo("test-stocklytics-key"))
                .withHeader("format", equalTo("JSON"))
                .withHeader("stock", equalTo("AAPL"))
                .withHeader("start", equalTo("2012-10-26"))
                .withHeader("end", equalTo("2012-10-26"))
                .withHeader("Accept", equalTo("application/json")));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    @Test
    void missingFieldsDefaultToZero() throws Exception {
        wm.stubFor(get(urlPathEqualTo(HISTORICAL_PRICES)).willReturn(okJson("{\"2012-10-26\":{\"close\":1.5}}")));

        StockStats partial = newService().getHistoricalPrices("AAPL", date("2012-10-26"));

        assertThat(partial.getClosingDate()).isEqualTo("2012-10-26");
        assertPrices(partial.getFinancialStats(), 0.0, 1.5, 0.0, 0.0, 0L);

        // Prices for another day only: the closing date's entry is absent, so every value is 0.
        wm.resetAll();
        wm.stubFor(get(urlPathEqualTo(HISTORICAL_PRICES)).willReturn(okJson("{\"2012-10-25\":{\"open\":1.0}}")));

        StockStats absent = newService().getHistoricalPrices("AAPL", date("2012-10-26"));

        assertThat(absent.getClosingDate()).isEqualTo("2012-10-26");
        assertPrices(absent.getFinancialStats(), 0.0, 0.0, 0.0, 0.0, 0L);
    }

    @Test
    void numericTextIsCoerced() throws Exception {
        // asDouble()/asLong() read numeric text as the original codehaus getValueAsDouble()/getValueAsLong() did.
        wm.stubFor(get(urlPathEqualTo(HISTORICAL_PRICES))
                .willReturn(okJson("{\"2012-10-26\":{\"open\":\"10.5\",\"volume\":\"42\"}}")));

        StockStats stats = newService().getHistoricalPrices("AAPL", date("2012-10-26"));

        assertPrices(stats.getFinancialStats(), 10.5, 0.0, 0.0, 0.0, 42L);
    }

    // 201 is included because the original accepts exactly 200, not any 2xx.
    @ParameterizedTest
    @ValueSource(ints = {201, 404, 500})
    void nonOkStatusRaisesOriginalMessage(int status) {
        wm.stubFor(get(urlPathEqualTo(HISTORICAL_PRICES)).willReturn(aResponse().withStatus(status)));
        StocklyticsStockService service = newService();

        // Exactly RuntimeException with the original's "Stockylitics" spelling: Spring's HttpClientErrorException
        // and HttpServerErrorException, RuntimeExceptions with other text, must not escape.
        assertThatThrownBy(() -> service.getHistoricalPrices("AAPL", date("2012-10-26")))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("Stockylitics returned status " + status);
        // Exactly one request: a failed call is never retried.
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    @Test
    void stocklyticsErrorBodyRaisesInvalidStockSymbol() {
        // Stocklytics answers an unknown symbol with 200 and this text, which the original matches by substring.
        wm.stubFor(get(urlPathEqualTo(HISTORICAL_PRICES))
                .willReturn(okJson("{\"error\":\"Error: Stocklytics doesn't have data for that stock.\"}")));
        StocklyticsStockService service = newService();

        assertThatThrownBy(() -> service.getHistoricalPrices("ZZZZ", date("2012-10-26")))
                .isExactlyInstanceOf(InvalidStockSymbol.class)
                .hasMessage("ZZZZ");
        wm.verify(1, getRequestedFor(urlEqualTo(HISTORICAL_PRICES)).withHeader("stock", equalTo("ZZZZ")));
    }

    // null, empty and whitespace only: none has text, so none may reach the api_key header.
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void apiKeyWithoutTextFailsNamingTheKeyBeforeAnyRequest(String apiKey) {
        wm.stubFor(get(urlPathEqualTo(HISTORICAL_PRICES)).willReturn(okJson("{\"2012-10-26\":{\"close\":1.5}}")));
        StocklyticsStockService service = new StocklyticsStockService(RestClient.builder(),
                new StockStatsProperties("test-access-key", "test-access-secret", "test-api-key", "test-api-secret",
                        wm.baseUrl() + "/1.1/", apiKey, wm.baseUrl() + "/", "test-sentiment140-key",
                        wm.baseUrl() + "/"));

        assertThatThrownBy(() -> service.getHistoricalPrices("AAPL", date("2012-10-26")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Stocklytics API key (stockstats.stocklyticsApiKey) is not configured")
                .hasNoCause();
        wm.verify(0, anyRequestedFor(anyUrl()));
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void malformedJsonRaisesRuntimeException() {
        wm.stubFor(get(urlPathEqualTo(HISTORICAL_PRICES)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{not json")));
        StocklyticsStockService service = newService();

        // The original wraps every parse failure in a plain RuntimeException.
        assertThatThrownBy(() -> service.getHistoricalPrices("AAPL", date("2012-10-26")))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(JsonProcessingException.class);
    }

    /**
     * A GET whose connection is reset, closed before any response, answered with garbage or broken inside the body
     * fails with a {@link ResourceAccessException} and is sent exactly once: the transport never re-sends the
     * bodiless GET after a connectivity failure, as the JDK {@code HttpURLConnection} did once.
     */
    @ParameterizedTest
    @EnumSource(Fault.class)
    void connectionFaultFailsOnceWithAnIoFailure(Fault fault) {
        wm.stubFor(get(urlPathEqualTo(HISTORICAL_PRICES)).willReturn(aResponse().withFault(fault)));
        StocklyticsStockService service = newService();

        Throwable thrown = catchThrowable(() -> service.getHistoricalPrices("AAPL", date("2012-10-26")));

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        assertThat(causeChain(thrown)).anyMatch(IOException.class::isInstance);
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    /** A redirect that keeps the scheme is followed, as HttpURLConnection followed it, with the original headers. */
    @ParameterizedTest
    @ValueSource(ints = {300, 301, 302, 303, 307})
    void sameSchemeRedirectIsFollowedWithTheOriginalHeaders(int status) throws Exception {
        wm.stubFor(get(urlPathEqualTo(HISTORICAL_PRICES)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Location", MOVED_HISTORICAL_PRICES)));
        wm.stubFor(get(urlPathEqualTo(MOVED_HISTORICAL_PRICES)).willReturn(okJson(
                "{\"2012-10-26\":{\"open\":1.0,\"close\":2.0,\"high\":3.0,\"low\":0.5,\"volume\":7}}")));

        StockStats stats = newService().getHistoricalPrices("AAPL", date("2012-10-26"));

        assertPrices(stats.getFinancialStats(), 1.0, 2.0, 3.0, 0.5, 7L);
        wm.verify(1, getRequestedFor(urlEqualTo(MOVED_HISTORICAL_PRICES))
                .withHeader("api_key", equalTo(properties().stocklyticsApiKey()))
                .withHeader("stock", equalTo("AAPL"))
                .withHeader("Accept", equalTo("application/json")));
        assertThat(wm.getAllServeEvents()).hasSize(2);
    }

    static Stream<Arguments> unfollowedRedirects() {
        return Stream.of(
                Arguments.of(Named.of("301 from http to https", 301), "https"),
                Arguments.of(Named.of("308, which HttpURLConnection does not follow", 308), "http"));
    }

    /**
     * A redirect HttpURLConnection did not follow is returned as received, so its status fails the call with the
     * original message, and nothing is sent to its location.
     */
    @ParameterizedTest
    @MethodSource("unfollowedRedirects")
    void unfollowedRedirectFailsWithItsStatusAfterOneRequest(int status, String scheme) {
        wm.stubFor(get(urlPathEqualTo(HISTORICAL_PRICES)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Location", scheme + "://localhost:" + wm.getPort() + MOVED_HISTORICAL_PRICES)));
        StocklyticsStockService service = newService();

        assertThatThrownBy(() -> service.getHistoricalPrices("AAPL", date("2012-10-26")))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("Stockylitics returned status " + status);
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    /**
     * Each followed status with an absolute location on the foreign origin, and a 302 to each other way off the
     * configured origin. The origin is a format pattern: {@code %1$d} is the configured port, {@code %2$d} the
     * foreign one.
     */
    static Stream<Arguments> crossOriginRedirects() {
        Stream<Arguments> everyFollowedStatus = IntStream.of(300, 301, 302, 303, 307).mapToObj(status ->
                Arguments.of(status, Named.of("the foreign origin", "http://localhost:%2$d")));
        Stream<Arguments> otherOrigins = Stream.of(
                Arguments.of(302, Named.of("a network-path reference to the foreign origin", "//localhost:%2$d")),
                Arguments.of(302, Named.of("another host on the configured port", "http://127.0.0.1:%1$d")),
                Arguments.of(302, Named.of("the configured host without a port, so on port 80", "http://localhost")));
        return Stream.concat(everyFollowedStatus, otherOrigins);
    }

    /**
     * A redirect off the configured origin is returned as received, so its status fails the call with the original
     * message after the one request to the configured origin, and nothing is sent to its location: the foreign
     * origin receives no request, so neither the marker API key nor the request data reach it. Both servers serve
     * prices at the location, so a followed redirect would make the call succeed, or on port 80 fail to connect.
     */
    @ParameterizedTest
    @MethodSource("crossOriginRedirects")
    void crossOriginRedirectIsReturnedAsReceivedAndSendsNothingThere(int status, String origin) {
        wm.stubFor(get(urlPathEqualTo(HISTORICAL_PRICES)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Location", String.format(origin, wm.getPort(), foreign.getPort())
                        + MOVED_HISTORICAL_PRICES)));
        wm.stubFor(get(urlPathEqualTo(MOVED_HISTORICAL_PRICES)).willReturn(okJson(MOVED_PRICES)));
        foreign.stubFor(get(anyUrl()).willReturn(okJson(MOVED_PRICES)));
        StocklyticsStockService service = markerKeyService();

        assertThatThrownBy(() -> service.getHistoricalPrices("AAPL", date("2012-10-26")))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("Stockylitics returned status " + status);
        wm.verify(1, getRequestedFor(urlEqualTo(HISTORICAL_PRICES)).withHeader("api_key", equalTo(MARKER_API_KEY)));
        assertThat(wm.getAllServeEvents()).hasSize(1);
        foreign.verify(0, anyRequestedFor(anyUrl()));
        assertThat(foreign.getAllServeEvents()).isEmpty();
    }

    /**
     * A redirect followed on the configured origin to a location that redirects to the foreign origin stops at the
     * second redirect, which fails the call with its status: the configured origin receives both requests, each with
     * the marker API key, and the foreign origin none.
     */
    @Test
    void sameOriginHopThenCrossOriginRedirectStopsThere() {
        wm.stubFor(get(urlPathEqualTo(HISTORICAL_PRICES)).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", MOVED_HISTORICAL_PRICES)));
        wm.stubFor(get(urlPathEqualTo(MOVED_HISTORICAL_PRICES)).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", foreign.baseUrl() + HISTORICAL_PRICES)));
        foreign.stubFor(get(anyUrl()).willReturn(okJson(MOVED_PRICES)));
        StocklyticsStockService service = markerKeyService();

        assertThatThrownBy(() -> service.getHistoricalPrices("AAPL", date("2012-10-26")))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("Stockylitics returned status 302");
        wm.verify(1, getRequestedFor(urlEqualTo(HISTORICAL_PRICES)).withHeader("api_key", equalTo(MARKER_API_KEY)));
        wm.verify(1, getRequestedFor(urlEqualTo(MOVED_HISTORICAL_PRICES))
                .withHeader("api_key", equalTo(MARKER_API_KEY)));
        assertThat(wm.getAllServeEvents()).hasSize(2);
        foreign.verify(0, anyRequestedFor(anyUrl()));
        assertThat(foreign.getAllServeEvents()).isEmpty();
    }

    static Stream<Arguments> sameOriginAbsoluteLocations() {
        return Stream.of(
                Arguments.of(Named.of("as configured", "http://localhost:%d")),
                Arguments.of(Named.of("with the host upper-cased", "http://LOCALHOST:%d")),
                Arguments.of(Named.of("with the scheme and the host in mixed case", "HTTP://LocalHost:%d")));
    }

    /**
     * An absolute location on the configured origin, its scheme and host in any letter case, is followed with the
     * marker API key and every request-data header of the original GET.
     */
    @ParameterizedTest
    @MethodSource("sameOriginAbsoluteLocations")
    void absoluteSameOriginRedirectIsFollowedWithTheOriginalHeaders(String origin) throws Exception {
        wm.stubFor(get(urlPathEqualTo(HISTORICAL_PRICES)).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", String.format(origin, wm.getPort()) + MOVED_HISTORICAL_PRICES)));
        wm.stubFor(get(urlPathEqualTo(MOVED_HISTORICAL_PRICES)).willReturn(okJson(MOVED_PRICES)));

        StockStats stats = markerKeyService().getHistoricalPrices("AAPL", date("2012-10-26"));

        assertPrices(stats.getFinancialStats(), 1.0, 2.0, 3.0, 0.5, 7L);
        wm.verify(1, getRequestedFor(urlEqualTo(MOVED_HISTORICAL_PRICES))
                .withHeader("api_key", equalTo(MARKER_API_KEY))
                .withHeader("format", equalTo("JSON"))
                .withHeader("stock", equalTo("AAPL"))
                .withHeader("start", equalTo("2012-10-26"))
                .withHeader("end", equalTo("2012-10-26"))
                .withHeader("Accept", equalTo("application/json")));
        assertThat(wm.getAllServeEvents()).hasSize(2);
    }

    static Stream<Arguments> silentUpstreams() {
        return Stream.of(
                Arguments.of(Named.of("no response at all", new byte[0])),
                Arguments.of(Named.of("headers, then a body stalled after 15 of its 1000 bytes",
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n"
                                + "{\"2012-10-26\":{").getBytes(US_ASCII))));
    }

    @ParameterizedTest
    @MethodSource("silentUpstreams")
    void upstreamFallingSilentFailsOnceWithSocketTimeoutWithinTheTimeout(byte[] sentBeforeStalling)
            throws IOException, InterruptedException {
        // A raw socket rather than WireMock: its delays hold back the headers as well, and it journals a delayed
        // request only once the delay ends, so it could neither send headers before stalling nor count the
        // requests when the call fails. Here each connection is counted as it is accepted. Twice the timeout is
        // outside the bound, so a second blocking read of the stalled body, such as a drain on close, fails.
        Duration timeout = Duration.ofSeconds(2);
        AtomicInteger accepted = new AtomicInteger();
        List<Socket> connections = Collections.synchronizedList(new ArrayList<>());
        List<IOException> closeFailures = new ArrayList<>();
        ServerSocket upstream = new ServerSocket(0);
        Thread server = new Thread(() -> serveThenFallSilent(upstream, sentBeforeStalling, accepted, connections),
                "silent-stocklytics-upstream");
        server.setDaemon(true);
        try {
            server.start();
            StocklyticsStockService service = new StocklyticsStockService(RestClient.builder(),
                    properties("http://127.0.0.1:" + upstream.getLocalPort() + "/"), timeout);
            Date closingDate = date("2012-10-26");

            long start = System.nanoTime();
            // The watchdog is independent of the service's own timeout, so a call that never ends fails the test
            // here instead of hanging the build.
            Throwable thrown = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> catchThrowable(() -> service.getHistoricalPrices("AAPL", closingDate)));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(thrown).isInstanceOf(ResourceAccessException.class);
            assertThat(causeChain(thrown)).anyMatch(SocketTimeoutException.class::isInstance);
            assertThat(elapsed).isBetween(timeout.minusMillis(50), timeout.plusMillis(1500));
            // Exactly one connection: a timed-out request is never retried.
            assertThat(accepted).hasValue(1);
        } finally {
            // The one owner of the sockets: closing the listening socket ends the accept loop, and closing each
            // accepted connection ends any read still blocked on it. Every close is attempted; failures are
            // reported after the cleanup, so none replaces the failure that brought the test here.
            try {
                upstream.close();
            } catch (IOException ex) {
                closeFailures.add(ex);
            }
            synchronized (connections) {
                for (Socket connection : connections) {
                    try {
                        connection.close();
                    } catch (IOException ex) {
                        closeFailures.add(ex);
                    }
                }
            }
            server.join(Duration.ofSeconds(5).toMillis());
        }
        assertThat(closeFailures).as("failures closing the silent upstream's sockets").isEmpty();
        assertThat(server.isAlive()).as("silent upstream thread still running").isFalse();
    }

    static Stream<Arguments> invalidTimeouts() {
        return Stream.of(
                Arguments.of(Named.of("null", null)),
                Arguments.of(Named.of("zero", Duration.ZERO)),
                Arguments.of(Named.of("negative", Duration.ofMillis(-1))),
                Arguments.of(Named.of("under 1 ms, which would truncate to no timeout", Duration.ofNanos(999_999))),
                Arguments.of(Named.of("over Integer.MAX_VALUE ms", Duration.ofMillis(Integer.MAX_VALUE + 1L))));
    }

    @ParameterizedTest
    @MethodSource("invalidTimeouts")
    void constructorRejectsATimeoutTheTransportCannotEnforce(Duration timeout) {
        assertThatThrownBy(() -> new StocklyticsStockService(RestClient.builder(), properties(), timeout))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Stocklytics timeout must be from 1 ms to 2147483647 ms, was " + timeout);
    }

    static Stream<Arguments> boundaryTimeouts() {
        return Stream.of(
                Arguments.of(Named.of("1 ms", Duration.ofMillis(1))),
                Arguments.of(Named.of("Integer.MAX_VALUE ms", Duration.ofMillis(Integer.MAX_VALUE))));
    }

    @ParameterizedTest
    @MethodSource("boundaryTimeouts")
    void constructorAcceptsTheBoundaryTimeouts(Duration timeout) {
        assertThatCode(() -> new StocklyticsStockService(RestClient.builder(), properties(), timeout))
                .doesNotThrowAnyException();
    }

    private static void assertPrices(FinancialStats financial, double open, double close, double high, double low,
                                     long volume) {
        assertThat(financial.getOpen()).as("open").isEqualTo(open);
        assertThat(financial.getClose()).as("close").isEqualTo(close);
        assertThat(financial.getHigh()).as("high").isEqualTo(high);
        assertThat(financial.getLow()).as("low").isEqualTo(low);
        assertThat(financial.getVolume()).as("volume").isEqualTo(volume);
    }

    /**
     * Accepts connections until {@code upstream} is closed, counting each in {@code accepted} as soon as it is
     * accepted. On each it reads the request head, which is the whole of the bodiless GET, sends
     * {@code sentBeforeStalling}, which is empty or a response whose body is shorter than its
     * {@code Content-Length}, and leaves the connection open and silent, so the response never completes.
     *
     * <p>Each connection is added to {@code connections}, a synchronized list the caller closes after closing
     * {@code upstream}. A connection accepted while {@code upstream} was being closed may join the list after the
     * caller has closed its contents, so this loop closes that one itself and stops.
     */
    private static void serveThenFallSilent(ServerSocket upstream, byte[] sentBeforeStalling, AtomicInteger accepted,
                                            List<Socket> connections) {
        try {
            while (!upstream.isClosed()) {
                Socket connection = upstream.accept();
                accepted.incrementAndGet();
                connections.add(connection);
                if (upstream.isClosed()) {
                    connection.close();
                    return;
                }
                readRequestHead(connection.getInputStream());
                OutputStream out = connection.getOutputStream();
                out.write(sentBeforeStalling);
                out.flush();
            }
        } catch (IOException closed) {
            // The test closed the listening socket, or the client dropped the connection: nothing more to serve.
        }
    }

    /** Reads up to and including the blank line that ends a request head. */
    private static void readRequestHead(InputStream in) throws IOException {
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (matched < terminator.length) {
            int next = in.read();
            if (next < 0) {
                return;
            }
            matched = next == terminator[matched] ? matched + 1 : (next == '\r' ? 1 : 0);
        }
    }

    /** The throwable and every cause beneath it, each once, so a cyclic cause chain cannot loop. */
    private static List<Throwable> causeChain(Throwable top) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = top; current != null && seen.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    /**
     * Midnight of {@code yyyyMMdd} in the JVM default time zone, the zone the service formats the closing date in,
     * so the date goes out as that same literal.
     */
    private static Date date(String yyyyMMdd) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd").parse(yyyyMMdd);
        } catch (ParseException ex) {
            throw new IllegalArgumentException("Not a yyyy-MM-dd date: " + yyyyMMdd, ex);
        }
    }

    /** The service as the application builds it, with every upstream on WireMock. */
    private static StocklyticsStockService newService() {
        return new StocklyticsStockService(RestClient.builder(), properties());
    }

    /** The service as the application builds it, with every upstream on WireMock and the API key the marker. */
    private static StocklyticsStockService markerKeyService() {
        return new StocklyticsStockService(RestClient.builder(),
                new StockStatsProperties("test-access-key", "test-access-secret", "test-api-key", "test-api-secret",
                        wm.baseUrl() + "/1.1/", MARKER_API_KEY, wm.baseUrl() + "/", "test-sentiment140-key",
                        wm.baseUrl() + "/"));
    }

    /** The {@code application-test.yml} values, with every upstream on WireMock. */
    private static StockStatsProperties properties() {
        return properties(wm.baseUrl() + "/");
    }

    /** The {@code application-test.yml} values, with Stocklytics at {@code stocklyticsBaseUrl}. */
    private static StockStatsProperties properties(String stocklyticsBaseUrl) {
        return new StockStatsProperties("test-access-key", "test-access-secret", "test-api-key", "test-api-secret",
                wm.baseUrl() + "/1.1/", "test-stocklytics-key", stocklyticsBaseUrl, "test-sentiment140-key",
                wm.baseUrl() + "/");
    }
}
