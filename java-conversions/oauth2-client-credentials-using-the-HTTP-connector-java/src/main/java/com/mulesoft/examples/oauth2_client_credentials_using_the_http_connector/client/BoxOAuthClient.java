/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import javax.net.ssl.SSLContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
 * TLS configuration fails the call before any client secret is sent.
 *
 * <p>Connect and read timeouts are 10 s, the Mule requester's default response timeout. Redirects are followed,
 * as {@code http:request} follows them by default. The client is thread-safe.
 */
@Component
public class BoxOAuthClient {

    /** The Mule HTTP requester's default response timeout. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** First status code outside the Mule 3.8 requester's default success status range {@code 0..399}. */
    private static final int FIRST_FAILURE_STATUS = 400;

    /** Statuses of Mule's default {@code refreshTokenWhen}: the resource server rejected the access token. */
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;

    private static final String HTTPS_SCHEME = "https";

    /** The whole client-credentials token request body: no scope, and the client credentials travel in Basic. */
    private static final byte[] CLIENT_CREDENTIALS_FORM =
            "grant_type=client_credentials".getBytes(StandardCharsets.US_ASCII);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String baseUrl;
    private final String basePath;
    private final TlsProperties tls;
    private final Duration timeout;

    /** Plain client of the token endpoint: JVM-default TLS, as the token request declares no TLS context. */
    private final RestClient tokenClient;

    private final Object resourceClientLock = new Object();

    /** Client of the Box resource request; built on the first {@link #authorize()}. */
    private volatile RestClient resourceClient;

    /** The cached token, replacing {@code Token_Manager_Config}; written only under this instance's lock. */
    private volatile Token token;

    /**
     * Creates the client the application uses, with the 10 s Mule default timeouts.
     *
     * @param tokenUrl     {@code box.oauth.token-url}: the client-credentials token endpoint
     * @param clientId     {@code oauth.client.id}: the Box application's client id
     * @param clientSecret {@code oauth.client.secret}: the Box application's client secret
     * @param baseUrl      {@code box.api.base-url}: scheme, host and port of the Box resource request
     * @param basePath     {@code box.api.base-path}: the request configuration's basePath
     * @param tls          key store and trust store of the HTTPS resource request
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
     * Creates a client with the given connect and read timeout. Construction only records the values and
     * builds the plain token client; it opens no connection and never reads {@code tls}.
     *
     * @param tokenUrl     the client-credentials token endpoint
     * @param clientId     the client id sent in the Basic header of the token request
     * @param clientSecret the client secret sent in the Basic header of the token request
     * @param baseUrl      scheme, host and port of the resource request, without a trailing slash
     * @param basePath     path prefix of the resource request; {@code "/"} is appended to it
     * @param tls          TLS stores of the resource request; may be {@code null} unless {@code baseUrl} is https
     * @param timeout      connect timeout and per-request read timeout of both requests
     */
    public BoxOAuthClient(String tokenUrl, String clientId, String clientSecret, String baseUrl, String basePath,
                          TlsProperties tls, Duration timeout) {
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.baseUrl = baseUrl;
        this.basePath = basePath;
        this.tls = tls;
        this.timeout = timeout;
        this.tokenClient = restClient(null);
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
     * @throws IllegalStateException when the token request fails or its response carries no access token, when
     *         the TLS context of an https base URL cannot be built, or when an https base URL has no
     *         {@link TlsProperties}
     * @throws org.springframework.web.client.ResourceAccessException on an I/O failure of either request,
     *         including connect and read timeouts; nothing is retried after one
     */
    public ResponseEntity<byte[]> authorize() {
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

    private UpstreamResponse get(RestClient client, URI uri, Token bearer) {
        // exchange bypasses RestClient's default status handlers, so every status reaches the checks above,
        // and adds no converter-derived Accept header. No inbound header is copied: line 34 builds none.
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
     * Returns the cached token, requesting it on first use. Neither expiry nor {@code expires_in} triggers a
     * refresh; only a rejected resource request does.
     */
    private Token currentToken() {
        Token cached = token;
        return cached != null ? cached : fetchTokenIfAbsent();
    }

    private synchronized Token fetchTokenIfAbsent() {
        if (token == null) {
            token = requestToken();
        }
        return token;
    }

    /**
     * Replaces {@code rejected} with a newly requested token. When another thread has already replaced it, that
     * thread's token is used instead of a second exchange.
     */
    private synchronized Token replaceToken(Token rejected) {
        if (token == null || token == rejected) {
            token = requestToken();
        }
        return token;
    }

    private Token requestToken() {
        String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        // The form is sent as raw bytes with an explicit Content-Type, so no converter adds a charset parameter.
        return tokenClient.post()
                .uri(URI.create(tokenUrl))
                .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(CLIENT_CREDENTIALS_FORM)
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
            throw new IllegalStateException(noAccessToken(status), e);
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

    private RestClient buildResourceClient() {
        if (!HTTPS_SCHEME.equalsIgnoreCase(URI.create(baseUrl).getScheme())) {
            return tokenClient;
        }
        if (tls == null) {
            // Never fall back to the JVM-default trust: the original binds TLS_Context to this request.
            throw new IllegalStateException("No TLS configuration for the https Box base URL " + baseUrl);
        }
        return restClient(tls.createSslContext());
    }

    /**
     * Builds a client that speaks HTTP/1.1 as Mule's requester does (the JDK default, HTTP/2, would add
     * {@code Upgrade: h2c} and {@code HTTP2-Settings} headers on http URIs) and follows redirects, as
     * {@code http:request} does by default.
     */
    private RestClient restClient(SSLContext sslContext) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(builder.build());
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(requestFactory).build();
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

