/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.service.BoxClientCredentialsService;
import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.support.HttpTestClient;

/**
 * Wire-level test of {@link OAuthController}, the HTTP trigger of flow {@code httpclientcredentialsFlow}
 * (oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:31-38), on the
 * application's own Undertow listener, with WireMock standing in for both Box endpoints of lines 12-23: the
 * client-credentials token URL and the {@code /api/oauth2/authorize} base path the flow's GET {@code path="/"}
 * extends.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, so status code, reason phrase, headers and
 * body bytes are asserted as the listener writes them. The happy path covers every method, because the listener
 * declares no {@code allowedMethods}. The flow's {@code #[payload]} logger (line 33) is shown to write a form body
 * as the caller's text, a Box reply without a Content-Type to be answered without one, and every reply to be
 * complete when the controller returns, so TRACE gets no servlet echo and OPTIONS no {@code Allow} header. The error
 * cases are the original's own failure paths, each ending in the platform-default 500 of SC-1 because the flow's
 * rollback strategy only logs (lines 35-37), an unparseable Content-Type, which fails before the flow runs, the
 * no-listener 404 for paths the listener does not serve, and the listener's empty 400 for a path holding a malformed
 * percent-escape.
 *
 * <p>The original example has no test, so no original assertion is ported here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// BoxOAuthClient is a singleton that caches the access token; a fresh context per test (and per parameterized
// invocation) makes every test start without a token, so the token-request counts below are exact.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OAuthControllerTest {

    private static final String OAUTH_PATH = "/oauth";

    private static final String TOKEN = "/api/oauth2/token";

    /** The flow's GET {@code path="/"} appended to the request configuration's basePath (line 12). */
    private static final String AUTHORIZE = "/api/oauth2/authorize/";

    /** {@code oauth.client.id} and {@code oauth.client.secret} of {@code application-test.yml}. */
    private static final String BASIC_CREDENTIALS = "Basic "
            + Base64.getEncoder().encodeToString("test-client-id:test-client-secret".getBytes(UTF_8));

    private static final String TOKEN_SCENARIO = "token-refresh";

    private static final String SECOND_TOKEN = "second-token";

    private static final String AUTHORIZE_SCENARIO = "authorize-retry";

    private static final String REFRESHED = "refreshed";

    private static final byte[] AUTHORIZED_BODY = "{\"authorized\":true}".getBytes(UTF_8);

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    /** Suppliers, not values: WireMock's port is known only once the extension has started the server. */
    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        registry.add("box.oauth.token-url", () -> wm.baseUrl() + TOKEN);
        registry.add("box.api.base-url", wm::baseUrl);
        registry.add("fim.base-url", wm::baseUrl);
    }

    @LocalServerPort
    private int port;

    private Logger serviceLogger;

    private Level previousLevel;

    /** The INFO events of the flow's two loggers (lines 33 and 36), which {@link BoxClientCredentialsService} owns. */
    private ListAppender<ILoggingEvent> serviceLog;

    @BeforeEach
    void resetUpstream() {
        wm.resetAll();
    }

    @BeforeEach
    void captureServiceLog() {
        serviceLogger = (Logger) LoggerFactory.getLogger(BoxClientCredentialsService.class);
        previousLevel = serviceLogger.getLevel();
        serviceLog = new ListAppender<>();
        serviceLog.start();
        serviceLogger.addAppender(serviceLog);
        serviceLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseServiceLog() {
        serviceLogger.detachAppender(serviceLog);
        serviceLog.stop();
        serviceLogger.setLevel(previousLevel);
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    /** GET and OPTIONS without a body, POST with a text body: the listener accepts every method. */
    static Stream<Arguments> requestsOfEveryMethod() {
        return Stream.of(
                Arguments.of("GET", null, null),
                Arguments.of("POST", Map.of("Content-Type", "text/plain"), "hello box".getBytes(UTF_8)),
                Arguments.of("OPTIONS", null, null));
    }

    /**
     * The listener answers 200 with the Box body and Box's Content-Type only: no other Box header reaches the
     * caller, and no inbound header or body reaches Box. The token is requested once, with the client credentials
     * in the Basic header, and sent as a Bearer token.
     */
    @ParameterizedTest(name = "{0} /oauth")
    @MethodSource("requestsOfEveryMethod")
    void everyMethodReturnsTheBoxBodyWithOnlyItsContentType(String method, Map<String, String> headers,
                                                            byte[] body) {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withHeader("X-Box", "1")
                .withBody(AUTHORIZED_BODY)));

        HttpTestClient.Response response = client().send(method, OAUTH_PATH, headers, body);

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertArrayEquals(AUTHORIZED_BODY, response.body(), response::toString);
        assertTrue(response.headers("X-Box").isEmpty(), response::toString);

        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN))
                .withHeader("Authorization", equalTo(BASIC_CREDENTIALS))
                .withRequestBody(equalTo("grant_type=client_credentials")));
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE))
                .withHeader("Authorization", equalTo("Bearer tok-1"))
                .withoutHeader("Content-Type"));
        wm.verify(2, anyRequestedFor(anyUrl()));
    }

    /** A status of 400 or above other than 401/403 fails at once: Mule's validator message, no retry. */
    @Test
    void upstreamServerErrorIsAnsweredWithThePlatform500AndNotRetried() {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE)).willReturn(aResponse().withStatus(500).withBody("box down")));

        HttpTestClient.Response response = client().get(OAUTH_PATH);

        assertPlatform500(response, "{ \"message\": \"Response code 500 mapped as failure.\" }");
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN)));
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE)));
    }

    /**
     * A rejected token request fails the flow before the resource GET: no Bearer call can be made without a
     * token. The message names the token URL, whose WireMock port varies, so only its fixed parts are compared;
     * neither the token response body nor the client secret reaches the caller.
     */
    @Test
    void rejectedTokenRequestIsAnsweredWithThePlatform500BeforeAnyResourceCall() {
        wm.stubFor(post(urlEqualTo(TOKEN)).willReturn(aResponse()
                .withStatus(401)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody("{\"error\":\"invalid_client\"}")));

        HttpTestClient.Response response = client().get(OAUTH_PATH);

        assertEquals(500, response.status(), response::toString);
        assertEquals("Internal Server Error", response.reason(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        String body = response.bodyAsString();
        assertTrue(body.startsWith("{ \"message\": \"Token request to "), body);
        assertTrue(body.endsWith(TOKEN + " failed with HTTP status 401\" }"), body);
        assertFalse(body.contains("invalid_client"), body);
        assertFalse(body.contains("test-client-secret"), body);
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN)));
        wm.verify(0, getRequestedFor(urlEqualTo(AUTHORIZE)));
    }

    /**
     * Mule's default {@code refreshTokenWhen}: a 401 or 403 from Box re-runs the client-credentials grant and
     * retries the GET once with the new token, and the caller sees only the retry's 200.
     *
     * @param rejection the status Box answers the first GET with
     */
    @ParameterizedTest(name = "Box answers {0}, then 200")
    @ValueSource(ints = {401, 403})
    void rejectedAccessTokenIsReplacedAndTheGetRetriedOnce(int rejection) {
        wm.stubFor(post(urlEqualTo(TOKEN)).inScenario(TOKEN_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(tokenResponse("tok-1"))
                .willSetStateTo(SECOND_TOKEN));
        wm.stubFor(post(urlEqualTo(TOKEN)).inScenario(TOKEN_SCENARIO)
                .whenScenarioStateIs(SECOND_TOKEN)
                .willReturn(tokenResponse("tok-2")));
        wm.stubFor(get(urlEqualTo(AUTHORIZE)).inScenario(AUTHORIZE_SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(rejection))
                .willSetStateTo(REFRESHED));
        byte[] retriedBody = "{\"ok\":true}".getBytes(UTF_8);
        wm.stubFor(get(urlEqualTo(AUTHORIZE)).inScenario(AUTHORIZE_SCENARIO)
                .whenScenarioStateIs(REFRESHED)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(retriedBody)));

        HttpTestClient.Response response = client().get(OAUTH_PATH);

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertArrayEquals(retriedBody, response.body(), response::toString);
        wm.verify(2, postRequestedFor(urlEqualTo(TOKEN)));
        wm.verify(2, getRequestedFor(urlEqualTo(AUTHORIZE)));
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE)).withHeader("Authorization", equalTo("Bearer tok-1")));
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE)).withHeader("Authorization", equalTo("Bearer tok-2")));
    }

    /** The retry's response is final: a second 401 fails the flow with the validator message, no third GET. */
    @Test
    void rejectionOfTheRetriedGetIsAnsweredWithThePlatform500() {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE)).willReturn(aResponse().withStatus(401)));

        HttpTestClient.Response response = client().get(OAUTH_PATH);

        assertPlatform500(response, "{ \"message\": \"Response code 401 mapped as failure.\" }");
        wm.verify(2, postRequestedFor(urlEqualTo(TOKEN)));
        wm.verify(2, getRequestedFor(urlEqualTo(AUTHORIZE)));
    }

    /**
     * The flow logs a form-encoded body as the caller sent it. These are query-less POSTs, whose bodies Spring's
     * {@code @RequestBody} binding rebuilt from the request parameters: re-sorted, {@code %20} turned into
     * {@code +}, the Latin-1 escape {@code %e9} decoded as UTF-8, and a bare name or a JSON text given a trailing
     * {@code =}.
     *
     * @param form the form body, sent as UTF-8
     */
    @ParameterizedTest(name = "POST /oauth {0}")
    @ValueSource(strings = {"z=1&a=2", "z=1&a=%e9&b=x%20y", "b=2&a=%C3%A9+x&a=dup&empty=", "{\"x\":1}", "k"})
    void formBodyIsLoggedAsTheCallersText(String form) {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody(AUTHORIZED_BODY)));

        HttpTestClient.Response response = client().post(OAUTH_PATH, MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                form);

        assertEquals(200, response.status(), response::toString);
        assertArrayEquals(AUTHORIZED_BODY, response.body(), response::toString);
        assertFalse(serviceLog.list.isEmpty(), "no INFO event of the flow's payload logger");
        ILoggingEvent payloadLine = serviceLog.list.get(0);
        assertEquals(Level.INFO, payloadLine.getLevel());
        assertEquals(form, payloadLine.getFormattedMessage());
    }

    /**
     * Method, Box status, Box Content-Type ({@code null} for none) and Box body. Each request carries an
     * {@code Authorization} header, which the servlet's TRACE echo would repeat.
     */
    static Stream<Arguments> boxReplies() {
        String authorized = new String(AUTHORIZED_BODY, UTF_8);
        return Stream.of(
                Arguments.of("TRACE", 200, MediaType.APPLICATION_JSON_VALUE, authorized),
                Arguments.of("OPTIONS", 200, MediaType.APPLICATION_JSON_VALUE, authorized),
                Arguments.of("HEAD", 200, MediaType.APPLICATION_JSON_VALUE, authorized),
                Arguments.of("GET", 200, null, "untyped"),
                Arguments.of("TRACE", 200, null, "untyped"),
                Arguments.of("OPTIONS", 200, null, "untyped"),
                Arguments.of("HEAD", 200, null, "untyped"),
                Arguments.of("GET", 204, null, ""),
                Arguments.of("TRACE", 204, null, ""),
                Arguments.of("OPTIONS", 204, null, ""));
    }

    /**
     * The reply carries Box's Content-Type and none when Box sent none, and the {@code Content-Length} of Box's
     * body, which a HEAD reply declares without sending it, because the flow's Box call is always a GET. Nothing is
     * added after the controller returns: no servlet TRACE echo and no {@code Allow} header.
     */
    @ParameterizedTest(name = "{0} /oauth, Box {1} with Content-Type {2}")
    @MethodSource("boxReplies")
    void replyCarriesOnlyTheBoxContentTypeAndIsCompleteWhenTheControllerReturns(String method, int boxStatus,
                                                                                String boxContentType,
                                                                                String boxBody) {
        stubToken();
        ResponseDefinitionBuilder box = aResponse().withStatus(boxStatus);
        if (boxContentType != null) {
            box.withHeader("Content-Type", boxContentType);
        }
        if (!boxBody.isEmpty()) {
            box.withBody(boxBody);
        }
        wm.stubFor(get(urlEqualTo(AUTHORIZE)).willReturn(box));
        byte[] body = boxBody.getBytes(UTF_8);

        HttpTestClient.Response response = client().send(method, OAUTH_PATH,
                Map.of("Authorization", "Bearer trace-sentinel"), null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals(boxContentType == null ? List.of() : List.of(boxContentType), response.headers("Content-Type"),
                response::toString);
        assertEquals(List.of(String.valueOf(body.length)), response.headers("Content-Length"), response::toString);
        assertTrue(response.headers("Allow").isEmpty(), response::toString);
        assertArrayEquals("HEAD".equals(method) ? new byte[0] : body, response.body(), response::toString);
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE)));
    }

    /**
     * The Content-Type is parsed before the body is read, as request-body binding parsed it, so the request fails
     * with the message of that parse before the flow's logger runs and without any Box call.
     */
    @Test
    void unparseableContentTypeIsAnsweredWithThePlatform500BeforeTheFlowRuns() {
        HttpTestClient.Response response = client().post(OAUTH_PATH, "bogus", "abc");

        assertPlatform500(response, "{ \"message\": \"Invalid mime type \\\"bogus\\\": does not contain '/'\" }");
        assertTrue(serviceLog.list.isEmpty(), () -> "the flow ran: " + serviceLog.list);
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * The listener path is exact: a sub-path or a trailing slash reaches the SC-1 no-listener 404, and the flow,
     * with its Box calls, never runs.
     *
     * @param path a path the {@code /oauth} listener does not serve
     */
    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"/oauth/extra", "/oauth/"})
    void pathOtherThanOauthReturnsTheNoListener404WithoutCallingBox(String path) {
        HttpTestClient.Response response = client().get(path);

        assertEquals(404, response.status(), response::toString);
        assertEquals("Not Found", response.reason(), response::toString);
        assertContentType(response, MediaType.TEXT_PLAIN_VALUE);
        assertEquals("No listener for endpoint: " + path, response.bodyAsString(), response::toString);
        wm.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * A path holding a {@code %} not followed by two hexadecimal digits, the listener's path with one or any other,
     * gets the listener's rejection, 400 with an empty body and no Content-Type, before dispatch, instead of
     * Undertow's HTML 500; the flow does not run, so Box is not called.
     */
    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"/oauth%zz", "/zz%zz"})
    void malformedPercentEscapeInThePathIsRejected400WithAnEmptyBodyWithoutCallingBox(String path) {
        HttpTestClient.Response response = client().get(path);

        assertEquals("HTTP/1.1 400 Bad Request", response.statusLine(), response::toString);
        assertEquals(List.of("0"), response.headers("Content-Length"), response::toString);
        assertTrue(response.headers("Content-Type").isEmpty(), response::toString);
        assertArrayEquals(new byte[0], response.body(), response::toString);
        assertTrue(serviceLog.list.isEmpty(), () -> serviceLog.list.toString());
        wm.verify(0, anyRequestedFor(anyUrl()));
    }


    private static void stubToken() {
        wm.stubFor(post(urlEqualTo(TOKEN)).willReturn(tokenResponse("tok-1")));
    }

    private static ResponseDefinitionBuilder tokenResponse(String token) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody("{\"access_token\":\"" + token + "\",\"token_type\":\"bearer\",\"expires_in\":3600}");
    }

    private static void assertPlatform500(HttpTestClient.Response response, String expectedBody) {
        assertEquals(500, response.status(), response::toString);
        assertEquals("Internal Server Error", response.reason(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals(expectedBody, response.bodyAsString(), response::toString);
    }

    /**
     * Compares the whole header: the listener writes exactly one {@code Content-Type}, the upstream value as Box
     * sent it on success and SC-1's bare media type on failure, with no charset added.
     */
    private static void assertContentType(HttpTestClient.Response response, String expected) {
        assertEquals(List.of(expected), response.headers("Content-Type"), response::toString);
    }
}
