/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClientResponseException;

import com.mulesoft.examples.document_integration_using_the_cmis_connector.client.CmisAtomPubClient;
import com.mulesoft.examples.document_integration_using_the_cmis_connector.service.CmisDocumentService;
import com.mulesoft.examples.document_integration_using_the_cmis_connector.support.HttpTestClient;

/**
 * Wire-level test of {@link CmisController}, the HTTP trigger of flow {@code CMIS}
 * (document-integration-using-the-cmis-connector/src/main/app/cmis-document-integration.xml:5-15), on the
 * application's own Undertow listener.
 *
 * <p>The whole application runs on a random port with the real {@link CmisDocumentService} and the SC-1 handler;
 * only {@link CmisAtomPubClient}, the one outbound path to the CMIS repository, is a mock, so no request leaves the
 * host. Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the
 * socket, so status, reason phrase, {@code Content-Type} and body bytes are asserted as the listener writes them.
 *
 * <p>The happy paths cover the original test's empty POST, the README's GET, and PUT and OPTIONS, because the
 * original listener declares no {@code allowedMethods}; each proves the flow ran exactly once with the connector
 * arguments of the original. The error cases are the platform defaults of SC-1: the no-listener 404 for a path the
 * flow does not serve, and the JSON 500 for a repository failure, which the flow, having no exception strategy,
 * leaves unhandled. The original {@code DocumentIntegrationUsingTheCmisConnectorIT} assertion is ported separately
 * in {@code DocumentIntegrationUsingTheCmisConnectorTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CmisControllerTest {

    private static final String CMIS_PATH = "/cmis";

    /** Prefix the original IT asserts on the created document's JSON (DocumentIntegrationUsingTheCmisConnectorIT:29). */
    private static final String ORIGINAL_IT_PREFIX = "{\"id\":\"/okm:root/pic";

    private static final byte[] EMPTY_BODY = new byte[0];

    private static byte[] logo;

    @LocalServerPort
    private int port;

    @MockBean
    private CmisAtomPubClient cmisClient;

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

        assertWire(response, 404, MediaType.TEXT_PLAIN, "No listener for endpoint: /unknown");
        verifyNoInteractions(cmisClient);
    }

    @Test
    void clientFailureReturnsJson500() {
        when(cmisClient.createDocumentByPath(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        HttpTestClient.Response response = client().get(CMIS_PATH);

        assertWire(response, 500, MediaType.APPLICATION_JSON, "{ \"message\": \"boom\" }");
        verifyDocumentCreationAttemptedOnce();
    }

    /** Mirrors the {@code post-cmis-upstream-unauthorized} scenario of {@code fixtures/SCENARIOS.md}. */
    @Test
    void upstreamUnauthorizedReturnsJson500() {
        when(cmisClient.createDocumentByPath(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RestClientResponseException("401 Unauthorized", 401, "Unauthorized", null, null,
                        null));

        HttpTestClient.Response response = client().send("POST", CMIS_PATH, Map.of(), EMPTY_BODY);

        assertWire(response, 500, MediaType.APPLICATION_JSON, "{ \"message\": \"401 Unauthorized\" }");
        verifyDocumentCreationAttemptedOnce();
    }

    private void stubCreatedDocument(String objectId) {
        when(cmisClient.createDocumentByPath(any(), any(), any(), any(), any(), any())).thenReturn(objectId);
    }

    /** 200 {@code application/json} with the service's compact JSON, after exactly one document creation. */
    private void assertCreated(HttpTestClient.Response response, String objectId) {
        assertWire(response, 200, MediaType.APPLICATION_JSON, "{\"id\":\"" + objectId + "\"}");
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
     * Asserts the response on the wire: the status code, a non-blank reason phrase (Undertow's standard phrase; its
     * exact text is a Tier 2A verification point), a {@code Content-Type} of the expected type and subtype (a charset
     * parameter is irrelevant) and the exact body.
     */
    private static void assertWire(HttpTestClient.Response response, int status, MediaType type, String body) {
        assertEquals(status, response.status(), response::toString);
        assertFalse(response.reason().isBlank(), response::toString);
        String contentType = response.header("Content-Type");
        assertNotNull(contentType, response::toString);
        assertTrue(type.equalsTypeAndSubtype(MediaType.parseMediaType(contentType)), response::toString);
        assertEquals(body, response.bodyAsString(), response::toString);
    }
}
