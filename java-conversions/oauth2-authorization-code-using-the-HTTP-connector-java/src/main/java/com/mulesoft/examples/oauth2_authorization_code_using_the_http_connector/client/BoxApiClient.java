/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

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
 * {@link RawResponse} like any other. A refused connection, a DNS failure or a connect or read timeout
 * propagates as {@link org.springframework.web.client.ResourceAccessException}, and nothing here retries.
 *
 * <p>An {@code https} URL is sent through {@code TLS_Context} only: the trust store {@code trust-store} and the
 * key store {@code keystore.jks}, never the JDK {@code cacerts}. That client is built on the first {@code https}
 * request, so the application starts without the store passwords and plain-HTTP stubs never touch the stores.
 */
@Component
public class BoxApiClient {

    /** Connect timeout, the Mule default response timeout. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Read timeout, the Mule default response timeout. */
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
     */
    public RawResponse search(String accessToken) {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        URI uri = absoluteUri(stripTrailingSlash(properties.getApiBaseUrl()) + SEARCH_SUFFIX, "box.api-base-url");
        LOGGER.debug("GET {}", uri);
        return execute(clientFor(uri).get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private RawResponse postForm(byte[] form) {
        URI uri = absoluteUri(properties.getTokenUrl(), "box.token-url");
        LOGGER.debug("POST {}", uri);
        // A byte[] body goes through ByteArrayHttpMessageConverter, which keeps FORM_CONTENT_TYPE as set;
        // Spring's form converter would append ";charset=UTF-8" and leave field order to the map.
        return execute(clientFor(uri).post()
                .uri(uri)
                .contentType(FORM_CONTENT_TYPE)
                .body(form));
    }

    /**
     * Runs the request through {@code exchange}, which bypasses RestClient's status handlers so that error
     * statuses are returned. RestClient wraps every {@link IOException} of the exchange in
     * {@link org.springframework.web.client.ResourceAccessException}, which is left to propagate.
     */
    private static RawResponse execute(RestClient.RequestHeadersSpec<?> spec) {
        return spec.exchange((request, response) -> toRaw(response));
    }

    private static RawResponse toRaw(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        byte[] body = StreamUtils.copyToByteArray(response.getBody());
        return new RawResponse(status, headers, body);
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
     * Parses a configured URL, which is passed to RestClient as a {@link URI} so that no template expansion or
     * re-encoding happens. A value that is not an absolute URL fails with the configuration key it came from;
     * the value itself is left out of the message, since a URL can carry user information.
     */
    private static URI absoluteUri(String url, String key) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(key + " is not a valid URL", e);
        }
        if (!uri.isAbsolute()) {
            throw new IllegalStateException(key + " is not an absolute URL");
        }
        return uri;
    }

    private static String stripTrailingSlash(String url) {
        String stripped = url;
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
