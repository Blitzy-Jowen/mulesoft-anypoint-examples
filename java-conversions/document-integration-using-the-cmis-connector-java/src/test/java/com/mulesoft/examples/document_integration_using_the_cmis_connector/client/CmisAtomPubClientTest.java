/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okForContentType;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.QueryParameter;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.document_integration_using_the_cmis_connector.config.CmisProperties;
import com.mulesoft.examples.document_integration_using_the_cmis_connector.support.TestProfileValues;

/**
 * Unit test of {@link CmisAtomPubClient}, the CMIS 1.1 AtomPub adapter that replaces the original flow's
 * {@code cmis:create-document-by-path-from-content} operation
 * (document-integration-using-the-cmis-connector/src/main/app/cmis-document-integration.xml:13).
 *
 * <p>WireMock on a dynamic port plays the OpenKM repository the original {@code cmis:config} pointed at, with the
 * same AtomPub surface as the root {@code DocumentIntegrationUsingTheCmisConnectorTest}: the service document, the
 * root folder entry read through the {@code objectbypath} URI template, and the children feed that accepts the new
 * document. No Spring context is started; the client is built directly from {@link CmisProperties}.
 */
class CmisAtomPubClientTest {

    private static final String BASE_PATH = "/OpenKM/cmis/atom";
    private static final String OBJECT_BY_PATH = BASE_PATH + "/default/path";
    private static final String CHILDREN = BASE_PATH + "/default/children";
    private static final String DESCENDANTS = BASE_PATH + "/default/descendants";
    private static final String QUERY = BASE_PATH + "/default/query";

    /** Where the redirect cases move the service document. */
    private static final String MOVED = "/moved/cmis/atom";

    /** A redirect hop on another destination, between {@link #BASE_PATH} and {@link #MOVED}. */
    private static final String MOVED_ELSEWHERE = "/elsewhere/cmis/atom";

    /** The page a web server sends with a redirect, carrying the DOCTYPE such pages declare. */
    private static final String REDIRECT_PAGE = "<!DOCTYPE HTML PUBLIC \"-//IETF//DTD HTML 2.0//EN\">\n"
            + "<html><head><title>301 Moved Permanently</title></head><body><h1>Moved Permanently</h1>"
            + "<p>The document has moved <a href=\"" + MOVED + "\">here</a>.</p></body></html>";

    /** The most requests one redirected GET makes: the JDK default of {@code http.maxRedirects}. */
    private static final int MAX_REDIRECT_REQUESTS = 20;

    private static final String FOLDER = "/okm:root/";
    private static final String NAME = "pic123.jpg";
    private static final String OBJECT_ID = "/okm:root/pic123.jpg";
    private static final String MIME_TYPE = "image/jpg";
    private static final String OBJECT_TYPE = "cmis:document";
    private static final String VERSIONING_STATE = "NONE";

    /** The public OpenKM demo account: {@code cmis.username} and {@code cmis.password} of application-test.yml. */
    private static final String USERNAME = TestProfileValues.required("cmis.username");
    private static final String PASSWORD = TestProfileValues.required("cmis.password");

    /** HTTP Basic credentials of that account, encoded with the JDK's Base64 rather than the client's encoder. */
    private static final String EXPECTED_AUTH = "Basic "
            + Base64.getEncoder().encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.ISO_8859_1));

    /** JPEG magic bytes, a NUL and a high byte; seven bytes, so the Base64 form ends in padding. */
    private static final byte[] CONTENT = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x01, 0x7F};

    private static final String SERVICE_DOCUMENT_TYPE = "application/atomsvc+xml";
    private static final String ENTRY_TYPE = "application/atom+xml;type=entry";

    /** Variables of the objectbypath template other than {@code path}, which the client leaves unset. */
    private static final List<String> UNSET_TEMPLATE_VARIABLES = List.of("filter", "includeAllowableActions",
            "includePolicyIds", "includeRelationships", "includeACL", "renditionFilter");

    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String APP = "http://www.w3.org/2007/app";
    private static final String CMIS = "http://docs.oasis-open.org/ns/cmis/core/200908/";
    private static final String CMISRA = "http://docs.oasis-open.org/ns/cmis/restatom/200908/";

    /** The client's three requests, in the order it sends them, by the step names its messages use. */
    private static final String SERVICE_DOCUMENT = "service document";
    private static final String FOLDER_ENTRY = "folder entry";
    private static final String CREATE_DOCUMENT = "create-document";
    private static final List<String> STEPS = List.of(SERVICE_DOCUMENT, FOLDER_ENTRY, CREATE_DOCUMENT);

    /** Text of an OpenKM stack trace, which must never reach an exception message. */
    private static final String STACK_TRACE_MARKER = "javax.servlet.ServletException: "
            + "com.openkm.core.DatabaseException: Connection refused "
            + "at com.openkm.cmis.CmisRepository.create(CmisRepository.java:412)";

    /** A Tomcat error page carrying the stack trace, padded past 200,000 bytes. */
    private static final String UPSTREAM_ERROR_PAGE = padded("<!doctype html><html><head><title>HTTP Status 500"
            + "</title></head><body><pre>" + STACK_TRACE_MARKER + "</pre><h3>Apache Tomcat/8.5.23</h3>", 200_000)
            + "</body></html>";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort())
            .build();

    /**
     * A second origin ({@code http://localhost:<another port>}) that the repository's responses may name: it must
     * receive nothing, and above all not the service account, unless {@code cmis.trusted-origins} lists it.
     */
    @RegisterExtension
    static WireMockExtension foreign = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort())
            .build();

    /** A host name that resolves nowhere, reached only through the HTTP proxy {@link #routeToWireMock} installs. */
    private static final String PROXIED_HOST = "repo.invalid";

    private CmisProperties props;

    private CmisAtomPubClient client;

    @BeforeEach
    void createClient() {
        props = new CmisProperties();
        props.setBaseUrl(wm.baseUrl() + BASE_PATH);
        props.setRepositoryId("default");
        props.setUsername(USERNAME);
        props.setPassword(PASSWORD);
        props.setConnectTimeout(Duration.ofSeconds(2));
        props.setReadTimeout(Duration.ofSeconds(2));
        client = new CmisAtomPubClient(props);
    }

    @Test
    void createsDocumentThroughThreeAtomPubCalls() throws Exception {
        stubHappyPath();

        String id = createDocument(client);

        assertEquals(OBJECT_ID, id);
        wm.verify(3, anyRequestedFor(anyUrl()));

        wm.verify(1, getRequestedFor(urlPathEqualTo(BASE_PATH))
                .withHeader("Authorization", equalTo(EXPECTED_AUTH))
                .withHeader("Accept", equalTo(SERVICE_DOCUMENT_TYPE)));

        wm.verify(1, getRequestedFor(urlPathEqualTo(OBJECT_BY_PATH))
                .withQueryParam("path", equalTo(FOLDER))
                .withHeader("Authorization", equalTo(EXPECTED_AUTH))
                .withHeader("Accept", equalTo(ENTRY_TYPE)));
        LoggedRequest byPath = wm.findAll(getRequestedFor(urlPathEqualTo(OBJECT_BY_PATH))).get(0);
        String byPathUrl = byPath.getUrl();
        assertTrue(byPathUrl.contains("path=%2Fokm%3Aroot%2F"), byPathUrl);
        String upperUrl = byPathUrl.toUpperCase(Locale.ROOT);
        assertFalse(byPathUrl.contains("{") || byPathUrl.contains("}")
                || upperUrl.contains("%7B") || upperUrl.contains("%7D"), byPathUrl);
        for (String variable : UNSET_TEMPLATE_VARIABLES) {
            QueryParameter parameter = byPath.queryParameter(variable);
            assertTrue(parameter.isPresent(), variable + " missing from " + byPathUrl);
            assertEquals(List.of(""), parameter.values(), variable);
        }

        wm.verify(1, postRequestedFor(urlPathEqualTo(CHILDREN))
                .withQueryParam("id", equalTo("root"))
                .withQueryParam("versioningState", equalTo("none"))
                .withHeader("Authorization", equalTo(EXPECTED_AUTH))
                .withHeader("Content-Type", equalTo(ENTRY_TYPE))
                .withHeader("Accept", equalTo(ENTRY_TYPE)));
        wm.verify(0, anyRequestedFor(urlPathEqualTo(DESCENDANTS)));

        Element entry = parse(wm.findAll(postRequestedFor(urlPathEqualTo(CHILDREN))).get(0).getBody());
        assertEquals(ATOM, entry.getNamespaceURI());
        assertEquals("entry", entry.getLocalName());
        assertEquals(NAME, childText(entry, ATOM, "title"));
        Element content = child(entry, CMISRA, "content");
        assertEquals(MIME_TYPE, childText(content, CMISRA, "mediatype"));
        assertArrayEquals(CONTENT, Base64.getDecoder().decode(childText(content, CMISRA, "base64")));
        Element properties = child(child(entry, CMISRA, "object"), CMIS, "properties");
        assertEquals(OBJECT_TYPE, propertyValue(properties, "propertyId", "cmis:objectTypeId"));
        assertEquals(NAME, propertyValue(properties, "propertyString", "cmis:name"));
    }

    @Test
    void serviceDocumentUnauthorizedRaisesRestClientResponseException() {
        wm.stubFor(get(urlPathEqualTo(BASE_PATH)).willReturn(aResponse().withStatus(401)));

        RestClientResponseException e = assertThrows(RestClientResponseException.class, () -> createDocument(client));

        assertInstanceOf(HttpClientErrorException.class, e);
        assertEquals(401, e.getStatusCode().value());
        assertEquals("CMIS service document request failed with HTTP status 401", e.getMessage());
        wm.verify(1, getRequestedFor(urlPathEqualTo(BASE_PATH)));
        wm.verify(0, anyRequestedFor(urlPathEqualTo(OBJECT_BY_PATH)));
        wm.verify(0, anyRequestedFor(urlPathEqualTo(CHILDREN)));
    }

    static Stream<Arguments> errorStatuses() {
        return Stream.of(
                Arguments.of(SERVICE_DOCUMENT, 503, HttpServerErrorException.class),
                Arguments.of(FOLDER_ENTRY, 404, HttpClientErrorException.class),
                Arguments.of(CREATE_DOCUMENT, 409, HttpClientErrorException.class),
                Arguments.of(CREATE_DOCUMENT, 500, HttpServerErrorException.class),
                Arguments.of(CREATE_DOCUMENT, 502, HttpServerErrorException.class));
    }

    /**
     * An error status keeps Spring's 4xx/5xx exception type and code, but its message is fixed text: the caller's
     * 500 body reports the message verbatim, so the upstream error page, its stack trace and its size never reach
     * the caller. The request that failed is sent once and no later step runs.
     */
    @ParameterizedTest
    @MethodSource("errorStatuses")
    void errorStatusRaisesFixedMessageWithoutUpstreamBody(String step, int status,
            Class<? extends RestClientResponseException> expectedType) {
        stubFailingStep(step, aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "text/html;charset=UTF-8")
                .withBody(UPSTREAM_ERROR_PAGE));

        RestClientResponseException e = assertThrows(RestClientResponseException.class, () -> createDocument(client));

        assertInstanceOf(expectedType, e);
        assertEquals(status, e.getStatusCode().value());
        assertEquals("CMIS " + step + " request failed with HTTP status " + status, e.getMessage());
        assertFalse(e.getMessage().contains(STACK_TRACE_MARKER), e.getMessage());
        assertEquals(0, e.getResponseBodyAsByteArray().length);
        verifyRequestsThrough(step);
    }

    /**
     * A response slower than the read timeout fails with a fixed message that names neither the URL nor a Java
     * type, and is not retried. The GETs time out while the connection waits for the status line; the POST, whose
     * status line RestClient reads only when it handles the response, times out there.
     */
    @ParameterizedTest
    @ValueSource(strings = {SERVICE_DOCUMENT, FOLDER_ENTRY, CREATE_DOCUMENT})
    void readTimeoutRaisesFixedMessageWithoutRetry(String step) {
        props.setReadTimeout(Duration.ofMillis(300));
        CmisAtomPubClient impatient = new CmisAtomPubClient(props);
        stubFailingStep(step, aResponse().withStatus(200).withFixedDelay(2_000));

        ResourceAccessException e = assertThrows(ResourceAccessException.class, () -> createDocument(impatient));

        assertEquals("CMIS " + step + " request timed out", e.getMessage());
        assertInstanceOf(SocketTimeoutException.class, e.getCause());
        verifyRequestsThrough(step);
    }

    /**
     * A connection broken while the created entry is awaited or read fails with a fixed message, and the POST is
     * never re-sent, so no second document is created.
     */
    @ParameterizedTest
    @EnumSource(Fault.class)
    void createDocumentConnectionFaultRaisesFixedMessageAndIsSentOnce(Fault fault) {
        stubFailingStep(CREATE_DOCUMENT, aResponse().withFault(fault));

        ResourceAccessException e = assertThrows(ResourceAccessException.class, () -> createDocument(client));

        assertEquals("CMIS create-document request failed with an I/O error", e.getMessage());
        assertInstanceOf(IOException.class, e.getCause());
        verifyRequestsThrough(CREATE_DOCUMENT);
    }

    static Stream<Arguments> getStepFaults() {
        return Stream.of(SERVICE_DOCUMENT, FOLDER_ENTRY)
                .flatMap(step -> Arrays.stream(Fault.values()).map(fault -> Arguments.of(step, fault)));
    }

    /**
     * A GET whose connection is reset, closed before any response, answered with garbage or broken inside the body
     * fails with a fixed message and is sent exactly once: the transport never re-sends a bodiless request after a
     * connectivity failure, as the JDK {@code HttpURLConnection} did once, and no later step runs.
     */
    @ParameterizedTest
    @MethodSource("getStepFaults")
    void getConnectionFaultRaisesFixedMessageAndIsSentOnce(String step, Fault fault) {
        stubFailingStep(step, aResponse().withFault(fault));

        ResourceAccessException e = assertThrows(ResourceAccessException.class, () -> createDocument(client));

        assertEquals("CMIS " + step + " request failed with an I/O error", e.getMessage());
        assertInstanceOf(IOException.class, e.getCause());
        verifyRequestsThrough(step);
    }

    /**
     * A connect that does not complete within the connect timeout fails with the same fixed timeout message as a
     * read timeout, and is not attempted again. The upstream is a listening socket that never accepts, whose
     * backlog the test fills first, so the kernel leaves every further connection attempt unanswered.
     */
    @Test
    void connectTimeoutRaisesFixedMessageWithoutRetry() throws IOException {
        try (ServerSocket unaccepting = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            List<Socket> backlog = new ArrayList<>();
            try {
                InetSocketAddress address = new InetSocketAddress(unaccepting.getInetAddress(),
                        unaccepting.getLocalPort());
                fillBacklog(address, backlog);
                props.setBaseUrl("http://" + address.getAddress().getHostAddress() + ":" + address.getPort()
                        + BASE_PATH);
                Duration connectTimeout = Duration.ofSeconds(1);
                props.setConnectTimeout(connectTimeout);
                CmisAtomPubClient unanswered = new CmisAtomPubClient(props);

                long start = System.nanoTime();
                ResourceAccessException e = assertThrows(ResourceAccessException.class,
                        () -> createDocument(unanswered));
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                assertEquals("CMIS service document request timed out", e.getMessage());
                assertInstanceOf(SocketTimeoutException.class, e.getCause());
                // One connect attempt: a second one would wait out the connect timeout again.
                assertTrue(elapsed.compareTo(connectTimeout.minusMillis(50)) >= 0
                        && elapsed.compareTo(connectTimeout.multipliedBy(2)) < 0, elapsed::toString);
            } finally {
                for (Socket socket : backlog) {
                    socket.close();
                }
            }
        }
    }

    /** Every status HttpURLConnection followed for a GET, each with an absolute and with a relative Location. */
    static Stream<Arguments> followedRedirects() {
        return IntStream.of(300, 301, 302, 303, 307).boxed()
                .flatMap(status -> Stream.of(Arguments.of(status, true), Arguments.of(status, false)));
    }

    /**
     * A GET redirect that keeps the scheme is followed, and a hop to the same host and port keeps the service
     * account and the {@code Accept} header.
     */
    @ParameterizedTest
    @MethodSource("followedRedirects")
    void sameSchemeGetRedirectIsFollowedWithTheServiceAccount(int status, boolean absoluteLocation) {
        wm.stubFor(get(urlPathEqualTo(BASE_PATH)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Location", absoluteLocation ? wm.baseUrl() + MOVED : MOVED)));
        stubMovedServiceDocument();
        stubFolderEntry(true);
        stubCreate(true);

        assertEquals(OBJECT_ID, createDocument(client));

        wm.verify(1, getRequestedFor(urlPathEqualTo(BASE_PATH)));
        wm.verify(1, getRequestedFor(urlPathEqualTo(MOVED))
                .withHeader("Authorization", equalTo(expectedAuthorization()))
                .withHeader("Accept", equalTo(SERVICE_DOCUMENT_TYPE)));
        wm.verify(4, anyRequestedFor(anyUrl()));
    }

    /**
     * A redirect hop to another destination (host or port) carries no {@code Authorization} header, nor does any
     * hop after it, even one back to the original host, as HttpURLConnection removed it for the rest of the
     * exchange; the next request is a new exchange and authenticates again. {@code 127.0.0.1} and
     * {@code localhost} reach the same WireMock but are different destinations.
     */
    @Test
    void redirectToAnotherDestinationDropsTheServiceAccountForTheRestOfTheExchange() {
        wm.stubFor(get(urlPathEqualTo(BASE_PATH)).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", "http://127.0.0.1:" + wm.getPort() + MOVED_ELSEWHERE)));
        wm.stubFor(get(urlPathEqualTo(MOVED_ELSEWHERE)).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", wm.baseUrl() + MOVED)));
        stubMovedServiceDocument();
        stubFolderEntry(true);
        stubCreate(true);

        assertEquals(OBJECT_ID, createDocument(client));

        wm.verify(1, getRequestedFor(urlPathEqualTo(BASE_PATH))
                .withHeader("Authorization", equalTo(expectedAuthorization())));
        wm.verify(1, getRequestedFor(urlPathEqualTo(MOVED_ELSEWHERE)).withoutHeader("Authorization"));
        wm.verify(1, getRequestedFor(urlPathEqualTo(MOVED)).withoutHeader("Authorization")
                .withHeader("Accept", equalTo(SERVICE_DOCUMENT_TYPE)));
        wm.verify(1, getRequestedFor(urlPathEqualTo(OBJECT_BY_PATH))
                .withHeader("Authorization", equalTo(expectedAuthorization())));
        wm.verify(1, postRequestedFor(urlPathEqualTo(CHILDREN))
                .withHeader("Authorization", equalTo(expectedAuthorization())));
        wm.verify(5, anyRequestedFor(anyUrl()));
    }

    static Stream<Arguments> unfollowedGetRedirects() {
        return Stream.of(
                Arguments.of(Named.of("301 from http to https, as the public OpenKM demo answers", 301), "https"),
                Arguments.of(Named.of("308, which HttpURLConnection does not follow", 308), "http"));
    }

    /**
     * A GET redirect HttpURLConnection did not follow is returned as received and nothing is sent to its
     * Location, so its HTML page is read as the service document; a page with a DOCTYPE, as such pages carry,
     * is rejected as not well-formed XML.
     */
    @ParameterizedTest
    @MethodSource("unfollowedGetRedirects")
    void unfollowedGetRedirectIsReadAsTheResponse(int status, String scheme) {
        wm.stubFor(get(urlPathEqualTo(BASE_PATH)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Location", scheme + "://localhost:" + wm.getPort() + MOVED)
                .withHeader("Content-Type", "text/html; charset=iso-8859-1")
                .withBody(REDIRECT_PAGE)));
        stubMovedServiceDocument();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> createDocument(client));

        assertEquals("CMIS service document response is not well-formed XML", e.getMessage());
        wm.verify(1, anyRequestedFor(anyUrl()));
    }

    /**
     * A redirect answering the create-document POST is never followed, whatever its status, so the entry is posted
     * once; the redirect has no body, which fails the step.
     */
    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void createDocumentRedirectIsNotFollowed(int status) {
        stubServiceDocument(true);
        stubFolderEntry(true);
        wm.stubFor(post(urlPathEqualTo(CHILDREN)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Location", wm.baseUrl() + MOVED)));
        wm.stubFor(any(urlPathEqualTo(MOVED)).willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", ENTRY_TYPE + ";charset=UTF-8")
                .withBody(createdEntry(true))));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> createDocument(client));

        assertEquals("CMIS create-document response has no body", e.getMessage());
        verifyRequestsThrough(CREATE_DOCUMENT);
    }

    /**
     * A GET that keeps redirecting is sent at most {@value #MAX_REDIRECT_REQUESTS} times, the JDK default of
     * {@code http.maxRedirects} that HttpURLConnection applied, and then fails as an I/O error.
     */
    @Test
    void redirectLoopFailsAfterTheRedirectLimit() {
        wm.stubFor(get(urlPathEqualTo(BASE_PATH)).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", wm.baseUrl() + BASE_PATH)));

        ResourceAccessException e = assertThrows(ResourceAccessException.class, () -> createDocument(client));

        assertEquals("CMIS service document request failed with an I/O error", e.getMessage());
        assertInstanceOf(IOException.class, e.getCause());
        wm.verify(MAX_REDIRECT_REQUESTS, getRequestedFor(urlPathEqualTo(BASE_PATH)));
        wm.verify(MAX_REDIRECT_REQUESTS, anyRequestedFor(anyUrl()));
    }

    @Test
    void connectionRefusedRaisesFixedMessageWithoutUrl() throws IOException {
        // A bound socket that never listens refuses connections and keeps the port from being reused meanwhile, which a
        // closed ServerSocket(0) cannot guarantee on a host where many builds open ports concurrently.
        try (Socket reserved = reserveRefusingPort()) {
            props.setBaseUrl("http://localhost:" + reserved.getLocalPort() + BASE_PATH);
            CmisAtomPubClient unreachable = new CmisAtomPubClient(props);

            ResourceAccessException e = assertThrows(ResourceAccessException.class,
                    () -> createDocument(unreachable));

            assertEquals("CMIS service document request failed with an I/O error", e.getMessage());
            assertInstanceOf(ConnectException.class, e.getCause());
        }
    }

    /**
     * RestClient parses the Content-Type before any status handler runs, so a value that is not a media type would
     * otherwise reach the caller inside Spring's {@code Invalid mime type "..."} message.
     */
    @ParameterizedTest
    @ValueSource(ints = {200, 500})
    void unparseableContentTypeRaisesFixedMessage(int status) {
        wm.stubFor(get(urlPathEqualTo(BASE_PATH)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", STACK_TRACE_MARKER)
                .withBody(serviceDocument(true))));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> createDocument(client));

        assertEquals("CMIS service document response has an invalid Content-Type", e.getMessage());
        verifyRequestsThrough(SERVICE_DOCUMENT);
    }

    @Test
    void unknownRepositoryRaisesIllegalState() {
        stubHappyPath();
        props.setRepositoryId("other");
        CmisAtomPubClient otherRepository = new CmisAtomPubClient(props);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> createDocument(otherRepository));

        assertTrue(e.getMessage().contains("'other'"), e.getMessage());
        wm.verify(1, getRequestedFor(urlPathEqualTo(BASE_PATH)));
        wm.verify(0, anyRequestedFor(urlPathEqualTo(OBJECT_BY_PATH)));
        wm.verify(0, anyRequestedFor(urlPathEqualTo(CHILDREN)));
    }

    /** The workspace still offers a {@code query} template, so the client must select the template by type. */
    @Test
    void missingObjectByPathTemplateRaisesIllegalState() {
        stubServiceDocument(false);
        stubFolderEntry(true);
        stubCreate(true);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> createDocument(client));

        assertTrue(e.getMessage().contains("objectbypath"), e.getMessage());
        wm.verify(1, anyRequestedFor(anyUrl()));
        wm.verify(0, anyRequestedFor(urlPathEqualTo(OBJECT_BY_PATH)));
        wm.verify(0, anyRequestedFor(urlPathEqualTo(QUERY)));
    }

    /** Only the descendants tree link remains, which is a {@code rel="down"} link of the wrong type. */
    @Test
    void missingFeedDownLinkRaisesIllegalState() {
        stubServiceDocument(true);
        stubFolderEntry(false);
        stubCreate(true);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> createDocument(client));

        assertTrue(e.getMessage().contains("children link"), e.getMessage());
        wm.verify(1, getRequestedFor(urlPathEqualTo(OBJECT_BY_PATH)));
        wm.verify(0, anyRequestedFor(urlPathEqualTo(CHILDREN)));
        wm.verify(0, anyRequestedFor(urlPathEqualTo(DESCENDANTS)));
    }

    @Test
    void createdEntryWithoutObjectIdRaisesIllegalState() {
        stubServiceDocument(true);
        stubFolderEntry(true);
        stubCreate(false);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> createDocument(client));

        assertTrue(e.getMessage().contains("cmis:objectId"), e.getMessage());
        wm.verify(1, postRequestedFor(urlPathEqualTo(CHILDREN)));
    }

    /** Each synthetic unset value, paired with the test profile's value of the other key, and the key it lacks. */
    static Stream<Arguments> unsetServiceAccount() {
        return Stream.of(
                Arguments.of(null, PASSWORD, "cmis.username"),
                Arguments.of("", PASSWORD, "cmis.username"),
                Arguments.of("   ", PASSWORD, "cmis.username"),
                Arguments.of(USERNAME, null, "cmis.password"),
                Arguments.of(USERNAME, "", "cmis.password"));
    }

    /**
     * The client authenticates every request, so an unset user name or password fails the operation, naming the
     * key, before anything is sent: no request ever goes out without credentials.
     */
    @ParameterizedTest
    @MethodSource("unsetServiceAccount")
    void blankServiceAccountRaisesIllegalStateBeforeAnyRequest(String username, String password, String missingKey) {
        stubHappyPath();
        props.setUsername(username);
        props.setPassword(password);
        CmisAtomPubClient unauthenticated = new CmisAtomPubClient(props);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> createDocument(unauthenticated));

        assertTrue(e.getMessage().contains(missingKey), e.getMessage());
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * Repository-supplied URLs off the trusted origins, each with the step that would request it: the folder entry
     * the objectbypath template names, or the create-document POST to the children link. The second element is
     * the start of that URL, which the stubbed response completes with the usual path and query.
     */
    static Stream<Arguments> untrustedRepositoryUrls() {
        String sameServer = "localhost:" + wm.getPort();
        return Stream.of(
                Arguments.of(FOLDER_ENTRY, Named.of("template on another port", foreign.baseUrl())),
                Arguments.of(FOLDER_ENTRY, Named.of("template on another host name of the same server",
                        "http://127.0.0.1:" + wm.getPort())),
                Arguments.of(FOLDER_ENTRY, Named.of("template on https", "https://" + sameServer)),
                Arguments.of(FOLDER_ENTRY, Named.of("template on ftp", "ftp://" + sameServer)),
                Arguments.of(FOLDER_ENTRY, Named.of("template on a port above 65535", "http://localhost:99999")),
                Arguments.of(FOLDER_ENTRY, Named.of("relative template", "")),
                Arguments.of(FOLDER_ENTRY, Named.of("template that does not parse", wm.baseUrl() + "/bad path")),
                Arguments.of(CREATE_DOCUMENT, Named.of("children link on another port", foreign.baseUrl())),
                Arguments.of(CREATE_DOCUMENT, Named.of("network-path children link to another host name",
                        "//127.0.0.1:" + wm.getPort())),
                Arguments.of(CREATE_DOCUMENT, Named.of("children link on ftp", "ftp://" + sameServer)),
                Arguments.of(CREATE_DOCUMENT, Named.of("children link that does not parse",
                        wm.baseUrl() + "/bad path")));
    }

    /**
     * A URL the repository's responses name is requested only on the origin of {@code cmis.base-url}: one on another
     * origin, without an {@code http} or {@code https} scheme and host, or unparseable fails its step with a fixed
     * message naming neither the URL nor its host, before anything, the service account included, is sent to it.
     * The foreign origin receives nothing, and the repository only the steps before.
     */
    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("untrustedRepositoryUrls")
    void repositoryUrlOffTheTrustedOriginsIsRefusedBeforeAnyRequestToIt(String step, String base) {
        boolean template = FOLDER_ENTRY.equals(step);
        stubServiceDocument(template ? base : wm.baseUrl());
        stubFolderEntry(wm, template ? wm.baseUrl() : base);
        stubCreate(wm);
        stubFolderEntry(foreign, foreign.baseUrl());
        stubCreate(foreign);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> createDocument(client));

        assertEquals("CMIS " + step + " URL is not on a trusted repository origin (cmis.base-url, cmis.trusted-origins)",
                e.getMessage());
        foreign.verify(0, anyRequestedFor(anyUrl()));
        verifyRequestsThrough(STEPS.get(STEPS.indexOf(step) - 1));
    }

    /** Spellings of the foreign origin in {@code cmis.trusted-origins}: scheme and host case, a "/" path, padding. */
    static Stream<String> trustedForeignOrigins() {
        return Stream.of(foreign.baseUrl(), foreign.baseUrl() + "/", "HTTP://LOCALHOST:" + foreign.getPort(),
                " " + foreign.baseUrl() + " ");
    }

    /**
     * A repository that legitimately serves its folder entry and children feed on another origin works once that
     * origin is listed in {@code cmis.trusted-origins}: both requests go there, with the service account.
     */
    @ParameterizedTest
    @MethodSource("trustedForeignOrigins")
    void repositoryUrlsOnAListedTrustedOriginAreRequestedWithTheServiceAccount(String trustedOrigin) {
        props.setTrustedOrigins(List.of("https://unrelated.example", trustedOrigin));
        CmisAtomPubClient trusting = new CmisAtomPubClient(props);
        stubServiceDocument(foreign.baseUrl());
        stubFolderEntry(foreign, foreign.baseUrl());
        stubCreate(foreign);

        assertEquals(OBJECT_ID, createDocument(trusting));

        wm.verify(1, getRequestedFor(urlPathEqualTo(BASE_PATH)).withHeader("Authorization", equalTo(EXPECTED_AUTH)));
        wm.verify(1, anyRequestedFor(anyUrl()));
        foreign.verify(1, getRequestedFor(urlPathEqualTo(OBJECT_BY_PATH))
                .withQueryParam("path", equalTo(FOLDER))
                .withHeader("Authorization", equalTo(EXPECTED_AUTH)));
        foreign.verify(1, postRequestedFor(urlPathEqualTo(CHILDREN))
                .withQueryParam("versioningState", equalTo("none"))
                .withHeader("Authorization", equalTo(EXPECTED_AUTH)));
        foreign.verify(2, anyRequestedFor(anyUrl()));
    }

    /**
     * Spellings of the {@code cmis.base-url} origin a repository may use for its template and children link, each
     * the same origin: scheme and host in another case, a relative or a network-path children link.
     */
    static Stream<Arguments> sameOriginSpellings() {
        String port = String.valueOf(wm.getPort());
        return Stream.of(
                Arguments.of(Named.of("upper-case host", "http://LOCALHOST:" + port), wm.baseUrl()),
                Arguments.of(Named.of("upper-case scheme", "HTTP://localhost:" + port), "HTTP://localhost:" + port),
                Arguments.of(Named.of("relative children link", wm.baseUrl()), ""),
                Arguments.of(Named.of("network-path children link", wm.baseUrl()), "//LOCALHOST:" + port));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sameOriginSpellings")
    void sameOriginSpelledDifferentlyIsTrusted(String templateBase, String childrenBase) {
        stubServiceDocument(templateBase);
        stubFolderEntry(wm, childrenBase);
        stubCreate(wm);

        assertEquals(OBJECT_ID, createDocument(client));

        verifyRequestsThrough(CREATE_DOCUMENT);
        wm.verify(3, anyRequestedFor(anyUrl()).withHeader("Authorization", equalTo(EXPECTED_AUTH)));
        foreign.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * An explicit default port names the same origin as none: with {@code cmis.base-url} on
     * {@code http://repo.invalid} and its links on {@code http://repo.invalid:80}, or the other way round, every link
     * is requested. The host resolves nowhere, so the JVM's default proxy selector, which the client consults for
     * every route, sends it to WireMock as an HTTP proxy.
     */
    @ParameterizedTest(name = "cmis.base-url port \"{0}\", link port \"{1}\"")
    @CsvSource({"'',:80", ":80,''"})
    void explicitDefaultPortIsTheSameOrigin(String baseUrlPort, String linkPort) {
        props.setBaseUrl("http://" + PROXIED_HOST + baseUrlPort + BASE_PATH);
        CmisAtomPubClient proxied = new CmisAtomPubClient(props);
        stubServiceDocument("http://" + PROXIED_HOST + linkPort);
        stubFolderEntry(wm, "http://" + PROXIED_HOST + linkPort);
        stubCreate(wm);

        ProxySelector previous = ProxySelector.getDefault();
        ProxySelector.setDefault(routeToWireMock());
        try {
            assertEquals(OBJECT_ID, createDocument(proxied));
        } finally {
            ProxySelector.setDefault(previous);
        }

        verifyRequestsThrough(CREATE_DOCUMENT);
        wm.verify(3, anyRequestedFor(anyUrl()).withHeader("Authorization", equalTo(EXPECTED_AUTH)));
    }

    /**
     * Entries that are not an {@code http} or {@code https} origin: no scheme, another scheme, a path, a query, a
     * fragment, user information, no host, a port out of range, a host that does not parse, and blank. Each fails
     * construction naming the key and the entry's index, never its value.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "repo.example.com", "//repo.example.com", "repo.example.com:8443",
            "ftp://repo.example.com", "https://repo.example.com/cmis", "https://repo.example.com/?a=b",
            "https://repo.example.com#top", "https://user:secret@repo.example.com", "http:///cmis",
            "http://repo.example.com:0", "http://repo.example.com:65536", "https://repo example.com"})
    void invalidTrustedOriginFailsConstruction(String entry) {
        props.setTrustedOrigins(Arrays.asList("https://repo.example.com:8443/", entry));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new CmisAtomPubClient(props));

        assertEquals("cmis.trusted-origins[1] is not an http or https origin (scheme://host[:port])", e.getMessage());
    }

    /** Routes {@link #PROXIED_HOST} through WireMock as an HTTP proxy and every other host directly. */
    private static ProxySelector routeToWireMock() {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return PROXIED_HOST.equalsIgnoreCase(uri.getHost())
                        ? List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", wm.getPort())))
                        : List.of(Proxy.NO_PROXY);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                // Nothing to record: a failed connect fails the request, which the test reports.
            }
        };
    }

    private static String createDocument(CmisAtomPubClient cmisClient) {
        return cmisClient.createDocumentByPath(FOLDER, NAME, MIME_TYPE, OBJECT_TYPE, VERSIONING_STATE, CONTENT);
    }

    /** Registered per test, because the extension resets its stubs after each one. */
    private static void stubHappyPath() {
        stubServiceDocument(true);
        stubFolderEntry(true);
        stubCreate(true);
    }

    /** Stubs every step before {@code step} to succeed and {@code step} itself to answer with {@code failure}. */
    private static void stubFailingStep(String step, ResponseDefinitionBuilder failure) {
        switch (step) {
            case SERVICE_DOCUMENT -> wm.stubFor(get(urlPathEqualTo(BASE_PATH)).willReturn(failure));
            case FOLDER_ENTRY -> {
                stubServiceDocument(true);
                wm.stubFor(get(urlPathEqualTo(OBJECT_BY_PATH)).willReturn(failure));
            }
            case CREATE_DOCUMENT -> {
                stubServiceDocument(true);
                stubFolderEntry(true);
                wm.stubFor(post(urlPathEqualTo(CHILDREN)).willReturn(failure));
            }
            default -> fail("Unknown step " + step);
        }
    }

    /** Each step up to and including {@code step} was requested exactly once, and nothing else was requested. */
    private static void verifyRequestsThrough(String step) {
        int last = STEPS.indexOf(step);
        wm.verify(1, getRequestedFor(urlPathEqualTo(BASE_PATH)));
        wm.verify(last >= STEPS.indexOf(FOLDER_ENTRY) ? 1 : 0, getRequestedFor(urlPathEqualTo(OBJECT_BY_PATH)));
        wm.verify(last >= STEPS.indexOf(CREATE_DOCUMENT) ? 1 : 0, postRequestedFor(urlPathEqualTo(CHILDREN)));
        wm.verify(last + 1, anyRequestedFor(anyUrl()));
    }

    /** {@code prefix} followed by {@code x} characters up to {@code length} characters in all. */
    private static String padded(String prefix, int length) {
        return prefix + "x".repeat(Math.max(0, length - prefix.length()));
    }

    /**
     * A socket bound to a free port on every local address that never listens or connects: a connection to its port
     * is refused while it is open, and with SO_REUSEADDR off no other socket can bind that port meanwhile.
     */
    private static Socket reserveRefusingPort() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(0));
            return socket;
        } catch (IOException | RuntimeException ex) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    private static void stubServiceDocument(boolean withObjectByPath) {
        wm.stubFor(get(urlPathEqualTo(BASE_PATH))
                .willReturn(okForContentType(SERVICE_DOCUMENT_TYPE + ";charset=UTF-8",
                        serviceDocument(withObjectByPath))));
    }

    private static void stubFolderEntry(boolean withFeedLink) {
        wm.stubFor(get(urlPathEqualTo(OBJECT_BY_PATH))
                .withQueryParam("path", equalTo(FOLDER))
                .willReturn(okForContentType(ENTRY_TYPE + ";charset=UTF-8", folderEntry(withFeedLink))));
    }

    private static void stubCreate(boolean withObjectId) {
        wm.stubFor(post(urlPathEqualTo(CHILDREN))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", ENTRY_TYPE + ";charset=UTF-8")
                        .withBody(createdEntry(withObjectId))));
    }

    /** Serves at {@link #BASE_PATH} a service document whose objectbypath template starts with {@code base}. */
    private static void stubServiceDocument(String base) {
        wm.stubFor(get(urlPathEqualTo(BASE_PATH))
                .willReturn(okForContentType(SERVICE_DOCUMENT_TYPE + ";charset=UTF-8", serviceDocument(base, true))));
    }

    /** Serves on {@code server} the folder entry whose children link starts with {@code base}. */
    private static void stubFolderEntry(WireMockExtension server, String base) {
        server.stubFor(get(urlPathEqualTo(OBJECT_BY_PATH))
                .withQueryParam("path", equalTo(FOLDER))
                .willReturn(okForContentType(ENTRY_TYPE + ";charset=UTF-8", folderEntry(base, true))));
    }

    /** Accepts on {@code server} the posted entry and answers with the created entry. */
    private static void stubCreate(WireMockExtension server) {
        server.stubFor(post(urlPathEqualTo(CHILDREN))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", ENTRY_TYPE + ";charset=UTF-8")
                        .withBody(createdEntry(true))));
    }

    /** The service document of {@link #stubServiceDocument}, served at the redirect target {@link #MOVED}. */
    private static void stubMovedServiceDocument() {
        wm.stubFor(get(urlPathEqualTo(MOVED))
                .willReturn(okForContentType(SERVICE_DOCUMENT_TYPE + ";charset=UTF-8", serviceDocument(true))));
    }

    /**
     * The HTTP Basic {@code Authorization} value of the service account {@link #createClient} configures, encoded
     * as Spring's {@code HttpHeaders.setBasicAuth} encodes it.
     */
    private String expectedAuthorization() {
        return "Basic " + Base64.getEncoder().encodeToString(
                (props.getUsername() + ":" + props.getPassword()).getBytes(StandardCharsets.ISO_8859_1));
    }

    /**
     * Connects to {@code address}, which listens but never accepts, until its accept queue is full and a connection
     * attempt goes unanswered; each socket that connected is added to {@code backlog}, which the caller closes.
     */
    private static void fillBacklog(InetSocketAddress address, List<Socket> backlog) throws IOException {
        for (int attempt = 0; attempt < 64; attempt++) {
            Socket socket = new Socket();
            boolean connected = false;
            try {
                socket.connect(address, 200);
                connected = true;
            } catch (SocketTimeoutException queueFull) {
                return;
            } finally {
                if (connected) {
                    backlog.add(socket);
                } else {
                    socket.close();
                }
            }
        }
        fail("the accept queue of " + address + " never filled");
    }

    /**
     * The repository's service document: one workspace for repository {@code default}, offering the objectbypath
     * template, or only a query template when {@code withObjectByPath} is false.
     */
    private static String serviceDocument(boolean withObjectByPath) {
        return serviceDocument(wm.baseUrl(), withObjectByPath);
    }

    /** {@link #serviceDocument(boolean)} with its template URL starting with {@code base} instead of WireMock's. */
    private static String serviceDocument(String base, boolean withObjectByPath) {
        String uriTemplate = withObjectByPath
                ? "<cmisra:uritemplate>"
                        + "<cmisra:template>" + base + OBJECT_BY_PATH + "?path={path}"
                        + "&amp;filter={filter}&amp;includeAllowableActions={includeAllowableActions}"
                        + "&amp;includePolicyIds={includePolicyIds}&amp;includeRelationships={includeRelationships}"
                        + "&amp;includeACL={includeACL}&amp;renditionFilter={renditionFilter}</cmisra:template>"
                        + "<cmisra:type>objectbypath</cmisra:type>"
                        + "<cmisra:mediatype>application/atom+xml;type=entry</cmisra:mediatype>"
                        + "</cmisra:uritemplate>"
                : "<cmisra:uritemplate>"
                        + "<cmisra:template>" + base + QUERY + "?q={q}&amp;searchAllVersions={searchAllVersions}"
                        + "&amp;maxItems={maxItems}&amp;skipCount={skipCount}</cmisra:template>"
                        + "<cmisra:type>query</cmisra:type>"
                        + "<cmisra:mediatype>application/atom+xml;type=feed</cmisra:mediatype>"
                        + "</cmisra:uritemplate>";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<app:service xmlns:app=\"" + APP + "\" xmlns:atom=\"" + ATOM + "\" xmlns:cmis=\"" + CMIS
                + "\" xmlns:cmisra=\"" + CMISRA + "\">"
                + "<app:workspace>"
                + "<atom:title>default</atom:title>"
                + "<cmisra:repositoryInfo>"
                + "<cmis:repositoryId>default</cmis:repositoryId>"
                + "<cmis:repositoryName>OpenKM</cmis:repositoryName>"
                + "<cmis:rootFolderId>root</cmis:rootFolderId>"
                + "</cmisra:repositoryInfo>"
                + uriTemplate
                + "</app:workspace>"
                + "</app:service>";
    }

    /**
     * The root folder's entry. Its descendants link precedes the children feed, so the client must choose the
     * {@code rel="down"} link by type; without {@code withFeedLink} only the descendants link remains.
     */
    private static String folderEntry(boolean withFeedLink) {
        return folderEntry(wm.baseUrl(), withFeedLink);
    }

    /** {@link #folderEntry(boolean)} with its link URLs starting with {@code base} instead of WireMock's. */
    private static String folderEntry(String base, boolean withFeedLink) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<atom:entry xmlns:atom=\"" + ATOM + "\" xmlns:cmis=\"" + CMIS + "\" xmlns:cmisra=\"" + CMISRA
                + "\">"
                + "<atom:id>urn:uuid:root</atom:id>"
                + "<atom:title>okm:root</atom:title>"
                + "<atom:link rel=\"down\" type=\"application/cmistree+xml\" href=\"" + base + DESCENDANTS
                + "?id=root\"/>"
                + (withFeedLink
                        ? "<atom:link rel=\"down\" type=\"application/atom+xml;type=feed\" href=\"" + base + CHILDREN
                                + "?id=root\"/>"
                        : "")
                + "<cmisra:object><cmis:properties>"
                + "<cmis:propertyId propertyDefinitionId=\"cmis:objectId\"><cmis:value>root</cmis:value>"
                + "</cmis:propertyId>"
                + "<cmis:propertyId propertyDefinitionId=\"cmis:baseTypeId\"><cmis:value>cmis:folder</cmis:value>"
                + "</cmis:propertyId>"
                + "</cmis:properties></cmisra:object>"
                + "</atom:entry>";
    }

    /**
     * The created document's entry. Its object ID has OpenKM's form {@code /okm:root/<name>} and is omitted when
     * {@code withObjectId} is false.
     */
    private static String createdEntry(boolean withObjectId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<atom:entry xmlns:atom=\"" + ATOM + "\" xmlns:cmis=\"" + CMIS + "\" xmlns:cmisra=\"" + CMISRA
                + "\">"
                + "<atom:id>urn:uuid:created</atom:id>"
                + "<atom:title>" + NAME + "</atom:title>"
                + "<cmisra:object><cmis:properties>"
                + (withObjectId
                        ? "<cmis:propertyId propertyDefinitionId=\"cmis:objectId\"><cmis:value>" + OBJECT_ID
                                + "</cmis:value></cmis:propertyId>"
                        : "")
                + "<cmis:propertyId propertyDefinitionId=\"cmis:baseTypeId\"><cmis:value>cmis:document</cmis:value>"
                + "</cmis:propertyId>"
                + "<cmis:propertyString propertyDefinitionId=\"cmis:name\"><cmis:value>" + NAME
                + "</cmis:value></cmis:propertyString>"
                + "</cmis:properties></cmisra:object>"
                + "</atom:entry>";
    }

    /** Parses the posted entry namespace-aware, with DOCTYPEs (and so external entities) rejected. */
    private static Element parse(byte[] body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body)).getDocumentElement();
    }

    /** The first direct child element with the given namespace and local name; fails the test when there is none. */
    private static Element child(Element parent, String ns, String local) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && ns.equals(element.getNamespaceURI())
                    && local.equals(element.getLocalName())) {
                return element;
            }
        }
        return fail("<" + parent.getLocalName() + "> has no child {" + ns + "}" + local);
    }

    private static String childText(Element parent, String ns, String local) {
        return child(parent, ns, local).getTextContent().trim();
    }

    /** The {@code cmis:value} of the {@code cmis:<kind>} property whose {@code propertyDefinitionId} is {@code id}. */
    private static String propertyValue(Element properties, String kind, String id) {
        for (Node node = properties.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element property && CMIS.equals(property.getNamespaceURI())
                    && kind.equals(property.getLocalName())
                    && id.equals(property.getAttribute("propertyDefinitionId"))) {
                return childText(property, CMIS, "value");
            }
        }
        return fail("cmis:properties has no cmis:" + kind + " " + id);
    }
}
