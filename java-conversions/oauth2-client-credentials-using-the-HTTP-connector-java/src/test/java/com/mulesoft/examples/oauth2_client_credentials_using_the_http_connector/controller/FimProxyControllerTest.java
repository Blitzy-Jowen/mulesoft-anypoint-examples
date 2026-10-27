/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.requestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.support.HttpTestClient;

/**
 * Wire-level test of {@link FimProxyController}, the HTTP trigger of flow {@code http-client-credentialsFlow}
 * (oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:39-47), on the
 * application's own Undertow listener with a WireMock stand-in for {@code https://tfim01.demos.ibm.com:443}.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the
 * socket, so status code, reason phrase, headers and body bytes are asserted exactly as the listener writes them.
 * The happy paths show the listener's method, raw path and query, body and Content-Type reaching the FIM server
 * unchanged and only the upstream body and Content-Type coming back with 200. The error cases are the platform
 * defaults of SC-1, because the flow declares no exception strategy: an upstream failure status and an I/O failure
 * each end in the JSON 500, and a path outside {@code /FIM/*} gets the no-listener 404 without any upstream call.
 *
 * <p>No request carries a literal {@code {client_id}}: its substitution is covered by {@code FimProxyServiceTest},
 * and every upstream URL points at WireMock, so nothing leaves the loopback interface. The original example has no
 * test of its own, so no original assertion is ported here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FimProxyControllerTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("fim.base-url", () -> wm.baseUrl());
        registry.add("box.oauth.token-url", () -> wm.baseUrl() + "/api/oauth2/token");
        registry.add("box.api.base-url", () -> wm.baseUrl());
    }

    @LocalServerPort
    private int port;

    private HttpTestClient http;

    @BeforeEach
    void setUp() {
        wm.resetAll();
        http = HttpTestClient.localhost(port);
    }

    @Test
    void getIsForwardedWithRawPathAndQueryAndAnswersWithUpstreamBodyAndContentTypeOnly() {
        byte[] upstreamBody = "<fim>ok</fim>".getBytes(StandardCharsets.UTF_8);
        wm.stubFor(get(urlEqualTo("/FIM/a/b?x=1&y=2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_XML_VALUE)
                        .withHeader("X-Fim", "1")
                        .withBody(upstreamBody)));

        HttpTestClient.Response response = http.get("/FIM/a/b?x=1&y=2");

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertArrayEquals(upstreamBody, response.body(), response::toString);
        assertContentType(response, MediaType.APPLICATION_XML);
        assertNull(response.header("X-Fim"), response::toString);
        wm.verify(1, getRequestedFor(urlEqualTo("/FIM/a/b?x=1&y=2")));
    }

    /**
     * POST and PUT go through {@link HttpTestClient#send}; DELETE, whose body is sent through ST-1's own helper, is
     * {@link #deleteIsForwardedWithBodyAndContentType()}.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(delimiter = '|', value = {
            "POST | /FIM/orders   | application/json | {\"id\":1}",
            "PUT  | /FIM/orders/1 | application/xml  | <order id=\"1\"/>"
    })
    void bodyIsForwardedWithMethodAndContentType(String method, String path, String contentType, String body) {
        stubDone();

        HttpTestClient.Response response = http.send(method, path, Map.of("Content-Type", contentType),
                body.getBytes(StandardCharsets.UTF_8));

        assertDone(response);
        assertForwarded(requestedFor(method, urlEqualTo(path)), body, contentType);
    }

    @Test
    void deleteIsForwardedWithBodyAndContentType() {
        stubDone();

        HttpTestClient.Response response = http.delete("/FIM/orders/1", MediaType.TEXT_PLAIN_VALUE, "bye");

        assertDone(response);
        assertForwarded(deleteRequestedFor(urlEqualTo("/FIM/orders/1")), "bye", MediaType.TEXT_PLAIN_VALUE);
    }

    @Test
    void upstreamFailureStatusReturnsPlatform500WithMappedAsFailureMessage() {
        wm.stubFor(get(urlEqualTo("/FIM/missing")).willReturn(aResponse().withStatus(404)));

        HttpTestClient.Response response = http.get("/FIM/missing");

        assertEquals(500, response.status(), response::toString);
        assertEquals("Internal Server Error", response.reason(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON);
        assertEquals("{ \"message\": \"Response code 404 mapped as failure.\" }", response.bodyAsString(),
                response::toString);
        wm.verify(1, getRequestedFor(urlEqualTo("/FIM/missing")));
    }

    /**
     * Only the APIkit-spaced envelope is asserted: the I/O exception's message names the WireMock port and carries
     * JDK-specific wording.
     */
    @Test
    void upstreamConnectionResetReturnsPlatform500JsonMessage() {
        wm.stubFor(get(urlEqualTo("/FIM/reset"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpTestClient.Response response = http.get("/FIM/reset");

        assertEquals(500, response.status(), response::toString);
        assertEquals("Internal Server Error", response.reason(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON);
        String body = response.bodyAsString();
        assertTrue(body.startsWith("{ \"message\": "), response::toString);
        assertTrue(body.endsWith(" }"), response::toString);
    }

    /** {@code /FIMX}, not {@code /FIM}: the {@code /FIM/**} mapping also matches the bare {@code /FIM}. */
    @Test
    void pathOutsideFimListenerReturnsNoListener404WithoutUpstreamCall() {
        HttpTestClient.Response response = http.get("/FIMX");

        assertEquals(404, response.status(), response::toString);
        assertEquals("Not Found", response.reason(), response::toString);
        String contentType = response.header("Content-Type");
        assertNotNull(contentType, response::toString);
        assertTrue(contentType.startsWith(MediaType.TEXT_PLAIN_VALUE), response::toString);
        assertEquals("No listener for endpoint: /FIMX", response.bodyAsString(), response::toString);
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    /** Upstream answer for every {@code /FIM/} request of the body-forwarding cases. */
    private static void stubDone() {
        wm.stubFor(any(urlPathMatching("/FIM/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.TEXT_PLAIN_VALUE)
                        .withBody("done")));
    }

    private static void assertDone(HttpTestClient.Response response) {
        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN);
        assertEquals("done", response.bodyAsString(), response::toString);
    }

    /** The FIM server received exactly one request of the pattern, carrying the body and Content-Type sent. */
    private static void assertForwarded(RequestPatternBuilder request, String body, String contentType) {
        wm.verify(1, request
                .withRequestBody(equalTo(body))
                .withHeader("Content-Type", containing(contentType)));
    }

    /** Compares type and subtype only, so a charset parameter neither passes nor fails the check. */
    private static void assertContentType(HttpTestClient.Response response, MediaType expected) {
        String contentType = response.header("Content-Type");
        assertNotNull(contentType, response::toString);
        assertTrue(expected.equalsTypeAndSubtype(MediaType.parseMediaType(contentType)), response::toString);
    }
}
