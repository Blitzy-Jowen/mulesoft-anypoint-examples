/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Objects;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

/**
 * Outbound adapter of the proxy: the original {@code http:request} against {@code HTTP_Request_Configuration}
 * (host, port and protocol from {@code proxy.upstream-url}, TLS key store from {@code keystore.*}).
 *
 * <p>The class only transports bytes. It forwards the method, the raw path and query, every header it is given
 * and the body exactly as received, and returns the upstream status, headers and body without parsing or
 * decompressing them ({@code parseResponse="false"}). Which headers travel in either direction, and how an
 * upstream error status is answered, are decided by the caller.
 *
 * <p>As the Mule 3.8 requester did by default, redirects are followed and both the connect and the response
 * timeout are {@code proxy.response-timeout-ms}. A timeout or connectivity failure surfaces unchanged as the
 * {@link org.springframework.web.client.ResourceAccessException} {@link RestClient} throws; nothing here
 * retries, re-authenticates or falls back.
 */
@Component
public class BoxProxyClient {

    private static final Logger LOG = LoggerFactory.getLogger(BoxProxyClient.class);

    private static final String FILE_URL_PREFIX = "file:";

    private final RestClient restClient;

    private final String baseUrl;

    /**
     * Builds the client once; the resulting {@link RestClient} is thread-safe and shared by every request.
     *
     * @param resourceLoader    resolves {@code classpath:} and {@code file:} key store locations; {@code null}
     *                          falls back to a {@link DefaultResourceLoader}
     * @param upstreamUrl       scheme, host and port of the proxied API; trailing {@code /} characters are ignored
     * @param responseTimeoutMs connect and response timeout in milliseconds, greater than zero
     * @param keystorePath      key store location; blank means no client key store and the JVM default TLS context
     * @param keystoreKey       the key store (store) password — Mule {@code tls:key-store password}
     * @param keystorePassword  the private-key password — Mule {@code tls:key-store keyPassword}; blank means the
     *                          store password is used
     * @throws IllegalStateException when the URL or timeout is invalid or the key store cannot be loaded, so the
     *                               application fails to start, as the original did with a bad key store
     */
    public BoxProxyClient(ResourceLoader resourceLoader,
                          @Value("${proxy.upstream-url}") String upstreamUrl,
                          @Value("${proxy.response-timeout-ms:10000}") long responseTimeoutMs,
                          @Value("${keystore.path:}") String keystorePath,
                          @Value("${keystore.key:}") String keystoreKey,
                          @Value("${keystore.password:}") String keystorePassword) {
        this.baseUrl = validatedBaseUrl(upstreamUrl);
        if (responseTimeoutMs <= 0) {
            throw new IllegalStateException(
                    "proxy.response-timeout-ms must be greater than zero, was " + responseTimeoutMs);
        }
        Duration timeout = Duration.ofMillis(responseTimeoutMs);
        SSLContext sslContext = buildSslContext(resourceLoader, keystorePath, keystoreKey, keystorePassword);

        // No retry is layered on this client. The JDK client's own transport recovery keeps its default, which no
        // per-client setting changes: it re-attempts a refused TCP connect once, and resends a GET or HEAD once when
        // the peer closes the connection before any response byte. Neither repeats a request with side effects.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(timeout)
                .sslContext(sslContext)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        // The static builder rather than Boot's RestClient.Builder: no default headers, base URL, URI template
        // handling or customizers may touch a proxied request, and tests construct the class without a context.
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Sends one request to the upstream API and returns its response as received.
     *
     * <p>{@code rawPath} and {@code rawQuery} are appended to the upstream URL verbatim, so the caller's
     * percent-encoding reaches the upstream byte for byte ({@code a%20b}, {@code +} and {@code %2F} stay as they
     * are). An empty query is sent as no query, as the original rebuilt the query from an empty parameter map.
     *
     * <p>Every header in {@code headers} is forwarded, {@code Authorization} included, apart from the
     * connection-level headers the HTTP client sets itself ({@code Host}, {@code Content-Length},
     * {@code Connection}, {@code Expect}, {@code Upgrade}). A body is sent only when {@code bodyOrNull} is not
     * {@code null}, with its exact length and no {@code Content-Type} beyond the one the caller supplied. Without
     * one the request carries no body, although the JDK client still writes {@code Content-Length: 0}.
     *
     * <p>Any status, 3xx not followed, 4xx and 5xx included, is returned as a value with its headers and body.
     *
     * @param method     the inbound request method
     * @param rawPath    the inbound request path, still percent-encoded, starting with {@code /}
     * @param rawQuery   the inbound query string without {@code ?}, still percent-encoded, or {@code null}
     * @param headers    the headers to forward, or {@code null} for none
     * @param bodyOrNull the request body, or {@code null} to send none
     * @return the upstream status, headers and body bytes
     * @throws IllegalArgumentException when the path or query does not form a valid URI
     * @throws org.springframework.web.client.ResourceAccessException on a timeout or I/O failure
     */
    public ResponseEntity<byte[]> exchange(HttpMethod method, String rawPath, String rawQuery,
                                           HttpHeaders headers, byte[] bodyOrNull) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(rawPath, "rawPath");
        URI uri = URI.create(baseUrl + rawPath + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery));
        // Path only: the query may carry tokens, and headers and bodies carry the caller's credentials.
        LOG.debug("Proxying {} {}", method, uri.getRawPath());

        RestClient.RequestBodySpec spec = restClient.method(method).uri(uri);
        if (headers != null) {
            spec.headers(h -> h.addAll(headers));
        }
        if (bodyOrNull != null) {
            // A streaming body, never body(byte[]): the byte-array converter would add
            // Content-Type: application/octet-stream to a request whose caller sent none.
            spec.headers(h -> h.setContentLength(bodyOrNull.length))
                    .body(out -> out.write(bodyOrNull));
        }
        return spec.exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body(StreamUtils.copyToByteArray(response.getBody())));
    }

    private static String validatedBaseUrl(String upstreamUrl) {
        String url = upstreamUrl == null ? "" : upstreamUrl;
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        url = url.substring(0, end);
        // The value is never echoed: a misconfigured URL could carry user information.
        String requirement = "proxy.upstream-url must be an absolute http or https URL with a host";
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(requirement, e);
        }
        String scheme = uri.getScheme();
        if (uri.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalStateException(requirement);
        }
        return url;
    }

    /**
     * The original's {@code tls:context}: a client key store and no trust store, so the JVM default trust applies.
     * The message of every failure names the configured path and never a password.
     */
    private static SSLContext buildSslContext(ResourceLoader resourceLoader, String keystorePath,
                                              String keystoreKey, String keystorePassword) {
        try {
            if (keystorePath == null || keystorePath.isBlank()) {
                return SSLContext.getDefault();
            }
            Resource resource = resolveKeystore(resourceLoader, keystorePath);
            if (!resource.exists()) {
                throw keystoreFailure(keystorePath, null);
            }
            // JKS with Java 17's keystore compatibility mode also reads a PKCS12 store.
            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (InputStream in = resource.getInputStream()) {
                keyStore.load(in, chars(keystoreKey));
            }
            String keyPassword = keystorePassword == null || keystorePassword.isBlank()
                    ? keystoreKey
                    : keystorePassword;
            KeyManagerFactory keyManagerFactory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, chars(keyPassword));
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagerFactory.getKeyManagers(), null, null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw keystoreFailure(keystorePath, e);
        }
    }

    /**
     * {@code classpath:} and {@code file:} locations go to the resource loader. A bare path is a file when one
     * exists there and a classpath resource otherwise, which is how Mule found {@code path="keystore.jks"} in
     * {@code src/main/resources}.
     */
    private static Resource resolveKeystore(ResourceLoader resourceLoader, String path) {
        if (path.startsWith(ResourceLoader.CLASSPATH_URL_PREFIX) || path.startsWith(FILE_URL_PREFIX)) {
            return Objects.requireNonNullElseGet(resourceLoader, DefaultResourceLoader::new).getResource(path);
        }
        FileSystemResource file = new FileSystemResource(path);
        return file.exists() ? file : new ClassPathResource(path);
    }

    private static char[] chars(String password) {
        return password == null ? new char[0] : password.toCharArray();
    }

    private static IllegalStateException keystoreFailure(String keystorePath, Throwable cause) {
        return new IllegalStateException("Cannot load keystore from keystore.path=" + keystorePath, cause);
    }
}
