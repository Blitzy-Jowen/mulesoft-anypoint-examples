/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.mulesoft.examples.proxying_a_rest_api.ProxyingARestApiApplication;
import com.mulesoft.examples.proxying_a_rest_api.support.HttpTestClient;

/**
 * Tests {@link OriginFormTargetGuard} on the wire: every request target not in origin form gets a listener-level
 * answer and never reaches the upstream, and origin-form and absolute-form targets are forwarded to the configured
 * upstream as before.
 *
 * <ul>
 *   <li>A target whose path Undertow hands over without a leading {@code /} (asterisk form, {@code CONNECT}'s
 *       authority form, an absolute form whose authority runs into its path) gets the no-listener 404 with the bytes
 *       the generic error handling writes for a path no handler serves: status line {@code HTTP/1.1 404 Not Found},
 *       the single {@code Content-Type: text/plain} and the body {@code No listener for endpoint: <path>}.</li>
 *   <li>Every other target not in origin form is answered by Undertow's own parser or {@code Host} check with an
 *       empty 400 before any handler runs; those answers are pinned here so that a change of the listener shows.</li>
 * </ul>
 *
 * <p>The application boots with its own {@code application.yml} on Undertow (random port on IPv4 loopback) in front
 * of a WireMock upstream, and ST-1 {@link HttpTestClient} writes each request target unchanged. In the cases below,
 * {@code {authority}} stands for the listener's {@code 127.0.0.1:<port>}, which ST-1 also sends as {@code Host}
 * unless a case names another one; {@code evil.example} is never resolved or contacted.
 */
@SpringBootTest(
        classes = ProxyingARestApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to the address ST-1 connects to, whatever server.address the project sets.
        properties = "server.address=" + OriginFormTargetGuardTest.LOOPBACK)
@ActiveProfiles("test")
class OriginFormTargetGuardTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    private static final String AUTHORITY = "{authority}";

    private static final String UPSTREAM_BODY = "ok";

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress(LOOPBACK))
            .build();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream-url", () -> "http://" + LOOPBACK + ":" + upstream.getPort());
    }

    /** Method, target, {@code Host} or {@code null} for ST-1's, request body or {@code null}, reported path. */
    private static Stream<Arguments> nonOriginFormPaths() {
        return Stream.of(
                Arguments.of("OPTIONS", "*", null, null, "*"),
                Arguments.of("PRI", "*", null, null, "*"),
                Arguments.of("CONNECT", AUTHORITY, null, null, AUTHORITY),
                Arguments.of("CONNECT", "evil.example:8443", "evil.example:8443", null, "evil.example:8443"),
                Arguments.of("GET", "http://" + AUTHORITY + "@evil.example/x", null, null, "@evil.example/x"),
                Arguments.of("GET", "http://" + AUTHORITY + "evil", null, null, "evil"),
                Arguments.of("POST", "http://" + AUTHORITY + "@evil.example/x", null, "data", "@evil.example/x"),
                // Reported as the escaping filter would hand the path on: the raw | becomes %7C.
                Arguments.of("DELETE", "http://" + AUTHORITY + "@evil.example/a|b", null, null,
                        "@evil.example/a%7Cb"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("nonOriginFormPaths")
    void pathWithoutLeadingSlashIsAnsweredWithTheNoListener404AndNotForwarded(String method, String target,
                                                                              String host, String body,
                                                                              String reportedPath) {
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody(UPSTREAM_BODY)));
        byte[] expectedBody = ("No listener for endpoint: " + withAuthority(reportedPath)).getBytes(US_ASCII);

        HttpTestClient.Response response = send(method, target, host, body);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(response.headers("Content-Type")).as(response::toString).containsExactly("text/plain");
        assertThat(response.headers("Content-Length")).as(response::toString)
                .containsExactly(String.valueOf(expectedBody.length));
        assertThat(response.body()).as(response::toString).isEqualTo(expectedBody);
        assertThat(upstream.getAllServeEvents()).isEmpty();
    }

    /** Method, target, {@code Host} or {@code null} for ST-1's, status line Undertow answers with. */
    private static Stream<Arguments> targetsUndertowRejects() {
        return Stream.of(
                Arguments.of("GET", "@evil.example:8443/x", null, "HTTP/1.1 400 Bad Request"),
                Arguments.of("GET", "http:@evil.example:8443/x", null, "HTTP/1.1 400 Bad Request"),
                Arguments.of("GET", ".evil/x", null, "HTTP/1.1 400 Bad Request"),
                Arguments.of("GET", "*", null, "HTTP/1.1 400 Bad Request"),
                Arguments.of("GET", ":8443/x", null, "HTTP/1.1 400 Bad Request"),
                Arguments.of("GET", "x@evil.example:8443/x", null, "HTTP/1.1 400 Bad Request"),
                Arguments.of("OPTIONS", "*?x", null, "HTTP/1.1 400 Bad Request"),
                Arguments.of("CONNECT", "@evil.example:8443", null, "HTTP/1.1 400 Bad Request"),
                Arguments.of("GET", "http://u:p@evil.example:8443/x", "evil.example:8443", "HTTP/1.1 400 Bad Request"),
                // A target naming a host the Host header does not.
                Arguments.of("CONNECT", "evil.example:8443", null, "HTTP/1.1 400 URI Host Header NO MATCH"),
                Arguments.of("GET", "http://evil.example/x", null, "HTTP/1.1 400 URI Host Header NO MATCH"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("targetsUndertowRejects")
    void targetUndertowRejectsIsAnsweredWithAnEmpty400AndNotForwarded(String method, String target, String host,
                                                                      String statusLine) {
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody(UPSTREAM_BODY)));

        HttpTestClient.Response response = send(method, target, host, null);

        assertThat(response.statusLine()).as(response::toString).isEqualTo(statusLine);
        assertThat(response.body()).as(response::toString).isEmpty();
        assertThat(upstream.getAllServeEvents()).isEmpty();
    }

    /** Method, target, {@code Host} or {@code null} for ST-1's, URL the configured upstream must receive. */
    private static Stream<Arguments> forwardedTargets() {
        return Stream.of(
                Arguments.of("GET", "/x", null, "/x"),
                Arguments.of("OPTIONS", "/x", null, "/x"),
                Arguments.of("PROPFIND", "/dav/folder", null, "/dav/folder"),
                Arguments.of("GET", "//evil.example/x", null, "//evil.example/x"),
                Arguments.of("GET", "/@evil.example:8443/x", null, "/@evil.example:8443/x"),
                // Absolute form: the authority is dropped, only the path and query travel.
                Arguments.of("GET", "http://evil.example/x", "evil.example", "/x"),
                Arguments.of("GET", "https://evil.example/x?q=1", "evil.example", "/x?q=1"),
                Arguments.of("GET", "http://" + AUTHORITY, null, "/"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("forwardedTargets")
    void originAndAbsoluteFormTargetsReachOnlyTheConfiguredUpstream(String method, String target, String host,
                                                                     String upstreamUrl) {
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody(UPSTREAM_BODY)));

        HttpTestClient.Response response = send(method, target, host, null);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.bodyAsString()).as(response::toString).isEqualTo(UPSTREAM_BODY);
        List<ServeEvent> received = upstream.getAllServeEvents();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).getRequest().getMethod().getName()).isEqualTo(method);
        assertThat(received.get(0).getRequest().getUrl()).isEqualTo(upstreamUrl);
    }

    /**
     * Sends {@code method} with {@code target}, after {@link #withAuthority}, on a new connection; {@code host}
     * replaces ST-1's {@code Host} unless {@code null}, and {@code body} is sent as UTF-8 unless {@code null}.
     */
    private HttpTestClient.Response send(String method, String target, String host, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (host != null) {
            headers.put("Host", host);
        }
        return new HttpTestClient(LOOPBACK, port)
                .send(method, withAuthority(target), headers, body == null ? null : body.getBytes(UTF_8));
    }

    /** {@code text} with {@code {authority}} replaced by the listener's {@code 127.0.0.1:<port>}. */
    private String withAuthority(String text) {
        return text.replace(AUTHORITY, LOOPBACK + ":" + port);
    }
}
