/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseCookie;

import com.mulesoft.examples.authenticating_salesforce_using_oauth2.service.SalesforceOAuthService;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.service.SalesforceOAuthService.AuthorizeOutcome;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the two paths of the application's one listener: {@code /}
 * of flow {@code salesforce-oauthFlow1} and {@code /oauth2callback} of its {@code sfdc:oauth-callback-config}
 * (authenticating-salesforce-using-oauth2/src/main/app/salesforce-oauth.xml:3-4,10). Neither declares
 * {@code allowedMethods} and the Mule listener applied no CORS policy, so a CORS pre-flight runs the flow and is
 * answered exactly as a plain {@code OPTIONS} request is: the 302 to the Salesforce authorization page with its state
 * cookie while no token is held, otherwise the 200 text without a cookie, both without a {@code Content-Type}, and in
 * neither case with an {@code Access-Control-*} or {@code Vary} header.
 *
 * <p>The whole application runs on Undertow on a random port with {@link SalesforceOAuthService} mocked, as in
 * {@code controller/SalesforceOAuthControllerTest}, so the real handler mapping, controller and advices answer while
 * nothing reaches Salesforce. Requests go through ST-1 {@link HttpTestClient}, so the status line, headers and body
 * bytes are asserted as the listener writes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sfdc.key=test-key", "sfdc.secret=test-secret"})
class PreflightDispatchListenerTest {

    private static final String ORIGIN = "https://example.invalid";

    /** A {@code state} in the form the service issues: 43 base64url characters. */
    private static final String STATE = "q6Y1oXk3c0bJ9r2LwT8vZ4mN7pS5dF0gH1jK3lA2s_-";

    /**
     * The authorization URL the service builds for {@code sfdc.key=test-key} with the original callback settings and
     * {@link #STATE}.
     */
    private static final String AUTHORIZE_URI = "https://login.salesforce.com/services/oauth2/authorize"
            + "?response_type=code&client_id=test-key"
            + "&redirect_uri=http%3A%2F%2Flocalhost%3A8081%2Foauth2callback&display=page&state=" + STATE;

    /**
     * The redirect's state cookie, here without {@code Max-Age}: a rendered {@code Expires} date would make the
     * replies to the plain and the pre-flight request differ by the second that passed between them, while this test
     * compares their every other header byte for byte. Its attributes are asserted by
     * {@code controller/SalesforceOAuthControllerTest}.
     */
    private static final ResponseCookie STATE_COOKIE = ResponseCookie.from(SalesforceOAuthService.STATE_COOKIE, STATE)
            .path("/oauth2callback")
            .httpOnly(true)
            .sameSite("Lax")
            .build();

    /** The {@code set-payload} text of the original flow for a query returning two contacts. */
    private static final String QUERY_TEXT = "Salesforce query returned 2 contacts.";

    private static final String CODE = "abc";

    @MockBean
    private SalesforceOAuthService service;

    @LocalServerPort
    private int port;

    /** Each answer of the listener's flow, with the request target that selects it and the reply it writes. */
    private enum Flow {

        /** {@code /} while no token is held: the authorize redirect with its state cookie, and an empty body. */
        ROOT_UNAUTHORIZED("/", "HTTP/1.1 302 Found", AUTHORIZE_URI, STATE_COOKIE.toString(), "") {
            @Override
            void stub(SalesforceOAuthService service) throws Exception {
                given(service.authorize())
                        .willReturn(AuthorizeOutcome.redirect(URI.create(AUTHORIZE_URI), STATE_COOKIE));
            }

            @Override
            void verifyRuns(SalesforceOAuthService service, int count) throws Exception {
                verify(service, times(count)).authorize();
            }
        },

        /** {@code /} with a token held: the query's {@code set-payload} text. */
        ROOT_AUTHORIZED("/", "HTTP/1.1 200 OK", null, null, QUERY_TEXT) {
            @Override
            void stub(SalesforceOAuthService service) throws Exception {
                given(service.authorize()).willReturn(AuthorizeOutcome.completed(QUERY_TEXT));
            }

            @Override
            void verifyRuns(SalesforceOAuthService service, int count) throws Exception {
                verify(service, times(count)).authorize();
            }
        },

        /**
         * The callback with a code and the {@code state} Salesforce returns: the exchange, then the same text as an
         * authorized {@code /}. A pre-flight carries no cookie, so the service receives none.
         */
        CALLBACK_WITH_CODE("/oauth2callback?code=" + CODE + "&state=" + STATE, "HTTP/1.1 200 OK", null, null,
                QUERY_TEXT) {
            @Override
            void stub(SalesforceOAuthService service) throws Exception {
                given(service.completeAuthorization(CODE, null, null, STATE, null)).willReturn(QUERY_TEXT);
            }

            @Override
            void verifyRuns(SalesforceOAuthService service, int count) throws Exception {
                verify(service, times(count)).completeAuthorization(CODE, null, null, STATE, null);
            }
        };

        private final String target;
        private final String statusLine;
        private final String location;
        private final String setCookie;
        private final String body;

        Flow(String target, String statusLine, String location, String setCookie, String body) {
            this.target = target;
            this.statusLine = statusLine;
            this.location = location;
            this.setCookie = setCookie;
            this.body = body;
        }

        /** Makes the mocked service give this flow's answer. */
        abstract void stub(SalesforceOAuthService service) throws Exception;

        /** Verifies that this flow ran {@code count} times with the arguments {@link #target} carries. */
        abstract void verifyRuns(SalesforceOAuthService service, int count) throws Exception;
    }

    /** Flow, requested method of the pre-flight, then its {@code Access-Control-Request-Headers}, or {@code null}. */
    private static Stream<Arguments> preflights() {
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
    @MethodSource("preflights")
    void preflightRunsTheFlowLikeAPlainOptionsRequest(Flow flow, String requestedMethod, String requestedHeaders)
            throws Exception {
        flow.stub(service);
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", flow.target, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", flow.target,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertFlowReply(flow, preflight);
        assertNoCorsHeaders(preflight);
        flow.verifyRuns(service, 2);
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest(name = "Access-Control-Request-Method {0}")
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void preflightToAnUnservedPathGetsTheNoListener404LikeAPlainOptionsRequest(String requestedMethod) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", "/unknown", null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", "/unknown", preflightHeaders(requestedMethod, null),
                null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("No listener for endpoint: /unknown".getBytes(StandardCharsets.UTF_8), preflight.body(),
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
    void crossOriginRequestThatIsNotAPreflightStillRunsTheFlow(Flow flow, String method) throws Exception {
        flow.stub(service);

        HttpTestClient.Response response = HttpTestClient.localhost(port).send(method, flow.target,
                Map.of("Origin", ORIGIN), "POST".equals(method) ? new byte[0] : null);

        assertFlowReply(flow, response);
        assertNoCorsHeaders(response);
        flow.verifyRuns(service, 1);
        verifyNoMoreInteractions(service);
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

    /**
     * The flow's reply as the controller writes it: status line, {@code Location}, the redirect's state cookie and no
     * other {@code Set-Cookie}, no {@code Content-Type}, as the Mule listener sent the redirect and the untyped
     * {@code set-payload}, the {@code Content-Length} of the body and the body bytes.
     */
    private static void assertFlowReply(Flow flow, HttpTestClient.Response response) {
        byte[] body = flow.body.getBytes(StandardCharsets.UTF_8);
        assertEquals(flow.statusLine, response.statusLine(), response::toString);
        assertEquals(flow.location, response.header("Location"), response::toString);
        assertEquals(flow.setCookie == null ? List.of() : List.of(flow.setCookie), response.headers("Set-Cookie"),
                response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(List.of(String.valueOf(body.length)), response.headers("Content-Length"), response::toString);
        assertArrayEquals(body, response.body(), response::toString);
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
}
