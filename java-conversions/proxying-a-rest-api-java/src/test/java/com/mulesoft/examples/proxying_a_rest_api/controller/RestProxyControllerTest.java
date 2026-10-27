/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import com.mulesoft.examples.proxying_a_rest_api.ProxyingARestApiApplication;
import com.mulesoft.examples.proxying_a_rest_api.service.RestProxyService;
import com.mulesoft.examples.proxying_a_rest_api.support.HttpTestClient;
import com.mulesoft.examples.proxying_a_rest_api.support.HttpTestClient.Response;

/**
 * Wire-level test of {@link RestProxyController}, the listener of the original flow {@code rest-api-proxy}
 * ({@code proxying-a-rest-api.xml:11-20}): {@code http:listener path="/*"} without {@code allowedMethods}, the
 * {@code copy-headers} sub-flow on both legs, and an {@code http:request} with the caller's method, raw path and query
 * whose response is left unparsed.
 *
 * <p>The application boots with its own {@code application.yml} on Undertow in front of a WireMock server standing in
 * for {@code https://api.box.com:443}. Every request goes through ST-1 {@link HttpTestClient}, so the raw status
 * line, the response headers and the body bytes are asserted as the listener writes them, and every forwarded request
 * is asserted as WireMock received it. Covered:
 * <ul>
 *   <li>happy paths: the response bytes and {@code Content-Type} returned unchanged; POST, PUT, PATCH and DELETE
 *       bodies, multipart and form bodies forwarded byte for byte; OPTIONS and HEAD forwarded without a body;
 *       {@code /} and {@code /error} proxied rather than answered locally; an upstream 201 answered 200, because the
 *       second {@code copy-headers} removes {@code http.status};</li>
 *   <li>header copying (SC-13) in both directions, the caller's {@code Authorization} reaching the upstream
 *       unchanged;</li>
 *   <li>errors, each the platform-default 500 {@code { "message": ... }} of SC-1 because the flow declares no
 *       exception strategy: an upstream status of 400 or above failing the requester's success-status validator, an
 *       unreachable upstream, and an upstream slower than {@code proxy.response-timeout-ms}, which is never
 *       retried.</li>
 * </ul>
 *
 * <p>No request leaves the loopback interface: the listener and WireMock are both bound to {@link #LOOPBACK}, and the
 * unreachable-upstream case points a second application context at a loopback port held by a bound socket that never
 * listens, so every connection to it is refused.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to the address ST-1 connects to, whatever server.address the project sets.
        properties = "server.address=" + RestProxyControllerTest.LOOPBACK)
@ActiveProfiles("test")
class RestProxyControllerTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    /**
     * {@code proxy.response-timeout-ms} for this class: short enough for the timeout case to stay quick, long enough
     * that a WireMock answer on a busy build host never reaches it in the other cases.
     */
    private static final long RESPONSE_TIMEOUT_MS = 2_000;

    /** Delay of the slow upstream, well past {@link #RESPONSE_TIMEOUT_MS}. */
    private static final int SLOW_UPSTREAM_DELAY_MS = 5_000;

    /** The body of every 500 the SC-1 advice writes starts with this, APIkit spacing included. */
    private static final String ERROR_BODY_START = "{ \"message\": ";

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress(LOOPBACK))
            .build();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void proxyProperties(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream-url", () -> "http://" + LOOPBACK + ":" + upstream.getPort());
        registry.add("proxy.response-timeout-ms", () -> String.valueOf(RESPONSE_TIMEOUT_MS));
    }

    private HttpTestClient client() {
        return new HttpTestClient(LOOPBACK, port);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Happy paths: method, path, query and bytes forwarded; the upstream answer returned unparsed
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void getFolderIsForwardedAndBytesReturned() {
        byte[] folder = "{\"type\":\"folder\",\"id\":\"0\",\"name\":\"All Files\"}".getBytes(UTF_8);
        upstream.stubFor(get(urlEqualTo("/2.0/folders/0?fields=name,id"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(folder)));

        Response response = client().get("/2.0/folders/0?fields=name,id");

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.body()).isEqualTo(folder);
        assertThat(response.headers("Content-Type")).containsExactly("application/json");
        assertThat(response.headers("Content-Length")).containsExactly(String.valueOf(folder.length));
        // The raw query reaches the upstream unchanged, and a GET carries no body.
        upstream.verify(exactly(1), getRequestedFor(urlEqualTo("/2.0/folders/0?fields=name,id")));
        assertThat(singleForwardedRequest().getBody()).isEmpty();
    }

    /** Method, then request {@code Content-Type} and body bytes: UTF-8 text with non-ASCII, and raw binary. */
    private static Stream<Arguments> methodsWithBodies() {
        byte[] utf8Json = "{\"name\":\"caf\u00e9 \u20ac \u65e5\u672c\"}".getBytes(UTF_8);
        byte[] binary = {0x00, (byte) 0xFF, 0x7F, (byte) 0x80, '\r', '\n', 0x1B, (byte) 0xC3, 0x28, 0x00};
        return Stream.of("POST", "PUT", "PATCH", "DELETE").flatMap(method -> Stream.of(
                Arguments.of(method, "application/json; charset=UTF-8", utf8Json),
                Arguments.of(method, "application/octet-stream", binary)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("methodsWithBodies")
    void methodAndBodyBytesAreForwardedUnchanged(String method, String contentType, byte[] body) {
        upstream.stubFor(any(urlEqualTo("/2.0/files/1")).willReturn(ok("done")));

        Response response = client().send(method, "/2.0/files/1", Map.of("Content-Type", contentType), body);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.bodyAsString()).isEqualTo("done");
        upstream.verify(exactly(1), RequestPatternBuilder
                .newRequestPattern(RequestMethod.fromString(method), urlEqualTo("/2.0/files/1"))
                .withHeader("Content-Type", equalTo(contentType))
                .withRequestBody(binaryEqualTo(body)));
        assertThat(upstream.getAllServeEvents()).as("upstream calls").hasSize(1);
    }

    @Test
    void multipartBodyIsForwardedByteIdentical() {
        String boundary = "----proxyBoundary7MA4YWxk";
        String contentType = "multipart/form-data; boundary=" + boundary;
        byte[] body = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"description\"\r\n"
                + "\r\n"
                + "Quarterly report\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "hello from the proxy\r\n"
                + "--" + boundary + "--\r\n").getBytes(UTF_8);
        upstream.stubFor(post(urlEqualTo("/2.0/files/content")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"total_count\":1}")));

        Response response = client().send("POST", "/2.0/files/content", Map.of("Content-Type", contentType), body);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.bodyAsString()).isEqualTo("{\"total_count\":1}");
        // Unparsed by the servlet layer (spring.servlet.multipart.enabled is false): boundary and parts arrive as sent.
        upstream.verify(exactly(1), postRequestedFor(urlEqualTo("/2.0/files/content"))
                .withHeader("Content-Type", equalTo(contentType))
                .withRequestBody(binaryEqualTo(body)));
    }

    // PUT is the method FormContentFilter would consume; POST is the original test's token form.
    @ParameterizedTest
    @ValueSource(strings = {"PUT", "POST"})
    void formUrlEncodedBodyArrivesIntact(String method) {
        String contentType = "application/x-www-form-urlencoded";
        byte[] form = "a=1&b=two%20words&c=".getBytes(UTF_8);
        upstream.stubFor(any(urlEqualTo("/2.0/folders/7")).willReturn(ok("updated")));

        Response response = client().send(method, "/2.0/folders/7", Map.of("Content-Type", contentType), form);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.bodyAsString()).isEqualTo("updated");
        upstream.verify(exactly(1), RequestPatternBuilder
                .newRequestPattern(RequestMethod.fromString(method), urlEqualTo("/2.0/folders/7"))
                .withHeader("Content-Type", equalTo(contentType))
                .withRequestBody(binaryEqualTo(form)));
    }

    @Test
    void optionsAndHeadAreForwardedWithoutBody() {
        upstream.stubFor(any(urlEqualTo("/2.0/folders"))
                .willReturn(ok().withHeader("X-Upstream-Answer", "folders")));

        Response options = client().send("OPTIONS", "/2.0/folders", Map.of(), null);
        Response head = client().send("HEAD", "/2.0/folders", Map.of(), null);

        assertThat(options.statusLine()).as("%s", options).isEqualTo("HTTP/1.1 200 OK");
        assertThat(head.statusLine()).as("%s", head).isEqualTo("HTTP/1.1 200 OK");
        // The upstream's answer, not Spring MVC's own OPTIONS handling, which would add Allow.
        assertThat(options.headers("X-Upstream-Answer")).containsExactly("folders");
        assertThat(options.headers("Allow")).isEmpty();
        assertThat(head.headers("X-Upstream-Answer")).containsExactly("folders");

        List<LoggedRequest> forwarded = upstream.getAllServeEvents().stream().map(ServeEvent::getRequest).toList();
        assertThat(forwarded).extracting(request -> request.getMethod().getName())
                .containsExactlyInAnyOrder("OPTIONS", "HEAD");
        assertThat(forwarded).allSatisfy(request -> {
            assertThat(request.getUrl()).isEqualTo("/2.0/folders");
            assertThat(request.getBody()).as("%s body", request.getMethod()).isEmpty();
        });
    }

    @Test
    void rootAndErrorPathsAreForwardedNotAnsweredLocally() {
        upstream.stubFor(get(urlEqualTo("/")).willReturn(ok("root-from-upstream")));
        upstream.stubFor(get(urlEqualTo("/error")).willReturn(ok("error-path-from-upstream")));

        Response root = client().get("/");
        Response error = client().get("/error");

        assertThat(root.statusLine()).as("%s", root).isEqualTo("HTTP/1.1 200 OK");
        assertThat(root.bodyAsString()).isEqualTo("root-from-upstream");
        // No BasicErrorController: /error is a path like any other under the /* listener.
        assertThat(error.statusLine()).as("%s", error).isEqualTo("HTTP/1.1 200 OK");
        assertThat(error.bodyAsString()).isEqualTo("error-path-from-upstream");
        upstream.verify(exactly(1), getRequestedFor(urlEqualTo("/")));
        upstream.verify(exactly(1), getRequestedFor(urlEqualTo("/error")));
    }

    @Test
    void upstreamCreatedBecomes200() {
        byte[] created = "{\"id\":\"42\"}".getBytes(UTF_8);
        upstream.stubFor(post(urlEqualTo("/2.0/folders")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withHeader("Location", "https://api.box.com/2.0/folders/42")
                .withBody(created)));

        Response response = client().post("/2.0/folders", "application/json",
                "{\"name\": \"mule-proxy-test\", \"parent\": {\"id\": \"0\"}}");

        // The listener never received the upstream status: copy-headers removed http.status with the other http.*.
        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.body()).isEqualTo(created);
        assertThat(response.headers("Location")).containsExactly("https://api.box.com/2.0/folders/42");
        upstream.verify(exactly(1), postRequestedFor(urlEqualTo("/2.0/folders"))
                .withRequestBody(binaryEqualTo(
                        "{\"name\": \"mule-proxy-test\", \"parent\": {\"id\": \"0\"}}".getBytes(UTF_8))));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Header copying (SC-13): Host, Content-Length, Connection, Transfer-Encoding, http.* and MULE_* removed
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void outboundHeadersAreFilteredAndAuthorizationForwarded() {
        upstream.stubFor(get(urlEqualTo("/2.0/folders/0")).willReturn(ok("{}")));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer abc");
        headers.put("MULE_X", "1");
        headers.put("http.foo", "bar");
        headers.put("X-Custom", "keep");
        // ST-1 adds Connection: close, the caller's own value that copy-headers must not carry upstream.

        Response response = client().get("/2.0/folders/0", headers);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        upstream.verify(exactly(1), getRequestedFor(urlEqualTo("/2.0/folders/0"))
                .withHeader("Authorization", equalTo("Bearer abc"))
                .withHeader("X-Custom", equalTo("keep"))
                .withoutHeader("MULE_X")
                .withoutHeader("http.foo")
                // The upstream's own authority, never the caller's Host of the proxy listener.
                .withHeader("Host", equalTo(LOOPBACK + ":" + upstream.getPort())));
        LoggedRequest forwarded = singleForwardedRequest();
        assertThat(values(forwarded, "Authorization")).containsExactly("Bearer abc");
        assertThat(values(forwarded, "Host")).containsExactly(LOOPBACK + ":" + upstream.getPort());
        // The transport may announce its own keep-alive, as it writes its own Host; the caller's close is not copied.
        assertThat(values(forwarded, "Connection"))
                .noneSatisfy(value -> assertThat(value).containsIgnoringCase("close"));
    }

    @Test
    void inboundHeadersAreFilteredOnTheWayBack() {
        upstream.stubFor(get(urlEqualTo("/2.0/folders/0")).willReturn(ok("{\"id\":\"0\"}")
                .withHeader("MULE_ENCODING", "UTF-8")
                .withHeader("http.status", "200")
                .withHeader("X-Box-Request-Id", "req-123")));

        Response response = client().get("/2.0/folders/0");

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.headers()).extracting(Map.Entry::getKey)
                .noneSatisfy(name -> assertThat(name).isEqualToIgnoringCase("MULE_ENCODING"))
                .noneSatisfy(name -> assertThat(name).isEqualToIgnoringCase("http.status"));
        assertThat(response.headers("X-Box-Request-Id")).containsExactly("req-123");
        // The upstream framing is dropped and the listener writes its own, once.
        assertThat(response.headers("Content-Length")).containsExactly("10");
        assertThat(response.headers("Transfer-Encoding")).isEmpty();
        assertThat(response.bodyAsString()).isEqualTo("{\"id\":\"0\"}");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Errors: the flow declares no exception strategy, so every failure is SC-1's platform-default 500
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void upstream404Becomes500Json() {
        upstream.stubFor(get(urlEqualTo("/2.0/folders/missing")).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withHeader("X-Box-Request-Id", "req-404")
                .withBody("{\"type\":\"error\",\"status\":404}")));

        Response response = client().get("/2.0/folders/missing");

        assertThat(response.status()).as("%s", response).isEqualTo(500);
        assertThat(response.statusLine()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertJsonContentType(response);
        String expected = "{ \"message\": \"" + String.format(RestProxyService.VALIDATOR_FAILURE_MESSAGE, 404) + "\" }";
        assertThat(expected).isEqualTo("{ \"message\": \"Response code 404 mapped as failure. "
                + "Message payload is of type: BufferInputStream\" }");
        assertThat(response.bodyAsString()).isEqualTo(expected);
        // The validator fails before the second copy-headers, so no upstream header reaches the caller.
        assertThat(response.headers("X-Box-Request-Id")).isEmpty();
        upstream.verify(exactly(1), getRequestedFor(urlEqualTo("/2.0/folders/missing")));
        assertThat(upstream.getAllServeEvents()).as("upstream calls").hasSize(1);
    }

    @Test
    void unreachableUpstreamBecomes500Json() throws IOException {
        // Held until the context has closed, so no other process can bind the port and answer in its place.
        try (Socket reservation = reserveRefusingPort()) {
            int deadPort = reservation.getLocalPort();
            // Command-line arguments outrank application.yml; builder properties() would be overridden by it.
            ConfigurableApplicationContext context = new SpringApplicationBuilder(ProxyingARestApiApplication.class)
                    .profiles("test")
                    .run("--server.port=0",
                            "--server.address=" + LOOPBACK,
                            "--proxy.upstream-url=http://" + LOOPBACK + ":" + deadPort,
                            "--proxy.response-timeout-ms=" + RESPONSE_TIMEOUT_MS);
            try {
                int proxyPort = ((WebServerApplicationContext) context).getWebServer().getPort();

                Response response = new HttpTestClient(LOOPBACK, proxyPort).get("/2.0/folders/0");

                assertThat(response.status()).as("%s", response).isEqualTo(500);
                assertThat(response.statusLine()).isEqualTo("HTTP/1.1 500 Internal Server Error");
                assertJsonContentType(response);
                // The text is the transport's connect-failure message, which depends on the JDK and the client.
                assertThat(response.bodyAsString()).startsWith(ERROR_BODY_START).endsWith("\" }");
            } finally {
                context.close();
            }
            assertThat(upstream.getAllServeEvents()).as("calls reaching the shared upstream").isEmpty();
        }
    }

    @Test
    void upstreamTimeoutBecomes500WithoutRetry() {
        upstream.stubFor(get(urlEqualTo("/2.0/folders/slow"))
                .willReturn(ok("too late").withFixedDelay(SLOW_UPSTREAM_DELAY_MS)));

        long started = System.nanoTime();
        Response response = client().get("/2.0/folders/slow");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(response.status()).as("%s", response).isEqualTo(500);
        assertThat(response.statusLine()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertJsonContentType(response);
        assertThat(response.bodyAsString()).startsWith(ERROR_BODY_START).doesNotContain("too late");
        assertThat(elapsedMs).as("elapsed until the %d ms response timeout fired", RESPONSE_TIMEOUT_MS)
                .isLessThan(SLOW_UPSTREAM_DELAY_MS - 500L);
        // Past the stub's delay, so a silent retry would have been journaled by now.
        long remainingMs = Math.max(0, SLOW_UPSTREAM_DELAY_MS + 500L - elapsedMs);
        await().pollDelay(Duration.ofMillis(remainingMs)).atMost(Duration.ofMillis(remainingMs + 5_000))
                .untilAsserted(() -> upstream.verify(exactly(1), getRequestedFor(urlEqualTo("/2.0/folders/slow"))));
        assertThat(upstream.getAllServeEvents()).as("upstream calls").hasSize(1);
    }

    // ---------------------------------------------------------------------------------------------------------------

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

    /** The one request the upstream received in this test. */
    private static LoggedRequest singleForwardedRequest() {
        List<ServeEvent> events = upstream.getAllServeEvents();
        assertThat(events).as("upstream calls").hasSize(1);
        return events.get(0).getRequest();
    }

    /** Every value of the header {@code name} (matched ignoring case) the upstream received, in order. */
    private static List<String> values(LoggedRequest request, String name) {
        HttpHeader header = request.getHeaders().getHeader(name);
        return header.isPresent() ? header.values() : List.of();
    }

    /** {@code Content-Type} is {@code application/json}, parameters aside. */
    private static void assertJsonContentType(Response response) {
        String contentType = response.header("Content-Type");
        assertThat(contentType).as("Content-Type of %s", response).isNotNull();
        assertThat(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(MediaType.APPLICATION_JSON))
                .as("Content-Type %s is application/json", contentType).isTrue();
    }
}
