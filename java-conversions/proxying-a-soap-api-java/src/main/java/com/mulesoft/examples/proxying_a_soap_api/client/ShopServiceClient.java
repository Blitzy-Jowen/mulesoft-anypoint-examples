/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api.client;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

/**
 * Outbound adapter of the proxy towards predic8's {@code ShopService}: the original {@code http:request-config}
 * {@code HTTP_Request_Configuration} ({@code soap-api-proxy.xml:4}) with its
 * {@code http:request method="POST" path="shop/ShopService"} ({@code soap-api-proxy.xml:10}), and the read of the
 * {@code cxf:proxy-service wsdlLocation} ({@code soap-api-proxy.xml:7}).
 *
 * <p>The class only transports bytes. Each {@link #post(HttpHeaders, byte[])} is exactly one HTTP/1.1 POST to the
 * fixed {@code proxy.upstream-url}; no inbound path or query is appended. Every status the upstream answers with,
 * 4xx and 5xx included, is returned as a value with its headers and body, and nothing is retried. Which status
 * counts as a failure, and which headers travel in either direction, are decided by the caller.
 *
 * <p>As the Mule 3.8 requester did by default, redirects are followed, except from an {@code https} URL to an
 * {@code http} one. {@code proxy.response-timeout-ms} bounds the connect and the wait for the response status and
 * headers. A timeout, a refused connection, a DNS failure or any other I/O failure, one while the response body is
 * read included, surfaces as the {@link org.springframework.web.client.ResourceAccessException} {@link RestClient}
 * throws.
 *
 * <p>The original flow logs nothing, adds no authentication and keeps no state, and neither does this class: the
 * caller's headers, {@code Authorization} and any SOAP security material included, reach the upstream as given.
 * Instances are immutable and thread-safe.
 */
@Component
public class ShopServiceClient {

    private static final String HTTP_PREFIX = "http:";

    private static final String HTTPS_PREFIX = "https:";

    private final ResourceLoader resourceLoader;

    private final URI upstreamUri;

    private final String wsdlLocation;

    private final long responseTimeoutMs;

    private final RestClient restClient;

    /**
     * Builds the client once; the resulting {@link RestClient} is shared by every request.
     *
     * @param resourceLoader    resolves {@code classpath:}, {@code file:} and plain WSDL locations
     * @param upstreamUrl       absolute {@code http} or {@code https} URL every envelope is posted to, used verbatim
     *                          ({@code proxy.upstream-url})
     * @param wsdlLocation      location of the upstream WSDL ({@code proxy.wsdl-location})
     * @param responseTimeoutMs in milliseconds, greater than zero: the connect timeout and the limit on the wait for
     *                          the response status and headers, of posts and WSDL reads alike
     *                          ({@code proxy.response-timeout-ms})
     * @throws IllegalArgumentException when an argument is missing, the URL is not an absolute {@code http} or
     *                                  {@code https} URL with a host, or the timeout is not positive, so the
     *                                  application fails to start
     */
    public ShopServiceClient(ResourceLoader resourceLoader,
                             @Value("${proxy.upstream-url}") String upstreamUrl,
                             @Value("${proxy.wsdl-location}") String wsdlLocation,
                             @Value("${proxy.response-timeout-ms}") long responseTimeoutMs) {
        Assert.notNull(resourceLoader, "resourceLoader must not be null");
        Assert.hasText(upstreamUrl, "proxy.upstream-url must not be empty");
        Assert.hasText(wsdlLocation, "proxy.wsdl-location must not be empty");
        Assert.isTrue(responseTimeoutMs > 0,
                () -> "proxy.response-timeout-ms must be greater than zero, was " + responseTimeoutMs);
        URI uri = URI.create(upstreamUrl);
        Assert.isTrue(uri.getHost() != null && isHttpUrl(upstreamUrl),
                "proxy.upstream-url must be an absolute http or https URL with a host");

        this.resourceLoader = resourceLoader;
        this.upstreamUri = uri;
        this.wsdlLocation = wsdlLocation;
        this.responseTimeoutMs = responseTimeoutMs;

        Duration timeout = Duration.ofMillis(responseTimeoutMs);
        // HTTP/1.1 explicitly: the JDK default HTTP_2 adds "Connection: Upgrade, HTTP2-Settings" and "Upgrade: h2c"
        // to every http: request, which the Mule HTTP/1.1 requester never sent.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        // The static builder rather than Boot's RestClient.Builder: no default headers, base URL or customizer may
        // touch a proxied request, and tests construct the class without a context. No status handler is configured
        // because post() uses exchange(), which applies none, so no status is ever turned into an exception.
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Posts one SOAP envelope to the upstream and returns its response as received
     * ({@code http:request method="POST" path="shop/ShopService"}, {@code soap-api-proxy.xml:10}).
     *
     * <p>Every header in {@code headers} is forwarded in its order with all its values. The JDK client does not
     * allow {@code Connection}, {@code Content-Length}, {@code Expect}, {@code Host} and {@code Upgrade} to be set,
     * so the request factory leaves those out and the client writes its own framing: {@code Content-Length} is the
     * length of {@code body}. A request without {@code Content-Type} is sent as {@code application/octet-stream}.
     * The body bytes are sent unchanged.
     *
     * <p>Exactly one request is sent; a redirect the client follows is part of that exchange. The upstream status,
     * whatever it is, is returned as a value, never thrown, and nothing is retried.
     *
     * @param headers the headers to forward, already mapped by the caller
     * @param body    the envelope bytes; an empty array sends an empty body
     * @return the upstream status, its headers, and its body bytes ({@code byte[0]} when it sent none); the
     *         headers are a copy that outlives the exchange, read-only as every {@link ResponseEntity}'s are
     * @throws IllegalArgumentException when {@code headers} or {@code body} is {@code null}
     * @throws org.springframework.web.client.ResourceAccessException on a timeout, a refused connection, a DNS
     *                                                                failure or any other I/O failure
     */
    public ResponseEntity<byte[]> post(HttpHeaders headers, byte[] body) {
        Assert.notNull(headers, "headers must not be null");
        Assert.notNull(body, "body must not be null");
        // exchange() rather than retrieve(): its callback runs inside RestClient's I/O handling, so a failure while
        // the response body is read is a ResourceAccessException as well, and the response is closed afterwards.
        return restClient.post()
                .uri(upstreamUri)
                .headers(h -> {
                    h.addAll(headers);
                    // The JDK publisher sends exactly the declared length, so it must be this body's.
                    h.setContentLength(body.length);
                })
                .body(body)
                .exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                        .headers(response.getHeaders())
                        .body(StreamUtils.copyToByteArray(response.getBody())));
    }

    /**
     * The upstream WSDL ({@code cxf:proxy-service wsdlLocation}, {@code soap-api-proxy.xml:7}), which the
     * application reads once at start-up to publish it at {@code ?wsdl}, so an unreachable WSDL fails start-up as
     * the CXF deployment did.
     *
     * <p>For an {@code http} or {@code https} location every connection, the {@code GET} of {@link Resource#exists()}
     * and of {@link Resource#getInputStream()} alike, has {@code proxy.response-timeout-ms} as connect and read
     * timeout and is never retried; the schemas it imports through {@link Resource#createRelative(String)} are read
     * the same way. Every other location ({@code classpath:}, {@code file:}, a plain path) is the resource the
     * {@link ResourceLoader} returns for it.
     *
     * @return the resource holding the upstream WSDL
     * @throws IllegalStateException when an {@code http} or {@code https} location is not a valid URL
     */
    public Resource wsdlResource() {
        if (!isHttpUrl(wsdlLocation)) {
            return resourceLoader.getResource(wsdlLocation);
        }
        try {
            return new TimeoutUrlResource(URI.create(wsdlLocation).toURL(), responseTimeoutMs);
        } catch (MalformedURLException | IllegalArgumentException ex) {
            throw new IllegalStateException("proxy.wsdl-location is not a valid URL: " + wsdlLocation, ex);
        }
    }

    private static boolean isHttpUrl(String location) {
        return location.regionMatches(true, 0, HTTP_PREFIX, 0, HTTP_PREFIX.length())
                || location.regionMatches(true, 0, HTTPS_PREFIX, 0, HTTPS_PREFIX.length());
    }

    /**
     * A {@link UrlResource} whose every connection, the existence probe of {@link #exists()} included, carries the
     * client's connect and read timeouts, and whose relative resources are resolved as URIs and carry them too.
     */
    static final class TimeoutUrlResource extends UrlResource {

        private final long timeoutMs;

        TimeoutUrlResource(URL url, long timeoutMs) {
            super(url);
            this.timeoutMs = timeoutMs;
        }

        @Override
        protected void customizeConnection(URLConnection con) throws IOException {
            super.customizeConnection(con);
            int timeout = (int) Math.min(timeoutMs, Integer.MAX_VALUE);
            con.setConnectTimeout(timeout);
            con.setReadTimeout(timeout);
        }

        /**
         * Whether the location answers a {@code GET} with a 2xx status. {@link UrlResource} probes with
         * {@code HEAD} instead, which the original never sent: CXF read the WSDL and its schemas with {@code GET}
         * only, and an upstream that refuses {@code HEAD} (405) would make the WSDL look absent and fail start-up.
         * The probe reads no body and carries the same timeouts; any I/O failure answers {@code false}.
         */
        @Override
        public boolean exists() {
            try {
                URLConnection con = getURL().openConnection();
                customizeConnection(con);
                if (!(con instanceof HttpURLConnection httpCon)) {
                    return super.exists();
                }
                try {
                    int code = httpCon.getResponseCode();
                    return code >= HttpURLConnection.HTTP_OK && code < HttpURLConnection.HTTP_MULT_CHOICE;
                } finally {
                    httpCon.disconnect();
                }
            } catch (IOException ex) {
                return false;
            }
        }

        /** Readable exactly when {@link #exists()}: an HTTP resource that answers {@code GET} can be read. */
        @Override
        public boolean isReadable() {
            return exists();
        }

        /**
         * An absolute {@code http} or {@code https} location is kept exactly as written, the form JAX-WS-generated
         * WSDLs import their schemas with ({@code http://host/shop/ShopService?xsd=1}); any other location is
         * resolved against this URL by {@link URI#resolve(String)}. {@link UrlResource#createRelative(String)} is not
         * used because it appends every location, an absolute one included, to this URL's directory.
         *
         * <p>Only {@code http} and {@code https} results are followed: a WSDL fetched from the network must not make
         * the application read a {@code file:} or {@code jar:} location and publish its content as a schema.
         *
         * @throws MalformedURLException when the location is not a valid URI reference or resolves to a scheme other
         *                               than {@code http} or {@code https}
         */
        @Override
        public Resource createRelative(String relativePath) throws MalformedURLException {
            URI target;
            try {
                target = isHttpUrl(relativePath)
                        ? URI.create(relativePath)
                        : getURL().toURI().resolve(relativePath);
            } catch (URISyntaxException | IllegalArgumentException ex) {
                throw malformed("Cannot resolve '" + relativePath + "' against " + getURL(), ex);
            }
            if (!isHttpUrl(target.toString())) {
                throw malformed("'" + relativePath + "' referenced by " + getURL()
                        + " is not an http or https location", null);
            }
            try {
                return new TimeoutUrlResource(target.toURL(), timeoutMs);
            } catch (IllegalArgumentException ex) {
                throw malformed("Cannot resolve '" + relativePath + "' against " + getURL(), ex);
            }
        }

        private static MalformedURLException malformed(String message, Throwable cause) {
            MalformedURLException failure = new MalformedURLException(message);
            if (cause != null) {
                failure.initCause(cause);
            }
            return failure;
        }
    }
}
