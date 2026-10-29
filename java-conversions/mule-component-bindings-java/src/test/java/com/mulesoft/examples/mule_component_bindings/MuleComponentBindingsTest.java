/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;

import org.apache.commons.lang3.SerializationUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;
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

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.mule_component_bindings.client.TwitterSearchClient;
import com.mulesoft.examples.mule_component_bindings.model.StockStats;
import com.mulesoft.examples.mule_component_bindings.model.Tweet;
import com.mulesoft.examples.mule_component_bindings.service.SentimentService;
import com.mulesoft.examples.mule_component_bindings.service.StockService;
import com.mulesoft.examples.mule_component_bindings.service.TwitterSearchService;
import com.mulesoft.examples.mule_component_bindings.support.HttpTestClient;
import com.mulesoft.examples.mule_component_bindings.support.PortInUseRetryingContextLoader;

import twitter4j.QueryResult;

/**
 * A GET of {@code /api/stockStats?stock=AAPL&date=2012-10-26} on the stock-statistics listener (SC-10's additional
 * listener, {@code localhost:8180} by default) answers a payload that contains the committed {@code reply.json}, a
 * JSON prefix ending at {@code "tweetsLoaded"}.
 *
 * <p>The original ran the Mule application with {@code mule.test.properties} credentials, replaced the
 * collaborators of bean {@code stackStatsResource} with Mockito mocks and waited five seconds after the call. Here
 * the Spring profile {@code test} supplies fake credentials, {@code @MockBean} puts the mocks into the context and
 * the raw-socket {@link HttpTestClient} returns once the complete response has arrived, so no wait is needed.
 *
 * <p>The scenarios need different mock sets or properties, so each sits in its own {@link Nested} class and
 * therefore its own application context, which it discards after its tests. SC-10 reads the stock-statistics port
 * from the configuration before Undertow binds it, so that port cannot be a random one: {@link #listenerAndUpstreams}
 * draws a free port for every attempt to load a nested context, {@link PortInUseRetryingContextLoader} loads the
 * context again when another process takes that port first, and each test reads the port of its own context.
 *
 * <p>{@code @SpringBootTest}, {@code @ContextConfiguration} and {@code @ActiveProfiles} sit on the nested classes,
 * not on this class: JUnit post-processes the enclosing instance of every nested test, so an annotated enclosing
 * class makes the Spring extension start a third, unmocked context for it beside the nested ones. The nested classes
 * still inherit this class's {@link DynamicPropertySource} method, because Spring's default
 * {@code @NestedTestConfiguration(INHERIT)} searches enclosing classes for it.
 */
class MuleComponentBindingsTest {

    private static final String STOCK = "AAPL";

    private static final String DATE = "2012-10-26";

    private static final String MESSAGE = "?stock=AAPL&date=2012-10-26";

    private static final String STOCK_STATS_PATH = "/api/stockStats";

    /** Stands in for Stocklytics, Sentiment140 and Twitter; only the added scenarios stub it. */
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    /**
     * Configures every attempt to load a nested context: the stock-statistics listener ({@code ports.stock-stats})
     * on a free port drawn for that attempt, and the upstreams on the WireMock stand-in.
     */
    @DynamicPropertySource
    static void listenerAndUpstreams(DynamicPropertyRegistry registry) {
        int drawnPort = TestSocketUtils.findAvailableTcpPort();
        registry.add("ports.stock-stats", () -> drawnPort);
        // The clients append their resource paths (historicalPrices/1.0, bulkClassifyJson) to these base URLs.
        registry.add("stockstats.stocklytics-base-url", () -> wm.baseUrl() + "/");
        registry.add("stockstats.sentiment140-base-url", () -> wm.baseUrl() + "/");
        // Reached only where the Twitter client is not mocked; twitter4j appends search/tweets.json.
        registry.add("twitter.rest-base-url", () -> wm.baseUrl() + "/1.1/");
    }

    /**
     * The expected payload prefix, read as the original read {@code src/test/resources/reply.json}: the whole file,
     * untrimmed.
     */
    private static String reply() throws IOException {
        try (InputStream in = MuleComponentBindingsTest.class.getResourceAsStream("/reply.json")) {
            assertNotNull(in, "reply.json is not on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The original assertions, in their order and with their contains semantics. */
    private static void assertOriginalReply(HttpTestClient.Response result) throws IOException {
        assertNotNull(result);
        // Replaces assertFalse(result.getPayload() instanceof NullPayload): the response carries a body.
        assertFalse(result.body() == null || result.body().length == 0, () -> "empty payload: " + result);
        assertTrue(result.bodyAsString().contains(reply()), () -> "payload lacks reply.json: " + result);
        // Diagnostic only; the original asserted no status.
        assertEquals(200, result.status(), () -> "unexpected response: " + result);
    }

    /**
     * The original {@code testBinding}: the real {@code StockStatsService} behind the real controller, with its
     * three collaborators mocked as the original set them on bean {@code stackStatsResource}.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class PortedBinding {

        /** Port of this context's stock-statistics listener. */
        @Value("${ports.stock-stats}")
        private int stockStatsPort;

        @MockBean
        private StockService stockService;

        @MockBean
        private SentimentService sentimentService;

        /**
         * The bound {@code TwitterService}, mocked by its concrete bean type: the Twitter search controller injects
         * that type, so an interface mock would leave it unsatisfied. Left unstubbed, it answers an empty page, which
         * ends the paging loop at page 1 as the original's unstubbed mock did.
         */
        @MockBean
        private TwitterSearchService twitterService;

        @Test
        void testBinding() throws Exception {
            StockStats stockStats = new StockStats();
            stockStats.setClosingDate(DATE);
            stockStats.getFinancialStats().setClose(1);
            stockStats.getFinancialStats().setLow(2);
            stockStats.getSocialStats().addTweet(new Tweet());

            Mockito.when(stockService.getHistoricalPrices(STOCK, new SimpleDateFormat("yyyy-MM-dd").parse(DATE)))
                    .thenReturn(stockStats);

            HttpTestClient.Response result = HttpTestClient.localhost(stockStatsPort).get(STOCK_STATS_PATH + MESSAGE);

            assertOriginalReply(result);
        }
    }

    /**
     * ADDED end-to-end scenario, not ported (the original declares only {@code testBinding}): the real
     * {@code StocklyticsStockService}, {@code Sentiment140SentimentService}, {@code TwitterSearchService} and
     * {@code TwitterResponseMapper}, with the Stocklytics and Sentiment140 upstreams stubbed by WireMock and the
     * Twitter client mocked, must produce a payload that contains the same {@code reply.json} prefix.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class BindingThroughClients {

        /** Port of this context's stock-statistics listener. */
        @Value("${ports.stock-stats}")
        private int stockStatsPort;

        @MockBean
        private TwitterSearchClient twitterSearchClient;

        @Test
        void testBindingThroughClients() throws Exception {
            QueryResult noTweets = Mockito.mock(QueryResult.class);
            Mockito.when(noTweets.getTweets()).thenReturn(Collections.emptyList());
            Mockito.when(twitterSearchClient.search(anyString(), anyString(), anyString())).thenReturn(noTweets);

            // Stocklytics sends its parameters as headers, so only the path is matched.
            String prices = "{\"2012-10-26\":{\"open\":0,\"close\":1,\"high\":0,\"low\":2,\"volume\":0}}";
            wm.stubFor(get(urlPathEqualTo("/historicalPrices/1.0")).willReturn(okJson(prices)));
            wm.stubFor(post(urlPathEqualTo("/bulkClassifyJson")).willReturn(okJson("{\"data\":[]}")));

            HttpTestClient.Response result = HttpTestClient.localhost(stockStatsPort).get(STOCK_STATS_PATH + MESSAGE);

            // No tweet is loaded here, so tweetsLoaded is 0; the prefix ends at the key and tolerates any count.
            assertOriginalReply(result);
            wm.verify(getRequestedFor(urlPathEqualTo("/historicalPrices/1.0")));
            wm.verify(postRequestedFor(urlPathEqualTo("/bulkClassifyJson")));
            Mockito.verify(twitterSearchClient).search("$" + STOCK, DATE, "2012-11-05");
        }
    }

    /**
     * ADDED scenario, not ported: both stock-statistics keys blank, the Twitter keys of profile {@code test}, and
     * no mock. The application starts, the Twitter-only {@code "/"} flow answers through the real Twitter client,
     * and the stock-statistics request fails with the {@code 500} naming {@code stockstats.stocklyticsApiKey}, the
     * key of its first call, before any upstream is contacted.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"stockstats.stocklyticsApiKey=", "stockstats.sentiment140ApiKey="})
    @ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class WithoutStockStatsKeys {

        /** A v1.1 search answer without tweets; twitter4j requires its {@code search_metadata}. */
        private static final String NO_TWEETS = "{\"statuses\":[],\"search_metadata\":{\"completed_in\":0.001,"
                + "\"max_id\":0,\"max_id_str\":\"0\",\"query\":\"AAPL\",\"refresh_url\":\"?since_id=0&q=AAPL\","
                + "\"count\":15,\"since_id\":0,\"since_id_str\":\"0\"}}";

        @LocalServerPort
        private int mainPort;

        /** Port of this context's stock-statistics listener. */
        @Value("${ports.stock-stats}")
        private int stockStatsPort;

        @BeforeEach
        void noStubsAndNoRequests() {
            wm.resetAll();
        }

        @Test
        void twitterOnlyListenerStillAnswers() {
            wm.stubFor(get(urlPathEqualTo("/1.1/search/tweets.json")).willReturn(okJson(NO_TWEETS)));

            HttpTestClient.Response result = HttpTestClient.localhost(mainPort).get("/");

            assertEquals("HTTP/1.1 200 OK", result.statusLine(), () -> "unexpected response: " + result);
            assertArrayEquals(SerializationUtils.serialize(new ArrayList<Tweet>()), result.body());
            wm.verify(1, getRequestedFor(urlPathEqualTo("/1.1/search/tweets.json")));
            assertEquals(1, wm.getAllServeEvents().size());
        }

        @Test
        void stockStatsFailsNamingTheMissingKeyBeforeAnyUpstreamCall() {
            HttpTestClient.Response result = HttpTestClient.localhost(stockStatsPort).get(STOCK_STATS_PATH + MESSAGE);

            assertEquals("HTTP/1.1 500 Internal Server Error", result.statusLine(),
                    () -> "unexpected response: " + result);
            assertEquals("Stocklytics API key (stockstats.stocklyticsApiKey) is not configured",
                    result.bodyAsString());
            wm.verify(0, anyRequestedFor(anyUrl()));
        }
    }

    /**
     * ADDED scenario, not ported: {@code twitter.apiKey} blank, every other key of profile {@code test}, and no
     * mock. The {@code "/"} flow answers SC-1's {@code 500} naming the key without contacting Twitter, and the
     * stock-statistics request calls Stocklytics first, as the original order has it, then fails with the
     * {@code 500} naming the same key, before Twitter or Sentiment140 is contacted.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "twitter.apiKey=")
    @ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class WithoutTwitterApiKey {

        private static final String MISSING_KEY = "Twitter API key (twitter.apiKey) is not configured";

        @LocalServerPort
        private int mainPort;

        /** Port of this context's stock-statistics listener. */
        @Value("${ports.stock-stats}")
        private int stockStatsPort;

        @BeforeEach
        void noStubsAndNoRequests() {
            wm.resetAll();
        }

        @Test
        void twitterListenerFailsNamingTheMissingKeyBeforeAnyUpstreamCall() {
            HttpTestClient.Response result = HttpTestClient.localhost(mainPort).get("/");

            assertEquals("HTTP/1.1 500 Internal Server Error", result.statusLine(),
                    () -> "unexpected response: " + result);
            assertTrue(String.valueOf(result.header("Content-Type")).startsWith("application/json"),
                    result::toString);
            assertEquals("{ \"message\": \"" + MISSING_KEY + "\" }", result.bodyAsString());
            wm.verify(0, anyRequestedFor(anyUrl()));
        }

        @Test
        void stockStatsFailsNamingTheMissingKeyAfterStocklyticsOnly() {
            String prices = "{\"2012-10-26\":{\"open\":0,\"close\":1,\"high\":0,\"low\":2,\"volume\":0}}";
            wm.stubFor(get(urlPathEqualTo("/historicalPrices/1.0")).willReturn(okJson(prices)));
            wm.stubFor(post(urlPathEqualTo("/bulkClassifyJson")).willReturn(okJson("{\"data\":[]}")));

            HttpTestClient.Response result = HttpTestClient.localhost(stockStatsPort).get(STOCK_STATS_PATH + MESSAGE);

            assertEquals("HTTP/1.1 500 Internal Server Error", result.statusLine(),
                    () -> "unexpected response: " + result);
            assertEquals(MISSING_KEY, result.bodyAsString());
            wm.verify(1, getRequestedFor(urlPathEqualTo("/historicalPrices/1.0")));
            assertEquals(1, wm.getAllServeEvents().size());
        }
    }
}
