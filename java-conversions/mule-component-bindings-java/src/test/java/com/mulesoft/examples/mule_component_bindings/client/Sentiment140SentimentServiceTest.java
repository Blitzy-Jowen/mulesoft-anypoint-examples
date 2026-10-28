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
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.mule_component_bindings.config.StockStatsProperties;
import com.mulesoft.examples.mule_component_bindings.model.Sentiment;
import com.mulesoft.examples.mule_component_bindings.model.Tweet;

/**
 * The contract of {@link Sentiment140SentimentService}, the port of the original
 * {@code stockstats.impl.sentiment140.Sentiment140SentimentService}, against a WireMock stand-in for
 * {@code http://www.sentiment140.com/api/}: the bulk classification is posted with the original headers and the
 * tweets' JSON as UTF-8 bytes, each returned polarity sets the sentiment of the tweet with its id and tweets without
 * a result keep none, a status other than 200 fails with the original message, an unreadable body or an unknown id
 * fails with a plain {@link RuntimeException}, an application id without text fails naming
 * {@code stockstats.sentiment140ApiKey} before any request is sent, even for no tweets, and through the timed
 * transport an upstream that never answers, or
 * that sends its headers and stalls its body, fails once with a {@link SocketTimeoutException} cause within the
 * timeout; a connection reset or closed before any response fails the call after one POST; the constructor accepts
 * only a timeout the transport can enforce.
 */
class Sentiment140SentimentServiceTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @BeforeEach
    void setUp() {
        wm.resetAll();
    }

    @Test
    void classifySendsOriginalRequestAndMapsPolarity() {
        // The results come back in another order than the tweets were sent, so each one must find its tweet by id.
        wm.stubFor(post(urlEqualTo("/bulkClassifyJson")).willReturn(okJson("{\"data\":["
                + "{\"id\":\"3\",\"text\":\"I hate it\",\"polarity\":0},"
                + "{\"id\":\"1\",\"text\":\"I love it\",\"polarity\":4},"
                + "{\"id\":\"4\",\"text\":\"not sure\",\"polarity\":3},"
                + "{\"id\":\"2\",\"text\":\"It is ok\",\"polarity\":2}]}")));
        List<Tweet> tweets = List.of(tweet("1", "I love it"), tweet("2", "It is ok"), tweet("3", "I hate it"),
                tweet("4", "not sure"));

        newService().classify(tweets);

        // Polarity 0 is negative, 2 neutral and any other value, 3 included, positive, as in the original.
        assertThat(tweets).extracting(Tweet::getSentiment)
                .containsExactly(Sentiment.POSITIVE, Sentiment.NEUTRAL, Sentiment.NEGATIVE, Sentiment.POSITIVE);
        // An exact string, not a JSON comparison: one entry per tweet in list order, keys id then text, compact.
        wm.verify(1, postRequestedFor(urlEqualTo("/bulkClassifyJson"))
                .withHeader("appid", equalTo("test-sentiment140-key"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("Content-Type", containing("text/plain"))
                .withRequestBody(equalTo("{\"data\":[{\"id\":\"1\",\"text\":\"I love it\"},"
                        + "{\"id\":\"2\",\"text\":\"It is ok\"},{\"id\":\"3\",\"text\":\"I hate it\"},"
                        + "{\"id\":\"4\",\"text\":\"not sure\"}]}")));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    // A non-ASCII text on purpose: the client posts UTF-8 bytes, where a String body declared text/plain without a
    // charset would go out as ISO-8859-1. Jackson writes these characters unescaped, so the bytes compare directly.
    @Test
    void nonAsciiTextIsPostedAsUtf8Bytes() {
        wm.stubFor(post(urlEqualTo("/bulkClassifyJson"))
                .willReturn(okJson("{\"data\":[{\"id\":\"1\",\"polarity\":4}]}")));
        List<Tweet> tweets = List.of(tweet("1", "$AAPL \u00e9t\u00e9 high"));

        newService().classify(tweets);

        assertThat(tweets.get(0).getSentiment()).isEqualTo(Sentiment.POSITIVE);
        wm.verify(1, postRequestedFor(urlEqualTo("/bulkClassifyJson"))
                .withHeader("Content-Type", containing("text/plain"))
                .withRequestBody(binaryEqualTo(
                        "{\"data\":[{\"id\":\"1\",\"text\":\"$AAPL \u00e9t\u00e9 high\"}]}".getBytes(UTF_8))));
    }

    @Test
    void tweetMissingFromResponseKeepsNullSentiment() {
        wm.stubFor(post(urlEqualTo("/bulkClassifyJson"))
                .willReturn(okJson("{\"data\":[{\"id\":\"1\",\"polarity\":0}]}")));
        List<Tweet> tweets = List.of(tweet("1", "I hate it"), tweet("2", "no result for me"));

        newService().classify(tweets);

        assertThat(tweets.get(0).getSentiment()).isEqualTo(Sentiment.NEGATIVE);
        assertThat(tweets.get(1).getSentiment()).isNull();
    }

    @Test
    void emptyListStillPostsEmptyData() {
        wm.stubFor(post(urlEqualTo("/bulkClassifyJson")).willReturn(okJson("{\"data\":[]}")));

        assertThatCode(() -> newService().classify(new ArrayList<>())).doesNotThrowAnyException();

        wm.verify(1, postRequestedFor(urlEqualTo("/bulkClassifyJson")).withRequestBody(equalTo("{\"data\":[]}")));
        assertThat(wm.getAllServeEvents()).hasSize(1);
    }

    /**
     * An application id that is {@code null}, empty or whitespace only, each with no tweets, which would still be
     * posted, and with one tweet.
     */
    static Stream<Arguments> applicationIdsWithoutText() {
        List<Arguments> cases = new ArrayList<>();
        for (String blank : Arrays.asList(null, "", "   ")) {
            Named<String> applicationId = Named.of(blank == null ? "null" : '"' + blank + '"', blank);
            cases.add(Arguments.of(applicationId, Named.of("no tweets", 0)));
            cases.add(Arguments.of(applicationId, Named.of("one tweet", 1)));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "[{index}] appid {0}, {1}")
    @MethodSource("applicationIdsWithoutText")
    void applicationIdWithoutTextFailsNamingTheKeyBeforeAnyRequest(String applicationId, int tweetCount) {
        wm.stubFor(post(urlEqualTo("/bulkClassifyJson"))
                .willReturn(okJson("{\"data\":[{\"id\":\"1\",\"polarity\":4}]}")));
        List<Tweet> tweets = new ArrayList<>();
        for (int id = 1; id <= tweetCount; id++) {
            tweets.add(tweet(String.valueOf(id), "$AAPL"));
        }
        Sentiment140SentimentService service = new Sentiment140SentimentService(RestClient.builder(),
                new StockStatsProperties("test-access-key", "test-access-secret", "test-api-key", "test-api-secret",
                        wm.baseUrl() + "/1.1/", "test-stocklytics-key", wm.baseUrl() + "/", applicationId,
                        wm.baseUrl() + "/"));

        assertThatThrownBy(() -> service.classify(tweets))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Sentiment140 application ID (stockstats.sentiment140ApiKey) is not configured")
                .hasNoCause();
        assertThat(tweets).hasSize(tweetCount).allSatisfy(tweet -> assertThat(tweet.getSentiment()).isNull());
        wm.verify(0, anyRequestedFor(anyUrl()));
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    // 201 is included because the original accepts exactly 200, not any 2xx.
    @ParameterizedTest
    @ValueSource(ints = {201, 403, 404, 500})
    void statusOtherThanOkFailsOnceWithTheOriginalMessage(int status) {
        wm.stubFor(post(urlEqualTo("/bulkClassifyJson")).willReturn(aResponse().withStatus(status)));
        List<Tweet> tweets = List.of(tweet("1", "$AAPL"));
        Sentiment140SentimentService service = newService();

        assertThatThrownBy(() -> service.classify(tweets))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("Sentiment140 returned status " + status);
        assertThat(wm.getAllServeEvents()).hasSize(1);
        assertThat(tweets.get(0).getSentiment()).isNull();
    }

    /**
     * A bulk classification whose connection is reset, or closed before any response, fails with a
     * {@link ResourceAccessException} and is posted exactly once: the JDK {@code HttpURLConnection} re-sends a
     * request once after such a failure only when it has no streamed body, and this POST streams its body.
     */
    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE"})
    void connectionFaultFailsAndIsPostedOnce(Fault fault) {
        wm.stubFor(post(urlEqualTo("/bulkClassifyJson")).willReturn(aResponse().withFault(fault)));
        List<Tweet> tweets = List.of(tweet("1", "$AAPL"));
        Sentiment140SentimentService service = newService();

        Throwable thrown = catchThrowable(() -> service.classify(tweets));

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        assertThat(causeChain(thrown)).anyMatch(IOException.class::isInstance);
        wm.verify(1, postRequestedFor(urlEqualTo("/bulkClassifyJson")));
        assertThat(wm.getAllServeEvents()).hasSize(1);
        assertThat(tweets.get(0).getSentiment()).isNull();
    }

    @Test
    void malformedBodyRaisesRuntimeException() {
        wm.stubFor(post(urlEqualTo("/bulkClassifyJson")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("not json")));
        List<Tweet> tweets = List.of(tweet("1", "I love it"));
        Sentiment140SentimentService service = newService();

        // The original wraps every parse failure in a plain RuntimeException.
        assertThatThrownBy(() -> service.classify(tweets))
                .isExactlyInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(JsonProcessingException.class);
        assertThat(tweets.get(0).getSentiment()).isNull();
    }

    // An id no tweet was sent with, a result without an id, and a numeric id, which has no textual value.
    @ParameterizedTest
    @ValueSource(strings = {"{\"data\":[{\"id\":\"99\",\"polarity\":4}]}", "{\"data\":[{\"polarity\":4}]}",
            "{\"data\":[{\"id\":1,\"polarity\":4}]}"})
    void unknownResponseIdRaisesRuntimeException(String responseBody) {
        wm.stubFor(post(urlEqualTo("/bulkClassifyJson")).willReturn(okJson(responseBody)));
        List<Tweet> tweets = List.of(tweet("1", "I love it"));
        Sentiment140SentimentService service = newService();

        // The original looks the id up and calls setSentiment on the null result; that NPE is wrapped as well. Its
        // NPE had no message, so the wrapper's message, which the 500 body carries, names only the NPE's class.
        Throwable thrown = catchThrowable(() -> service.classify(tweets));

        assertThat(thrown).isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("java.lang.NullPointerException")
                .cause().isExactlyInstanceOf(NullPointerException.class).hasMessage(null);
        assertThat(tweets.get(0).getSentiment()).isNull();
    }

    static Stream<Arguments> silentUpstreams() {
        return Stream.of(
                Arguments.of(Named.of("no response at all", new byte[0])),
                Arguments.of(Named.of("headers, then a body stalled after 9 of its 1000 bytes",
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n"
                                + "{\"data\":[").getBytes(US_ASCII))));
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
                "silent-sentiment140-upstream");
        server.setDaemon(true);
        try {
            server.start();
            Sentiment140SentimentService service = new Sentiment140SentimentService(RestClient.builder(),
                    properties("http://127.0.0.1:" + upstream.getLocalPort() + "/"), timeout);
            List<Tweet> tweets = List.of(tweet("1", "$AAPL"));

            long start = System.nanoTime();
            // The watchdog is independent of the service's own timeout, so a call that never ends fails the test
            // here instead of hanging the build.
            Throwable thrown = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> catchThrowable(() -> service.classify(tweets)));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(thrown).isInstanceOf(ResourceAccessException.class);
            assertThat(causeChain(thrown)).anyMatch(SocketTimeoutException.class::isInstance);
            assertThat(elapsed).isBetween(timeout.minusMillis(50), timeout.plusMillis(1500));
            // Exactly one connection: a timed-out request is never retried.
            assertThat(accepted).hasValue(1);
            assertThat(tweets.get(0).getSentiment()).isNull();
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
        assertThatThrownBy(() -> new Sentiment140SentimentService(RestClient.builder(), properties(), timeout))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Sentiment140 timeout must be from 1 ms to 2147483647 ms, was " + timeout);
    }

    static Stream<Arguments> boundaryTimeouts() {
        return Stream.of(
                Arguments.of(Named.of("1 ms", Duration.ofMillis(1))),
                Arguments.of(Named.of("Integer.MAX_VALUE ms", Duration.ofMillis(Integer.MAX_VALUE))));
    }

    @ParameterizedTest
    @MethodSource("boundaryTimeouts")
    void constructorAcceptsTheBoundaryTimeouts(Duration timeout) {
        assertThatCode(() -> new Sentiment140SentimentService(RestClient.builder(), properties(), timeout))
                .doesNotThrowAnyException();
    }

    /**
     * Accepts connections until {@code upstream} is closed, counting each in {@code accepted} as soon as it is
     * accepted. On each it reads the whole request, sends {@code sentBeforeStalling}, which is empty or a response
     * whose body is shorter than its {@code Content-Length}, and leaves the connection open and silent, so the
     * response never completes.
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
                readRequest(connection.getInputStream());
                OutputStream out = connection.getOutputStream();
                out.write(sentBeforeStalling);
                out.flush();
            }
        } catch (IOException closed) {
            // The test closed the listening socket, or the client dropped the connection: nothing more to serve.
        }
    }

    /** Reads one request head and the {@code Content-Length} bytes of its body. */
    private static void readRequest(InputStream in) throws IOException {
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < terminator.length) {
            int next = in.read();
            if (next < 0) {
                throw new EOFException("Request head ended after " + head.size() + " bytes");
            }
            head.write(next);
            matched = next == terminator[matched] ? matched + 1 : (next == '\r' ? 1 : 0);
        }
        int contentLength = 0;
        for (String line : head.toString(US_ASCII).split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                contentLength = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        if (in.readNBytes(contentLength).length != contentLength) {
            throw new EOFException("Request body shorter than its Content-Length " + contentLength);
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

    /** The service as the application builds it, with every upstream on WireMock. */
    private static Sentiment140SentimentService newService() {
        return new Sentiment140SentimentService(RestClient.builder(), properties());
    }

    private static Tweet tweet(String id, String text) {
        Tweet tweet = new Tweet();
        tweet.setId(id);
        tweet.setText(text);
        return tweet;
    }

    /** The {@code application-test.yml} values, with every upstream on WireMock. */
    private static StockStatsProperties properties() {
        return properties(wm.baseUrl() + "/");
    }

    /** The {@code application-test.yml} values, with Sentiment140 at {@code sentiment140BaseUrl}. */
    private static StockStatsProperties properties(String sentiment140BaseUrl) {
        return new StockStatsProperties("test-access-key", "test-access-secret", "test-api-key", "test-api-secret",
                wm.baseUrl() + "/1.1/", "test-stocklytics-key", wm.baseUrl() + "/", "test-sentiment140-key",
                sentiment140BaseUrl);
    }
}
