/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.service;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.client.BoxApiClient;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.client.BoxApiClient.RawResponse;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config.BoxProperties;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.service.BoxOAuthService.Reply;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.service.BoxOAuthService.TokenSet;

/**
 * Unit test of {@link BoxOAuthService}, the logic of
 * {@code oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml}: flow
 * {@code boxUserLoginFlow} ({@code /web}), flow {@code userLoginDoneFlow} ({@code /web/loginDone}) with the grant's
 * refresh-and-retry on 401/403, the local authorization URL ({@code /authorization}), the redirect URL
 * ({@code /redirectUrl}) with every {@code authorizationStatus} marker and its platform text, the state decoding
 * and the per-resource-owner token store.
 *
 * <p>{@link BoxApiClient} is a strict Mockito mock, {@link BoxProperties} is real and built from literals (its TLS
 * context is never built), and the log is read from a Logback {@link ListAppender} on the root logger. No Spring
 * context, network or WireMock is used.
 */
@ExtendWith(MockitoExtension.class)
class BoxOAuthServiceTest {

    private static final String CLIENT_ID = "test-box-client-id";
    private static final String CLIENT_SECRET = "test-box-client-secret";
    private static final String AUTHORIZATION_URL = "https://box.test/api/oauth2/authorize";
    private static final String LOCAL_AUTHORIZATION_URL = "https://localhost:8082/authorization";
    private static final String REDIRECT_URL = "https://localhost:8082/redirectUrl";
    private static final String TOKEN_URL = "https://box.test/api/oauth2/token";
    private static final String API_BASE_URL = "https://api.box.test/2.0";
    private static final String LOGIN_REDIRECT_LOCATION =
            "https://localhost:8082/authorization?onCompleteRedirectTo=http://localhost:8081/web/loginDone";
    private static final String STORE_PASSWORD = "mule123";

    private static final String LOGIN_DONE_URL = "http://localhost:8081/web/loginDone";
    /** The state {@code /redirectUrl} receives, URL-decoded, after {@code /authorization?onCompleteRedirectTo=}. */
    private static final String LOGIN_DONE_STATE = ":onCompleteRedirectTo=http://localhost:8081/web/loginDone";

    /** Box authorization URL without a state, written out so the expectation does not reuse the encoder. */
    private static final String AUTHORIZE_WITHOUT_STATE = "https://box.test/api/oauth2/authorize"
            + "?response_type=code&client_id=test-box-client-id"
            + "&box_device_id=1&box_device_name=device1"
            + "&redirect_uri=https%3A%2F%2Flocalhost%3A8082%2FredirectUrl";

    private static final String DEFAULT_OWNER = BoxOAuthService.DEFAULT_RESOURCE_OWNER_ID;

    private static final String SEARCH_PREFIX =
            "The user successfully authorized access to the resource. Searching for files containing \"mule\": ";
    private static final String NO_ACCESS_TOKEN = "No access token for the default user. Verify that you have "
            + "authenticated the user before trying to execute an operation to the API.";
    private static final String TOKEN_NOT_FOUND =
            "Failed getting access token or refresh token from token URL response. See logs for details.";
    private static final String TOKEN_URL_FAILURE = "Failure calling token url https://box.test/api/oauth2/token";
    private static final String ACCESS_TOKEN_RETRIEVED = "Successfully retrieved access token";
    private static final String REDIRECT_PROCESSING_FAILURE =
            "Failed processing redirect URL request done from OAuth provider. See logs for details.";
    private static final String LOGIN_LOG = "Redirecting user to Box login page";

    private static final String CODE = "authorization-code-1";
    private static final String ACCESS = "access-token-1";
    private static final String REFRESH = "refresh-token-1";
    private static final String NEW_ACCESS = "access-token-2";
    private static final String NEW_REFRESH = "refresh-token-2";
    private static final String SEARCH_BODY =
            "{\"total_count\":1,\"entries\":[{\"type\":\"file\",\"id\":\"1\",\"name\":\"mule.txt\"}]}";

    private static final TokenSet STORED = new TokenSet(ACCESS, REFRESH, "3600", "bearer");

    @Mock
    BoxApiClient client;

    BoxOAuthService service;

    Logger rootLogger;

    Logger serviceLogger;

    Level previousServiceLevel;

    ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void createServiceAndCaptureTheLog() {
        service = new BoxOAuthService(properties(CLIENT_ID), client);
        serviceLogger = (Logger) LoggerFactory.getLogger(BoxOAuthService.class);
        previousServiceLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void releaseTheLog() {
        rootLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(previousServiceLevel);
    }

    // ---------------------------------------------------------------- construction, records and token store

    @Test
    void constructorRejectsAMissingCollaborator() {
        assertThatNullPointerException().isThrownBy(() -> new BoxOAuthService(null, client))
                .withMessage("properties");
        assertThatNullPointerException().isThrownBy(() -> new BoxOAuthService(properties(CLIENT_ID), null))
                .withMessage("client");
    }

    @Test
    void replyNormalizesAMissingBodyToAnEmptyOne() {
        assertThat(new Reply(204, null, null, null).body()).isEmpty();
    }

    static Stream<Arguments> tokenSetTexts() {
        return Stream.of(
                Arguments.of(new TokenSet(ACCESS, REFRESH, "3600", "bearer"),
                        "TokenSet[accessToken=****, refreshToken=****, expiresIn=3600, tokenType=bearer]"),
                Arguments.of(new TokenSet(null, null, null, null),
                        "TokenSet[accessToken=null, refreshToken=null, expiresIn=null, tokenType=null]"));
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("tokenSetTexts")
    void tokenSetTextNeverCarriesATokenValue(TokenSet tokens, String expected) {
        assertThat(tokens.toString()).isEqualTo(expected);
    }

    @Test
    void anOwnerThatHasNotAuthorizedHasNoTokens() {
        assertThat(service.tokens(null)).isEmpty();
        assertThat(service.tokens(DEFAULT_OWNER)).isEmpty();
        assertThat(service.tokens("alice")).isEmpty();
    }

    @Test
    void tokensAreKeptPerOwnerAndReplacedByTheNextStore() {
        TokenSet alice = new TokenSet("alice-access", "alice-refresh", null, null);
        TokenSet replacement = new TokenSet(NEW_ACCESS, NEW_REFRESH, "4000", "bearer");

        service.storeTokens(null, STORED);
        service.storeTokens("alice", alice);

        assertThat(service.tokens(DEFAULT_OWNER)).contains(STORED);
        assertThat(service.tokens(null)).contains(STORED);
        assertThat(service.tokens("alice")).contains(alice);

        service.storeTokens(DEFAULT_OWNER, replacement);

        assertThat(service.tokens(null)).contains(replacement);
        assertThat(service.tokens("alice")).contains(alice);
        verifyNoInteractions(client);
    }

    @Test
    void storeTokensRejectsMissingTokens() {
        assertThatNullPointerException().isThrownBy(() -> service.storeTokens(DEFAULT_OWNER, null))
                .withMessage("tokens");
        assertThat(service.tokens(DEFAULT_OWNER)).isEmpty();
    }

    // ---------------------------------------------------------------- /web (boxUserLoginFlow)

    static Stream<Arguments> webLoginRequests() {
        byte[] form = "name=mul\u00e9".getBytes(UTF_8);
        return Stream.of(
                Arguments.of("no body", null, "text/plain", new byte[0], null),
                Arguments.of("empty body", new byte[0], "text/plain", new byte[0], null),
                Arguments.of("form body", form, "application/x-www-form-urlencoded", form,
                        "application/x-www-form-urlencoded"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("webLoginRequests")
    void webLoginRedirectsToTheLocalAuthorizationUrlAndEchoesTheBody(String description, byte[] body,
            String contentType, byte[] expectedBody, String expectedContentType) {
        Reply reply = service.webLogin(body, contentType);

        assertThat(reply.status()).isEqualTo(302);
        assertThat(reply.location()).isEqualTo(LOGIN_REDIRECT_LOCATION);
        assertThat(reply.contentType()).isEqualTo(expectedContentType);
        assertThat(reply.body()).isEqualTo(expectedBody);
        assertThat(serviceEvents()).extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(tuple(Level.INFO, LOGIN_LOG));
        verifyNoInteractions(client);
    }

    // ---------------------------------------------------------------- /web/loginDone (userLoginDoneFlow)

    static Stream<Arguments> storesWithoutADefaultAccessToken() {
        return Stream.of(
                Arguments.of("nothing stored", (Consumer<BoxOAuthService>) s -> { }),
                Arguments.of("default owner without access token",
                        (Consumer<BoxOAuthService>) s -> s.storeTokens(DEFAULT_OWNER,
                                new TokenSet(null, REFRESH, null, null))),
                Arguments.of("tokens of another owner only",
                        (Consumer<BoxOAuthService>) s -> s.storeTokens("alice", STORED)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("storesWithoutADefaultAccessToken")
    void loginDoneWithoutAnAccessTokenIsRefusedBeforeAnyBoxCall(String description, Consumer<BoxOAuthService> setUp) {
        setUp.accept(service);

        Throwable thrown = catchThrowable(service::loginDone);

        assertThat(thrown).isExactlyInstanceOf(IllegalStateException.class).hasMessage(NO_ACCESS_TOKEN);
        verifyNoInteractions(client);
    }

    static Stream<Arguments> successfulSearches() {
        String result = "M\u00fcller \u00fcber mule \u2713";
        String latin1 = "M\u00fcller \u00fcber mule";
        return Stream.of(
                Arguments.of("status 200", json(200, SEARCH_BODY), SEARCH_BODY),
                Arguments.of("status 201", json(201, SEARCH_BODY), SEARCH_BODY),
                Arguments.of("status 204", json(204, SEARCH_BODY), SEARCH_BODY),
                Arguments.of("status 302", json(302, SEARCH_BODY), SEARCH_BODY),
                Arguments.of("status 399", json(399, SEARCH_BODY), SEARCH_BODY),
                Arguments.of("declared ISO-8859-1", withContentType("text/plain;charset=ISO-8859-1",
                        latin1.getBytes(ISO_8859_1)), latin1),
                Arguments.of("declared UTF-8", withContentType("application/json;charset=UTF-8",
                        result.getBytes(UTF_8)), result),
                Arguments.of("no charset", withContentType("application/json", result.getBytes(UTF_8)), result),
                Arguments.of("no Content-Type", withContentType(null, result.getBytes(UTF_8)), result),
                Arguments.of("unparseable Content-Type", withContentType("not a media type",
                        result.getBytes(UTF_8)), result),
                Arguments.of("unknown charset", withContentType("text/plain;charset=x-no-such-charset",
                        result.getBytes(UTF_8)), result),
                Arguments.of("illegal charset name", withContentType("text/plain;charset=@@",
                        result.getBytes(UTF_8)), result),
                Arguments.of("no headers", new RawResponse(200, null, result.getBytes(UTF_8)), result),
                Arguments.of("no body", new RawResponse(200, new HttpHeaders(), null), ""));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("successfulSearches")
    void loginDoneWrapsEverySearchAnswerBelow400InItsDeclaredCharsetOrUtf8(String description,
            RawResponse answer, String expectedResult) {
        service.storeTokens(DEFAULT_OWNER, STORED);
        when(client.search(ACCESS)).thenReturn(answer);

        Reply reply = service.loginDone();

        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.location()).isNull();
        assertThat(reply.contentType()).isNull();
        assertThat(text(reply)).isEqualTo(SEARCH_PREFIX + expectedResult);
        assertThat(service.tokens(DEFAULT_OWNER)).contains(STORED);
        verify(client).search(ACCESS);
        verifyNoMoreInteractions(client);
        assertThat(serviceEvents()).isEmpty();
    }

    static Stream<Arguments> successfulRefreshes() {
        String fullResponse = "{\"access_token\":\"" + NEW_ACCESS + "\",\"expires_in\":4000,\"restricted_to\":[],"
                + "\"refresh_token\":\"" + NEW_REFRESH + "\",\"token_type\":\"bearer\"}";
        return Stream.of(
                Arguments.of(401, fullResponse, new TokenSet(NEW_ACCESS, NEW_REFRESH, "4000", "bearer")),
                Arguments.of(403, fullResponse, new TokenSet(NEW_ACCESS, NEW_REFRESH, "4000", "bearer")),
                // A refresh response without a refresh token keeps the stored one.
                Arguments.of(401, "{\"access_token\":\"" + NEW_ACCESS + "\"}",
                        new TokenSet(NEW_ACCESS, REFRESH, null, null)));
    }

    @ParameterizedTest(name = "[{index}] status {0}, refreshed {2}")
    @MethodSource("successfulRefreshes")
    void loginDoneRefreshesOnceAndRetriesWhenBoxRejectsTheToken(int rejection, String refreshBody,
            TokenSet expectedTokens) {
        service.storeTokens(DEFAULT_OWNER, STORED);
        when(client.search(ACCESS)).thenReturn(json(rejection, "{\"type\":\"error\"}"));
        when(client.refreshToken(REFRESH)).thenReturn(json(200, refreshBody));
        when(client.search(NEW_ACCESS)).thenReturn(json(200, SEARCH_BODY));

        Reply reply = service.loginDone();

        assertThat(reply.status()).isEqualTo(200);
        assertThat(text(reply)).isEqualTo(SEARCH_PREFIX + SEARCH_BODY);
        assertThat(service.tokens(DEFAULT_OWNER)).contains(expectedTokens);
        InOrder order = inOrder(client);
        order.verify(client).search(ACCESS);
        order.verify(client).refreshToken(REFRESH);
        order.verify(client).search(NEW_ACCESS);
        verifyNoMoreInteractions(client);
        assertThat(serviceEvents()).isEmpty();
    }

    @Test
    void loginDoneRethrowsAFailedRefreshCallAndLogsIt() {
        ResourceAccessException failure = new ResourceAccessException("I/O error on POST request");
        service.storeTokens(DEFAULT_OWNER, STORED);
        when(client.search(ACCESS)).thenReturn(json(401, ""));
        when(client.refreshToken(REFRESH)).thenThrow(failure);

        Throwable thrown = catchThrowable(service::loginDone);

        assertThat(thrown).isSameAs(failure);
        assertThat(service.tokens(DEFAULT_OWNER)).contains(STORED);
        assertThat(serviceEvents()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Failure calling the token url to refresh the access token");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(ResourceAccessException.class.getName());
        });
        verify(client).search(ACCESS);
        verify(client).refreshToken(REFRESH);
        verifyNoMoreInteractions(client);
    }

    static Stream<Arguments> failedRefreshes() {
        String rejected = "The token url answered the refresh request with status ";
        String noAccessToken = "No access token could be extracted from the token url's refresh response";
        return Stream.of(
                Arguments.of(403, 400, "{\"error\":\"invalid_grant\"}", TOKEN_URL_FAILURE, rejected + 400),
                Arguments.of(403, 401, "{\"error\":\"invalid_client\"}", TOKEN_URL_FAILURE, rejected + 401),
                Arguments.of(401, 500, "", TOKEN_URL_FAILURE, rejected + 500),
                Arguments.of(401, 200, "", TOKEN_NOT_FOUND, noAccessToken),
                Arguments.of(401, 200, "{}", TOKEN_NOT_FOUND, noAccessToken),
                Arguments.of(403, 200, "{\"refresh_token\":\"" + NEW_REFRESH + "\"}", TOKEN_NOT_FOUND, noAccessToken),
                // Mule's extractors match the whole body without DOTALL, so a multi-line response yields no token.
                Arguments.of(401, 200, "{\n  \"access_token\": \"" + NEW_ACCESS + "\",\n  \"refresh_token\": \""
                        + NEW_REFRESH + "\"\n}", TOKEN_NOT_FOUND, noAccessToken));
    }

    @ParameterizedTest(name = "[{index}] search {0}, token url {1} {2}")
    @MethodSource("failedRefreshes")
    void loginDoneFailsWithoutRetryingWhenTheRefreshYieldsNoAccessToken(int rejection, int refreshStatus,
            String refreshBody, String expectedMessage, String expectedLog) {
        service.storeTokens(DEFAULT_OWNER, STORED);
        when(client.search(ACCESS)).thenReturn(json(rejection, ""));
        when(client.refreshToken(REFRESH)).thenReturn(json(refreshStatus, refreshBody));

        Throwable thrown = catchThrowable(service::loginDone);

        assertThat(thrown).isExactlyInstanceOf(IllegalStateException.class).hasMessage(expectedMessage);
        assertThat(service.tokens(DEFAULT_OWNER)).contains(STORED);
        assertThat(serviceEvents()).extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(tuple(Level.ERROR, expectedLog));
        assertThat(loggedText()).doesNotContain(NEW_ACCESS, NEW_REFRESH);
        verify(client).search(ACCESS);
        verify(client).refreshToken(REFRESH);
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest(name = "[{index}] {0} then {1}")
    @CsvSource({"401, 401", "403, 403", "401, 403", "403, 500"})
    void loginDoneFailsWithoutASecondRefreshWhenTheRetryIsStillRejected(int first, int retry) {
        service.storeTokens(DEFAULT_OWNER, STORED);
        when(client.search(ACCESS)).thenReturn(json(first, ""));
        when(client.refreshToken(REFRESH)).thenReturn(json(200,
                "{\"access_token\":\"" + NEW_ACCESS + "\",\"refresh_token\":\"" + NEW_REFRESH + "\"}"));
        when(client.search(NEW_ACCESS)).thenReturn(json(retry, ""));

        Throwable thrown = catchThrowable(service::loginDone);

        assertThat(thrown).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Response code " + retry + " mapped as failure.");
        assertThat(service.tokens(DEFAULT_OWNER)).contains(new TokenSet(NEW_ACCESS, NEW_REFRESH, null, null));
        InOrder order = inOrder(client);
        order.verify(client).search(ACCESS);
        order.verify(client).refreshToken(REFRESH);
        order.verify(client).search(NEW_ACCESS);
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest(name = "[{index}] status {0}")
    @ValueSource(ints = {400, 404, 405, 429, 500, 503})
    void loginDoneMapsAnyOtherErrorStatusToAFailureWithoutRefreshing(int status) {
        service.storeTokens(DEFAULT_OWNER, STORED);
        when(client.search(ACCESS)).thenReturn(json(status, "{\"type\":\"error\"}"));

        Throwable thrown = catchThrowable(service::loginDone);

        assertThat(thrown).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Response code " + status + " mapped as failure.");
        assertThat(service.tokens(DEFAULT_OWNER)).contains(STORED);
        verify(client).search(ACCESS);
        verifyNoMoreInteractions(client);
    }

    @Test
    void loginDonePropagatesASearchFailureUnchanged() {
        ResourceAccessException failure = new ResourceAccessException("I/O error on GET request");
        service.storeTokens(DEFAULT_OWNER, STORED);
        when(client.search(ACCESS)).thenThrow(failure);

        assertThat(catchThrowable(service::loginDone)).isSameAs(failure);
        verify(client).search(ACCESS);
        verifyNoMoreInteractions(client);
    }

    // ---------------------------------------------------------------- /authorization (localAuthorizationUrl)

    static Stream<Arguments> authorizationRedirects() {
        String beforeState = "https://box.test/api/oauth2/authorize"
                + "?response_type=code&client_id=test-box-client-id&state=";
        String afterState = "&box_device_id=1&box_device_name=device1"
                + "&redirect_uri=https%3A%2F%2Flocalhost%3A8082%2FredirectUrl";
        return Stream.of(
                Arguments.of(LOGIN_DONE_URL, beforeState
                        + "%3AonCompleteRedirectTo%3Dhttp%3A%2F%2Flocalhost%3A8081%2Fweb%2FloginDone" + afterState),
                Arguments.of("http://localhost:8081/web/loginDone?a=b c&d=\u00e9", beforeState
                        + "%3AonCompleteRedirectTo%3Dhttp%3A%2F%2Flocalhost%3A8081%2Fweb%2FloginDone"
                        + "%3Fa%3Db+c%26d%3D%C3%A9" + afterState),
                Arguments.of(null, AUTHORIZE_WITHOUT_STATE),
                Arguments.of("", AUTHORIZE_WITHOUT_STATE));
    }

    @ParameterizedTest(name = "[{index}] onCompleteRedirectTo={0}")
    @MethodSource("authorizationRedirects")
    void authorizationRedirectsToBoxWithTheOnCompleteRedirectInTheState(String onCompleteRedirectTo,
            String expectedLocation) {
        Reply reply = service.authorization(onCompleteRedirectTo);

        assertThat(reply.status()).isEqualTo(302);
        assertThat(reply.location()).isEqualTo(expectedLocation);
        assertThat(reply.contentType()).isNull();
        assertThat(reply.body()).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void authorizationAppendsTheClientIdUnencodedAsMuleDoes() {
        BoxOAuthService unusualId = new BoxOAuthService(properties("id with space"), client);

        assertThat(unusualId.authorization(null).location())
                .isEqualTo(AUTHORIZE_WITHOUT_STATE.replace(CLIENT_ID, "id with space"));
    }

    // ---------------------------------------------------------------- /redirectUrl (redirectionUrl)

    @ParameterizedTest(name = "[{index}] code={0}")
    @NullAndEmptySource
    void redirectCallbackWithoutACodeSendsStatus100WithoutCallingBox(String code) {
        Reply reply = service.redirectCallback(code, LOGIN_DONE_STATE, "/redirectUrl?error=access_denied");

        assertThat(reply.status()).isEqualTo(302);
        assertThat(reply.location()).isEqualTo(LOGIN_DONE_URL + "?authorizationStatus=100");
        assertThat(service.tokens(DEFAULT_OWNER)).isEmpty();
        assertThat(serviceEvents()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo(
                    "Redirect URL request failed, authorizationStatus 100: no authorization code in the request");
            assertThat(event.getThrowableProxy()).isNull();
        });
        verifyNoInteractions(client);
    }

    @Test
    void redirectCallbackSendsStatus200WhenTheTokenCallThrows() {
        when(client.requestToken(CODE)).thenThrow(new ResourceAccessException("I/O error on POST request"));

        Reply reply = service.redirectCallback(CODE, LOGIN_DONE_STATE, "/redirectUrl?code=" + CODE);

        assertThat(reply.location()).isEqualTo(LOGIN_DONE_URL + "?authorizationStatus=200");
        assertThat(service.tokens(DEFAULT_OWNER)).isEmpty();
        assertThat(serviceEvents()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Redirect URL request failed, authorizationStatus 200: the token url call failed");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(ResourceAccessException.class.getName());
        });
    }

    static Stream<Arguments> unusableTokenResponses() {
        String notFound = " not found in the token url response";
        return Stream.of(
                Arguments.of(json(400, "{\"error\":\"invalid_grant\"}"), 200,
                        "the token url answered status 400", null),
                Arguments.of(json(401, "{\"error\":\"invalid_client\"}"), 200,
                        "the token url answered status 401", null),
                Arguments.of(json(500, ""), 200, "the token url answered status 500", null),
                Arguments.of(json(200, "{\"refresh_token\":\"" + REFRESH + "\"}"), 201, "access token" + notFound,
                        null),
                Arguments.of(json(200, "{\"access_token\":\"" + ACCESS + "\",\"expires_in\":3600}"), 201,
                        "refresh token" + notFound, null),
                Arguments.of(json(200, ""), 201, "access token" + notFound, null),
                // Mule's extractors match the whole body without DOTALL, so a multi-line response yields no token.
                Arguments.of(json(200, "{\n  \"access_token\": \"" + ACCESS + "\",\n  \"refresh_token\": \""
                        + REFRESH + "\",\n  \"token_type\": \"bearer\"\n}"), 201, "access token" + notFound, null),
                // No response object at all is a failure outside the token call: 300.
                Arguments.of(null, 300, "processing failed", NullPointerException.class));
    }

    @ParameterizedTest(name = "[{index}] authorizationStatus {1}: {2}")
    @MethodSource("unusableTokenResponses")
    void redirectCallbackSendsTheFailureMarkerForATokenResponseItCannotUse(RawResponse answer, int marker,
            String detail, Class<? extends Throwable> loggedFailure) {
        when(client.requestToken(CODE)).thenReturn(answer);

        Reply reply = service.redirectCallback(CODE, LOGIN_DONE_STATE, "/redirectUrl?code=" + CODE);

        assertThat(reply.status()).isEqualTo(302);
        assertThat(reply.location()).isEqualTo(LOGIN_DONE_URL + "?authorizationStatus=" + marker);
        assertThat(reply.body()).isEmpty();
        assertThat(service.tokens(DEFAULT_OWNER)).isEmpty();
        assertThat(serviceEvents()).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Redirect URL request failed, authorizationStatus " + marker + ": " + detail);
            if (loggedFailure == null) {
                assertThat(event.getThrowableProxy()).isNull();
            } else {
                assertThat(event.getThrowableProxy().getClassName()).isEqualTo(loggedFailure.getName());
            }
        });
        assertThat(loggedText()).doesNotContain(ACCESS, REFRESH);
        verify(client).requestToken(CODE);
        verifyNoMoreInteractions(client);
    }

    static Stream<Arguments> outcomesWithoutARedirect() {
        return Stream.of(
                Arguments.of("100 no code", null, (Consumer<BoxApiClient>) c -> { },
                        400, "Failure retrieving access token.\n OAuth Server uri from callback: "
                                + "/redirectUrl?error=access_denied"),
                Arguments.of("200 token call threw", CODE,
                        (Consumer<BoxApiClient>) c -> when(c.requestToken(CODE))
                                .thenThrow(new ResourceAccessException("I/O error on POST request")),
                        500, TOKEN_URL_FAILURE + ". Exception message is null"),
                Arguments.of("200 token url status 401", CODE,
                        (Consumer<BoxApiClient>) c -> when(c.requestToken(CODE)).thenReturn(json(401, "")),
                        500, TOKEN_URL_FAILURE + ". Exception message is null"),
                Arguments.of("201 no refresh token", CODE,
                        (Consumer<BoxApiClient>) c -> when(c.requestToken(CODE))
                                .thenReturn(json(200, "{\"access_token\":\"" + ACCESS + "\"}")),
                        500, TOKEN_NOT_FOUND),
                Arguments.of("300 no token response", CODE,
                        (Consumer<BoxApiClient>) c -> when(c.requestToken(CODE)).thenReturn(null),
                        500, REDIRECT_PROCESSING_FAILURE));
    }

    /** The failure texts; the status-0 text is asserted with the state decoding below. */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("outcomesWithoutARedirect")
    void redirectCallbackWithoutAStateAnswersAFailureWithThePlatformText(String description, String code,
            Consumer<BoxApiClient> upstream, int expectedStatus, String expectedText) {
        upstream.accept(client);
        String requestUri = code == null ? "/redirectUrl?error=access_denied" : "/redirectUrl?code=" + code;

        Reply reply = service.redirectCallback(code, null, requestUri);

        assertThat(reply.status()).isEqualTo(expectedStatus);
        assertThat(reply.location()).isNull();
        assertThat(reply.contentType()).isNull();
        assertThat(text(reply)).isEqualTo(expectedText);
        assertThat(service.tokens(DEFAULT_OWNER)).isEmpty();
    }

    /** Each state decodes to the owner the tokens are stored for and the Location of the status-0 reply. */
    @ParameterizedTest(name = "[{index}] state={0}")
    @CsvSource(nullValues = "NULL", value = {
        "NULL, default, NULL",
        "'', default, NULL",
        "caller-state, default, NULL",
        ":onCompleteRedirectTo=, default, NULL",
        ":resourceOwnerId=alice, alice, NULL",
        ":onCompleteRedirectTo=http://localhost:8081/web/loginDone, default, "
                + "http://localhost:8081/web/loginDone?authorizationStatus=0",
        ":onCompleteRedirectTo=http://localhost:8081/web/loginDone?lang=en, default, "
                + "http://localhost:8081/web/loginDone?lang=en&authorizationStatus=0",
        "caller-state:resourceOwnerId=alice:onCompleteRedirectTo=http://localhost:8081/web/loginDone, alice, "
                + "http://localhost:8081/web/loginDone?authorizationStatus=0",
        ":onCompleteRedirectTo=http://localhost:8081/web/loginDone:resourceOwnerId=bob, bob, "
                + "http://localhost:8081/web/loginDone?authorizationStatus=0",
        ":resourceOwnerId=:onCompleteRedirectTo=http://localhost:8081/done, '', "
                + "http://localhost:8081/done?authorizationStatus=0"
    })
    void redirectCallbackStoresTheTokensForTheStatesOwnerAndRedirectsWithStatus0(String state,
            String expectedOwner, String expectedLocation) {
        when(client.requestToken(CODE)).thenReturn(json(200, tokenBody(ACCESS, REFRESH)));

        Reply reply = service.redirectCallback(CODE, state, "/redirectUrl?code=" + CODE);

        assertThat(reply.location()).isEqualTo(expectedLocation);
        assertThat(reply.contentType()).isNull();
        if (expectedLocation == null) {
            assertThat(reply.status()).isEqualTo(200);
            assertThat(text(reply)).isEqualTo(ACCESS_TOKEN_RETRIEVED);
        } else {
            assertThat(reply.status()).isEqualTo(302);
            assertThat(reply.body()).isEmpty();
        }
        assertThat(service.tokens(expectedOwner)).contains(new TokenSet(ACCESS, REFRESH, "3600", "bearer"));
        if (!DEFAULT_OWNER.equals(expectedOwner)) {
            assertThat(service.tokens(DEFAULT_OWNER)).isEmpty();
        }
        assertThat(serviceEvents()).isEmpty();
        verify(client).requestToken(CODE);
        verifyNoMoreInteractions(client);
    }

    static Stream<Arguments> tokenTypes() {
        String tokens = "\"access_token\":\"" + ACCESS + "\",\"refresh_token\":\"" + REFRESH + "\"";
        return Stream.of(
                Arguments.of("{" + tokens + ",\"token_type\":\"bearer\"}", "bearer"),
                Arguments.of("{" + tokens + ",\"token_type\":7}", "7"),
                Arguments.of("{" + tokens + ",\"token_type\":true}", "true"),
                Arguments.of("{" + tokens + ",\"token_type\":{\"scheme\":\"bearer\"}}", "{\"scheme\":\"bearer\"}"),
                Arguments.of("{" + tokens + ",\"token_type\":[\"bearer\"]}", "[\"bearer\"]"),
                Arguments.of("{" + tokens + ",\"token_type\":null}", null),
                Arguments.of("{" + tokens + "}", null),
                Arguments.of("[{" + tokens + ",\"token_type\":\"bearer\"}]", null),
                Arguments.of("tokens: " + tokens + ", token_type=bearer", null));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("tokenTypes")
    void redirectCallbackStoresTheTokenTypeTheCustomExtractorReads(String body, String expectedTokenType) {
        when(client.requestToken(CODE)).thenReturn(json(200, body));

        Reply reply = service.redirectCallback(CODE, null, "/redirectUrl?code=" + CODE);

        assertThat(reply.status()).isEqualTo(200);
        assertThat(service.tokens(DEFAULT_OWNER)).contains(new TokenSet(ACCESS, REFRESH, null, expectedTokenType));
    }

    // ---------------------------------------------------------------- helpers

    private static BoxProperties properties(String clientId) {
        return new BoxProperties(clientId, CLIENT_SECRET, AUTHORIZATION_URL, LOCAL_AUTHORIZATION_URL, REDIRECT_URL,
                TOKEN_URL, API_BASE_URL, LOGIN_REDIRECT_LOCATION, "keystore.jks", STORE_PASSWORD, STORE_PASSWORD,
                "trust-store", STORE_PASSWORD);
    }

    /** A Box answer with {@code Content-Type: application/json} and a UTF-8 body. */
    private static RawResponse json(int status, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new RawResponse(status, headers, body.getBytes(UTF_8));
    }

    /** A 200 Box answer with the given {@code Content-Type} header, none when {@code null}. */
    private static RawResponse withContentType(String contentType, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        if (contentType != null) {
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        }
        return new RawResponse(200, headers, body);
    }

    /** A single-line Box token response, the shape Mule's default extractors read. */
    private static String tokenBody(String accessToken, String refreshToken) {
        return "{\"access_token\":\"" + accessToken + "\",\"expires_in\":3600,\"restricted_to\":[],"
                + "\"refresh_token\":\"" + refreshToken + "\",\"token_type\":\"bearer\"}";
    }

    private static String text(Reply reply) {
        return new String(reply.body(), UTF_8);
    }

    /** The events {@link BoxOAuthService} logged, in order; other loggers' events reaching the root are ignored. */
    private List<ILoggingEvent> serviceEvents() {
        return appender.list.stream()
                .filter(event -> BoxOAuthService.class.getName().equals(event.getLoggerName()))
                .toList();
    }

    /** Every formatted message and throwable message the service logged, so token values can be searched for. */
    private String loggedText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : serviceEvents()) {
            text.append(event.getFormattedMessage()).append('\n');
            Optional.ofNullable(event.getThrowableProxy()).ifPresent(proxy -> text.append(proxy.getMessage()));
        }
        return text.toString();
    }
}
