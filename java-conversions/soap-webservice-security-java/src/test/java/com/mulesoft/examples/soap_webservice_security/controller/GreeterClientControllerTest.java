/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.oxm.UnmarshallingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.ws.client.WebServiceIOException;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.mulesoft.examples.soap_webservice_security.SoapWebserviceSecurityApplication;
import com.mulesoft.examples.soap_webservice_security.client.SecureGreeterClient;
import com.mulesoft.examples.soap_webservice_security.support.HttpTestClient;
import com.mulesoft.examples.soap_webservice_security.support.HttpTestClient.Response;
import com.mulesoft.examples.soap_webservice_security.support.PortInUseRetryingContextLoader;

/**
 * Wire-level controller test of {@link GreeterClientController}, the HTTP front of flow {@code SecurityClients}
 * (soap-webservice-security/src/main/app/service-clients.xml:6-40): an {@code http:listener} at path {@code client}
 * with no {@code allowedMethods}, whose payload is query {@code name}, whose choice on query {@code clientType} runs
 * one of six WS-Security client sub-flows or answers {@code Client type is not supported}, which sets
 * {@code Content-Type: text/plain}, and whose {@code catch-exception-strategy} answers
 * {@code There has been an Error processing the request} as {@code text/plain}.
 *
 * <p>The whole application runs on Undertow: the SOAP listener on a random port stands in for the original's fixed
 * services port, and the additional {@code client} listener of {@code config/ListenerPorts} on a port drawn for each
 * load attempt of the context, and read back from it, stands in for the client front's. Every response is read off
 * the socket by ST-1 {@link HttpTestClient}, so the raw status line, the headers and the body bytes are asserted as
 * sent. {@link SecureGreeterClient} is a Spring spy: unless a test stubs it, each client type really calls its Greeter
 * path on the SOAP listener, through that path's WS-Security enforcement, with the demo credentials of profile
 * {@code test}. Replies the Greeter endpoint never sends come from a WireMock stand-in on a dynamic port, at which a
 * test points the unsecure client's endpoint.
 *
 * <p>Covered: the six {@code when} branches end to end, the {@code otherwise} branch (unknown, case-variant, empty and
 * missing type) without any SOAP call, the catch branch for a stubbed failure, a real transport failure, a real SOAP
 * security fault and a real 200 reply without a {@code greetResponse} (no entity, an empty SOAP Body, a payload the
 * Greeter marshaller binds to no class, a {@code greet} element), the {@code text/plain} setter on every branch,
 * every HTTP method (extension tokens such as {@code PROPFIND} included), CORS pre-flights answered exactly as plain
 * {@code OPTIONS} requests without any {@code Access-Control-*} or {@code Vary} header, the query-string-only binding
 * (last value wins, UTF-8 decoding, a missing name greeting {@code null}), and the SC-10 no-listener 404 for the
 * client path on the SOAP listener and for the SOAP paths on the client listener.
 */
// The SOAP listener runs on RANDOM_PORT (@LocalServerPort) rather than a pre-allocated DEFINED_PORT: the conversion
// maps the originals' fixed ports to RANDOM_PORT plus @DynamicPropertySource, as SoapWebserviceSecurityTest does, and a
// random port leaves no window between choosing and binding it. Only the additional client listener, which must have
// a concrete port, gets a drawn one, drawn for each load attempt; PortInUseRetryingContextLoader loads the context
// again when another process takes that port before the listener binds it.
@SpringBootTest(classes = SoapWebserviceSecurityApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@ActiveProfiles("test")
class GreeterClientControllerTest {

    private static final String NAME = "John";

    private static final String UNSUPPORTED = "Client type is not supported";

    private static final String ERROR = "There has been an Error processing the request";

    private static final String NO_LISTENER_CONTENT_TYPE = "text/plain";

    /** {@code Origin} of the CORS pre-flights. */
    private static final String PREFLIGHT_ORIGIN = "https://example.invalid";

    /** Prefix of the CORS response headers, compared ignoring case. */
    private static final String ACCESS_CONTROL_PREFIX = "Access-Control-";

    /** The request the original README sends to the client front: {@code /client?clientType=unsecure&name=John}. */
    private static final String UNSECURE_JOHN = "/client?clientType=unsecure&name=" + NAME;

    /** The Greeter path of the unsecure client. */
    private static final String UNSECURE_SERVICE_PATH = "/services/unsecure";

    /** Namespace of the Greeter's {@code greet} and {@code greetResponse} elements. */
    private static final String GREETER_NS = "http://security.example.mule.mulesoft.com/";

    /**
     * A WireMock stand-in for the Greeter service, on a dynamic port, for replies the Greeter endpoint never sends,
     * started and stopped by this class's own {@code @BeforeAll} and {@code @AfterAll}. The application context never
     * reads its port: a test points the spied client's endpoint at it.
     */
    private static final WireMockServer GREETER_STUB = new WireMockServer(wireMockConfig().dynamicPort());

    /** The sub-flow method each {@code when} branch of the choice runs, called with {@link #NAME}. */
    private static final Map<String, Consumer<SecureGreeterClient>> SUB_FLOWS = Map.of(
            "unsecure", client -> client.unsecure(NAME),
            "usernameToken", client -> client.usernameToken(NAME),
            "usernameTokenSigned", client -> client.usernameTokenSigned(NAME),
            "usernameTokenEncrypted", client -> client.usernameTokenEncrypted(NAME),
            "samlToken", client -> client.samlToken(NAME),
            "samlTokenSigned", client -> client.samlTokenSigned(NAME));

    @LocalServerPort
    private int soapPort;

    /** Port of the {@code /client} listener, as the loaded context bound it. */
    @Value("${ports.client}")
    private int clientPort;

    @SpyBean
    private SecureGreeterClient secureGreeterClient;

    /** Spring calls this for every load attempt of the context, so each attempt binds a newly drawn client port. */
    @DynamicPropertySource
    static void listeners(DynamicPropertyRegistry registry) {
        int drawnClientPort = TestSocketUtils.findAvailableTcpPort();
        // listeners.additional[0].port reads ${ports.client}, so the scalar is overridden and the list keeps its
        // name, host and /client route from application.yml (a list binds from one property source only).
        registry.add("ports.client", () -> drawnClientPort);
        // The outbound Greeter calls go to the SOAP listener on localhost instead of the original 0.0.0.0.
        registry.add("soap-security.client.host", () -> "localhost");
    }

    @BeforeAll
    static void startGreeterStub() {
        GREETER_STUB.start();
    }

    @AfterAll
    static void stopGreeterStub() {
        GREETER_STUB.stop();
    }

    /** Forgets the previous test's stubs and journaled requests. */
    @BeforeEach
    void resetGreeterStub() {
        GREETER_STUB.resetAll();
    }

    @AfterEach
    void resetSpy() {
        Mockito.reset(secureGreeterClient);
    }

    @ParameterizedTest(name = "clientType={0}")
    @ValueSource(strings = {"unsecure", "usernameToken", "usernameTokenSigned", "usernameTokenEncrypted",
            "samlToken", "samlTokenSigned"})
    void everyClientTypeGreetsThroughItsSecuredGreeterPath(String clientType) {
        Response response = clientPort().get("/client?clientType=" + clientType + "&name=" + NAME);

        assertTextReply(response, "Hello " + NAME);
        verify(secureGreeterClient).invoke(clientType, NAME);
        SUB_FLOWS.get(clientType).accept(verify(secureGreeterClient));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedClientTypes")
    void unsupportedClientTypeAnswersTheOtherwisePayloadWithoutAnySoapCall(String query, String clientType) {
        Response response = clientPort().get("/client?" + query);

        assertTextReply(response, UNSUPPORTED);
        verify(secureGreeterClient).invoke(clientType, NAME);
        verifyNoMoreInteractions(secureGreeterClient);
    }

    static Stream<Arguments> unsupportedClientTypes() {
        return Stream.of(
                Arguments.of("clientType=foo&name=John", "foo"),
                Arguments.of("clientType=UsernameToken&name=John", "UsernameToken"),   // the choice is case-sensitive
                Arguments.of("clientType=&name=John", ""),
                Arguments.of("clientType&name=John", null),                          // a pair without '='
                Arguments.of("name=John", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientFailures")
    void clientFailureIsAnsweredWithTheCatchStrategyPayload(RuntimeException failure) {
        doThrow(failure).when(secureGreeterClient).invoke(any(), any());

        Response response = clientPort().get(UNSECURE_JOHN);

        assertTextReply(response, ERROR);
        verify(secureGreeterClient).invoke("unsecure", NAME);
    }

    static Stream<RuntimeException> clientFailures() {
        return Stream.of(new WebServiceIOException("I/O error: Connection refused"),
                new IllegalStateException("keystore password not configured"));
    }

    @Test
    void unreachableGreeterServiceIsAnsweredWithTheCatchStrategyPayload() throws IOException {
        // A port held by a socket that never listens: the real unsecure client fails with a transport error.
        try (Socket reserved = reserveRefusingPort()) {
            URI closed = URI.create("http://localhost:" + reserved.getLocalPort() + "/services/unsecure");
            doReturn(closed).when(secureGreeterClient).endpoint("unsecure");

            Response response = clientPort().get(UNSECURE_JOHN);

            assertTextReply(response, ERROR);
            verify(secureGreeterClient).unsecure(NAME);
        }
    }

    @Test
    void securityFaultOfTheGreeterServiceIsAnsweredWithTheCatchStrategyPayload() {
        // The unsecure client sends no security header; the username path rejects it with a WS-Security SOAP fault,
        // whose text must not reach the caller.
        doReturn(soapUri("username")).when(secureGreeterClient).endpoint("unsecure");

        Response response = clientPort().get(UNSECURE_JOHN);

        assertTextReply(response, ERROR);
        verify(secureGreeterClient).unsecure(NAME);
    }

    /**
     * The real unsecure client calls the {@link #GREETER_STUB} stand-in, which answers 200 without a
     * {@code greetResponse}; the client's failure for that reply, raised after one request, reaches the catch branch.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("greeterRepliesWithoutGreetResponse")
    void greeterReplyWithoutGreetResponseIsAnsweredWithTheCatchStrategyPayload(String reply,
            ResponseDefinitionBuilder greeterReply, Class<? extends RuntimeException> expectedFailure) {
        GREETER_STUB.stubFor(post(urlPathEqualTo(UNSECURE_SERVICE_PATH)).willReturn(greeterReply));
        doReturn(URI.create(GREETER_STUB.baseUrl() + UNSECURE_SERVICE_PATH))
                .when(secureGreeterClient).endpoint("unsecure");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        doAnswer(invocation -> {
            try {
                return invocation.callRealMethod();
            } catch (Throwable thrown) {
                failure.set(thrown);
                throw thrown;
            }
        }).when(secureGreeterClient).unsecure(NAME);

        Response response = clientPort().get(UNSECURE_JOHN);

        assertTextReply(response, ERROR);
        assertNotNull(failure.get(), response::toString);
        assertEquals(expectedFailure, failure.get().getClass(), failure.get()::toString);
        verify(secureGreeterClient).unsecure(NAME);
        GREETER_STUB.verify(1, postRequestedFor(urlPathEqualTo(UNSECURE_SERVICE_PATH)));
        GREETER_STUB.verify(1, anyRequestedFor(anyUrl()));
    }

    static Stream<Arguments> greeterRepliesWithoutGreetResponse() {
        return Stream.of(
                // The explicit Content-Length keeps WireMock from answering the client's Accept-Encoding: gzip with
                // a gzip entity of an empty document, which is not a reply without an entity.
                Arguments.of("no entity, Content-Length 0",
                        aResponse().withStatus(200).withHeader(HttpHeaders.CONTENT_LENGTH, "0"),
                        IllegalStateException.class),
                Arguments.of("empty SOAP Body", soapReply(""), IllegalStateException.class),
                Arguments.of("payload the Greeter marshaller binds to no class",
                        soapReply("<x:other xmlns:x=\"urn:other\"/>"), UnmarshallingFailureException.class),
                Arguments.of("greet element", soapReply("<ns2:greet xmlns:ns2=\"" + GREETER_NS + "\"><name>" + NAME
                        + "</name></ns2:greet>"), IllegalStateException.class));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void everyMethodWithAnEmptyBodyRunsTheFlowLikeGet(String method) {
        Response response = clientPort().send(method, UNSECURE_JOHN, null, new byte[0]);

        assertTextReply(response, "Hello " + NAME);
        verify(secureGreeterClient).invoke("unsecure", NAME);
    }

    /**
     * The listener declares no {@code allowedMethods}, so {@code OPTIONS}, {@code TRACE} and the extension methods
     * {@code PROPFIND} and {@code MKCOL} run the flow instead of getting Spring MVC's {@code Allow} reply, its
     * {@code message/http} echo or the method 405.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"OPTIONS", "TRACE", "PROPFIND", "MKCOL"})
    void optionsTraceAndExtensionMethodsRunTheFlowInsteadOfAPlatformAnswer(String method) {
        Response response = clientPort().send(method, UNSECURE_JOHN, null, null);

        assertTextReply(response, "Hello " + NAME);
        assertNull(response.header(HttpHeaders.ALLOW), response::toString);
        verify(secureGreeterClient).invoke("unsecure", NAME);
    }

    /**
     * The Mule listener applied no CORS policy, so a CORS pre-flight, whatever method it asks for, runs the flow and
     * is answered exactly as a plain {@code OPTIONS} request to {@code /client}: not with Spring's 403
     * {@code Invalid CORS request}, and without any {@code Access-Control-*} or {@code Vary} header.
     */
    @ParameterizedTest(name = "Access-Control-Request-Method {0}")
    @ValueSource(strings = {"GET", "POST", "PROPFIND"})
    void corsPreflightRunsTheFlowLikeAPlainOptionsRequest(String requestedMethod) {
        Response plain = clientPort().send("OPTIONS", UNSECURE_JOHN, null, null);
        Response preflight = clientPort().send("OPTIONS", UNSECURE_JOHN, preflightHeaders(requestedMethod), null);

        assertSameReply(plain, preflight);
        assertTextReply(preflight, "Hello " + NAME);
        assertNull(preflight.header(HttpHeaders.ALLOW), preflight::toString);
        assertNoCorsHeaders(preflight);
        verify(secureGreeterClient, times(2)).invoke("unsecure", NAME);
    }

    /** A CORS pre-flight to a path a listener does not serve gets the no-listener 404, as plain OPTIONS does. */
    @ParameterizedTest(name = "{0} listener, {1}")
    @CsvSource({"client, /other", "client, /", "soap, /client"})
    void corsPreflightToAnUnservedPathAnswersTheNoListener404LikeAPlainOptionsRequest(String listener, String path) {
        HttpTestClient http = "soap".equals(listener) ? soapPort() : clientPort();

        Response plain = http.send("OPTIONS", path, null, null);
        Response preflight = http.send("OPTIONS", path, preflightHeaders("GET"), null);

        assertSameReply(plain, preflight);
        assertNoListener(preflight, path);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(secureGreeterClient);
    }

    @Test
    void headRunsTheFlowAndAnswersTheHeadersWithoutABody() {
        Response response = clientPort().send("HEAD", UNSECURE_JOHN, null, null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertTextPlain(response);
        assertEquals(0, response.body().length, response::toString);
        verify(secureGreeterClient).invoke("unsecure", NAME);
    }

    @Test
    void formBodyNeverReplacesTheQueryParameters() {
        Response response = clientPort().post(UNSECURE_JOHN, MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                "clientType=foo&name=Mallory");

        assertTextReply(response, "Hello " + NAME);
        verify(secureGreeterClient).invoke("unsecure", NAME);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "clientType=unsecure&name=A&name=B         | B          | Hello B",
            "clientType=unsecure&name=J%C3%BCrgen      | J\u00fcrgen | Hello J\u00fcrgen",
            "clientType=unsecure&name=John+Doe         | John Doe   | Hello John Doe",
            "name=John&clientType=unsecure             | John       | Hello John",
            "clientType=unsecure                       | NULL       | Hello null"})
    void queryParametersAreReadAsTheMuleListenerDecodedThem(String query, String name, String greeting) {
        Response response = clientPort().get("/client?" + query);

        assertTextReply(response, greeting);
        verify(secureGreeterClient).invoke("unsecure", name);
    }

    @Test
    void repeatedClientTypeSelectsItsLastValue() {
        Response response = clientPort().get("/client?clientType=unsecure&clientType=foo&name=John");

        assertTextReply(response, UNSUPPORTED);
        verify(secureGreeterClient).invoke("foo", NAME);
        verifyNoMoreInteractions(secureGreeterClient);
    }

    @Test
    void clientPathIsNotServedOnTheSoapListener() {
        Response response = soapPort().get(UNSECURE_JOHN);

        assertNoListener(response, "/client");
        verifyNoInteractions(secureGreeterClient);
    }

    @Test
    void greeterPathIsNotServedOnTheClientListener() {
        Response response = clientPort().post("/services/unsecure", MediaType.TEXT_XML_VALUE,
                "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                        + "<ns2:greet xmlns:ns2=\"http://security.example.mule.mulesoft.com/\"><name>John</name>"
                        + "</ns2:greet></soap:Body></soap:Envelope>");

        assertNoListener(response, "/services/unsecure");
        verifyNoInteractions(secureGreeterClient);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET, /services/username?wsdl, /services/username",
            "GET, /client/greet?clientType=unsecure&name=John, /client/greet",
            "GET, /, /"})
    void otherPathsAreNotServedOnTheClientListener(String method, String target, String path) {
        Response response = clientPort().send(method, target, null, null);

        assertNoListener(response, path);
        verifyNoInteractions(secureGreeterClient);
    }

    private HttpTestClient clientPort() {
        return HttpTestClient.localhost(clientPort);
    }

    private HttpTestClient soapPort() {
        return HttpTestClient.localhost(soapPort);
    }

    private URI soapUri(String path) {
        return URI.create("http://localhost:" + soapPort + "/services/" + path);
    }

    /** 200 with a SOAP 1.1 envelope whose Body holds {@code bodyContent}, empty included. */
    private static ResponseDefinitionBuilder soapReply(String bodyContent) {
        return aResponse()
                .withStatus(200)
                .withHeader(HttpHeaders.CONTENT_TYPE, "text/xml;charset=UTF-8")
                .withBody("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                        + bodyContent + "</soap:Body></soap:Envelope>");
    }

    /** 200 OK, {@code text/plain}, and the body text {@code expected}. */
    private static void assertTextReply(Response response, String expected) {
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertTextPlain(response);
        assertEquals(expected, text(response), response::toString);
    }

    /** The {@code set-property Content-Type text/plain} of both the flow and its catch strategy. */
    private static void assertTextPlain(Response response) {
        String contentType = response.header(HttpHeaders.CONTENT_TYPE);
        assertNotNull(contentType, response::toString);
        assertTrue(MediaType.TEXT_PLAIN.equalsTypeAndSubtype(MediaType.parseMediaType(contentType)),
                response::toString);
    }

    /** The SC-10 answer for a path the receiving port's original listener did not serve. */
    private static void assertNoListener(Response response, String path) {
        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertEquals(NO_LISTENER_CONTENT_TYPE, response.header(HttpHeaders.CONTENT_TYPE), response::toString);
        assertEquals("No listener for endpoint: " + path, text(response), response::toString);
    }

    /** The headers that make an {@code OPTIONS} request a CORS pre-flight asking for {@code requestedMethod}. */
    private static Map<String, String> preflightHeaders(String requestedMethod) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HttpHeaders.ORIGIN, PREFLIGHT_ORIGIN);
        headers.put(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, requestedMethod);
        return headers;
    }

    /** Same status line, headers in the same order (the {@code Date} header aside) and the same body bytes. */
    private static void assertSameReply(Response expected, Response actual) {
        assertEquals(expected.statusLine(), actual.statusLine(), actual::toString);
        assertEquals(withoutDate(expected.headers()), withoutDate(actual.headers()), actual::toString);
        assertArrayEquals(expected.body(), actual.body(), actual::toString);
    }

    private static List<Map.Entry<String, String>> withoutDate(List<Map.Entry<String, String>> headers) {
        return headers.stream().filter(header -> !HttpHeaders.DATE.equalsIgnoreCase(header.getKey())).toList();
    }

    /** No CORS processing took part: no {@code Access-Control-*} header and no {@code Vary} header. */
    private static void assertNoCorsHeaders(Response response) {
        assertFalse(response.headers().stream().anyMatch(header -> header.getKey()
                        .regionMatches(true, 0, ACCESS_CONTROL_PREFIX, 0, ACCESS_CONTROL_PREFIX.length())),
                response::toString);
        assertNull(response.header(HttpHeaders.VARY), response::toString);
    }

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

    /** The body decoded with the charset its {@code Content-Type} declares, UTF-8 when it declares none. */
    private static String text(Response response) {
        String contentType = response.header(HttpHeaders.CONTENT_TYPE);
        Charset charset = contentType == null ? null : MediaType.parseMediaType(contentType).getCharset();
        return new String(response.body(), charset == null ? UTF_8 : charset);
    }
}
