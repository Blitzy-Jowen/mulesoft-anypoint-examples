/**
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
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
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
import org.apache.hc.core5.http.HeaderElements;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.impl.io.HttpRequestExecutor;
import org.apache.hc.core5.http.io.HttpClientConnection;
import org.apache.hc.core5.http.io.HttpResponseInformationCallback;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.URIAuthority;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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

/**
 * Outbound adapter of the {@code /FIM/*} flow {@code http-client-credentialsFlow} in
 * {@code oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml}.
 *
 * <ul>
 *   <li>Line 29, {@code HTTP_Request_Configuration1}: host {@code tfim01.demos.ibm.com}, port 443, HTTPS and no
 *       TLS context, so the JVM's default trust applies. The base URL is the {@code fim.base-url} key.</li>
 *   <li>Line 41, {@code http:request}: the inbound {@code http.request.uri} (path and query string) is the
 *       outbound path, and the inbound {@code http.method} is the outbound method; both are forwarded
 *       verbatim.</li>
 *   <li>Line 43, {@code uri-param client_id=${client.id}}: not handled here. {@code service/FimProxyService}
 *       substitutes the {@code {client_id}} placeholder before calling {@link #send}, so this class receives
 *       the final URI.</li>
 * </ul>
 *
 * <p>The transport is Apache HttpClient 5 under {@link RestClient}, speaking HTTP/1.1 as Mule's requester does,
 * and it sends every request exactly once: automatic retries are disabled, so nothing is re-sent after a refused
 * connect, a connection reset, a connection closed before the response or a timeout, whatever the method, and a
 * host name is connected to at the first address it resolves to only. The connect timeout is 10 s, the Mule
 * requester's default response timeout, and one 10 s deadline, started when the exchange starts, bounds the whole
 * response, headers and body: when it passes, the request is aborted, its connection discarded, and the call fails
 * with an {@link HttpTimeoutException} {@code Response not received within <timeout> ms}. A response outside the
 * requester's default success range 0..399 fails with Mule 3.8's response-validator message
 * {@code Response code <code> mapped as failure.}, carried by a {@link RestClientResponseException}.
 *
 * <p>Redirects are followed, as {@code http:request} follows them by default, up to four in a row; the fifth 3xx
 * in a row is returned as received, as is a redirect from {@code https} to {@code http}, which is never followed.
 * A 307 or 308 re-sends the method and body; a 301 or 302 answering a POST, and a 303 answering any method, HEAD
 * included, continue as a GET without the body, and every later redirect of that exchange is followed as a GET
 * without the body too, as {@code java.net.http} and the Mule requester follow them. Every hop carries the
 * request's headers, except that {@code Authorization}, {@code Cookie}, {@code Origin}, {@code Referer} and
 * {@code Host} are dropped once a redirect leaves the request's origin. A 301, 302, 303, 307 or 308 without
 * {@code Location} fails the call with {@code Invalid redirection}.
 *
 * <p>No cookie is kept, no {@code Accept-Encoding} is sent and no response is decompressed, no authentication
 * challenge is answered, and no {@code Expect} is sent. Connections are pooled without limit, as the Mule
 * requester's default {@code maxConnections} of -1 allowed, use the JVM's default TLS context with host name
 * verification, and are released by {@link #close()} when the application context shuts down. The client is
 * thread-safe.
 */
@Component
public class FimProxyClient implements AutoCloseable {

    /** First status code outside the Mule 3.8 requester's default success status range {@code 0..399}. */
    private static final int FIRST_FAILURE_STATUS = 400;

    /** The Mule HTTP requester's default response timeout. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** Most redirects followed in a row; the next 3xx is returned as received. */
    private static final int MAX_REDIRECTS_FOLLOWED = 4;

    /** Headers a redirect never carries to another origin; the client sets {@code Host} for the new target. */
    private static final List<String> CROSS_ORIGIN_REDIRECT_DROPPED_HEADERS =
            List.of("Authorization", "Cookie", "Origin", "Referer", "Host");

    /** Exchange attribute set once a redirect has left the original request's origin. */
    private static final String LEFT_ORIGIN_ATTRIBUTE = FimProxyClient.class.getName() + ".leftOrigin";

    /** Exchange attribute set once a redirect has turned the exchange into a GET without a body. */
    private static final String CONTINUES_AS_GET_ATTRIBUTE = FimProxyClient.class.getName() + ".continuesAsGet";

    /** Exchange attribute holding the body of the TRACE hop being sent, for the request executor to write. */
    private static final String HELD_TRACE_BODY_ATTRIBUTE = FimProxyClient.class.getName() + ".heldTraceBody";

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

    private final String baseUrl;

    /** Connect timeout, and the bound on each whole response from the start of the exchange to its last body byte. */
    private final Duration timeout;

    /** One daemon thread, started by the first request: it aborts an exchange whose deadline passes. */
    private final ScheduledThreadPoolExecutor deadlines;

    private final CloseableHttpClient httpClient;

    private final RestClient restClient;

    /**
     * Creates the client the application uses, with the 10 s Mule default timeouts.
     *
     * @param baseUrl scheme, host and port of the FIM server, without a trailing slash
     */
    @Autowired
    public FimProxyClient(@Value("${fim.base-url:https://tfim01.demos.ibm.com:443}") String baseUrl) {
        this(baseUrl, DEFAULT_TIMEOUT);
    }

    /**
     * Creates a client for {@code baseUrl} with the given connect and response timeout. Building the transport
     * opens no connection, so construction never contacts a host.
     *
     * @param baseUrl scheme, host and port of the FIM server, without a trailing slash; the raw URI passed to
     *                {@link #send} always begins with {@code /FIM/} and is appended unchanged
     * @param timeout connect timeout, and the time within which each request's whole response, headers and
     *                body, must arrive
     * @throws IllegalStateException when the JVM has no default TLS context
     */
    public FimProxyClient(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl;
        this.timeout = timeout;
        this.deadlines = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "fim-proxy-response-deadline");
            thread.setDaemon(true);
            return thread;
        });
        this.deadlines.setRemoveOnCancelPolicy(true);
        this.httpClient = buildHttpClient(defaultSslContext());
        // Every method token travels as a generic request: Spring's factory rejects tokens outside
        // HttpMethod.values(), such as PROPFIND, and builds TRACE as a request that refuses a body, while the
        // original requester sent whatever method the caller used, with its body.
        HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient) {
            @Override
            protected ClassicHttpRequest createHttpUriRequest(HttpMethod httpMethod, URI uri) {
                return Method.TRACE.isSame(httpMethod.name())
                        ? new TraceRequest(httpMethod.name(), uri)
                        : new HttpUriRequestBase(httpMethod.name(), uri);
            }
        };
        // No factory timeout is set, so the client's default request configuration applies.
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Forwards one request to the FIM server.
     *
     * @param method      the HTTP method token, sent as given
     * @param rawUri      path and query string, percent-encoding included, appended to the base URL verbatim
     * @param contentType the Content-Type to send, or {@code null} to send none
     * @param body        the request body, or {@code null} to send none
     * @return the upstream status, headers and body; the body is {@code null} when the upstream sent none
     * @throws RestClientResponseException when the upstream status is 400 or above, with the message
     *         {@code Response code <code> mapped as failure.} and the upstream status, headers and body
     * @throws org.springframework.web.client.ResourceAccessException on an I/O failure, including a refused or
     *         timed-out connect and a response, headers or body, not complete within the timeout (its cause is
     *         then a {@link HttpTimeoutException}); nothing is retried after one
     */
    public ResponseEntity<byte[]> send(String method, String rawUri, MediaType contentType, byte[] body) {
        // URI.create, not a URI template or builder: the caller's encoding must reach the wire byte for byte.
        RestClient.RequestBodySpec request = restClient.method(HttpMethod.valueOf(method))
                .uri(URI.create(baseUrl + rawUri));
        if (contentType != null) {
            request = request.contentType(contentType);
        }
        if (body != null) {
            // The bytes are set on the transport's request rather than through body(...): the byte-array
            // converter would add Content-Type: application/octet-stream when the caller supplied none, and Mule's
            // requester sends no Content-Type for a message without a MIME type; RestClient would copy a
            // body(Body) into a buffer it declares not repeatable, and httpclient5 follows no redirect of a
            // request whose body cannot be sent again. The explicit length keeps the request non-chunked.
            StreamingHttpOutputMessage.Body requestBody = repeatableBody(body);
            request = request.contentLength(body.length)
                    .httpRequest(clientRequest -> setBody(clientRequest, requestBody));
        }
        // exchange bypasses RestClient's default status handlers and adds no converter-derived Accept header. The
        // transport's deadline bounds the body read below together with the headers.
        return request.exchange((clientRequest, clientResponse) -> {
            byte[] bytes = clientResponse.getBody().readAllBytes();
            HttpHeaders headers = new HttpHeaders();
            headers.addAll(clientResponse.getHeaders());
            HttpStatusCode statusCode = clientResponse.getStatusCode();
            if (statusCode.value() >= FIRST_FAILURE_STATUS) {
                String statusText = clientResponse.getStatusText();
                throw new RestClientResponseException(
                        "Response code " + statusCode.value() + " mapped as failure.",
                        statusCode, statusText == null ? "" : statusText, headers, bytes, charsetOf(headers));
            }
            return ResponseEntity.status(statusCode).headers(headers).body(bytes.length == 0 ? null : bytes);
        });
    }

    /**
     * Closes every pooled connection, idle or in use, and stops the deadline thread once its pending deadlines have
     * run; an exchange still in progress fails with {@link org.springframework.web.client.ResourceAccessException}.
     * Spring calls it when the application context shuts down, as for every {@link AutoCloseable} bean; no exchange
     * may start afterwards.
     */
    @Override
    public void close() {
        httpClient.close(CloseMode.GRACEFUL);
        deadlines.shutdown();
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

    /** The JVM's default TLS context, which {@code HTTP_Request_Configuration1} uses as it declares none. */
    private static SSLContext defaultSslContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The JVM's default TLS context is unavailable", e);
        }
    }

    /**
     * Builds the single-attempt transport. Every wait has the timeout as its bound: the connect, the TLS handshake,
     * the lease of a pooled connection and every socket read, and above them the exchange's deadline. Host names are
     * verified against the server certificate, httpclient5's default.
     */
    private CloseableHttpClient buildHttpClient(SSLContext sslContext) {
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
                .setRequestExecutor(new HeldTraceBodyRequestExecutor())
                // No request is ever re-sent. Content compression would add Accept-Encoding and decompress the
                // upstream body, and a cookie store or an auth cache would keep one caller's state for the next.
                .disableAutomaticRetries()
                .disableContentCompression()
                .disableCookieManagement()
                .disableAuthCaching()
                .setRedirectStrategy(new FollowingRedirectStrategy())
                // First in the chain, so the one deadline spans every redirect hop.
                .addExecInterceptorFirst("response-deadline", this::withinDeadline)
                .addExecInterceptorAfter(ChainElement.REDIRECT.name(), "redirect-header-policy",
                        FimProxyClient::applyRedirectHeaderPolicy)
                .addExecInterceptorAfter("redirect-header-policy", "redirect-method-policy",
                        FimProxyClient::applyRedirectMethodPolicy)
                .addExecInterceptorLast("held-trace-body", FimProxyClient::passHeldTraceBody)
                .build();
    }

    /**
     * Runs one exchange, its redirects included, under the deadline: the response head must arrive before it, and
     * the returned response's body is read under it too. A failure once the deadline has passed is reported as the
     * timeout.
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
                throw new IOException("FimProxyClient is closed", e);
            }
        }

        /** Stops the abort, if it has not run; returns whether the deadline has passed. Safe to call repeatedly. */
        boolean stop() {
            abort.cancel(false);
            return System.nanoTime() - expiresAt >= 0;
        }

        /** {@code failure} reported as the timeout, when the deadline has passed; otherwise {@code failure}. */
        IOException translate(IOException failure) {
            return stop() ? timedOut(failure) : failure;
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
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    /**
     * The method policy for redirects. {@code java.net.http} and the Mule requester derive each hop's method from the
     * hop it answers; httpclient5 derives it from the original request, so it would follow a 303 answering a HEAD
     * with a HEAD, and a hop after one it turned into a GET with the original method and body again. Once a hop is
     * answered by a 303, or by a 301 or 302 while it is a POST, the exchange continues as a GET: every later hop is
     * sent as a GET to its own target with its headers, as {@link #applyRedirectHeaderPolicy} set them, and no body.
     * Until then every hop passes unchanged, so a 307 or 308 re-sends the method and body.
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
     * A TRACE request, in any letter case, whose body is held aside rather than set: httpcore5 refuses to copy a
     * TRACE request with a body, which httpclient5 does before every hop, and to send one. The body reaches the wire
     * through {@link #passHeldTraceBody} and {@link HeldTraceBodyRequestExecutor}, so a TRACE is forwarded with
     * its body as every other method is.
     */
    private static final class TraceRequest extends HttpUriRequestBase {

        private static final long serialVersionUID = 1L;

        private transient HttpEntity heldBody;

        TraceRequest(String method, URI uri) {
            super(method, uri);
        }

        @Override
        public void setEntity(HttpEntity entity) {
            this.heldBody = entity;
        }

        HttpEntity heldBody() {
            return heldBody;
        }
    }

    /**
     * Hands a TRACE hop the body its {@link TraceRequest} holds, for {@link HeldTraceBodyRequestExecutor} to write
     * once httpclient5's request processors have run. A hop a redirect has turned into a GET gets none, and every
     * other request passes unchanged.
     */
    private static ClassicHttpResponse passHeldTraceBody(ClassicHttpRequest request, ExecChain.Scope scope,
                                                         ExecChain chain) throws IOException, HttpException {
        if (!(scope.originalRequest instanceof TraceRequest trace) || trace.heldBody() == null
                || !Method.TRACE.isSame(request.getMethod())) {
            return chain.proceed(request, scope);
        }
        scope.clientContext.setAttribute(HELD_TRACE_BODY_ATTRIBUTE, trace.heldBody());
        try {
            return chain.proceed(request, scope);
        } finally {
            scope.clientContext.removeAttribute(HELD_TRACE_BODY_ATTRIBUTE);
        }
    }

    /**
     * Writes a TRACE request with the body {@link #passHeldTraceBody} passed, framed as httpclient5 frames every
     * other body: by its length, or chunked when the length is unknown.
     */
    private static final class HeldTraceBodyRequestExecutor extends HttpRequestExecutor {

        @Override
        public ClassicHttpResponse execute(ClassicHttpRequest request, HttpClientConnection conn,
                                           HttpResponseInformationCallback informationCallback, HttpContext context)
                throws IOException, HttpException {
            if (request.getEntity() == null
                    && context.getAttribute(HELD_TRACE_BODY_ATTRIBUTE) instanceof HttpEntity held) {
                if (held.isChunked() || held.getContentLength() < 0) {
                    request.setHeader(HttpHeaders.TRANSFER_ENCODING, HeaderElements.CHUNKED_ENCODING);
                } else {
                    request.setHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(held.getContentLength()));
                }
                request.setEntity(held);
            }
            return super.execute(request, conn, informationCallback, context);
        }
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
            return !("https".equalsIgnoreCase(answeredScheme(request, followed))
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
}
