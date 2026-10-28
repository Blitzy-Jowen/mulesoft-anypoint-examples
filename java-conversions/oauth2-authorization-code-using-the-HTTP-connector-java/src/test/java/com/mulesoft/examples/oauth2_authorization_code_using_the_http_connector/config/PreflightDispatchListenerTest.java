/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.HttpEncodingAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;

import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.controller.BoxAuthorizationController;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.controller.WebController;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.exception.GlobalExceptionHandler;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.service.BoxOAuthService;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on both listeners of
 * {@code oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml}: the main
 * listener serving {@code /web} and {@code /web/loginDone} (lines 26-48), and the HTTPS listener behind the OAuth
 * module's {@code localAuthorizationUrl} and {@code redirectionUrl} serving {@code /authorization} and
 * {@code /redirectUrl} (lines 6-7, 21-24). None declares {@code allowedMethods} and none applies a CORS policy, so a
 * CORS pre-flight ({@code OPTIONS} with {@code Origin} and {@code Access-Control-Request-Method}) runs the flow and is
 * answered exactly as a plain {@code OPTIONS} request to the same listener and path is, never with Spring's 403
 * {@code Invalid CORS request}, and with no {@code Access-Control-*} or {@code Vary} header, whatever method it asks
 * for. A pre-flight on the listener that does not serve its path, or for a path no listener serves, gets SC-10's
 * no-listener 404 like a plain {@code OPTIONS} request and runs no flow; a cross-origin request that is not a
 * pre-flight still reaches its controller.
 *
 * <p>The cases run the real controllers, SC-10 {@link ListenerPorts} with its {@link PortRoutingFilter} and the
 * HTTPS listener, and the SC-1 handler on Undertow ({@link WireConfig}), with {@link BoxOAuthService} mocked, so every
 * flow run is recorded with its arguments and no Box call is made. The main listener takes a random port; the HTTPS
 * listener takes a free port chosen once per run, because SC-10 identifies it by its concrete local port. Requests go
 * through ST-1 {@link HttpTestClient}, in its TLS mode on the HTTPS listener with the client key the listener
 * requires, so the status line, headers and body bytes are asserted as each listener writes them.
 */
@SpringBootTest(classes = PreflightDispatchListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PreflightDispatchListenerTest {

    private static final String ORIGIN = "https://example.invalid";

    /** Local port of the HTTPS listener, the {@code ports.box-oauth} the listener and its routes are keyed by. */
    private static final int BOX_OAUTH_PORT = TestSocketUtils.findAvailableTcpPort();

    /** Store type of {@code keystore.jks} and {@code trust-store}, as {@code application.yml} declares it. */
    private static final String STORE_TYPE = "JKS";

    /** Content type of SC-10's no-listener 404, written by {@link PortRoutingFilter}. */
    private static final String NO_LISTENER_CONTENT_TYPE = "text/plain";

    /** The {@code Location} the mocked {@code boxUserLoginFlow} answers with. */
    private static final String LOGIN_LOCATION =
            "https://localhost:8082/authorization?onCompleteRedirectTo=http://localhost:8081/web/loginDone";

    /** The {@code onCompleteRedirectTo} the {@code /authorization} cases send, decoded. */
    private static final String ON_COMPLETE_REDIRECT_TO = "http://localhost:8081/web/loginDone";

    /** The {@code Location} the mocked authorization step answers with: Box's authorization endpoint. */
    private static final String BOX_AUTHORIZE_LOCATION = "https://www.box.com/api/oauth2/authorize?response_type=code"
            + "&client_id=test-box-client-id&redirect_uri=https%3A%2F%2Flocalhost%3A8082%2FredirectUrl&state=s1";

    /** The text the mocked {@code userLoginDoneFlow} answers with. */
    private static final String LOGIN_DONE_TEXT = "The user successfully authorized access to the resource. "
            + "Searching for files containing \"mule\": {\"total_count\":0,\"entries\":[]}";

    @MockBean
    private BoxOAuthService service;

    @LocalServerPort
    private int mainPort;

    @Value("${truststore.path}")
    private String trustStorePath;

    @Value("${truststore.password}")
    private String trustStorePassword;

    @Value("${keystore.path}")
    private String keyStorePath;

    @Value("${keystore.password}")
    private String keyStorePassword;

    @DynamicPropertySource
    static void boxOAuthPort(DynamicPropertyRegistry registry) {
        registry.add("ports.box-oauth", () -> BOX_OAUTH_PORT);
    }

    /** The two listeners of the original application. */
    private enum Listener {
        /** {@code HTTP_Listener_Configuration}, the main listener on {@code server.port}. */
        MAIN,
        /** The OAuth module's HTTPS listener on {@code ports.box-oauth}, requiring a client certificate. */
        BOX_OAUTH
    }

    /**
     * Each served path with its listener, the request target the cases send, the reply its mocked flow answers with,
     * and the flow run that target must record.
     */
    private enum Flow {

        /** {@code boxUserLoginFlow}: the body, empty in every case, and its absent {@code Content-Type} are echoed. */
        WEB(Listener.MAIN, "/web", "/web", 302, "HTTP/1.1 302 Found", LOGIN_LOCATION, "") {
            @Override
            void stub(BoxOAuthService service) {
                when(service.webLogin(any(), any())).thenReturn(reply());
            }

            @Override
            void verifyRuns(BoxOAuthService service, int count) {
                verify(service, times(count)).webLogin(aryEq(new byte[0]), isNull());
            }
        },

        /** {@code userLoginDoneFlow}: the Box search text, which reads nothing from the request. */
        LOGIN_DONE(Listener.MAIN, "/web/loginDone", "/web/loginDone", 200, "HTTP/1.1 200 OK", null,
                LOGIN_DONE_TEXT) {
            @Override
            void stub(BoxOAuthService service) {
                when(service.loginDone()).thenReturn(reply());
            }

            @Override
            void verifyRuns(BoxOAuthService service, int count) {
                verify(service, times(count)).loginDone();
            }
        },

        /** The local authorization URL: the redirect to Box, from the decoded {@code onCompleteRedirectTo}. */
        AUTHORIZATION(Listener.BOX_OAUTH,
                "/authorization?onCompleteRedirectTo=http%3A%2F%2Flocalhost%3A8081%2Fweb%2FloginDone",
                "/authorization", 302, "HTTP/1.1 302 Found", BOX_AUTHORIZE_LOCATION, "") {
            @Override
            void stub(BoxOAuthService service) {
                when(service.authorization(any())).thenReturn(reply());
            }

            @Override
            void verifyRuns(BoxOAuthService service, int count) {
                verify(service, times(count)).authorization(ON_COMPLETE_REDIRECT_TO);
            }
        },

        /** The OAuth redirect URL: the callback, from {@code code}, {@code state} and the raw request target. */
        REDIRECT_URL(Listener.BOX_OAUTH, "/redirectUrl?code=c0de&state=s1", "/redirectUrl", 302,
                "HTTP/1.1 302 Found", ON_COMPLETE_REDIRECT_TO, "") {
            @Override
            void stub(BoxOAuthService service) {
                when(service.redirectCallback(any(), any(), any())).thenReturn(reply());
            }

            @Override
            void verifyRuns(BoxOAuthService service, int count) {
                verify(service, times(count)).redirectCallback("c0de", "s1", "/redirectUrl?code=c0de&state=s1");
            }
        };

        private final Listener listener;
        private final String target;
        private final String path;
        private final int status;
        private final String statusLine;
        private final String location;
        private final String body;

        Flow(Listener listener, String target, String path, int status, String statusLine, String location,
                String body) {
            this.listener = listener;
            this.target = target;
            this.path = path;
            this.status = status;
            this.statusLine = statusLine;
            this.location = location;
            this.body = body;
        }

        /** Makes the mocked service answer this flow with {@link #reply()}. */
        abstract void stub(BoxOAuthService service);

        /** Verifies that this flow ran {@code count} times with the arguments {@link #target} carries. */
        abstract void verifyRuns(BoxOAuthService service, int count);

        /** The reply the mocked flow answers with; it carries no {@code Content-Type}. */
        BoxOAuthService.Reply reply() {
            return new BoxOAuthService.Reply(status, location, null, body.getBytes(StandardCharsets.UTF_8));
        }

        /** The listener that does not serve this flow's path. */
        Listener otherListener() {
            return listener == Listener.MAIN ? Listener.BOX_OAUTH : Listener.MAIN;
        }
    }

    /** Flow, requested method of the pre-flight, then its {@code Access-Control-Request-Headers}, or {@code null}. */
    private static Stream<Arguments> preflightsOnTheServingListener() {
        return Stream.of(Flow.values()).flatMap(flow -> Stream.of(
                Arguments.of(flow, "GET", null),
                Arguments.of(flow, "POST", null),
                Arguments.of(flow, "PUT", null),
                Arguments.of(flow, "DELETE", null),
                Arguments.of(flow, "PATCH", null),
                Arguments.of(flow, "OPTIONS", null),
                Arguments.of(flow, "POST", "content-type")));
    }

    @ParameterizedTest(name = "{0}: Access-Control-Request-Method {1}, Access-Control-Request-Headers {2}")
    @MethodSource("preflightsOnTheServingListener")
    void preflightRunsTheFlowLikeAPlainOptionsRequest(Flow flow, String requestedMethod, String requestedHeaders) {
        flow.stub(service);
        HttpTestClient client = client(flow.listener);
        HttpTestClient.Response plain = client.send("OPTIONS", flow.target, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", flow.target,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertFlowReply(flow, preflight);
        assertNoCorsHeaders(preflight);
        flow.verifyRuns(service, 2);
        verifyNoMoreInteractions(service);
    }

    /**
     * Listener, request target and the path the 404 names, for each path on the listener that does not serve it and
     * for a path neither listener serves; then the requested method of the pre-flight.
     */
    private static Stream<Arguments> preflightsOnAListenerThatDoesNotServeThePath() {
        Stream<Arguments> wrongPort = Stream.of(Flow.values())
                .map(flow -> Arguments.of(flow.otherListener(), flow.target, flow.path));
        Stream<Arguments> unknownPath = Stream.of(Listener.values())
                .map(listener -> Arguments.of(listener, "/unknown", "/unknown"));
        return Stream.concat(wrongPort, unknownPath).flatMap(arguments -> Stream.of("GET", "POST", "DELETE")
                .map(method -> Arguments.of(arguments.get()[0], arguments.get()[1], arguments.get()[2], method)));
    }

    @ParameterizedTest(name = "{1} on {0}: Access-Control-Request-Method {3}")
    @MethodSource("preflightsOnAListenerThatDoesNotServeThePath")
    void preflightGetsTheNoListener404LikeAPlainOptionsRequest(Listener listener, String target, String path,
                                                                String requestedMethod) {
        HttpTestClient client = client(listener);
        HttpTestClient.Response plain = client.send("OPTIONS", target, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", target, preflightHeaders(requestedMethod, null),
                null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals(NO_LISTENER_CONTENT_TYPE, preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals(("No listener for endpoint: " + path).getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(service);
    }

    /** Flow, then the method of a cross-origin request that is not a pre-flight. */
    private static Stream<Arguments> crossOriginRequests() {
        return Stream.of(Flow.values()).flatMap(flow -> Stream.of(
                Arguments.of(flow, "GET"),
                Arguments.of(flow, "POST")));
    }

    @ParameterizedTest(name = "{1} {0} with Origin")
    @MethodSource("crossOriginRequests")
    void crossOriginRequestThatIsNotAPreflightStillReachesTheController(Flow flow, String method) {
        flow.stub(service);

        // POST carries an empty body, so the echoed body and the absent Content-Type match every case of WEB.
        HttpTestClient.Response response = client(flow.listener).send(method, flow.target, Map.of("Origin", ORIGIN),
                "POST".equals(method) ? new byte[0] : null);

        assertFlowReply(flow, response);
        assertNoCorsHeaders(response);
        flow.verifyRuns(service, 1);
        verifyNoMoreInteractions(service);
    }

    /** ST-1 client for {@code listener}: plain HTTP on the main port, TLS with the client key on the HTTPS one. */
    private HttpTestClient client(Listener listener) {
        if (listener == Listener.MAIN) {
            return HttpTestClient.localhost(mainPort);
        }
        return HttpTestClient.https("localhost", BOX_OAUTH_PORT, trustStorePath, STORE_TYPE, trustStorePassword,
                keyStorePath, STORE_TYPE, keyStorePassword);
    }

    private static Map<String, String> preflightHeaders(String requestedMethod, String requestedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", ORIGIN);
        headers.put("Access-Control-Request-Method", requestedMethod);
        if (requestedHeaders != null) {
            headers.put("Access-Control-Request-Headers", requestedHeaders);
        }
        return headers;
    }

    /** The flow's reply as the controller writes it: status line, {@code Location}, no {@code Content-Type}, body. */
    private static void assertFlowReply(Flow flow, HttpTestClient.Response response) {
        assertEquals(flow.statusLine, response.statusLine(), response::toString);
        assertEquals(flow.location, response.header("Location"), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertArrayEquals(flow.body.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
    }

    /** Same status line, same body bytes and the same headers but {@code Date}, compared as sorted lines. */
    private static void assertSameResponse(HttpTestClient.Response expected, HttpTestClient.Response actual) {
        assertEquals(expected.statusLine(), actual.statusLine(), actual::toString);
        assertArrayEquals(expected.body(), actual.body(), actual::toString);
        assertEquals(headerLines(expected), headerLines(actual), actual::toString);
    }

    /** Every header but {@code Date} as {@code name: value} with the name lower-cased, sorted, duplicates kept. */
    private static List<String> headerLines(HttpTestClient.Response response) {
        return response.headers().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("Date"))
                .map(header -> header.getKey().toLowerCase(Locale.ROOT) + ": " + header.getValue())
                .sorted()
                .toList();
    }

    private static void assertNoCorsHeaders(HttpTestClient.Response response) {
        assertTrue(response.headers().stream().map(Map.Entry::getKey).noneMatch(name ->
                        name.regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length())
                                || name.equalsIgnoreCase("Vary")),
                response::toString);
    }

    /**
     * The test's whole context: Spring Boot's servlet web stack on Undertow, {@link PreflightDispatchConfig}, SC-10's
     * listeners and routing, both controllers and the SC-1 handler, with {@link BoxOAuthService} supplied by the test
     * as a mock.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the two listeners and no Box client or TLS client context; and it is not a
     * {@code @SpringBootConfiguration}, so the project's other {@code @SpringBootTest} classes still find exactly one
     * application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightDispatchConfig.class, ListenerPorts.class, WebController.class,
            BoxAuthorizationController.class, GlobalExceptionHandler.class})
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            ServletWebServerFactoryAutoConfiguration.class,
            EmbeddedWebServerFactoryCustomizerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            HttpEncodingAutoConfiguration.class})
    static class WireConfig {
    }
}
