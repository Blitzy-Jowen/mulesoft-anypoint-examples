/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.mapper;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.SerializationUtils;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.mule_component_bindings.model.Tweet;

import twitter4j.QueryResult;
import twitter4j.Status;

/**
 * Transforms the Twitter search response ({@link QueryResult}) into the {@code TwitterService.search}
 * response type ({@code List<Tweet>}). It produces the result of both the {@code "/"} listener's search and the
 * {@code TwitterService.search} binding, which {@code service.TwitterSearchService} runs through it.
 */
@Component
public class TwitterResponseMapper {

	/**
	 * Maps each tweet of the search result, in result order, to a {@link Tweet} carrying its id and text.
	 *
	 * @param queryResult the Twitter search result
	 * @return a {@link java.util.ArrayList} of tweets, the runtime type the original payload had
	 */
	public List<Tweet> toTweets(QueryResult queryResult) {
		List<Tweet> tweetList = new ArrayList<Tweet>();
		for (Status t : queryResult.getTweets()) {
			Tweet tweet = new Tweet();
			tweet.setId(t.getId() + "");
			tweet.setText(t.getText());
			tweetList.add(tweet);
		}
		return tweetList;
	}

	/**
	 * Renders the {@code "/"} listener flow's final payload as the response body: the Mule 3 HTTP listener wrote
	 * any {@code Serializable} payload other than a stream, a byte array or a string by Java serialization.
	 * <p>
	 * {@link Tweet} is not {@code Serializable}, so an empty list serializes to an empty
	 * {@link java.util.ArrayList} while a non-empty list throws
	 * {@link org.apache.commons.lang3.SerializationException}. That exception is deliberately not caught here:
	 * it propagates through the search service and controller to the generic error handler's 500
	 * {@code { "message": ... }} response, the conversion's rendering of the original's failure and a Tier 2A
	 * fixture verification point.
	 *
	 * @param tweets the mapped tweets
	 * @return the Java-serialized {@link java.util.ArrayList} of the tweets
	 */
	public byte[] toListenerPayload(List<Tweet> tweets) {
		// The serialized stream always names java.util.ArrayList, the class of the original payload.
		ArrayList<Tweet> list = tweets instanceof ArrayList ? (ArrayList<Tweet>) tweets : new ArrayList<>(tweets);
		return SerializationUtils.serialize(list);
	}

}
