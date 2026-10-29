/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

import org.apache.commons.lang3.SerializationException;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.mulesoft.examples.mule_component_bindings.model.Tweet;

import twitter4j.QueryResult;
import twitter4j.Status;

/**
 * Unit test of {@link TwitterResponseMapper}, the port of the Mule custom processor
 * (mule-component-bindings/src/main/java/stockstats/impl/mule/TwitterResponseProcessor.java) that turned the
 * Twitter search {@link QueryResult} into the {@code List<Tweet>} payload, and of the Java-serialized body the
 * {@code "/"} listener wrote for that payload.
 */
class TwitterResponseMapperTest {

    private final TwitterResponseMapper mapper = new TwitterResponseMapper();

    static Stream<Arguments> searchResults() {
        return Stream.of(
                Arguments.of(Named.of("no tweets", new long[] {}), new String[] {}),
                Arguments.of(Named.of("one tweet", new long[] {529013410513453056L}),
                        new String[] {"$AAPL closes higher"}),
                Arguments.of(Named.of("several tweets, a null text and a negative id included",
                        new long[] {3L, -7L, 1L}), new String[] {"third \u00e9t\u00e9", null, "first"}),
                Arguments.of(Named.of("snowflake-sized ids up to Long.MAX_VALUE and an empty text",
                        new long[] {529784356710236160L, 1L, Long.MAX_VALUE}),
                        new String[] {"$AAPL up", "second", ""}));
    }

    @ParameterizedTest
    @MethodSource("searchResults")
    void mapsEveryStatusInResultOrderToAnArrayListOfTweets(long[] ids, String[] texts) {
        Status[] statuses = new Status[ids.length];
        for (int i = 0; i < ids.length; i++) {
            statuses[i] = status(ids[i], texts[i]);
        }

        List<Tweet> tweets = mapper.toTweets(result(statuses));

        // The original payload was a java.util.ArrayList; the listener's serialized body names that class.
        assertThat(tweets).isExactlyInstanceOf(ArrayList.class).hasSize(ids.length);
        for (int i = 0; i < ids.length; i++) {
            Tweet tweet = tweets.get(i);
            // The original wrote the id as t.getId() + "", the decimal rendering of the long.
            assertThat(tweet.getId()).isEqualTo(Long.toString(ids[i]));
            assertThat(tweet.getText()).isEqualTo(texts[i]);
            assertThat(tweet.getSentiment()).isNull();
        }
    }

    @Test
    void readsOnlyTheTweetsOfTheResultAndTheIdAndTextOfEachStatus() {
        Status status = status(529784356710236160L, "$AAPL up");
        QueryResult queryResult = result(status);

        mapper.toTweets(queryResult);

        verify(queryResult).getTweets();
        verify(status).getId();
        verify(status).getText();
        verifyNoMoreInteractions(queryResult, status);
    }

    static Stream<Named<List<Tweet>>> emptyTweetLists() {
        return Stream.of(
                Named.of("empty ArrayList", new ArrayList<>()),
                Named.of("immutable empty list", List.of()),
                Named.of("Collections.emptyList", Collections.emptyList()),
                Named.of("empty LinkedList", new LinkedList<>()));
    }

    @ParameterizedTest
    @MethodSource("emptyTweetLists")
    void serializesAnEmptyListAsAnEmptyJavaUtilArrayList(List<Tweet> tweets)
            throws IOException, ClassNotFoundException {
        byte[] body = mapper.toListenerPayload(tweets);

        assertThat(body).isEqualTo(javaSerialized(new ArrayList<Tweet>()));
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(body))) {
            Object payload = in.readObject();
            assertThat(payload).isExactlyInstanceOf(ArrayList.class);
            assertThat((List<?>) payload).isEmpty();
        }
    }

    static Stream<Named<List<Tweet>>> nonEmptyTweetLists() {
        Tweet tweet = new Tweet();
        tweet.setId("1");
        tweet.setText("$AAPL");
        return Stream.of(
                Named.of("ArrayList of one tweet", new ArrayList<>(List.of(tweet))),
                Named.of("immutable list of one tweet", List.of(tweet)));
    }

    @ParameterizedTest
    @MethodSource("nonEmptyTweetLists")
    void failsToSerializeANonEmptyListBecauseTweetIsNotSerializable(List<Tweet> tweets) {
        // The failure is deliberately left to propagate to the generic error handler's 500.
        assertThatThrownBy(() -> mapper.toListenerPayload(tweets))
                .isInstanceOf(SerializationException.class)
                .hasCauseInstanceOf(NotSerializableException.class)
                .hasMessageContaining(Tweet.class.getName())
                .hasRootCauseMessage(Tweet.class.getName());
    }

    // The "/" flow end to end: Twitter search result -> mapped tweets -> listener body.
    @Test
    void serializesTheTweetsOfASearchWithoutHitsAsAnEmptyArrayList() throws IOException {
        byte[] body = mapper.toListenerPayload(mapper.toTweets(result()));

        assertThat(body).isEqualTo(javaSerialized(new ArrayList<Tweet>()));
    }

    @Test
    void failsToSerializeTheTweetsOfASearchWithHits() {
        List<Tweet> tweets = mapper.toTweets(result(status(7L, "x")));

        assertThatThrownBy(() -> mapper.toListenerPayload(tweets))
                .isInstanceOf(SerializationException.class)
                .hasCauseInstanceOf(NotSerializableException.class)
                .hasRootCauseMessage(Tweet.class.getName());
    }

    // Guards the failure above: making Tweet Serializable would change the "/" listener's observable result.
    @Test
    void tweetIsNotSerializable() {
        assertThat(Serializable.class.isAssignableFrom(Tweet.class)).isFalse();
    }

    private static Status status(long id, String text) {
        Status status = mock(Status.class);
        when(status.getId()).thenReturn(id);
        when(status.getText()).thenReturn(text);
        return status;
    }

    private static QueryResult result(Status... statuses) {
        QueryResult queryResult = mock(QueryResult.class);
        when(queryResult.getTweets()).thenReturn(List.of(statuses));
        return queryResult;
    }

    private static byte[] javaSerialized(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }
}
