/**
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
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.mule_component_bindings.config.StockStatsProperties;
import com.mulesoft.examples.mule_component_bindings.exception.InvalidStockSymbol;
import com.mulesoft.examples.mule_component_bindings.model.StockStats;
import com.mulesoft.examples.mule_component_bindings.service.StockService;

/**
 * A StockService implementation that delegates to the Stocklytics cloud API.
 *
 * <p>The call runs over {@link java.net.HttpURLConnection} with a 10 s connect timeout and a 10 s read timeout,
 * the Mule default response timeout, as the project's Sentiment140 and Twitter clients do; the original Jersey
 * client had none, so an upstream that never answered held the stock statistics request forever. The read timeout
 * is the socket's own, so it bounds the wait for the response headers and every blocking read of the body: an
 * upstream that falls silent fails the call 10 s later with a
 * {@link org.springframework.web.client.ResourceAccessException} caused by a
 * {@link java.net.SocketTimeoutException}. A failed call is never retried.
 */
@Component
public class StocklyticsStockService implements StockService {

	private static final String FORMAT = "JSON";

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
	 * @param properties        the Stocklytics base URL and API key
	 */
	@Autowired
	public StocklyticsStockService(RestClient.Builder restClientBuilder, StockStatsProperties properties) {
		this(restClientBuilder, properties, DEFAULT_TIMEOUT);
	}

	/**
	 * Creates the service with the given connect and read timeout. Building the client opens no connection.
	 *
	 * @param restClientBuilder the builder the client is built from; its request factory is replaced by the timed one
	 * @param properties        the Stocklytics base URL and API key
	 * @param timeout           the connect timeout, and the longest any single read of the response, headers or
	 *                          body, may block; from 1 ms to {@link Integer#MAX_VALUE} ms
	 * @throws IllegalArgumentException when {@code timeout} is {@code null} or outside that range; below 1 ms it
	 *                                  would truncate to 0, which means no timeout at all
	 */
	public StocklyticsStockService(RestClient.Builder restClientBuilder, StockStatsProperties properties,
			Duration timeout) {
		if (timeout == null || timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(MAX_TIMEOUT) > 0) {
			throw new IllegalArgumentException("Stocklytics timeout must be from " + MIN_TIMEOUT.toMillis()
					+ " ms to " + MAX_TIMEOUT.toMillis() + " ms, was " + timeout);
		}
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(timeout);
		requestFactory.setReadTimeout(timeout);
		this.properties = properties;
		this.client = restClientBuilder.requestFactory(requestFactory).build();
	}

	@Override
	public StockStats getHistoricalPrices(String stock, Date closingDate) throws InvalidStockSymbol {

		DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
		String closingDateStr = df.format(closingDate);

		URI target = URI.create(properties.stocklyticsBaseUrl() + "historicalPrices/1.0");

		// exchange() reports every status instead of throwing on non-2xx, so the original status check below decides.
		RawResponse raw = client.get()
				.uri(target)
				.accept(MediaType.APPLICATION_JSON)
				.header("api_key", properties.stocklyticsApiKey())
				.header("format", FORMAT)
				.header("stock", stock)
				.header("start", closingDateStr)
				.header("end", closingDateStr)
				.exchange((request, response) -> readResponse(response));

		if (raw.status() != HttpStatus.OK.value()) {
			throw new RuntimeException("Stockylitics returned status " + raw.status());
		}

		// The response body as received replaces the original's response.getEntity().toString().
		String jsonStr = raw.body();

		// A 200 status is sent back for invalid stocks
		if (jsonStr.contains("Error: Stocklytics doesn't have data for that stock.")) {
			throw new InvalidStockSymbol(stock);
		}

		try {
			JsonNode rootNode = objectMapper.readTree(jsonStr);
			StockStats price = new StockStats();
			price.setClosingDate(closingDateStr);
			price.getFinancialStats().setOpen(rootNode.path(closingDateStr).path("open").asDouble());
			price.getFinancialStats().setClose(rootNode.path(closingDateStr).path("close").asDouble());
			price.getFinancialStats().setHigh(rootNode.path(closingDateStr).path("high").asDouble());
			price.getFinancialStats().setLow(rootNode.path(closingDateStr).path("low").asDouble());
			price.getFinancialStats().setVolume(rootNode.path(closingDateStr).path("volume").asLong());

			return price;

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
