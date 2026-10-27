/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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

    private static final String FOLDER = "/okm:root/";
    private static final String NAME = "pic123.jpg";
    private static final String OBJECT_ID = "/okm:root/pic123.jpg";
    private static final String MIME_TYPE = "image/jpg";
    private static final String OBJECT_TYPE = "cmis:document";
    private static final String VERSIONING_STATE = "NONE";

    /** HTTP Basic credentials of the public OpenKM demo account {@code user3}/{@code pass3} the original used. */
    private static final String EXPECTED_AUTH = "Basic dXNlcjM6cGFzczM=";

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

    private CmisProperties props;

    private CmisAtomPubClient client;

    @BeforeEach
    void createClient() {
        props = new CmisProperties();
        props.setBaseUrl(wm.baseUrl() + BASE_PATH);
        props.setRepositoryId("default");
        props.setUsername("user3");
        props.setPassword("pass3");
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
    @EnumSource(value = Fault.class,
            names = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE", "MALFORMED_RESPONSE_CHUNK"})
    void createDocumentConnectionFaultRaisesFixedMessageAndIsSentOnce(Fault fault) {
        stubFailingStep(CREATE_DOCUMENT, aResponse().withFault(fault));

        ResourceAccessException e = assertThrows(ResourceAccessException.class, () -> createDocument(client));

        assertEquals("CMIS create-document request failed with an I/O error", e.getMessage());
        assertInstanceOf(IOException.class, e.getCause());
        verifyRequestsThrough(CREATE_DOCUMENT);
    }

    @Test
    void connectionRefusedRaisesFixedMessageWithoutUrl() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        props.setBaseUrl("http://localhost:" + closedPort + BASE_PATH);
        CmisAtomPubClient unreachable = new CmisAtomPubClient(props);

        ResourceAccessException e = assertThrows(ResourceAccessException.class, () -> createDocument(unreachable));

        assertEquals("CMIS service document request failed with an I/O error", e.getMessage());
        assertInstanceOf(ConnectException.class, e.getCause());
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

    static Stream<Arguments> unsetServiceAccount() {
        return Stream.of(
                Arguments.of(null, "pass3", "cmis.username"),
                Arguments.of("", "pass3", "cmis.username"),
                Arguments.of("   ", "pass3", "cmis.username"),
                Arguments.of("user3", null, "cmis.password"),
                Arguments.of("user3", "", "cmis.password"));
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

    /**
     * The repository's service document: one workspace for repository {@code default}, offering the objectbypath
     * template, or only a query template when {@code withObjectByPath} is false.
     */
    private static String serviceDocument(boolean withObjectByPath) {
        String base = wm.baseUrl();
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
        String base = wm.baseUrl();
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
