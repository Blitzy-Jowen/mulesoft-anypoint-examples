/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.client;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.mulesoft.examples.mule_component_bindings.config.StockStatsProperties;
import com.mulesoft.examples.mule_component_bindings.service.TwitterSearchService;

import twitter4j.Query;
import twitter4j.QueryResult;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.conf.Configuration;
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
 *
 * <p>Every search has a 10 s connect timeout and a 10 s read timeout, in place of twitter4j's own defaults of 20 s
 * and 120 s, which external twitter4j configuration could also change. twitter4j's own retries are off, so a search
 * that times out or that Twitter rejects is not sent again. The JDK {@link java.net.HttpURLConnection} under
 * twitter4j, however, re-sends the bodiless search GET once, unchanged, when the connection is reset or closes
 * before any response arrives, as the original Twitter connector's twitter4j did over the same transport; the
 * search fails when that second attempt fails too. twitter4j 4.0.7 chooses its HTTP implementation once per JVM,
 * from the system property {@code twitter4j.http.httpClient}, and keeps its request handling package-private, so no
 * transport without that re-send can be given to this client alone.
 */
@Component
public class TwitterSearchClient implements TwitterSearchService.RawSearch {

	/**
	 * Connect timeout of every Twitter call, in milliseconds: the Mule default response timeout, which also bounded
	 * the original request-response VM binding to {@code TwitterFlow}.
	 */
	private static final int HTTP_CONNECTION_TIMEOUT_MILLIS = 10_000;

	/** Read timeout of every Twitter call, in milliseconds: the longest any single read of the response may block. */
	private static final int HTTP_READ_TIMEOUT_MILLIS = 10_000;

	/**
	 * twitter4j's own retries after a failed Twitter call: none, so twitter4j never sends a timed-out or rejected
	 * search again. Below twitter4j, {@link java.net.HttpURLConnection} still re-sends the bodiless search GET once
	 * when the connection is reset or closes before any response, as described on the class.
	 */
	private static final int HTTP_RETRY_COUNT = 0;

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
	 * @throws IllegalStateException when {@code twitter.apiKey}, {@code twitter.apiSecret}, {@code twitter.accessKey}
	 *                               or {@code twitter.accessSecret} has no text, before any request is made; the
	 *                               message names the first such key, never a configured value
	 */
	@Override
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
	 * reuses it afterwards; twitter4j instances are thread-safe once built. A failed credential check builds and
	 * caches nothing, so every later search checks the credentials again.
	 */
	private Twitter twitter() {
		Twitter instance = twitter;
		if (instance == null) {
			synchronized (this) {
				instance = twitter;
				if (instance == null) {
					instance = new TwitterFactory(configuration(properties)).getInstance();
					twitter = instance;
				}
			}
		}
		return instance;
	}

	/**
	 * The twitter4j configuration of every search: the OAuth credentials and REST base URL of {@code properties},
	 * the 10 s connect and read timeouts and no twitter4j retry. The explicit setters override whatever
	 * {@link ConfigurationBuilder} loaded from {@code twitter4j.*} system properties, environment variables or a
	 * {@code twitter4j.properties} file, so no external twitter4j configuration can change these bounds.
	 *
	 * @throws IllegalStateException when one of the four OAuth credentials has no text, before any twitter4j
	 *                               object is configured; the message names the first such key
	 */
	static Configuration configuration(StockStatsProperties properties) {
		requireCredentials(properties);
		return new ConfigurationBuilder()
				.setOAuthConsumerKey(properties.twitterApiKey())
				.setOAuthConsumerSecret(properties.twitterApiSecret())
				.setOAuthAccessToken(properties.twitterAccessKey())
				.setOAuthAccessTokenSecret(properties.twitterAccessSecret())
				.setRestBaseURL(properties.twitterRestBaseUrl())
				.setHttpConnectionTimeout(HTTP_CONNECTION_TIMEOUT_MILLIS)
				.setHttpReadTimeout(HTTP_READ_TIMEOUT_MILLIS)
				.setHttpRetryCount(HTTP_RETRY_COUNT)
				.build();
	}

	/**
	 * Fails the search, naming the first of the four {@code twitter:config} credentials that has no text (absent,
	 * empty or whitespace only), in the order consumer key, consumer secret, access token, access token secret,
	 * so that twitter4j never signs a request with a blank credential. Only the Twitter keys are checked: the
	 * Stocklytics and Sentiment140 keys belong to the other clients, so the {@code "/"} flow works without them.
	 * The REST base URL is not read here.
	 */
	private static void requireCredentials(StockStatsProperties properties) {
		if (!StringUtils.hasText(properties.twitterApiKey())) {
			throw new IllegalStateException("Twitter API key (twitter.apiKey) is not configured");
		}
		if (!StringUtils.hasText(properties.twitterApiSecret())) {
			throw new IllegalStateException("Twitter API secret (twitter.apiSecret) is not configured");
		}
		if (!StringUtils.hasText(properties.twitterAccessKey())) {
			throw new IllegalStateException("Twitter access token (twitter.accessKey) is not configured");
		}
		if (!StringUtils.hasText(properties.twitterAccessSecret())) {
			throw new IllegalStateException("Twitter access token secret (twitter.accessSecret) is not configured");
		}
	}

}
