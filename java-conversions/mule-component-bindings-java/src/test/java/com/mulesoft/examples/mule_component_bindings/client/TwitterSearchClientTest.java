/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.and;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.mule_component_bindings.config.StockStatsProperties;
import com.mulesoft.examples.mule_component_bindings.mapper.TwitterResponseMapper;
import com.mulesoft.examples.mule_component_bindings.model.Tweet;
import com.mulesoft.examples.mule_component_bindings.service.TwitterSearchService;

import twitter4j.HttpClientConfiguration;
import twitter4j.JSONException;
import twitter4j.QueryResult;
import twitter4j.Status;
import twitter4j.TwitterException;
import twitter4j.conf.Configuration;
import twitter4j.conf.ConfigurationBuilder;

/**
 * The contract of {@link TwitterSearchClient}
 * against a WireMock stand-in for the Twitter v1.1 REST API: the search sends the query and date bounds signed
 * with the configured OAuth credentials and returns the parsed statuses, and sends no date bound it is not given;
 * the twitter4j instance is built on the first search, so construction needs no credentials, and is reused
 * afterwards; a credential without text fails each search, naming the first such key, before twitter4j is
 * configured or any request is sent; a rejected search or a response without {@code search_metadata} raises
 * {@link TwitterException},
 * a rejection carrying Twitter's status and error code and never sent twice; Spring binds the search service's
 * {@link TwitterSearchService.RawSearch} port to this client; the configuration pins 10 s connect and read timeouts
 * and no twitter4j retry whatever twitter4j would otherwise inherit; a connection reset or closed before any
 * response gets the search re-sent once, unchanged, by the JDK transport under twitter4j, as documented on the
 * client; and an upstream that never answers fails the search once, after the 10 s read timeout, with a
 * {@link SocketTimeoutException} cause.
 */
class TwitterSearchClientTest {

    /** A v1.1 search response: twitter4j reads both {@code statuses} and {@code search_metadata}. */
    private static final String SEARCH_RESPONSE = "{\"statuses\":["
            + "{\"id\":529013410513453056,\"id_str\":\"529013410513453056\",\"text\":\"$AAPL closes higher\","
            + "\"created_at\":\"Sun Nov 02 21:30:00 +0000 2014\"},"
            + "{\"id\":529784356710236160,\"id_str\":\"529784356710236160\",\"text\":\"$AAPL up\","
            + "\"created_at\":\"Tue Nov 04 00:35:00 +0000 2014\"}],"
            + "\"search_metadata\":{\"completed_in\":0.021,\"max_id\":529784356710236160,"
            + "\"max_id_str\":\"529784356710236160\",\"query\":\"AAPL\",\"refresh_url\":\"?since_id=529784356710236160"
            + "&q=AAPL\",\"count\":15,\"since_id\":0,\"since_id_str\":\"0\"}}";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @BeforeEach
    void setUp() {
        wm.resetAll();
    }

    @Test
    void searchSendsTheQueryAndDatesSignedWithTheConfiguredCredentials() throws TwitterException {
        wm.stubFor(get(urlPathEqualTo("/1.1/search/tweets.json")).willReturn(okJson(SEARCH_RESPONSE)));

        // The "$" + stock query of the binding flow: sent as q=%24AAPL, which WireMock decodes before matching.
        QueryResult result = new TwitterSearchClient(properties()).search("$AAPL", "2014-10-26", "2014-11-04");

        assertThat(result.getTweets()).extracting(Status::getId, Status::getText).containsExactly(
                tuple(529013410513453056L, "$AAPL closes higher"), tuple(529784356710236160L, "$AAPL up"));
        // One pattern for the header: WireMock keeps only the last withHeader pattern given for a name.
        wm.verify(1, getRequestedFor(urlPathEqualTo("/1.1/search/tweets.json"))
                .withQueryParam("q", equalTo("$AAPL"))
                .withQueryParam("since", equalTo("2014-10-26"))
                .withQueryParam("until", equalTo("2014-11-04"))
                .withHeader("Authorization", and(matching("OAuth .+"),
                        containing("oauth_consumer_key=\"test-api-key\""),
                        containing("oauth_token=\"test-access-key\""))));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    @Test
    void nullSinceAndUntilAreNotSent() throws TwitterException {
        wm.stubFor(get(urlPathEqualTo("/1.1/search/tweets.json")).willReturn(okJson(SEARCH_RESPONSE)));

        new TwitterSearchClient(properties()).search("AAPL", null, null);

        wm.verify(1, getRequestedFor(urlPathEqualTo("/1.1/search/tweets.json"))
                .withQueryParam("q", equalTo("AAPL"))
                .withQueryParam("since", absent())
                .withQueryParam("until", absent()));
    }

    @Test
    void twitterInstanceIsCreatedLazily() throws TwitterException {
        wm.stubFor(get(urlPathEqualTo("/1.1/search/tweets.json")).willReturn(okJson(SEARCH_RESPONSE)));
        ReadCountingProperties noCredentials = new ReadCountingProperties(
                properties(wm.baseUrl() + "/1.1/", "", "", "", ""));

        // Start-up needs no credentials: constructing the client neither reads its configuration nor calls Twitter.
        assertDoesNotThrow(() -> new TwitterSearchClient(noCredentials));
        assertThat(noCredentials.restBaseUrlReads).hasValue(0);
        wm.verify(0, anyRequestedFor(anyUrl()));

        ReadCountingProperties configured = new ReadCountingProperties(properties());
        TwitterSearchClient client = new TwitterSearchClient(configured);
        assertThat(configured.restBaseUrlReads).hasValue(0);

        assertThat(client.search("$AAPL", "2014-10-26", "2014-11-04").getTweets()).hasSize(2);
        assertThat(client.search("$AAPL", "2014-10-26", "2014-11-04").getTweets()).hasSize(2);

        // Configured once, on the first search, and that instance served both searches.
        assertThat(configured.restBaseUrlReads).hasValue(1);
        wm.verify(2, getRequestedFor(urlPathEqualTo("/1.1/search/tweets.json")));
    }

    /**
     * Each OAuth credential alone as {@code null}, empty and whitespace only, then two cases with several missing,
     * which name the first in the order consumer key, consumer secret, access token, access token secret.
     */
    static Stream<Arguments> credentialsWithoutText() {
        Map<String, String> messages = new LinkedHashMap<>();
        messages.put("twitter.apiKey", "Twitter API key (twitter.apiKey) is not configured");
        messages.put("twitter.apiSecret", "Twitter API secret (twitter.apiSecret) is not configured");
        messages.put("twitter.accessKey", "Twitter access token (twitter.accessKey) is not configured");
        messages.put("twitter.accessSecret", "Twitter access token secret (twitter.accessSecret) is not configured");
        List<Arguments> cases = new ArrayList<>();
        messages.forEach((key, message) -> {
            for (String blank : Arrays.asList(null, "", "   ")) {
                cases.add(Arguments.of(List.of(key), Named.of(blank == null ? "null" : '"' + blank + '"', blank),
                        message));
            }
        });
        cases.add(Arguments.of(List.copyOf(messages.keySet()), Named.of("\"\"", ""),
                messages.get("twitter.apiKey")));
        cases.add(Arguments.of(List.of("twitter.accessKey", "twitter.accessSecret"), Named.of("\"   \"", "   "),
                messages.get("twitter.accessKey")));
        return cases.stream();
    }

    @ParameterizedTest(name = "[{index}] {0} = {1}")
    @MethodSource("credentialsWithoutText")
    void credentialWithoutTextFailsEverySearchNamingItsKeyBeforeAnyRequest(List<String> blankKeys, String blank,
            String expectedMessage) {
        wm.stubFor(get(urlPathEqualTo("/1.1/search/tweets.json")).willReturn(okJson(SEARCH_RESPONSE)));
        ReadCountingProperties properties = new ReadCountingProperties(properties(wm.baseUrl() + "/1.1/",
                blankKeys.contains("twitter.apiKey") ? blank : "test-api-key",
                blankKeys.contains("twitter.apiSecret") ? blank : "test-api-secret",
                blankKeys.contains("twitter.accessKey") ? blank : "test-access-key",
                blankKeys.contains("twitter.accessSecret") ? blank : "test-access-secret"));
        TwitterSearchClient client = new TwitterSearchClient(properties);

        // Searched twice: a failed check builds and caches nothing, so the second search is checked again.
        for (int search = 1; search <= 2; search++) {
            assertThatThrownBy(() -> client.search("$AAPL", "2014-10-26", "2014-11-04"))
                    .as("search %d", search)
                    .isExactlyInstanceOf(IllegalStateException.class)
                    .hasMessage(expectedMessage)
                    .hasNoCause();
        }

        // The check runs before twitter4j is configured, which is the only read of the REST base URL, and no
        // request leaves the client.
        assertThat(properties.restBaseUrlReads).hasValue(0);
        wm.verify(0, anyRequestedFor(anyUrl()));
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test
    void unauthorizedRaisesTwitterException() {
        wm.stubFor(get(urlPathEqualTo("/1.1/search/tweets.json")).willReturn(aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"errors\":[{\"code\":32,\"message\":\"Could not authenticate you.\"}]}")));
        TwitterSearchClient client = new TwitterSearchClient(properties());

        TwitterException ex = assertThrows(TwitterException.class,
                () -> client.search("$AAPL", "2014-10-26", "2014-11-04"));

        assertThat(ex.getStatusCode()).isEqualTo(401);
        assertThat(ex.getErrorCode()).isEqualTo(32);
        // Exactly one request: a rejected search is never retried.
        wm.verify(1, getRequestedFor(urlPathEqualTo("/1.1/search/tweets.json")));
    }

    /**
     * Pins the documented transport re-send. twitter4j's own retries are off, but the JDK {@code HttpURLConnection}
     * under twitter4j sends the bodiless search GET a second time, byte for byte with the same OAuth signature, when
     * the connection is reset or closes before any response, as the original Twitter connector's twitter4j did over
     * the same transport; the search then fails with a {@link TwitterException}. A timed-out search is sent once
     * ({@link #searchWithNoResponseFailsOnceAfterTheTenSecondReadTimeout}).
     */
    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE"})
    void connectionLostBeforeAnyResponseIsSentAgainOnceByTheJdkTransport(Fault fault) {
        wm.stubFor(get(urlPathEqualTo("/1.1/search/tweets.json")).willReturn(aResponse().withFault(fault)));
        TwitterSearchClient client = new TwitterSearchClient(properties());

        TwitterException ex = assertThrows(TwitterException.class,
                () -> client.search("$AAPL", "2014-10-26", "2014-11-04"));

        assertThat(causeChain(ex)).anyMatch(IOException.class::isInstance);
        List<LoggedRequest> searches = wm.findAll(getRequestedFor(urlPathEqualTo("/1.1/search/tweets.json")));
        assertThat(searches).hasSize(2);
        // A twitter4j retry would sign again with a new nonce and timestamp; the transport re-sends the same request.
        assertThat(searches.get(1).getUrl()).isEqualTo(searches.get(0).getUrl());
        assertThat(searches.get(1).getHeader("Authorization")).isEqualTo(searches.get(0).getHeader("Authorization"));
        assertThat(wm.getAllServeEvents()).hasSize(2);
    }

    @Test
    void missingSearchMetadataRaisesTwitterException() {
        wm.stubFor(get(urlPathEqualTo("/1.1/search/tweets.json")).willReturn(okJson("{\"statuses\":[]}")));
        TwitterSearchClient client = new TwitterSearchClient(properties());

        TwitterException ex = assertThrows(TwitterException.class,
                () -> client.search("$AAPL", "2014-10-26", "2014-11-04"));

        assertThat(ex).hasCauseInstanceOf(JSONException.class);
        wm.verify(1, getRequestedFor(urlPathEqualTo("/1.1/search/tweets.json")));
    }

    @Test
    void springBindsTheSearchServicePortToThisClient() {
        wm.stubFor(get(urlPathEqualTo("/1.1/search/tweets.json")).willReturn(okJson(SEARCH_RESPONSE)));

        new ApplicationContextRunner()
                .withBean(StockStatsProperties.class, TwitterSearchClientTest::properties)
                .withUserConfiguration(TwitterSearchClient.class, TwitterResponseMapper.class,
                        TwitterSearchService.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TwitterSearchService.RawSearch.class))
                            .isSameAs(context.getBean(TwitterSearchClient.class));

                    // The bound service's search of the binding flow reaches Twitter through this client.
                    List<Tweet> tweets = context.getBean(TwitterSearchService.class)
                            .search("AAPL", new SimpleDateFormat("yyyy-MM-dd").parse("2014-10-26"), 100, 1);

                    assertThat(tweets).extracting(Tweet::getId, Tweet::getText).containsExactly(
                            tuple("529013410513453056", "$AAPL closes higher"),
                            tuple("529784356710236160", "$AAPL up"));
                });
        wm.verify(1, getRequestedFor(urlPathEqualTo("/1.1/search/tweets.json"))
                .withQueryParam("q", equalTo("$AAPL"))
                .withQueryParam("since", equalTo("2014-10-26"))
                .withQueryParam("until", equalTo("2014-11-05")));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    static Stream<Arguments> externalTwitter4jConfigurations() {
        return Stream.of(
                Arguments.of(Named.of("none, so twitter4j's own defaults", Map.of()), 20_000, 120_000, 0),
                Arguments.of(Named.of("twitter4j.* system properties with other values", Map.of(
                        "twitter4j.http.connectionTimeout", "1",
                        "twitter4j.http.readTimeout", "1",
                        "twitter4j.http.retryCount", "3")), 1, 1, 3));
    }

    @ParameterizedTest
    @MethodSource("externalTwitter4jConfigurations")
    void configurationPinsTenSecondTimeoutsAndNoRetryOverWhatTwitter4jWouldInherit(
            Map<String, String> systemProperties, int inheritedConnectionTimeout, int inheritedReadTimeout,
            int inheritedRetryCount) {
        Map<String, String> previous = new HashMap<>();
        systemProperties.keySet().forEach(key -> previous.put(key, System.getProperty(key)));
        try {
            systemProperties.forEach(System::setProperty);

            // What twitter4j applies without explicit settings, so each case is known to differ from the pins.
            HttpClientConfiguration inherited = new ConfigurationBuilder().build().getHttpClientConfiguration();
            assertThat(inherited.getHttpConnectionTimeout()).isEqualTo(inheritedConnectionTimeout);
            assertThat(inherited.getHttpReadTimeout()).isEqualTo(inheritedReadTimeout);
            assertThat(inherited.getHttpRetryCount()).isEqualTo(inheritedRetryCount);

            Configuration configuration = TwitterSearchClient.configuration(properties());

            HttpClientConfiguration pinned = configuration.getHttpClientConfiguration();
            assertThat(pinned.getHttpConnectionTimeout()).isEqualTo(10_000);
            assertThat(pinned.getHttpReadTimeout()).isEqualTo(10_000);
            assertThat(pinned.getHttpRetryCount()).isZero();
            assertThat(configuration.getOAuthConsumerKey()).isEqualTo("test-api-key");
            assertThat(configuration.getOAuthConsumerSecret()).isEqualTo("test-api-secret");
            assertThat(configuration.getOAuthAccessToken()).isEqualTo("test-access-key");
            assertThat(configuration.getOAuthAccessTokenSecret()).isEqualTo("test-access-secret");
            assertThat(configuration.getRestBaseURL()).isEqualTo(wm.baseUrl() + "/1.1/");
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    @Test
    void searchWithNoResponseFailsOnceAfterTheTenSecondReadTimeout() throws IOException, InterruptedException {
        // A raw socket rather than a delayed WireMock stub: WireMock journals a delayed request only once its delay
        // ends, so it could not count the attempts when the search fails. Here each connection is counted as it
        // is accepted, and the upstream reads the request and never answers.
        AtomicInteger accepted = new AtomicInteger();
        List<Socket> connections = Collections.synchronizedList(new ArrayList<>());
        List<IOException> closeFailures = new ArrayList<>();
        ServerSocket upstream = new ServerSocket(0);
        Thread server = new Thread(() -> acceptAndFallSilent(upstream, accepted, connections),
                "silent-twitter-upstream");
        server.setDaemon(true);
        try {
            server.start();
            TwitterSearchClient client =
                    new TwitterSearchClient(properties("http://127.0.0.1:" + upstream.getLocalPort() + "/1.1/"));

            long start = System.nanoTime();
            // The watchdog is independent of the client's own timeout, so a search that never ends, or one
            // repeated by a retry, fails the test here instead of hanging the build.
            Throwable thrown = assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> catchThrowable(() -> client.search("AAPL", "2014-10-26", "2014-11-04")));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(thrown).isInstanceOf(TwitterException.class);
            assertThat(causeChain(thrown)).anyMatch(SocketTimeoutException.class::isInstance);
            assertThat(elapsed).isBetween(Duration.ofMillis(9_950), Duration.ofSeconds(14));
            // Exactly one connection: a timed-out search is never retried.
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

    /**
     * Accepts connections until {@code upstream} is closed, counting each in {@code accepted} as soon as it is
     * accepted. On each it reads the request head, which is the whole of a search request, and then leaves the
     * connection open without answering.
     *
     * <p>Each connection is added to {@code connections}, a synchronized list the caller closes after closing
     * {@code upstream}. A connection accepted while {@code upstream} was being closed may join the list after the
     * caller has closed its contents, so this loop closes that one itself and stops.
     */
    private static void acceptAndFallSilent(ServerSocket upstream, AtomicInteger accepted, List<Socket> connections) {
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

    /** The {@code application-test.yml} values, with every upstream on WireMock. */
    private static StockStatsProperties properties() {
        return properties(wm.baseUrl() + "/1.1/");
    }

    /** The {@code application-test.yml} values, with the Twitter REST API at {@code twitterRestBaseUrl}. */
    private static StockStatsProperties properties(String twitterRestBaseUrl) {
        return properties(twitterRestBaseUrl, "test-api-key", "test-api-secret", "test-access-key",
                "test-access-secret");
    }

    /**
     * The Twitter REST API at {@code twitterRestBaseUrl} with the given OAuth consumer key and secret and access
     * token and secret, and the other two services on WireMock with their {@code application-test.yml} keys.
     */
    private static StockStatsProperties properties(String twitterRestBaseUrl, String apiKey, String apiSecret,
            String accessKey, String accessSecret) {
        return new StockStatsProperties(accessKey, accessSecret, apiKey, apiSecret, twitterRestBaseUrl,
                "test-stocklytics-key", wm.baseUrl() + "/", "test-sentiment140-key", wm.baseUrl() + "/");
    }

    /**
     * A copy of {@code source} that counts reads of the Twitter REST base URL, which the client reads once each time
     * it builds a twitter4j instance, so the count tells when and how often that happens.
     */
    private static final class ReadCountingProperties extends StockStatsProperties {

        private final AtomicInteger restBaseUrlReads = new AtomicInteger();

        ReadCountingProperties(StockStatsProperties source) {
            super(source.twitterAccessKey(), source.twitterAccessSecret(), source.twitterApiKey(),
                    source.twitterApiSecret(), source.twitterRestBaseUrl(), source.stocklyticsApiKey(),
                    source.stocklyticsBaseUrl(), source.sentiment140ApiKey(), source.sentiment140BaseUrl());
        }

        @Override
        public String twitterRestBaseUrl() {
            restBaseUrlReads.incrementAndGet();
            return super.twitterRestBaseUrl();
        }
    }
}
