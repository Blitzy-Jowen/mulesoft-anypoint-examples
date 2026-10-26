/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.client;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.mule_component_bindings.config.StockStatsProperties;

import twitter4j.Query;
import twitter4j.QueryResult;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.conf.ConfigurationBuilder;

/**
 * Performs the Twitter search the Mule Twitter cloud connector ({@code twitter:search}) performed, configured
 * as the original {@code <twitter:config>} was: {@code twitter.apiKey} and {@code twitter.apiSecret} are the
 * OAuth consumer key and secret, {@code twitter.accessKey} and {@code twitter.accessSecret} the access token and
 * its secret.
 *
 * <p>Both original searches go through {@link #search(String, String, String)}: the fixed {@code AAPL} search of
 * the {@code "/"} listener flow and the {@code TwitterFlow} search behind the component binding. Callers compute
 * the query and the {@code yyyy-MM-dd} dates; this client only executes the search.
 */
@Component
public class TwitterSearchClient {

	private final StockStatsProperties properties;

	private volatile Twitter twitter;

	public TwitterSearchClient(StockStatsProperties properties) {
		this.properties = properties;
	}

	/**
	 * Searches Twitter with the query and date bounds the connector's {@code query}, {@code since} and
	 * {@code until} attributes carried. No other search option is set, as the connector set none.
	 *
	 * @param query the search query
	 * @param since the {@code yyyy-MM-dd} lower date bound, or {@code null} to send none
	 * @param until the {@code yyyy-MM-dd} upper date bound, or {@code null} to send none
	 * @return the search result
	 * @throws TwitterException when Twitter rejects or fails the search; propagated unchanged to the caller's
	 *                          original error handling
	 */
	public QueryResult search(String query, String since, String until) throws TwitterException {
		Query q = new Query(query);
		if (since != null) {
			q.setSince(since);
		}
		if (until != null) {
			q.setUntil(until);
		}
		return twitter().search(q);
	}

	/**
	 * Builds the {@link Twitter} instance on first use, so the application starts without credentials, and
	 * reuses it afterwards; twitter4j instances are thread-safe once built.
	 */
	private Twitter twitter() {
		Twitter instance = twitter;
		if (instance == null) {
			synchronized (this) {
				instance = twitter;
				if (instance == null) {
					instance = new TwitterFactory(new ConfigurationBuilder()
							.setOAuthConsumerKey(properties.twitterApiKey())
							.setOAuthConsumerSecret(properties.twitterApiSecret())
							.setOAuthAccessToken(properties.twitterAccessKey())
							.setOAuthAccessTokenSecret(properties.twitterAccessSecret())
							.setRestBaseURL(properties.twitterRestBaseUrl())
							.build()).getInstance();
					twitter = instance;
				}
			}
		}
		return instance;
	}

}
