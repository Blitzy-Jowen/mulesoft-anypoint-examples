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
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.Objects;
import java.util.Set;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.SystemDefaultRoutePlanner;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.protocol.RedirectLocations;
import org.apache.hc.client5.http.utils.URIUtils;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.io.EofSensorInputStream;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
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
 * <p>The call has a 10 s connect timeout and a 10 s read timeout, the Mule default response timeout, as the
 * project's Sentiment140 and Twitter clients do; the original Jersey client had none, so an upstream that never
 * answered held the stock statistics request forever. The read timeout is the socket's own, so it bounds the wait
 * for the response headers and every blocking read of the body: an upstream that falls silent fails the call 10 s
 * later with a {@link org.springframework.web.client.ResourceAccessException} caused by a
 * {@link java.net.SocketTimeoutException}.
 *
 * <p>A failed call is never retried, neither here nor by the transport, so the GET is sent once, whether it fails
 * with a timeout, a refused, reset or early-closed connection or an error status. RestClient sends through Apache
 * httpclient5 with its automatic retries switched off, because the JDK {@link java.net.HttpURLConnection} under
 * Spring's default request factory re-sends a bodiless request, as this GET is, once when its connection is reset
 * or closes before a response arrives, and has no setting to stop it. Apart from that re-send, and from following a
 * redirect only on the origin of the configured Stocklytics URL, {@link #buildHttpClient(Timeout)} keeps every
 * behaviour of the {@code HttpURLConnection} transport. The service owns a connection pool, which {@link #close()}
 * releases when the application context shuts down.
 */
@Component
public class StocklyticsStockService implements StockService, AutoCloseable {

	private static final String FORMAT = "JSON";

	/** Connect and read timeout of the application's client: the Mule default response timeout. */
	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

	/** Shortest accepted timeout: a socket timeout counts whole milliseconds and reads 0 as none. */
	private static final Duration MIN_TIMEOUT = Duration.ofMillis(1);

	/** Longest accepted timeout: a socket timeout is an {@code int} of milliseconds. */
	private static final Duration MAX_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

	/**
	 * The most redirects the GET follows. {@code HttpURLConnection} sends at most {@code http.maxRedirects}, by JDK
	 * default 20, requests per exchange and fails with an I/O error when the last is answered with a redirect too;
	 * httpclient5 sends one request more than its limit before failing the same way.
	 */
	private static final int MAX_REDIRECTS_FOLLOWED = 19;

	/**
	 * How long an idle pooled connection stays reusable when the server's {@code Keep-Alive} header names no
	 * timeout: the 5 s the JDK keep-alive cache kept one, so no request is written to a connection idle for longer.
	 */
	private static final TimeValue IDLE_CONNECTION_LIFETIME = TimeValue.ofSeconds(5);

	private final StockStatsProperties properties;
	private final CloseableHttpClient httpClient;
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
	 * Creates the service with the given connect and read timeout. Building the client and its connection pool
	 * opens no connection.
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
		this.properties = properties;
		this.httpClient = buildHttpClient(Timeout.ofMilliseconds(timeout.toMillis()));
		// No factory timeout is set, so the client's own configuration governs the exchange.
		this.client = restClientBuilder.requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient)).build();
	}

	/** Closes the connection pool and its connections; Spring calls it when the application context shuts down. */
	@Override
	public void close() {
		httpClient.close(CloseMode.GRACEFUL);
	}

	/**
	 * Builds the transport, configured so that the GET is never sent twice:
	 * <ul>
	 *   <li>automatic retries are off, so a request whose connection is refused, reset or closed before or while
	 *       the response arrives fails instead of being sent again;</li>
	 *   <li>a host name resolves to its first address only, as {@code HttpURLConnection} connected, so a refused or
	 *       timed-out connect is not repeated against the host's other addresses;</li>
	 *   <li>a pooled connection is checked before every reuse and kept idle no longer than the server's
	 *       {@code Keep-Alive} timeout, or {@link #IDLE_CONNECTION_LIFETIME} without one, so one the server closed
	 *       is replaced before the request is written rather than failing it, which {@code HttpURLConnection} hid
	 *       by re-sending the request.</li>
	 * </ul>
	 *
	 * <p>Everything else is as {@code HttpURLConnection} did it. {@code timeout} bounds the TCP connect and every
	 * wait for response bytes, the TLS handshake included, each wait on its own. Redirects are followed as
	 * {@link HttpUrlConnectionRedirectStrategy} describes, with the same request headers, but only on the origin of
	 * the configured Stocklytics URL, where {@code HttpURLConnection} followed a same-scheme one to any host: a
	 * redirect to another host, port or scheme is returned as received, so the API key and the request data are never
	 * sent there. An HTTP proxy is used where the JVM's default {@link java.net.ProxySelector}, read for every route,
	 * names one, and {@code https} connects with the JSSE default context, protocols and cipher suites
	 * ({@code https.protocols}, {@code https.cipherSuites}).
	 * Content compression is off, so no {@code Accept-Encoding} is sent and the body is read as sent; there is no
	 * cookie store, auth cache or connection state, and authentication challenges are not answered. The pool has no
	 * size limit, as {@code HttpURLConnection} had none, so no request waits for another's connection.
	 */
	private static CloseableHttpClient buildHttpClient(Timeout timeout) {
		return HttpClients.custom()
				.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
						.useSystemProperties()
						.setDnsResolver(new FirstAddressDnsResolver(SystemDefaultDnsResolver.INSTANCE))
						.setDefaultConnectionConfig(ConnectionConfig.custom()
								.setConnectTimeout(timeout)
								.setSocketTimeout(timeout)
								.setValidateAfterInactivity(TimeValue.ZERO_MILLISECONDS)
								.build())
						.setMaxConnTotal(Integer.MAX_VALUE)
						.setMaxConnPerRoute(Integer.MAX_VALUE)
						.build())
				// A null selector reads ProxySelector.getDefault() for every route.
				.setRoutePlanner(new SystemDefaultRoutePlanner(null))
				.setDefaultRequestConfig(RequestConfig.custom()
						.setResponseTimeout(timeout)
						.setConnectionKeepAlive(IDLE_CONNECTION_LIFETIME)
						.setRedirectsEnabled(true)
						.setMaxRedirects(MAX_REDIRECTS_FOLLOWED)
						// A redirect loop ends at the redirect limit, as it did for HttpURLConnection, where
						// httpclient5's loop check would end it at the first repeated location.
						.setCircularRedirectsAllowed(true)
						.setAuthenticationEnabled(false)
						.build())
				.disableAutomaticRetries()
				.disableContentCompression()
				.disableCookieManagement()
				.disableAuthCaching()
				.disableConnectionState()
				.setRedirectStrategy(new HttpUrlConnectionRedirectStrategy())
				.build();
	}

	/**
	 * Fetches the open, close, high, low and volume of {@code stock} on {@code closingDate} from Stocklytics.
	 *
	 * @param stock       the stock symbol, sent as header {@code stock}
	 * @param closingDate the day, sent as headers {@code start} and {@code end} in {@code yyyy-MM-dd}
	 * @return the day's financial statistics
	 * @throws InvalidStockSymbol    when Stocklytics answers 200 with its no-data text for {@code stock}
	 * @throws IllegalStateException when {@code stockstats.stocklyticsApiKey} has no text (absent, empty or
	 *                               whitespace only), before any request is made; the message names the key,
	 *                               never a configured value
	 */
	@Override
	public StockStats getHistoricalPrices(String stock, Date closingDate) throws InvalidStockSymbol {

		if (!StringUtils.hasText(properties.stocklyticsApiKey())) {
			throw new IllegalStateException("Stocklytics API key (stockstats.stocklyticsApiKey) is not configured");
		}

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
	 * Reads the status and the whole body. When reading the body fails, a read timeout included, the connection the
	 * body arrives on is discarded before the failure propagates: closing the body stream or the response reads the
	 * rest of the body first, to keep the connection, and on a stalled body that read would block for a second full
	 * read timeout. httpclient5 hands such a body out as an {@link EofSensorInputStream}, whose
	 * {@link EofSensorInputStream#abort()} discards the connection without reading on; any other body is not backed
	 * by a connection and is closed.
	 */
	private static RawResponse readResponse(ClientHttpResponse response) throws IOException {
		int status = response.getStatusCode().value();
		InputStream bodyStream = response.getBody();
		try {
			return new RawResponse(status,
					StreamUtils.copyToString(bodyStream, charsetOf(response.getHeaders().getContentType())));
		} catch (IOException | RuntimeException failure) {
			try {
				if (bodyStream instanceof EofSensorInputStream connectionBody) {
					connectionBody.abort();
				} else {
					bodyStream.close();
				}
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

	/**
	 * Follows a redirect only where {@code HttpURLConnection}, as {@code SimpleClientHttpRequestFactory} set it up,
	 * followed one, and only on the configured Stocklytics origin: the request is a GET, the status is 300, 301,
	 * 302, 303 or 307 with a {@code Location}, that location keeps the scheme of the URL that answered, which a
	 * relative one always does, and it is on the origin of the original request, the
	 * {@code stockstats.stocklytics-base-url} one. Two URLs share an origin when their schemes and hosts are equal,
	 * each compared case-insensitively, and so are their ports, a URL without one counting 80 for {@code http} and 443
	 * for {@code https}. A relative location is first resolved against the URL that answered, as httpclient5 then
	 * resolves it, so a network-path one such as {@code //host/path} names its own host.
	 *
	 * <p>Any other redirect, a 308, a {@code 305 Use Proxy} or one to another host, port or scheme among them, is
	 * returned as received, and nothing is sent to its location. httpclient5 sends a followed redirect with every
	 * header of the original request, so following one off the configured origin would hand that location the
	 * {@code api_key} and the request data; {@code HttpURLConnection} followed a same-scheme redirect to any host with
	 * them. A redirect on the configured origin is followed with all of them, as before.
	 */
	private static final class HttpUrlConnectionRedirectStrategy extends DefaultRedirectStrategy {

		private static final Set<Integer> FOLLOWED_STATUSES = Set.of(300, 301, 302, 303, 307);

		private static final int HTTP_DEFAULT_PORT = 80;

		private static final int HTTPS_DEFAULT_PORT = 443;

		@Override
		public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context)
				throws ProtocolException {
			Header location = response.getFirstHeader(HttpHeaders.LOCATION);
			if (location == null || !Method.GET.isSame(request.getMethod())
					|| !FOLLOWED_STATUSES.contains(response.getCode())) {
				return false;
			}
			URI configured = uriOf(request);
			URI answered = answeredUri(configured, context);
			URI target = createLocationURI(location.getValue());
			if (!target.isAbsolute()) {
				target = URIUtils.resolve(answered, target);
			}
			return sameText(target.getScheme(), answered.getScheme()) && sameOrigin(target, configured);
		}

		/**
		 * The URL of the original request, the configured Stocklytics one: httpclient5 passes the original request
		 * to every redirect decision of the exchange.
		 */
		private static URI uriOf(HttpRequest original) throws ProtocolException {
			try {
				return original.getUri();
			} catch (URISyntaxException ex) {
				throw new ProtocolException("Invalid request URI: " + ex.getMessage(), ex);
			}
		}

		/**
		 * The URL that answered with the redirect: the last redirect location followed, or the original request's
		 * before any has been.
		 */
		private static URI answeredUri(URI original, HttpContext context) {
			RedirectLocations followed = HttpClientContext.adapt(context).getRedirectLocations();
			return followed == null || followed.size() == 0 ? original : followed.get(followed.size() - 1);
		}

		private static boolean sameOrigin(URI first, URI second) {
			return sameText(first.getScheme(), second.getScheme()) && sameText(first.getHost(), second.getHost())
					&& effectivePort(first) == effectivePort(second);
		}

		/** Whether both are present and equal ignoring case; a missing scheme or host matches nothing. */
		private static boolean sameText(String first, String second) {
			return first != null && first.equalsIgnoreCase(second);
		}

		/** The URL's port, or without one the default port of its scheme; -1 for a scheme that has none. */
		private static int effectivePort(URI uri) {
			if (uri.getPort() >= 0) {
				return uri.getPort();
			}
			if (URIScheme.HTTP.same(uri.getScheme())) {
				return HTTP_DEFAULT_PORT;
			}
			return URIScheme.HTTPS.same(uri.getScheme()) ? HTTPS_DEFAULT_PORT : -1;
		}
	}

	/**
	 * The first address {@code delegate} resolves a host to, alone; with the system resolver that is the one address
	 * {@code HttpURLConnection} connected to. httpclient5 would otherwise move on to each further address of the host
	 * after a refused or timed-out connect: a second connection attempt after a connectivity failure, which could
	 * also stretch the connect past its timeout.
	 */
	private static final class FirstAddressDnsResolver implements DnsResolver {

		private final DnsResolver delegate;

		FirstAddressDnsResolver(DnsResolver delegate) {
			this.delegate = Objects.requireNonNull(delegate, "delegate");
		}

		@Override
		public InetAddress[] resolve(String host) throws UnknownHostException {
			InetAddress[] addresses = delegate.resolve(host);
			return addresses.length <= 1 ? addresses : new InetAddress[] {addresses[0]};
		}

		@Override
		public String resolveCanonicalHostname(String host) throws UnknownHostException {
			return delegate.resolveCanonicalHostname(host);
		}
	}

}
