/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.SerializationUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.web.servlet.DispatcherServlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.mule_component_bindings.service.StockStatsService;
import com.mulesoft.examples.mule_component_bindings.support.HttpTestClient;
import com.mulesoft.examples.mule_component_bindings.support.PortInUseRetryingContextLoader;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The six credentials of {@code application.yml} never reach the log, whatever the level: the whole application
 * runs under {@code logging.level.root=TRACE} with each secret set to a distinct sentinel and its three upstreams on
 * one WireMock stand-in. Nothing is mocked, so twitter4j signs every search with OAuth, Apache httpclient5 sends the
 * Stocklytics {@code api_key} header and the JDK {@link java.net.HttpURLConnection} the Sentiment140 {@code appid}
 * header; left at the root level, twitter4j writes its OAuth base string (consumer key and access token) at DEBUG,
 * httpclient5's loggers {@code org.apache.hc.client5.http.headers} and {@code org.apache.hc.client5.http.wire} dump
 * every request header and byte, and the JDK's JUL logger {@code sun.net.www.protocol.http.HttpURLConnection},
 * bridged to SLF4J, dumps every request header.
 *
 * <p>Both listeners are covered: {@code /api/stockStats} answering, failing at each upstream with an error status,
 * and failing when each upstream falls silent past its 10 s read timeout, and the Twitter search of {@code "/"}.
 * Every case asserts the caller-facing result; that each upstream it reaches received its secret, the Twitter
 * search signed with both secrets, so all six are in play; that the captured output holds none of them; and that
 * the same request produced a TRACE event of an unpinned logger and a FINEST record sent through JUL, so TRACE
 * output is known to reach the log on both routes the transport loggers use. Logger levels are recorded before the
 * context starts and restored after the class, whose context is closed, so later test classes do not run at TRACE.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "logging.level.root=TRACE")
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@ActiveProfiles("test")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class SecretLoggingTest {

    private static final String TWITTER_API_KEY = "SENTINEL_TWITTER_API_KEY";
    private static final String TWITTER_API_SECRET = "SENTINEL_TWITTER_API_SECRET";
    private static final String TWITTER_ACCESS_KEY = "SENTINEL_TWITTER_ACCESS_KEY";
    private static final String TWITTER_ACCESS_SECRET = "SENTINEL_TWITTER_ACCESS_SECRET";
    private static final String STOCKLYTICS_API_KEY = "SENTINEL_STOCKLYTICS_API_KEY";
    private static final String SENTIMENT140_API_KEY = "SENTINEL_SENTIMENT140_APP_ID";

    /** Every configured secret; none may reach the log. */
    private static final List<String> SENTINELS = List.of(TWITTER_API_KEY, TWITTER_API_SECRET, TWITTER_ACCESS_KEY,
            TWITTER_ACCESS_SECRET, STOCKLYTICS_API_KEY, SENTIMENT140_API_KEY);

    private static final String SEARCH_PATH = "/1.1/search/tweets.json";
    private static final String PRICES_PATH = "/historicalPrices/1.0";
    private static final String CLASSIFY_PATH = "/bulkClassifyJson";

    private static final String DATE = "2014-10-31";
    private static final String STOCK_STATS = "/api/stockStats?stock=AAPL&date=" + DATE;

    private static final String PRICES = "{\"" + DATE + "\":{\"open\":108.01,\"close\":108.0,\"high\":108.04,"
            + "\"low\":107.21,\"volume\":44639285}}";

    private static final String SEARCH_METADATA = "\"search_metadata\":{\"completed_in\":0.021,"
            + "\"max_id\":529784356710236160,\"max_id_str\":\"529784356710236160\",\"query\":\"AAPL\","
            + "\"refresh_url\":\"?since_id=529784356710236160&q=AAPL\",\"count\":15,\"since_id\":0,"
            + "\"since_id_str\":\"0\"}";

    /** A v1.1 search response with two tweets: twitter4j reads both {@code statuses} and {@code search_metadata}. */
    private static final String TWEETS = "{\"statuses\":["
            + "{\"id\":529013410513453056,\"id_str\":\"529013410513453056\",\"text\":\"$AAPL closes higher\","
            + "\"created_at\":\"Sun Nov 02 21:30:00 +0000 2014\"},"
            + "{\"id\":529784356710236160,\"id_str\":\"529784356710236160\",\"text\":\"$AAPL up\","
            + "\"created_at\":\"Tue Nov 04 00:35:00 +0000 2014\"}]," + SEARCH_METADATA + "}";

    private static final String NO_TWEETS = "{\"statuses\":[]," + SEARCH_METADATA + "}";

    /** Twitter's rejection of a request whose OAuth credentials it does not accept. */
    private static final String TWITTER_UNAUTHORIZED = "{\"errors\":[{\"code\":32,"
            + "\"message\":\"Could not authenticate you.\"}]}";

    private static final String CLASSIFIED = "{\"data\":[{\"id\":\"529013410513453056\",\"polarity\":4},"
            + "{\"id\":\"529784356710236160\",\"polarity\":0}]}";

    /** Longer than the 10 s read timeout every client of the application has. */
    private static final int STALL_MILLIS = 12_000;

    /** How long the output must stay free of sentinels after the exchange, for lines written after the reply. */
    private static final Duration SETTLE = Duration.ofMillis(300);

    /** The logger levels set before this class's context started, by logger name. */
    private static final Map<String, Level> LEVELS_BEFORE = new HashMap<>();

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @LocalServerPort
    int port;

    /** Port of this context's stock-statistics listener. */
    @Value("${ports.stock-stats}")
    int stockStatsPort;

    /** The three upstreams in the order a stock statistics request calls them. */
    enum Upstream {
        STOCKLYTICS, TWITTER, SENTIMENT140
    }

    /**
     * Configures every attempt to load the context: the stock-statistics listener ({@code ports.stock-stats}) on a
     * free port drawn for that attempt, the upstreams on the WireMock stand-in and the six secrets.
     */
    @DynamicPropertySource
    static void upstreamsAndSecrets(DynamicPropertyRegistry registry) {
        int drawnPort = TestSocketUtils.findAvailableTcpPort();
        registry.add("ports.stock-stats", () -> drawnPort);
        registry.add("twitter.rest-base-url", () -> upstream.baseUrl() + "/1.1/");
        registry.add("stockstats.stocklytics-base-url", () -> upstream.baseUrl() + "/");
        registry.add("stockstats.sentiment140-base-url", () -> upstream.baseUrl() + "/");
        // Dynamic properties, unlike inline ones, are never rendered into the test framework's own log lines.
        registry.add("twitter.apiKey", () -> TWITTER_API_KEY);
        registry.add("twitter.apiSecret", () -> TWITTER_API_SECRET);
        registry.add("twitter.accessKey", () -> TWITTER_ACCESS_KEY);
        registry.add("twitter.accessSecret", () -> TWITTER_ACCESS_SECRET);
        registry.add("stockstats.stocklyticsApiKey", () -> STOCKLYTICS_API_KEY);
        registry.add("stockstats.sentiment140ApiKey", () -> SENTIMENT140_API_KEY);
    }

    @BeforeAll
    static void recordLevels() {
        LEVELS_BEFORE.clear();
        for (Logger logger : loggerContext().getLoggerList()) {
            if (logger.getLevel() != null) {
                LEVELS_BEFORE.put(logger.getName(), logger.getLevel());
            }
        }
    }

    @AfterAll
    static void restoreLevels() {
        for (Logger logger : loggerContext().getLoggerList()) {
            Level before = LEVELS_BEFORE.get(logger.getName());
            // The root logger always has a level and cannot be given none.
            if (before != null || !org.slf4j.Logger.ROOT_LOGGER_NAME.equals(logger.getName())) {
                logger.setLevel(before);
            }
        }
    }

    @Test
    void stockStatsCarriesEverySecretUpstreamWithoutLoggingAny(CapturedOutput output) throws Exception {
        stubAnswering(EnumSet.allOf(Upstream.class));

        try (RecordedLog log = new RecordedLog()) {
            HttpTestClient.Response response = stockStats().get(STOCK_STATS);

            assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 200 OK");
            assertThat(response.header("Content-Type")).isEqualTo("application/json");
            ObjectMapper json = new ObjectMapper();
            assertThat(json.readTree(response.body())).isEqualTo(json.readTree("{\"closingDate\":\"" + DATE + "\","
                    + "\"financialStats\":{\"open\":108.01,\"close\":108.0,\"high\":108.04,\"low\":107.21,"
                    + "\"volume\":44639285},\"socialStats\":{\"tweetsLoaded\":2,\"moreTweets\":false,"
                    + "\"negativeTweets\":1,\"neutralTweets\":0,\"positiveTweets\":1}}"));
            assertTraceReachedTheLog(log, STOCK_STATS);
        }
        assertEachUpstreamReceivedItsSecrets(EnumSet.allOf(Upstream.class));
        assertThat(upstream.findAll(getRequestedFor(urlPathEqualTo(SEARCH_PATH)))).as("both search pages").hasSize(2);
        assertLogHoldsNoSentinel(output);
    }

    static Stream<Arguments> upstreamErrors() {
        return Stream.of(
                Arguments.of(Upstream.STOCKLYTICS, 403, "Forbidden", "Stockylitics returned status 403"),
                Arguments.of(Upstream.STOCKLYTICS, 500, "Internal Server Error", "Stockylitics returned status 500"),
                Arguments.of(Upstream.TWITTER, 401, TWITTER_UNAUTHORIZED, "401:Authentication credentials "
                        + "(https://dev.twitter.com/pages/auth) were missing or incorrect. Ensure that you have set "
                        + "valid consumer key/secret, access token/secret, and the system clock is in sync.\n"
                        + "message - Could not authenticate you.\ncode - 32\n"),
                Arguments.of(Upstream.SENTIMENT140, 500, "{\"error\":\"down\"}", "Sentiment140 returned status 500"));
    }

    @ParameterizedTest(name = "[{index}] {0} answers {1}")
    @MethodSource("upstreamErrors")
    void stockStatsFailingAtAnUpstreamLogsNoSecret(Upstream failing, int status, String body, String reason,
            CapturedOutput output) throws Exception {
        stubFailingAt(failing, aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                .withBody(body));

        try (RecordedLog log = new RecordedLog()) {
            HttpTestClient.Response response = stockStats().get(STOCK_STATS);

            assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 500 Internal Server Error");
            assertThat(response.header("Content-Type")).isEqualTo("application/json");
            assertThat(response.bodyAsString()).isEqualTo(reason);
            assertTraceReachedTheLog(log, STOCK_STATS);
        }
        assertEachUpstreamReceivedItsSecrets(EnumSet.range(Upstream.STOCKLYTICS, failing));
        assertLogHoldsNoSentinel(output);
    }

    // The 500 comes 10 s after the request at the earliest, when ST-1's own 10 s read timeout may already have
    // ended the exchange; the failure is therefore asserted on the resource's ERROR line, which names the cause.
    @ParameterizedTest(name = "[{index}] {0} falls silent")
    @EnumSource(Upstream.class)
    void stockStatsTimingOutAtAnUpstreamLogsNoSecret(Upstream stalled, CapturedOutput output) throws Exception {
        stubFailingAt(stalled, (stalled == Upstream.TWITTER ? okJson(TWEETS) : okJson("{}"))
                .withFixedDelay(STALL_MILLIS));
        String cause = stalled == Upstream.TWITTER
                ? "java.lang.IllegalStateException <- twitter4j.TwitterException <- java.net.SocketTimeoutException"
                : "org.springframework.web.client.ResourceAccessException <- java.net.SocketTimeoutException";

        try (RecordedLog log = new RecordedLog()) {
            CompletableFuture<Void> exchange = CompletableFuture.runAsync(() -> stockStats().get(STOCK_STATS));
            await().atMost(Duration.ofSeconds(30)).until(() -> !log.errorLines().isEmpty());
            // Done either way: answered with the 500, or ended by ST-1's read timeout.
            exchange.handle((done, failure) -> null).join();

            assertThat(log.errorLines()).containsExactly("Error searching stock stats: " + cause);
            assertTraceReachedTheLog(log, STOCK_STATS);
        }
        assertEachUpstreamReceivedItsSecrets(EnumSet.range(Upstream.STOCKLYTICS, stalled));
        assertLogHoldsNoSentinel(output);
    }

    static Stream<Arguments> listenerSearches() {
        return Stream.of(
                // Tweet is not Serializable, so a non-empty result fails the listener's Java serialization.
                Arguments.of("tweets", okJson(TWEETS), 500),
                Arguments.of("no tweets", okJson(NO_TWEETS), 200),
                Arguments.of("401", aResponse().withStatus(401).withHeader("Content-Type", "application/json")
                        .withBody(TWITTER_UNAUTHORIZED), 500));
    }

    @ParameterizedTest(name = "[{index}] Twitter answers {0}")
    @MethodSource("listenerSearches")
    void listenerSearchIsSignedWithoutLoggingTheTwitterCredentials(String answer, ResponseDefinitionBuilder search,
            int status, CapturedOutput output) throws Exception {
        upstream.stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(search));

        try (RecordedLog log = new RecordedLog()) {
            HttpTestClient.Response response = HttpTestClient.localhost(port).get("/");

            assertThat(response.status()).as(response::toString).isEqualTo(status);
            if (status == 200) {
                assertThat(response.body()).isEqualTo(SerializationUtils.serialize(new ArrayList<>()));
            }
            assertTraceReachedTheLog(log, "/");
        }
        upstream.verify(1, getRequestedFor(urlPathEqualTo(SEARCH_PATH))
                .withQueryParam("q", equalTo("AAPL"))
                .withQueryParam("since", equalTo("2014-10-26"))
                .withQueryParam("until", equalTo("2014-11-04")));
        assertEachUpstreamReceivedItsSecrets(EnumSet.of(Upstream.TWITTER));
        assertLogHoldsNoSentinel(output);
    }

    /** Stubs every upstream in {@code answering} with its successful answer; the search answers two pages. */
    private static void stubAnswering(Set<Upstream> answering) {
        if (answering.contains(Upstream.STOCKLYTICS)) {
            upstream.stubFor(get(urlPathEqualTo(PRICES_PATH)).willReturn(okJson(PRICES)));
        }
        if (answering.contains(Upstream.TWITTER)) {
            // Every page repeats the same search, so the second answer is the one that ends the paging loop.
            upstream.stubFor(get(urlPathEqualTo(SEARCH_PATH)).inScenario("pages")
                    .whenScenarioStateIs(Scenario.STARTED).willReturn(okJson(TWEETS)).willSetStateTo("last page"));
            upstream.stubFor(get(urlPathEqualTo(SEARCH_PATH)).inScenario("pages")
                    .whenScenarioStateIs("last page").willReturn(okJson(NO_TWEETS)));
        }
        if (answering.contains(Upstream.SENTIMENT140)) {
            upstream.stubFor(post(urlPathEqualTo(CLASSIFY_PATH)).willReturn(okJson(CLASSIFIED)));
        }
    }

    /** Stubs the upstreams before {@code failing} with their answers and {@code failing} with {@code failure}. */
    private static void stubFailingAt(Upstream failing, ResponseDefinitionBuilder failure) {
        stubAnswering(EnumSet.complementOf(EnumSet.range(failing, Upstream.SENTIMENT140)));
        switch (failing) {
            case STOCKLYTICS -> upstream.stubFor(get(urlPathEqualTo(PRICES_PATH)).willReturn(failure));
            case TWITTER -> upstream.stubFor(get(urlPathEqualTo(SEARCH_PATH)).willReturn(failure));
            case SENTIMENT140 -> upstream.stubFor(post(urlPathEqualTo(CLASSIFY_PATH)).willReturn(failure));
        }
    }

    /**
     * Asserts that each upstream in {@code reached} received at least one request and that every one carried its
     * secrets, every search signed with both Twitter secrets, and that no other upstream was called.
     */
    private static void assertEachUpstreamReceivedItsSecrets(Set<Upstream> reached) throws GeneralSecurityException {
        List<LoggedRequest> prices = upstream.findAll(getRequestedFor(urlPathEqualTo(PRICES_PATH)));
        List<LoggedRequest> searches = upstream.findAll(getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        List<LoggedRequest> classifications = upstream.findAll(postRequestedFor(urlPathEqualTo(CLASSIFY_PATH)));
        assertThat(prices).as("Stocklytics requests").hasSize(reached.contains(Upstream.STOCKLYTICS) ? 1 : 0)
                .allSatisfy(request -> assertThat(request.getHeader("api_key")).isEqualTo(STOCKLYTICS_API_KEY));
        if (reached.contains(Upstream.TWITTER)) {
            assertThat(searches).as("Twitter searches").isNotEmpty();
        } else {
            assertThat(searches).as("Twitter searches").isEmpty();
        }
        for (LoggedRequest search : searches) {
            assertThat(search.getHeader("Authorization")).startsWith("OAuth ")
                    .contains("oauth_consumer_key=\"" + TWITTER_API_KEY + "\"")
                    .contains("oauth_token=\"" + TWITTER_ACCESS_KEY + "\"");
            assertSignedWithBothTwitterSecrets(search);
        }
        assertThat(classifications).as("Sentiment140 requests")
                .hasSize(reached.contains(Upstream.SENTIMENT140) ? 1 : 0)
                .allSatisfy(request -> assertThat(request.getHeader("appid")).isEqualTo(SENTIMENT140_API_KEY));
    }

    /**
     * Recomputes the OAuth 1.0a HMAC-SHA1 signature of a received search, keyed with the consumer secret and the
     * access token secret, and asserts it is the one the request carried.
     */
    private static void assertSignedWithBothTwitterSecrets(LoggedRequest search) throws GeneralSecurityException {
        Map<String, String> parameters = new TreeMap<>();
        Matcher oauth = Pattern.compile("(\\w+)=\"([^\"]*)\"").matcher(search.getHeader("Authorization"));
        while (oauth.find()) {
            parameters.put(oauth.group(1), URLDecoder.decode(oauth.group(2), StandardCharsets.UTF_8));
        }
        String signature = parameters.remove("oauth_signature");
        search.getQueryParams().forEach((name, values) -> parameters.put(name, values.firstValue()));
        // Every parameter name is lower-case ASCII, so the raw names sort as their encoded forms do.
        String normalized = parameters.entrySet().stream()
                .map(parameter -> percentEncode(parameter.getKey()) + "=" + percentEncode(parameter.getValue()))
                .collect(Collectors.joining("&"));
        String baseString = "GET&" + percentEncode(upstream.baseUrl() + SEARCH_PATH) + "&" + percentEncode(normalized);
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec((percentEncode(TWITTER_API_SECRET) + "&" + percentEncode(TWITTER_ACCESS_SECRET))
                .getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        byte[] expected = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
        assertThat(signature).as("oauth_signature").isEqualTo(Base64.getEncoder().encodeToString(expected));
    }

    /** RFC 5849 percent-encoding: every octet except the unreserved characters. */
    private static String percentEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A")
                .replace("%7E", "~");
    }

    /**
     * Asserts that the exchange's own events include the TRACE line of the unpinned {@link DispatcherServlet}
     * for {@code target} and the JUL control record bridged at TRACE: TRACE output reached the log on both the
     * commons-logging route twitter4j uses and the JUL route of the JDK's HTTP client.
     */
    private static void assertTraceReachedTheLog(RecordedLog log, String target) {
        List<ILoggingEvent> events = log.events();
        assertThat(events).as("DispatcherServlet TRACE line for " + target).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.TRACE);
            assertThat(event.getLoggerName()).isEqualTo(DispatcherServlet.class.getName());
            assertThat(event.getFormattedMessage()).startsWith("GET \"" + target + "\", parameters=");
        });
        assertThat(events).as("JUL FINEST control record").anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.TRACE);
            assertThat(event.getLoggerName()).isEqualTo(SecretLoggingTest.class.getName());
            assertThat(event.getFormattedMessage()).isEqualTo(log.julControl);
        });
    }

    /**
     * The output stays free of every sentinel for {@link #SETTLE} after the exchange; a failure lists only the lines
     * that hold one.
     */
    private static void assertLogHoldsNoSentinel(CapturedOutput output) {
        await().during(SETTLE).atMost(SETTLE.multipliedBy(4))
                .untilAsserted(() -> assertThat(output.getAll().lines()
                        .filter(line -> SENTINELS.stream().anyMatch(line::contains))
                        .distinct()
                        .toList())
                        .as("captured lines holding a secret").isEmpty());
    }

    private HttpTestClient stockStats() {
        return HttpTestClient.localhost(stockStatsPort);
    }

    private static LoggerContext loggerContext() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    /**
     * Records every event any logger writes while it is open, through an appender on the root logger, after
     * writing a FINEST record of its own through JUL.
     */
    private static final class RecordedLog implements AutoCloseable {

        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        /** The JUL control record's text, unique to this recording. */
        private final String julControl = "JUL FINEST control " + UUID.randomUUID();

        RecordedLog() {
            appender.setContext(loggerContext());
            appender.start();
            root().addAppender(appender);
            java.util.logging.Logger.getLogger(SecretLoggingTest.class.getName()).finest(julControl);
        }

        /** The events recorded so far, read under the appender's lock, under which it appends from any thread. */
        List<ILoggingEvent> events() {
            synchronized (appender) {
                return List.copyOf(appender.list);
            }
        }

        /** The ERROR lines of the stock statistics resource recorded so far. */
        List<String> errorLines() {
            return events().stream()
                    .filter(event -> event.getLevel() == Level.ERROR
                            && StockStatsService.class.getName().equals(event.getLoggerName()))
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        @Override
        public void close() {
            root().detachAppender(appender);
            appender.stop();
        }

        private static Logger root() {
            return loggerContext().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        }
    }
}
