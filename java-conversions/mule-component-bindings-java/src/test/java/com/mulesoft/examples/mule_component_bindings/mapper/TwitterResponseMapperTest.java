/**
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
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

import org.apache.commons.lang3.SerializationException;
import org.junit.jupiter.api.Named;
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
                        new long[] {3L, -7L, 1L}), new String[] {"third \u00e9t\u00e9", null, "first"}));
    }

    @ParameterizedTest
    @MethodSource("searchResults")
    void mapsEveryStatusInResultOrderToAnArrayListOfTweets(long[] ids, String[] texts) {
        List<Status> statuses = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            Status status = mock(Status.class);
            when(status.getId()).thenReturn(ids[i]);
            when(status.getText()).thenReturn(texts[i]);
            statuses.add(status);
        }
        QueryResult queryResult = mock(QueryResult.class);
        when(queryResult.getTweets()).thenReturn(statuses);

        List<Tweet> tweets = mapper.toTweets(queryResult);

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
                .hasMessageContaining(Tweet.class.getName());
    }

    private static byte[] javaSerialized(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }
}
