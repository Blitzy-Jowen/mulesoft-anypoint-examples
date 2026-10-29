/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.document_integration_using_the_cmis_connector.client.CmisAtomPubClient;
import com.mulesoft.examples.document_integration_using_the_cmis_connector.service.CmisDocumentService;
import com.mulesoft.examples.document_integration_using_the_cmis_connector.support.HttpTestClient;

/**
 * Wire-level test of {@link CmisController}, the HTTP trigger of flow {@code CMIS}
 * (document-integration-using-the-cmis-connector/src/main/app/cmis-document-integration.xml:5-15), on the
 * application's own Undertow listener.
 *
 * <p>The whole application runs on a random port with the real {@link CmisDocumentService}, the real
 * {@link CmisAtomPubClient} and the SC-1 handler. The adapter, the one outbound path to the CMIS repository, is a
 * spy whose {@code cmis.base-url} points at a WireMock stand-in of the repository, so no request leaves the host:
 * the repository-failure case runs the real AtomPub adapter against that stand-in, and the other cases stub the
 * spied adapter. Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line
 * from the socket, so status line, {@code Content-Type} and body bytes are asserted as the listener writes them.
 *
 * <p>The happy paths cover the original test's empty POST, the README's GET, and PUT and OPTIONS, because the
 * original listener declares no {@code allowedMethods}; each proves the flow ran exactly once with the connector
 * arguments of the original. The error cases are the platform defaults of SC-1: the no-listener 404 for a path the
 * flow does not serve, {@code /cmis} with {@code ;} segment parameters included, and the JSON 500 for a repository
 * failure (the repository's 401) and for any other exception escaping the adapter, which the flow, having no
 * exception strategy, leaves unhandled. The original {@code DocumentIntegrationUsingTheCmisConnectorIT} assertion
 * is ported separately in {@code DocumentIntegrationUsingTheCmisConnectorTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CmisControllerTest {

    private static final String CMIS_PATH = "/cmis";

    /** Prefix the original IT asserts on the created document's JSON (DocumentIntegrationUsingTheCmisConnectorIT:29). */
    private static final String ORIGINAL_IT_PREFIX = "{\"id\":\"/okm:root/pic";

    private static final byte[] EMPTY_BODY = new byte[0];

    /** Status lines as Undertow writes them, each with the standard reason phrase of its code. */
    private static final String STATUS_OK = "HTTP/1.1 200 OK";

    private static final String STATUS_NOT_FOUND = "HTTP/1.1 404 Not Found";

    private static final String STATUS_INTERNAL_SERVER_ERROR = "HTTP/1.1 500 Internal Server Error";

    /** The exact {@code Content-Type} values the controller and SC-1 write, without parameters. */
    private static final String APPLICATION_JSON = "application/json";

    private static final String TEXT_PLAIN = "text/plain";

    /** Path of the repository's AtomPub service document, the first request the adapter makes. */
    private static final String ATOM_ROOT = "/OpenKM/cmis/atom";

    /** Stand-in for the CMIS repository; its stubs are reset after each test. */
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort())
            .build();

    private static byte[] logo;

    @LocalServerPort
    private int port;

    /** The real adapter bean, stubbed per test except where the repository's own answer is exercised. */
    @SpyBean
    private CmisAtomPubClient cmisClient;

    /** Lazy: the extension's server starts before the context, but its URL is only read when the key resolves. */
    @DynamicPropertySource
    static void cmisProperties(DynamicPropertyRegistry r) {
        r.add("cmis.base-url", () -> wm.baseUrl() + ATOM_ROOT);
    }

    @BeforeAll
    static void readLogo() throws IOException {
        logo = new ClassPathResource("mulesoft-logo.jpg").getContentAsByteArray();
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    @Test
    void getCmisReturnsCreatedDocumentId() {
        stubCreatedDocument("/okm:root/pic1.jpg");

        HttpTestClient.Response response = client().get(CMIS_PATH);

        assertCreated(response, "/okm:root/pic1.jpg");
    }

    @Test
    void postCmisWithEmptyBodyReturnsCreatedDocumentId() {
        stubCreatedDocument("/okm:root/pic1700000000000.jpg");

        HttpTestClient.Response response = client().send("POST", CMIS_PATH, Map.of(), EMPTY_BODY);

        assertCreated(response, "/okm:root/pic1700000000000.jpg");
        assertTrue(response.bodyAsString().startsWith(ORIGINAL_IT_PREFIX), response::toString);
    }

    @Test
    void putCmisIsAcceptedBecauseListenerDeclaresNoAllowedMethods() {
        stubCreatedDocument("/okm:root/pic1700000000001.jpg");

        HttpTestClient.Response response = client().send("PUT", CMIS_PATH, Map.of(), EMPTY_BODY);

        assertCreated(response, "/okm:root/pic1700000000001.jpg");
    }

    /**
     * OPTIONS reaches the controller's explicit OPTIONS mapping rather than Spring's built-in {@code Allow} reply,
     * so the flow runs and creates a document, as the Mule listener did for every method.
     */
    @Test
    void optionsCmisIsAccepted() {
        stubCreatedDocument("/okm:root/pic1700000000002.jpg");

        HttpTestClient.Response response = client().send("OPTIONS", CMIS_PATH, Map.of(), EMPTY_BODY);

        assertCreated(response, "/okm:root/pic1700000000002.jpg");
    }

    @Test
    void unknownPathReturnsNoListener404() {
        HttpTestClient.Response response = client().get("/unknown");

        assertWire(response, STATUS_NOT_FOUND, TEXT_PLAIN, "No listener for endpoint: /unknown");
        verifyNoInteractions(cmisClient);
    }

    /**
     * A path carrying {@code ;} segment parameters is not the listener's path: the Mule listener compared the raw
     * path, query removed, literally, so every method gets the no-listener 404 naming the path with its parameters,
     * and the flow never runs, so no document is created and the repository receives no request
     * ({@code config/ListenerPathConfig}). A {@code HEAD} reply carries the 404's headers without its body.
     *
     * @param method the request method
     * @param path   {@code /cmis} followed by its segment parameters
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("pathParameterRequests")
    void pathWithSegmentParametersReturnsNoListener404(String method, String path) {
        HttpTestClient.Response response = client().send(method, path, Map.of(), EMPTY_BODY);

        String body = "No listener for endpoint: " + path;
        if ("HEAD".equals(method)) {
            assertWire(response, STATUS_NOT_FOUND, TEXT_PLAIN, "");
            assertEquals(List.of(String.valueOf(body.getBytes(StandardCharsets.UTF_8).length)),
                    response.headers("Content-Length"), response::toString);
        } else {
            assertWire(response, STATUS_NOT_FOUND, TEXT_PLAIN, body);
        }
        verifyNoInteractions(cmisClient);
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    private static Stream<Arguments> pathParameterRequests() {
        return Stream.of("GET", "POST", "HEAD", "OPTIONS").flatMap(method ->
                Stream.of(CMIS_PATH + ";x=1", CMIS_PATH + ";jsessionid=ABC", CMIS_PATH + ";")
                        .map(path -> Arguments.of(method, path)));
    }

    /** Any exception escaping the adapter reaches the caller with its own message in the JSON 500. */
    @Test
    void clientFailureReturnsJson500() {
        doThrow(new RuntimeException("boom"))
                .when(cmisClient).createDocumentByPath(any(), any(), any(), any(), any(), any());

        HttpTestClient.Response response = client().get(CMIS_PATH);

        assertWire(response, STATUS_INTERNAL_SERVER_ERROR, APPLICATION_JSON, "{ \"message\": \"boom\" }");
        verifyDocumentCreationAttemptedOnce();
    }

    /**
     * Mirrors the {@code post-cmis-upstream-unauthorized} scenario of {@code fixtures/SCENARIOS.md}: the real
     * AtomPub adapter asks the WireMock repository for its service document, the repository answers 401, and the
     * adapter's failure, which the flow leaves unhandled, becomes the JSON 500 carrying the adapter's message. The
     * repository receives the service-document GET alone.
     */
    @Test
    void upstreamUnauthorizedReturnsJson500() {
        wm.stubFor(get(urlPathEqualTo(ATOM_ROOT)).willReturn(aResponse().withStatus(401)));

        HttpTestClient.Response response = client().send("POST", CMIS_PATH, Map.of(), EMPTY_BODY);

        assertWire(response, STATUS_INTERNAL_SERVER_ERROR, APPLICATION_JSON,
                "{ \"message\": \"CMIS service document request failed with HTTP status 401\" }");
        verifyDocumentCreationAttemptedOnce();
        wm.verify(1, getRequestedFor(urlPathEqualTo(ATOM_ROOT)));
        wm.verify(1, anyRequestedFor(anyUrl()));
    }

    /** Stubbed without calling the real method, which would reject the matchers' null arguments. */
    private void stubCreatedDocument(String objectId) {
        doReturn(objectId).when(cmisClient).createDocumentByPath(any(), any(), any(), any(), any(), any());
    }

    /** {@code HTTP/1.1 200 OK}, {@code application/json}, the service's compact JSON, after one document creation. */
    private void assertCreated(HttpTestClient.Response response, String objectId) {
        assertWire(response, STATUS_OK, APPLICATION_JSON, "{\"id\":\"" + objectId + "\"}");
        verifyDocumentCreationAttemptedOnce();
    }

    /**
     * Verifies that the request reached the repository adapter exactly once, through the real service, with the
     * arguments of the original {@code cmis:create-document-by-path-from-content} (cmis-document-integration.xml:13)
     * and the logo bytes as content. The literals are written out rather than taken from the service's constants, so
     * a changed constant fails here.
     */
    private void verifyDocumentCreationAttemptedOnce() {
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> content = ArgumentCaptor.forClass(byte[].class);
        verify(cmisClient).createDocumentByPath(eq("/okm:root/"), name.capture(), eq("image/jpg"),
                eq("cmis:document"), eq("NONE"), content.capture());
        assertTrue(name.getValue().matches("pic\\d+\\.jpg"), name::getValue);
        assertArrayEquals(logo, content.getValue());
    }

    /**
     * Asserts the response exactly as the listener wrote it: the complete raw status line, so version, code and
     * Undertow's standard reason phrase for that code all match; exactly one {@code Content-Type} header, with
     * exactly the expected value, parameters included; and the exact body bytes.
     */
    private static void assertWire(HttpTestClient.Response response, String statusLine, String contentType,
            String body) {
        assertEquals(statusLine, response.statusLine(), response::toString);
        assertEquals(List.of(contentType), response.headers("Content-Type"), response::toString);
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
    }
}
