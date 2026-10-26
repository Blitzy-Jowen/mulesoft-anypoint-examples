/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecRuntime;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.protocol.RedirectLocations;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.URIAuthority;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

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
 * {@code http} one. Posts travel over httpclient5, and {@code proxy.response-timeout-ms} bounds the connect, the TLS
 * handshake, every write of the request body, the wait for the response status and headers, and every wait for
 * response body bytes, so an upstream that stalls at any point, after its headers included, cannot hold the calling
 * thread longer than that. A timeout, a refused connection, a DNS failure or any other I/O failure, one while the
 * response body is read included, surfaces as the {@link org.springframework.web.client.ResourceAccessException}
 * {@link RestClient} throws, and the connection it happened on is discarded.
 *
 * <p>Connections are pooled without limit, as the Mule requester's default {@code maxConnections} of -1 allowed, are
 * checked before each reuse so that one the upstream has closed is replaced by a new one rather than failing the post,
 * and are released by {@link #close()} when the application context shuts down.
 *
 * <p>The upstream WSDL of {@link #wsdlResource()} is the one document the class adapts: {@code WsdlQueryPublisher},
 * which publishes it at {@code ?wsdl}, neither resolves nor rewrites a reference location carrying a URI scheme, and
 * keys every schema of the closure by its location exactly as written. An {@code http} or {@code https} WSDL and
 * every schema it reaches are therefore handed over in one of two modes. The WSDL, and every document reached from
 * it through relative locations only, is read as written: its relative locations stay as written, CXF's
 * {@code ?xsd=<location>} form, and each absolute {@code http} or {@code https} {@code xsd:import},
 * {@code xsd:include}, {@code xsd:redefine} or {@code wsdl:import} location, the form JAX-WS WSDLs import their
 * schemas with, is replaced by the scheme-less key {@code ./<that URL>}, which the resource resolves back to that URL.
 * A document reached through such an absolute location or key, and every document it reaches, is read resolved: each
 * of its scheme-less locations is replaced as well, by the key of the URL it denotes, so two such documents that spell
 * one relative location alike never share a key. The whole schema closure is thereby served at {@code ?xsd=<key>}
 * with every reference pointing at the proxy.
 *
 * <p>The original flow logs nothing, adds no authentication and keeps no state, and neither does this class: no
 * cookie, credential or authentication result is kept from one post to the next, and the caller's headers,
 * {@code Authorization} and any SOAP security material included, reach the upstream as given. Instances are
 * thread-safe.
 */
@Component
public class ShopServiceClient implements AutoCloseable {

    private static final String HTTP_PREFIX = "http:";

    private static final String HTTPS_PREFIX = "https:";

    /**
     * Largest slice of request body handed to the connection under one write deadline: httpclient5's default
     * session buffer, so each slice reaches the socket in at most one write and the deadline measures progress.
     */
    private static final int WRITE_SLICE_BYTES = 8192;

    /** Headers a redirect never carries to another origin; the client sets {@code Host} for the new target. */
    private static final List<String> CROSS_ORIGIN_REDIRECT_DROPPED_HEADERS =
            List.of("Authorization", "Cookie", "Origin", "Referer", "Host");

    /** Exchange attribute set once a redirect has left the original request's origin. */
    private static final String LEFT_ORIGIN_ATTRIBUTE = ShopServiceClient.class.getName() + ".leftOrigin";

    private final ResourceLoader resourceLoader;

    private final URI upstreamUri;

    private final String wsdlLocation;

    private final long responseTimeoutMs;

    private final ScheduledThreadPoolExecutor writeDeadlines;

    private final CloseableHttpClient httpClient;

    private final RestClient restClient;

    /**
     * Builds the client once; the resulting {@link RestClient} and its connection pool are shared by every request.
     *
     * @param resourceLoader    resolves {@code classpath:}, {@code file:} and plain WSDL locations
     * @param upstreamUrl       absolute {@code http} or {@code https} URL every envelope is posted to, used verbatim
     *                          ({@code proxy.upstream-url})
     * @param wsdlLocation      location of the upstream WSDL ({@code proxy.wsdl-location})
     * @param responseTimeoutMs in milliseconds, greater than zero: for a post, the limit on the connect, the TLS
     *                          handshake, each request body write, the wait for the response status and headers and
     *                          every wait for response body bytes; for a WSDL read, the connect and read timeout
     *                          ({@code proxy.response-timeout-ms})
     * @throws IllegalArgumentException when an argument is missing, the URL is not an absolute {@code http} or
     *                                  {@code https} URL with a host, or the timeout is not positive, so the
     *                                  application fails to start; the message names the key and never quotes the
     *                                  URL, which may carry credentials, and no cause quoting it is attached
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
        String invalidUpstreamUrl = "proxy.upstream-url must be an absolute http or https URL with a host";
        URI uri;
        try {
            uri = URI.create(upstreamUrl);
        } catch (IllegalArgumentException ex) {
            // Not attached as the cause: its message, and its URISyntaxException's, quote the URL as configured,
            // user information and query included.
            throw new IllegalArgumentException(invalidUpstreamUrl + ": " + ex.getClass().getName());
        }
        Assert.isTrue(uri.getHost() != null && isHttpUrl(upstreamUrl), invalidUpstreamUrl);

        this.resourceLoader = resourceLoader;
        this.upstreamUri = uri;
        this.wsdlLocation = wsdlLocation;
        this.responseTimeoutMs = responseTimeoutMs;

        // One daemon thread, started by the first body write: it closes a connection whose write makes no progress.
        this.writeDeadlines = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "shop-service-write-deadline");
            thread.setDaemon(true);
            return thread;
        });
        this.writeDeadlines.setRemoveOnCancelPolicy(true);

        Timeout limit = Timeout.of(Duration.ofMillis(responseTimeoutMs));
        // httpclient5's default of five connections per route would queue concurrent proxied calls. An https
        // upstream is reached with the JVM's default TLS context, SSLContext.getDefault(), so the javax.net.ssl
        // system properties apply to it.
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                        .setSslContext(SSLContexts.createSystemDefault())
                        .build())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(limit)
                        .setSocketTimeout(limit)
                        // No retry, so stale-check every lease: a connection the upstream closed is never leased.
                        .setValidateAfterInactivity(TimeValue.ZERO_MILLISECONDS)
                        .build())
                .setDefaultTlsConfig(TlsConfig.custom().setHandshakeTimeout(limit).build())
                .setMaxConnTotal(Integer.MAX_VALUE)
                .setMaxConnPerRoute(Integer.MAX_VALUE)
                .build();
        // httpclient5 classic speaks HTTP/1.1 only, so no request offers h2c, which the Mule HTTP/1.1 requester never
        // did. No request is ever re-sent: automatic retries and authentication challenge handling are off. The
        // response timeout is a socket timeout, so it bounds the wait for the response head and every wait for body
        // bytes; on expiry the read fails with SocketTimeoutException, httpclient5 discards the connection, and
        // RestClient closes the response and throws ResourceAccessException. Body writes get the same bound from
        // boundBodyWrites, because a socket write has no timeout of its own. Content compression would add
        // Accept-Encoding and decompress the response, a cookie store would replay one caller's cookies to the next,
        // and an auth cache would keep state across callers, so all three are off.
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
                        ShopServiceClient::applyRedirectHeaderPolicy)
                .addExecInterceptorAfter(ChainElement.REDIRECT.name(), "body-write-deadline", this::boundBodyWrites)
                .build();
        // No factory timeout is set, so the client's default request configuration above applies to every post.
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
        // The static builder rather than Boot's RestClient.Builder: no default headers, base URL or customizer may
        // touch a proxied request, and tests construct the class without a context. No status handler is configured
        // because post() uses exchange(), which applies none, so no status is ever turned into an exception.
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Posts one SOAP envelope to the upstream and returns its response as received
     * ({@code http:request method="POST" path="shop/ShopService"}, {@code soap-api-proxy.xml:10}).
     *
     * <p>Every header in {@code headers} is forwarded in its order with all its values, {@code Authorization},
     * {@code Expect} and {@code Upgrade} included; several {@code Cookie} values travel as one {@code Cookie} header
     * joined with {@code "; "}, as RFC 6265 requires. {@code Content-Length} and {@code Transfer-Encoding} in
     * {@code headers} are ignored: the client writes the framing itself, a {@code Content-Length} of the length of
     * {@code body}. It also adds {@code Host}, {@code Connection: keep-alive} and its own {@code User-Agent} when
     * {@code headers} has none, so the caller removes an inbound {@code Host} and {@code Connection} first. No
     * {@code Content-Type} is added: a request without one is sent without one. The body bytes are sent unchanged.
     *
     * <p>{@code Expect: 100-continue} keeps its protocol behaviour: the body follows the upstream's
     * {@code 100 Continue}, or a three-second wait for it, and is withheld when the upstream answers with a 4xx or
     * 5xx status first. {@code Upgrade} travels as a plain header: the exchange stays HTTP/1.1, never offers
     * {@code h2c}, and switches no protocol.
     *
     * <p>Exactly one request is sent; a redirect the client follows is part of that exchange. A followed redirect
     * carries every header of the original request while it stays on the original origin, and none of
     * {@code Authorization}, {@code Cookie}, {@code Origin}, {@code Referer} or {@code Host} once it has left it. A
     * 307 or 308 re-sends the POST with its body; a 301, 302 or 303 continues as a GET without the body. A redirect
     * from an {@code https} URL to an {@code http} one is not followed and is returned as received; nothing is sent
     * to the {@code http} URL. The upstream status, whatever it is, is returned as a value, never thrown, and
     * nothing is retried.
     *
     * @param headers the headers to forward, already mapped by the caller
     * @param body    the envelope bytes; an empty array sends an empty body with {@code Content-Length: 0}
     * @return the upstream status, its headers, and its body bytes ({@code byte[0]} when it sent none); the
     *         headers are a copy that outlives the exchange, read-only as every {@link ResponseEntity}'s are
     * @throws IllegalArgumentException when {@code headers} or {@code body} is {@code null}
     * @throws org.springframework.web.client.ResourceAccessException on a timeout, a refused connection, a DNS
     *                                                                failure, any other I/O failure, or a
     *                                                                redirect the client refuses to follow, such
     *                                                                as a circular one
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
                    // The request entity declares this length, so the client frames the body with it, never chunked.
                    h.setContentLength(body.length);
                })
                // Set on the transport's request rather than through body(...): the byte-array converter would add
                // Content-Type: application/octet-stream to a request whose caller sent none.
                .httpRequest(request -> setRepeatableBody(request, body))
                .exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                        .headers(response.getHeaders())
                        .body(StreamUtils.copyToByteArray(response.getBody())));
    }

    /**
     * Closes every pooled connection, idle or in use, without a reset, and stops the write-deadline thread once
     * its pending deadlines have run; a post still in progress fails with
     * {@link org.springframework.web.client.ResourceAccessException}. Spring calls it when the application context
     * shuts down, as for every {@link AutoCloseable} bean; no post may start afterwards. {@link #wsdlResource()}
     * does not use the pool and is unaffected.
     */
    @Override
    public void close() {
        httpClient.close(CloseMode.GRACEFUL);
        writeDeadlines.shutdown();
    }

    /**
     * Streams {@code body} as the request body, declared repeatable because the same bytes can be written again,
     * so a 307 or 308 redirect re-sends them and a redirect that turns the post into a GET is followed: httpclient5
     * follows no redirect of a request whose body is not repeatable.
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
     * The header policy for redirects, which httpclient5 does not apply itself. A hop starts from every header of
     * the original request, so a post that a redirect turns into a GET keeps them where httpclient5 would send
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
        // An omitted port is the scheme's default one, resolved as httpclient5 resolves it for the connection.
        return schemeA.equalsIgnoreCase(schemeB)
                && authorityA.getHostName().equalsIgnoreCase(authorityB.getHostName())
                && DefaultSchemePortResolver.INSTANCE.resolve(schemeA, authorityA)
                        == DefaultSchemePortResolver.INSTANCE.resolve(schemeB, authorityB);
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
                deadline = writeDeadlines.schedule(execRuntime::discardEndpoint, responseTimeoutMs,
                        TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // Only a closed client rejects a deadline; its connections are closed too.
                throw new IOException("ShopServiceClient is closed", e);
            }
            IOException failure = null;
            try {
                write.run();
            } catch (IOException e) {
                failure = e;
            } finally {
                // A deadline that can no longer be cancelled has fired and discarded the connection.
                if (!deadline.cancel(false)) {
                    SocketTimeoutException timedOut = new SocketTimeoutException(
                            "Request body write timed out after " + responseTimeoutMs + " ms");
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

    /**
     * The upstream WSDL ({@code cxf:proxy-service wsdlLocation}, {@code soap-api-proxy.xml:7}), which the
     * application reads once at start-up to publish it at {@code ?wsdl}, so an unreachable WSDL fails start-up as
     * the CXF deployment did.
     *
     * <p>For an {@code http} or {@code https} location every connection, the {@code GET} of {@link Resource#exists()}
     * and of {@link Resource#getInputStream()} alike, has {@code proxy.response-timeout-ms} as connect and read
     * timeout and is never retried; the schemas it imports through {@link Resource#createRelative(String)} are read
     * the same way. The resource reads the WSDL as written: its relative reference locations stay as written, and
     * each absolute {@code http} or {@code https} one is replaced by the key {@code ./<that URL>}, which
     * {@link Resource#createRelative(String)} resolves back to that URL. A schema reached through relative locations
     * only is read the same way. One reached through an absolute location or a key, and every schema it reaches, is
     * read resolved: every scheme-less reference location is replaced too, by the key of the URL it denotes. So
     * {@code WsdlQueryPublisher} serves and rewrites the whole closure under keys that each denote one document (see
     * {@link TimeoutUrlResource}). Every other location ({@code classpath:}, {@code file:}, a plain path) is the
     * resource the {@link ResourceLoader} returns for it, read unchanged.
     *
     * @return the resource holding the upstream WSDL
     * @throws IllegalStateException when an {@code http} or {@code https} location is not a valid URL; the message
     *                               names the key and the type of the parse failure, never the location, which may
     *                               carry credentials, and no cause quoting it is attached
     */
    public Resource wsdlResource() {
        if (!isHttpUrl(wsdlLocation)) {
            return resourceLoader.getResource(wsdlLocation);
        }
        try {
            return new TimeoutUrlResource(URI.create(wsdlLocation).toURL(), responseTimeoutMs,
                    TimeoutUrlResource.Mode.AS_WRITTEN);
        } catch (MalformedURLException | IllegalArgumentException ex) {
            // Not attached as the cause: its message, and its own cause's, quote the location or a part of it.
            throw new IllegalStateException("proxy.wsdl-location is not a valid http or https URL: "
                    + ex.getClass().getName());
        }
    }

    private static boolean isHttpUrl(String location) {
        return location.regionMatches(true, 0, HTTP_PREFIX, 0, HTTP_PREFIX.length())
                || location.regionMatches(true, 0, HTTPS_PREFIX, 0, HTTPS_PREFIX.length());
    }

    /**
     * A {@link UrlResource} for an {@code http} or {@code https} WSDL or schema, adapted to {@code WsdlQueryPublisher}.
     *
     * <ul>
     *   <li>Every connection, the existence probe of {@link #exists()} included, carries the client's connect and
     *       read timeouts.</li>
     *   <li>{@link #getInputStream()} returns the document with its reference locations replaced by keys as its
     *       {@link Mode} prescribes, and {@link #createRelative(String)} resolves a key back to its URL and any other
     *       location as a URI, into this same type in the mode that location calls for, so the adaptation and the
     *       timeouts extend to every document the WSDL reaches.</li>
     *   <li>{@link #getURL()} stays the real upstream URL, the identity under which the publisher reads each document
     *       once; a document reached in both modes is read in the mode of the first reference the publisher follows
     *       to it. {@code equals}, {@code hashCode} and the description are {@link UrlResource}'s, so the mode never
     *       makes one document two.</li>
     * </ul>
     *
     * <p>{@link Mode#AS_WRITTEN} is the mode of the resource {@link #wsdlResource()} returns and of every resource
     * created from an {@code AS_WRITTEN} one through a relative location that is not a key. Only its absolute
     * {@code http} and {@code https} locations, and its relative ones of the key's form, are replaced; every other
     * relative location stays exactly as written, so the publisher serves it at {@code ?xsd=<location as written>},
     * CXF's form. {@link Mode#RESOLVED} is the mode of every resource created through an absolute {@code http} or
     * {@code https} location or a key, and of every resource created from a {@code RESOLVED} one. Each of its
     * non-blank locations without a scheme is replaced as well, by {@code ./} followed by the URL
     * {@link URI#resolve(String)} makes of it against this URL, as {@link #createRelative(String)} resolves it. A
     * key therefore denotes one URL across the whole closure, so two documents that spell one relative location alike
     * never share a key. Every key begins with {@code ./http:} or {@code ./https:} in some letter case, a form an
     * {@code AS_WRITTEN} document keeps only where it cannot be resolved, which {@link #createRelative(String)}
     * rejects, so no key clashes with a location kept as written either. In both modes a blank location, and one with
     * a scheme other than {@code http} or {@code https}, stays as written; the publisher follows neither.
     *
     * <p>The key of an absolute location is {@code ./} followed by the location exactly as written, for example
     * {@code ./http://host/shop/ShopService?xsd=1}. Beginning with {@code .}, it never looks like a URI scheme, so the
     * publisher resolves it through {@link #createRelative(String)} and serves it at {@code ?xsd=<key>}. It carries
     * the whole URL, scheme included, so it denotes one resource whichever document refers to it, and one location
     * always yields one key; a network-path key such as {@code //host/path} would not, as it denotes different URLs
     * under an {@code http} and an {@code https} referrer. Read as a URI reference, {@code ./http://...} is a relative
     * path whose first segment is {@code http:}, so a document's own reference of that form is replaced by the key of
     * the URL it resolves to, and every key a document holds denotes what its reference does.
     *
     * <p>A failure this resource raises names a URL or a reference location only in its diagnostic form, the one
     * {@code WsdlQueryPublisher} names resources by: user information removed, everything from the first {@code ?}
     * or {@code #} omitted, and the rest escaped onto one line and bounded in length. Where the underlying failure's
     * message quotes the location, or a part of it, raw, as those of {@link URI#create(String)},
     * {@link URI#resolve(String)} and {@link URI#toURL()} do, the failure names its type only and does not carry it as
     * its cause.
     */
    static final class TimeoutUrlResource extends UrlResource {

        /** Prefix of every key, which is followed by the absolute {@code http} or {@code https} URL it stands for. */
        private static final String KEY_PREFIX = "./";

        /** Longest diagnostic text, escapes included, before {@link #TRUNCATED} replaces the rest. */
        private static final int DIAGNOSTIC_LIMIT = 256;

        /** Appended where {@link #escapedAndBounded(String)} cuts a diagnostic text short. */
        private static final String TRUNCATED = "...(truncated)";

        /** URL user information, in a nested URL ({@code jar:http://user:password@host/...}) as well. */
        private static final Pattern USER_INFO = Pattern.compile("//[^/]*@");

        private static final char[] HEX = "0123456789ABCDEF".toCharArray();

        /** A location carrying a URI scheme, which the publisher neither resolves nor rewrites; matched as it does. */
        private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

        private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";

        private static final String SCHEMA_LOCATION = "schemaLocation";

        private static final String LOCATION = "location";

        /** Rejects errors as well as fatal errors, which {@link DefaultHandler} alone rejects; warnings pass. */
        private static final DefaultHandler REJECTING_ERRORS = new RejectingErrorHandler();

        /** How a document's reference locations are replaced by keys; see the class description. */
        enum Mode {

            /**
             * Absolute {@code http} and {@code https} locations, and relative ones of the key's form, become keys;
             * every other relative location stays as written.
             */
            AS_WRITTEN,

            /** Every non-blank location without a scheme becomes a key too, that of the URL it denotes. */
            RESOLVED
        }

        private final long timeoutMs;

        private final Mode mode;

        TimeoutUrlResource(URL url, long timeoutMs, Mode mode) {
            super(url);
            this.timeoutMs = timeoutMs;
            this.mode = Objects.requireNonNull(mode, "mode");
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
         * The document, read with one {@code GET} under the timeouts, with each {@code schemaLocation} of an
         * {@code xsd:import}, {@code xsd:include} or {@code xsd:redefine} and {@code location} of a {@code wsdl:import}
         * replaced by its key where this resource's {@link Mode} replaces it: an absolute {@code http} or
         * {@code https} one in either mode, a relative one in {@link Mode#RESOLVED} only, apart from a relative one of
         * the key's form. Reference elements are matched by namespace URI and local name, never by prefix, as the
         * publisher matches them.
         *
         * <p>A document in which nothing is replaced is returned with its exact bytes, and so is one the hardened
         * parser refuses (not well-formed, or declaring a DOCTYPE), which the publisher then reports itself. A
         * changed document is serialized as UTF-8 without added indentation.
         *
         * @throws IOException when the document cannot be read, the timeouts included
         */
        @Override
        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(adaptedContent());
        }

        /**
         * The length of the bytes {@link #getInputStream()} returns, read with {@code GET}. {@link UrlResource} asks
         * with {@code HEAD} instead, which the original never sent and which reports the unadapted length.
         */
        @Override
        public long contentLength() throws IOException {
            return adaptedContent().length;
        }

        private byte[] adaptedContent() throws IOException {
            byte[] upstream;
            try (InputStream in = super.getInputStream()) {
                upstream = in.readAllBytes();
            }
            Document document;
            try {
                document = parse(upstream);
            } catch (SAXException ex) {
                return upstream;
            }
            boolean replaced = false;
            NodeList elements = document.getElementsByTagNameNS("*", "*");
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);
                String attribute = referenceAttribute(element);
                if (attribute == null || !element.hasAttributeNS(null, attribute)) {
                    continue;
                }
                String key = keyFor(element.getAttributeNS(null, attribute));
                if (key != null) {
                    element.setAttributeNS(null, attribute, key);
                    replaced = true;
                }
            }
            return replaced ? serialize(document) : upstream;
        }

        /**
         * The key that replaces {@code location}, or {@code null} when it stays as written. An absolute {@code http}
         * or {@code https} location yields its own key in either mode; a blank one, or one with any other scheme,
         * stays as written. Any other location is relative and yields the key of the URL it resolves to against this
         * URL: in {@link Mode#RESOLVED} every one, in {@link Mode#AS_WRITTEN} only one already of the key's form. One
         * that cannot be resolved stays as written and is rejected by {@link #createRelative(String)}, as any
         * unresolvable location is.
         */
        private String keyFor(String location) {
            if (isHttpUrl(location)) {
                return KEY_PREFIX + location;
            }
            if (location.isBlank() || URI_SCHEME.matcher(location).find()) {
                return null;
            }
            if (mode == Mode.AS_WRITTEN && keyTarget(location) == null) {
                return null;
            }
            try {
                return KEY_PREFIX + getURL().toURI().resolve(location);
            } catch (URISyntaxException | IllegalArgumentException ex) {
                return null;
            }
        }

        /** The absolute {@code http} or {@code https} location {@code location} is the key of, else {@code null}. */
        private static String keyTarget(String location) {
            if (!location.startsWith(KEY_PREFIX)) {
                return null;
            }
            String target = location.substring(KEY_PREFIX.length());
            return isHttpUrl(target) ? target : null;
        }

        /**
         * The attribute carrying the location of a reference element, or {@code null} for any other element: the
         * reference kinds {@code WsdlQueryPublisher} follows and rewrites.
         */
        private static String referenceAttribute(Element element) {
            String namespace = element.getNamespaceURI();
            String localName = element.getLocalName();
            if (XMLConstants.W3C_XML_SCHEMA_NS_URI.equals(namespace)
                    && ("import".equals(localName) || "include".equals(localName) || "redefine".equals(localName))) {
                return SCHEMA_LOCATION;
            }
            if (WSDL_NS.equals(namespace) && "import".equals(localName)) {
                return LOCATION;
            }
            return null;
        }

        /**
         * Parses with the hardening {@code WsdlQueryPublisher} applies: namespace-aware, with DOCTYPE declarations,
         * external entities, external DTDs and XInclude disabled and secure processing on.
         */
        private static Document parse(byte[] bytes) throws SAXException, IOException {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            DocumentBuilder builder;
            try {
                factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                builder = factory.newDocumentBuilder();
            } catch (ParserConfigurationException ex) {
                throw new IllegalStateException("Cannot configure a secure XML parser", ex);
            }
            builder.setErrorHandler(REJECTING_ERRORS);
            return builder.parse(new ByteArrayInputStream(bytes));
        }

        /**
         * Serializes as UTF-8 without added indentation, keeping the document's own whitespace;
         * {@code setXmlStandalone(true)} keeps {@code standalone="no"} out of the declaration.
         */
        private byte[] serialize(Document document) throws IOException {
            document.setXmlStandalone(true);
            try {
                TransformerFactory factory = TransformerFactory.newDefaultInstance();
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                Transformer transformer = factory.newTransformer();
                transformer.setOutputProperty(OutputKeys.METHOD, "xml");
                transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
                transformer.setOutputProperty(OutputKeys.INDENT, "no");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                transformer.transform(new DOMSource(document), new StreamResult(out));
                return out.toByteArray();
            } catch (TransformerConfigurationException ex) {
                throw new IllegalStateException("Cannot configure a secure XML serializer", ex);
            } catch (TransformerException ex) {
                // The cause stays attached: the source has no system identifier, so its message quotes no URL.
                throw new IOException("Cannot serialize the adapted document of " + safeUrl(), ex);
            }
        }

        /**
         * An absolute {@code http} or {@code https} location is kept exactly as written, the form JAX-WS-generated
         * WSDLs import their schemas with ({@code http://host/shop/ShopService?xsd=1}); any other location is
         * resolved against this URL by {@link URI#resolve(String)}. {@link UrlResource#createRelative(String)} is not
         * used because it appends every location, an absolute one included, to this URL's directory.
         *
         * <p>A key, {@code ./} followed by an absolute {@code http} or {@code https} URL, stands for that URL
         * exactly, so {@code ./http://host/shop/ShopService?xsd=1} resolves to
         * {@code http://host/shop/ShopService?xsd=1} whichever document holds it. {@code ./} followed by anything
         * else is an ordinary relative location.
         *
         * <p>The resource returned is in {@link Mode#RESOLVED} when the location is absolute or a key, or when this
         * resource is; otherwise it is in {@link Mode#AS_WRITTEN}, as this resource is.
         *
         * <p>Only {@code http} and {@code https} results are followed: a WSDL fetched from the network must not make
         * the application read a {@code file:} or {@code jar:} location and publish its content as a schema.
         *
         * @throws MalformedURLException when the location is not a valid URI reference or resolves to a scheme other
         *                               than {@code http} or {@code https}; the message names the location and this
         *                               URL in their diagnostic form (see the class description) and, for a location
         *                               that cannot be parsed, the type of the parse failure, and no cause quoting
         *                               either is attached
         */
        @Override
        public Resource createRelative(String relativePath) throws MalformedURLException {
            String absolute = isHttpUrl(relativePath) ? relativePath : keyTarget(relativePath);
            URI target;
            try {
                target = absolute != null
                        ? URI.create(absolute)
                        : getURL().toURI().resolve(relativePath);
            } catch (URISyntaxException | IllegalArgumentException ex) {
                throw unresolvable(relativePath, ex);
            }
            if (!isHttpUrl(target.toString())) {
                throw new MalformedURLException("'" + safeLocation(relativePath) + "' referenced by " + safeUrl()
                        + " is not an http or https location");
            }
            try {
                return new TimeoutUrlResource(target.toURL(), timeoutMs, absolute != null ? Mode.RESOLVED : mode);
            } catch (MalformedURLException | IllegalArgumentException ex) {
                throw unresolvable(relativePath, ex);
            }
        }

        /**
         * The failure for {@code relativePath} when it cannot be resolved against this URL or made a URL. Only the
         * type of {@code parseFailure} is named, and it is not attached as the cause: its message, or its own
         * cause's, quotes the location, or a part of it, raw, user information, query and line breaks included.
         */
        private MalformedURLException unresolvable(String relativePath, Exception parseFailure) {
            return new MalformedURLException("Cannot resolve '" + safeLocation(relativePath) + "' against "
                    + safeUrl() + ": " + parseFailure.getClass().getName());
        }

        /** This resource's URL in its diagnostic form, as {@link #safeLocation(String)} gives it. */
        private String safeUrl() {
            return safeLocation(getURL().toExternalForm());
        }

        /**
         * {@code location} as diagnostic text: URL user information (nested URLs included) removed, everything from
         * the first {@code ?} or {@code #} omitted, and the rest escaped and bounded by
         * {@link #escapedAndBounded(String)}. User information is removed first, so a password holding {@code ?} or
         * {@code #} cannot survive the cut. The rule {@code WsdlQueryPublisher} applies to the names of its resources.
         */
        private static String safeLocation(String location) {
            String withoutUserInfo = USER_INFO.matcher(location).replaceAll("//");
            int end = withoutUserInfo.length();
            int query = withoutUserInfo.indexOf('?');
            if (query >= 0) {
                end = query;
            }
            int fragment = withoutUserInfo.indexOf('#');
            if (fragment >= 0 && fragment < end) {
                end = fragment;
            }
            return escapedAndBounded(withoutUserInfo.substring(0, end));
        }

        /**
         * Escapes every character that could break or forge a log line, so the text always stays on one line, and
         * cuts the result at {@link #DIAGNOSTIC_LIMIT} characters, never inside an escape or a surrogate pair.
         */
        private static String escapedAndBounded(String text) {
            StringBuilder out = new StringBuilder(Math.min(text.length(), DIAGNOSTIC_LIMIT) + TRUNCATED.length());
            int index = 0;
            while (index < text.length()) {
                int codePoint = text.codePointAt(index);
                String escaped = escape(codePoint);
                if (out.length() + escaped.length() > DIAGNOSTIC_LIMIT) {
                    return out.append(TRUNCATED).toString();
                }
                out.append(escaped);
                index += Character.charCount(codePoint);
            }
            return out.toString();
        }

        /**
         * {@code \n}, {@code \r} and {@code \t} by name, a backslash doubled so an escape cannot be forged, every
         * other control, line or paragraph separator, format character and unpaired surrogate as Java-style Unicode
         * escapes of its UTF-16 units, and any other code point unchanged.
         */
        private static String escape(int codePoint) {
            String named = switch (codePoint) {
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                case '\\' -> "\\\\";
                default -> null;
            };
            if (named != null) {
                return named;
            }
            int type = Character.getType(codePoint);
            if (!Character.isISOControl(codePoint) && type != Character.LINE_SEPARATOR
                    && type != Character.PARAGRAPH_SEPARATOR && type != Character.FORMAT
                    && type != Character.SURROGATE) {
                return new String(Character.toChars(codePoint));
            }
            StringBuilder escaped = new StringBuilder(12);
            for (char unit : Character.toChars(codePoint)) {
                escaped.append('\\').append('u').append(HEX[(unit >> 12) & 0x0F]).append(HEX[(unit >> 8) & 0x0F])
                        .append(HEX[(unit >> 4) & 0x0F]).append(HEX[unit & 0x0F]);
            }
            return escaped.toString();
        }

        /**
         * Turns a recoverable parser error into a failure, as {@link DefaultHandler} already does for fatal ones, so
         * a document the publisher's parser would refuse is never adapted; warnings do not stop the parse.
         */
        private static final class RejectingErrorHandler extends DefaultHandler {

            @Override
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }
        }
    }
}
