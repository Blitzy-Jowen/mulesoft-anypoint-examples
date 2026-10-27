/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.document_integration_using_the_cmis_connector.support.HttpTestClient;

/**
 * Port of the original example's {@code DocumentIntegrationUsingTheCmisConnectorIT} functional test, keeping its
 * empty-body {@code POST /cmis} and its three assertions; wire-level checks of status and headers live in
 * {@code controller/CmisControllerTest}.
 *
 * <p>The original uploaded to the live OpenKM demo repository. Here WireMock plays that repository's CMIS 1.1
 * AtomPub surface (service document, folder entry by path, children-feed create), and the create stub echoes the
 * posted {@code atom:title} into the returned {@code cmis:objectId}, so the {@code pic<millis>.jpg} name the
 * service generates is what the prefix assertion reads. The controller, service and AtomPub client all run for real;
 * the call is synchronous, so the original's five-second sleep is not needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DocumentIntegrationUsingTheCmisConnectorTest {

    private static final String MESSAGE = "{\"id\":\"/okm:root/pic";

    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String APP = "http://www.w3.org/2007/app";
    private static final String CMIS = "http://docs.oasis-open.org/ns/cmis/core/200908/";
    private static final String CMISRA = "http://docs.oasis-open.org/ns/cmis/restatom/200908/";

    private static final String ATOM_ROOT = "/OpenKM/cmis/atom";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(WireMockConfiguration.options().dynamicPort())
            .build();

    @LocalServerPort
    private int port;

    /** Lazy: the extension's server starts before the context, but its URL is only read when the key resolves. */
    @DynamicPropertySource
    static void cmisProperties(DynamicPropertyRegistry r) {
        r.add("cmis.base-url", () -> wm.baseUrl() + ATOM_ROOT);
    }

    /** Registered per test, because the extension resets its stubs after each one. */
    @BeforeEach
    void stubOpenKmAtomPub() {
        String base = wm.baseUrl();

        wm.stubFor(get(urlPathEqualTo(ATOM_ROOT))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/atomsvc+xml;charset=UTF-8")
                        .withBody(serviceDocument(base))));

        wm.stubFor(get(urlPathEqualTo(ATOM_ROOT + "/default/path"))
                .withQueryParam("path", equalTo("/okm:root/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/atom+xml;type=entry;charset=UTF-8")
                        .withBody(folderEntry(base))));

        wm.stubFor(post(urlPathEqualTo(ATOM_ROOT + "/default/children"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/atom+xml;type=entry;charset=UTF-8")
                        .withBody(createdEntry())
                        .withTransformers("response-template")));
    }

    @Test
    void httpGetToFlowUrlSentMessage() {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response response = client.send("POST", "/cmis", Map.of(), new byte[0]);
        assertNotNull(response);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertFalse(response.bodyAsString().isEmpty());
        assertTrue(response.bodyAsString().startsWith(MESSAGE));
    }

    /** The repository's service document: one workspace for repository {@code default} with its objectbypath template. */
    private static String serviceDocument(String base) {
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
                + "<cmisra:uritemplate>"
                + "<cmisra:template>" + base + ATOM_ROOT + "/default/path?path={path}"
                + "&amp;filter={filter}&amp;includeAllowableActions={includeAllowableActions}"
                + "&amp;includePolicyIds={includePolicyIds}&amp;includeRelationships={includeRelationships}"
                + "&amp;includeACL={includeACL}&amp;renditionFilter={renditionFilter}</cmisra:template>"
                + "<cmisra:type>objectbypath</cmisra:type>"
                + "<cmisra:mediatype>application/atom+xml;type=entry</cmisra:mediatype>"
                + "</cmisra:uritemplate>"
                + "</app:workspace>"
                + "</app:service>";
    }

    /** The root folder's entry, whose descendants link precedes the children feed the client must choose. */
    private static String folderEntry(String base) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<atom:entry xmlns:atom=\"" + ATOM + "\" xmlns:cmis=\"" + CMIS + "\" xmlns:cmisra=\"" + CMISRA
                + "\">"
                + "<atom:id>urn:uuid:root</atom:id>"
                + "<atom:title>okm:root</atom:title>"
                + "<atom:link rel=\"down\" type=\"application/cmistree+xml\" href=\"" + base + ATOM_ROOT
                + "/default/descendants?id=root\"/>"
                + "<atom:link rel=\"down\" type=\"application/atom+xml;type=feed\" href=\"" + base + ATOM_ROOT
                + "/default/children?id=root\"/>"
                + "<cmisra:object><cmis:properties>"
                + "<cmis:propertyId propertyDefinitionId=\"cmis:objectId\"><cmis:value>root</cmis:value>"
                + "</cmis:propertyId>"
                + "<cmis:propertyId propertyDefinitionId=\"cmis:baseTypeId\"><cmis:value>cmis:folder</cmis:value>"
                + "</cmis:propertyId>"
                + "</cmis:properties></cmisra:object>"
                + "</atom:entry>";
    }

    /** The created document's entry; its objectId is OpenKM's form, {@code /okm:root/<posted atom:title>}. */
    private static String createdEntry() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<atom:entry xmlns:atom=\"" + ATOM + "\" xmlns:cmis=\"" + CMIS + "\" xmlns:cmisra=\"" + CMISRA
                + "\">"
                + "<atom:id>urn:uuid:created</atom:id>"
                + "<atom:title>{{xPath request.body '(//*[local-name()=\"title\"])[1]/text()'}}</atom:title>"
                + "<cmisra:object><cmis:properties>"
                + "<cmis:propertyId propertyDefinitionId=\"cmis:objectId\"><cmis:value>/okm:root/"
                + "{{xPath request.body '(//*[local-name()=\"title\"])[1]/text()'}}</cmis:value></cmis:propertyId>"
                + "<cmis:propertyId propertyDefinitionId=\"cmis:baseTypeId\"><cmis:value>cmis:document</cmis:value>"
                + "</cmis:propertyId>"
                + "</cmis:properties></cmisra:object>"
                + "</atom:entry>";
    }
}
