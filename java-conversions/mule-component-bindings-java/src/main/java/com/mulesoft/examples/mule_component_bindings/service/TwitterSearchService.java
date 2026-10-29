/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.service;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.mulesoft.examples.mule_component_bindings.mapper.TwitterResponseMapper;
import com.mulesoft.examples.mule_component_bindings.model.Tweet;

import twitter4j.QueryResult;
import twitter4j.TwitterException;

/**
 * Implements the Twitter search that the original bound to {@link TwitterService#search} through the component
 * {@code binding} and ran in the request-response VM flow {@code TwitterFlow}: the request preparation of
 * {@code TwitterRequestProcessor}, the {@code twitter:search} call and the {@code TwitterResponseProcessor}
 * mapping. It also runs the fixed search of flow {@code mule-component-bindingsFlow1}, the {@code "/"} listener.
 */
@Service
public class TwitterSearchService implements TwitterService {

	/** {@code query} of the {@code twitter:search} in flow {@code mule-component-bindingsFlow1}. */
	static final String QUERY = "AAPL";

	/** {@code since} of the {@code twitter:search} in flow {@code mule-component-bindingsFlow1}. */
	static final String SINCE = "2014-10-26";

	/** {@code until} of the {@code twitter:search} in flow {@code mule-component-bindingsFlow1}. */
	static final String UNTIL = "2014-11-04";

	private static final String DATE_FORMAT = "yyyy-MM-dd";

	/**
	 * The {@code twitter:search} operation, with the {@code query}, {@code since} and {@code until} attributes the
	 * connector carried; a {@code null} date bound sends none. This service owns the port and the outbound Twitter
	 * adapter, {@code client.TwitterSearchClient}, implements it, so the service package does not depend on the
	 * client package.
	 */
	public interface RawSearch {

		/**
		 * Executes the search.
		 *
		 * @param query the search query
		 * @param since the {@code yyyy-MM-dd} lower date bound, or {@code null} to send none
		 * @param until the {@code yyyy-MM-dd} upper date bound, or {@code null} to send none
		 * @return the search result
		 * @throws TwitterException when Twitter rejects or fails the search
		 */
		QueryResult search(String query, String since, String until) throws TwitterException;

	}

	private final RawSearch client;

	private final TwitterResponseMapper mapper;

	public TwitterSearchService(RawSearch client, TwitterResponseMapper mapper) {
		this.client = client;
		this.mapper = mapper;
	}

	/**
	 * Searches Twitter for the cashtag of {@code stock} in the ten days starting at {@code date}, as
	 * {@code TwitterRequestProcessor} and {@code TwitterFlow} did.
	 *
	 * @throws IllegalStateException carrying the {@link TwitterException} message and cause when the search fails
	 */
	@Override
	public List<Tweet> search(String stock, Date date, int pageSize, int page) {
		String query = "$" + stock;
		// A new formatter per call, because SimpleDateFormat is not thread-safe; default zone and locale, as the
		// original used.
		DateFormat df = new SimpleDateFormat(DATE_FORMAT);
		String since = df.format(date);
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		cal.add(Calendar.DATE, 10);
		String until = df.format(cal.getTime());
		// pageSize and page are deliberately not sent: the original stored them as the invocation properties
		// rpp and page, but its twitter:search read only query, since and until. Every page therefore returns the
		// same tweets, and a non-empty result repeats until the caller's 1000-tweet cap, as in the original.
		try {
			return mapper.toTweets(client.search(query, since, until));
		} catch (TwitterException e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	/**
	 * Runs flow {@code mule-component-bindingsFlow1}: the fixed {@code AAPL} search, mapped to tweets and rendered
	 * as the listener rendered that payload. A non-empty result fails in {@link TwitterResponseMapper#toListenerPayload}
	 * with a {@code SerializationException}, which is left to propagate to the generic error handler.
	 *
	 * @return the response body bytes
	 * @throws IllegalStateException carrying the {@link TwitterException} message and cause when the search fails
	 */
	public byte[] searchListenerFlow() {
		try {
			return mapper.toListenerPayload(mapper.toTweets(client.search(QUERY, SINCE, UNTIL)));
		} catch (TwitterException e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

}
