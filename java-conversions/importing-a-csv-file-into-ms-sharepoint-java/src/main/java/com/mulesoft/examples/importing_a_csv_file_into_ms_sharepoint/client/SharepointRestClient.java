/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config.SharepointProperties;

/**
 * SharePoint Online access over the SharePoint REST API, the project's only path to SharePoint.
 *
 * <p>Replaces the Microsoft SharePoint 2013 connector of the original flow
 * {@code importing-a-csv-file-into-ms-sharepoint.xml}:
 * <ul>
 *   <li>{@code <sharepoint:online-connection-config username password siteUrl/>} (line 4) becomes {@link #signIn()},
 *       the SharePoint Online claims sign-in the connector performed with the configured username and password:
 *       a WS-Trust {@code RST/Issue} to the security token service, the token posted to the site's
 *       {@code /_forms/default.aspx?wa=wsignin1.0} for the {@code rtFa} and {@code FedAuth} cookies, and a form
 *       digest from {@code /_api/contextinfo};</li>
 *   <li>{@code <sharepoint:folder-create url="/Shared Documents/${csvImportFolder}"/>} (line 10) becomes
 *       {@link #createFolder(SharepointSession, String)};</li>
 *   <li>{@code <sharepoint:file-add fileServerRelativeUrl="/Shared Documents/${csvImportFolder}/contacts.csv"
 *       overwrite="true"/>} (line 12) becomes {@link #addFile(SharepointSession, String, byte[], boolean)}.</li>
 * </ul>
 *
 * <p>Every request is sent exactly once. HTTP error statuses surface as Spring's
 * {@link org.springframework.web.client.RestClientResponseException} subtypes and timeouts or connectivity failures
 * as {@link org.springframework.web.client.ResourceAccessException}; neither is caught, wrapped or retried.
 * The password, the security token, the cookie values and the form digest are never logged and never placed in an
 * exception message. A security token service error status surfaces with its status and headers but without its
 * body. Of a security token service response with a 2xx status, only the SOAP fault reason can reach an exception
 * message; neither its body nor the XML parser's messages about it are logged or placed in one. Nor are the JSON
 * parser's messages about a {@code /_api/contextinfo} response, which can quote its form digest.
 */
@Component
public class SharepointRestClient {

    private static final Logger log = LoggerFactory.getLogger(SharepointRestClient.class);

    /**
     * The Mule default response timeout, applied to connecting and to each whole response: its headers and the last
     * byte of its body must arrive within this time of the request being sent.
     */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The most bytes of one response body held in memory, 1 MiB. SharePoint's security token service and contextinfo
     * responses, its folder and file metadata and its error bodies are a few kilobytes. A longer body is read no
     * further and its connection is closed rather than drained: its status and headers still decide a call that needs
     * no body, and reading the body fails with an {@link IOException} that names this limit.
     */
    static final int MAX_BUFFERED_BODY_BYTES = 1 << 20;

    static final String SOAP12_NS = "http://www.w3.org/2003/05/soap-envelope";
    static final String WSA_NS = "http://www.w3.org/2005/08/addressing";
    static final String WSSE_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    static final String WST_NS = "http://schemas.xmlsoap.org/ws/2005/02/trust";
    static final String WSP_NS = "http://schemas.xmlsoap.org/ws/2004/09/policy";

    static final MediaType ODATA_VERBOSE = MediaType.parseMediaType("application/json;odata=verbose");
    static final MediaType SOAP12_UTF8 = MediaType.parseMediaType("application/soap+xml; charset=utf-8");

    private static final String SIGN_IN_FAILED = "SharePoint Online sign-in failed: ";
    private static final String NO_FORM_DIGEST = "SharePoint contextinfo response carries no FormDigestValue";
    private static final String RTFA_COOKIE = "rtFa";
    private static final String FEDAUTH_COOKIE = "FedAuth";
    private static final String REQUEST_DIGEST_HEADER = "X-RequestDigest";

    /**
     * Rethrows parse errors instead of the parser's default of printing them to standard error, and logs a warning
     * by its {@linkplain #parserDiagnostic type and position} only, since the parser's message can quote the response.
     */
    private static final ErrorHandler XML_ERROR_HANDLER = new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {
            log.debug("XML warning in a security token service response: {}", parserDiagnostic(e));
        }

        @Override
        public void error(SAXParseException e) throws SAXParseException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXParseException {
            throw e;
        }
    };

    private final SharepointProperties props;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates the client with the {@linkplain #DEFAULT_TIMEOUT default timeout}.
     *
     * @param props the SharePoint Online account, site and security token service
     */
    @Autowired
    public SharepointRestClient(SharepointProperties props) {
        this(props, DEFAULT_TIMEOUT);
    }

    /**
     * Creates the client with the given connect and response timeout; the test seam for short timeouts.
     *
     * @param props   the SharePoint Online account, site and security token service
     * @param timeout the connect timeout and the time allowed for each whole response, headers and body, from the
     *                moment its request is sent
     */
    SharepointRestClient(SharepointProperties props, Duration timeout) {
        this.props = Objects.requireNonNull(props, "props");
        Objects.requireNonNull(timeout, "timeout");
        // Redirects are never followed, so the Set-Cookie headers of the wsignin1.0 redirect reach signIn(), and no
        // cookie handler is installed, so the client never stores or replays cookies by itself. The JDK client
        // re-attempts only a refused TCP connect, which never delivered the request; nothing that reached a server
        // is sent twice. HTTP/1.1 is pinned because the original connector spoke HTTP/1.1: the JDK default (HTTP/2)
        // adds Upgrade: h2c and HTTP2-Settings headers on plain-HTTP calls, which the original never sent and which
        // the fixture replay (ST-2) compares exactly.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .build();
        // The factory's read timeout bounds only the wait for the response headers. The interceptor reads every
        // response body, up to MAX_BUFFERED_BODY_BYTES, within what is left of the same timeout, so a server that
        // stalls after its headers fails the call as a timeout instead of blocking the file poll, an oversized body
        // cannot exhaust the heap, and nothing after it reads or drains the connection.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor(new BoundedExchangeInterceptor(timeout))
                .build();
    }

    /**
     * The authenticated state every SharePoint REST call carries.
     *
     * @param cookieHeader the {@code Cookie} header value holding the {@code rtFa} and {@code FedAuth} cookies
     * @param formDigest   the {@code X-RequestDigest} value from {@code /_api/contextinfo}
     */
    public record SharepointSession(String cookieHeader, String formDigest) {

        /** Masks both values, so a logged session reveals no credential. */
        @Override
        public String toString() {
            return "SharepointSession[cookieHeader=***, formDigest=***]";
        }
    }

    /**
     * Signs in to SharePoint Online with the configured username and password.
     *
     * @return the session holding the federation cookies and the form digest
     * @throws IllegalStateException when the security token service returns no token (the message is its SOAP fault
     *                               reason, or else a fixed reason for an empty, unparseable or token-less response,
     *                               never other response text), when the sign-in response lacks the {@code rtFa} or
     *                               {@code FedAuth} cookie, or when {@code /_api/contextinfo} carries no form digest;
     *                               and first, before any request is sent, when
     *                               {@link SharepointProperties#requireValid()} rejects the configuration
     */
    public SharepointSession signIn() {
        props.requireValid();
        log.debug("Signing in to SharePoint Online site {}", props.siteUrl());
        String token = requestSecurityToken();
        String cookieHeader = requestFederationCookies(token);
        String formDigest = requestFormDigest(cookieHeader);
        return new SharepointSession(cookieHeader, formDigest);
    }

    /**
     * Creates a folder; SharePoint answers success when the folder already exists.
     *
     * @param session                 the session from {@link #signIn()}
     * @param folderServerRelativeUrl the server-relative folder URL, for example {@code /Shared Documents/my_folder}
     * @throws IllegalStateException when {@link SharepointProperties#requireValid()} rejects the configuration; no
     *                               request is sent
     */
    public void createFolder(SharepointSession session, String folderServerRelativeUrl) {
        props.requireValid();
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(folderServerRelativeUrl, "folderServerRelativeUrl");
        log.debug("Creating SharePoint folder {}", folderServerRelativeUrl);

        ObjectNode root = objectMapper.createObjectNode();
        root.putObject("__metadata").put("type", "SP.Folder");
        root.put("ServerRelativeUrl", folderServerRelativeUrl);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize the SharePoint folder request", e);
        }

        restClient.post()
                .uri(URI.create(props.siteUrl() + "/_api/web/folders"))
                .header(HttpHeaders.COOKIE, session.cookieHeader())
                .header(REQUEST_DIGEST_HEADER, session.formDigest())
                .accept(ODATA_VERBOSE)
                .contentType(ODATA_VERBOSE)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Uploads a file into an existing folder.
     *
     * @param session               the session from {@link #signIn()}
     * @param fileServerRelativeUrl the server-relative file URL {@code <folder>/<name>}, for example
     *                              {@code /Shared Documents/my_folder/contacts.csv}
     * @param content               the file bytes, sent unchanged
     * @param overwrite             whether an existing file of that name is replaced
     * @throws IllegalStateException    when {@link SharepointProperties#requireValid()} rejects the configuration; no
     *                                  request is sent
     * @throws IllegalArgumentException when {@code fileServerRelativeUrl} is not {@code <folder>/<name>}
     */
    public void addFile(SharepointSession session, String fileServerRelativeUrl, byte[] content, boolean overwrite) {
        props.requireValid();
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(fileServerRelativeUrl, "fileServerRelativeUrl");
        Objects.requireNonNull(content, "content");
        int slash = fileServerRelativeUrl.lastIndexOf('/');
        if (slash <= 0 || slash == fileServerRelativeUrl.length() - 1) {
            throw new IllegalArgumentException("fileServerRelativeUrl must be <folder>/<name>: " + fileServerRelativeUrl);
        }
        String folder = fileServerRelativeUrl.substring(0, slash);
        String name = fileServerRelativeUrl.substring(slash + 1);
        String path = "/_api/web/GetFolderByServerRelativeUrl('" + odataLiteral(folder) + "')/Files/add(url='"
                + odataLiteral(name) + "',overwrite=" + overwrite + ")";
        // Encoded once here and passed as a URI: template expansion would re-encode '%' and read '{' as a variable.
        URI uri = URI.create(props.siteUrl() + UriUtils.encodePath(path, StandardCharsets.UTF_8));
        log.debug("Adding SharePoint file {} ({} bytes, overwrite={})", fileServerRelativeUrl, content.length, overwrite);

        restClient.post()
                .uri(uri)
                .header(HttpHeaders.COOKIE, session.cookieHeader())
                .header(REQUEST_DIGEST_HEADER, session.formDigest())
                .accept(ODATA_VERBOSE)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content)
                .retrieve()
                .toBodilessEntity();
    }

    /** Step one of the claims sign-in: the WS-Trust RST/Issue for the site, answered with a binary token. */
    private String requestSecurityToken() {
        String envelope = "<s:Envelope xmlns:s=\"" + SOAP12_NS + "\" xmlns:a=\"" + WSA_NS + "\">"
                + "<s:Header>"
                + "<a:Action s:mustUnderstand=\"1\">" + WST_NS + "/RST/Issue</a:Action>"
                + "<a:ReplyTo><a:Address>" + WSA_NS + "/anonymous</a:Address></a:ReplyTo>"
                + "<a:To s:mustUnderstand=\"1\">" + xmlEscape(props.stsUrl()) + "</a:To>"
                + "<o:Security s:mustUnderstand=\"1\" xmlns:o=\"" + WSSE_NS + "\">"
                + "<o:UsernameToken>"
                + "<o:Username>" + xmlEscape(props.username()) + "</o:Username>"
                + "<o:Password>" + xmlEscape(props.password()) + "</o:Password>"
                + "</o:UsernameToken>"
                + "</o:Security>"
                + "</s:Header>"
                + "<s:Body>"
                + "<t:RequestSecurityToken xmlns:t=\"" + WST_NS + "\">"
                + "<wsp:AppliesTo xmlns:wsp=\"" + WSP_NS + "\">"
                + "<a:EndpointReference><a:Address>" + xmlEscape(props.siteUrl()) + "</a:Address></a:EndpointReference>"
                + "</wsp:AppliesTo>"
                + "<t:KeyType>http://schemas.xmlsoap.org/ws/2005/05/identity/NoProofKey</t:KeyType>"
                + "<t:RequestType>" + WST_NS + "/Issue</t:RequestType>"
                + "<t:TokenType>urn:oasis:names:tc:SAML:1.0:assertion</t:TokenType>"
                + "</t:RequestSecurityToken>"
                + "</s:Body>"
                + "</s:Envelope>";

        byte[] response = restClient.post()
                .uri(URI.create(props.stsUrl()))
                .contentType(SOAP12_UTF8)
                .body(envelope.getBytes(StandardCharsets.UTF_8))
                .retrieve()
                // Spring's default handler quotes an error body in the exception message and keeps it on the exception.
                // This one raises the exception type Spring would, with the status and headers only, because a
                // security token service body can hold an issued token.
                .onStatus(HttpStatusCode::isError, (request, error) -> {
                    HttpStatusCode status = error.getStatusCode();
                    String statusText = error.getStatusText();
                    HttpHeaders headers = error.getHeaders();
                    if (status.is4xxClientError()) {
                        throw HttpClientErrorException.create(status, statusText, headers, null, null);
                    }
                    throw HttpServerErrorException.create(status, statusText, headers, null, null);
                })
                .body(byte[].class);

        if (response == null || response.length == 0) {
            throw new IllegalStateException(SIGN_IN_FAILED + "empty response from the security token service");
        }
        Document document = parseXml(response);
        NodeList tokens = document.getElementsByTagNameNS(WSSE_NS, "BinarySecurityToken");
        String token = tokens.getLength() > 0 ? tokens.item(0).getTextContent().trim() : "";
        if (token.isEmpty()) {
            // The service answers rejected credentials with HTTP 200 and a SOAP 1.2 fault, whose reason is the message.
            // Without one, only the body's length is reported: a token in an unexpected namespace is still a token.
            throw new IllegalStateException(SIGN_IN_FAILED + faultReason(document).orElseGet(() ->
                    "the security token service response carries no BinarySecurityToken and no SOAP fault reason ("
                            + response.length + " bytes)"));
        }
        return token;
    }

    /** Step two: posts the token to the site and keeps the {@code rtFa} and {@code FedAuth} cookies it sets. */
    private String requestFederationCookies(String token) {
        ResponseEntity<Void> response = restClient.post()
                .uri(URI.create(props.siteUrl() + "/_forms/default.aspx?wa=wsignin1.0"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(token.getBytes(StandardCharsets.UTF_8))
                .retrieve()
                // The site answers with a redirect to its landing page; the redirect itself is the success.
                .onStatus(HttpStatusCode::is3xxRedirection, (request, redirect) -> { })
                .toBodilessEntity();

        String rtFa = null;
        String fedAuth = null;
        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        for (String setCookie : setCookies == null ? List.<String>of() : setCookies) {
            int end = setCookie.indexOf(';');
            String pair = end < 0 ? setCookie : setCookie.substring(0, end);
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (value.isEmpty()) {
                continue;
            }
            if (rtFa == null && RTFA_COOKIE.equals(name)) {
                rtFa = value;
            } else if (fedAuth == null && FEDAUTH_COOKIE.equals(name)) {
                fedAuth = value;
            }
        }

        List<String> missing = new ArrayList<>();
        if (rtFa == null) {
            missing.add(RTFA_COOKIE);
        }
        if (fedAuth == null) {
            missing.add(FEDAUTH_COOKIE);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(SIGN_IN_FAILED + "missing cookie(s) " + String.join(", ", missing));
        }
        return RTFA_COOKIE + "=" + rtFa + "; " + FEDAUTH_COOKIE + "=" + fedAuth;
    }

    /** Step three: the form digest every write must carry as {@code X-RequestDigest}. */
    private String requestFormDigest(String cookieHeader) {
        byte[] response = restClient.post()
                .uri(URI.create(props.siteUrl() + "/_api/contextinfo"))
                .header(HttpHeaders.COOKIE, cookieHeader)
                .accept(ODATA_VERBOSE)
                .retrieve()
                .body(byte[].class);

        if (response == null || response.length == 0) {
            throw new IllegalStateException(NO_FORM_DIGEST);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (IOException e) {
            // The parser exception is not chained: its message quotes the response, which can hold the form digest.
            throw new IllegalStateException(NO_FORM_DIGEST + " (" + parserDiagnostic(e) + ")");
        }
        JsonNode digest = root.path("d").path("GetContextWebInformation").path("FormDigestValue");
        // Only a JSON string is a digest: null, number and boolean values would otherwise render as bogus text.
        String formDigest = digest.isTextual() ? digest.textValue() : "";
        if (formDigest.isBlank()) {
            throw new IllegalStateException(NO_FORM_DIGEST);
        }
        return formDigest;
    }

    /** The trimmed text of the first SOAP 1.2 {@code Reason/Text}, when the document holds a non-blank one. */
    private static Optional<String> faultReason(Document document) {
        NodeList reasons = document.getElementsByTagNameNS(SOAP12_NS, "Reason");
        if (reasons.getLength() == 0) {
            return Optional.empty();
        }
        NodeList texts = ((Element) reasons.item(0)).getElementsByTagNameNS(SOAP12_NS, "Text");
        if (texts.getLength() == 0) {
            return Optional.empty();
        }
        String reason = texts.item(0).getTextContent().trim();
        return reason.isEmpty() ? Optional.empty() : Optional.of(reason);
    }

    /**
     * Parses a namespace-aware DOM with DTDs, external entities and XInclude disabled.
     *
     * <p>A rejected response fails with a fixed reason and the parser exception's
     * {@linkplain #parserDiagnostic type and position}. The body and the parser exception are left out of the
     * {@link IllegalStateException}, because a truncated response can hold an issued token and the parser's message
     * can quote the input.
     */
    private static Document parseXml(byte[] xml) {
        DocumentBuilder builder;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            builder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Cannot configure a secure XML parser", e);
        }
        builder.setErrorHandler(XML_ERROR_HANDLER);
        try {
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (SAXException | IOException e) {
            throw new IllegalStateException(SIGN_IN_FAILED
                    + "the security token service response is not well-formed XML or declares a DOCTYPE ("
                    + parserDiagnostic(e) + ")");
        }
    }

    /**
     * Describes a parser exception by its type and, for a {@link SAXParseException} or a
     * {@link JsonProcessingException} with a known position, its line and column. The exception's message is never
     * used: it can quote the parsed response.
     */
    private static String parserDiagnostic(Exception e) {
        String type = e.getClass().getSimpleName().isEmpty() ? e.getClass().getName() : e.getClass().getSimpleName();
        if (e instanceof SAXParseException parse && parse.getLineNumber() > 0) {
            return type + " at line " + parse.getLineNumber() + ", column " + parse.getColumnNumber();
        }
        JsonLocation location = e instanceof JsonProcessingException json ? json.getLocation() : null;
        if (location != null && location.getLineNr() > 0) {
            return type + " at line " + location.getLineNr() + ", column " + location.getColumnNr();
        }
        return type;
    }

    /** Escapes a value for XML text and attribute content. */
    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /** Quotes a value inside an OData string literal by doubling every single quote. */
    private static String odataLiteral(String value) {
        return value.replace("'", "''");
    }

    /**
     * Bounds each whole exchange by the client's timeout, counted from the moment the request is handed to the
     * request factory. The request is sent once, unchanged. The response body is then read to its end within what
     * remains of the timeout and passed on as a {@link BufferedClientHttpResponse}, so no later step reads from or
     * drains the connection. When the time runs out, a watchdog closes the body stream, which ends a blocked read,
     * and the exchange fails with an {@link HttpTimeoutException}; any other I/O failure propagates unchanged.
     * RestClient reports either as a {@link org.springframework.web.client.ResourceAccessException}.
     *
     * <p>A body is read in {@value #READ_BUFFER_BYTES}-byte steps and at most {@link #MAX_BUFFERED_BODY_BYTES} of it
     * is kept. A body that grows past that limit is read no further and its stream is closed, abandoning the
     * connection instead of draining it; the response is passed on with its status and headers and a body that cannot
     * be read, never with the part that arrived.
     */
    private static final class BoundedExchangeInterceptor implements ClientHttpRequestInterceptor {

        private static final int READ_BUFFER_BYTES = 8 * 1024;

        private final Duration timeout;
        private final long timeoutNanos;

        BoundedExchangeInterceptor(Duration timeout) {
            this.timeout = timeout;
            this.timeoutNanos = timeout.toNanos();
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            long deadline = System.nanoTime() + timeoutNanos;
            ClientHttpResponse response = execution.execute(request, body);
            InputStream stream = null;
            boolean readInFull = false;
            try {
                stream = response.getBody();
                byte[] content = readWithin(stream, deadline - System.nanoTime());
                readInFull = content != null;
                return new BufferedClientHttpResponse(response.getStatusCode(), response.getStatusText(),
                        response.getHeaders(), content);
            } finally {
                if (!readInFull) {
                    // Closed first, so that closing the response does not drain a body that has stopped arriving or
                    // that exceeds MAX_BUFFERED_BODY_BYTES.
                    closeQuietly(stream);
                }
                response.close();
            }
        }

        /**
         * Reads the stream to its end, closing it when {@code remainingNanos} pass first.
         *
         * @return the whole body, or {@code null} once it exceeds {@link #MAX_BUFFERED_BODY_BYTES}, at which point
         *         reading stops and the bytes read so far are discarded
         */
        private byte[] readWithin(InputStream stream, long remainingNanos) throws IOException {
            if (remainingNanos <= 0) {
                throw timedOut(null);
            }
            AtomicBoolean expired = new AtomicBoolean();
            CompletableFuture<Void> watchdog = new CompletableFuture<>();
            watchdog.orTimeout(remainingNanos, TimeUnit.NANOSECONDS).whenComplete((done, failure) -> {
                if (failure != null) {
                    expired.set(true);
                    closeQuietly(stream);
                }
            });
            try {
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                byte[] buffer = new byte[READ_BUFFER_BYTES];
                for (int read = stream.read(buffer); read != -1; read = stream.read(buffer)) {
                    if (read > MAX_BUFFERED_BODY_BYTES - content.size()) {
                        return null;
                    }
                    content.write(buffer, 0, read);
                }
                return content.toByteArray();
            } catch (IOException e) {
                if (expired.get()) {
                    throw timedOut(e);
                }
                throw e;
            } finally {
                // Completing the watchdog cancels its pending timeout.
                watchdog.complete(null);
            }
        }

        /** The timeout failure; its message names the bound and nothing of the exchange. */
        private HttpTimeoutException timedOut(IOException cause) {
            HttpTimeoutException timeoutException = new HttpTimeoutException(
                    "SharePoint response not received in full within " + timeout.toMillis() + " ms");
            if (cause != null) {
                timeoutException.initCause(cause);
            }
            return timeoutException;
        }

        /** Closes a body stream whose exchange has already ended; a failure to close leaves nothing to report to. */
        private static void closeQuietly(InputStream stream) {
            if (stream == null) {
                return;
            }
            try {
                stream.close();
            } catch (IOException e) {
                log.debug("Closing a SharePoint response body failed: {}", e.getClass().getName());
            }
        }
    }

    /**
     * A response whose exchange has ended: the status, reason phrase and headers as received, and the body served
     * from memory. A body over {@link #MAX_BUFFERED_BODY_BYTES} was not kept and is passed as {@code null}, so
     * {@link #getBody()} fails with an {@link IOException} naming the limit and nothing of the content. RestClient
     * then reports a call that reads the body as a {@link org.springframework.web.client.RestClientException} with
     * that cause, builds an error-status exception with an empty body, and completes a call that needs only the
     * status and headers.
     */
    private static final class BufferedClientHttpResponse implements ClientHttpResponse {

        private static final String BODY_TOO_LARGE =
                "SharePoint response body exceeds " + MAX_BUFFERED_BODY_BYTES + " bytes";

        private final HttpStatusCode statusCode;
        private final String statusText;
        private final HttpHeaders headers;
        private final InputStream body;

        BufferedClientHttpResponse(HttpStatusCode statusCode, String statusText, HttpHeaders headers, byte[] body) {
            this.statusCode = statusCode;
            this.statusText = statusText;
            this.headers = headers;
            this.body = body == null ? null : new ByteArrayInputStream(body);
        }

        @Override
        public HttpStatusCode getStatusCode() {
            return statusCode;
        }

        @Override
        public String getStatusText() {
            return statusText;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() throws IOException {
            if (body == null) {
                throw new IOException(BODY_TOO_LARGE);
            }
            return body;
        }

        @Override
        public void close() {
            // Nothing to release: the body is held in memory, and the connection was released once it had been read,
            // or closed once the body had exceeded MAX_BUFFERED_BODY_BYTES.
        }
    }
}
