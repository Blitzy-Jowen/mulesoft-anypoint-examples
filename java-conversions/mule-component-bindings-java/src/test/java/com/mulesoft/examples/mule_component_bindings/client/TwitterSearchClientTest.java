/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.client;

import static com.github.tomakehurst.wiremock.client.WireMock.and;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
import org.junit.jupiter.params.provider.MethodSource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.mule_component_bindings.config.StockStatsProperties;

import twitter4j.HttpClientConfiguration;
import twitter4j.QueryResult;
import twitter4j.Status;
import twitter4j.TwitterException;
import twitter4j.conf.Configuration;
import twitter4j.conf.ConfigurationBuilder;

/**
 * The contract of {@link TwitterSearchClient}, which replaces the Mule Twitter connector's {@code twitter:search},
 * against a WireMock stand-in for the Twitter v1.1 REST API: the search sends the query and date bounds signed
 * with the configured OAuth credentials and returns the parsed statuses; the configuration pins 10 s connect and
 * read timeouts and no HTTP retry whatever twitter4j would otherwise inherit; and an upstream that never answers
 * fails the search once, after the 10 s read timeout, with a {@link SocketTimeoutException} cause.
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

        QueryResult result = new TwitterSearchClient(properties()).search("AAPL", "2014-10-26", "2014-11-04");

        assertThat(result.getTweets()).extracting(Status::getId, Status::getText).containsExactly(
                tuple(529013410513453056L, "$AAPL closes higher"), tuple(529784356710236160L, "$AAPL up"));
        wm.verify(1, getRequestedFor(urlPathEqualTo("/1.1/search/tweets.json"))
                .withQueryParam("q", equalTo("AAPL"))
                .withQueryParam("since", equalTo("2014-10-26"))
                .withQueryParam("until", equalTo("2014-11-04"))
                .withHeader("Authorization", and(containing("oauth_consumer_key=\"test-api-key\""),
                        containing("oauth_token=\"test-access-key\""))));
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
        return new StockStatsProperties("test-access-key", "test-access-secret", "test-api-key", "test-api-secret",
                twitterRestBaseUrl, "test-stocklytics-key", wm.baseUrl() + "/", "test-sentiment140-key",
                wm.baseUrl() + "/");
    }
}
