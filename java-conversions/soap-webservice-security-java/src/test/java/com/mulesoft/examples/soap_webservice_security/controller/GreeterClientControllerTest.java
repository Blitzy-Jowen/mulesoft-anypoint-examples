/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.ws.client.WebServiceIOException;

import com.mulesoft.examples.soap_webservice_security.SoapWebserviceSecurityApplication;
import com.mulesoft.examples.soap_webservice_security.client.SecureGreeterClient;
import com.mulesoft.examples.soap_webservice_security.support.HttpTestClient;
import com.mulesoft.examples.soap_webservice_security.support.HttpTestClient.Response;

/**
 * Wire-level controller test of {@link GreeterClientController}, the HTTP front of flow {@code SecurityClients}
 * (soap-webservice-security/src/main/app/service-clients.xml:6-40): an {@code http:listener} at path {@code client}
 * with no {@code allowedMethods}, whose payload is query {@code name}, whose choice on query {@code clientType} runs
 * one of six WS-Security client sub-flows or answers {@code Client type is not supported}, which sets
 * {@code Content-Type: text/plain}, and whose {@code catch-exception-strategy} answers
 * {@code There has been an Error processing the request} as {@code text/plain}.
 *
 * <p>The whole application runs on Undertow: the SOAP listener on a random port stands in for the original's fixed
 * services port, and the additional {@code client} listener of {@code config/ListenerPorts} on a port drawn once per
 * class stands in for the client front's. Every response is read off the socket by ST-1 {@link HttpTestClient}, so
 * the raw status line, the headers and the body bytes are asserted as sent. {@link SecureGreeterClient} is a Spring
 * spy: unless a test stubs it, each client type really calls its Greeter path on the SOAP listener, through that
 * path's WS-Security enforcement, with the demo credentials of profile {@code test}.
 *
 * <p>Covered: the six {@code when} branches end to end, the {@code otherwise} branch (unknown, case-variant, empty and
 * missing type) without any SOAP call, the catch branch for a stubbed failure, a real transport failure and a real
 * SOAP security fault, the {@code text/plain} setter on every branch, every HTTP method, the query-string-only
 * binding (last value wins, UTF-8 decoding, a missing name greeting {@code null}), and the SC-10 no-listener 404 for
 * the client path on the SOAP listener and for the SOAP paths on the client listener.
 */
// The SOAP listener runs on RANDOM_PORT (@LocalServerPort) rather than a pre-allocated DEFINED_PORT: the conversion
// maps the originals' fixed ports to RANDOM_PORT plus @DynamicPropertySource, as SoapWebserviceSecurityTest does, and a
// random port leaves no window between choosing and binding it. Only the additional client listener, which must have
// a concrete port, gets a drawn one.
@SpringBootTest(classes = SoapWebserviceSecurityApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GreeterClientControllerTest {

    private static final String NAME = "John";

    private static final String UNSUPPORTED = "Client type is not supported";

    private static final String ERROR = "There has been an Error processing the request";

    private static final String NO_LISTENER_CONTENT_TYPE = "text/plain;charset=UTF-8";

    /** The request the original README sends to the client front: {@code /client?clientType=unsecure&name=John}. */
    private static final String UNSECURE_JOHN = "/client?clientType=unsecure&name=" + NAME;

    /**
     * Port of the {@code /client} listener, drawn once for the class so a cached context and its bound listener keep
     * agreeing on it.
     */
    private static final int CLIENT_PORT = TestSocketUtils.findAvailableTcpPort();

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

    @SpyBean
    private SecureGreeterClient secureGreeterClient;

    @DynamicPropertySource
    static void listeners(DynamicPropertyRegistry registry) {
        // listeners.additional[0].port reads ${ports.client}, so the scalar is overridden and the list keeps its
        // name, host and /client route from application.yml (a list binds from one property source only).
        registry.add("ports.client", () -> CLIENT_PORT);
        // The outbound Greeter calls go to the SOAP listener on localhost instead of the original 0.0.0.0.
        registry.add("soap-security.client.host", () -> "localhost");
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
    void unreachableGreeterServiceIsAnsweredWithTheCatchStrategyPayload() {
        // A port drawn as free and never bound: the real unsecure client fails with a transport error.
        URI closed = URI.create("http://localhost:" + TestSocketUtils.findAvailableTcpPort() + "/services/unsecure");
        doReturn(closed).when(secureGreeterClient).endpoint("unsecure");

        Response response = clientPort().get(UNSECURE_JOHN);

        assertTextReply(response, ERROR);
        verify(secureGreeterClient).unsecure(NAME);
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void everyMethodWithAnEmptyBodyRunsTheFlowLikeGet(String method) {
        Response response = clientPort().send(method, UNSECURE_JOHN, null, new byte[0]);

        assertTextReply(response, "Hello " + NAME);
        verify(secureGreeterClient).invoke("unsecure", NAME);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"OPTIONS", "TRACE"})
    void optionsAndTraceRunTheFlowInsteadOfAPlatformAnswer(String method) {
        Response response = clientPort().send(method, UNSECURE_JOHN, null, null);

        assertTextReply(response, "Hello " + NAME);
        verify(secureGreeterClient).invoke("unsecure", NAME);
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

    private static HttpTestClient clientPort() {
        return HttpTestClient.localhost(CLIENT_PORT);
    }

    private HttpTestClient soapPort() {
        return HttpTestClient.localhost(soapPort);
    }

    private URI soapUri(String path) {
        return URI.create("http://localhost:" + soapPort + "/services/" + path);
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

    /** The body decoded with the charset its {@code Content-Type} declares, UTF-8 when it declares none. */
    private static String text(Response response) {
        String contentType = response.header(HttpHeaders.CONTENT_TYPE);
        Charset charset = contentType == null ? null : MediaType.parseMediaType(contentType).getCharset();
        return new String(response.body(), charset == null ? UTF_8 : charset);
    }
}
