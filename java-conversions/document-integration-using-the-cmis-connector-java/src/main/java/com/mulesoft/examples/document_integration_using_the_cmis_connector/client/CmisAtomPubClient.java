/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.SystemDefaultRoutePlanner;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.protocol.RedirectLocations;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.ProtocolException;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.URIAuthority;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.mulesoft.examples.document_integration_using_the_cmis_connector.config.CmisProperties;

/**
 * CMIS 1.1 AtomPub adapter for the configured repository (OpenKM in the original example).
 *
 * <p>Replaces the connector operation of the original flow {@code CMIS}
 * ({@code cmis-document-integration.xml}):
 * {@code <cmis:create-document-by-path-from-content config-ref="openkm" filename="pic#[System.currentTimeMillis()].jpg"
 * folderPath="/okm:root/" mimeType="image/jpg" objectType="cmis:document" versioningState="NONE"/>}, run by
 * {@code mule-module-cmis} 2.0.0 over Apache Chemistry OpenCMIS with the AtomPub binding. The same wire exchange
 * is made here directly: the service document is read for the repository's {@code objectbypath} URI template,
 * the folder entry is fetched by path for its children feed, and an Atom entry carrying the Base64 content and
 * the {@code cmis:objectTypeId} and {@code cmis:name} properties is posted to that feed. The new document's
 * {@code cmis:objectId} is returned unchanged.
 *
 * <p>OpenCMIS is not used: its client bindings declare a compile dependency on
 * {@code org.apache.cxf:cxf-rt-frontend-jaxws}, which the project's JAX-WS/CXF dependency ban rejects.
 *
 * <p>Repository URL, repository ID, service account, trusted origins and timeouts come only from
 * {@link CmisProperties}. Every request authenticates with HTTP Basic as that service account, so both
 * {@code cmis.username} and {@code cmis.password} are required: when either is blank, {@link #createDocumentByPath}
 * fails with an {@link IllegalStateException} naming the key before any request is made. Construction accepts blank
 * values, so the application starts with the credentials unset.
 *
 * <p>Requests go only to a trusted repository origin: the scheme, host and port, an absent port counting as the
 * scheme's default, of {@code cmis.base-url} or of an entry of {@code cmis.trusted-origins}, scheme and host
 * compared case-insensitively. The service document is read from {@code cmis.base-url} itself. The folder-entry
 * URL the {@code objectbypath} template yields and the create-document URL the children link yields are checked
 * before they are requested: one that does not parse, lacks a scheme or host, has a scheme other than {@code http}
 * or {@code https}, or is on any other origin fails the step with an {@link IllegalStateException}
 * {@code CMIS <step> URL is not on a trusted repository origin (cmis.base-url, cmis.trusted-origins)}, so nothing
 * is sent to it and the service account never reaches a host the repository's responses name. A service document
 * reached through a redirect to another origin therefore yields links that are refused unless that origin is
 * listed in {@code cmis.trusted-origins}. Construction fails with an {@link IllegalArgumentException} naming
 * {@code cmis.trusted-origins} and the index of an entry that is not an {@code http} or {@code https} origin.
 *
 * <p>Failures carry fixed messages naming the step ({@code service document}, {@code folder entry} or
 * {@code create-document}), because the caller's 500 body reports a message verbatim: an error status raises
 * Spring's {@code HttpClientErrorException} (4xx) or {@code HttpServerErrorException} (5xx) with
 * {@code CMIS <step> request failed with HTTP status <code>}; a transport or body-read failure raises
 * {@code ResourceAccessException} with {@code CMIS <step> request timed out} or
 * {@code CMIS <step> request failed with an I/O error}; missing protocol elements, an untrusted URL, an unparseable
 * Content-Type and unreadable XML raise {@link IllegalStateException}. Upstream bodies, reason phrases and header
 * text, request URLs and Java type names are never copied into a message.
 *
 * <p>No call is retried, neither here nor by the transport, so each request is sent once, whether it fails with a
 * timeout, a refused, reset or early-closed connection or an error status. RestClient sends through Apache
 * httpclient5 with its automatic retries switched off, because the JDK {@code HttpURLConnection} under Spring's
 * default request factory re-sends a bodiless request, as each GET here is, once when its connection is reset or
 * closes before a response arrives, and has no setting to stop it. Apart from that re-send, {@link #buildHttpClient}
 * keeps every behaviour of the {@code HttpURLConnection} transport: its timeouts, redirects, proxy selection, TLS
 * defaults and absent compression, cookies and authentication handling. The client owns a connection pool, which
 * {@link #close()} releases when the application context shuts down; it holds no other state and is safe to share.
 */
@Component
public class CmisAtomPubClient implements AutoCloseable {

    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String APP = "http://www.w3.org/2007/app";
    private static final String CMIS = "http://docs.oasis-open.org/ns/cmis/core/200908/";
    private static final String CMISRA = "http://docs.oasis-open.org/ns/cmis/restatom/200908/";

    private static final String SERVICE_DOCUMENT_TYPE = "application/atomsvc+xml";
    private static final String ENTRY_TYPE = "application/atom+xml;type=entry";
    private static final MediaType ENTRY_MEDIA_TYPE = MediaType.parseMediaType(ENTRY_TYPE);
    private static final String FEED_TYPE = "application/atom+xml;type=feed";

    /** The fixed {@code atom:id} OpenCMIS writes into every entry it posts; servers assign the real ID. */
    private static final String PLACEHOLDER_ENTRY_ID = "urn:uuid:00000000-0000-0000-0000-00000000000";

    /** Mule's default response timeout, used where a timeout property is unset. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The most redirects one GET follows. {@code HttpURLConnection} sends at most {@code http.maxRedirects}, by JDK
     * default 20, requests per exchange and fails with an I/O error when the last is answered with a redirect too;
     * httpclient5 sends one request more than its limit before failing the same way.
     */
    private static final int MAX_REDIRECTS_FOLLOWED = 19;

    /**
     * How long an idle pooled connection stays reusable when the server's {@code Keep-Alive} header names no
     * timeout: the 5 s the JDK keep-alive cache kept one, so no request is written to a connection idle for longer.
     */
    private static final TimeValue IDLE_CONNECTION_LIFETIME = TimeValue.ofSeconds(5);

    /** The request headers {@code HttpURLConnection} removes from a redirect hop to another host or port. */
    private static final List<String> NEW_DESTINATION_DROPPED_HEADERS = List.of("Authorization", "Cookie", "Cookie2");

    /** Exchange attribute set once a redirect hop has left the original request's host and port. */
    private static final String LEFT_DESTINATION_ATTRIBUTE = CmisAtomPubClient.class.getName() + ".leftDestination";

    /**
     * Fails the parse on errors and fatal errors instead of the JDK default handler, which also prints each one
     * to {@code System.err}; warnings leave the parsed document intact and are not reported.
     */
    private static final ErrorHandler FAIL_ON_ERROR = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) {
            // Intentionally silent: a warning never changes the document that is returned.
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };

    /** Configuration key of the service-account user name. */
    private static final String USERNAME_KEY = "cmis.username";

    /** Configuration key of the service-account password. */
    private static final String PASSWORD_KEY = "cmis.password";

    /** Configuration key of the repository origins trusted besides that of {@code cmis.base-url}. */
    private static final String TRUSTED_ORIGINS_KEY = "cmis.trusted-origins";

    private final CmisProperties properties;

    /** The origins {@code cmis.trusted-origins} lists, trusted besides the origin of {@code cmis.base-url}. */
    private final List<Origin> trustedOrigins;

    private final CloseableHttpClient httpClient;

    private final RestClient restClient;

    /** First unset service-account key, {@code cmis.username} before {@code cmis.password}; null if both are set. */
    private final String missingCredentialKey;

    /**
     * Builds the HTTP client and its connection pool from the connection settings; makes no request. An unset
     * service account or {@code cmis.base-url} is accepted here, so the application starts without it, and is
     * rejected by {@link #createDocumentByPath} instead.
     *
     * @param properties repository URL, repository ID, service account, trusted origins and timeouts
     * @throws IllegalArgumentException when an entry of {@code cmis.trusted-origins} is not an {@code http} or
     *                                  {@code https} origin, before the connection pool is built
     */
    public CmisAtomPubClient(CmisProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.trustedOrigins = trustedOrigins(properties.getTrustedOrigins());
        this.httpClient = buildHttpClient(timeout(properties.getConnectTimeout()),
                timeout(properties.getReadTimeout()));
        // No factory timeout is set, so the client's own configuration governs every exchange.
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient));
        String username = properties.getUsername();
        String password = properties.getPassword();
        if (!StringUtils.hasText(username)) {
            this.missingCredentialKey = USERNAME_KEY;
        } else if (!StringUtils.hasText(password)) {
            this.missingCredentialKey = PASSWORD_KEY;
        } else {
            this.missingCredentialKey = null;
            builder.defaultHeaders(headers -> headers.setBasicAuth(username, password));
        }
        this.restClient = builder.build();
    }

    /**
     * Creates a document from content in the folder at {@code folderPath}, as OpenCMIS
     * {@code createDocument} over AtomPub does.
     *
     * @param folderPath      repository path of the target folder, for example {@code /okm:root/}
     * @param name            document name, written as {@code atom:title} and {@code cmis:name}
     * @param mimeType        media type of the content
     * @param objectTypeId    CMIS object type, for example {@code cmis:document}
     * @param versioningState CMIS versioning state ({@code NONE}, {@code MAJOR}, ...), or null to send none
     * @param content         document bytes
     * @return the {@code cmis:objectId} the repository assigned to the new document
     * @throws IllegalStateException when {@code cmis.username} or {@code cmis.password} is not configured, before
     *                               any request is made, or when the folder-entry or create-document URL the
     *                               repository supplies is not on a trusted repository origin, before it is
     *                               requested
     */
    public String createDocumentByPath(String folderPath, String name, String mimeType, String objectTypeId,
            String versioningState, byte[] content) {
        Objects.requireNonNull(folderPath, "folderPath");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(mimeType, "mimeType");
        Objects.requireNonNull(objectTypeId, "objectTypeId");
        Objects.requireNonNull(content, "content");
        requireServiceAccount();

        String repositoryId = properties.getRepositoryId();
        URI serviceDocumentUri = serviceDocumentUri();
        Origin repositoryOrigin = Origin.of(serviceDocumentUri);
        Document serviceDocument = get(serviceDocumentUri, SERVICE_DOCUMENT_TYPE, "service document");
        String template = objectByPathTemplate(serviceDocument, repositoryId);

        URI folderEntryUri = requireTrustedOrigin(
                discoveredUri(expandObjectByPathTemplate(template, folderPath), "folder entry"),
                repositoryOrigin, "folder entry");
        Document folderEntry = get(folderEntryUri, ENTRY_TYPE, "folder entry");
        URI createUri = requireTrustedOrigin(
                withVersioningState(childrenFeedUri(folderEntry, folderEntryUri, folderPath), versioningState),
                repositoryOrigin, "create-document");

        byte[] created = send(restClient.post()
                .uri(createUri)
                .contentType(ENTRY_MEDIA_TYPE)
                .header(HttpHeaders.ACCEPT, ENTRY_TYPE)
                .body(entry(name, mimeType, objectTypeId, content).getBytes(StandardCharsets.UTF_8)),
                "create-document");
        return objectId(parse(created, "create-document"));
    }

    /** Closes the connection pool and its connections; Spring calls it when the application context shuts down. */
    @Override
    public void close() {
        httpClient.close(CloseMode.GRACEFUL);
    }

    /**
     * A configured timeout as {@code SimpleClientHttpRequestFactory} applied it to {@code HttpURLConnection}: unset
     * is {@link #DEFAULT_TIMEOUT}, the value counts in whole milliseconds, and zero or a negative value means no
     * timeout.
     */
    private static Timeout timeout(Duration configured) {
        int millis = (int) (configured == null ? DEFAULT_TIMEOUT : configured).toMillis();
        return millis > 0 ? Timeout.ofMilliseconds(millis) : Timeout.DISABLED;
    }

    /**
     * Builds the transport, configured so that no request is ever sent twice:
     * <ul>
     *   <li>automatic retries are off, so a request whose connection is refused, reset or closed before or while
     *       the response arrives fails instead of being sent again;</li>
     *   <li>a host name resolves to its first address only, as {@code HttpURLConnection} connected, so a refused or
     *       timed-out connect is not repeated against the host's other addresses;</li>
     *   <li>a pooled connection is checked before every reuse and kept idle no longer than the server's
     *       {@code Keep-Alive} timeout, or {@link #IDLE_CONNECTION_LIFETIME} without one, so one the server closed
     *       is replaced before the request is written rather than failing it, which {@code HttpURLConnection} hid
     *       by re-sending the request.</li>
     * </ul>
     *
     * <p>Everything else is as {@code HttpURLConnection} did it. {@code connectTimeout} bounds the TCP connect and
     * {@code readTimeout} every wait for response bytes, the TLS handshake included, each wait on its own rather
     * than the response as a whole. Only a GET follows redirects, as {@link HttpUrlConnectionRedirectStrategy}
     * describes, and a hop to another host or port carries no {@code Authorization}, {@code Cookie} or
     * {@code Cookie2} header ({@link #dropCredentialsOnNewDestination}). An HTTP proxy is used where the JVM's
     * default {@link java.net.ProxySelector}, read for every route, names one, and {@code https} connects with the
     * JSSE default context, protocols and cipher suites ({@code https.protocols}, {@code https.cipherSuites}).
     * Content compression is off, so no {@code Accept-Encoding} is sent and bodies are read as sent; there is no
     * cookie store, auth cache or connection state, and authentication challenges are not answered, so a
     * {@code 401} is returned as received. The pool has no size limit, as {@code HttpURLConnection} had none, so
     * no exchange waits for another's connection.
     */
    private static CloseableHttpClient buildHttpClient(Timeout connectTimeout, Timeout readTimeout) {
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .useSystemProperties()
                        .setDnsResolver(new FirstAddressDnsResolver(SystemDefaultDnsResolver.INSTANCE))
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(connectTimeout)
                                .setSocketTimeout(readTimeout)
                                .setValidateAfterInactivity(TimeValue.ZERO_MILLISECONDS)
                                .build())
                        .setMaxConnTotal(Integer.MAX_VALUE)
                        .setMaxConnPerRoute(Integer.MAX_VALUE)
                        .build())
                // A null selector reads ProxySelector.getDefault() for every route.
                .setRoutePlanner(new SystemDefaultRoutePlanner(null))
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(readTimeout)
                        .setConnectionKeepAlive(IDLE_CONNECTION_LIFETIME)
                        .setRedirectsEnabled(true)
                        .setMaxRedirects(MAX_REDIRECTS_FOLLOWED)
                        // A redirect loop ends at the redirect limit, as it did for HttpURLConnection, where
                        // httpclient5's loop check would end it at the first repeated location.
                        .setCircularRedirectsAllowed(true)
                        .setAuthenticationEnabled(false)
                        .build())
                .disableAutomaticRetries()
                .disableContentCompression()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableConnectionState()
                .setRedirectStrategy(new HttpUrlConnectionRedirectStrategy())
                .addExecInterceptorAfter(ChainElement.REDIRECT.name(), "new-destination-redirect-headers",
                        CmisAtomPubClient::dropCredentialsOnNewDestination)
                .build();
    }

    /**
     * Removes {@link #NEW_DESTINATION_DROPPED_HEADERS} from every redirect hop, starting with the first whose host
     * or port differs from the original request's, as {@code HttpURLConnection} removed them for the rest of the
     * exchange; the service account's Basic credentials therefore never reach another host. The first hop is the
     * original request itself and passes unchanged.
     */
    private static ClassicHttpResponse dropCredentialsOnNewDestination(ClassicHttpRequest request,
            ExecChain.Scope scope, ExecChain chain) throws IOException, HttpException {
        HttpClientContext context = scope.clientContext;
        if (context.getAttribute(LEFT_DESTINATION_ATTRIBUTE) != null
                || !sameDestination(request, scope.originalRequest)) {
            context.setAttribute(LEFT_DESTINATION_ATTRIBUTE, Boolean.TRUE);
            NEW_DESTINATION_DROPPED_HEADERS.forEach(request::removeHeaders);
        }
        return chain.proceed(request, scope);
    }

    /**
     * Whether both requests go to the same {@link Origin}: scheme, host (case-insensitively) and port, an absent
     * port counting as the scheme's default, as {@code HttpURLConnection} compared destinations.
     */
    private static boolean sameDestination(ClassicHttpRequest a, ClassicHttpRequest b) {
        Origin originA = Origin.of(a);
        return originA != null && originA.sameAs(Origin.of(b));
    }

    private static int effectivePort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    /**
     * The origins {@code configured} lists, in order; null lists none. Each entry, trimmed, must be an absolute
     * {@code http} or {@code https} URI with a host, an optional port from 1 to 65535 and nothing else
     * but an optional {@code /} path: no user information, query or fragment. Any other entry fails construction
     * with an {@link IllegalArgumentException} naming the key and the entry's index but not its value, which a
     * mistaken entry could fill with credentials.
     */
    private static List<Origin> trustedOrigins(List<String> configured) {
        if (configured == null) {
            return List.of();
        }
        List<Origin> origins = new ArrayList<>(configured.size());
        for (int index = 0; index < configured.size(); index++) {
            Origin origin = trustedOrigin(configured.get(index));
            if (origin == null) {
                throw new IllegalArgumentException(TRUSTED_ORIGINS_KEY + "[" + index
                        + "] is not an http or https origin (scheme://host[:port])");
            }
            origins.add(origin);
        }
        return List.copyOf(origins);
    }

    /** The origin {@code entry} names, or null when it is not one as {@link #trustedOrigins} defines it. */
    private static Origin trustedOrigin(String entry) {
        if (entry == null) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(entry.trim());
        } catch (URISyntaxException e) {
            return null;
        }
        String authority = uri.getRawAuthority();
        String path = uri.getRawPath();
        if (authority == null || authority.indexOf('@') >= 0 || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(path == null || path.isEmpty() || "/".equals(path))) {
            return null;
        }
        Origin origin = Origin.of(uri);
        return origin != null && origin.isHttp() && origin.port > 0 ? origin : null;
    }

    /**
     * Returns {@code target} when it is an {@code http} or {@code https} URI on the origin of {@code cmis.base-url},
     * {@code repository} (null when that URL names none), or on one of {@link #trustedOrigins}. Otherwise the step
     * {@code what} fails with {@link #untrustedUrl} before anything is sent to {@code target}, so the service
     * account's credentials never leave for an origin the repository's responses chose.
     */
    private URI requireTrustedOrigin(URI target, Origin repository, String what) {
        Origin origin = Origin.of(target);
        if (origin == null || !origin.isHttp()
                || !(origin.sameAs(repository) || trustedOrigins.stream().anyMatch(origin::sameAs))) {
            throw untrustedUrl(what, null);
        }
        return target;
    }

    /**
     * {@code url}, a URL the repository supplied for the step {@code what}, as a URI; a URL that does not parse
     * fails the step as one on no trusted origin, so the parser's message, which repeats the URL, stays out of the
     * caller's 500 body.
     */
    private static URI discoveredUri(String url, String what) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw untrustedUrl(what, e);
        }
    }

    /** The fixed failure of a step whose repository-supplied URL is refused; it names neither the URL nor its host. */
    private static IllegalStateException untrustedUrl(String what, URISyntaxException cause) {
        return new IllegalStateException(
                "CMIS " + what + " URL is not on a trusted repository origin (cmis.base-url, cmis.trusted-origins)",
                cause);
    }

    /** Fails the operation, naming the first unset service-account key, before any repository request. */
    private void requireServiceAccount() {
        if (USERNAME_KEY.equals(missingCredentialKey)) {
            throw new IllegalStateException("CMIS service-account user name (cmis.username) is not configured");
        }
        if (PASSWORD_KEY.equals(missingCredentialKey)) {
            throw new IllegalStateException("CMIS service-account password (cmis.password) is not configured");
        }
    }

    private URI serviceDocumentUri() {
        String baseUrl = properties.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("CMIS service document URL (cmis.base-url) is not configured");
        }
        return URI.create(baseUrl.trim());
    }

    /** URIs are always passed as {@link URI}: a String would be re-expanded and re-encoded by RestClient. */
    private Document get(URI uri, String accept, String what) {
        byte[] body = send(restClient.get()
                .uri(uri)
                .header(HttpHeaders.ACCEPT, accept), what);
        return parse(body, what);
    }

    /**
     * Sends {@code request} once and returns the response body, or null when the response has none. These failures
     * carry a fixed message naming the step {@code what}: an error status raises
     * {@link HttpClientErrorException} (4xx) or {@link HttpServerErrorException} (5xx) with
     * {@code CMIS <what> request failed with HTTP status <code>}, built without reading the body; a transport or
     * body-read failure raises the exception of {@link #transportFailure}; a Content-Type that is not a media type,
     * which RestClient parses before any status handler runs, raises {@link IllegalStateException}. No message
     * carries the upstream body, reason phrase or header text, the request URL or a Java type name, because the
     * caller's 500 body reports the message verbatim.
     */
    private static byte[] send(RestClient.RequestHeadersSpec<?> request, String what) {
        try {
            return request.retrieve()
                    .onStatus(HttpStatusCode::isError, (clientRequest, response) -> {
                        throw statusFailure(response, what);
                    })
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            throw e;
        } catch (RestClientException e) {
            throw transportFailure(e, what);
        } catch (InvalidMediaTypeException e) {
            throw new IllegalStateException("CMIS " + what + " response has an invalid Content-Type", e);
        }
    }

    /** The exception for an error status (4xx or 5xx), keeping status, reason and headers but not the body. */
    private static RestClientResponseException statusFailure(ClientHttpResponse response, String what)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        String message = "CMIS " + what + " request failed with HTTP status " + status.value();
        String statusText = Objects.requireNonNullElse(response.getStatusText(), "");
        HttpHeaders headers = response.getHeaders();
        return status.is4xxClientError()
                ? HttpClientErrorException.create(message, status, statusText, headers, null, null)
                : HttpServerErrorException.create(message, status, statusText, headers, null, null);
    }

    /**
     * The exception for a request that got no usable response: a {@link ResourceAccessException} whose cause is the
     * first {@link IOException} in the cause chain of {@code failure}, with {@code CMIS <what> request timed out}
     * when a {@link SocketTimeoutException} is in that chain and {@code CMIS <what> request failed with an I/O
     * error} otherwise; a {@link RestClientException} {@code CMIS <what> request failed} caused by {@code failure}
     * when the chain holds no {@link IOException}.
     */
    private static RestClientException transportFailure(RestClientException failure, String what) {
        IOException ioFailure = null;
        boolean timedOut = false;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure.getCause(); cause != null && seen.add(cause); cause = cause.getCause()) {
            timedOut |= cause instanceof SocketTimeoutException;
            if (ioFailure == null && cause instanceof IOException io) {
                ioFailure = io;
            }
        }
        if (ioFailure == null) {
            return new RestClientException("CMIS " + what + " request failed", failure);
        }
        String message = timedOut
                ? "CMIS " + what + " request timed out"
                : "CMIS " + what + " request failed with an I/O error";
        return new ResourceAccessException(message, ioFailure);
    }

    private static String objectByPathTemplate(Document serviceDocument, String repositoryId) {
        Element service = serviceDocument.getDocumentElement();
        if (isElement(service, APP, "service")) {
            for (Element workspace : children(service, APP, "workspace")) {
                if (servesRepository(workspace, repositoryId)) {
                    for (Element uriTemplate : children(workspace, CMISRA, "uritemplate")) {
                        if ("objectbypath".equals(childText(uriTemplate, CMISRA, "type"))) {
                            String template = childText(uriTemplate, CMISRA, "template");
                            if (StringUtils.hasLength(template)) {
                                return template;
                            }
                        }
                    }
                    throw new IllegalStateException(
                            "CMIS workspace '" + repositoryId + "' has no 'objectbypath' URI template");
                }
            }
        }
        throw new IllegalStateException(
                "CMIS service document has no workspace for repository '" + repositoryId + "'");
    }

    private static boolean servesRepository(Element workspace, String repositoryId) {
        for (Element repositoryInfo : children(workspace, CMISRA, "repositoryInfo")) {
            for (Element id : children(repositoryInfo, CMIS, "repositoryId")) {
                if (id.getTextContent().trim().equals(repositoryId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Expands the template as OpenCMIS {@code UrlBuilder.expandUriTemplate} does: {@code {path}} becomes the
     * form-encoded folder path, every other variable is unset and becomes empty, and an unclosed brace is
     * copied as it stands.
     */
    private static String expandObjectByPathTemplate(String template, String folderPath) {
        StringBuilder expanded = new StringBuilder(template.length() + 3 * folderPath.length());
        int index = 0;
        while (index < template.length()) {
            char current = template.charAt(index);
            int close = current == '{' ? template.indexOf('}', index + 1) : -1;
            if (close < 0) {
                expanded.append(current);
                index++;
            } else {
                if ("path".equals(template.substring(index + 1, close))) {
                    expanded.append(URLEncoder.encode(folderPath, StandardCharsets.UTF_8));
                }
                index = close + 1;
            }
        }
        return expanded.toString();
    }

    /**
     * The children feed ({@code rel=down}, Atom feed), not the {@code application/cmistree+xml} descendants,
     * resolved against the folder entry's URL; an {@code href} that does not parse fails as
     * {@link #discoveredUri} describes.
     */
    private static URI childrenFeedUri(Document folderEntry, URI folderEntryUri, String folderPath) {
        Element entry = folderEntry.getDocumentElement();
        if (isElement(entry, ATOM, "entry")) {
            for (Element link : children(entry, ATOM, "link")) {
                String href = link.getAttribute("href").trim();
                if ("down".equals(link.getAttribute("rel").trim())
                        && FEED_TYPE.equalsIgnoreCase(StringUtils.trimAllWhitespace(link.getAttribute("type")))
                        && !href.isEmpty()) {
                    return folderEntryUri.resolve(discoveredUri(href, "create-document"));
                }
            }
        }
        throw new IllegalStateException("CMIS folder entry for path '" + folderPath
                + "' has no children link (rel=down, type=" + FEED_TYPE + ")");
    }

    /** A null state adds no parameter, as OpenCMIS {@code UrlBuilder.addParameter} skips null values. */
    private static URI withVersioningState(URI childrenUri, String versioningState) {
        if (versioningState == null) {
            return childrenUri;
        }
        String separator = childrenUri.getRawQuery() == null ? "?" : "&";
        return URI.create(childrenUri + separator + "versioningState="
                + URLEncoder.encode(versioningState.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8));
    }

    private static String objectId(Document createdEntry) {
        Element entry = createdEntry.getDocumentElement();
        if (isElement(entry, ATOM, "entry")) {
            for (Element object : children(entry, CMISRA, "object")) {
                for (Element objectProperties : children(object, CMIS, "properties")) {
                    for (Element property : children(objectProperties, CMIS, "propertyId")) {
                        if ("cmis:objectId".equals(property.getAttribute("propertyDefinitionId"))) {
                            String value = childText(property, CMIS, "value");
                            if (StringUtils.hasLength(value)) {
                                return value;
                            }
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("CMIS create-document response has no cmis:objectId");
    }

    /**
     * Parses a response body without DTDs, external entities or XInclude; the XML declaration, not the
     * Content-Type, decides the charset.
     */
    private static Document parse(byte[] body, String what) {
        if (body == null || body.length == 0) {
            throw new IllegalStateException("CMIS " + what + " response has no body");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(FAIL_ON_ERROR);
            return builder.parse(new ByteArrayInputStream(body));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("CMIS " + what + " response is not well-formed XML", e);
        }
    }

    /** The Atom entry OpenCMIS {@code AtomEntryWriter} posts for a document with content, in its child order. */
    private static String entry(String name, String mimeType, String objectTypeId, byte[] content) {
        String escapedName = escapeXml(name);
        String base64 = Base64.getEncoder().encodeToString(content);
        return new StringBuilder(base64.length() + 1024)
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<atom:entry xmlns:atom=\"").append(ATOM)
                .append("\" xmlns:cmis=\"").append(CMIS)
                .append("\" xmlns:cmisra=\"").append(CMISRA).append("\">")
                .append("<atom:id>").append(PLACEHOLDER_ENTRY_ID).append("</atom:id>")
                .append("<atom:title>").append(escapedName).append("</atom:title>")
                .append("<atom:updated>")
                .append(DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.MILLIS)))
                .append("</atom:updated>")
                .append("<cmisra:content>")
                .append("<cmisra:mediatype>").append(escapeXml(mimeType)).append("</cmisra:mediatype>")
                .append("<cmisra:base64>").append(base64).append("</cmisra:base64>")
                .append("</cmisra:content>")
                .append("<cmisra:object><cmis:properties>")
                .append("<cmis:propertyId propertyDefinitionId=\"cmis:objectTypeId\"><cmis:value>")
                .append(escapeXml(objectTypeId)).append("</cmis:value></cmis:propertyId>")
                .append("<cmis:propertyString propertyDefinitionId=\"cmis:name\"><cmis:value>")
                .append(escapedName).append("</cmis:value></cmis:propertyString>")
                .append("</cmis:properties></cmisra:object>")
                .append("</atom:entry>")
                .toString();
    }

    private static String escapeXml(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static boolean isElement(Element element, String namespace, String localName) {
        return element != null && namespace.equals(element.getNamespaceURI())
                && localName.equals(element.getLocalName());
    }

    /** Direct child elements matched by namespace URI and local name; servers choose their own prefixes. */
    private static List<Element> children(Element parent, String namespace, String localName) {
        List<Element> matches = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && isElement(element, namespace, localName)) {
                matches.add(element);
            }
        }
        return matches;
    }

    /** Trimmed text of the first matching direct child, or null when there is none. */
    private static String childText(Element parent, String namespace, String localName) {
        List<Element> matches = children(parent, namespace, localName);
        return matches.isEmpty() ? null : matches.get(0).getTextContent().trim();
    }

    /**
     * Where a request goes: its scheme, host and port, an absent port counting as the scheme's default. Two origins
     * are the same when scheme and host match case-insensitively and the ports are equal, as
     * {@code HttpURLConnection} compared destinations. The client's single notion of origin: it decides which
     * redirect hop leaves the original destination ({@link #sameDestination}) and which repository-supplied URL may
     * be requested ({@link #requireTrustedOrigin}).
     */
    private static final class Origin {

        private final String scheme;

        private final String host;

        private final int port;

        private Origin(String scheme, String host, int port) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }

        /** The origin of a request as httpclient5 sends it, or null when it names no scheme or host. */
        static Origin of(HttpRequest request) {
            return of(request.getScheme(), request.getAuthority());
        }

        /**
         * The origin of {@code uri}, its authority read as httpclient5 reads a request URI: the parsed host and port,
         * or else the raw authority, so a host name such as {@code my_host} that {@link URI} leaves unparsed is the
         * host the transport connects to. Null when {@code uri} names no scheme or host, or a port above 65535,
         * which the transport would refuse to send to.
         */
        static Origin of(URI uri) {
            URIAuthority authority;
            try {
                if (uri.getHost() != null) {
                    authority = new URIAuthority(uri.getHost(), uri.getPort());
                } else if (uri.getRawAuthority() != null) {
                    authority = URIAuthority.create(uri.getRawAuthority());
                } else {
                    return null;
                }
            } catch (URISyntaxException | IllegalArgumentException e) {
                return null;
            }
            return of(uri.getScheme(), authority);
        }

        private static Origin of(String scheme, URIAuthority authority) {
            if (scheme == null || authority == null || !StringUtils.hasLength(authority.getHostName())) {
                return null;
            }
            return new Origin(scheme, authority.getHostName(), effectivePort(scheme, authority.getPort()));
        }

        /** Whether the scheme is {@code http} or {@code https}, the only ones a repository URL may use. */
        boolean isHttp() {
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        }

        /** Whether {@code other} is the same origin; never for null. */
        boolean sameAs(Origin other) {
            return other != null && scheme.equalsIgnoreCase(other.scheme) && host.equalsIgnoreCase(other.host)
                    && port == other.port;
        }
    }

    /**
     * Follows a redirect only where {@code HttpURLConnection}, as {@code SimpleClientHttpRequestFactory} set it up,
     * followed one: the request is a GET, the status is 300, 301, 302, 303 or 307 with a {@code Location}, and that
     * location keeps the scheme of the URL that answered, which a relative one always does. Any other redirect, a
     * 308 or a {@code 305 Use Proxy} among them, and every redirect answering the create-document POST, is returned
     * as received, and nothing is sent to its location.
     */
    private static final class HttpUrlConnectionRedirectStrategy extends DefaultRedirectStrategy {

        private static final Set<Integer> FOLLOWED_STATUSES = Set.of(300, 301, 302, 303, 307);

        @Override
        public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context)
                throws ProtocolException {
            Header location = response.getFirstHeader(HttpHeaders.LOCATION);
            if (location == null || !Method.GET.isSame(request.getMethod())
                    || !FOLLOWED_STATUSES.contains(response.getCode())) {
                return false;
            }
            String targetScheme = createLocationURI(location.getValue()).getScheme();
            return targetScheme == null || targetScheme.equalsIgnoreCase(answeredScheme(request, context));
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
     * {@code HttpURLConnection} connected to. httpclient5 would otherwise move on to each further address of the host
     * after a refused or timed-out connect: a second connection attempt after a connectivity failure, which could
     * also stretch the connect past its timeout.
     */
    private static final class FirstAddressDnsResolver implements DnsResolver {

        private final DnsResolver delegate;

        FirstAddressDnsResolver(DnsResolver delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
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
}
