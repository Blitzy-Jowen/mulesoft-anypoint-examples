/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.web.server.ResponseStatusException;

import com.mulesoft.examples.mule_component_bindings.exception.InvalidStockSymbol;
import com.mulesoft.examples.mule_component_bindings.model.StockStats;
import com.mulesoft.examples.mule_component_bindings.model.Tweet;
import com.mulesoft.examples.mule_component_bindings.service.SentimentService;
import com.mulesoft.examples.mule_component_bindings.service.StockService;
import com.mulesoft.examples.mule_component_bindings.service.StockStatsService;
import com.mulesoft.examples.mule_component_bindings.service.TwitterSearchService;
import com.mulesoft.examples.mule_component_bindings.service.TwitterService;
import com.mulesoft.examples.mule_component_bindings.support.HttpTestClient;
import com.mulesoft.examples.mule_component_bindings.support.HttpTestClient.Response;

/**
 * Wire-level test of {@link StockStatsController}, the replacement of the JAX-RS resource
 * {@code stockstats.StockStatsResource} ({@code @Path("/stockStats")}, {@code @GET search(@QueryParam("stock"),
 * @QueryParam("date"))}) behind the {@code /api/*} listener of {@code localhost:8180}
 * (mule-component-bindings/src/main/app/mule-component-bindings.xml:40-54).
 *
 * <p>The whole application runs on Undertow under profile {@code test}: the main listener on a random port and the
 * stock-statistics listener on a free port reserved before the context starts, because SC-10's
 * {@code PortRoutingFilter} tells the listeners apart by local port. The real {@link StockStatsService} runs over
 * mocked {@link StockService}, {@link SentimentService} and {@link TwitterSearchService} (the bound
 * {@link TwitterService}), so every upstream call is recorded and none leaves the JVM. Requests go through ST-1
 * {@link HttpTestClient}, which adds no {@code Accept} or {@code Content-Type} of its own, so the status line,
 * headers and body bytes are asserted as written.
 *
 * <p>Expected query bindings and media-selection statuses are those of the JAX-RS runtime the original ran on
 * (Jersey 2.11: {@code UriComponent.decodeQuery}, which {@code @QueryParam} reads, and the method selection against
 * the resource's {@code @Consumes} and {@code @Produces("application/json")}), established by running that runtime
 * on each input.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StockStatsControllerTest {

    /** Port of the stock-statistics listener ({@code ports.stock-stats}), reserved once for the whole class. */
    private static final int STOCK_STATS_PORT = TestSocketUtils.findAvailableTcpPort();

    private static final String STOCK_STATS = "/api/stockStats";

    private static final String DAY = "2012-10-26";

    private static final String NO_CONTENT = "HTTP/1.1 204 No Content";

    private static final String BAD_REQUEST = "HTTP/1.1 400 Bad Request";

    private static final String NOT_ACCEPTABLE = "HTTP/1.1 406 Not Acceptable";

    private static final String UNSUPPORTED_MEDIA_TYPE = "HTTP/1.1 415 Unsupported Media Type";

    @MockBean
    private StockService stockService;

    @MockBean
    private SentimentService sentimentService;

    @MockBean
    private TwitterSearchService twitterSearchService;

    @Autowired
    private StockStatsService stockStatsService;

    private HttpTestClient stockStats;

    @DynamicPropertySource
    static void stockStatsPort(DynamicPropertyRegistry registry) {
        registry.add("ports.stock-stats", () -> STOCK_STATS_PORT);
    }

    @BeforeEach
    void client() {
        stockStats = HttpTestClient.localhost(STOCK_STATS_PORT);
    }

    @Test
    void contextBindsTheMockedServicesIntoTheRealResource() {
        assertThat(stockStatsService.getStockService()).isSameAs(stockService);
        assertThat(stockStatsService.getTwitterService()).isSameAs(twitterSearchService);
        assertThat(stockStatsService.getSentimentService()).isSameAs(sentimentService);
    }

    @ParameterizedTest(name = "[{index}] ?{0} -> stock {1}")
    @CsvSource(delimiter = '|', value = {
        // first occurrence of a repeated parameter wins
        "stock=AAPL&stock=MSFT&date=2012-10-26 | AAPL",
        // a percent-encoded name is decoded before it is compared
        "st%6Fck=AAPL&date=2012-10-26          | AAPL",
        // '+' and %20 decode to a space
        "stock=A+B%20C&date=2012-10-26         | A B C",
        // a term whose name is empty is ignored
        "=x&stock=AAPL&date=2012-10-26         | AAPL",
        // empty terms are skipped
        "&&stock=AAPL&&date=2012-10-26&        | AAPL",
        // an encoded '&' or '=' stays in the value
        "stock=A%26B&date=2012-10-26           | A&B",
    })
    void queryParametersAreBoundAsTheJaxRsRuntimeBoundThem(String query, String expectedStock)
            throws Exception {
        Response response = stockStats.get(STOCK_STATS + "?" + query);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo(NO_CONTENT);
        verify(stockService).getHistoricalPrices(eq(expectedStock), eq(day(DAY)));
        // the stock is looked up once, so a later value of a repeated parameter is never asked for
        verifyNoMoreInteractions(stockService);
    }

    @ParameterizedTest(name = "[{index}] ?{0} -> 400 {1}")
    @CsvSource(delimiter = '|', value = {
        // an empty value is blank
        "stock=&date=2012-10-26                | stock is required",
        // date absent
        "stock=AAPL                            | date is required",
        // a date of another length than yyyy-MM-dd
        "stock=AAPL&date=2012-1-26             | date format must be YYYY-MM-DD",
        // a date of the right length that does not parse
        "stock=AAPL&date=abcd-ef-gh            | date format must be YYYY-MM-DD",
        // a bare name binds the empty value
        "stock&date=2012-10-26                 | stock is required",
        // ';' is not a separator: date is part of the stock value
        "stock=AAPL;date=2012-10-26            | date is required",
        // names are compared case-sensitively
        "Stock=AAPL&date=2012-10-26            | stock is required",
        // stock absent
        "date=2012-10-26                       | stock is required",
    })
    void queryParametersTheResourceRejectsReachItsValidation(String query, String reason) {
        Response response = stockStats.get(STOCK_STATS + "?" + query);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 400 Bad Request");
        assertThat(response.header("Content-Type")).isEqualTo("application/json");
        assertThat(response.bodyAsString()).isEqualTo(reason);
        verifyNoInteractions(stockService, twitterSearchService, sentimentService);
    }

    @ParameterizedTest(name = "[{index}] {0} {1} -> {2}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
        "NULL                          | stock | NULL",
        "''                            | stock | NULL",
        "stock=AAPL&date=2014-11-04    | stock | AAPL",
        "stock=AAPL&date=2014-11-04    | date  | 2014-11-04",
        "stock&date=x                  | stock | ''",
        "stock&date=x                  | date  | x",
        "=x&stock=a                    | stock | a",
        "stock=a&stock=b               | stock | a",
        "st%6Fck=a+b%20c               | stock | a b c",
        "stock=a;date=b                | stock | a;date=b",
        "stock=a;date=b                | date  | NULL",
        "&&stock=a&                    | stock | a",
        "stock=%E2%82%AC               | stock | \u20ac",
        "stock==a                      | stock | =a",
        "stock=a&date                  | date  | ''",
        "stock=                        | stock | ''",
        "Stock=a                       | stock | NULL",
        "stock=a%26b&date=c%3Dd        | stock | a&b",
        "stock=a%26b&date=c%3Dd        | date  | c=d",
        "&                             | stock | NULL",
        "=                             | stock | NULL",
        "stock=a=b                     | stock | a=b",
    })
    void queryParameterDecodesTheRawQueryAsTheJaxRsRuntimeDid(String rawQuery, String name, String expected) {
        assertThat(StockStatsController.queryParameter(rawQuery, name)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource({
        // a malformed escape fails the whole query, even in a term other than the one asked for
        "stock=%ZZ",
        "stock=a%2",
        "stock=a&date=%",
        "stock=%u0041",
    })
    void queryParameterRejectsMalformedEscapesAnywhereInTheQuery(String rawQuery) {
        assertThat(catchThrowable(() -> StockStatsController.queryParameter(rawQuery, "stock")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formFieldsOfTheRequestBodyAreNotQueryParameters() {
        // The servlet request exposes the body's form fields as request parameters; @QueryParam never saw them.
        StockService stocks = mock(StockService.class);
        TwitterService twitter = mock(TwitterService.class);
        SentimentService sentiment = mock(SentimentService.class);
        StockStatsController controller =
                new StockStatsController(new StockStatsService(stocks, twitter, sentiment));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", STOCK_STATS);
        request.addParameter("stock", "AAPL");
        request.addParameter("date", "2014-11-04");
        request.setContent("stock=AAPL&date=2014-11-04".getBytes(StandardCharsets.ISO_8859_1));

        Throwable thrown = catchThrowable(() -> controller.search(request));

        assertThat(thrown).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getReason()).isEqualTo("stock is required");
        });
        verifyNoInteractions(stocks, twitter, sentiment);
    }

    @Test
    void getRunsTheResourceAndAnswersTheStatsAsJson() throws Exception {
        // The original MuleComponentBindingsIT stub: close 1, low 2 and one tweet already counted.
        StockStats stats = new StockStats();
        stats.setClosingDate(DAY);
        stats.getFinancialStats().setClose(1);
        stats.getFinancialStats().setLow(2);
        stats.getSocialStats().addTweet(new Tweet());
        when(stockService.getHistoricalPrices("AAPL", day(DAY))).thenReturn(stats);

        Response response = stockStats.get(STOCK_STATS + "?stock=AAPL&date=" + DAY);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.header("Content-Type")).isEqualTo("application/json");
        assertThat(response.bodyAsString()).isEqualTo("{\"closingDate\":\"2012-10-26\","
                + "\"financialStats\":{\"open\":0.0,\"close\":1.0,\"high\":0.0,\"low\":2.0,\"volume\":0},"
                + "\"socialStats\":{\"tweetsLoaded\":1,\"moreTweets\":false,\"negativeTweets\":0,"
                + "\"neutralTweets\":0,\"positiveTweets\":1}}");
        verify(twitterSearchService).search(eq("AAPL"), eq(day(DAY)), eq(100), eq(1));
        verify(sentimentService).classify(anyList());
    }

    @Test
    void getWithoutStatsForTheDayAnswers204WithoutBody() throws Exception {
        Response response = stockStats.get(STOCK_STATS + "?stock=AAPL&date=" + DAY);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo(NO_CONTENT);
        assertThat(response.header("Content-Type")).isNull();
        assertThat(response.body()).isEmpty();
        verify(stockService).getHistoricalPrices(eq("AAPL"), any(Date.class));
        verify(twitterSearchService).search(eq("AAPL"), any(Date.class), anyInt(), anyInt());
    }

    @Test
    void invalidStockSymbolIsAnswered400NamingTheSymbol() throws Exception {
        when(stockService.getHistoricalPrices(eq("XYZ"), any())).thenThrow(new InvalidStockSymbol("XYZ"));

        Response response = stockStats.get(STOCK_STATS + "?stock=XYZ&date=" + DAY);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo(BAD_REQUEST);
        assertThat(response.header("Content-Type")).isEqualTo("application/json");
        assertThat(response.bodyAsString()).isEqualTo("Invalid stock symbol XYZ");
        verify(stockService).getHistoricalPrices(eq("XYZ"), eq(day(DAY)));
        verifyNoInteractions(twitterSearchService, sentimentService);
    }

    @ParameterizedTest(name = "[{index}] RuntimeException({0})")
    @NullSource
    @ValueSource(strings = "boom")
    void unexpectedFailureIsAnswered500WithItsMessageAsTheRawBody(String message) throws Exception {
        // The resource's catch-all branch, not the { "message": ... } 500 of the generic handler.
        when(stockService.getHistoricalPrices(eq("AAPL"), any())).thenThrow(new RuntimeException(message));

        Response response = stockStats.get(STOCK_STATS + "?stock=AAPL&date=" + DAY);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(response.header("Content-Type")).isEqualTo("application/json");
        assertThat(response.bodyAsString()).isEqualTo(message == null ? "" : message);
        verifyNoInteractions(twitterSearchService, sentimentService);
    }

    @Test
    void formBodyWithoutQueryIsRejectedByTheResourceMediaTypeBeforeAnyUpstreamCall() {
        // @Consumes(application/json) rejects the form entity before the method runs, so its fields never matter.
        Response response = stockStats.send("GET", STOCK_STATS,
                Map.of("Content-Type", "application/x-www-form-urlencoded"),
                "stock=AAPL&date=2014-11-04".getBytes(StandardCharsets.ISO_8859_1));

        assertRejected(response, UNSUPPORTED_MEDIA_TYPE);
    }

    @ParameterizedTest(name = "[{index}] Accept: {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "text/html                                              | " + NOT_ACCEPTABLE,
        "text/html;                                             | " + NOT_ACCEPTABLE,
        "application/*+json                                     | " + NOT_ACCEPTABLE,
        "application/vnd.x+json                                 | " + NOT_ACCEPTABLE,
        "json                                                   | " + NOT_ACCEPTABLE,
        "text/*;q=0.5, application/xml                          | " + NOT_ACCEPTABLE,
        "application/json;q=2                                   | " + BAD_REQUEST,
        "application/json;q=abc                                 | " + BAD_REQUEST,
        "application/json;q=1.5                                 | " + BAD_REQUEST,
        "application/json;q=0.5555                              | " + BAD_REQUEST,
        "/json                                                  | " + BAD_REQUEST,
        "application/                                           | " + BAD_REQUEST,
        "a/b/c                                                  | " + BAD_REQUEST,
        "text/html,,application/json                            | " + BAD_REQUEST,
        ",                                                      | " + BAD_REQUEST,
        ", application/json                                     | " + BAD_REQUEST,
        "application/json; q = 0.5                              | " + BAD_REQUEST,
        "application / json                                     | " + BAD_REQUEST,
        "application/json (x)                                   | " + BAD_REQUEST,
        "application/json;level                                 | " + BAD_REQUEST,
        // an invalid element fails the list even when another element would be acceptable
        "text/html, application/json;q=abc                      | " + BAD_REQUEST,
    })
    void acceptTheResourceCannotSatisfyIsRejectedBeforeAnyUpstreamCall(String accept, String statusLine) {
        Response response = stockStats.get(STOCK_STATS + "?stock=AAPL&date=" + DAY, Map.of("Accept", accept));

        assertRejected(response, statusLine);
    }

    @ParameterizedTest(name = "[{index}] Accept: {0}")
    @CsvSource(delimiter = '|', value = {
        "application/json",
        "Application/JSON",
        "application/json;charset=utf-8",
        "application/json; charset=\"a,b\"",
        // the quality of a compatible element does not matter: a single resource method is still selected
        "application/json;q=0",
        "text/html;q=1, application/json;q=0",
        "*/*;q=0.1",
        "text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2",
        "*",
        "application",
        "*/json",
        "application/*",
        "application/json,",
        "application/json;q=.5555",
        "application/json;q=1.",
    })
    void acceptAdmittingJsonReachesTheResource(String accept) throws Exception {
        Response response = stockStats.get(STOCK_STATS + "?stock=AAPL&date=" + DAY, Map.of("Accept", accept));

        assertThat(response.statusLine()).as(response.toString()).isEqualTo(NO_CONTENT);
        verify(stockService).getHistoricalPrices(eq("AAPL"), eq(day(DAY)));
    }

    @ParameterizedTest(name = "[{index}] Accept: {0} + Accept: {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
        "text/html | application/json | " + NO_CONTENT,
        "text/html | image/png        | " + NOT_ACCEPTABLE,
        // the values are joined with ',', so an empty one leaves an empty list element
        "''        | text/html        | " + BAD_REQUEST,
    })
    void repeatedAcceptHeadersAreReadAsOneList(String first, String second, String statusLine) {
        Response response = stockStats.sendHeaderPairs("GET", STOCK_STATS + "?stock=AAPL&date=" + DAY,
                List.of(Map.entry("Accept", first), Map.entry("Accept", second)), null);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo(statusLine);
    }

    @ParameterizedTest(name = "[{index}] Content-Type: {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "text/plain                                             | " + UNSUPPORTED_MEDIA_TYPE,
        "text/plain;                                            | " + UNSUPPORTED_MEDIA_TYPE,
        "application/x-www-form-urlencoded                      | " + UNSUPPORTED_MEDIA_TYPE,
        "application/*+json                                     | " + UNSUPPORTED_MEDIA_TYPE,
        "application/vnd.x+json                                 | " + UNSUPPORTED_MEDIA_TYPE,
        "text/*                                                 | " + UNSUPPORTED_MEDIA_TYPE,
        // a type that is not JSON is rejected before its parameters are read
        "text/plain;q=abc                                       | " + UNSUPPORTED_MEDIA_TYPE,
        "json                                                   | " + BAD_REQUEST,
        "*                                                      | " + BAD_REQUEST,
        "''                                                     | " + BAD_REQUEST,
        "application/                                           | " + BAD_REQUEST,
        "/json                                                  | " + BAD_REQUEST,
        "a/b/c                                                  | " + BAD_REQUEST,
        "application/json;charset                               | " + BAD_REQUEST,
        "application/json;charset=                              | " + BAD_REQUEST,
        "application/json;=x                                    | " + BAD_REQUEST,
        "application/json, text/plain                           | " + BAD_REQUEST,
        "application / json                                     | " + BAD_REQUEST,
        "application/json (x)                                   | " + BAD_REQUEST,
    })
    void contentTypeTheResourceDoesNotConsumeIsRejectedBeforeAnyUpstreamCall(String contentType, String statusLine) {
        Response response = stockStats.get(STOCK_STATS + "?stock=AAPL&date=" + DAY,
                Map.of("Content-Type", contentType));

        assertRejected(response, statusLine);
    }

    @ParameterizedTest(name = "[{index}] Content-Type: {0}")
    @CsvSource(delimiter = '|', value = {
        "application/json",
        "application/json;charset=UTF-8",
        "Application/Json;Charset=UTF-8",
        "APPLICATION/JSON",
        "application/*",
        "*/*",
        "*/json",
        "application/json;charset=\"a\"",
        "application/json ; charset=utf-8",
        "application/json;q=0.5",
    })
    void contentTypeCompatibleWithJsonReachesTheResource(String contentType) throws Exception {
        Response response = stockStats.get(STOCK_STATS + "?stock=AAPL&date=" + DAY,
                Map.of("Content-Type", contentType));

        assertThat(response.statusLine()).as(response.toString()).isEqualTo(NO_CONTENT);
        verify(stockService).getHistoricalPrices(eq("AAPL"), eq(day(DAY)));
    }

    @Test
    void repeatedContentTypeHeadersAreRejected() {
        Response response = stockStats.sendHeaderPairs("GET", STOCK_STATS + "?stock=AAPL&date=" + DAY,
                List.of(Map.entry("Content-Type", "application/json"), Map.entry("Content-Type", "application/json")),
                null);

        assertRejected(response, BAD_REQUEST);
    }

    @ParameterizedTest(name = "[{index}] Content-Type: {0}, Accept: {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
        // the Content-Type is judged first, parse and compatibility both, then the Accept
        "text/plain       | text/html | " + UNSUPPORTED_MEDIA_TYPE,
        "text/plain       | /json     | " + UNSUPPORTED_MEDIA_TYPE,
        "json             | text/html | " + BAD_REQUEST,
        "application/json | /json     | " + BAD_REQUEST,
        "application/json | text/html | " + NOT_ACCEPTABLE,
        // the q of the Content-Type is read only once both media types matched
        "application/json;q=2 | text/html | " + NOT_ACCEPTABLE,
    })
    void contentTypeIsJudgedBeforeAccept(String contentType, String accept, String statusLine) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("Accept", accept);

        Response response = stockStats.get(STOCK_STATS + "?stock=AAPL&date=" + DAY, headers);

        assertRejected(response, statusLine);
    }

    @Test
    void contentTypeWithAMalformedQualityFailsAfterSelectionWithoutReachingTheResource() {
        Response response = stockStats.get(STOCK_STATS + "?stock=AAPL&date=" + DAY,
                Map.of("Content-Type", "application/json;q=2"));

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(response.header("Content-Type")).isEqualTo("application/json");
        assertThat(response.bodyAsString()).isEqualTo("{ \"message\": \"java.text.ParseException: Error parsing "
                + "Quality value: a decimal numeral '0' or '1' is expected rather than '2'\" }");
        verifyNoInteractions(stockService, twitterSearchService, sentimentService);
    }

    @ParameterizedTest(name = "[{index}] HEAD {0}: {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
        "Accept       | text/html  | " + NOT_ACCEPTABLE,
        "Content-Type | text/plain | " + UNSUPPORTED_MEDIA_TYPE,
    })
    void headIsSelectedAsGetIs(String header, String value, String statusLine) {
        Response response = stockStats.send("HEAD", STOCK_STATS + "?stock=AAPL&date=" + DAY,
                Map.of(header, value), null);

        assertRejected(response, statusLine);
    }

    @Test
    void headAdmittingJsonReachesTheResource() throws Exception {
        Response response = stockStats.send("HEAD", STOCK_STATS + "?stock=AAPL&date=" + DAY,
                Map.of("Accept", "application/json"), null);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo(NO_CONTENT);
        verify(stockService).getHistoricalPrices(eq("AAPL"), eq(day(DAY)));
    }

    @ParameterizedTest(name = "[{index}] Content-Type {0}, Accept {1} -> {2}")
    @CsvSource(delimiter = '|', nullValues = "NONE", value = {
        // white space the HTTP listener trims from a header line, judged as the runtime judged it
        "NONE                 | ' '                   | 406",
        "NONE                 | '\tapplication/json'  | OK",
        "NONE                 | 'application/json\t'  | OK",
        "' application/json'  | NONE                  | OK",
        "' '                  | NONE                  | 400",
        // characters outside US-ASCII are not token characters
        "NONE                 | applicati\u00f3n/json | 400",
        "applicati\u00f3n/json | NONE                 | 400",
    })
    void selectionReadsHeaderValuesWithTheRuntimeGrammar(String contentType, String accept, String expected) {
        Optional<HttpStatus> rejection = JaxRsMediaSelection.rejection(
                contentType == null ? List.of() : List.of(contentType), accept == null ? List.of() : List.of(accept));

        assertThat(rejection.map(status -> String.valueOf(status.value())).orElse("OK")).isEqualTo(expected);
    }

    @Test
    void selectionPassesARequestWithoutMediaHeaders() {
        assertThat(JaxRsMediaSelection.rejection(List.of(), List.of())).isEmpty();
    }

    @Test
    void postIsAnsweredWithTheListenerMethodNotAllowed() {
        Response response = stockStats.post(STOCK_STATS + "?stock=AAPL&date=" + DAY, "application/json", "{}");

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 405 Method Not Allowed");
        assertThat(response.header("Content-Type")).isEqualTo("text/plain");
        assertThat(response.bodyAsString()).isEqualTo("Method not allowed for endpoint: " + STOCK_STATS);
        verifyNoInteractions(stockService, twitterSearchService, sentimentService);
    }

    @Test
    void optionsIsAnsweredWithTheAllowedMethods() {
        Response response = stockStats.send("OPTIONS", STOCK_STATS, null, null);

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.header("Allow")).isEqualTo("GET,HEAD,OPTIONS");
        assertThat(response.body()).isEmpty();
        verifyNoInteractions(stockService, twitterSearchService, sentimentService);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        // an unserved path under the listener's /api/* reaches the dispatcher's no-listener answer
        "/api/unknown | text/plain",
        // a path outside /api/* on this port is answered by the port routing itself
        "/            | text/plain",
    })
    void pathsTheResourceDoesNotServeAreAnsweredWithTheNoListener404(String path, String contentType) {
        Response response = stockStats.get(path, Map.of("Accept", "text/html"));

        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(response.header("Content-Type")).isEqualTo(contentType);
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: " + path);
        verifyNoInteractions(stockService, twitterSearchService, sentimentService);
    }

    /**
     * Asserts the runtime's answer to a request it did not route to the resource method: the status alone, with the
     * standard reason phrase, no Content-Type and an empty body, and no upstream call.
     */
    private void assertRejected(Response response, String statusLine) {
        assertThat(response.statusLine()).as(response.toString()).isEqualTo(statusLine);
        assertThat(response.header("Content-Type")).as(response.toString()).isNull();
        assertThat(response.header("Content-Length")).as(response.toString()).isEqualTo("0");
        assertThat(response.body()).isEmpty();
        verifyNoInteractions(stockService, twitterSearchService, sentimentService);
    }

    private static Date day(String text) throws ParseException {
        return new SimpleDateFormat("yyyy-MM-dd").parse(text);
    }
}
