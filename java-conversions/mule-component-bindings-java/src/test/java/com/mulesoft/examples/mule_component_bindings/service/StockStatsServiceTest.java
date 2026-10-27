/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.mule_component_bindings.exception.InvalidStockSymbol;
import com.mulesoft.examples.mule_component_bindings.model.Sentiment;
import com.mulesoft.examples.mule_component_bindings.model.SocialStats;
import com.mulesoft.examples.mule_component_bindings.model.StockStats;
import com.mulesoft.examples.mule_component_bindings.model.Tweet;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit test of {@link StockStatsService}, the port of
 * {@code mule-component-bindings/src/main/java/stockstats/impl/StockStatsResourceImpl.java}: the input validation
 * (400 with the original plain-text reasons, the date parsed leniently as {@code yyyy-MM-dd}), the Stocklytics
 * lookup and its invalid-symbol 400, the Twitter paging (pages of 100 from page 1 until an empty page or more than
 * 1000 tweets, then trimmed to 1000), the Sentiment140 classification and the tweet counts, and the 500 carrying the
 * failure's own message for any other throwable, logged by exception type alone. The three bound services are
 * Mockito mocks; no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class StockStatsServiceTest {

    private static final String DAY = "2012-10-26";

    /** Personal data planted in upstream text; it must never reach the log. */
    private static final String SENTINEL = "SENTINEL-PII jane.roe@example.invalid";

    @Mock
    private StockService stockService;

    @Mock
    private TwitterService twitterService;

    @Mock
    private SentimentService sentimentService;

    @Captor
    private ArgumentCaptor<List<Tweet>> tweetsCaptor;

    private StockStatsService service;

    /** The service's logger, captured by {@link #appender} for the duration of each test. */
    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        service = new StockStatsService(stockService, twitterService, sentimentService);
        logger = (Logger) LoggerFactory.getLogger(StockStatsService.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void blankStockIsRejectedWith400BeforeAnyCollaboratorIsCalled(String stock) {
        ResponseStatusException e = searchFailure(stock, DAY);

        assertThat(e.getStatusCode().value()).isEqualTo(400);
        assertThat(e.getReason()).isEqualTo("stock is required");
        verifyNoInteractions(stockService, twitterService, sentimentService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "          "})
    void blankDateIsRejectedWith400BeforeAnyCollaboratorIsCalled(String dateStr) {
        ResponseStatusException e = searchFailure("AAPL", dateStr);

        assertThat(e.getStatusCode().value()).isEqualTo(400);
        assertThat(e.getReason()).isEqualTo("date is required");
        verifyNoInteractions(stockService, twitterService, sentimentService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2012-1-26", "2012-10-261", "20121026", "abcd-ef-gh"})
    void dateOfAnotherLengthOrUnparseableIsRejectedWith400BeforeAnyCollaboratorIsCalled(String dateStr) {
        ResponseStatusException e = searchFailure("AAPL", dateStr);

        assertThat(e.getStatusCode().value()).isEqualTo(400);
        assertThat(e.getReason()).isEqualTo("date format must be YYYY-MM-DD");
        verifyNoInteractions(stockService, twitterService, sentimentService);
    }

    @ParameterizedTest
    @CsvSource({"2012-13-45, 2013-02-14", "2012-02-30, 2012-03-01"})
    void outOfRangeMonthOrDayRollsOverAsTheLenientParseDoes(String dateStr, String rolledDay) throws Exception {
        Date rolled = date(rolledDay);
        StockStats stats = new StockStats();
        when(stockService.getHistoricalPrices("AAPL", rolled)).thenReturn(stats);

        StockStats result = service.search("AAPL", dateStr);

        assertThat(result).isSameAs(stats);
        verify(stockService).getHistoricalPrices("AAPL", rolled);
        verify(twitterService).search("AAPL", rolled, 100, 1);
        verifyNoMoreInteractions(stockService, twitterService);
    }

    @Test
    void invalidStockSymbolIsRejectedWith400NamingTheSymbolBeforeTwitterOrSentimentIsCalled() throws Exception {
        when(stockService.getHistoricalPrices("XYZ", date(DAY))).thenThrow(new InvalidStockSymbol("XYZ"));

        ResponseStatusException e = searchFailure("XYZ", DAY);

        assertThat(e.getStatusCode().value()).isEqualTo(400);
        assertThat(e.getReason()).isEqualTo("Invalid stock symbol XYZ");
        assertThat(e.getCause()).isNull();
        verifyNoInteractions(twitterService, sentimentService);
    }

    static Stream<Arguments> pagings() {
        return Stream.of(
                Arguments.of(Named.of("empty first page", List.of(0)), 0, false),
                Arguments.of(Named.of("100 + 50 + empty page", List.of(100, 50, 0)), 150, false),
                Arguments.of(Named.of("one page of 1001", List.of(1001)), 1000, true),
                Arguments.of(Named.of("exactly 1000 over ten pages + empty page", tenPagesOf100Then(0)), 1000, false),
                Arguments.of(Named.of("1001 over eleven pages", tenPagesOf100Then(1)), 1000, true));
    }

    @ParameterizedTest
    @MethodSource("pagings")
    void readsPagesOf100FromPage1UntilAnEmptyPageOrMoreThan1000TweetsAndClassifiesAtMost1000(List<Integer> pageSizes,
            int expectedLoaded, boolean expectedMoreTweets) throws Exception {
        Date day = date(DAY);
        StockStats stats = new StockStats();
        when(stockService.getHistoricalPrices("AAPL", day)).thenReturn(stats);
        stubPages(day, pageSizes);

        StockStats result = service.search("AAPL", DAY);

        assertThat(result).isSameAs(stats);
        InOrder pages = inOrder(twitterService);
        for (int page = 1; page <= pageSizes.size(); page++) {
            pages.verify(twitterService).search("AAPL", day, 100, page);
        }
        verifyNoMoreInteractions(twitterService);
        verify(sentimentService).classify(tweetsCaptor.capture());
        assertThat(tweetsCaptor.getValue()).extracting(Tweet::getId).containsExactlyElementsOf(ids(expectedLoaded));
        SocialStats social = result.getSocialStats();
        assertThat(social.getTweetsLoaded()).isEqualTo(expectedLoaded);
        assertThat(social.isMoreTweets()).isEqualTo(expectedMoreTweets);
    }

    @Test
    void classifiedTweetsAreCountedBySentimentWithAnUnclassifiedTweetCountedPositive() throws Exception {
        Date day = date(DAY);
        StockStats stats = new StockStats();
        when(stockService.getHistoricalPrices("AAPL", day)).thenReturn(stats);
        stubPages(day, List.of(10, 0));
        List<Sentiment> sentiments = Arrays.asList(Sentiment.NEGATIVE, Sentiment.NEGATIVE, Sentiment.NEUTRAL,
                Sentiment.NEUTRAL, Sentiment.NEUTRAL, Sentiment.POSITIVE, Sentiment.POSITIVE, Sentiment.POSITIVE,
                Sentiment.POSITIVE, null);
        doAnswer(invocation -> {
            List<Tweet> classified = invocation.getArgument(0);
            for (int i = 0; i < classified.size(); i++) {
                classified.get(i).setSentiment(sentiments.get(i));
            }
            return null;
        }).when(sentimentService).classify(anyList());

        StockStats result = service.search("AAPL", DAY);

        assertThat(result).isSameAs(stats);
        SocialStats social = result.getSocialStats();
        assertThat(social.getTweetsLoaded()).isEqualTo(10);
        assertThat(social.getNegativeTweets()).isEqualTo(2);
        assertThat(social.getNeutralTweets()).isEqualTo(3);
        assertThat(social.getPositiveTweets()).isEqualTo(5);
        assertThat(social.isMoreTweets()).isFalse();
    }

    @Test
    void noStatsForTheDayReturnsNullAfterTheTweetsAreStillClassified() throws Exception {
        Date day = date(DAY);
        when(stockService.getHistoricalPrices("AAPL", day)).thenReturn(null);
        stubPages(day, List.of(2, 0));

        StockStats result = service.search("AAPL", DAY);

        assertThat(result).isNull();
        verify(sentimentService).classify(tweetsCaptor.capture());
        assertThat(tweetsCaptor.getValue()).extracting(Tweet::getId).containsExactly("0", "1");
    }

    @Test
    void noStatsForTheDayWithMoreThan1000TweetsFailsWith500BeforeClassifying() throws Exception {
        Date day = date(DAY);
        when(stockService.getHistoricalPrices("AAPL", day)).thenReturn(null);
        stubPages(day, List.of(1001));

        ResponseStatusException e = searchFailure("AAPL", DAY);

        assertThat(e.getStatusCode().value()).isEqualTo(500);
        // The original's NullPointerException had no message, so its 500 carried none.
        assertThat(e.getReason()).isNull();
        verifyNoInteractions(sentimentService);
    }

    static Stream<Arguments> collaboratorFailures() {
        return Stream.of(Collaborator.values())
                .flatMap(collaborator -> Stream.of(
                        Arguments.of(collaborator, Named.of("RuntimeException(\"boom\")", new RuntimeException("boom")),
                                "boom"),
                        Arguments.of(collaborator, Named.of("Error(\"fatal\")", new Error("fatal")), "fatal"),
                        Arguments.of(collaborator, Named.of("RuntimeException without a message",
                                new RuntimeException()), null)));
    }

    @ParameterizedTest(name = "{0} throws {1}")
    @MethodSource("collaboratorFailures")
    void anyOtherFailureOfACollaboratorIsAnswered500WithItsOwnMessage(Collaborator collaborator, Throwable failure,
            String expectedReason) throws Exception {
        collaborator.failWith(this, date(DAY), failure);

        ResponseStatusException e = searchFailure("AAPL", DAY);

        assertThat(e.getStatusCode().value()).isEqualTo(500);
        assertThat(e.getReason()).isEqualTo(expectedReason);
        assertThat(e.getCause()).isNull();
        collaborator.verifyLaterCollaboratorsNotCalled(this);
    }

    static Stream<Arguments> failuresCarryingUpstreamText() {
        String io = " <- java.io.IOException";
        return Stream.of(
                Arguments.of(Named.of("Sentiment140 body cut off in a tweet, wrapped as the client wraps it",
                        new RuntimeException(truncatedBodyParseFailure())),
                        "java.lang.RuntimeException <- com.fasterxml.jackson.core.io.JsonEOFException"),
                Arguments.of(Named.of("text in nested messages and in a suppressed exception",
                        nestedFailureWithSuppressed()),
                        "java.lang.IllegalStateException" + io + " <- java.lang.IllegalArgumentException"),
                Arguments.of(Named.of("circular cause chain", circularFailure()),
                        "java.lang.RuntimeException" + io + " <- [circular]"),
                Arguments.of(Named.of("chain of exactly 10 causes", failureWithCauses(10)),
                        "java.lang.RuntimeException" + io.repeat(10)),
                Arguments.of(Named.of("chain of 11 causes", failureWithCauses(11)),
                        "java.lang.RuntimeException" + io.repeat(10) + " <- ..."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresCarryingUpstreamText")
    void failureIsLoggedByExceptionTypesAloneWhileThe500KeepsItsMessage(RuntimeException failure,
            String expectedTypes) throws Exception {
        // The planted text is in what logging the throwable itself would print: messages, causes, suppressed.
        assertThat(printedStackTrace(failure)).contains(SENTINEL);
        Collaborator.SENTIMENT_SERVICE.failWith(this, date(DAY), failure);

        ResponseStatusException e = searchFailure("AAPL", DAY);

        assertThat(e.getStatusCode().value()).isEqualTo(500);
        assertThat(e.getReason()).isEqualTo(failure.getMessage());
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getMessage()).isEqualTo("Error searching stock stats: {}");
        assertThat(event.getArgumentArray()).containsExactly(expectedTypes);
        assertThat(event.getFormattedMessage()).isEqualTo("Error searching stock stats: " + expectedTypes)
                .doesNotContain(SENTINEL);
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void gettersReturnTheCollaboratorsAndSettersReplaceThemForLaterSearches(@Mock StockService otherStockService,
            @Mock TwitterService otherTwitterService, @Mock SentimentService otherSentimentService) throws Exception {
        assertThat(service.getStockService()).isSameAs(stockService);
        assertThat(service.getTwitterService()).isSameAs(twitterService);
        assertThat(service.getSentimentService()).isSameAs(sentimentService);

        service.setStockService(otherStockService);
        service.setTwitterService(otherTwitterService);
        service.setSentimentService(otherSentimentService);

        assertThat(service.getStockService()).isSameAs(otherStockService);
        assertThat(service.getTwitterService()).isSameAs(otherTwitterService);
        assertThat(service.getSentimentService()).isSameAs(otherSentimentService);
        Date day = date(DAY);
        StockStats stats = new StockStats();
        when(otherStockService.getHistoricalPrices("AAPL", day)).thenReturn(stats);

        StockStats result = service.search("AAPL", DAY);

        assertThat(result).isSameAs(stats);
        verify(otherTwitterService).search("AAPL", day, 100, 1);
        verify(otherSentimentService).classify(List.of());
        verifyNoInteractions(stockService, twitterService, sentimentService);
    }

    @ParameterizedTest
    @CsvSource({"BAD_REQUEST, 400, x", "INTERNAL_SERVER_ERROR, 500, "})
    void responseStatusExceptionCarriesTheStatusAndTheMessageAsItsReason(HttpStatus status, int expectedCode,
            String message) {
        ResponseStatusException e = StockStatsService.createResponseStatusException(status, message);

        assertThat(e.getStatusCode().value()).isEqualTo(expectedCode);
        assertThat(e.getReason()).isEqualTo(message);
        assertThat(e.getCause()).isNull();
    }

    /** The collaborator a failure case makes throw, in the order {@code search} calls them. */
    enum Collaborator {
        STOCK_SERVICE {
            @Override
            void failWith(StockStatsServiceTest test, Date day, Throwable failure) throws Exception {
                when(test.stockService.getHistoricalPrices("AAPL", day)).thenThrow(failure);
            }

            @Override
            void verifyLaterCollaboratorsNotCalled(StockStatsServiceTest test) {
                verifyNoInteractions(test.twitterService, test.sentimentService);
            }
        },
        TWITTER_SERVICE {
            @Override
            void failWith(StockStatsServiceTest test, Date day, Throwable failure) throws Exception {
                when(test.stockService.getHistoricalPrices("AAPL", day)).thenReturn(new StockStats());
                when(test.twitterService.search("AAPL", day, 100, 1)).thenThrow(failure);
            }

            @Override
            void verifyLaterCollaboratorsNotCalled(StockStatsServiceTest test) {
                verifyNoInteractions(test.sentimentService);
            }
        },
        SENTIMENT_SERVICE {
            @Override
            void failWith(StockStatsServiceTest test, Date day, Throwable failure) throws Exception {
                when(test.stockService.getHistoricalPrices("AAPL", day)).thenReturn(new StockStats());
                when(test.twitterService.search("AAPL", day, 100, 1)).thenReturn(new ArrayList<Tweet>());
                doThrow(failure).when(test.sentimentService).classify(anyList());
            }

            @Override
            void verifyLaterCollaboratorsNotCalled(StockStatsServiceTest test) {
                verify(test.sentimentService).classify(List.of());
            }
        };

        abstract void failWith(StockStatsServiceTest test, Date day, Throwable failure) throws Exception;

        abstract void verifyLaterCollaboratorsNotCalled(StockStatsServiceTest test);
    }

    private ResponseStatusException searchFailure(String stock, String dateStr) {
        Throwable thrown = catchThrowable(() -> service.search(stock, dateStr));

        assertThat(thrown).isExactlyInstanceOf(ResponseStatusException.class);
        return (ResponseStatusException) thrown;
    }

    /** Stubs page {@code n} of the AAPL search to return the {@code n}-th size, numbering tweet ids from 0 on. */
    private void stubPages(Date day, List<Integer> pageSizes) {
        int nextId = 0;
        for (int page = 1; page <= pageSizes.size(); page++) {
            int size = pageSizes.get(page - 1);
            when(twitterService.search("AAPL", day, 100, page)).thenReturn(tweets(nextId, size));
            nextId += size;
        }
    }

    private static List<Integer> tenPagesOf100Then(int lastPageSize) {
        List<Integer> sizes = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            sizes.add(100);
        }
        sizes.add(lastPageSize);
        return sizes;
    }

    private static List<Tweet> tweets(int firstId, int count) {
        List<Tweet> tweets = new ArrayList<>();
        for (int id = firstId; id < firstId + count; id++) {
            Tweet tweet = new Tweet();
            tweet.setId(String.valueOf(id));
            tweet.setText("$AAPL tweet " + id);
            tweets.add(tweet);
        }
        return tweets;
    }

    private static List<String> ids(int count) {
        return IntStream.range(0, count).mapToObj(String::valueOf).toList();
    }

    /** What a default {@link ObjectMapper} throws for a Sentiment140 body that ends inside a tweet's text. */
    private static Throwable truncatedBodyParseFailure() {
        String truncatedBody = "{\"data\":[{\"id\":\"1\",\"text\":\"$AAPL " + SENTINEL;
        return catchThrowable(() -> new ObjectMapper().readTree(truncatedBody));
    }

    /** A failure whose own message, nested causes and suppressed exception all quote the planted text. */
    private static RuntimeException nestedFailureWithSuppressed() {
        IllegalStateException failure = new IllegalStateException("classify failed for " + SENTINEL,
                new IOException("read " + SENTINEL, new IllegalArgumentException("tweet " + SENTINEL)));
        failure.addSuppressed(new TimeoutException("close " + SENTINEL));
        return failure;
    }

    /** A failure whose cause names the failure again as its own cause. */
    private static RuntimeException circularFailure() {
        RuntimeException failure = new RuntimeException("outer " + SENTINEL);
        failure.initCause(new IOException("inner " + SENTINEL, failure));
        return failure;
    }

    /** A failure above a chain of {@code causes} distinct {@link IOException}s, each quoting the planted text. */
    private static RuntimeException failureWithCauses(int causes) {
        Throwable cause = null;
        for (int depth = causes; depth >= 1; depth--) {
            cause = new IOException("cause " + depth + " " + SENTINEL, cause);
        }
        return new RuntimeException("outer " + SENTINEL, cause);
    }

    /** What {@link Throwable#printStackTrace()} prints for {@code failure}. */
    private static String printedStackTrace(Throwable failure) {
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out, true));
        return out.toString();
    }

    private static Date date(String s) throws ParseException {
        return new SimpleDateFormat("yyyy-MM-dd").parse(s);
    }

}
