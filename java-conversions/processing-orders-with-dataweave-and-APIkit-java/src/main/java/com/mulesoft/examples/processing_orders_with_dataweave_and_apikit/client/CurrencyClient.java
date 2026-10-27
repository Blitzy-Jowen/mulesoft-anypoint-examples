/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Reads the currency ratios from the application's own Currency API, replacing the enricher's outbound call of
 * {@code processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml}: the
 * {@code HTTP_Request_Configuration} {@code http:request-config} (l.3-5: host {@code localhost}, port
 * {@code ${http.port}}, basePath {@code /api/}) and the {@code http:request} GET {@code /currencies} that
 * {@code <enricher target="flowVars.currencies">} wraps (l.9-11).
 *
 * <p>As in the original, the order flow does not read {@code currency.json} itself: it calls
 * {@code GET http://localhost:<port>/api/currencies}, served by this application's {@code CurrencyController}, and
 * hands the parsed tree to the order mapping as the {@code currencies} value. The nested
 * {@code http:raml-api-configuration} was design-time metadata only and has no counterpart here.
 *
 * <p>The response is parsed by a private mapper that keeps every JSON decimal as an exact {@link java.math.BigDecimal}
 * with its written scale ({@code 0.92} stays {@code 0.92}, never a {@code double}), because the order mapping
 * multiplies each {@code USD[i].ratio} by the item price and the DataWeave output keeps the scale of that product
 * ({@code 27.60}, {@code 27.5908}). Boot's shared {@code ObjectMapper} is deliberately not used for this reason.
 *
 * <p>Failures propagate to the caller unchanged, as Mule's {@code http:request} failed the flow: a status outside
 * 200..299 raises {@link RestClientResponseException}, a refused connection or a timeout raises
 * {@link ResourceAccessException}, and a body that is not JSON raises {@link UncheckedIOException}. There is no
 * retry, and nothing is logged here; the file trigger logs the failed flow.
 */
@Component
public class CurrencyClient {

    /** The request-config basePath {@code /api/} joined with the request path {@code /currencies}. */
    static final String CURRENCIES_PATH = "/api/currencies";

    /** The listener port when neither the running server nor {@code http.port} names one. */
    static final int DEFAULT_PORT = 8081;

    /** Connect and read timeout: the Mule default response timeout of 10 s. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;

    private final Environment environment;

    private final ObjectMapper objectMapper;

    /**
     * @param restClientBuilder the builder to derive the client from; it is cloned, so the caller's builder is
     *                          left unchanged
     * @param environment       the environment the target port is read from on every call
     */
    public CurrencyClient(RestClient.Builder restClientBuilder, Environment environment) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT);
        requestFactory.setReadTimeout(TIMEOUT);
        this.restClient = restClientBuilder.clone().requestFactory(requestFactory).build();
        this.environment = environment;
        this.objectMapper = exactDecimalMapper();
    }

    /**
     * Calls {@code GET http://localhost:<port>/api/currencies} with {@code Accept: application/json} and returns
     * the parsed body unchanged, shaped {@code {"USD": [{"currency": ..., "ratio": ...}, ...]}} with every ratio a
     * {@code DecimalNode}. An empty body yields the {@code MissingNode} Jackson returns for no content.
     *
     * @return the currencies tree, the original's {@code flowVars.currencies}
     * @throws RestClientResponseException when the Currency API answers with a status outside 200..299
     * @throws ResourceAccessException     when the connection is refused or the call times out
     * @throws UncheckedIOException        when the body is not valid JSON
     */
    public JsonNode getCurrencies() {
        String url = "http://localhost:" + resolvePort() + CURRENCIES_PATH;
        byte[] body = restClient.get()
                .uri(url)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(byte[].class);
        try {
            return objectMapper.readTree(body == null ? new byte[0] : body);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to parse the Currency API response from " + url, e);
        }
    }

    /**
     * The port of the original's {@code ${http.port}}, which is also this application's listener port: the running
     * server's actual port first, so a random or overridden port is followed, then the configured
     * {@code http.port}, then {@value #DEFAULT_PORT}. It is resolved on every call because
     * {@code local.server.port} exists only once the embedded server has started.
     *
     * @return the port the Currency API is called on
     * @throws NumberFormatException when the property found is not an integer
     */
    int resolvePort() {
        String localServerPort = environment.getProperty("local.server.port");
        if (StringUtils.hasText(localServerPort)) {
            return Integer.parseInt(localServerPort.trim());
        }
        String httpPort = environment.getProperty("http.port");
        if (StringUtils.hasText(httpPort)) {
            return Integer.parseInt(httpPort.trim());
        }
        return DEFAULT_PORT;
    }

    /**
     * A mapper that reads floating-point numbers as {@link java.math.BigDecimal} and keeps their trailing zeros.
     * Jackson 2.15 honours {@link JsonNodeFeature#STRIP_TRAILING_BIGDECIMAL_ZEROES} only when it is set explicitly
     * and otherwise falls back to the node factory's flag, so both are set.
     */
    @SuppressWarnings("deprecation") // JsonNodeFactory.withExactBigDecimals is deprecated in favour of the feature
    private static ObjectMapper exactDecimalMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
                .build();
    }
}
