/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config.BoxProperties;

/**
 * Outbound adapter to Box, replacing three elements of
 * {@code oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml}:
 * <ul>
 *   <li>line 5, {@code http:request-config HTTP_Request_Configuration_HTTPS}: HTTPS to {@code api.box.com:443}
 *       with {@code basePath="/2.0"} and {@code tlsContext-ref="TLS_Context"}, here {@link BoxProperties#getApiBaseUrl()}
 *       and {@link BoxProperties#sslContext()};</li>
 *   <li>lines 40-45, {@code http:request method="GET" path="/search"} with {@code http:query-param query=mule},
 *       here {@link #search(String)}, carrying the {@code Authorization: Bearer} header the
 *       {@code oauth2:authorization-code-grant-type} adds to every request on that configuration;</li>
 *   <li>line 13, {@code oauth2:token-request tokenUrl="https://www.box.com/api/oauth2/token"} of that grant
 *       ({@code clientId="${box.id}"}, {@code clientSecret="${box.secret}"},
 *       {@code redirectionUrl="https://localhost:8082/redirectUrl"}), here {@link #requestToken(String)} and
 *       {@link #refreshToken(String)}.</li>
 * </ul>
 *
 * <p>The class only performs the HTTP exchanges; {@code service/BoxOAuthService} interprets every response and
 * decides when to refresh. It never throws on an HTTP error status: a 4xx or 5xx answer is returned as a
 * {@link RawResponse} like any other. The read timeout bounds the whole response, headers and body: a response
 * not complete within it fails with an {@link HttpTimeoutException} cause, so a truncated body is never returned.
 * A refused connection, a DNS failure or a connect or read timeout propagates as
 * {@link org.springframework.web.client.ResourceAccessException}, and nothing here retries.
 *
 * <p>An {@code https} URL is sent through {@code TLS_Context} only: the trust store {@code trust-store} and the
 * key store {@code keystore.jks}, never the JDK {@code cacerts}. That client is built on the first {@code https}
 * request, so the application starts without the store passwords and plain-HTTP stubs never touch the stores.
 */
@Component
public class BoxApiClient {

    /** Connect timeout, the Mule default response timeout. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Read timeout, the Mule default response timeout: it bounds the whole response, from sending the request to
     * its last body byte, headers and body together.
     */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** The form media type exactly as written, with no {@code charset} parameter. */
    static final MediaType FORM_CONTENT_TYPE = MediaType.parseMediaType("application/x-www-form-urlencoded");

    /** {@code path="/search"} plus {@code http:query-param query=mule}, relative to the API base URL. */
    static final String SEARCH_SUFFIX = "/search?query=mule";

    private static final Logger LOGGER = LoggerFactory.getLogger(BoxApiClient.class);

    private final BoxProperties properties;
    private final RestClient httpClient;

    private final Object httpsClientLock = new Object();
    private volatile RestClient httpsClient;

    /**
     * Builds the plain-HTTP client only; the {@code TLS_Context} client is built on the first {@code https}
     * request.
     *
     * @param properties the Box OAuth and TLS configuration
     */
    public BoxApiClient(BoxProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.httpClient = restClient(httpClientBuilder().build());
    }

    /**
     * Raw result of one exchange with Box.
     *
     * @param status  the HTTP status code
     * @param headers the response headers, copied before the response is closed
     * @param body    the response body bytes, empty when there is none
     */
    public record RawResponse(int status, HttpHeaders headers, byte[] body) {
    }

    /**
     * Exchanges an authorization code for tokens: POST to {@link BoxProperties#getTokenUrl()} with the form
     * {@code code, grant_type=authorization_code, client_secret, redirect_uri, client_id}.
     *
     * @param code the authorization code Box sent to the redirection URL
     * @return the token endpoint's response, whatever its status
     * @throws IllegalStateException when {@code box.token-url} is not a usable URL, before any request is sent
     */
    public RawResponse requestToken(String code) {
        Objects.requireNonNull(code, "code must not be null");
        // Field order is the java.util.HashMap iteration order of the form Mule 3.8
        // AutoAuthorizationCodeTokenRequestHandler builds for these five keys.
        return postForm(formBody(
                "code", code,
                "grant_type", "authorization_code",
                "client_secret", properties.getSecret(),
                "redirect_uri", properties.getRedirectUrl(),
                "client_id", properties.getId()));
    }

    /**
     * Exchanges a refresh token for new tokens: POST to {@link BoxProperties#getTokenUrl()} with the form
     * {@code refresh_token, grant_type=refresh_token, client_secret, redirect_uri, client_id}.
     *
     * @param refreshToken the refresh token stored from an earlier token response
     * @return the token endpoint's response, whatever its status
     * @throws IllegalStateException when {@code box.token-url} is not a usable URL, before any request is sent
     */
    public RawResponse refreshToken(String refreshToken) {
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
        // HashMap iteration order of Mule 3.8's refresh form, as for requestToken.
        return postForm(formBody(
                "refresh_token", refreshToken,
                "grant_type", "refresh_token",
                "client_secret", properties.getSecret(),
                "redirect_uri", properties.getRedirectUrl(),
                "client_id", properties.getId()));
    }

    /**
     * Searches Box for {@code mule}: GET {@code <api base>/search?query=mule} with
     * {@code Authorization: Bearer <accessToken>} and no body.
     *
     * @param accessToken the access token of the authorized user
     * @return the search endpoint's response, whatever its status
     * @throws IllegalStateException when {@code box.api-base-url} is not a usable URL, before any request is
     *                               sent
     */
    public RawResponse search(String accessToken) {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        URI base = configuredUrl(properties.getApiBaseUrl(), "box.api-base-url", false);
        // The base has neither query nor fragment, so the suffix extends its path and adds the only query.
        URI uri = URI.create(stripTrailingSlash(base.toString()) + SEARCH_SUFFIX);
        LOGGER.debug("GET {}", forLog(uri));
        return execute(clientFor(uri).get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private RawResponse postForm(byte[] form) {
        URI uri = configuredUrl(properties.getTokenUrl(), "box.token-url", true);
        LOGGER.debug("POST {}", forLog(uri));
        // A byte[] body goes through ByteArrayHttpMessageConverter, which keeps FORM_CONTENT_TYPE as set;
        // Spring's form converter would append ";charset=UTF-8" and leave field order to the map.
        return execute(clientFor(uri).post()
                .uri(uri)
                .contentType(FORM_CONTENT_TYPE)
                .body(form));
    }

    /**
     * Runs the request through {@code exchange}, which bypasses RestClient's status handlers so that error
     * statuses are returned. RestClient wraps every {@link IOException} of the exchange, the body deadline's
     * {@link HttpTimeoutException} included, in {@link org.springframework.web.client.ResourceAccessException},
     * which is left to propagate.
     */
    private static RawResponse execute(RestClient.RequestHeadersSpec<?> spec) {
        // Taken before the request is sent, so the headers and the body share the one READ_TIMEOUT.
        long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
        return spec.exchange((request, response) -> toRaw(response, deadline));
    }

    private static RawResponse toRaw(ClientHttpResponse response, long deadline) throws IOException {
        int status = response.getStatusCode().value();
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        byte[] body = readBody(response.getBody(), deadline);
        return new RawResponse(status, headers, body);
    }

    /**
     * Reads the whole response body, failing once {@code deadline} (a {@link System#nanoTime()} value) passes.
     *
     * <p>The request factory's read timeout ends when the response headers arrive; the JDK client then streams
     * the body with no timeout of its own, so a server that sends headers and stalls its body would block this
     * read indefinitely. When the deadline passes first, the stream is closed from the timer thread, which
     * unblocks the read. The bytes read up to then may be incomplete, so the read fails with an
     * {@link HttpTimeoutException} whether the closed stream ended the read with an error or not.
     *
     * @return every body byte, an empty array when the response has no body
     * @throws HttpTimeoutException when the body is not complete by the deadline
     * @throws IOException          on any other I/O failure while reading
     */
    private static byte[] readBody(InputStream body, long deadline) throws IOException {
        long remainingNanos = Math.max(0L, deadline - System.nanoTime());
        CompletableFuture<Void> expiry = new CompletableFuture<Void>()
                .completeOnTimeout(null, remainingNanos, TimeUnit.NANOSECONDS);
        expiry.thenRun(() -> closeAbandoned(body));
        byte[] bytes;
        try {
            bytes = StreamUtils.copyToByteArray(body);
        } catch (IOException ex) {
            // cancel succeeds only while the timer has not fired; once it has, the failure comes from the
            // stream the timer closed.
            if (expiry.cancel(false)) {
                throw ex;
            }
            throw timedOut(ex);
        } finally {
            expiry.cancel(false);
        }
        if (expiry.isCancelled()) {
            return bytes;
        }
        // The timer closed the stream before the read ended, so the bytes may be truncated.
        throw timedOut(null);
    }

    private static void closeAbandoned(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // The stream is being abandoned; the reading thread reports the timeout.
        }
    }

    private static HttpTimeoutException timedOut(IOException cause) {
        HttpTimeoutException timedOut =
                new HttpTimeoutException("Response not received within " + READ_TIMEOUT.toMillis() + " ms");
        if (cause != null) {
            timedOut.initCause(cause);
        }
        return timedOut;
    }

    private RestClient clientFor(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) ? httpsClient() : httpClient;
    }

    private RestClient httpsClient() {
        RestClient client = httpsClient;
        if (client == null) {
            synchronized (httpsClientLock) {
                client = httpsClient;
                if (client == null) {
                    client = restClient(httpClientBuilder().sslContext(properties.sslContext()).build());
                    httpsClient = client;
                }
            }
        }
        return client;
    }

    /**
     * HTTP/1.1 is pinned because the Mule 3.8 requester speaks HTTP/1.1 and the JDK default (HTTP/2) adds h2c
     * {@code Upgrade} and {@code HTTP2-Settings} headers to plain-HTTP requests.
     */
    private static HttpClient.Builder httpClientBuilder() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL);
    }

    /** The static builder, not Boot's {@code RestClient.Builder} bean, so no customizer or default header applies. */
    private static RestClient restClient(HttpClient client) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Hand-built {@code application/x-www-form-urlencoded} body. Keys are ASCII literals; values are encoded
     * with {@link URLEncoder} (space as {@code +}, as Mule's form encoder writes it), and a {@code null}
     * configuration value is sent as the empty string.
     */
    private static byte[] formBody(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("Form fields must be given as key-value pairs");
        }
        StringBuilder form = new StringBuilder();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            if (i > 0) {
                form.append('&');
            }
            String value = keyValuePairs[i + 1];
            form.append(keyValuePairs[i])
                    .append('=')
                    .append(value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return form.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parses and checks a configured URL before any request uses it; the result is passed to RestClient as a
     * {@link URI} so that no template expansion or re-encoding happens. The URL must be an absolute {@code http}
     * or {@code https} URL with a host and with neither user information nor a fragment. A query is accepted only
     * when {@code queryAllowed}: the token endpoint may carry one, which is sent unchanged, while the search
     * suffix and its query are appended to the API base URL. A value that fails a check fails with an
     * {@link IllegalStateException} naming only the configuration key it came from; neither the message nor a
     * cause carries the value, since a URL can carry credentials.
     */
    private static URI configuredUrl(String url, String key, boolean queryAllowed) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            // getReason() leaves out the input that getMessage() appends; e is not attached for the same reason.
            throw new IllegalStateException(key + " is not a valid URL: " + e.getReason());
        }
        if (!uri.isAbsolute()) {
            throw new IllegalStateException(key + " is not an absolute URL");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException(key + " is not an http or https URL");
        }
        if (uri.getHost() == null) {
            throw new IllegalStateException(key + " has no host");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalStateException(key + " must not contain user information");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalStateException(key + " must not contain a fragment");
        }
        if (!queryAllowed && uri.getRawQuery() != null) {
            throw new IllegalStateException(key + " must not contain a query");
        }
        return uri;
    }

    /**
     * The form of a request URI written to the log: scheme, host, port and path. The query and fragment are left
     * out, so the query a token endpoint may carry is sent but never logged; user information never reaches a
     * request, {@link #configuredUrl} having rejected it.
     */
    private static String forLog(URI uri) {
        StringBuilder text = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() != -1) {
            text.append(':').append(uri.getPort());
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null) {
            text.append(rawPath);
        }
        return text.toString();
    }

    private static String stripTrailingSlash(String url) {
        String stripped = url;
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
