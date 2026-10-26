/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.client;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
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
 */
@Component
public class Sentiment140SentimentService implements SentimentService {

	private final StockStatsProperties properties;
	private final RestClient client;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public Sentiment140SentimentService(RestClient.Builder restClientBuilder, StockStatsProperties properties) {
		this.properties = properties;
		this.client = restClientBuilder.build();
	}

	@Override
	public void classify(List<Tweet> tweets) {

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
				.exchange((request, response) -> new RawResponse(
						response.getStatusCode().value(),
						StreamUtils.copyToString(response.getBody(), charsetOf(response.getHeaders().getContentType()))));

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
				tweet.setSentiment(sentiment);
			}

		} catch (Exception e) {
			throw new RuntimeException(e);
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
