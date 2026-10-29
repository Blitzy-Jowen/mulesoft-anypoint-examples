/*
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

import io.undertow.server.HandlerWrapper;
import io.undertow.server.HttpHandler;
import io.undertow.servlet.api.DeploymentInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.mulesoft.examples.proxying_a_rest_api.ProxyingARestApiApplication;
import com.mulesoft.examples.proxying_a_rest_api.support.HttpTestClient;

/**
 * Tests {@link OriginFormTargetGuard} on the wire: every request target Undertow hands over without a leading
 * {@code /} gets the no-listener 404 and never reaches the upstream, and origin-form and absolute-form targets are
 * forwarded to the configured upstream as before.
 *
 * <ul>
 *   <li>A target that is neither in origin form nor an absolute URI with {@code //} and an authority (asterisk form
 *       with any method, {@code CONNECT}'s authority form, {@code @host/x}, {@code http:@host/x} and the like) gets
 *       the no-listener 404 with the bytes the generic error handling writes for a path no handler serves: status
 *       line {@code HTTP/1.1 404 Not Found}, the single {@code Content-Type: text/plain} and the body
 *       {@code No listener for endpoint: <path>}, the query left out.</li>
 *   <li>An absolute-form target reaches the configured upstream as its path and query only, whatever authority,
 *       user information or {@code Host} it names; those answers are pinned here so that a change of the listener
 *       shows.</li>
 *   <li>The guard runs inside the handler of {@link RequestFramingGuardConfig} that attaches each request's header
 *       fields as sent, whatever order the deployment customizer beans are created in.</li>
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

    @Autowired
    UndertowServletWebServerFactory webServerFactory;

    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream-url", () -> "http://" + LOOPBACK + ":" + upstream.getPort());
    }

    /** Method, target, {@code Host} or {@code null} for ST-1's, request body or {@code null}, reported path. */
    private static Stream<Arguments> nonOriginFormPaths() {
        return Stream.of(
                Arguments.of("OPTIONS", "*", null, null, "*"),
                Arguments.of("PRI", "*", null, null, "*"),
                Arguments.of("GET", "*", null, null, "*"),
                Arguments.of("OPTIONS", "*?x", null, null, "*"),
                Arguments.of("CONNECT", AUTHORITY, null, null, AUTHORITY),
                Arguments.of("CONNECT", "evil.example:8443", "evil.example:8443", null, "evil.example:8443"),
                // A target naming a host the Host header does not.
                Arguments.of("CONNECT", "evil.example:8443", null, null, "evil.example:8443"),
                Arguments.of("CONNECT", "@evil.example:8443", null, null, "@evil.example:8443"),
                Arguments.of("GET", "@evil.example:8443/x", null, null, "@evil.example:8443/x"),
                Arguments.of("GET", "http:@evil.example:8443/x", null, null, "http:@evil.example:8443/x"),
                Arguments.of("GET", ".evil/x", null, null, ".evil/x"),
                Arguments.of("GET", ":8443/x", null, null, ":8443/x"),
                Arguments.of("GET", "x@evil.example:8443/x", null, null, "x@evil.example:8443/x"),
                Arguments.of("POST", "@evil.example/x", null, "data", "@evil.example/x"),
                // Reported as the escaping filter would hand the path on: the raw | becomes %7C.
                Arguments.of("DELETE", "@evil.example/a|b", null, null, "@evil.example/a%7Cb"));
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

    /**
     * Method, target, {@code Host} or {@code null} for ST-1's, request body or {@code null}, URL the configured
     * upstream must receive.
     */
    private static Stream<Arguments> forwardedTargets() {
        return Stream.of(
                Arguments.of("GET", "/x", null, null, "/x"),
                Arguments.of("OPTIONS", "/x", null, null, "/x"),
                Arguments.of("PROPFIND", "/dav/folder", null, null, "/dav/folder"),
                Arguments.of("GET", "//evil.example/x", null, null, "//evil.example/x"),
                Arguments.of("GET", "/@evil.example:8443/x", null, null, "/@evil.example:8443/x"),
                // Absolute form: the authority is dropped, only the path and query travel.
                Arguments.of("GET", "http://evil.example/x", "evil.example", null, "/x"),
                Arguments.of("GET", "https://evil.example/x?q=1", "evil.example", null, "/x?q=1"),
                Arguments.of("GET", "http://" + AUTHORITY, null, null, "/"),
                // A target naming a host the Host header does not.
                Arguments.of("GET", "http://evil.example/x", null, null, "/x"),
                Arguments.of("GET", "http://u:p@evil.example:8443/x", "evil.example:8443", null, "/x"),
                // An authority that runs into its path or into another host is dropped the same way.
                Arguments.of("GET", "http://" + AUTHORITY + "@evil.example/x", null, null, "/x"),
                Arguments.of("GET", "http://" + AUTHORITY + "evil", null, null, "/"),
                Arguments.of("POST", "http://" + AUTHORITY + "@evil.example/x", null, "data", "/x"),
                Arguments.of("DELETE", "http://" + AUTHORITY + "@evil.example/a|b", null, null, "/a%7Cb"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("forwardedTargets")
    void originAndAbsoluteFormTargetsReachOnlyTheConfiguredUpstream(String method, String target, String host,
                                                                     String body, String upstreamUrl) {
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody(UPSTREAM_BODY)));

        HttpTestClient.Response response = send(method, target, host, body);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.bodyAsString()).as(response::toString).isEqualTo(UPSTREAM_BODY);
        List<ServeEvent> received = upstream.getAllServeEvents();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).getRequest().getMethod().getName()).isEqualTo(method);
        assertThat(received.get(0).getRequest().getUrl()).isEqualTo(upstreamUrl);
        assertThat(received.get(0).getRequest().getBodyAsString()).isEqualTo(body == null ? "" : body);
    }

    /**
     * The deployment customizers, applied in the order Spring Boot applies them, register the guard first and the
     * handler that attaches each request's header fields as sent last, so that handler wraps the guard and also runs
     * for every request the guard answers.
     */
    @Test
    void guardRunsInsideTheHandlerThatAttachesEachRequestsFieldsAsSent() {
        DeploymentInfo deployment = new DeploymentInfo();
        HttpHandler servletHandler = exchange -> { };

        webServerFactory.getDeploymentInfoCustomizers().forEach(customizer -> customizer.customize(deployment));

        List<HandlerWrapper> wrappers = deployment.getInitialHandlerChainWrappers();
        assertThat(wrappers).hasSize(2);
        assertThat(wrappers.get(0).wrap(servletHandler))
                .isInstanceOf(OriginFormTargetGuard.NonOriginFormPathHandler.class);
        assertThat(wrappers.get(1).wrap(servletHandler))
                .isInstanceOf(RequestFramingGuardConfig.SentHeaderFieldsHandler.class);
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
