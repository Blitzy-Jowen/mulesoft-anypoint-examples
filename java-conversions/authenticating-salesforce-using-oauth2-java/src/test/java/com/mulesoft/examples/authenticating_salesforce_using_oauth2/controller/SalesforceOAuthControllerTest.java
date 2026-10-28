/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.UpstreamSystem;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamRateLimitException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamUnavailableException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.service.SalesforceOAuthService;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.service.SalesforceOAuthService.AuthorizeOutcome;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.support.HttpTestClient;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.support.HttpTestClient.Response;

/**
 * Wire-level test of {@link SalesforceOAuthController}, the {@code /} listener of flow {@code salesforce-oauthFlow1}
 * and the {@code /oauth2callback} endpoint of the {@code sfdc:config-with-oauth} callback configuration
 * ({@code authenticating-salesforce-using-oauth2/src/main/app/salesforce-oauth.xml}).
 *
 * <p>The whole application runs on Undertow on a random port with {@link SalesforceOAuthService} mocked, so the
 * controller binding, the SC-9 {@code UpstreamExceptionHandler} and the SC-1 {@code GlobalExceptionHandler} run for
 * real while nothing reaches Salesforce. The mock is also the project's single Partner API session source, so the
 * Partner client still wires. Every request goes through ST-1 {@link HttpTestClient}, which reads the raw status
 * line and never follows a redirect, so the status code, the reason phrase, the headers and the body bytes are
 * asserted exactly as they leave the server.
 *
 * <p>Covered: the authorize redirect; the flow's text on {@code /} and on the callback with a code for every method,
 * as the listener declares no {@code allowedMethods}, with its {@code Content-Length} and no {@code Content-Type}, as
 * the Mule listener sent the flow's untyped {@code set-payload}; the callback with a refused authorization (the
 * platform 500); the approved connector failure modes on both endpoints, which take precedence over that 500; and
 * the no-listener 404. The text of the flow and the content of the redirect are the service's; their construction is
 * covered by {@code service/SalesforceOAuthServiceTest}, and the original assertion by {@code SalesforceOauthTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sfdc.key=test-key", "sfdc.secret=test-secret"})
class SalesforceOAuthControllerTest {

    private static final String APPLICATION_JSON = "application/json";

    /** The authorization URL the service builds for {@code sfdc.key=test-key} with the original callback settings. */
    private static final String AUTHORIZE_URI = "https://login.salesforce.com/services/oauth2/authorize"
            + "?response_type=code&client_id=test-key"
            + "&redirect_uri=http%3A%2F%2Flocalhost%3A8081%2Foauth2callback&display=page";

    /** The {@code set-payload} text of the original flow for a query returning two contacts. */
    private static final String QUERY_TEXT = "Salesforce query returned 2 contacts.";

    private static final String CODE = "abc";

    @MockBean
    SalesforceOAuthService oauthService;

    @LocalServerPort
    int port;

    private HttpTestClient http;

    @BeforeEach
    void setUp() {
        http = HttpTestClient.localhost(port);
    }

    // ---------------------------------------------------------------- / (salesforce-oauthFlow1)

    @Test
    void getRootUnauthorizedRedirectsToSalesforce() throws Exception {
        given(oauthService.authorize()).willReturn(AuthorizeOutcome.redirect(URI.create(AUTHORIZE_URI)));

        Response response = http.get("/");

        assertStatusLine(response, 302, "Found");
        assertThat(response.headers("Location")).as("Location of %s", response).containsExactly(AUTHORIZE_URI);
        assertThat(response.header("Content-Type")).as("Content-Type of %s", response).isNull();
        assertThat(response.header("Content-Length")).isEqualTo("0");
        assertThat(response.body()).isEmpty();
        verify(oauthService).authorize();
        verify(oauthService, never()).completeAuthorization(any(), any(), any());
    }

    /**
     * Every method the text cases send, with the {@code text/plain} body it carries or {@code null} for none. The
     * original listener declares no {@code allowedMethods}, so each one runs the flow (AAP 0.4.9): {@code OPTIONS}
     * exercises the explicit {@code OPTIONS} mapping, {@code TRACE} the dispatched {@code TRACE} whose reply must be
     * committed ahead of the servlet's {@code message/http} echo, {@code HEAD} the reply without its body, and an
     * extension method and a lower-case token show that no method is matched by name.
     */
    static Stream<Arguments> everyMethod() {
        return Stream.of(
                Arguments.of("GET", null),
                Arguments.of("HEAD", null),
                Arguments.of("POST", "ignored"),
                Arguments.of("PUT", "ignored"),
                Arguments.of("DELETE", null),
                Arguments.of("PATCH", "ignored"),
                Arguments.of("OPTIONS", ""),
                Arguments.of("TRACE", null),
                Arguments.of("PROPFIND", null),
                Arguments.of("get", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyMethod")
    void rootAcceptsEveryMethodWhenAuthorized(String method, String body) throws Exception {
        given(oauthService.authorize()).willReturn(AuthorizeOutcome.completed(QUERY_TEXT));

        Response response = send(method, "/", body);

        assertTextAnswer(response, method, QUERY_TEXT);
        verify(oauthService).authorize();
        verify(oauthService, never()).completeAuthorization(any(), any(), any());
    }

    // ---------------------------------------------------------------- /oauth2callback

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyMethod")
    void callbackWithCodeCompletesAuthorization(String method, String body) throws Exception {
        given(oauthService.completeAuthorization(CODE, null, null)).willReturn(QUERY_TEXT);

        Response response = send(method, "/oauth2callback?code=" + CODE, body);

        assertTextAnswer(response, method, QUERY_TEXT);
        verify(oauthService).completeAuthorization(CODE, null, null);
        verify(oauthService, never()).authorize();
    }

    @Test
    void callbackWithAccessDeniedAnswersPlatform500() throws Exception {
        given(oauthService.completeAuthorization(isNull(), eq("access_denied"), eq("end-user denied authorization")))
                .willThrow(new IllegalStateException("Salesforce authorization failed: access_denied"));

        Response response = http.get(
                "/oauth2callback?error=access_denied&error_description=end-user%20denied%20authorization");

        assertStatusLine(response, 500, "Internal Server Error");
        assertContentType(response, APPLICATION_JSON);
        assertThat(response.body())
                .isEqualTo("{ \"message\": \"Salesforce authorization failed: access_denied\" }".getBytes(UTF_8));
        verify(oauthService).completeAuthorization(null, "access_denied", "end-user denied authorization");
        verify(oauthService, never()).authorize();
    }

    // ---------------------------------------------------------------- approved connector failure modes (SC-9)

    /**
     * The two endpoints and the service call each one fails on. Both are covered, beyond the {@code /} cases the
     * plan names, so that each endpoint has at least two error cases besides its happy path (AAP 0.8.2).
     */
    enum Endpoint {

        /** {@code GET /}, failing in the authorize step or the contact query behind it. */
        ROOT {
            @Override
            void failWith(SalesforceOAuthService service, RuntimeException failure) throws Exception {
                given(service.authorize()).willThrow(failure);
            }

            @Override
            Response send(HttpTestClient http) {
                return http.get("/");
            }

            @Override
            void verifyCalled(SalesforceOAuthService service) throws Exception {
                verify(service).authorize();
                verify(service, never()).completeAuthorization(any(), any(), any());
            }
        },

        /** {@code GET /oauth2callback?code=abc}, failing in the code exchange or the contact query behind it. */
        CALLBACK {
            @Override
            void failWith(SalesforceOAuthService service, RuntimeException failure) throws Exception {
                given(service.completeAuthorization(CODE, null, null)).willThrow(failure);
            }

            @Override
            Response send(HttpTestClient http) {
                return http.get("/oauth2callback?code=" + CODE);
            }

            @Override
            void verifyCalled(SalesforceOAuthService service) throws Exception {
                verify(service).completeAuthorization(CODE, null, null);
                verify(service, never()).authorize();
            }
        };

        abstract void failWith(SalesforceOAuthService service, RuntimeException failure) throws Exception;

        abstract Response send(HttpTestClient http);

        abstract void verifyCalled(SalesforceOAuthService service) throws Exception;
    }

    /** One SC-9 exception with the response {@code UpstreamExceptionHandler} must give it. */
    record UpstreamFailure(String label, RuntimeException exception, int status, String reason, String body) {

        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Arguments> upstreamFailures() {
        return Stream.of(Endpoint.values()).flatMap(endpoint -> Stream.of(
                new UpstreamFailure("authentication",
                        new UpstreamAuthenticationException(UpstreamSystem.SALESFORCE,
                                "INVALID_SESSION_ID: Session expired or invalid", null),
                        502, "Bad Gateway",
                        "{ \"message\": \"INVALID_SESSION_ID: Session expired or invalid\","
                                + " \"upstream\": \"Salesforce\" }"),
                new UpstreamFailure("rate limit",
                        new UpstreamRateLimitException(UpstreamSystem.SALESFORCE,
                                "REQUEST_LIMIT_EXCEEDED: TotalRequests Limit exceeded.", null, null),
                        429, "Too Many Requests",
                        "{ \"message\": \"REQUEST_LIMIT_EXCEEDED: TotalRequests Limit exceeded.\","
                                + " \"upstream\": \"Salesforce\" }"),
                new UpstreamFailure("unavailable",
                        new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "Connect timed out",
                                new SocketTimeoutException("Connect timed out")),
                        503, "Service Unavailable",
                        "{ \"message\": \"Connect timed out\", \"upstream\": \"Salesforce\" }"))
                .map(failure -> Arguments.of(endpoint, failure)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("upstreamFailures")
    void upstreamFailuresTakePrecedenceOverThePlatform500(Endpoint endpoint, UpstreamFailure failure)
            throws Exception {
        endpoint.failWith(oauthService, failure.exception());

        Response response = endpoint.send(http);

        assertStatusLine(response, failure.status(), failure.reason());
        assertContentType(response, APPLICATION_JSON);
        assertThat(response.body()).isEqualTo(failure.body().getBytes(UTF_8));
        // Salesforce sends no Retry-After, so none of the three responses carries one, the 429 included.
        assertThat(response.header("Retry-After")).as("Retry-After of %s", response).isNull();
        endpoint.verifyCalled(oauthService);
    }

    // ---------------------------------------------------------------- listener-level default (SC-1)

    @Test
    void unknownPathAnswersNoListener404() {
        Response response = http.get("/unknown");

        assertStatusLine(response, 404, "Not Found");
        assertContentType(response, "text/plain");
        assertThat(response.body()).isEqualTo("No listener for endpoint: /unknown".getBytes(UTF_8));
        verifyNoInteractions(oauthService);
    }

    // ---------------------------------------------------------------- assertions

    /** {@code method} to {@code target} with {@code body} as {@code text/plain}, or with neither when it is null. */
    private Response send(String method, String target, String body) {
        return body == null
                ? http.send(method, target, null, null)
                : http.send(method, target, Map.of("Content-Type", "text/plain"), body.getBytes(UTF_8));
    }

    /**
     * 200 {@code OK} with no {@code Content-Type}, the {@code Content-Length} of {@code text} as UTF-8 bytes and
     * exactly those bytes as the body, which a {@code HEAD} reply leaves out.
     */
    private static void assertTextAnswer(Response response, String method, String text) {
        byte[] bytes = text.getBytes(UTF_8);
        assertStatusLine(response, 200, "OK");
        assertThat(response.headers("Content-Type")).as("Content-Type of %s", response).isEmpty();
        assertThat(response.headers("Content-Length")).as("Content-Length of %s", response)
                .containsExactly(String.valueOf(bytes.length));
        assertThat(response.body()).as("body of %s", response).isEqualTo("HEAD".equals(method) ? new byte[0] : bytes);
    }

    private static void assertStatusLine(Response response, int status, String reason) {
        assertThat(response.status()).as("status of %s", response).isEqualTo(status);
        assertThat(response.reason()).as("reason phrase of %s", response).isEqualTo(reason);
        assertThat(response.statusLine()).isEqualTo("HTTP/1.1 " + status + " " + reason);
    }

    private static void assertContentType(Response response, String expected) {
        assertThat(response.headers("Content-Type")).as("Content-Type of %s", response).containsExactly(expected);
    }
}
