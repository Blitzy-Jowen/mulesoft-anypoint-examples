/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.headRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * The contract of {@link ShopServiceClient} against a WireMock stand-in for {@code www.predic8.com:8080}, the
 * original {@code HTTP_Request_Configuration} with its {@code http:request method="POST" path="shop/ShopService"}
 * and the {@code cxf:proxy-service wsdlLocation} read ({@code soap-api-proxy.xml:4,7,10}).
 *
 * <p>{@code post} sends exactly one HTTP/1.1 POST carrying the envelope bytes and the given headers unchanged, returns
 * every upstream status as a value with its headers and exact body, follows redirects, and turns a timeout or a
 * refused connection into one {@link ResourceAccessException} without retrying. {@code wsdlResource} reads an
 * {@code http} WSDL and its relative schemas under the same timeout, and resolves any other location through the
 * {@code ResourceLoader}. Only localhost is ever contacted.
 */
class ShopServiceClientTest {

    private static final String UPSTREAM_PATH = "/shop/ShopService";

    private static final String WSDL_PATH_AND_QUERY = "/shop/ShopService?wsdl";

    private static final long TIMEOUT_MS = 1000L;

    private static final String CLASSPATH_WSDL = "classpath:upstream/ShopService.wsdl";

    private static final byte[] SOAP_RESPONSE =
            ("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                    + "<soap:Body><ns:createResponse xmlns:ns=\"http://predic8.com/wsdl/shop/1/\"><cartId>42</cartId>"
                    + "</ns:createResponse></soap:Body></soap:Envelope>").getBytes(UTF_8);

    private static final byte[] SOAP_FAULT =
            ("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                    + "<soap:Body><soap:Fault><faultcode>soap:Server</faultcode><faultstring>boom</faultstring>"
                    + "</soap:Fault></soap:Body></soap:Envelope>").getBytes(UTF_8);

    // gzip disabled: WireMock must serve the stubbed bytes exactly as given.
    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true))
            .build();

    private static ShopServiceClient client() {
        return client(wm.baseUrl() + WSDL_PATH_AND_QUERY);
    }

    private static ShopServiceClient client(String wsdlLocation) {
        return new ShopServiceClient(new DefaultResourceLoader(), wm.baseUrl() + UPSTREAM_PATH, wsdlLocation,
                TIMEOUT_MS);
    }

    private byte[] classpathBytes(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/" + name)) {
            assertThat(in).as("test resource %s", name).isNotNull();
            return in.readAllBytes();
        }
    }

    /** The original test's request, {@code message.xml}: a 248-byte SOAP 1.1 {@code create} envelope. */
    private byte[] envelope() throws IOException {
        return classpathBytes("message.xml");
    }

    private static HttpHeaders soapHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "text/xml; charset=UTF-8");
        headers.add("SOAPAction", "\"\"");
        return headers;
    }

    private static byte[] read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static LoggedRequest singlePost() {
        List<LoggedRequest> requests = wm.findAll(postRequestedFor(urlEqualTo(UPSTREAM_PATH)));
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = failure; cause != null && !chain.contains(cause); cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }

    static Stream<Arguments> invalidSettings() {
        String upstream = "http://localhost/shop/ShopService";
        return Stream.of(
                Arguments.of("blank upstream URL", " ", CLASSPATH_WSDL, TIMEOUT_MS),
                Arguments.of("relative upstream URL", UPSTREAM_PATH, CLASSPATH_WSDL, TIMEOUT_MS),
                Arguments.of("non-HTTP upstream URL", "ftp://localhost/shop/ShopService", CLASSPATH_WSDL, TIMEOUT_MS),
                Arguments.of("blank WSDL location", upstream, "", TIMEOUT_MS),
                Arguments.of("zero timeout", upstream, CLASSPATH_WSDL, 0L));
    }

    // Bound to proxy.upstream-url, proxy.wsdl-location and proxy.response-timeout-ms: a bad value fails start-up.
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSettings")
    void invalidSettingsAreRejectedAtConstruction(String description, String upstreamUrl, String wsdlLocation,
                                                  long timeoutMs) {
        assertThrows(IllegalArgumentException.class,
                () -> new ShopServiceClient(new DefaultResourceLoader(), upstreamUrl, wsdlLocation, timeoutMs));
    }

    // The caller's Authorization and SOAPAction reach predic8 unchanged: the proxy forwards the caller's identity.
    @Test
    void postSendsTheEnvelopeBytesAndEveryGivenHeaderUnchanged() throws IOException {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(ok()));
        byte[] bytes = envelope();
        HttpHeaders headers = soapHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer abc");
        headers.add("X-Correlation-Id", "c-1");
        headers.add("X-Multi", "a");
        headers.add("X-Multi", "b");

        ResponseEntity<byte[]> response = client().post(headers, bytes);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        wm.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH))
                .withRequestBody(binaryEqualTo(bytes))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo("text/xml; charset=UTF-8"))
                .withHeader("SOAPAction", equalTo("\"\""))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("Bearer abc"))
                .withHeader("X-Correlation-Id", equalTo("c-1"))
                .withHeader(HttpHeaders.CONTENT_LENGTH, equalTo(String.valueOf(bytes.length))));
        assertThat(singlePost().getHeaders().getHeader("X-Multi").values()).containsExactly("a", "b");
    }

    static Stream<Arguments> upstreamResponses() {
        return Stream.of(
                Arguments.of(200, "text/xml;charset=UTF-8", SOAP_RESPONSE),
                Arguments.of(404, "text/plain", "Not Found".getBytes(UTF_8)),
                Arguments.of(500, "text/xml", SOAP_FAULT));
    }

    // Deciding which status is a failure is the proxy service's job; the client only transports.
    @ParameterizedTest(name = "HTTP {0}")
    @MethodSource("upstreamResponses")
    void everyUpstreamStatusIsReturnedAsAValueWithItsHeadersAndExactBody(int status, String contentType, byte[] body)
            throws IOException {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(aResponse().withStatus(status)
                .withHeader(HttpHeaders.CONTENT_TYPE, contentType)
                .withHeader("X-Upstream-Trace", "t1")
                .withBody(body)));
        ShopServiceClient client = client();
        HttpHeaders headers = soapHeaders();
        byte[] bytes = envelope();

        ResponseEntity<byte[]> response = assertDoesNotThrow(() -> client.post(headers, bytes));

        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType(contentType));
        assertThat(response.getHeaders().getFirst("X-Upstream-Trace")).isEqualTo("t1");
        assertThat(response.getBody()).isEqualTo(body);
    }

    // Which method the JDK client uses after a 302 is its own redirect policy, so only the outcome is asserted.
    @Test
    void redirectIsFollowedToTheFinalResponse() throws IOException {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(aResponse().withStatus(302)
                .withHeader(HttpHeaders.LOCATION, "/shop/Moved")));
        wm.stubFor(any(urlEqualTo("/shop/Moved")).willReturn(ok("moved")));

        ResponseEntity<byte[]> response = client().post(soapHeaders(), envelope());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("moved".getBytes(UTF_8));
        wm.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH)));
        wm.verify(1, anyRequestedFor(urlEqualTo("/shop/Moved")));
    }

    // A timed-out envelope is never re-sent: predic8 may already have acted on it.
    @Test
    void timeoutFailsWithinTheConfiguredLimitAndIsNotRetried() throws IOException {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(ok().withFixedDelay(3000)));
        ShopServiceClient client = client();
        HttpHeaders headers = soapHeaders();
        byte[] bytes = envelope();

        long started = System.nanoTime();
        assertThrows(ResourceAccessException.class, () -> client.post(headers, bytes));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMs).as("elapsed until the %d ms timeout fired", TIMEOUT_MS).isLessThan(2900);
        // WireMock journals a request on arrival, so any retry made before the failure surfaced is already counted.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> wm.verify(exactly(1), postRequestedFor(urlEqualTo(UPSTREAM_PATH))));
        wm.verify(exactly(1), postRequestedFor(urlEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void refusedConnectionFailsWithResourceAccessException() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ShopServiceClient client = new ShopServiceClient(new DefaultResourceLoader(),
                "http://localhost:" + port + UPSTREAM_PATH, wm.baseUrl() + WSDL_PATH_AND_QUERY, TIMEOUT_MS);
        HttpHeaders headers = soapHeaders();
        byte[] bytes = envelope();

        ResourceAccessException failure = assertThrows(ResourceAccessException.class,
                () -> client.post(headers, bytes));

        assertThat(causeChain(failure)).hasAtLeastOneElementOfType(ConnectException.class);
    }

    // The JDK client rejects these names outright; the request goes out without them instead of failing.
    @Test
    void jdkRestrictedHeadersAreSkippedAndTheOthersForwarded() throws IOException {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(ok()));
        ShopServiceClient client = client();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.EXPECT, "100-continue");
        headers.add(HttpHeaders.UPGRADE, "websocket");
        headers.add("X-Keep", "1");
        headers.add(HttpHeaders.CONTENT_TYPE, "text/xml");
        byte[] bytes = envelope();

        ResponseEntity<byte[]> response = assertDoesNotThrow(() -> client.post(headers, bytes));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        LoggedRequest sent = singlePost();
        assertThat(sent.containsHeader(HttpHeaders.EXPECT)).isFalse();
        assertThat(sent.containsHeader(HttpHeaders.UPGRADE)).isFalse();
        assertThat(sent.getHeader("X-Keep")).isEqualTo("1");
    }

    // The JDK default HTTP_2 would offer h2c on every http: request, which the Mule HTTP/1.1 requester never did.
    @Test
    void postIsPlainHttp11WithoutAnH2cUpgrade() throws IOException {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(ok()));
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "text/xml");

        client().post(headers, envelope());

        LoggedRequest sent = singlePost();
        assertThat(sent.getProtocol()).isEqualTo("HTTP/1.1");
        assertThat(sent.containsHeader(HttpHeaders.UPGRADE)).isFalse();
        assertThat(sent.containsHeader("HTTP2-Settings")).isFalse();
    }

    @Test
    void httpWsdlIsReadWithItsExactBytes() throws IOException {
        byte[] wsdl = classpathBytes("upstream/ShopService.wsdl");
        wm.stubFor(get(urlEqualTo(WSDL_PATH_AND_QUERY)).willReturn(aResponse().withStatus(200)
                .withHeader(HttpHeaders.CONTENT_TYPE, "text/xml")
                .withBody(wsdl)));

        Resource resource = client().wsdlResource();

        assertThat(resource).isInstanceOf(UrlResource.class);
        assertThat(resource.getURL().toString()).isEqualTo(wm.baseUrl() + WSDL_PATH_AND_QUERY);
        assertThat(read(resource)).isEqualTo(wsdl);
    }

    // CXF read the WSDL and its schemas with GET only; an upstream refusing HEAD must not make the WSDL look absent.
    @Test
    void existsProbesTheHttpLocationWithGetOnly() throws IOException {
        wm.stubFor(get(urlEqualTo(WSDL_PATH_AND_QUERY)).willReturn(ok("<wsdl/>")));
        wm.stubFor(get(urlEqualTo("/shop/Missing.xsd")).willReturn(notFound()));
        Resource wsdl = client().wsdlResource();
        Resource missing = wsdl.createRelative("Missing.xsd");

        assertThat(wsdl.exists()).isTrue();
        assertThat(wsdl.isReadable()).isTrue();
        assertThat(missing.exists()).isFalse();
        assertThat(missing.isReadable()).isFalse();
        wm.verify(0, headRequestedFor(urlEqualTo(WSDL_PATH_AND_QUERY)));
        wm.verify(0, headRequestedFor(urlEqualTo("/shop/Missing.xsd")));
        wm.verify(2, getRequestedFor(urlEqualTo(WSDL_PATH_AND_QUERY)));
    }

    @Test
    void createRelativeResolvesARelativeSchemaAgainstTheWsdlUrl() throws IOException {
        byte[] xsd = classpathBytes("upstream/ShopService.xsd");
        wm.stubFor(get(urlEqualTo("/shop/ShopService.xsd")).willReturn(aResponse().withStatus(200)
                .withHeader(HttpHeaders.CONTENT_TYPE, "text/xml")
                .withBody(xsd)));
        Resource wsdl = client().wsdlResource();

        Resource schema = wsdl.createRelative("ShopService.xsd");

        assertThat(schema.getURL().toString()).isEqualTo(wm.baseUrl() + "/shop/ShopService.xsd");
        // The same timeout-applying type, so schema reads are bounded like the WSDL read.
        assertThat(schema.getClass()).isEqualTo(wsdl.getClass());
        assertThat(read(schema)).isEqualTo(xsd);
    }

    // JAX-WS WSDLs import schemas by absolute URL (?xsd=1), which UrlResource would append to the WSDL's directory.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/other/Other.xsd", "/shop/ShopService?xsd=1"})
    void createRelativeKeepsAnAbsoluteSchemaLocationUnchanged(String pathAndQuery) throws IOException {
        String absolute = wm.baseUrl() + pathAndQuery;

        Resource schema = client().wsdlResource().createRelative(absolute);

        assertThat(schema.getURL().toString()).isEqualTo(absolute);
    }

    // A WSDL fetched from the network must not make the application read and publish a local file.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"file:/etc/hosts", "jar:file:/tmp/schemas.jar!/ShopService.xsd"})
    void createRelativeRejectsALocationOutsideHttp(String location) {
        Resource wsdl = client().wsdlResource();

        assertThrows(MalformedURLException.class, () -> wsdl.createRelative(location));
    }

    // A plain UrlResource would block for the full 5 s delay; the timeouts must reach every URLConnection.
    @Test
    void delayedWsdlFailsWithinTheConfiguredTimeout() {
        wm.stubFor(get(urlEqualTo(WSDL_PATH_AND_QUERY)).willReturn(ok("<wsdl/>").withFixedDelay(5000)));
        Resource resource = client().wsdlResource();

        assertTimeoutPreemptively(Duration.ofMillis(TIMEOUT_MS + 2500),
                () -> assertThrows(IOException.class, () -> {
                    try (InputStream in = resource.getInputStream()) {
                        in.readAllBytes();
                    }
                }));
        assertTimeoutPreemptively(Duration.ofMillis(TIMEOUT_MS + 2500),
                () -> assertThat(resource.exists()).isFalse());
    }

    // The stand-in surefire sets as proxy.wsdl-location, so every test context starts without the network.
    @Test
    void classpathWsdlAndItsRelativeSchemaAreReadWithoutAnyHttpRequest() throws IOException {
        Resource resource = client(CLASSPATH_WSDL).wsdlResource();

        assertThat(resource.exists()).isTrue();
        assertThat(resource.isReadable()).isTrue();
        byte[] bytes = read(resource);
        assertThat(bytes).isEqualTo(classpathBytes("upstream/ShopService.wsdl"));
        assertThat(new String(bytes, UTF_8)).contains("ShopService");
        // The WSDL's own import, served by WsdlQueryPublisher at ?xsd=ShopService.xsd.
        assertThat(resource.createRelative("ShopService.xsd").exists()).isTrue();
        assertThat(wm.getAllServeEvents()).isEmpty();
    }
}
