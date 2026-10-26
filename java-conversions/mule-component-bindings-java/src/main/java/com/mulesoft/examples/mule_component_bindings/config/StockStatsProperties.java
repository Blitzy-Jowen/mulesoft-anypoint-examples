/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Credentials and base URLs of the three services the stock statistics resource and the Twitter search call.
 *
 * <p>Replaces these values of the original {@code mule-component-bindings.xml} and its Java classes, under the
 * same property names:
 * <ul>
 *   <li>{@code <twitter:config accessKey="${twitter.accessKey}" accessSecret="${twitter.accessSecret}"
 *       consumerKey="${twitter.apiKey}" consumerSecret="${twitter.apiSecret}"/>} (line 10);</li>
 *   <li>the {@code ${stockstats.stocklyticsApiKey}} constructor argument of {@code StocklyticsStockService}
 *       (line 20), sent as header {@code api_key};</li>
 *   <li>the {@code ${stockstats.sentiment140ApiKey}} constructor argument of {@code Sentiment140SentimentService}
 *       (line 25), sent as header {@code appid};</li>
 *   <li>the hard-coded {@code BASE_URL}s of those two classes, now {@code stockstats.stocklytics-base-url} and
 *       {@code stockstats.sentiment140-base-url}, and twitter4j's default REST base, now
 *       {@code twitter.rest-base-url}, so tests can point every client at a stub server.</li>
 * </ul>
 *
 * <p>Plain {@code @Value} bindings rather than {@code @ConfigurationProperties}, because the original camelCase
 * names ({@code twitter.accessKey}) sit beside the dashed base-URL keys and each must bind by its exact name.
 * The six secrets default to empty so the application starts without credentials; only the three public base
 * URLs have literal defaults, each ending in {@code /} because the clients append their resource paths to it.
 * Values are returned exactly as configured. No {@code toString()} is declared, so no secret is ever rendered.
 */
@Component
public class StockStatsProperties {

    private final String twitterAccessKey;
    private final String twitterAccessSecret;
    private final String twitterApiKey;
    private final String twitterApiSecret;
    private final String twitterRestBaseUrl;
    private final String stocklyticsApiKey;
    private final String stocklyticsBaseUrl;
    private final String sentiment140ApiKey;
    private final String sentiment140BaseUrl;

    public StockStatsProperties(
            @Value("${twitter.accessKey:}") String twitterAccessKey,
            @Value("${twitter.accessSecret:}") String twitterAccessSecret,
            @Value("${twitter.apiKey:}") String twitterApiKey,
            @Value("${twitter.apiSecret:}") String twitterApiSecret,
            @Value("${twitter.rest-base-url:https://api.twitter.com/1.1/}") String twitterRestBaseUrl,
            @Value("${stockstats.stocklyticsApiKey:}") String stocklyticsApiKey,
            @Value("${stockstats.stocklytics-base-url:http://api.stocklytics.com/}") String stocklyticsBaseUrl,
            @Value("${stockstats.sentiment140ApiKey:}") String sentiment140ApiKey,
            @Value("${stockstats.sentiment140-base-url:http://www.sentiment140.com/api/}") String sentiment140BaseUrl) {
        this.twitterAccessKey = twitterAccessKey;
        this.twitterAccessSecret = twitterAccessSecret;
        this.twitterApiKey = twitterApiKey;
        this.twitterApiSecret = twitterApiSecret;
        this.twitterRestBaseUrl = twitterRestBaseUrl;
        this.stocklyticsApiKey = stocklyticsApiKey;
        this.stocklyticsBaseUrl = stocklyticsBaseUrl;
        this.sentiment140ApiKey = sentiment140ApiKey;
        this.sentiment140BaseUrl = sentiment140BaseUrl;
    }

    /** Twitter OAuth access token ({@code twitter:config accessKey}). */
    public String twitterAccessKey() {
        return twitterAccessKey;
    }

    /** Twitter OAuth access token secret ({@code twitter:config accessSecret}). */
    public String twitterAccessSecret() {
        return twitterAccessSecret;
    }

    /** Twitter OAuth consumer key ({@code twitter:config consumerKey}). */
    public String twitterApiKey() {
        return twitterApiKey;
    }

    /** Twitter OAuth consumer secret ({@code twitter:config consumerSecret}). */
    public String twitterApiSecret() {
        return twitterApiSecret;
    }

    /** Twitter REST API base URL handed to twitter4j's {@code restBaseURL}. */
    public String twitterRestBaseUrl() {
        return twitterRestBaseUrl;
    }

    /** Stocklytics API key, sent as header {@code api_key}. */
    public String stocklyticsApiKey() {
        return stocklyticsApiKey;
    }

    /** Stocklytics base URL; the client appends {@code historicalPrices/1.0}. */
    public String stocklyticsBaseUrl() {
        return stocklyticsBaseUrl;
    }

    /** Sentiment140 application id, sent as header {@code appid}. */
    public String sentiment140ApiKey() {
        return sentiment140ApiKey;
    }

    /** Sentiment140 base URL; the client appends {@code bulkClassifyJson}. */
    public String sentiment140BaseUrl() {
        return sentiment140BaseUrl;
    }
}
