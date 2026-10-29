/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.client;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.URIAuthority;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.config.TlsProperties;

/**
 * Hand-written OAuth 2.0 client-credentials client for Box, the outbound side of the {@code /oauth} flow
 * {@code httpclientcredentialsFlow} in
 * {@code oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml}.
 *
 * <ul>
 *   <li>Line 10, {@code oauth2:token-manager-config Token_Manager_Config}: the token store, here the token
 *       cached by this singleton.</li>
 *   <li>Lines 12-13, {@code HTTP_Request_Configuration}: host {@code app.box.com}, port 443, HTTPS, basePath
 *       {@code /api/oauth2/authorize}, {@code tlsContext-ref="TLS_Context"}; the keys {@code box.api.base-url}
 *       and {@code box.api.base-path}.</li>
 *   <li>Line 14, {@code oauth2:client-credentials-grant-type}: {@code ${oauth.client.id}} and
 *       {@code ${oauth.client.secret}}, sent as an HTTP Basic header, because Mule 3.8 defaults
 *       {@code encodeClientCredentialsInBody} to false.</li>
 *   <li>Lines 16 and 18, {@code oauth2:token-request}: token URL {@code https://app.box.com/api/oauth2/token}
 *       (key {@code box.oauth.token-url}) and the custom parameter extractor {@code token_type =
 *       #[json:token_type]}, kept with the token as Mule's token store kept it.</li>
 *   <li>Lines 25-28, {@code tls:context TLS_Context}: supplied by {@link TlsProperties}, applied only to the
 *       Box resource request over HTTPS. The token request declares no TLS context and uses the JVM-default
 *       trust.</li>
 *   <li>Line 34, {@code http:request} GET {@code path="/"}: the request {@link #authorize()} sends to
 *       {@code base URL + basePath + "/"}, with the cached access token as a Bearer header. A 401 or 403, Mule's
 *       default {@code refreshTokenWhen}, re-runs the client-credentials exchange and retries the GET once. A
 *       final status outside the requester's success range 0..399 fails with Mule 3.8's response-validator
 *       message {@code Response code <code> mapped as failure.}.</li>
 * </ul>
 *
 * <p>Deviation from the original: Mule requested the token when the application was deployed. This client
 * requests it lazily, on the first {@link #authorize()}, so the application starts without contacting Box and
 * with blank {@code oauth.client.*} and key-store passwords. For the same reason the HTTPS resource client, and
 * with it the TLS context, is built on the first {@link #authorize()} and before the token request, so a broken
 * TLS configuration fails the call before any client secret is sent. A blank {@code oauth.client.id} or
 * {@code oauth.client.secret} fails each {@link #authorize()}, naming the key, before either step.
 *
 * <p>The transport of both requests is Apache HttpClient 5 under {@link RestClient}, speaking HTTP/1.1 as Mule's
 * requester does, and it sends each request exactly once: automatic retries are disabled, so neither the token
 * request nor the resource request is re-sent after a refused connect, a connection reset, a connection closed
 * before the response or a timeout, and a host name is connected to at the first address it resolves to only. The
 * one repeat is the token refresh and GET retry after a 401 or 403 described above. The connect timeout is 10 s,
 * the Mule requester's default response timeout, and one 10 s deadline, started when each request's exchange
 * starts, bounds its whole response, headers and body: when it passes, the request is aborted, its connection
 * discarded, and the call fails with an {@link HttpTimeoutException}
 * {@code Response not received within <timeout> ms}. A TLS failure of either request, such as a certificate the
 * trust does not hold or one issued for another host, fails the call with an {@link SSLException}
 * {@code TLS handshake failed}, whose cause is the JDK's exception: the JDK's text names internal classes and never
 * reaches a message.
 *
 * <p>Token acquisition is single-flight: the calls that need a token while one is being requested, because none is
 * cached or because Box rejected the cached one, all wait for that one request instead of sending their own, and
 * all receive its token or its failure when it ends. A stalled token response therefore holds every waiting call
 * for one deadline at most, never one deadline for each call queued before it, and a failed request is not retried;
 * the next call after it sends a new one.
 *
 * <p>Redirects are followed, as {@code http:request} follows them by default, up to four in a row; the fifth 3xx
 * in a row is returned as received, as is a redirect from {@code https} to {@code http}, which is never followed.
 * A 307 or 308 re-sends the method and body; a 301, 302 or 303 answering the token POST continues as a GET without
 * the body, and every later redirect of that exchange, a 307 or 308 included, is followed as a GET without the body
 * too, as {@code java.net.http} and the Mule requester follow them. Every hop carries the request's headers, except
 * that {@code Authorization}, {@code Cookie}, {@code Origin}, {@code Referer} and {@code Host} are dropped once a
 * redirect leaves the request's origin, so neither the client credentials nor the access token reach another
 * origin. A 301, 302, 303, 307 or 308 without {@code Location} fails the request with {@code Invalid redirection}.
 * No cookie is kept, no {@code Accept-Encoding} is sent and no response is decompressed, and no authentication
 * challenge is answered.
 *
 * <p>Connections are pooled without limit, as the Mule requester's default {@code maxConnections} of -1 allowed,
 * verify the server's host name, and are released by {@link #close()} when the application context shuts down.
 * The client is thread-safe.
 */
@Component
public class BoxOAuthClient implements AutoCloseable {

    /** The Mule HTTP requester's default response timeout. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** First status code outside the Mule 3.8 requester's default success status range {@code 0..399}. */
    private static final int FIRST_FAILURE_STATUS = 400;

    /** Statuses of Mule's default {@code refreshTokenWhen}: the resource server rejected the access token. */
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;

    private static final String HTTPS_SCHEME = "https";

    /**
     * The message of every TLS failure. The JDK's own text names internal classes and certificate details, and a
     * transport failure's message reaches the caller's 500 body.
     */
    private static final String TLS_FAILURE_MESSAGE = "TLS handshake failed";

    /** Most redirects followed in a row; the next 3xx is returned as received. */
    private static final int MAX_REDIRECTS_FOLLOWED = 4;

    /** Headers a redirect never carries to another origin; the client sets {@code Host} for the new target. */
    private static final List<String> CROSS_ORIGIN_REDIRECT_DROPPED_HEADERS =
            List.of("Authorization", "Cookie", "Origin", "Referer", "Host");

    /** Exchange attribute set once a redirect has left the original request's origin. */
    private static final String LEFT_ORIGIN_ATTRIBUTE = BoxOAuthClient.class.getName() + ".leftOrigin";

    /** Exchange attribute set once a redirect has turned the exchange into a GET without a body. */
    private static final String CONTINUES_AS_GET_ATTRIBUTE = BoxOAuthClient.class.getName() + ".continuesAsGet";

    /**
     * Resolves a host name to its first address only, so a refused connect is one connection attempt; httpclient5's
     * default resolver returns every address, and its connection operator tries each in turn.
     */
    private static final DnsResolver FIRST_ADDRESS_RESOLVER = new SystemDefaultDnsResolver() {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return new InetAddress[] {InetAddress.getByName(host)};
        }
    };

    private static final String CLIENT_ID_KEY = "oauth.client.id";
    private static final String CLIENT_SECRET_KEY = "oauth.client.secret";

    /** The whole client-credentials token request body: no scope, and the client credentials travel in Basic. */
    private static final byte[] CLIENT_CREDENTIALS_FORM =
            "grant_type=client_credentials".getBytes(StandardCharsets.US_ASCII);

    /*
     * Jackson can quote the input it fails on, and a token response is where the secrets are, so the parser never
     * includes the source in its locations and parse failures are reported without the Jackson exception.
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();

    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String baseUrl;
    private final String basePath;
    private final TlsProperties tls;
    private final Duration timeout;

    /** One daemon thread, started by the first request: it aborts an exchange whose deadline passes. */
    private final ScheduledThreadPoolExecutor deadlines;

    /** Transport of the token endpoint: JVM-default TLS, as the token request declares no TLS context. */
    private final CloseableHttpClient tokenHttpClient;

    /** Plain client of the token endpoint, over {@link #tokenHttpClient}. */
    private final RestClient tokenClient;

    private final Object resourceClientLock = new Object();

    /** Client of the Box resource request; built on the first {@link #authorize()}. */
    private volatile RestClient resourceClient;

    /** Transport of the HTTPS resource client, once built; guarded by {@link #resourceClientLock}. */
    private CloseableHttpClient resourceHttpClient;

    /** Set by {@link #close()}; guarded by {@link #resourceClientLock}. */
    private boolean closed;

    /**
     * The cached token, replacing {@code Token_Manager_Config}; read without a lock, and written only under
     * {@link #tokenLock} by the call whose token request answered it.
     */
    private volatile Token token;

    /** Guards {@link #tokenFlight} and every write of {@link #token}; never held while a request is in progress. */
    private final Object tokenLock = new Object();

    /**
     * The token request in progress, or {@code null}: the one request every call that needs a token awaits, which
     * ends with its token or its failure; guarded by {@link #tokenLock}.
     */
    private CompletableFuture<Token> tokenFlight;

    /**
     * Creates the client the application uses, with the 10 s Mule default timeouts.
     *
     * @param tokenUrl     {@code box.oauth.token-url}: the client-credentials token endpoint
     * @param clientId     {@code oauth.client.id}: the Box application's client id
     * @param clientSecret {@code oauth.client.secret}: the Box application's client secret
     * @param baseUrl      {@code box.api.base-url}: scheme, host and port of the Box resource request
     * @param basePath     {@code box.api.base-path}: the request configuration's basePath
     * @param tls          key store and trust store of the HTTPS resource request
     * @throws IllegalStateException when {@code box.oauth.token-url} or {@code box.api.base-url} is not an absolute
     *         http or https URL with a host or embeds user information, with a message naming the key and never the
     *         value, so the application does not start
     */
    @Autowired
    public BoxOAuthClient(@Value("${box.oauth.token-url:https://app.box.com/api/oauth2/token}") String tokenUrl,
                          @Value("${oauth.client.id:}") String clientId,
                          @Value("${oauth.client.secret:}") String clientSecret,
                          @Value("${box.api.base-url:https://app.box.com:443}") String baseUrl,
                          @Value("${box.api.base-path:/api/oauth2/authorize}") String basePath,
                          TlsProperties tls) {
        this(tokenUrl, clientId, clientSecret, baseUrl, basePath, tls, DEFAULT_TIMEOUT);
    }

    /**
     * Creates a client with the given connect and response timeout. Construction only checks the two URLs, records
     * the values and builds the plain token client; it opens no connection and never reads {@code tls}.
     *
     * @param tokenUrl     the client-credentials token endpoint
     * @param clientId     the client id sent in the Basic header of the token request; accepted blank here and
     *                     rejected by {@link #authorize()}
     * @param clientSecret the client secret sent in the Basic header of the token request; accepted blank here and
     *                     rejected by {@link #authorize()}
     * @param baseUrl      scheme, host and port of the resource request, without a trailing slash
     * @param basePath     path prefix of the resource request; {@code "/"} is appended to it
     * @param tls          TLS stores of the resource request; may be {@code null} unless {@code baseUrl} is https
     * @param timeout      connect timeout of both requests, and the time within which each one's whole response,
     *                     headers and body, must arrive
     * @throws IllegalStateException when {@code tokenUrl} or {@code baseUrl} is not an absolute http or https URL
     *         with a host or embeds user information, checked before anything is built and with a message naming
     *         {@code box.oauth.token-url} or {@code box.api.base-url} and never the value; or when the JVM has no
     *         default TLS context
     */
    public BoxOAuthClient(String tokenUrl, String clientId, String clientSecret, String baseUrl, String basePath,
                          TlsProperties tls, Duration timeout) {
        this.tokenUrl = validatedUrl(tokenUrl, "box.oauth.token-url");
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.baseUrl = validatedUrl(baseUrl, "box.api.base-url");
        this.basePath = basePath;
        this.tls = tls;
        this.timeout = timeout;
        this.deadlines = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "box-oauth-response-deadline");
            thread.setDaemon(true);
            return thread;
        });
        this.deadlines.setRemoveOnCancelPolicy(true);
        this.tokenHttpClient = httpClient(defaultSslContext());
        this.tokenClient = restClient(tokenHttpClient);
    }

    /**
     * {@code url} unchanged, once it has proved to be an absolute {@code http} or {@code https} URL with a host and
     * without user information. The original's {@code host}, {@code port} and {@code protocol} attributes and its
     * {@code tokenUrl} held no credentials; a configured URL could, and every request to it would then fail with an
     * error that names it.
     *
     * @param url the configured value
     * @param key its configuration key, which a failure names in place of the value
     * @return {@code url}, unchanged
     * @throws IllegalStateException when it is not, with a message that names {@code key} and never repeats the value
     */
    private static String validatedUrl(String url, String key) {
        String requirement = key + " must be an absolute http or https URL with a host";
        URI uri;
        try {
            uri = new URI(url == null ? "" : url);
        } catch (URISyntaxException e) {
            // Not kept as the cause: its message repeats the whole URL. The reason and index name the fault alone.
            throw new IllegalStateException(requirement + ": " + e.getReason()
                    + (e.getIndex() >= 0 ? " at index " + e.getIndex() : ""));
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || HTTPS_SCHEME.equalsIgnoreCase(scheme))) {
            throw new IllegalStateException(requirement);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalStateException(key + " must not embed credentials");
        }
        return url;
    }

    /**
     * Sends the flow's GET to {@code base URL + basePath + "/"} with the client-credentials access token,
     * requesting the token first when none is cached.
     *
     * @return the upstream status (below 400), a copy of its headers, and its body, which is {@code null} when
     *         the upstream sent none
     * @throws RestClientResponseException when the final status is 400 or above, with the message
     *         {@code Response code <code> mapped as failure.} and the upstream status, headers and body; a 401 or
     *         403 is final only after one token refresh and retry
     * @throws IllegalStateException with the message {@code <key> is not configured} when {@code oauth.client.id}
     *         or {@code oauth.client.secret} is {@code null} or blank, checked first so that no TLS context is
     *         built and no request is sent; when the token request fails or its response carries no access token;
     *         when the TLS context of an https base URL cannot be built; or when an https base URL has no
     *         {@link TlsProperties}, or once the client is closed
     * @throws org.springframework.web.client.ResourceAccessException on an I/O failure of either request,
     *         including a refused or timed-out connect, a response, headers or body, not complete within the
     *         timeout (its cause is then a {@link HttpTimeoutException}), and a TLS failure (its message then ends
     *         with {@code TLS handshake failed}, and its cause is an {@link SSLException} with that message, caused
     *         by the JDK's exception); nothing is retried after one
     */
    public ResponseEntity<byte[]> authorize() {
        // Checked here rather than at construction, so the application still starts with the blank placeholders.
        requireConfigured(clientId, CLIENT_ID_KEY);
        requireConfigured(clientSecret, CLIENT_SECRET_KEY);
        RestClient client = resourceClient();
        // URI.create, not a URI template: the configured path reaches the wire as written.
        URI uri = URI.create(baseUrl + basePath + "/");
        Token bearer = currentToken();
        UpstreamResponse response = get(client, uri, bearer);
        int code = response.status().value();
        if (code == UNAUTHORIZED || code == FORBIDDEN) {
            // The client-credentials grant re-requests the token; it never uses refresh_token. The retry's
            // response is final whatever its status.
            response = get(client, uri, replaceToken(bearer));
        }
        return toResult(response);
    }

    /** Fails, naming {@code key}, when its configured {@code value} is {@code null} or blank. */
    private static void requireConfigured(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is not configured");
        }
    }

    private UpstreamResponse get(RestClient client, URI uri, Token bearer) {
        // exchange bypasses RestClient's default status handlers, so every status reaches the checks above,
        // and adds no converter-derived Accept header. No inbound header is copied: line 34 builds none. The
        // transport's deadline bounds the body read below together with the headers.
        return client.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer.accessToken())
                .exchange((clientRequest, clientResponse) -> {
                    byte[] body = clientResponse.getBody().readAllBytes();
                    HttpHeaders headers = new HttpHeaders();
                    headers.addAll(clientResponse.getHeaders());
                    String statusText = clientResponse.getStatusText();
                    return new UpstreamResponse(clientResponse.getStatusCode(),
                            statusText == null ? "" : statusText, headers, body);
                });
    }

    private static ResponseEntity<byte[]> toResult(UpstreamResponse response) {
        HttpStatusCode status = response.status();
        if (status.value() >= FIRST_FAILURE_STATUS) {
            throw new RestClientResponseException("Response code " + status.value() + " mapped as failure.",
                    status, response.statusText(), response.headers(), response.body(),
                    charsetOf(response.headers()));
        }
        byte[] body = response.body();
        return ResponseEntity.status(status).headers(response.headers()).body(body.length == 0 ? null : body);
    }

    /**
     * Returns the cached token, without taking a lock, or requests it on first use; concurrent first calls share
     * that one request. Neither expiry nor {@code expires_in} triggers a refresh; only a rejected resource request
     * does.
     */
    private Token currentToken() {
        Token cached = token;
        return cached != null ? cached : tokenOtherThan(null);
    }

    /**
     * Replaces {@code rejected} with a newly requested token. When another thread has already replaced it, that
     * thread's token is used instead of a second exchange, and the calls rejected with the same token while its
     * replacement is requested share that one exchange. A failed exchange leaves {@code rejected} cached.
     */
    private Token replaceToken(Token rejected) {
        return tokenOtherThan(rejected);
    }

    /**
     * Returns the cached token unless there is none or it is {@code unusable}; otherwise joins the token request in
     * flight or, when none is, starts one and runs it on the calling thread. {@link #tokenLock} is held only to
     * read and set the cache and the flight, never during the request, so every call that joined receives the
     * flight's token or its failure as soon as the one request ends. A failure is not retried, and a call arriving
     * once the flight has ended starts a new one.
     *
     * @param unusable the token the caller cannot use, or {@code null} when any cached token will do
     */
    private Token tokenOtherThan(Token unusable) {
        CompletableFuture<Token> flight;
        boolean starts = false;
        synchronized (tokenLock) {
            Token cached = token;
            if (cached != null && cached != unusable) {
                return cached;
            }
            flight = tokenFlight;
            if (flight == null) {
                flight = new CompletableFuture<>();
                tokenFlight = flight;
                starts = true;
            }
        }
        if (starts) {
            runFlight(flight);
        }
        return awaitFlight(flight);
    }

    /**
     * Sends the token request of {@code flight} and ends the flight with its outcome. The token is cached and the
     * flight cleared before the waiting calls are released, so neither they nor a later call find the ended flight.
     * Whatever ends the request ends the flight, so no waiting call is left without an outcome.
     */
    private void runFlight(CompletableFuture<Token> flight) {
        Token fetched = null;
        Throwable failure = null;
        try {
            fetched = requestToken();
        } catch (RuntimeException | Error e) {
            failure = e;
        } catch (Throwable e) {
            // requestToken() declares no checked exception; one thrown regardless is wrapped once, here, so every
            // waiting call still receives the same instance.
            failure = new IllegalStateException(e.getMessage(), e);
        }
        synchronized (tokenLock) {
            if (failure == null) {
                token = fetched;
            }
            tokenFlight = null;
        }
        if (failure == null) {
            flight.complete(fetched);
        } else {
            // Wrapped so that join() throws this CompletionException itself, whose cause is exactly the failure.
            flight.completeExceptionally(new CompletionException(failure));
        }
    }

    /**
     * Waits for {@code flight} to end, then returns its token or throws its failure, the same
     * {@link RuntimeException} or {@link Error} instance for every call that waited. The wait is not interruptible
     * and lasts as long as the one request, which the transport's deadline bounds.
     */
    private static Token awaitFlight(CompletableFuture<Token> flight) {
        try {
            return flight.join();
        } catch (CompletionException wrapped) {
            Throwable failure = wrapped.getCause();
            if (failure instanceof Error error) {
                throw error;
            }
            // runFlight ends a flight only with a RuntimeException or an Error.
            throw (RuntimeException) failure;
        }
    }

    private Token requestToken() {
        String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        // The transport's one deadline for headers and body also bounds how long a stalled token response keeps the
        // calls sharing this request waiting, so they are all released by one timed failure. The form is set as raw
        // bytes on the transport's request with an explicit Content-Type and length, so no converter adds a charset
        // parameter, the request is not chunked, and a 307 or 308 re-sends it: RestClient's body(...) would hand
        // httpclient5 a body it declares not repeatable, and httpclient5 follows no redirect of such a request.
        StreamingHttpOutputMessage.Body form = repeatableBody(CLIENT_CREDENTIALS_FORM);
        return tokenClient.post()
                .uri(URI.create(tokenUrl))
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .contentLength(CLIENT_CREDENTIALS_FORM.length)
                .httpRequest(clientRequest -> setBody(clientRequest, form))
                .exchange((clientRequest, clientResponse) -> {
                    HttpStatusCode status = clientResponse.getStatusCode();
                    byte[] body = clientResponse.getBody().readAllBytes();
                    if (!status.is2xxSuccessful()) {
                        throw new IllegalStateException("Token request to " + tokenUrl
                                + " failed with HTTP status " + status.value());
                    }
                    return parseToken(body, status.value());
                });
    }

    private Token parseToken(byte[] body, int status) {
        JsonNode response;
        try {
            response = JSON.readTree(body);
        } catch (IOException e) {
            // The cause is dropped on purpose: Jackson's message can quote the body, which may hold the access
            // or refresh token, and its parser keeps a reference to the input buffer.
            throw new IllegalStateException(noAccessToken(status));
        }
        String accessToken = response == null ? null : textField(response, "access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException(noAccessToken(status));
        }
        return new Token(accessToken, textField(response, "expires_in"), textField(response, "refresh_token"),
                textField(response, "token_type"));
    }

    private String noAccessToken(int status) {
        return "Token response from " + tokenUrl + " (HTTP status " + status + ") contains no access_token";
    }

    /** Returns the scalar value of {@code name} as text, or {@code null} when it is absent, null or not a scalar. */
    private static String textField(JsonNode response, String name) {
        JsonNode value = response.get(name);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    /**
     * Returns the resource client, building it on first use: over TLS from {@link TlsProperties} for an https
     * base URL, otherwise the plain token client.
     */
    private RestClient resourceClient() {
        RestClient client = resourceClient;
        if (client == null) {
            synchronized (resourceClientLock) {
                client = resourceClient;
                if (client == null) {
                    client = buildResourceClient();
                    resourceClient = client;
                }
            }
        }
        return client;
    }

    /** Called under {@link #resourceClientLock}. */
    private RestClient buildResourceClient() {
        if (closed) {
            throw new IllegalStateException("BoxOAuthClient is closed");
        }
        if (!HTTPS_SCHEME.equalsIgnoreCase(URI.create(baseUrl).getScheme())) {
            return tokenClient;
        }
        if (tls == null) {
            // Never fall back to the JVM-default trust: the original binds TLS_Context to this request.
            throw new IllegalStateException("No TLS configuration for the https Box base URL " + baseUrl);
        }
        CloseableHttpClient httpClient = httpClient(tls.createSslContext());
        resourceHttpClient = httpClient;
        return restClient(httpClient);
    }

    /**
     * Closes every pooled connection of both transports, idle or in use, and stops the deadline thread once its
     * pending deadlines have run; an exchange still in progress fails with
     * {@link org.springframework.web.client.ResourceAccessException}. Spring calls it when the application context
     * shuts down, as for every {@link AutoCloseable} bean; no exchange may start afterwards.
     */
    @Override
    public void close() {
        synchronized (resourceClientLock) {
            closed = true;
            tokenHttpClient.close(CloseMode.GRACEFUL);
            if (resourceHttpClient != null) {
                resourceHttpClient.close(CloseMode.GRACEFUL);
            }
        }
        deadlines.shutdown();
    }

    /** The JVM's default TLS context, which the token request uses as it declares no TLS context. */
    private static SSLContext defaultSslContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The JVM's default TLS context is unavailable", e);
        }
    }

    /**
     * A client over {@code httpClient}. The static builder rather than Boot's {@code RestClient.Builder}: no
     * customizer may add headers or converters, and tests construct the class without a context. No factory
     * timeout is set, so the transport's default request configuration applies.
     */
    private static RestClient restClient(CloseableHttpClient httpClient) {
        return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient)).build();
    }

    /**
     * Builds the single-attempt transport over {@code sslContext}. Every wait has the timeout as its bound: the
     * connect, the TLS handshake, the lease of a pooled connection and every socket read, and above them the
     * exchange's deadline. Host names are verified against the server certificate, httpclient5's default.
     */
    private CloseableHttpClient httpClient(SSLContext sslContext) {
        Timeout limit = Timeout.of(timeout);
        // httpclient5's default of five connections per route would queue concurrent calls.
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                                .setSslContext(sslContext)
                                .build())
                        .setDnsResolver(FIRST_ADDRESS_RESOLVER)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(limit)
                                .setSocketTimeout(limit)
                                .build())
                        .setDefaultTlsConfig(TlsConfig.custom().setHandshakeTimeout(limit).build())
                        .setMaxConnTotal(Integer.MAX_VALUE)
                        .setMaxConnPerRoute(Integer.MAX_VALUE)
                        .build())
                // Circular redirects are followed within the redirect limit, which the redirect strategy applies.
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(limit)
                        .setConnectionRequestTimeout(limit)
                        .setRedirectsEnabled(true)
                        .setCircularRedirectsAllowed(true)
                        .setAuthenticationEnabled(false)
                        .build())
                // No request is ever re-sent; the 401/403 retry of authorize() is a new exchange. Content compression
                // would add Accept-Encoding and decompress the Box body, and a cookie store or an auth cache would
                // keep state across calls.
                .disableAutomaticRetries()
                .disableContentCompression()
                .disableCookieManagement()
                .disableAuthCaching()
                .setRedirectStrategy(new FollowingRedirectStrategy())
                // First in the chain, so the one deadline spans every redirect hop.
                .addExecInterceptorFirst("response-deadline", this::withinDeadline)
                .addExecInterceptorAfter(ChainElement.REDIRECT.name(), "redirect-header-policy",
                        BoxOAuthClient::applyRedirectHeaderPolicy)
                .addExecInterceptorAfter("redirect-header-policy", "redirect-method-policy",
                        BoxOAuthClient::applyRedirectMethodPolicy)
                .build();
    }

    /** {@code body} as a request body declared repeatable, so a 307 or 308 redirect re-sends the same bytes. */
    private static StreamingHttpOutputMessage.Body repeatableBody(byte[] body) {
        return new StreamingHttpOutputMessage.Body() {
            @Override
            public void writeTo(OutputStream out) throws IOException {
                out.write(body);
            }

            @Override
            public boolean repeatable() {
                return true;
            }
        };
    }

    /** Streams {@code body} as the request body, which the request factory then writes without buffering it. */
    private static void setBody(ClientHttpRequest request, StreamingHttpOutputMessage.Body body) {
        if (!(request instanceof StreamingHttpOutputMessage streaming)) {
            throw new IllegalStateException("The request factory must stream request bodies, got "
                    + request.getClass().getName());
        }
        streaming.setBody(body);
    }

    /**
     * Runs one exchange, its redirects included, under the deadline: the response head must arrive before it, and
     * the returned response's body is read under it too. A failure once the deadline has passed is reported as the
     * timeout, and any other TLS failure without the JDK's text ({@link #withoutTlsDetail}).
     */
    private ClassicHttpResponse withinDeadline(ClassicHttpRequest request, ExecChain.Scope scope, ExecChain chain)
            throws IOException, HttpException {
        ResponseDeadline deadline = new ResponseDeadline(scope);
        ClassicHttpResponse response;
        try {
            response = chain.proceed(request, scope);
        } catch (IOException | HttpException e) {
            if (deadline.stop()) {
                throw deadline.timedOut(e);
            }
            if (e instanceof IOException failure) {
                throw withoutTlsDetail(failure);
            }
            throw e;
        } catch (RuntimeException e) {
            deadline.stop();
            throw e;
        }
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            deadline.stop();
        } else {
            response.setEntity(new DeadlineEntity(entity, deadline));
        }
        return response;
    }

    /**
     * {@code failure} as the exchange reports it: a TLS failure, any {@link SSLException} (an untrusted or mismatched
     * certificate, a refused or broken handshake), becomes an {@link SSLException} with the fixed message
     * {@value #TLS_FAILURE_MESSAGE} and the original as its cause; any other failure is returned unchanged. The JDK's
     * message names internal classes, such as {@code sun.security.provider.certpath.SunCertPathBuilderException}, and
     * {@link RestClient} copies the message into the {@code ResourceAccessException} whose text a caller's 500 body
     * reports.
     */
    private static IOException withoutTlsDetail(IOException failure) {
        return failure instanceof SSLException ? new SSLException(TLS_FAILURE_MESSAGE, failure) : failure;
    }

    /**
     * The deadline of one exchange. When it passes before {@link #stop()}, the request is cancelled from the deadline
     * thread: httpclient5 discards its connection, which ends a blocked connect, write or read at once, and no
     * further step of the exchange starts. Closing a response stream would instead drain the rest of the body.
     */
    private final class ResponseDeadline {

        /** The {@link System#nanoTime()} at which the deadline passes. */
        private final long expiresAt;

        private final ScheduledFuture<?> abort;

        ResponseDeadline(ExecChain.Scope scope) throws IOException {
            this.expiresAt = System.nanoTime() + timeout.toNanos();
            // The request Spring created is the exchange's cancellable: cancelling it aborts the step in progress
            // and every later one, a redirect hop included.
            Runnable abortExchange = scope.originalRequest instanceof Cancellable cancellable
                    ? cancellable::cancel
                    : scope.execRuntime::discardEndpoint;
            try {
                // Scheduled after expiresAt was taken, so the abort never runs before it.
                this.abort = deadlines.schedule(abortExchange, timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException e) {
                // Only a closed client rejects a deadline; its connections are closed too.
                throw new IOException("BoxOAuthClient is closed", e);
            }
        }

        /** Stops the abort, if it has not run; returns whether the deadline has passed. Safe to call repeatedly. */
        boolean stop() {
            abort.cancel(false);
            return System.nanoTime() - expiresAt >= 0;
        }

        /**
         * {@code failure} reported as the timeout, when the deadline has passed; otherwise {@code failure}, a TLS
         * failure without the JDK's text ({@link #withoutTlsDetail}).
         */
        IOException translate(IOException failure) {
            return stop() ? timedOut(failure) : withoutTlsDetail(failure);
        }

        HttpTimeoutException timedOut(Exception failure) {
            HttpTimeoutException timedOut =
                    new HttpTimeoutException("Response not received within " + timeout.toMillis() + " ms");
            timedOut.initCause(failure);
            return timedOut;
        }
    }

    /** A response body read under its exchange's deadline, which ends at the end of the body or when it is closed. */
    private static final class DeadlineEntity extends HttpEntityWrapper {

        private final ResponseDeadline deadline;

        private InputStream content;

        DeadlineEntity(HttpEntity entity, ResponseDeadline deadline) {
            super(entity);
            this.deadline = deadline;
        }

        @Override
        public synchronized InputStream getContent() throws IOException {
            if (content == null) {
                content = new DeadlineInputStream(super.getContent(), deadline);
            }
            return content;
        }

        @Override
        public void writeTo(OutputStream outStream) throws IOException {
            try (InputStream in = getContent()) {
                in.transferTo(outStream);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                deadline.stop();
            }
        }
    }

    /** The body stream: its end stops the deadline, and a failure once the deadline has passed is the timeout. */
    private static final class DeadlineInputStream extends FilterInputStream {

        private final ResponseDeadline deadline;

        DeadlineInputStream(InputStream in, ResponseDeadline deadline) {
            super(in);
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            try {
                int b = in.read();
                if (b < 0) {
                    deadline.stop();
                }
                return b;
            } catch (IOException e) {
                throw deadline.translate(e);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                int count = in.read(b, off, len);
                if (count < 0) {
                    deadline.stop();
                }
                return count;
            } catch (IOException e) {
                throw deadline.translate(e);
            }
        }

        @Override
        public long skip(long n) throws IOException {
            try {
                return in.skip(n);
            } catch (IOException e) {
                throw deadline.translate(e);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                in.close();
            } catch (IOException e) {
                throw deadline.translate(e);
            } finally {
                deadline.stop();
            }
        }
    }

    /**
     * The header policy for redirects, which httpclient5 does not apply itself. A hop starts from every header of
     * the original request, so a request that a redirect turns into a GET keeps them where httpclient5 would send
     * none. From the first hop whose origin (scheme, host and port, RFC 6454) differs from the original request's,
     * {@link #CROSS_ORIGIN_REDIRECT_DROPPED_HEADERS} are removed on that hop and every later one. The first hop is
     * the original request itself and passes unchanged.
     */
    private static ClassicHttpResponse applyRedirectHeaderPolicy(ClassicHttpRequest request, ExecChain.Scope scope,
                                                                 ExecChain chain) throws IOException, HttpException {
        ClassicHttpRequest original = scope.originalRequest;
        request.setHeaders(original.getHeaders());
        HttpClientContext context = scope.clientContext;
        if (context.getAttribute(LEFT_ORIGIN_ATTRIBUTE) != null || !sameOrigin(request, original)) {
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
        return HTTPS_SCHEME.equalsIgnoreCase(scheme) ? 443 : 80;
    }

    /**
     * The method policy for redirects. {@code java.net.http} and the Mule requester derive each hop's method from the
     * hop it answers; httpclient5 derives it from the original request, so a hop after one it turned into a GET would
     * re-send the token POST with the form and the client credentials. Once a hop is answered by a 303, or by a 301
     * or 302 while it is a POST, the exchange continues as a GET: every later hop is sent as a GET to its own target
     * with its headers, as {@link #applyRedirectHeaderPolicy} set them, and no body. Until then every hop passes
     * unchanged, so a 307 or 308 re-sends the method and body.
     */
    private static ClassicHttpResponse applyRedirectMethodPolicy(ClassicHttpRequest request, ExecChain.Scope scope,
                                                                 ExecChain chain) throws IOException, HttpException {
        HttpClientContext context = scope.clientContext;
        ClassicHttpRequest hop = context.getAttribute(CONTINUES_AS_GET_ATTRIBUTE) != null
                ? asBodylessGet(request)
                : request;
        ClassicHttpResponse response = chain.proceed(hop, scope);
        if (continuesAsGet(hop.getMethod(), response.getCode())) {
            context.setAttribute(CONTINUES_AS_GET_ATTRIBUTE, Boolean.TRUE);
        }
        return response;
    }

    /**
     * Whether a redirect of {@code method} answered by {@code status} continues as a GET without the body. A 301 or
     * 302 is tested for POST as httpclient5 tests it, so the decision matches the hop it builds next.
     */
    private static boolean continuesAsGet(String method, int status) {
        return switch (status) {
            case 303 -> true;
            case 301, 302 -> Method.POST.isSame(method);
            default -> false;
        };
    }

    /** {@code hop} as a GET to the same target, with the same protocol version and headers and no body. */
    private static ClassicHttpRequest asBodylessGet(ClassicHttpRequest hop) {
        BasicClassicHttpRequest get =
                new BasicClassicHttpRequest(Method.GET.name(), hop.getScheme(), hop.getAuthority(), hop.getPath());
        get.setVersion(hop.getVersion());
        get.setHeaders(hop.getHeaders());
        return get;
    }

    /**
     * httpclient5's redirect rules within the limits {@code java.net.http.HttpClient.Redirect.NORMAL} applies: at
     * most {@link #MAX_REDIRECTS_FOLLOWED} redirects in a row are followed and the next 3xx is returned as received;
     * a redirect from an {@code https} URL to an {@code http} one is not followed and is returned as received, so
     * nothing reaches the {@code http} URL; and a 301, 302, 303, 307 or 308 without {@code Location} fails the
     * exchange.
     */
    private static final class FollowingRedirectStrategy extends DefaultRedirectStrategy {

        @Override
        public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context)
                throws ProtocolException {
            if (!isRedirectStatus(response.getCode())) {
                return false;
            }
            Header location = response.getFirstHeader(HttpHeaders.LOCATION);
            if (location == null) {
                throw new ProtocolException("Invalid redirection");
            }
            RedirectLocations followed = HttpClientContext.adapt(context).getRedirectLocations();
            if (followed != null && followed.size() >= MAX_REDIRECTS_FOLLOWED) {
                return false;
            }
            // A relative Location keeps the scheme of the URL it answers, so only an absolute http one downgrades.
            String targetScheme = createLocationURI(location.getValue()).getScheme();
            return !(HTTPS_SCHEME.equalsIgnoreCase(answeredScheme(request, followed))
                    && "http".equalsIgnoreCase(targetScheme));
        }

        /** 301, 302, 303, 307 and 308 redirect; 300, 304, 305 and 306 are returned as received. */
        private static boolean isRedirectStatus(int code) {
            return switch (code) {
                case 301, 302, 303, 307, 308 -> true;
                default -> false;
            };
        }

        /**
         * The scheme of the URL that answered with the redirect. httpclient5 passes the original request on every
         * hop, so that URL is the last redirect location followed, or the original request's before any has been.
         */
        private static String answeredScheme(HttpRequest original, RedirectLocations followed) {
            return followed == null || followed.size() == 0
                    ? original.getScheme()
                    : followed.get(followed.size() - 1).getScheme();
        }
    }

    /**
     * Returns the charset of the response Content-Type, or {@code null} when there is none or it cannot be
     * parsed, so a malformed upstream header never replaces the mapped-as-failure exception.
     */
    private static Charset charsetOf(HttpHeaders headers) {
        try {
            MediaType responseType = headers.getContentType();
            return responseType != null ? responseType.getCharset() : null;
        } catch (InvalidMediaTypeException ex) {
            return null;
        }
    }

    /**
     * The values Mule's token store kept for the grant. Only {@code accessToken} is read; {@code expiresIn},
     * {@code refreshToken} and the custom parameter {@code tokenType} (line 18) are held as the store held them.
     */
    private record Token(String accessToken, String expiresIn, String refreshToken, String tokenType) {

        /** Keeps both tokens out of any log line or message that renders this record. */
        @Override
        public String toString() {
            return "Token[expiresIn=" + expiresIn + ", tokenType=" + tokenType + "]";
        }
    }

    /** One resource response, read in full before the connection is released. */
    private record UpstreamResponse(HttpStatusCode status, String statusText, HttpHeaders headers, byte[] body) {
    }
}

