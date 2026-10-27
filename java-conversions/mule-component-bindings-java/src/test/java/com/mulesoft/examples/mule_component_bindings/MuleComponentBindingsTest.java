/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
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

import twitter4j.QueryResult;

/**
 * Ported from {@code MuleComponentBindingsIT} of the original example {@code mule-component-bindings}: a GET of
 * {@code /api/stockStats?stock=AAPL&date=2012-10-26} on the stock-statistics listener (the original's
 * {@code localhost:8180}, SC-10's additional listener here) must answer a payload that contains the committed
 * {@code reply.json}, a JSON prefix ending at {@code "tweetsLoaded"}.
 *
 * <p>The original ran the Mule application with {@code mule.test.properties} credentials, replaced the
 * collaborators of bean {@code stackStatsResource} with Mockito mocks and waited five seconds after the call. Here
 * the Spring profile {@code test} supplies fake credentials, {@code @MockBean} puts the mocks into the context and
 * the raw-socket {@link HttpTestClient} returns once the complete response has arrived, so no wait is needed.
 *
 * <p>The two scenarios need different mock sets, so each sits in its own {@link Nested} class and therefore its own
 * application context. Both bind the stock-statistics listener to {@link #STOCK_STATS_PORT}, reserved once for
 * the class; each nested class discards its context after its test, so the first context releases the port before
 * the second binds it.
 *
 * <p>{@code @SpringBootTest} and {@code @ActiveProfiles} sit on the nested classes, not on this class: JUnit
 * post-processes the enclosing instance of every nested test, so an annotated enclosing class makes the Spring
 * extension start a third, unmocked context for it, which keeps {@link #STOCK_STATS_PORT} bound for the whole run
 * and stops both nested contexts from starting. The nested classes still inherit this class's
 * {@link DynamicPropertySource} method, because Spring's default {@code @NestedTestConfiguration(INHERIT)} searches
 * enclosing classes for it.
 */
class MuleComponentBindingsTest {

    /** Port of the stock-statistics listener ({@code ports.stock-stats}), reserved once for the whole class. */
    private static final int STOCK_STATS_PORT = TestSocketUtils.findAvailableTcpPort();

    private static final String STOCK = "AAPL";

    private static final String DATE = "2012-10-26";

    private static final String MESSAGE = "?stock=AAPL&date=2012-10-26";

    private static final String STOCK_STATS_PATH = "/api/stockStats";

    /** Stands in for Stocklytics and Sentiment140; only the added end-to-end scenario stubs it. */
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void listenerAndUpstreams(DynamicPropertyRegistry registry) {
        registry.add("ports.stock-stats", () -> STOCK_STATS_PORT);
        // The clients append their resource paths (historicalPrices/1.0, bulkClassifyJson) to these base URLs.
        registry.add("stockstats.stocklytics-base-url", () -> wm.baseUrl() + "/");
        registry.add("stockstats.sentiment140-base-url", () -> wm.baseUrl() + "/");
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
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class PortedBinding {

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

            HttpTestClient.Response result = HttpTestClient.localhost(STOCK_STATS_PORT).get(STOCK_STATS_PATH + MESSAGE);

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
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class BindingThroughClients {

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

            HttpTestClient.Response result = HttpTestClient.localhost(STOCK_STATS_PORT).get(STOCK_STATS_PATH + MESSAGE);

            // No tweet is loaded here, so tweetsLoaded is 0; the prefix ends at the key and tolerates any count.
            assertOriginalReply(result);
            wm.verify(getRequestedFor(urlPathEqualTo("/historicalPrices/1.0")));
            wm.verify(postRequestedFor(urlPathEqualTo("/bulkClassifyJson")));
            Mockito.verify(twitterSearchClient).search("$" + STOCK, DATE, "2012-11-05");
        }
    }
}
