/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.login_form_using_the_http_connector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import com.mulesoft.examples.login_form_using_the_http_connector.config.LoginProperties;

/**
 * The outbound contract of {@link LoginRequesterClient}, the replacement for the {@code http:request} of flow
 * {@code CallLoginFlowUsingRequester}, against a WireMock stand-in for the application's own {@code /login}
 * listener: the form map is posted to {@code /login} as {@code application/x-www-form-urlencoded} with UTF-8
 * URL-encoded pairs in the map's iteration order; a response below 400 is relayed with its status, raw
 * Content-Type and body bytes unchanged; a status of 400 or more fails as Mule's default success validator did;
 * and neither a failing status nor a connectivity failure is ever retried.
 *
 * <p>The end-to-end path through {@code /requesterLogin} is covered by {@code HttpLoginHtmlFormTest}; this class
 * isolates the adapter, so it starts no Spring context: the request tests build the client on a fixed base URL,
 * and the port-discovery tests feed the application's constructor mocked web-server start-up events.
 */
class LoginRequesterClientTest {

    private static final String LOGIN_PATH = "/login";

    private static final String FORM_URLENCODED = "application/x-www-form-urlencoded";

    private static final String HTML_UTF8 = "text/html; charset=UTF-8";

    private WireMockServer wireMock;

    private LoginRequesterClient client;

    @BeforeEach
    void startUpstream() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        client = new LoginRequesterClient(RestClient.builder(), "http://localhost:" + wireMock.port());
    }

    @AfterEach
    void stopUpstream() {
        if (wireMock != null && wireMock.isRunning()) {
            wireMock.stop();
        }
    }

    /** The form map the original flow builds before its request (xml:38-40), passed here as test input. */
    private static Map<String, String> form() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", "mule");
        form.put("password", "mule");
        return form;
    }

    @Test
    void postsFormUrlEncodedLoginRequest() {
        wireMock.stubFor(post(urlEqualTo(LOGIN_PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", HTML_UTF8).withBody("ok")));

        client.postLogin(form());

        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(LOGIN_PATH))
                .withHeader("Content-Type", equalTo(FORM_URLENCODED))
                .withRequestBody(equalTo("username=mule&password=mule")));
        List<LoggedRequest> journal = wireMock.findAll(anyRequestedFor(anyUrl()));
        assertEquals(1, journal.size());
        LoggedRequest request = journal.get(0);
        assertEquals(RequestMethod.POST, request.getMethod());
        assertEquals(LOGIN_PATH, request.getUrl());
        assertFalse(request.containsHeader("Authorization"), "no credentials travel outside the form body");
    }

    @Test
    void encodesFormValuesAsUtf8() {
        wireMock.stubFor(post(urlEqualTo(LOGIN_PATH)).willReturn(aResponse().withStatus(200)));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", "mü le&x=y");
        form.put("password", "p@ss/é");

        client.postLogin(form);

        String body = wireMock.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertTrue(body.contains("%C3%BC"), () -> "ü must be UTF-8 percent-encoded in " + body);
        assertTrue(body.contains("%C3%A9"), () -> "é must be UTF-8 percent-encoded in " + body);
        List<Map.Entry<String, String>> decoded = new ArrayList<>();
        for (String pair : body.split("&")) {
            String[] nameAndValue = pair.split("=", 2);
            assertEquals(2, nameAndValue.length, () -> "pair without '=' in " + body);
            decoded.add(Map.entry(URLDecoder.decode(nameAndValue[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(nameAndValue[1], StandardCharsets.UTF_8)));
        }
        assertEquals(List.copyOf(form.entrySet()), decoded);
    }

    static Stream<Arguments> exactFormBodies() {
        Map<String, String> vector = new LinkedHashMap<>();
        vector.put("user name", "a&b");
        vector.put("pass", "é=~*");
        vector.put("empty", null);
        Map<String, String> hashMapForm = new HashMap<>();
        hashMapForm.put("username", "mule");
        hashMapForm.put("password", "mule");
        return Stream.of(
                // Mule 3.8.0 HttpParser.encodeString writes a null value as the bare name, without '='.
                Arguments.of("encoding vector", vector, "user+name=a%26b&pass=%C3%A9%3D%7E*&empty"),
                // HashMap iteration order, which LoginService relies on to match the original wire order.
                Arguments.of("HashMap order", hashMapForm, "password=mule&username=mule"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exactFormBodies")
    void writesExactFormBody(String description, Map<String, String> form, String expectedBody) {
        wireMock.stubFor(post(urlEqualTo(LOGIN_PATH)).willReturn(aResponse().withStatus(200)));

        client.postLogin(form);

        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(LOGIN_PATH))
                .withHeader("Content-Type", equalTo(FORM_URLENCODED)));
        assertEquals(expectedBody, wireMock.getAllServeEvents().get(0).getRequest().getBodyAsString());
    }

    @Test
    void relaysStatusContentTypeAndBodyBytesUnchanged() {
        byte[] bytes = "<html>User mule has been logged successfully! é</html>\n".getBytes(StandardCharsets.UTF_8);
        wireMock.stubFor(post(urlEqualTo(LOGIN_PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", HTML_UTF8).withBody(bytes)));

        LoginRequesterClient.LoginResponse response = client.postLogin(form());

        assertEquals(200, response.status());
        assertEquals(HTML_UTF8, response.contentType());
        assertArrayEquals(bytes, response.body());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 500})
    void failureStatusIsMappedToIllegalStateException(int status) {
        wireMock.stubFor(post(urlEqualTo(LOGIN_PATH)).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", HTML_UTF8)
                .withBody("<html>Login failed. Please check your credentials.</html>")));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> client.postLogin(form()));

        assertEquals("Response code " + status + " mapped as failure.", failure.getMessage());
        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(LOGIN_PATH)));
    }

    @Test
    void connectionRefusedPropagatesWithoutRetry() {
        int port = wireMock.port();
        wireMock.stop();

        Exception failure = assertThrows(Exception.class, () -> client.postLogin(form()));

        assertTrue(hasCause(failure, ConnectException.class),
                () -> "expected a ConnectException in the cause chain of " + failure);
        assertFalse(failure instanceof IllegalStateException
                && String.valueOf(failure.getMessage()).endsWith("mapped as failure."),
                "a refused connection is not a status failure");
        // A listener opened on the same port only after the call must never see a late second attempt.
        wireMock = new WireMockServer(WireMockConfiguration.options().port(port));
        wireMock.start();
        assertTrue(wireMock.getAllServeEvents().isEmpty(), "no retry may follow a refused connection");
    }

    @Test
    void connectivityFaultIsNotRetried() {
        wireMock.stubFor(post(urlEqualTo(LOGIN_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        Exception failure = assertThrows(Exception.class, () -> client.postLogin(form()));

        assertFalse(failure instanceof IllegalStateException
                && String.valueOf(failure.getMessage()).endsWith("mapped as failure."),
                "a reset connection is not a status failure");
        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(LOGIN_PATH)));
    }

    @Test
    void applicationClientFailsBeforeServerPortIsKnown() {
        LoginRequesterClient applicationClient = applicationClient();

        assertNull(applicationClient.baseUrl());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> applicationClient.postLogin(form()));
        assertEquals("LoginRequesterClient used before the web server port is known", failure.getMessage());
    }

    @Test
    void mainServerStartSetsBaseUrlAndNamespacedServerStartIsIgnored() {
        LoginRequesterClient applicationClient = applicationClient();

        applicationClient.onWebServerInitialized(serverStarted(12345, null));
        assertEquals("http://localhost:12345", applicationClient.baseUrl());

        applicationClient.onWebServerInitialized(serverStarted(54321, "management"));
        assertEquals("http://localhost:12345", applicationClient.baseUrl());
    }

    @Test
    void fixedBaseUrlClientIgnoresServerStart() {
        client.onWebServerInitialized(serverStarted(12345, null));

        assertEquals("http://localhost:" + wireMock.port(), client.baseUrl());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void applicationClientRejectsBlankRequesterHost(String requesterHost) {
        LoginProperties properties = new LoginProperties("u", "p", requesterHost);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new LoginRequesterClient(RestClient.builder(), properties));

        assertEquals("login.requester-host must not be blank", failure.getMessage());
    }

    /** A client built as the application builds it, with {@code login.requester-host} {@code localhost}. */
    private static LoginRequesterClient applicationClient() {
        return new LoginRequesterClient(RestClient.builder(), new LoginProperties("u", "p", "localhost"));
    }

    /** A start-up event of a web server on {@code port}, in an application context of {@code namespace}. */
    private static WebServerInitializedEvent serverStarted(int port, String namespace) {
        WebServer webServer = mock(WebServer.class);
        when(webServer.getPort()).thenReturn(port);
        WebServerApplicationContext context = mock(WebServerApplicationContext.class);
        when(context.getServerNamespace()).thenReturn(namespace);
        WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
        when(event.getWebServer()).thenReturn(webServer);
        when(event.getApplicationContext()).thenReturn(context);
        return event;
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
