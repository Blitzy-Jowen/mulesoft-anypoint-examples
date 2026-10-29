/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.login_form_using_the_http_connector.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.mulesoft.examples.login_form_using_the_http_connector.service.LoginService;
import com.mulesoft.examples.login_form_using_the_http_connector.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the application's listeners. The original listeners apply no
 * CORS policy (login-form-using-the-http-connector/src/main/app/login-form-using-the-http-connector.xml:9,16,35), so
 * a CORS pre-flight is answered exactly as a plain {@code OPTIONS} request to its path is, whatever origin it comes
 * from and whatever method it asks for, and no response carries an {@code Access-Control-*} or {@code Vary} header:
 * <ul>
 *   <li>{@code /requesterLogin}, which accepts every method, runs {@code CallLoginFlowUsingRequester} and relays the
 *       success page of its own {@code POST /login};</li>
 *   <li>{@code /login}, whose two listeners accept {@code GET} and {@code POST} only, answers with the listener's
 *       405 {@code text/plain} {@code Method not allowed for endpoint: /login}, and neither flow runs;</li>
 *   <li>an unserved path, either listener's path with {@code ;} segment parameters included, answers with the
 *       no-listener 404, and no flow runs.</li>
 * </ul>
 * A cross-origin {@code GET} or {@code POST} that is not a pre-flight still renders the page or logs in.
 *
 * <p>The cases run the whole application on Undertow (random port, profile {@code test} with the original demo
 * credentials {@code mule}/{@code mule}), so the requester's call to its own listener is real. {@link LoginService}
 * is a spy, so the flows each request ran are recorded. The listener and the requester are pinned to IPv4 loopback
 * whatever {@code server.address} and {@code login.requester-host} {@code application.yml} sets. Requests go through
 * ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the listener writes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "login.requester-host=127.0.0.1"})
@ActiveProfiles("test")
class PreflightDispatchListenerTest {

    /** Path of {@code GetLoginPageFlow} and {@code DoLoginFlow}, which accept {@code GET} and {@code POST} only. */
    private static final String LOGIN_PATH = "/login";

    /** Path of {@code CallLoginFlowUsingRequester}, which accepts every method. */
    private static final String REQUESTER_LOGIN_PATH = "/requesterLogin";

    /** The origin of the cross-origin requests, and the first of the two pre-flight origins. */
    private static final String ORIGIN = "https://example.invalid";

    /** Media type of the posted login form. */
    private static final String FORM = "application/x-www-form-urlencoded";

    /** The configured demo credentials as {@code readForm} hands them to the service. */
    private static final Map<String, String> DEMO_FORM = Map.of("username", "mule", "password", "mule");

    /** The real service, recording which flows each request ran; reset after every test. */
    @SpyBean
    private LoginService loginService;

    /** The random port of the application's listener. */
    @LocalServerPort
    private int port;

    /**
     * Origin of the pre-flight, its requested method, then its {@code Access-Control-Request-Headers}, or
     * {@code null}; every case is sent from each of two origins.
     */
    private static Stream<Arguments> preflights() {
        return Stream.of(ORIGIN, "http://a.example").flatMap(origin -> Stream.of(
                Arguments.of(origin, "GET", null),
                Arguments.of(origin, "POST", null),
                Arguments.of(origin, "PUT", null),
                Arguments.of(origin, "DELETE", null),
                Arguments.of(origin, "PATCH", null),
                Arguments.of(origin, "OPTIONS", null),
                Arguments.of(origin, "POST", "content-type")));
    }

    /**
     * A pre-flight to {@code /requesterLogin} runs {@code CallLoginFlowUsingRequester}, as a plain {@code OPTIONS}
     * request does: each of the two requests calls the requester once, whose own {@code POST /login} logs in.
     *
     * @param origin           the {@code Origin} of the pre-flight
     * @param requestedMethod  its {@code Access-Control-Request-Method}
     * @param requestedHeaders its {@code Access-Control-Request-Headers}, or {@code null} for none
     */
    @ParameterizedTest(name = "Origin {0}, Access-Control-Request-Method {1}, Access-Control-Request-Headers {2}")
    @MethodSource("preflights")
    void preflightToRequesterLoginRunsTheFlowLikeAPlainOptionsRequest(String origin, String requestedMethod,
                                                                     String requestedHeaders) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", REQUESTER_LOGIN_PATH, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", REQUESTER_LOGIN_PATH,
                preflightHeaders(origin, requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 200 OK", preflight.statusLine(), preflight::toString);
        assertEquals(List.of("text/html; charset=UTF-8"), preflight.headers("Content-Type"), preflight::toString);
        assertArrayEquals(successPage(), preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
        verify(loginService, times(2)).requesterLogin();
        verify(loginService, times(2)).login(DEMO_FORM);
        verifyNoMoreInteractions(loginService);
    }

    /**
     * A pre-flight to {@code /login} gets the listener's 405, as a plain {@code OPTIONS} request does, whatever
     * method it asks for, and neither {@code /login} flow runs.
     *
     * @param origin           the {@code Origin} of the pre-flight
     * @param requestedMethod  its {@code Access-Control-Request-Method}
     * @param requestedHeaders its {@code Access-Control-Request-Headers}, or {@code null} for none
     */
    @ParameterizedTest(name = "Origin {0}, Access-Control-Request-Method {1}, Access-Control-Request-Headers {2}")
    @MethodSource("preflights")
    void preflightToLoginGetsTheListenerMethod405LikeAPlainOptionsRequest(String origin, String requestedMethod,
                                                                         String requestedHeaders) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", LOGIN_PATH, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", LOGIN_PATH,
                preflightHeaders(origin, requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 405 Method Not Allowed", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("Method not allowed for endpoint: /login".getBytes(StandardCharsets.UTF_8),
                preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(loginService);
    }

    /**
     * A pre-flight to a path no listener serves gets the no-listener 404, as a plain {@code OPTIONS} request does, and
     * no flow runs: an unknown path, and each listener's path with {@code ;} segment parameters, which
     * {@link ListenerPathConfig} refuses after the pre-flight has selected the {@code OPTIONS} handler.
     *
     * @param path the unserved request path
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/unknown", LOGIN_PATH + ";x=1", REQUESTER_LOGIN_PATH + ";x=1"})
    void preflightToAnUnservedPathGetsTheNoListener404(String path) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", path, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", path, preflightHeaders(ORIGIN, "POST", null), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals(("No listener for endpoint: " + path).getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(loginService);
    }

    /** A {@code GET /login} carrying {@code Origin} is no pre-flight: {@code GetLoginPageFlow} renders the page. */
    @Test
    void crossOriginGetStillRendersTheLoginPage() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", ORIGIN);

        HttpTestClient.Response response = HttpTestClient.localhost(port).send("GET", LOGIN_PATH, headers, null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(List.of(), response.headers("Content-Type"), response::toString);
        assertArrayEquals(template("login/index.html"), response.body(), response::toString);
        assertNoCorsHeaders(response);
        verify(loginService).loginPage();
        verifyNoMoreInteractions(loginService);
    }

    /** A {@code POST /login} carrying {@code Origin} is no pre-flight: {@code DoLoginFlow} logs the user in. */
    @Test
    void crossOriginPostStillLogsIn() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", ORIGIN);
        headers.put("Content-Type", FORM);

        HttpTestClient.Response response = HttpTestClient.localhost(port).send("POST", LOGIN_PATH, headers,
                "username=mule&password=mule".getBytes(StandardCharsets.UTF_8));

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(List.of("text/html; charset=UTF-8"), response.headers("Content-Type"), response::toString);
        assertArrayEquals(successPage(), response.body(), response::toString);
        assertNoCorsHeaders(response);
        verify(loginService).login(DEMO_FORM);
        verifyNoMoreInteractions(loginService);
    }

    /** The pre-flight headers in sending order, {@code Access-Control-Request-Headers} only when it is given. */
    private static Map<String, String> preflightHeaders(String origin, String requestedMethod,
                                                        String requestedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", origin);
        headers.put("Access-Control-Request-Method", requestedMethod);
        if (requestedHeaders != null) {
            headers.put("Access-Control-Request-Headers", requestedHeaders);
        }
        return headers;
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

    /** No header named {@code Access-Control-*} or {@code Vary}, compared ignoring case. */
    private static void assertNoCorsHeaders(HttpTestClient.Response response) {
        assertTrue(response.headers().stream().map(Map.Entry::getKey).noneMatch(name ->
                        name.regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length())
                                || name.equalsIgnoreCase("Vary")),
                response::toString);
    }

    /** {@code loginSuccessful.html} with its only expression, {@code #[payload.username]}, rendered as {@code mule}. */
    private static byte[] successPage() {
        String page = new String(template("login/loginSuccessful.html"), StandardCharsets.UTF_8);
        return page.replace("#[payload.username]", "mule").getBytes(StandardCharsets.UTF_8);
    }

    /** The bytes of the classpath resource {@code location}. */
    private static byte[] template(String location) {
        try (InputStream in = PreflightDispatchListenerTest.class.getClassLoader().getResourceAsStream(location)) {
            assertNotNull(in, () -> location + " not found on the test classpath");
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + location, e);
        }
    }
}
