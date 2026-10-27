/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.binaryEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToIgnoreCase;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import com.mulesoft.examples.proxying_a_soap_api.service.SoapProxyService;
import com.mulesoft.examples.proxying_a_soap_api.support.HttpTestClient;
import com.mulesoft.examples.proxying_a_soap_api.support.HttpTestClient.Response;

/**
 * Wire-level test of {@link SoapProxyController}, the listener of the original flow {@code main}
 * ({@code soap-api-proxy.xml:5-12}): {@code http:listener path="/"} without {@code allowedMethods}, the
 * {@code cxf:proxy-service}/{@code cxf:proxy-client} pair working on the raw envelope, the {@code copy-headers}
 * sub-flow on both legs and the one {@code http:request method="POST" path="shop/ShopService"}.
 *
 * <p>The whole application ({@code ProxyingASoapApiApplication}, found by package scan) boots on Undertow with its own
 * {@code application.yml}, in front of a WireMock server standing in for {@code www.predic8.com:8080}. The upstream
 * WSDL is the offline stand-in {@code classpath:upstream/ShopService.wsdl}. Every proxy request goes through ST-1
 * {@link HttpTestClient}, so the raw status line, headers and body bytes are asserted as the listener writes them, and
 * every forwarded request is asserted as WireMock journaled it. Covered:
 * <ul>
 *   <li>the original test's case: {@code message.xml} POSTed to {@code /} comes back with the upstream's
 *       {@code <cartId>} envelope, byte for byte;</li>
 *   <li>the proxy client's SOAP 1.1 defaults (Content-Type kept or replaced, empty {@code SOAPAction}), the header
 *       copying of SC-13 in both directions with the caller's {@code Authorization} forwarded, the query dropped,
 *       every method sent as the one {@code POST}, an upstream status below 400 answered 200, a gzip body passed
 *       through undecoded and {@code Expect: 100-continue} honoured;</li>
 *   <li>SC-11 {@code GET /?wsdl} and {@code GET /?xsd=} served from the stand-in without any upstream call;</li>
 *   <li>errors, each the SC-1 platform default because the flow declares no exception strategy: an upstream status of
 *       400 or above failing the requester's success-status validator, an upstream slower than
 *       {@code proxy.response-timeout-ms} (never retried), an unreachable upstream, and a path no listener serves.</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "proxy.wsdl-location=" + SoapProxyControllerTest.WSDL_LOCATION,
                "proxy.response-timeout-ms=" + SoapProxyControllerTest.RESPONSE_TIMEOUT_MS})
class SoapProxyControllerTest {

    static final String WSDL_LOCATION = "classpath:upstream/ShopService.wsdl";

    /**
     * {@code proxy.response-timeout-ms} of both contexts: two seconds rather than one, as in the REST proxy's
     * controller test, because on a build host shared by many builds a WireMock answer can take longer than a second
     * and would fail the cases that expect an answer. The slow upstream's delay keeps the timeout case decisive.
     */
    static final long RESPONSE_TIMEOUT_MS = 2_000;

    /** Delay of the slow upstream, well past {@link #RESPONSE_TIMEOUT_MS}. */
    private static final int SLOW_UPSTREAM_DELAY_MS = 5_000;

    /** The {@code http:request path} on the upstream. */
    private static final String UPSTREAM_PATH = "/shop/ShopService";

    private static final String SOAP11_CONTENT_TYPE = "text/xml; charset=UTF-8";

    private static final String SOAP12_CONTENT_TYPE = "application/soap+xml; charset=UTF-8";

    /** The empty SOAP 1.1 action: the two double-quote characters. */
    private static final String EMPTY_SOAP_ACTION = "\"\"";

    /** The upstream's answer to {@code create}, as one compact envelope. */
    private static final byte[] CREATE_RESPONSE = (
            "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soapenv:Body><ns:createResponse xmlns:ns=\"http://predic8.com/wsdl/shop/1/\"><cartId>42</cartId>"
            + "</ns:createResponse></soapenv:Body></soapenv:Envelope>").getBytes(UTF_8);

    private static final byte[] SOAP_FAULT = ("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body><soap:Fault><faultcode>soap:Server</faultcode><faultstring>Cart service unavailable"
            + "</faultstring></soap:Fault></soap:Body></soap:Envelope>").getBytes(UTF_8);

    /** The body of every 500 the SC-1 advice writes starts with this, APIkit spacing included. */
    private static final String ERROR_BODY_START = "{ \"message\": ";

    private static final Pattern SCHEMA_LOCATION = Pattern.compile("schemaLocation=\"([^\"]+)\"");

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true))
            .build();

    /** The bytes of the original test's request, {@code src/test/resources/message.xml}. */
    private static byte[] message;

    @LocalServerPort
    int port;

    private HttpTestClient client;

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream-url", () -> wm.baseUrl() + UPSTREAM_PATH);
    }

    @BeforeAll
    static void loadMessage() throws IOException {
        message = new ClassPathResource("message.xml").getContentAsByteArray();
        assertThat(message).as("message.xml, a byte copy of the original").hasSize(248);
    }

    @BeforeEach
    void createClient() {
        client = HttpTestClient.localhost(port);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Happy path: the envelope is forwarded unparsed and the upstream bytes come back
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void createEnvelopeIsForwardedAndCartIdReturned() {
        stubOk(CREATE_RESPONSE, "text/xml;charset=UTF-8");

        Response response = client.send("POST", "/", Map.of("Content-Type", SOAP11_CONTENT_TYPE), message);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.body()).isEqualTo(CREATE_RESPONSE);
        // The original ProxyingSoapApiIT's assertions.
        assertThat(response.bodyAsString()).contains("<cartId>").contains("</cartId>");
        assertThat(response.headers("Content-Length")).containsExactly(String.valueOf(CREATE_RESPONSE.length));
        wm.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH)).withRequestBody(binaryEqualTo(message)));
        assertThat(forwarded()).as("upstream calls").hasSize(1);
    }

    /**
     * A SOAP Content-Type, then the {@code SOAPAction} the upstream receives for a caller that sent none: SOAP 1.2
     * carries its action in the Content-Type, so the proxy client adds none ({@code null}).
     */
    private static Stream<Arguments> soapContentTypes() {
        return Stream.of(
                Arguments.of(SOAP11_CONTENT_TYPE, EMPTY_SOAP_ACTION),
                Arguments.of(SOAP12_CONTENT_TYPE, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("soapContentTypes")
    void soapContentTypeReachesTheUpstreamUnchanged(String contentType, String upstreamSoapAction) {
        stubOk(CREATE_RESPONSE, "text/xml;charset=UTF-8");

        Response response = client.send("POST", "/", Map.of("Content-Type", contentType), message);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        wm.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH)).withHeader("Content-Type", equalTo(contentType)));
        LoggedRequest upstream = singleForwarded();
        assertThat(headerValues(upstream, "Content-Type")).containsExactly(contentType);
        assertThat(headerValues(upstream, "SOAPAction"))
                .isEqualTo(upstreamSoapAction == null ? List.of() : List.of(upstreamSoapAction));
    }

    /** {@code null} sends no Content-Type at all; the other values are not a SOAP media type. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"text/plain", "application/xml", "not a media type"})
    void otherOrMissingContentTypeIsReplacedBySoap11(String callerContentType) {
        stubOk(CREATE_RESPONSE, "text/xml;charset=UTF-8");
        Map<String, String> headers = callerContentType == null ? Map.of() : Map.of("Content-Type", callerContentType);

        Response response = client.send("POST", "/", headers, message);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(SoapProxyService.UPSTREAM_SOAP11_CONTENT_TYPE).isEqualTo("text/xml; charset=UTF-8");
        LoggedRequest upstream = singleForwarded();
        assertThat(headerValues(upstream, "Content-Type"))
                .containsExactly(SoapProxyService.UPSTREAM_SOAP11_CONTENT_TYPE);
        assertThat(headerValues(upstream, "SOAPAction")).containsExactly(EMPTY_SOAP_ACTION);
        assertThat(upstream.getBody()).isEqualTo(message);
    }

    /** {@code null}: the caller sends no SOAPAction and the proxy client supplies the empty one. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"\"urn:create\"", EMPTY_SOAP_ACTION})
    void soapActionIsDefaultedToEmptyOrKept(String callerSoapAction) {
        stubOk(CREATE_RESPONSE, "text/xml;charset=UTF-8");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", SOAP11_CONTENT_TYPE);
        if (callerSoapAction != null) {
            headers.put("SOAPAction", callerSoapAction);
        }

        Response response = client.send("POST", "/", headers, message);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        String expected = callerSoapAction == null ? EMPTY_SOAP_ACTION : callerSoapAction;
        assertThat(headerValues(singleForwarded(), "SOAPAction")).containsExactly(expected);
    }

    @Test
    void callerHeadersAuthorizationIncludedReachTheUpstream() {
        stubOk(CREATE_RESPONSE, "text/xml;charset=UTF-8");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", SOAP11_CONTENT_TYPE);
        headers.put("Authorization", "Basic dXNlcjpwYXNz");
        headers.put("X-Custom", "abc");

        Response response = client.send("POST", "/", headers, message);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        wm.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH))
                .withHeader("Authorization", equalTo("Basic dXNlcjpwYXNz"))
                .withHeader("X-Custom", equalTo("abc")));
        LoggedRequest upstream = singleForwarded();
        assertThat(headerValues(upstream, "Authorization")).containsExactly("Basic dXNlcjpwYXNz");
        assertThat(headerValues(upstream, "X-Custom")).containsExactly("abc");
    }

    @Test
    void hostConnectionMuleAndHttpPropertyHeadersAreNotCopiedUpstream() {
        stubOk(CREATE_RESPONSE, "text/xml;charset=UTF-8");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", SOAP11_CONTENT_TYPE);
        headers.put("MULE_CORRELATION_ID", "123");
        headers.put("http.method", "POST");
        // ST-1 also writes the caller's Host (the proxy's own authority) and Connection: close.

        Response response = client.send("POST", "/", headers, message);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        wm.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH))
                .withoutHeader("MULE_CORRELATION_ID")
                .withoutHeader("http.method")
                .withHeader("Host", equalTo("localhost:" + wm.getPort())));
        LoggedRequest upstream = singleForwarded();
        assertThat(headerValues(upstream, "Host")).containsExactly("localhost:" + wm.getPort());
        // The transport announces its own keep-alive, as it writes its own Host, so the header itself may be present;
        // the caller's close never is. The envelope is framed once, with its own length.
        assertThat(headerValues(upstream, "Connection"))
                .noneSatisfy(value -> assertThat(value).containsIgnoringCase("close"));
        assertThat(headerValues(upstream, "Content-Length")).containsExactly(String.valueOf(message.length));
    }

    @Test
    void queryIsNotForwarded() {
        stubOk(CREATE_RESPONSE, "text/xml;charset=UTF-8");

        Response response = client.send("POST", "/?foo=bar", Map.of("Content-Type", SOAP11_CONTENT_TYPE), message);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.body()).isEqualTo(CREATE_RESPONSE);
        wm.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH)));
        assertThat(forwarded()).as("upstream calls").singleElement()
                .satisfies(request -> assertThat(request.getUrl()).isEqualTo(UPSTREAM_PATH));
    }

    /** Method, and whether the request carries {@code message.xml} or no body at all. */
    private static Stream<Arguments> nonPostMethods() {
        return Stream.of(
                Arguments.of("PUT", true),
                Arguments.of("DELETE", true),
                Arguments.of("PATCH", true),
                Arguments.of("GET", false),
                Arguments.of("OPTIONS", false),
                Arguments.of("TRACE", false));
    }

    @ParameterizedTest(name = "{0} with body: {1}")
    @MethodSource("nonPostMethods")
    void everyMethodIsForwardedAsTheOnePost(String method, boolean withBody) {
        stubOk(CREATE_RESPONSE, "text/xml;charset=UTF-8");
        byte[] body = withBody ? message : null;
        Map<String, String> headers = withBody ? Map.of("Content-Type", SOAP11_CONTENT_TYPE) : Map.of();

        Response response = client.send(method, "/", headers, body);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.body()).isEqualTo(CREATE_RESPONSE);
        // The upstream's answer, not Spring MVC's own OPTIONS or TRACE handling.
        assertThat(response.headers("Allow")).isEmpty();
        assertThat(response.headers("Content-Type")).containsExactly("text/xml;charset=UTF-8");
        wm.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH))
                .withRequestBody(binaryEqualTo(withBody ? message : new byte[0])));
        assertThat(forwarded()).as("upstream calls").singleElement().satisfies(request -> {
            assertThat(request.getMethod().getName()).isEqualTo("POST");
            assertThat(request.getUrl()).isEqualTo(UPSTREAM_PATH);
            assertThat(request.getBody()).isEqualTo(withBody ? message : new byte[0]);
        });
    }

    @Test
    void upstreamResponseHeadersAreCopiedBackFiltered() {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/xml;charset=UTF-8")
                .withHeader("X-Upstream", "yes")
                .withHeader("MULE_ENCODING", "UTF-8")
                .withHeader("http.status", "200")
                .withBody(CREATE_RESPONSE)));

        Response response = client.send("POST", "/", Map.of("Content-Type", SOAP11_CONTENT_TYPE), message);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.headers("X-Upstream")).containsExactly("yes");
        assertThat(response.headers("Content-Type")).containsExactly("text/xml;charset=UTF-8");
        assertThat(response.headers()).extracting(Map.Entry::getKey)
                .noneSatisfy(name -> assertThat(name).isEqualToIgnoringCase("MULE_ENCODING"))
                .noneSatisfy(name -> assertThat(name).isEqualToIgnoringCase("http.status"));
        // The upstream framing is dropped and the listener writes its own, once.
        assertThat(response.headers("Content-Length")).containsExactly(String.valueOf(CREATE_RESPONSE.length));
        assertThat(response.headers("Transfer-Encoding")).isEmpty();
        assertThat(response.body()).isEqualTo(CREATE_RESPONSE);
    }

    /** The second {@code copy-headers} removes {@code http.status}, so the listener answers 200 below 400. */
    @ParameterizedTest
    @ValueSource(ints = {201, 202, 299})
    void upstreamSuccessStatusIsAnswered200(int upstreamStatus) {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(aResponse().withStatus(upstreamStatus)
                .withHeader("Content-Type", "text/xml;charset=UTF-8").withBody(CREATE_RESPONSE)));

        Response response = client.send("POST", "/", Map.of("Content-Type", SOAP11_CONTENT_TYPE), message);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.body()).isEqualTo(CREATE_RESPONSE);
    }

    @Test
    void gzipBodyPassesThroughUndecoded() throws IOException {
        byte[] compressed = gzip(CREATE_RESPONSE);
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/xml;charset=UTF-8")
                .withHeader("Content-Encoding", "gzip")
                .withBody(compressed)));

        Response response = client.send("POST", "/", Map.of("Content-Type", SOAP11_CONTENT_TYPE), message);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.headers("Content-Encoding")).containsExactly("gzip");
        assertThat(response.headers("Content-Length")).containsExactly(String.valueOf(compressed.length));
        assertThat(response.body()).isEqualTo(compressed);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
            assertThat(in.readAllBytes()).isEqualTo(CREATE_RESPONSE);
        }
        // The client asked for no compression on the caller's behalf.
        assertThat(headerValues(singleForwarded(), "Accept-Encoding")).isEmpty();
    }

    /**
     * Sent with the JDK client, which withholds the body until the listener's {@code 100 Continue}; ST-1 writes the
     * body at once.
     */
    @Test
    void expectContinueIsHonouredAndForwarded() throws IOException, InterruptedException {
        stubOk(CREATE_RESPONSE, "text/xml;charset=UTF-8");
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/"))
                .expectContinue(true)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", SOAP11_CONTENT_TYPE)
                .POST(BodyPublishers.ofByteArray(message))
                .build();

        HttpResponse<byte[]> response = http.send(request, BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(CREATE_RESPONSE);
        // SC-13 removes only Host, Content-Length, Connection, Transfer-Encoding, http.* and MULE_*, so the caller's
        // Expect travels on and keeps its meaning towards the upstream: the envelope still arrives whole. The
        // expectation token is case-insensitive, and the JDK client spells it 100-Continue.
        wm.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH))
                .withHeader("Expect", equalToIgnoreCase("100-continue"))
                .withRequestBody(binaryEqualTo(message)));
        assertThat(forwarded()).as("upstream calls").hasSize(1);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // SC-11: the upstream WSDL and its schema published at ?wsdl and ?xsd=, without an upstream call
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void wsdlAndSchemaArePublishedWithReferencesPointingAtTheProxy() throws IOException {
        Response wsdl = client.get("/?wsdl");

        assertThat(wsdl.statusLine()).as("%s", wsdl).isEqualTo("HTTP/1.1 200 OK");
        assertThat(wsdl.headers("Content-Type")).containsExactly("text/xml;charset=UTF-8");
        String wsdlText = wsdl.bodyAsString();
        assertThat(wsdlText)
                .contains("soap:address location=\"http://localhost:" + port + "/\"")
                .contains("targetNamespace=\"http://predic8.com/wsdl/shop/1/\"")
                .contains("<wsdl:service name=\"ShopService\">")
                .doesNotContain("www.predic8.com");

        Matcher schemaLocation = SCHEMA_LOCATION.matcher(wsdlText);
        assertThat(schemaLocation.find()).as("schemaLocation in %s", wsdlText).isTrue();
        String schemaUrl = schemaLocation.group(1);
        assertThat(schemaUrl).contains("?xsd=").isEqualTo("http://localhost:" + port + "/?xsd=ShopService.xsd");
        assertThat(schemaLocation.find()).as("a single schema reference").isFalse();

        URI schemaUri = URI.create(schemaUrl);
        Response schema = client.get(schemaUri.getRawPath() + "?" + schemaUri.getRawQuery());

        assertThat(schema.statusLine()).as("%s", schema).isEqualTo("HTTP/1.1 200 OK");
        assertMediaType(schema, MediaType.TEXT_XML);
        assertThat(schema.bodyAsString()).contains("createResponse");
        byte[] standIn = new ClassPathResource("upstream/ShopService.xsd").getContentAsByteArray();
        Diff diff = DiffBuilder.compare(Input.fromByteArray(schema.body()))
                .withTest(Input.fromByteArray(standIn))
                .ignoreComments()
                .ignoreWhitespace()
                .checkForSimilar()
                .build();
        assertThat(diff.hasDifferences()).as("served schema differs from the stand-in: %s", diff).isFalse();

        assertThat(forwarded()).as("upstream calls while publishing metadata").isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Errors: the flow declares no exception strategy, so every failure is SC-1's platform default
    // ---------------------------------------------------------------------------------------------------------------

    /** Upstream status, then its Content-Type and body. */
    private static Stream<Arguments> upstreamFailures() {
        return Stream.of(
                Arguments.of(500, "text/xml;charset=UTF-8", SOAP_FAULT),
                Arguments.of(404, "text/plain", "Not Found".getBytes(UTF_8)));
    }

    @ParameterizedTest(name = "upstream {0}")
    @MethodSource("upstreamFailures")
    void upstreamErrorStatusBecomes500Json(int upstreamStatus, String contentType, byte[] upstreamBody) {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(aResponse().withStatus(upstreamStatus)
                .withHeader("Content-Type", contentType)
                .withHeader("X-Upstream", "yes")
                .withBody(upstreamBody)));

        Response response = client.send("POST", "/", Map.of("Content-Type", SOAP11_CONTENT_TYPE), message);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertMediaType(response, MediaType.APPLICATION_JSON);
        String expected = "{ \"message\": \""
                + String.format(SoapProxyService.VALIDATOR_FAILURE_MESSAGE, upstreamStatus) + "\" }";
        assertThat(expected).isEqualTo("{ \"message\": \"Response code " + upstreamStatus
                + " mapped as failure. Message payload is of type: BufferInputStream\" }");
        assertThat(response.body()).isEqualTo(expected.getBytes(UTF_8));
        // The validator fails before the second copy-headers, so no upstream header reaches the caller.
        assertThat(response.headers("X-Upstream")).isEmpty();
        wm.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH)).withRequestBody(binaryEqualTo(message)));
        assertThat(forwarded()).as("upstream calls").hasSize(1);
    }

    @Test
    void upstreamTimeoutBecomes500WithoutRetry() {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/xml;charset=UTF-8")
                .withBody(CREATE_RESPONSE)
                .withFixedDelay(SLOW_UPSTREAM_DELAY_MS)));

        long started = System.nanoTime();
        Response response = client.send("POST", "/", Map.of("Content-Type", SOAP11_CONTENT_TYPE), message);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertMediaType(response, MediaType.APPLICATION_JSON);
        // The text is the transport's timeout message, which depends on the JDK and the client.
        assertThat(response.bodyAsString()).startsWith(ERROR_BODY_START).endsWith(" }").doesNotContain("cartId");
        assertThat(elapsedMs).as("elapsed until the %d ms response timeout fired", RESPONSE_TIMEOUT_MS)
                .isLessThan(SLOW_UPSTREAM_DELAY_MS - 500L);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(forwarded()).as("journaled upstream calls").isNotEmpty());
        // Past the stub's delay, so a silent retry would have been journaled by now.
        long remainingMs = Math.max(0, SLOW_UPSTREAM_DELAY_MS + 500L - elapsedMs);
        await().pollDelay(Duration.ofMillis(remainingMs)).atMost(Duration.ofMillis(remainingMs + 5_000))
                .untilAsserted(() -> wm.verify(1, postRequestedFor(urlEqualTo(UPSTREAM_PATH))));
        assertThat(forwarded()).as("upstream calls").hasSize(1);
    }

    /** Method and path: the listener serves {@code /} only, so neither a sub-path nor the upstream path is proxied. */
    private static Stream<Arguments> unservedPaths() {
        return Stream.of(
                Arguments.of("GET", "/other"),
                Arguments.of("POST", "/other"),
                Arguments.of("POST", UPSTREAM_PATH),
                Arguments.of("GET", "/x/"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("unservedPaths")
    void unservedPathIsTheNoListener404(String method, String path) {
        stubOk(CREATE_RESPONSE, "text/xml;charset=UTF-8");
        byte[] body = "POST".equals(method) ? message : null;
        Map<String, String> headers = body == null ? Map.of() : Map.of("Content-Type", SOAP11_CONTENT_TYPE);

        Response response = client.send(method, path, headers, body);

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 404 Not Found");
        assertMediaType(response, MediaType.TEXT_PLAIN);
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: " + path);
        assertThat(forwarded()).as("upstream calls").isEmpty();
    }

    /** The upstream address points at a closed local port, so every post fails to connect. */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "proxy.wsdl-location=" + SoapProxyControllerTest.WSDL_LOCATION,
                    "proxy.response-timeout-ms=" + SoapProxyControllerTest.RESPONSE_TIMEOUT_MS})
    class UnreachableUpstream {

        private static final int CLOSED_PORT = closedPort();

        @LocalServerPort
        int proxyPort;

        @DynamicPropertySource
        static void unreachableUpstream(DynamicPropertyRegistry registry) {
            registry.add("proxy.upstream-url", () -> "http://localhost:" + CLOSED_PORT + UPSTREAM_PATH);
        }

        @Test
        void connectFailureBecomes500Json() {
            Response response = HttpTestClient.localhost(proxyPort)
                    .send("POST", "/", Map.of("Content-Type", SOAP11_CONTENT_TYPE), message);

            assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 500 Internal Server Error");
            assertMediaType(response, MediaType.APPLICATION_JSON);
            // The text is the transport's connect-failure message, which depends on the JDK and the client.
            assertThat(response.bodyAsString()).startsWith(ERROR_BODY_START).endsWith("\" }");
            assertThat(forwarded()).as("calls reaching the WireMock upstream").isEmpty();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static void stubOk(byte[] body, String contentType) {
        wm.stubFor(post(urlEqualTo(UPSTREAM_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", contentType)
                .withBody(body)));
    }

    /** Every request the upstream journaled in this test, in arrival order. */
    private static List<LoggedRequest> forwarded() {
        return wm.findAll(anyRequestedFor(anyUrl()));
    }

    /** The one request the upstream received in this test. */
    private static LoggedRequest singleForwarded() {
        List<LoggedRequest> requests = forwarded();
        assertThat(requests).as("upstream calls").hasSize(1);
        return requests.get(0);
    }

    /** Every value of the header {@code name} (matched ignoring case) the upstream received, in order. */
    private static List<String> headerValues(LoggedRequest request, String name) {
        HttpHeader header = request.getHeaders().getHeader(name);
        return header.isPresent() ? header.values() : List.of();
    }

    /** The response's single {@code Content-Type} has the type and subtype of {@code expected}, parameters aside. */
    private static void assertMediaType(Response response, MediaType expected) {
        assertThat(response.headers("Content-Type")).as("Content-Type of %s", response).hasSize(1);
        MediaType actual = MediaType.parseMediaType(response.header("Content-Type"));
        assertThat(actual.equalsTypeAndSubtype(expected)).as("%s is %s", actual, expected).isTrue();
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        }
        return out.toByteArray();
    }

    /** A local port that was free a moment ago and is closed now. */
    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
