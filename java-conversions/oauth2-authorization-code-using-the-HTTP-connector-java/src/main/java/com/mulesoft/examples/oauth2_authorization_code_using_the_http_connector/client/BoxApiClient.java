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
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.protocol.RedirectLocations;
import org.apache.hc.client5.http.ssl.HttpsSupport;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.io.EofSensorInputStream;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.URIAuthority;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
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
 * not complete within it of sending the request fails with an {@link HttpTimeoutException} cause, so a truncated
 * body is never returned. A refused, reset or early-closed connection, a DNS failure, a TLS failure or a connect or
 * read timeout propagates as {@link org.springframework.web.client.ResourceAccessException}.
 *
 * <p>No request is ever sent twice, neither here nor by the transport, so a failed search or token request is
 * never repeated behind the caller's back and the only repetition left is the one
 * {@code service/BoxOAuthService} makes after Box rejects an access token. The transport is Apache httpclient5,
 * whose automatic retries can be switched off per client, rather than {@code java.net.http.HttpClient}, which
 * re-sends a GET whose connection closes before the response arrives, with no setting to stop it, and repeats a
 * refused connect unless a JVM-wide system property forbids it; {@link #buildHttpClient(BoxProperties)} lists what
 * else keeps every exchange to one attempt.
 *
 * <p>Redirects are followed as {@code java.net.http.HttpClient.Redirect.NORMAL} followed them, at most
 * {@link #MAX_REDIRECTS_FOLLOWED} per exchange and never from an {@code https} URL to an {@code http} one; a redirect
 * beyond those limits is returned as received. A redirect hop that leaves the
 * origin of the original request carries none of {@code Authorization}, {@code Cookie}, {@code Origin},
 * {@code Referer} or {@code Host}, as the JDK client dropped them, so the bearer token never reaches another origin.
 *
 * <p>Every {@code https} connection, a redirect from {@code http} included, uses {@code TLS_Context} only: the trust
 * store {@code trust-store} and the key store {@code keystore.jks}, never the JDK {@code cacerts}, and the server
 * certificate must match the host name. The stores are loaded on the first {@code https} connection, so the
 * application starts without the store passwords and plain-HTTP stubs never touch the stores; stores that cannot be
 * loaded fail that exchange, before any request is written, with the {@link IllegalStateException} of
 * {@link BoxProperties#sslContext()}.
 */
@Component
public class BoxApiClient implements AutoCloseable {

    /** Connect timeout, the Mule default response timeout: it bounds the TCP connect and the TLS handshake each. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Read timeout, the Mule default response timeout: no wait for response bytes, head or body, lasts longer, and
     * the response, headers and body together, must be complete within it of sending the request.
     */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** The form media type exactly as written, with no {@code charset} parameter. */
    static final MediaType FORM_CONTENT_TYPE = MediaType.parseMediaType("application/x-www-form-urlencoded");

    /** {@code path="/search"} plus {@code http:query-param query=mule}, relative to the API base URL. */
    static final String SEARCH_SUFFIX = "/search?query=mule";

    private static final Logger LOGGER = LoggerFactory.getLogger(BoxApiClient.class);

    /**
     * The most redirects one exchange follows, as {@code java.net.http.HttpClient} follows them under its default
     * {@code jdk.httpclient.redirects.retrylimit} of 5: the redirect that would be the fifth is returned as
     * received.
     */
    static final int MAX_REDIRECTS_FOLLOWED = 4;

    /** The headers {@code java.net.http.HttpClient} drops from a redirect to another origin. */
    private static final List<String> CROSS_ORIGIN_REDIRECT_DROPPED_HEADERS =
            List.of("Authorization", "Cookie", "Origin", "Referer", "Host");

    /** Exchange attribute set once a redirect hop has left the original request's origin. */
    private static final String LEFT_ORIGIN_ATTRIBUTE = BoxApiClient.class.getName() + ".leftOrigin";

    private final BoxProperties properties;
    private final CloseableHttpClient httpClient;
    private final RestClient restClient;

    /**
     * Builds the connection pool and the client every exchange shares, without loading the {@code TLS_Context}
     * stores, which the first {@code https} connection does.
     *
     * @param properties the Box OAuth and TLS configuration
     */
    public BoxApiClient(BoxProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.httpClient = buildHttpClient(properties);
        // The static builder, not Boot's RestClient.Builder bean, so no customizer or default header applies. No
        // factory timeout is set, so the client's own request configuration governs every exchange.
        this.restClient = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
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
        return execute(restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private RawResponse postForm(byte[] form) {
        URI uri = configuredUrl(properties.getTokenUrl(), "box.token-url", true);
        LOGGER.debug("POST {}", forLog(uri));
        // A byte[] body goes through ByteArrayHttpMessageConverter, which keeps FORM_CONTENT_TYPE as set;
        // Spring's form converter would append ";charset=UTF-8" and leave field order to the map.
        return execute(restClient.post()
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
        // Taken before the request is sent, so the body is complete within READ_TIMEOUT of the start of the
        // exchange, which boundUntilResponseHead already gives the response head.
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
     * <p>The socket timeout bounds each wait for body bytes but not the body as a whole, so a server that keeps
     * sending its body slowly would hold this read past the read timeout. When the deadline passes first, the timer
     * thread aborts the connection the body arrives on, which ends the read; closing the stream instead would read
     * the rest of the body to keep the connection, and block as the read does. The bytes read up to then may be
     * incomplete, so the read fails with an {@link HttpTimeoutException} whether the aborted connection ended the
     * read with an error or not. A socket timeout during the read fails it the same way: that wait began after the
     * request was sent and lasted the whole read timeout, so the deadline had passed too.
     *
     * @return every body byte, an empty array when the response has no body
     * @throws HttpTimeoutException when the body is not complete by the deadline
     * @throws IOException          on any other I/O failure while reading
     */
    private static byte[] readBody(InputStream body, long deadline) throws IOException {
        long remainingNanos = Math.max(0L, deadline - System.nanoTime());
        CompletableFuture<Void> expiry = new CompletableFuture<Void>()
                .completeOnTimeout(null, remainingNanos, TimeUnit.NANOSECONDS);
        expiry.thenRun(() -> abandon(body));
        byte[] bytes;
        try {
            bytes = StreamUtils.copyToByteArray(body);
        } catch (IOException ex) {
            // cancel succeeds only while the timer has not fired; once it has, the failure comes from the
            // connection the timer aborted.
            if (expiry.cancel(false) && !(ex instanceof SocketTimeoutException)) {
                throw ex;
            }
            throw timedOut(ex);
        } finally {
            expiry.cancel(false);
        }
        if (expiry.isCancelled()) {
            return bytes;
        }
        // The timer aborted the connection before the read ended, so the bytes may be truncated.
        throw timedOut(null);
    }

    /**
     * Ends a body read that has outlived the deadline. httpclient5 hands a body that arrives on a connection to the
     * caller as an {@link EofSensorInputStream}, whose {@link EofSensorInputStream#abort()} discards the connection
     * without reading on; any other body is not backed by a connection and is closed.
     */
    private static void abandon(InputStream body) {
        try {
            if (body instanceof EofSensorInputStream connectionBody) {
                connectionBody.abort();
            } else {
                body.close();
            }
        } catch (IOException ignored) {
            // The stream is being abandoned; the reading thread reports the timeout.
        }
    }

    /**
     * Gives the exchange up to its response head, the connect, TLS handshake, request write and every redirect hop
     * included, {@link #READ_TIMEOUT} in total, which the socket timeout gives only to each single wait. When that
     * time passes first, the timer cancels the request, which discards its connection and so ends whichever wait is
     * under way, and the exchange fails with an {@link HttpTimeoutException}. A socket timeout fails it the same way:
     * that wait began after the exchange did and lasted the whole read timeout. {@link #readBody} bounds the body.
     */
    private static ClassicHttpResponse boundUntilResponseHead(ClassicHttpRequest request, ExecChain.Scope scope,
                                                              ExecChain chain) throws IOException, HttpException {
        CompletableFuture<Void> expiry = new CompletableFuture<Void>()
                .completeOnTimeout(null, READ_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        expiry.thenRun(() -> cancel(scope.originalRequest));
        ClassicHttpResponse response;
        try {
            response = chain.proceed(request, scope);
        } catch (IOException | HttpException | RuntimeException ex) {
            // cancel succeeds only while the timer has not fired; once it has, the failure comes from the
            // cancelled request.
            if (expiry.cancel(false) && !(ex instanceof SocketTimeoutException)) {
                throw ex;
            }
            throw timedOut(ex);
        } finally {
            expiry.cancel(false);
        }
        if (expiry.isCancelled()) {
            return response;
        }
        // The timer fired as the head arrived, and the connection it arrived on is already discarded.
        response.close();
        throw timedOut(null);
    }

    /**
     * Cancels a request httpclient5 is executing. The request Spring's factory builds is cancellable: cancelling it
     * discards the connection it holds and makes every later step of its execution fail.
     */
    private static void cancel(ClassicHttpRequest request) {
        if (request instanceof Cancellable cancellable) {
            cancellable.cancel();
        }
    }

    private static HttpTimeoutException timedOut(Throwable cause) {
        HttpTimeoutException timedOut =
                new HttpTimeoutException("Response not received within " + READ_TIMEOUT.toMillis() + " ms");
        if (cause != null) {
            timedOut.initCause(cause);
        }
        return timedOut;
    }

    /** Closes the connection pool and its connections; Spring calls it when the application context shuts down. */
    @Override
    public void close() {
        httpClient.close(CloseMode.GRACEFUL);
    }

    /**
     * Builds the transport, configured so that no request is ever sent twice:
     * <ul>
     *   <li>automatic retries are off, so a request whose connection is refused, reset or closed before or while
     *       the response arrives fails instead of being sent again;</li>
     *   <li>a host name resolves to its first address only, as the JDK client connected, so a refused or timed-out
     *       connect, or a failed TLS handshake, is not repeated against the host's other addresses;</li>
     *   <li>authentication challenges are not answered, so a {@code 401} is returned as received;</li>
     *   <li>a pooled connection is checked before every reuse, so one the server closed while it sat idle is
     *       replaced before the request is written rather than failing it, which the JDK client hid by re-sending
     *       the request.</li>
     * </ul>
     *
     * <p>There is no cookie store, auth cache or connection state, so nothing carries over from one exchange to the
     * next, and a connection that presented the {@code TLS_Context} client key is reused like any other. Content
     * compression is off, so no {@code Accept-Encoding} is sent and the body is returned as Box sends it, as the
     * JDK client did. Classic httpclient5 speaks HTTP/1.1 only, as the Mule 3.8 requester does, and sends no h2c
     * upgrade. The pool has no size limit, as the JDK client had none, so no exchange waits for another's
     * connection. {@link #CONNECT_TIMEOUT} bounds the TCP connect, the TLS handshake and the connection lease;
     * {@link #READ_TIMEOUT} bounds every wait for response bytes, and {@link #boundUntilResponseHead} the whole
     * exchange up to the response head.
     */
    private static CloseableHttpClient buildHttpClient(BoxProperties properties) {
        Timeout connectTimeout = Timeout.of(CONNECT_TIMEOUT);
        Timeout readTimeout = Timeout.of(READ_TIMEOUT);
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setSSLSocketFactory(new SSLConnectionSocketFactory(
                                new TlsContextSocketFactory(properties), HttpsSupport.getDefaultHostnameVerifier()))
                        .setDnsResolver(new FirstAddressDnsResolver(SystemDefaultDnsResolver.INSTANCE))
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(connectTimeout)
                                .setSocketTimeout(readTimeout)
                                .setValidateAfterInactivity(TimeValue.ZERO_MILLISECONDS)
                                .build())
                        .setDefaultTlsConfig(TlsConfig.custom().setHandshakeTimeout(connectTimeout).build())
                        .setMaxConnTotal(Integer.MAX_VALUE)
                        .setMaxConnPerRoute(Integer.MAX_VALUE)
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(readTimeout)
                        .setConnectionRequestTimeout(connectTimeout)
                        .setRedirectsEnabled(true)
                        // The redirect strategy's limit ends a redirect loop, returning its last redirect as the
                        // JDK client did, where httpclient5's loop check would throw.
                        .setCircularRedirectsAllowed(true)
                        .setAuthenticationEnabled(false)
                        .build())
                .disableAutomaticRetries()
                .disableContentCompression()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableConnectionState()
                .setRedirectStrategy(new NoDowngradeRedirectStrategy())
                .addExecInterceptorFirst("response-head-deadline", BoxApiClient::boundUntilResponseHead)
                .addExecInterceptorAfter(ChainElement.REDIRECT.name(), "cross-origin-redirect-headers",
                        BoxApiClient::dropCredentialsAcrossOrigins)
                .build();
    }

    /**
     * Removes {@link #CROSS_ORIGIN_REDIRECT_DROPPED_HEADERS} from every redirect hop, starting with the first whose
     * origin (scheme, host and port, RFC 6454) differs from the original request's, as
     * {@code java.net.http.HttpClient} does and httpclient5 does not. The first hop is the original request itself
     * and passes unchanged.
     */
    private static ClassicHttpResponse dropCredentialsAcrossOrigins(ClassicHttpRequest request, ExecChain.Scope scope,
                                                                    ExecChain chain) throws IOException, HttpException {
        HttpClientContext context = scope.clientContext;
        if (context.getAttribute(LEFT_ORIGIN_ATTRIBUTE) != null || !sameOrigin(request, scope.originalRequest)) {
            context.setAttribute(LEFT_ORIGIN_ATTRIBUTE, Boolean.TRUE);
            CROSS_ORIGIN_REDIRECT_DROPPED_HEADERS.forEach(request::removeHeaders);
        }
        return chain.proceed(request, scope);
    }

    private static boolean sameOrigin(ClassicHttpRequest a, ClassicHttpRequest b) {
        URIAuthority authorityA = a.getAuthority();
        URIAuthority authorityB = b.getAuthority();
        String schemeA = a.getScheme();
        String schemeB = b.getScheme();
        if (authorityA == null || authorityB == null || schemeA == null || schemeB == null) {
            return false;
        }
        return schemeA.equalsIgnoreCase(schemeB)
                && authorityA.getHostName().equalsIgnoreCase(authorityB.getHostName())
                && effectivePort(schemeA, authorityA.getPort()) == effectivePort(schemeB, authorityB.getPort());
    }

    private static int effectivePort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
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

    /**
     * httpclient5's redirect rules with the two limits of {@code java.net.http.HttpClient.Redirect.NORMAL}: a
     * redirect from an {@code https} URL to an {@code http} one is not followed, and neither is one that arrives after
     * {@link #MAX_REDIRECTS_FOLLOWED} have been. Such a response, {@code Location} included, is returned as received,
     * and nothing is sent to the URL it names.
     */
    private static final class NoDowngradeRedirectStrategy extends DefaultRedirectStrategy {

        @Override
        public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context)
                throws ProtocolException {
            if (!super.isRedirected(request, response, context)) {
                return false;
            }
            // httpclient5 records a location once it decides to follow it, so this counts the hops already followed.
            RedirectLocations followed = HttpClientContext.adapt(context).getRedirectLocations();
            if (followed != null && followed.size() >= MAX_REDIRECTS_FOLLOWED) {
                return false;
            }
            // A relative Location keeps the scheme of the URL it answers, so only an absolute http one downgrades.
            String targetScheme = createLocationURI(response.getFirstHeader(HttpHeaders.LOCATION).getValue())
                    .getScheme();
            return !("https".equalsIgnoreCase(answeredScheme(request, context))
                    && "http".equalsIgnoreCase(targetScheme));
        }

        /**
         * The scheme of the URL that answered with the redirect. httpclient5 passes the original request on every
         * hop, so that URL is the last redirect location followed, or the original request's before any has been.
         */
        private static String answeredScheme(HttpRequest original, HttpContext context) {
            RedirectLocations followed = HttpClientContext.adapt(context).getRedirectLocations();
            return followed == null || followed.size() == 0
                    ? original.getScheme()
                    : followed.get(followed.size() - 1).getScheme();
        }
    }

    /**
     * The first address {@code delegate} resolves a host to, alone; with the system resolver that is the one address
     * {@code java.net.http.HttpClient} connects to. httpclient5 would otherwise move on to each further address of
     * the host after a refused, reset or timed-out connect or a failed TLS handshake: a second connection attempt
     * after a connectivity failure, which could also stretch the connect past {@link #CONNECT_TIMEOUT}.
     * Package-private for the tests.
     */
    static final class FirstAddressDnsResolver implements DnsResolver {

        private final DnsResolver delegate;

        FirstAddressDnsResolver(DnsResolver delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
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

    /**
     * The socket factory of {@code TLS_Context}, taken from {@link BoxProperties#sslContext()} each time a TLS
     * connection is opened rather than when the client is built, so that the stores are loaded by the first
     * {@code https} connection and never by a plain-HTTP exchange. {@link BoxProperties} builds the context once and
     * keeps it, and a failure to load the stores propagates as its {@link IllegalStateException}.
     */
    private static final class TlsContextSocketFactory extends SSLSocketFactory {

        private final BoxProperties properties;

        TlsContextSocketFactory(BoxProperties properties) {
            this.properties = properties;
        }

        private SSLSocketFactory delegate() {
            return properties.sslContext().getSocketFactory();
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate().getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate().getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return delegate().createSocket(socket, host, port, autoClose);
        }

        @Override
        public Socket createSocket(Socket socket, InputStream consumed, boolean autoClose) throws IOException {
            return delegate().createSocket(socket, consumed, autoClose);
        }

        @Override
        public Socket createSocket() throws IOException {
            return delegate().createSocket();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return delegate().createSocket(host, port);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return delegate().createSocket(host, port, localHost, localPort);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return delegate().createSocket(host, port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return delegate().createSocket(address, port, localAddress, localPort);
        }
    }
}
