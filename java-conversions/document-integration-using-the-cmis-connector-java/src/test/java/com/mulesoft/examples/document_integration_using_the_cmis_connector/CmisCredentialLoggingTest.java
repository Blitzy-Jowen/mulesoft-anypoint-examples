/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.document_integration_using_the_cmis_connector.support.HttpTestClient;

/**
 * Guards the CMIS service account ({@code cmis.username}, {@code cmis.password}) against the log under
 * {@code logging.level.root=TRACE}.
 *
 * <p>{@code CmisAtomPubClient} sends every AtomPub request over the JDK {@code HttpURLConnection} with an HTTP Basic
 * {@code Authorization} header for that account. Below {@code INFO} the JDK logger
 * {@code sun.net.www.protocol.http.HttpURLConnection}, bridged into Logback, writes every request's and response's
 * headers, so a root {@code DEBUG} or {@code TRACE} would print the account in Base64 on each of the three requests
 * one {@code /cmis} call makes; {@code application.yml} pins that logger to {@code INFO}.
 *
 * <p>The whole application runs at root {@code TRACE} on a random port with sentinel credentials assembled at run
 * time, against WireMock playing the OpenKM AtomPub surface of {@code DocumentIntegrationUsingTheCmisConnectorTest}
 * (service document, folder entry by path, children-feed create). One empty {@code POST /cmis} through ST-1 must
 * return the created document's ID, WireMock must have received the sentinel Basic credentials on all three
 * requests, so they really crossed the transport, and the captured console output must hold no
 * {@code Authorization} header, no Base64 token and neither sentinel, while it does hold {@code TRACE} lines and a
 * {@code FINE} record of another JDK logger, so the capture is known to see every level the bridge passes. The test
 * restores the logger levels it found and closes its context, so later test classes see the logging configuration
 * they would have seen without it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "logging.level.root=TRACE")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class CmisCredentialLoggingTest {

    /** Prefix the original IT asserts on the created document's JSON (DocumentIntegrationUsingTheCmisConnectorIT:29). */
    private static final String MESSAGE = "{\"id\":\"/okm:root/pic";

    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String APP = "http://www.w3.org/2007/app";
    private static final String CMIS = "http://docs.oasis-open.org/ns/cmis/core/200908/";
    private static final String CMISRA = "http://docs.oasis-open.org/ns/cmis/restatom/200908/";

    private static final String ATOM_ROOT = "/OpenKM/cmis/atom";

    /** Service-account sentinels, unique per run and built from parts, so no credential-shaped literal is committed. */
    private static final String USERNAME = sentinel("user");
    private static final String PASSWORD = sentinel("password");

    /** The Base64 form of {@code USERNAME:PASSWORD} that the Basic {@code Authorization} header carries. */
    private static final String TOKEN = Base64.getEncoder()
            .encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));

    /** Every form in which the service account could reach the log; none may. */
    private static final List<String> SECRETS = List.of(TOKEN, USERNAME, PASSWORD);

    /** A JDK logger outside the pinned one, whose {@code FINE} record proves the bridge passes JDK records. */
    private static final String JDK_PROBE_LOGGER = CmisCredentialLoggingTest.class.getName() + ".jdk";
    private static final String JDK_PROBE_MESSAGE = "jdk-fine-probe-" + UUID.randomUUID();

    /** How long the output must stay free of the account after the exchange, for lines written after the reply. */
    private static final Duration SETTLE = Duration.ofMillis(300);

    /** The logger levels set before this class's context started, by logger name. */
    private static final Map<String, Level> LEVELS_BEFORE = new HashMap<>();

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
        r.add("cmis.username", () -> USERNAME);
        r.add("cmis.password", () -> PASSWORD);
    }

    @BeforeAll
    static void recordLevels() {
        LEVELS_BEFORE.clear();
        for (Logger logger : loggerContext().getLoggerList()) {
            if (logger.getLevel() != null) {
                LEVELS_BEFORE.put(logger.getName(), logger.getLevel());
            }
        }
    }

    @AfterAll
    static void restoreLevels() {
        for (Logger logger : loggerContext().getLoggerList()) {
            Level before = LEVELS_BEFORE.get(logger.getName());
            // The root logger always has a level and cannot be given none.
            if (before != null || !org.slf4j.Logger.ROOT_LOGGER_NAME.equals(logger.getName())) {
                logger.setLevel(before);
            }
        }
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
    void serviceAccountCrossesTheTransportWithoutReachingTheLogAtRootTrace(CapturedOutput output) {
        java.util.logging.Logger.getLogger(JDK_PROBE_LOGGER).fine(JDK_PROBE_MESSAGE);

        HttpTestClient.Response response = HttpTestClient.localhost(port).send("POST", "/cmis", Map.of(), new byte[0]);

        assertEquals(200, response.status(), response::toString);
        assertTrue(response.bodyAsString().startsWith(MESSAGE), response::toString);
        wm.verify(3, anyRequestedFor(anyUrl()));
        wm.verify(3, anyRequestedFor(anyUrl()).withHeader("Authorization", equalTo("Basic " + TOKEN)));

        await().during(SETTLE).atMost(SETTLE.multipliedBy(4))
                .untilAsserted(() -> assertLogHoldsNoServiceAccount(output.getAll()));

        String log = output.getAll();
        assertTrue(log.lines().anyMatch(line -> line.contains(" TRACE ")),
                "the capture holds TRACE lines, so it sees the root level configured");
        assertTrue(log.lines().anyMatch(line -> line.contains(" DEBUG ") && line.contains(JDK_PROBE_MESSAGE)),
                "the capture holds the FINE record of another JDK logger, so the JDK bridge passes FINE records");
    }

    private static void assertLogHoldsNoServiceAccount(String log) {
        // Matched as a header ("Authorization:"), because class names in the DEBUG condition report, such as
        // OAuth2AuthorizationServerAutoConfiguration, carry the word itself.
        assertFalse(log.toLowerCase(Locale.ROOT).contains("authorization:"), "an Authorization header is logged");
        for (String secret : SECRETS) {
            assertFalse(log.contains(secret), "the service account is logged");
        }
    }

    private static String sentinel(String part) {
        return String.join("_", "SENTINEL", "CMIS", part.toUpperCase(Locale.ROOT),
                UUID.randomUUID().toString().replace("-", ""));
    }

    private static LoggerContext loggerContext() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
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
