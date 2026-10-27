/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.service;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.mulesoft.examples.mule_component_bindings.exception.InvalidStockSymbol;
import com.mulesoft.examples.mule_component_bindings.model.StockStats;
import com.mulesoft.examples.mule_component_bindings.model.Tweet;

/**
 * The stock stats resource implementation: combines the financial stats of a stock on a date with the tweets
 * about it, classified by sentiment. Ported from {@code StockStatsResourceImpl}.
 *
 * <p>Failures are {@link ResponseStatusException}s whose reason is the plain-text body the original
 * {@code WebApplicationException} carried: 400 for invalid input, 500 with the failure's own message otherwise.
 */
@Service
public class StockStatsService {

	private static final Logger log = LoggerFactory.getLogger(StockStatsService.class);

	private static final String DATE_FORMAT = "yyyy-MM-dd";
	// Not final: the setters below let callers replace a collaborator, as the original bean's properties did;
	// volatile, so a replacement made on one thread is seen by the request threads of this singleton.
	private volatile StockService stockService;
	private volatile TwitterService twitterService;
	private volatile SentimentService sentimentService;

	public StockStatsService(StockService stockService, TwitterService twitterService,
			SentimentService sentimentService) {
		this.stockService = stockService;
		this.twitterService = twitterService;
		this.sentimentService = sentimentService;
	}

	public StockStats search(String stock, String dateStr) {
		try {
			// Validation
			if (StringUtils.isBlank(stock)) {
				throw createResponseStatusException(HttpStatus.BAD_REQUEST, "stock is required");
			}
			if (StringUtils.isBlank(dateStr)) {
				throw createResponseStatusException(HttpStatus.BAD_REQUEST, "date is required");
			}
			if (dateStr.length() != DATE_FORMAT.length()) {
				throw createResponseStatusException(HttpStatus.BAD_REQUEST, "date format must be YYYY-MM-DD");
			}
			
			// Lenient, as in the original: an out-of-range month or day such as 2012-13-45 rolls over.
			DateFormat df = new SimpleDateFormat(DATE_FORMAT);
			Date date = null;
			try {
				date = df.parse(dateStr);
			} catch (ParseException e) {
				throw createResponseStatusException(HttpStatus.BAD_REQUEST, "date format must be YYYY-MM-DD");
			}
			
			// Fetch the stock price
			StockStats stockStats = null;
			try {
				stockStats = stockService.getHistoricalPrices(stock, date);
			} catch (InvalidStockSymbol e) {
				throw createResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid stock symbol " + stock);
			}
			
			// Fetch tweets
			List<Tweet> tweets = new ArrayList<Tweet>();
			int maxResults = 1000;
			int pageSize = 100;
			int page = 1;
			while (true) {
				List<Tweet> results = twitterService.search(stock, date, pageSize, page);
				tweets.addAll(results);
				if (results.size() == 0) {
					break;
				}
				if (tweets.size() > maxResults) {
					// Unguarded, as in the original: null stats here fail the request with a 500.
					stockStats.getSocialStats().setMoreTweets(true);
					break;
				}
				page++;
			}
			
			// Make sure that we have no more that 1000 tweets
			while (tweets.size() > maxResults) {
				tweets.remove(maxResults);
			}
			
			// Classify the tweets
			sentimentService.classify(tweets);
			
			for (Tweet tweet: tweets) {
				if (stockStats != null) { // We won't have stats for weekend days
					stockStats.getSocialStats().addTweet(tweet);
				}
			}
			
			return stockStats;
		} catch (ResponseStatusException e) {
			throw e;
		} catch (Throwable e) {
			log.error("Error searching stock stats", e);
			throw createResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
		}
	}
	
	/**
	 * Replaces the original {@code WebApplicationException(Response.status(status).entity(message))}: the reason
	 * is the response body, and may be null when the failure carried no message.
	 */
	static ResponseStatusException createResponseStatusException(HttpStatus status, String message) {
		return new ResponseStatusException(status, message);
	}

	public StockService getStockService() {
		return stockService;
	}

	public void setStockService(StockService stockService) {
		this.stockService = stockService;
	}

	public TwitterService getTwitterService() {
		return twitterService;
	}

	public void setTwitterService(TwitterService twitterService) {
		this.twitterService = twitterService;
	}

	public SentimentService getSentimentService() {
		return sentimentService;
	}

	public void setSentimentService(SentimentService sentimentService) {
		this.sentimentService = sentimentService;
	}

}
