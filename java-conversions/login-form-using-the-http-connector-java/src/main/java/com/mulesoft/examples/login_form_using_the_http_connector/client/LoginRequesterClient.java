/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.login_form_using_the_http_connector.client;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.mulesoft.examples.login_form_using_the_http_connector.config.LoginProperties;

/**
 * Outbound adapter replacing the {@code http:request} of flow {@code CallLoginFlowUsingRequester}
 * ({@code login-form-using-the-http-connector.xml}:42), which posts a form map to this application's own
 * {@code POST /login} through {@code HttpRequestConfig} (xml:5).
 *
 * <p>The request carries the form exactly as Mule's requester encoded a {@code Map} payload: Content-Type
 * {@code application/x-www-form-urlencoded} with no charset parameter, and a body of UTF-8 URL-encoded pairs
 * in the map's own iteration order. Any response status of 400 or more fails the call, as Mule's default
 * success-status validator (0..399) did; any other response is relayed with its status, raw Content-Type
 * value and body bytes unchanged. Connect and read timeouts are 10 s, Mule's default response timeout, and
 * no call is ever retried.
 *
 * <p>The original targeted {@code ${http.port}}, the port of its own listener. Here the port is taken from
 * the running web server when it starts, so the client follows {@code server.port}, {@code http.port} and a
 * random test port alike; the host is {@code login.requester-host}.
 */
@Component
public class LoginRequesterClient {

    /** Connect and read timeout: the Mule HTTP requester's default response timeout. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Path of the {@code http:request} (xml:42). */
    static final String LOGIN_PATH = "/login";

    private final RestClient restClient;

    /** Host the base URL is built on once the server port is known; {@code null} for a fixed base URL. */
    private final String requesterHost;

    /** {@code http://<host>:<port>} without a trailing slash; {@code null} until the server port is known. */
    private volatile String baseUrl;

    /**
     * Creates the client the application uses, whose base URL is set when the web server has started.
     *
     * @param builder    Boot's prototype {@link RestClient.Builder}
     * @param properties source of {@code login.requester-host}
     * @throws IllegalArgumentException if the requester host is blank
     */
    @Autowired
    public LoginRequesterClient(RestClient.Builder builder, LoginProperties properties) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(properties, "properties");
        String host = properties.requesterHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("login.requester-host must not be blank");
        }
        this.restClient = builder.requestFactory(requestFactory()).build();
        this.requesterHost = host;
    }

    /**
     * Creates a client bound to a fixed base URL, which server start-up never changes.
     *
     * @param builder the {@link RestClient.Builder} to build on
     * @param baseUrl scheme, host and port the {@code /login} path is appended to, for example
     *                {@code http://localhost:8089}; trailing slashes are removed
     * @throws IllegalArgumentException if {@code baseUrl} is null or blank
     */
    public LoginRequesterClient(RestClient.Builder builder, String baseUrl) {
        Objects.requireNonNull(builder, "builder");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        String trimmed = baseUrl;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        this.restClient = builder.requestFactory(requestFactory()).build();
        this.requesterHost = null;
        this.baseUrl = trimmed;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        return factory;
    }

    /**
     * Points the client at the application's own listener once the main web server has started. Events of a
     * namespaced server (for example a separate {@code management} port) and of a client built on a fixed
     * base URL leave the base URL unchanged.
     *
     * @param event the server start-up event
     */
    @EventListener
    public void onWebServerInitialized(WebServerInitializedEvent event) {
        if (requesterHost == null) {
            return;
        }
        WebServerApplicationContext context = event.getApplicationContext();
        if (context != null && context.getServerNamespace() != null) {
            return;
        }
        baseUrl = "http://" + requesterHost + ":" + event.getWebServer().getPort();
    }

    /** Returns the current base URL, or {@code null} before the server port is known. */
    String baseUrl() {
        return baseUrl;
    }

    /**
     * Posts {@code form} to {@code /login} as {@code application/x-www-form-urlencoded}.
     *
     * @param form the form fields, encoded in the map's iteration order
     * @return the response status, raw Content-Type header value ({@code null} when absent) and body bytes
     * @throws NullPointerException  if {@code form} or one of its keys is null
     * @throws IllegalStateException if the server port is not known yet, or the response status is 400 or
     *                               more ({@code Response code <status> mapped as failure.})
     * @throws org.springframework.web.client.ResourceAccessException on an I/O failure such as a refused
     *                               connection or a read timeout
     */
    public LoginResponse postLogin(Map<String, String> form) {
        Objects.requireNonNull(form, "form");
        String target = baseUrl;
        if (target == null) {
            throw new IllegalStateException("LoginRequesterClient used before the web server port is known");
        }
        byte[] body = encodeForm(form);
        // A byte[] body is written by ByteArrayHttpMessageConverter, which keeps the Content-Type exactly
        // as given; a MultiValueMap would go through FormHttpMessageConverter, which appends a charset.
        return restClient.post()
                .uri(URI.create(target + LOGIN_PATH))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status >= 400) {
                        throw new IllegalStateException("Response code " + status + " mapped as failure.");
                    }
                    String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
                    return new LoginResponse(status, contentType, response.getBody().readAllBytes());
                });
    }

    /**
     * Encodes the form as Mule 3.8's {@code HttpParser.encodeString} did: {@code name=value} pairs joined by
     * {@code &}, names and values URL-encoded in UTF-8, in iteration order. A null value is written as the
     * bare name without {@code =}, which is Mule's rendering of a parameter with no value, not {@code name=}.
     */
    private static byte[] encodeForm(Map<String, String> form) {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            String name = entry.getKey();
            if (name == null) {
                throw new NullPointerException("form key");
            }
            if (encoded.length() > 0) {
                encoded.append('&');
            }
            encoded.append(URLEncoder.encode(name, StandardCharsets.UTF_8));
            String value = entry.getValue();
            if (value != null) {
                encoded.append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }
        return encoded.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * The relayed response of {@code POST /login}.
     *
     * @param status      the response status code, below 400
     * @param contentType the raw Content-Type header value, or {@code null} when the response has none
     * @param body        the response body bytes, empty when the response has no body
     */
    public record LoginResponse(int status, String contentType, byte[] body) {

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof LoginResponse that
                    && status == that.status
                    && Objects.equals(contentType, that.contentType)
                    && Arrays.equals(body, that.body);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Integer.hashCode(status) + Objects.hashCode(contentType)) + Arrays.hashCode(body);
        }

        /** Describes the response with the body's length only, never its content. */
        @Override
        public String toString() {
            return "LoginResponse[status=" + status + ", contentType=" + contentType + ", body="
                    + (body == null ? "null" : body.length + " bytes") + "]";
        }
    }
}
