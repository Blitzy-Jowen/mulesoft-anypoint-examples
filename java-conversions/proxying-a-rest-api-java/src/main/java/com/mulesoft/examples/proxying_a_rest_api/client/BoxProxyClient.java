/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.client;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecRuntime;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.protocol.RedirectLocations;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.config.CharCodingConfig;
import org.apache.hc.core5.http.impl.io.DefaultHttpRequestWriterFactory;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;
import org.apache.hc.core5.http.message.BasicLineFormatter;
import org.apache.hc.core5.http.message.RequestLine;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.URIAuthority;
import org.apache.hc.core5.util.CharArrayBuffer;
import org.apache.hc.core5.util.Timeout;
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
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
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
 * <p>Header values are written as ISO-8859-1 bytes, one byte per character, as the listener reads them, so
 * obs-text such as UTF-8 reaches the upstream unchanged; response heads are read the same way. A control character
 * other than HTAB never reaches the wire: CR, LF, FF and VT in a value become SP, the others and DEL become
 * {@code ?}, as httpcore5's default writer substitutes them.
 *
 * <p>As the Mule 3.8 requester did by default, redirects are followed, except from an {@code https} URL to an
 * {@code http} one. {@code proxy.response-timeout-ms} bounds the connect, the TLS handshake, every write of the
 * request body, the wait for the response head and every wait for response body bytes, so an upstream that stalls
 * at any point, after its headers included, cannot hold the calling thread longer than that. A timeout or
 * connectivity failure surfaces unchanged as the {@link org.springframework.web.client.ResourceAccessException}
 * {@link RestClient} throws, and the connection it happened on is discarded; nothing here retries, re-sends,
 * re-authenticates or falls back.
 *
 * <p>Connections are pooled without limit, as the Mule requester's default {@code maxConnections} of -1 allowed,
 * and released by {@link #close()} when the application context shuts down.
 */
@Component
public class BoxProxyClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BoxProxyClient.class);

    private static final String FILE_URL_PREFIX = "file:";

    /**
     * Largest slice of request body handed to the connection under one write deadline: httpclient5's default
     * session buffer, so each slice reaches the socket in at most one write and the deadline measures progress.
     */
    private static final int WRITE_SLICE_BYTES = 8192;

    /** Headers a redirect never carries to another origin; the client sets {@code Host} for the new target. */
    private static final List<String> CROSS_ORIGIN_REDIRECT_DROPPED_HEADERS =
            List.of("Authorization", "Cookie", "Origin", "Referer", "Host");

    /** Exchange attribute set once a redirect has left the original request's origin. */
    private static final String LEFT_ORIGIN_ATTRIBUTE = BoxProxyClient.class.getName() + ".leftOrigin";

    /** The methods Spring's request factory builds itself; any other method token is sent as given. */
    private static final Set<HttpMethod> STANDARD_METHODS = Set.of(HttpMethod.values());

    private final CloseableHttpClient httpClient;

    private final ScheduledThreadPoolExecutor writeDeadlines;

    private final long timeoutMs;

    private final RestClient restClient;

    private final String baseUrl;

    /**
     * Builds the client once; the resulting {@link RestClient} is thread-safe and shared by every request.
     *
     * @param resourceLoader    resolves {@code classpath:} and {@code file:} key store locations; {@code null}
     *                          falls back to a {@link DefaultResourceLoader}
     * @param upstreamUrl       scheme, host and port of the proxied API; trailing {@code /} characters are ignored
     * @param responseTimeoutMs in milliseconds, greater than zero: the connect, TLS handshake, request body write
     *                          and response read timeout, each applied to every wait on the socket
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
        this.timeoutMs = responseTimeoutMs;
        // One daemon thread, started by the first body write: it closes a connection whose write makes no progress.
        this.writeDeadlines = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "box-proxy-write-deadline");
            thread.setDaemon(true);
            return thread;
        });
        this.writeDeadlines.setRemoveOnCancelPolicy(true);

        Timeout limit = Timeout.of(timeout);
        // httpclient5's default of five connections per route would queue concurrent proxied calls. The connections
        // encode and decode heads in ISO-8859-1, the listener's one character per byte, so header bytes from 0x80 to
        // 0x9F pass unchanged, which httpcore5 without a charset writes as '?'; the formatter keeps substituting
        // control characters as that writer does.
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create().setSslContext(sslContext).build())
                .setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
                        .charCodingConfig(CharCodingConfig.custom()
                                .setCharset(StandardCharsets.ISO_8859_1)
                                .setMalformedInputAction(CodingErrorAction.REPLACE)
                                .setUnmappableInputAction(CodingErrorAction.REPLACE)
                                .build())
                        .requestWriterFactory(new DefaultHttpRequestWriterFactory(new ObsTextLineFormatter()))
                        .build())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(limit)
                        .setSocketTimeout(limit)
                        .build())
                .setDefaultTlsConfig(TlsConfig.custom().setHandshakeTimeout(limit).build())
                .setMaxConnTotal(Integer.MAX_VALUE)
                .setMaxConnPerRoute(Integer.MAX_VALUE)
                .build();
        // No request is ever re-sent: automatic retries and authentication challenge handling are off. The response
        // timeout is a socket timeout, so it bounds the wait for the response head and every wait for body bytes; on
        // expiry the read fails with SocketTimeoutException, httpclient5 discards the connection, and RestClient
        // closes the response and throws ResourceAccessException. Body writes get the same bound from
        // boundBodyWrites, because a socket write has no timeout of its own. Content compression would add
        // Accept-Encoding and decompress the unparsed response, a cookie store would replay one caller's cookies to
        // the next, and an auth cache would keep state across callers, so all three are off.
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(limit)
                        .setConnectionRequestTimeout(limit)
                        .setRedirectsEnabled(true)
                        .setAuthenticationEnabled(false)
                        .build())
                .disableAutomaticRetries()
                .disableContentCompression()
                .disableCookieManagement()
                .disableAuthCaching()
                // A redirect never downgrades https to cleartext http, as java.net.http's Redirect.NORMAL refuses too.
                .setRedirectStrategy(new NoDowngradeRedirectStrategy())
                .addExecInterceptorAfter(ChainElement.REDIRECT.name(), "redirect-header-policy",
                        BoxProxyClient::applyRedirectHeaderPolicy)
                .addExecInterceptorAfter(ChainElement.REDIRECT.name(), "body-write-deadline", this::boundBodyWrites)
                .build();
        // The client's default request configuration applies because no factory timeout is set.
        // Spring's factory rejects every method outside HttpMethod.values(), while the original requester sent
        // whatever method the caller used, so an extension method such as PROPFIND travels as a generic request.
        // The standard methods keep Spring's request classes, whose TRACE refuses a body.
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient) {
            @Override
            protected ClassicHttpRequest createHttpUriRequest(HttpMethod httpMethod, URI uri) {
                return STANDARD_METHODS.contains(httpMethod)
                        ? super.createHttpUriRequest(httpMethod, uri)
                        : new HttpUriRequestBase(httpMethod.name(), uri);
            }
        };
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
     * <p>Every header in {@code headers} is forwarded with its values, {@code Authorization}, {@code Expect} and
     * {@code Upgrade} included; several {@code Cookie} values travel as one {@code Cookie} header joined with
     * {@code "; "}, as RFC 6265 requires. {@code Content-Length} and {@code Transfer-Encoding} in {@code headers}
     * are ignored: the client writes the framing of the body it sends. It also adds {@code Host},
     * {@code Connection: keep-alive} and its own {@code User-Agent} when {@code headers} has none, so the caller
     * removes an inbound {@code Host} and {@code Connection} first. {@code Expect: 100-continue} keeps its protocol
     * behaviour: the body follows the upstream's {@code 100 Continue}, or a three-second wait for it, and is
     * withheld when the upstream answers with a 4xx or 5xx status first.
     *
     * <p>A body is sent only when {@code bodyOrNull} holds at least one byte, with its exact length and no
     * {@code Content-Type} beyond the one the caller supplied. Without one, GET, HEAD, OPTIONS and TRACE requests
     * carry no {@code Content-Length} and every other method carries {@code Content-Length: 0}. A TRACE request
     * with body bytes cannot be sent, because RFC 7231 forbids a client to send one: httpclient5 refuses the entity
     * with an {@link IllegalStateException} before anything reaches the upstream, and it matches the method token
     * in any letter case, so an extension token such as {@code trace} fails the same way. Dropping TRACE bytes, as
     * the Mule requester did, is the caller's decision.
     *
     * <p>A followed redirect carries every header of the original request while it stays on the original
     * origin, and none of {@code Authorization}, {@code Cookie}, {@code Origin}, {@code Referer} or {@code Host}
     * once it has left it. A 307 or 308 re-sends the method and body; a 303 to a method other than GET or HEAD, or
     * a 301 or 302 to a POST, continues as a GET without the body. A redirect from an {@code https} URL to an
     * {@code http} one is not followed and is returned as received; nothing is sent to the {@code http} URL. Any
     * status, 3xx not followed, 4xx and 5xx included, is returned as a value with its headers and body.
     *
     * @param method     the inbound request method, any token; an extension method such as {@code PROPFIND} is sent
     *                   as given
     * @param rawPath    the inbound request path, still percent-encoded, starting with {@code /}
     * @param rawQuery   the inbound query string without {@code ?}, still percent-encoded, or {@code null}
     * @param headers    the headers to forward, or {@code null} for none
     * @param bodyOrNull the request body; {@code null} or empty sends none
     * @return the upstream status, headers and body bytes
     * @throws IllegalArgumentException when the path or query does not form a valid URI
     * @throws IllegalStateException    when {@code method} is TRACE, in any letter case, and {@code bodyOrNull}
     *                                  holds at least one byte; nothing is sent
     * @throws org.springframework.web.client.ResourceAccessException on a timeout, an I/O failure, or a request
     *                                                                the HTTP protocol does not allow
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
        // Nothing is attached for no bytes, so the request factory frames the request by its method: an entity would
        // add Content-Length: 0 to a GET, and the client refuses any entity on a TRACE.
        if (bodyOrNull != null && bodyOrNull.length > 0) {
            // Set on the transport's request rather than through body(...): RestClient copies a body(Body) into a
            // buffer it declares not repeatable, so the client would follow no redirect of a request with a body.
            // Never body(byte[]) either: the byte-array converter would add Content-Type: application/octet-stream
            // to a request whose caller sent none.
            spec.headers(h -> h.setContentLength(bodyOrNull.length))
                    .httpRequest(request -> setRepeatableBody(request, bodyOrNull));
        }
        return spec.exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .body(StreamUtils.copyToByteArray(response.getBody())));
    }

    /**
     * Streams {@code body} as the request body, declared repeatable because the same bytes can be written again,
     * so a 307 or 308 redirect re-sends them and a redirect that turns the request into a GET is followed.
     */
    private static void setRepeatableBody(ClientHttpRequest request, byte[] body) {
        if (!(request instanceof StreamingHttpOutputMessage streaming)) {
            throw new IllegalStateException("The request factory must stream request bodies, got "
                    + request.getClass().getName());
        }
        streaming.setBody(new StreamingHttpOutputMessage.Body() {
            @Override
            public void writeTo(OutputStream out) throws IOException {
                out.write(body);
            }

            @Override
            public boolean repeatable() {
                return true;
            }
        });
    }

    /**
     * Closes every pooled connection, idle or in use, without a reset, and stops the write-deadline thread once
     * its pending deadlines have run; an exchange still in progress fails with
     * {@link org.springframework.web.client.ResourceAccessException}. Spring calls it when the application context
     * shuts down, as for every {@link AutoCloseable} bean; no exchange may start afterwards.
     */
    @Override
    public void close() {
        httpClient.close(CloseMode.GRACEFUL);
        writeDeadlines.shutdown();
    }

    /**
     * The header policy for redirects, which httpclient5 does not apply itself. A hop starts from every header of
     * the original request, so a request that a redirect turns into a GET keeps them where httpclient5 would send
     * none. From the first hop whose origin (scheme, host and port, RFC 6454) differs from the original request's,
     * {@link #CROSS_ORIGIN_REDIRECT_DROPPED_HEADERS} are removed on that hop and every later one, so the caller's
     * credentials never reach another origin, nor return through it. The first hop is the original request itself
     * and passes unchanged.
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
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    /**
     * httpclient5's redirect rules, except that a redirect from an {@code https} URL to an {@code http} one is not
     * followed: its response, {@code Location} included, is returned as received, and nothing is sent to the
     * {@code http} URL, neither the caller's headers nor a body a 307 or 308 would re-send. This is what
     * {@code java.net.http.HttpClient.Redirect.NORMAL} refuses.
     */
    private static final class NoDowngradeRedirectStrategy extends DefaultRedirectStrategy {

        @Override
        public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context)
                throws ProtocolException {
            if (!super.isRedirected(request, response, context)) {
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
     * httpcore5's request line and header formatting for connections that encode in ISO-8859-1. Each formatted
     * line keeps HTAB, U+0020 to U+007E and U+0080 to U+00FF, which that encoding writes as the same byte, so
     * obs-text passes unchanged; every other character becomes {@code ?}, as httpcore5 writes it without a charset,
     * so no control character, DEL included, reaches the wire. CR, LF, FF and VT in a header value have already
     * become SP.
     */
    private static final class ObsTextLineFormatter extends BasicLineFormatter {

        @Override
        public void formatRequestLine(CharArrayBuffer buffer, RequestLine line) {
            CharArrayBuffer formatted = new CharArrayBuffer(128);
            super.formatRequestLine(formatted, line);
            appendWritable(buffer, formatted);
        }

        @Override
        public void formatHeader(CharArrayBuffer buffer, Header header) {
            CharArrayBuffer formatted = new CharArrayBuffer(128);
            super.formatHeader(formatted, header);
            appendWritable(buffer, formatted);
        }

        private static void appendWritable(CharArrayBuffer buffer, CharArrayBuffer formatted) {
            Objects.requireNonNull(buffer, "Char array buffer");
            for (int i = 0; i < formatted.length(); i++) {
                char c = formatted.charAt(i);
                boolean writable = c == '\t' || (c >= 0x20 && c <= 0x7E) || (c >= 0x80 && c <= 0xFF);
                buffer.append(writable ? c : '?');
            }
        }
    }

    /**
     * Gives every write of a request body on this hop the deadline a socket write lacks: the body is handed to the
     * connection in slices of at most {@link #WRITE_SLICE_BYTES}, and a slice still blocked after the timeout
     * discards the connection, which fails the write with a {@link SocketTimeoutException}.
     */
    private ClassicHttpResponse boundBodyWrites(ClassicHttpRequest request, ExecChain.Scope scope, ExecChain chain)
            throws IOException, HttpException {
        HttpEntity entity = request.getEntity();
        if (entity != null) {
            request.setEntity(new WriteDeadlineEntity(entity, scope.execRuntime));
        }
        return chain.proceed(request, scope);
    }

    /** A request entity whose writes, and the final flush of their bytes, run under the write deadline. */
    private final class WriteDeadlineEntity extends HttpEntityWrapper {

        private final ExecRuntime execRuntime;

        WriteDeadlineEntity(HttpEntity entity, ExecRuntime execRuntime) {
            super(entity);
            this.execRuntime = execRuntime;
        }

        @Override
        public void writeTo(OutputStream connectionOut) throws IOException {
            OutputStream bounded = new FilterOutputStream(connectionOut) {
                @Override
                public void write(int b) throws IOException {
                    withinDeadline(() -> connectionOut.write(b));
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    Objects.checkFromIndexSize(offset, length, bytes.length);
                    for (int start = offset, end = offset + length; start < end; start += WRITE_SLICE_BYTES) {
                        int from = start;
                        int count = Math.min(WRITE_SLICE_BYTES, end - start);
                        withinDeadline(() -> connectionOut.write(bytes, from, count));
                    }
                }

                @Override
                public void flush() throws IOException {
                    withinDeadline(connectionOut::flush);
                }
            };
            super.writeTo(bounded);
            // The connection's buffer still holds the last bytes; flush them under the deadline too.
            bounded.flush();
        }

        private void withinDeadline(SocketWrite write) throws IOException {
            ScheduledFuture<?> deadline;
            try {
                deadline = writeDeadlines.schedule(execRuntime::discardEndpoint, timeoutMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // Only a closed client rejects a deadline; its connections are closed too.
                throw new IOException("BoxProxyClient is closed", e);
            }
            IOException failure = null;
            try {
                write.run();
            } catch (IOException e) {
                failure = e;
            } finally {
                // A deadline that can no longer be cancelled has fired and discarded the connection.
                if (!deadline.cancel(false)) {
                    SocketTimeoutException timedOut =
                            new SocketTimeoutException("Request body write timed out after " + timeoutMs + " ms");
                    if (failure != null) {
                        timedOut.initCause(failure);
                    }
                    failure = timedOut;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** One write or flush on the connection's output stream. */
    @FunctionalInterface
    private interface SocketWrite {
        void run() throws IOException;
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
