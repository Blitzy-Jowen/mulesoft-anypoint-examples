/*
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
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
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
 * unchanged and only the upstream body and Content-Type coming back with 200: form bodies as the caller's bytes,
 * whatever the method and query, and an upstream reply without a Content-Type answered without one. Every reply is
 * complete when the controller returns, so TRACE gets no servlet echo and OPTIONS no {@code Allow} header. The
 * error cases are the platform defaults of SC-1, because the flow declares no exception strategy: an upstream
 * failure status, an I/O failure and an unparseable Content-Type each end in the JSON 500, and a path outside
 * {@code /FIM/*} gets the no-listener 404 without any upstream call. A path holding a malformed percent-escape gets
 * the listener's empty 400 before dispatch, also without any upstream call.
 *
 * <p>A target holding characters {@code java.net.URI} refuses reaches the FIM server percent-encoded, as the
 * original requester sent it. Only those targets and one rejected path carry a literal {@code {client_id}}, to show
 * that the substituted {@code client.id} is forwarded rather than echoed in an error body; the substitution itself
 * is covered by {@code FimProxyServiceTest}. Every upstream URL points at WireMock, so nothing leaves the loopback
 * interface. The original example has no test of its own, so no original assertion is ported here.
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
     * Raw request target, written to the socket as given, and the URL the FIM server must receive. The targets hold
     * characters {@code java.net.URI} refuses in a path or a query, or a malformed escape in the query; the literal
     * {@code {client_id}} is replaced by {@code client.id} of {@code application-test.yml}, as in each case the
     * outbound URL that would otherwise have been echoed held it. The last target's UTF-8 bytes reach the FIM server
     * as the same bytes, percent-encoded.
     */
    static Stream<Arguments> targetsJavaNetUriRefuses() {
        return Stream.of(
                Arguments.of("/FIM/{client_id}/\\x", "/FIM/test-fim-client-id/%5Cx"),
                Arguments.of("/FIM/{client_id}/|x", "/FIM/test-fim-client-id/%7Cx"),
                Arguments.of("/FIM/{client_id}/^x", "/FIM/test-fim-client-id/%5Ex"),
                Arguments.of("/FIM/x?id={client_id}&z=\\", "/FIM/x?id=test-fim-client-id&z=%5C"),
                Arguments.of("/FIM/x?q=%zz", "/FIM/x?q=%25zz"),
                Arguments.of("/FIM/caf\u00e9", "/FIM/caf%C3%A9"));
    }

    /**
     * A target the original requester percent-encoded and sent is forwarded the same way and answered with the
     * upstream reply, instead of a 500 whose message repeats the outbound URL with {@code fim.base-url} and the
     * substituted {@code client.id}.
     */
    @ParameterizedTest(name = "GET {0} -> {1}")
    @MethodSource("targetsJavaNetUriRefuses")
    void targetJavaNetUriRefusesIsForwardedPercentEncodedAndAnsweredWithTheUpstreamReply(String target,
                                                                                         String forwarded) {
        byte[] upstreamBody = "<fim>escaped</fim>".getBytes(StandardCharsets.UTF_8);
        wm.stubFor(get(urlEqualTo(forwarded)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.APPLICATION_XML_VALUE)
                .withBody(upstreamBody)));

        HttpTestClient.Response response = http.get(target);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertArrayEquals(upstreamBody, response.body(), response::toString);
        assertContentType(response, MediaType.APPLICATION_XML);
        wm.verify(1, getRequestedFor(urlEqualTo(forwarded)));
        assertEquals(1, wm.getAllServeEvents().size(), response::toString);
    }

    /**
     * A path holding a {@code %} not followed by two hexadecimal digits gets the listener's rejection, 400 with an
     * empty body and no Content-Type, before dispatch and whatever the method, instead of Undertow's HTML 500; the
     * FIM server is not called.
     *
     * @param method the request method
     * @param path   the raw request target
     * @param body   the request body, or {@code null} for none
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(nullValues = "null", value = {
            "GET, /FIM/x%zz, null",
            "GET, /FIM/x%, null",
            "GET, /FIM/{client_id}%zz, null",
            "POST, /FIM/x%2/y?q=1, abc",
            "OPTIONS, /FIM/%G0, null"
    })
    void malformedPercentEscapeInThePathIsRejected400WithAnEmptyBodyWithoutUpstreamCall(String method, String path,
                                                                                         String body) {
        HttpTestClient.Response response = http.send(method, path, Map.of(),
                body == null ? null : body.getBytes(StandardCharsets.UTF_8));

        assertEquals("HTTP/1.1 400 Bad Request", response.statusLine(), response::toString);
        assertEquals(List.of("0"), response.headers("Content-Length"), response::toString);
        assertTrue(response.headers("Content-Type").isEmpty(), response::toString);
        assertArrayEquals(new byte[0], response.body(), response::toString);
        wm.verify(0, anyRequestedFor(anyUrl()));
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

    /**
     * Method, path, body text and the charset of its bytes. The query-less POSTs are the bodies Spring's
     * {@code @RequestBody} binding rebuilt from the request parameters: re-sorted, {@code %20} turned into
     * {@code +}, the Latin-1 escape {@code %e9} and the raw Latin-1 byte decoded as UTF-8, and a bare name or a JSON
     * text given a trailing {@code =}.
     */
    static Stream<Arguments> formBodies() {
        return Stream.of(
                Arguments.of("POST", "/FIM/form", "z=1&a=%e9&b=x%20y", StandardCharsets.US_ASCII),
                Arguments.of("POST", "/FIM/form", "b=2&a=%C3%A9+x&a=dup&empty=", StandardCharsets.US_ASCII),
                Arguments.of("POST", "/FIM/form", "token=abc%2Bdef%3D%3D&grant_type=refresh_token",
                        StandardCharsets.US_ASCII),
                Arguments.of("POST", "/FIM/form", "{\"x\":1}", StandardCharsets.US_ASCII),
                Arguments.of("POST", "/FIM/form", "k", StandardCharsets.US_ASCII),
                Arguments.of("POST", "/FIM/form", "a=caf\u00e9", StandardCharsets.ISO_8859_1),
                Arguments.of("POST", "/FIM/form?q=1", "z=1&a=%e9&b=x%20y", StandardCharsets.US_ASCII),
                Arguments.of("PUT", "/FIM/form", "z=1&a=%e9&b=x%20y", StandardCharsets.US_ASCII),
                Arguments.of("PATCH", "/FIM/form", "z=1&a=%e9&b=x%20y", StandardCharsets.US_ASCII),
                Arguments.of("DELETE", "/FIM/form", "z=1&a=%e9&b=x%20y", StandardCharsets.US_ASCII));
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("formBodies")
    void formBodyIsForwardedAsTheCallersBytes(String method, String path, String text, Charset charset) {
        stubDone();
        byte[] body = text.getBytes(charset);

        HttpTestClient.Response response = http.send(method, path,
                Map.of("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE), body);

        assertDone(response);
        wm.verify(1, requestedFor(method, urlEqualTo(path))
                .withRequestBody(binaryEqualTo(body))
                .withHeader("Content-Type", containing(MediaType.APPLICATION_FORM_URLENCODED_VALUE)));
    }

    /**
     * Method, upstream Content-Type ({@code null} for none) and upstream body. Each request carries an
     * {@code Authorization} header, which the servlet's TRACE echo would repeat.
     */
    static Stream<Arguments> upstreamReplies() {
        return Stream.of(
                Arguments.of("GET", "text/plain", "fim reply"),
                Arguments.of("GET", "text/html;charset=UTF-8", "<p>fim</p>"),
                Arguments.of("GET", "bogus", "fim reply"),
                Arguments.of("GET", "text/plain", ""),
                Arguments.of("GET", null, "untyped"),
                Arguments.of("GET", null, ""),
                Arguments.of("TRACE", "text/plain", "fim reply"),
                Arguments.of("TRACE", "text/plain", ""),
                Arguments.of("TRACE", null, "untyped"),
                Arguments.of("TRACE", null, ""),
                Arguments.of("OPTIONS", "text/plain", "fim reply"),
                Arguments.of("OPTIONS", "text/plain", ""),
                Arguments.of("OPTIONS", null, "untyped"),
                Arguments.of("OPTIONS", null, ""),
                Arguments.of("HEAD", "text/plain", "fim reply"),
                Arguments.of("HEAD", null, "untyped"));
    }

    /**
     * The reply carries the upstream Content-Type exactly as sent, an unparseable one included, and none when the
     * upstream sent none; its {@code Content-Length} is that of the upstream body, which the requester reads empty
     * for HEAD. Nothing is added after the controller returns: no servlet TRACE echo and no {@code Allow} header.
     */
    @ParameterizedTest(name = "{0}, upstream Content-Type {1}, upstream body \"{2}\"")
    @MethodSource("upstreamReplies")
    void replyCarriesOnlyTheUpstreamContentTypeAndIsCompleteWhenTheControllerReturns(
            String method, String upstreamContentType, String upstreamBody) {
        ResponseDefinitionBuilder upstream = aResponse().withStatus(200).withBody(upstreamBody);
        if (upstreamContentType != null) {
            upstream.withHeader("Content-Type", upstreamContentType);
        }
        wm.stubFor(any(urlEqualTo("/FIM/reply")).willReturn(upstream));
        byte[] expectedBody = "HEAD".equals(method) ? new byte[0] : upstreamBody.getBytes(StandardCharsets.UTF_8);

        HttpTestClient.Response response = http.send(method, "/FIM/reply",
                Map.of("Authorization", "Bearer trace-sentinel"), null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(upstreamContentType == null ? List.of() : List.of(upstreamContentType),
                response.headers("Content-Type"), response::toString);
        assertEquals(List.of(String.valueOf(expectedBody.length)), response.headers("Content-Length"),
                response::toString);
        assertTrue(response.headers("Allow").isEmpty(), response::toString);
        assertArrayEquals(expectedBody, response.body(), response::toString);
        wm.verify(1, requestedFor(method, urlEqualTo("/FIM/reply")));
    }

    /**
     * A HEAD answered by a 303 is followed with one GET, as the Mule requester followed it, so the reply declares the
     * length of the representation that GET returned and, as a HEAD reply, carries no body.
     */
    @Test
    void headAnsweredBy303IsFollowedAsAGetAndAnsweredWithItsLength() {
        byte[] representation = "redirectbody".getBytes(StandardCharsets.UTF_8);
        wm.stubFor(any(urlEqualTo("/FIM/head303")).willReturn(aResponse()
                .withStatus(303)
                .withHeader("Location", "/FIM/final303")));
        wm.stubFor(any(urlEqualTo("/FIM/final303")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.TEXT_PLAIN_VALUE)
                .withBody(representation)));

        HttpTestClient.Response response = http.send("HEAD", "/FIM/head303", Map.of(), null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.TEXT_PLAIN_VALUE), response.headers("Content-Type"), response::toString);
        assertEquals(List.of(String.valueOf(representation.length)), response.headers("Content-Length"),
                response::toString);
        assertArrayEquals(new byte[0], response.body(), response::toString);
        wm.verify(1, requestedFor("HEAD", urlEqualTo("/FIM/head303")));
        wm.verify(1, getRequestedFor(urlEqualTo("/FIM/final303")));
        assertEquals(2, wm.getAllServeEvents().size(), response::toString);
    }

    /**
     * The Content-Type is parsed before the body is read, as request-body binding parsed it, so the request fails
     * with the message of that parse and the FIM server is never called.
     *
     * @param method the request method
     * @param body   the request body, or {@code null} for none
     */
    @ParameterizedTest(name = "{0} with body \"{1}\"")
    @CsvSource(nullValues = "null", value = {"POST, abc", "GET, null"})
    void unparseableContentTypeReturnsPlatform500WithoutUpstreamCall(String method, String body) {
        HttpTestClient.Response response = http.send(method, "/FIM/x", Map.of("Content-Type", "bogus"),
                body == null ? null : body.getBytes(StandardCharsets.UTF_8));

        assertEquals(500, response.status(), response::toString);
        assertEquals("Internal Server Error", response.reason(), response::toString);
        assertEquals(List.of(MediaType.APPLICATION_JSON_VALUE), response.headers("Content-Type"),
                response::toString);
        assertEquals("{ \"message\": \"Invalid mime type \\\"bogus\\\": does not contain '/'\" }",
                response.bodyAsString(), response::toString);
        wm.verify(0, anyRequestedFor(anyUrl()));
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
     * the HTTP transport's own wording.
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
