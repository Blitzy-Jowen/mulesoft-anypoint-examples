/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mulesoft.examples.mule_component_bindings.mapper.TwitterResponseMapper;
import com.mulesoft.examples.mule_component_bindings.model.Tweet;

import twitter4j.QueryResult;
import twitter4j.TwitterException;

/**
 * Unit test of {@link TwitterSearchService}, which replaces the component {@code binding} to the request-response
 * VM flow {@code TwitterFlow}: the request preparation of
 * {@code mule-component-bindings/src/main/java/stockstats/impl/mule/TwitterRequestProcessor.java} (cashtag query,
 * {@code since} as {@code yyyy-MM-dd}, {@code until} ten calendar days later, {@code rpp} and {@code page} stored but
 * never read by {@code twitter:search}), the search and the {@code TwitterResponseProcessor} mapping; and the fixed
 * {@code AAPL} search of flow {@code mule-component-bindingsFlow1} behind the {@code "/"} listener. The Twitter
 * search port {@link TwitterSearchService.RawSearch} and the mapper are Mockito mocks.
 */
@ExtendWith(MockitoExtension.class)
class TwitterSearchServiceTest {

    @Mock
    private TwitterSearchService.RawSearch client;

    @Mock
    private TwitterResponseMapper mapper;

    @Mock
    private QueryResult queryResult;

    private TwitterSearchService service;

    @BeforeEach
    void setUp() {
        service = new TwitterSearchService(client, mapper);
    }

    static Stream<Arguments> searchWindows() {
        return Stream.of(
                Arguments.of(Named.of("month boundary, two tweets", "2012-10-26"), "2012-11-05",
                        List.of(tweet("261888939591479296", "$AAPL earnings"), tweet("261889120974155777", "$AAPL"))),
                Arguments.of(Named.of("year boundary, two tweets", "2012-12-25"), "2013-01-04",
                        List.of(tweet("283559470734966784", "$AAPL holiday"), tweet("1", "second"))),
                Arguments.of(Named.of("leap day inside the window, one tweet", "2012-02-25"), "2012-03-06",
                        List.of(tweet("173586018417098752", "$AAPL leap"))),
                Arguments.of(Named.of("month boundary, no tweets", "2012-10-26"), "2012-11-05",
                        new ArrayList<Tweet>()));
    }

    @ParameterizedTest
    @MethodSource("searchWindows")
    void searchesTheCashtagFromDateToTenDaysLaterAndReturnsTheMappedTweetsAsIs(String since, String until,
            List<Tweet> tweets) throws Exception {
        when(client.search("$AAPL", since, until)).thenReturn(queryResult);
        when(mapper.toTweets(queryResult)).thenReturn(tweets);

        List<Tweet> result = service.search("AAPL", date(since), 100, 3);

        assertThat(result).isSameAs(tweets);
        verify(client).search("$AAPL", since, until);
        verifyNoMoreInteractions(client);
        verify(mapper).toTweets(queryResult);
    }

    @Test
    void pageSizeAndPageAreNotForwardedSoEveryPageRunsTheSameSearch() throws Exception {
        List<Tweet> tweets = List.of(tweet("261888939591479296", "$AAPL earnings"));
        when(client.search("$AAPL", "2012-10-26", "2012-11-05")).thenReturn(queryResult);
        when(mapper.toTweets(queryResult)).thenReturn(tweets);

        List<Tweet> first = service.search("AAPL", date("2012-10-26"), 100, 1);
        List<Tweet> later = service.search("AAPL", date("2012-10-26"), 50, 7);

        assertThat(first).isSameAs(tweets);
        assertThat(later).isSameAs(tweets);
        verify(client, times(2)).search("$AAPL", "2012-10-26", "2012-11-05");
        verifyNoMoreInteractions(client);
    }

    @Test
    void searchFailureIsRethrownAsIllegalStateExceptionWithTheTwitterMessageAndCause() throws Exception {
        TwitterException te = new TwitterException("rate limit exceeded");
        when(client.search(anyString(), anyString(), anyString())).thenThrow(te);

        Throwable thrown = catchThrowable(() -> service.search("AAPL", date("2012-10-26"), 100, 1));

        assertThat(thrown).isExactlyInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage()).isEqualTo(te.getMessage());
        assertThat(thrown.getCause()).isSameAs(te);
        verifyNoInteractions(mapper);
    }

    @Test
    void listenerFlowRunsTheFixedAaplSearchAndReturnsTheRenderedPayload() throws Exception {
        List<Tweet> tweets = List.of(tweet("529013410513453056", "$AAPL closes higher"));
        byte[] payload = {1, 2, 3};
        when(client.search("AAPL", "2014-10-26", "2014-11-04")).thenReturn(queryResult);
        when(mapper.toTweets(queryResult)).thenReturn(tweets);
        when(mapper.toListenerPayload(tweets)).thenReturn(payload);

        byte[] result = service.searchListenerFlow();

        assertThat(result).isSameAs(payload);
        verify(client).search("AAPL", "2014-10-26", "2014-11-04");
        verifyNoMoreInteractions(client);
    }

    @Test
    void listenerFlowSearchFailureIsRethrownAsIllegalStateExceptionWithTheTwitterMessageAndCause() throws Exception {
        TwitterException te = new TwitterException("rate limit exceeded");
        when(client.search(anyString(), anyString(), anyString())).thenThrow(te);

        Throwable thrown = catchThrowable(service::searchListenerFlow);

        assertThat(thrown).isExactlyInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage()).isEqualTo(te.getMessage());
        assertThat(thrown.getCause()).isSameAs(te);
        verifyNoInteractions(mapper);
    }

    private static Date date(String s) throws ParseException {
        return new SimpleDateFormat("yyyy-MM-dd").parse(s);
    }

    private static Tweet tweet(String id, String text) {
        Tweet tweet = new Tweet();
        tweet.setId(id);
        tweet.setText(text);
        return tweet;
    }

}
