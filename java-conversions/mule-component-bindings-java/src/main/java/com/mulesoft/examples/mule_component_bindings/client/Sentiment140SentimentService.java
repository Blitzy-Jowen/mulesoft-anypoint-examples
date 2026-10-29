/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.mule_component_bindings.config.StockStatsProperties;
import com.mulesoft.examples.mule_component_bindings.model.BulkClassifyRequest;
import com.mulesoft.examples.mule_component_bindings.model.ClassifyRequest;
import com.mulesoft.examples.mule_component_bindings.model.Sentiment;
import com.mulesoft.examples.mule_component_bindings.model.Tweet;
import com.mulesoft.examples.mule_component_bindings.service.SentimentService;

/**
 * A SentimentService implementation that delegates to the Sentiment140 cloud API.
 *
 * <p>The call runs over {@link java.net.HttpURLConnection} with a 10 s connect timeout and a 10 s read timeout,
 * the Mule default response timeout. The read timeout is the socket's own, so it bounds the wait for the
 * response headers and every blocking read of the body: an upstream that never answers, or that sends its
 * headers and then stalls its body, fails the call 10 s after it falls silent with a
 * {@link org.springframework.web.client.ResourceAccessException} caused by a
 * {@link java.net.SocketTimeoutException}. A failed call is never retried.
 */
@Component
public class Sentiment140SentimentService implements SentimentService {

	/** Connect and read timeout of the application's client: the Mule default response timeout. */
	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

	/** Shortest accepted timeout: {@link java.net.HttpURLConnection} takes whole milliseconds and reads 0 as none. */
	private static final Duration MIN_TIMEOUT = Duration.ofMillis(1);

	/** Longest accepted timeout: {@link java.net.HttpURLConnection} takes an {@code int} of milliseconds. */
	private static final Duration MAX_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

	private final StockStatsProperties properties;
	private final RestClient client;
	private final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * Creates the service the application uses, with the 10 s Mule default connect and read timeouts.
	 *
	 * @param restClientBuilder the builder the client is built from; its request factory is replaced by the timed one
	 * @param properties        the Sentiment140 base URL and application id
	 */
	@Autowired
	public Sentiment140SentimentService(RestClient.Builder restClientBuilder, StockStatsProperties properties) {
		this(restClientBuilder, properties, DEFAULT_TIMEOUT);
	}

	/**
	 * Creates the service with the given connect and read timeout. Building the client opens no connection.
	 *
	 * @param restClientBuilder the builder the client is built from; its request factory is replaced by the timed one
	 * @param properties        the Sentiment140 base URL and application id
	 * @param timeout           the connect timeout, and the longest any single read of the response, headers or
	 *                          body, may block; from 1 ms to {@link Integer#MAX_VALUE} ms
	 * @throws IllegalArgumentException when {@code timeout} is {@code null} or outside that range; below 1 ms it
	 *                                  would truncate to 0, which means no timeout at all
	 */
	public Sentiment140SentimentService(RestClient.Builder restClientBuilder, StockStatsProperties properties,
			Duration timeout) {
		if (timeout == null || timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
			throw new IllegalArgumentException("Sentiment140 timeout must be from " + MIN_TIMEOUT.toMillis()
					+ " ms to " + MAX_TIMEOUT.toMillis() + " ms, was " + timeout);
		}
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(timeout);
		requestFactory.setReadTimeout(timeout);
		this.properties = properties;
		this.client = restClientBuilder.requestFactory(requestFactory).build();
	}

	/**
	 * Classifies {@code tweets} with one Sentiment140 bulk request, posted for an empty list too, and sets each
	 * tweet's sentiment from the polarity returned for its id.
	 *
	 * @param tweets the tweets to classify
	 * @throws IllegalStateException when {@code stockstats.sentiment140ApiKey} has no text (absent, empty or
	 *                               whitespace only), before any request is made, whether or not {@code tweets} is
	 *                               empty; the message names the key, never a configured value
	 */
	@Override
	public void classify(List<Tweet> tweets) {

		if (!StringUtils.hasText(properties.sentiment140ApiKey())) {
			throw new IllegalStateException(
					"Sentiment140 application ID (stockstats.sentiment140ApiKey) is not configured");
		}

		URI target = URI.create(properties.sentiment140BaseUrl() + "bulkClassifyJson");

		BulkClassifyRequest bulkRequest = new BulkClassifyRequest();
		Map<String,Tweet> idToTweet = new HashMap<String, Tweet>();
		List<ClassifyRequest> data = new ArrayList<ClassifyRequest>();
		for (Tweet tweet: tweets) {
			ClassifyRequest request = new ClassifyRequest();
			request.setId(tweet.getId());
			request.setText(tweet.getText());
			data.add(request);
			idToTweet.put(tweet.getId(), tweet);
		}
		bulkRequest.setData(data);

		// The original Entity.text(bulkRequest) had no text writer for the bean; the conversion posts its JSON.
		// Sent as UTF-8 bytes: a String body declared text/plain without a charset would be encoded as ISO-8859-1.
		byte[] body;
		try {
			body = objectMapper.writeValueAsBytes(bulkRequest);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}

		// exchange() reports every status instead of throwing on non-2xx, so the original status check below decides.
		RawResponse raw = client.post()
				.uri(target)
				.accept(MediaType.APPLICATION_JSON)
				.header("appid", properties.sentiment140ApiKey())
				.contentType(MediaType.TEXT_PLAIN)
				.body(body)
				.exchange((request, response) -> readResponse(response));

		if (raw.status() != HttpStatus.OK.value()) {
			throw new RuntimeException("Sentiment140 returned status " + raw.status());
		}

		// The response body as received replaces the original's response.getEntity().toString().
		String jsonStr = raw.body();

		try {
			JsonNode rootNode = objectMapper.readTree(jsonStr);
			JsonNode dataNode = rootNode.path("data");
			for (Iterator<JsonNode> i = dataNode.elements(); i.hasNext();) {
				JsonNode resultNode = i.next();
				String id = resultNode.path("id").textValue();
				int polarity = resultNode.path("polarity").intValue();

				Sentiment sentiment;
				if (polarity == 0) {
					sentiment = Sentiment.NEGATIVE;
				} else if (polarity == 2) {
					sentiment = Sentiment.NEUTRAL;
				} else {
					sentiment = Sentiment.POSITIVE;
				}

				Tweet tweet = idToTweet.get(id);
				// An id no tweet was sent with, or a result without a textual id, fails the call as dereferencing
				// the null lookup did. The NPE is thrown explicitly and without a message because one the JVM
				// raises carries a helpful message naming this code, which the original runtime never produced;
				// the wrapper's message, and so the 500 body, stays exactly java.lang.NullPointerException.
				if (tweet == null) {
					throw new NullPointerException();
				}
				tweet.setSentiment(sentiment);
			}

		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Reads the status and the whole body. The status comes first, because {@link java.net.HttpURLConnection}
	 * hands out the body of an error status only once that status has been read. When reading the body fails, a
	 * read timeout included, the body stream is closed before the failure propagates: closing the response drains
	 * the stream first, and on a stalled body that drain would block for a second full read timeout.
	 */
	private static RawResponse readResponse(ClientHttpResponse response) throws IOException {
		int status = response.getStatusCode().value();
		InputStream bodyStream = response.getBody();
		try {
			return new RawResponse(status,
					StreamUtils.copyToString(bodyStream, charsetOf(response.getHeaders().getContentType())));
		} catch (IOException | RuntimeException failure) {
			try {
				bodyStream.close();
			} catch (IOException closeFailure) {
				failure.addSuppressed(closeFailure);
			}
			throw failure;
		}
	}

	private static Charset charsetOf(MediaType contentType) {
		if (contentType == null || contentType.getCharset() == null) {
			return StandardCharsets.UTF_8;
		}
		return contentType.getCharset();
	}

	private record RawResponse(int status, String body) {
	}

}
