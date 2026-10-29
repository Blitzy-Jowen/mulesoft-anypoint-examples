/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforceFailureClassifier;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforcePartnerClient;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforcePartnerClient.Session;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforcePartnerClient.SessionSource;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforceTokenClient;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforceTokenClient.TokenEndpointException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforceTokenClient.TokenResponse;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.UpstreamCallExecutor;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.UpstreamSystem;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.config.SalesforceOAuthProperties;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.config.SalesforceProperties;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamRateLimitException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamUnavailableException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.service.SalesforceOAuthService.AuthorizeOutcome;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.support.LoopbackHttpStub;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.support.LoopbackHttpStub.Ending;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.support.LoopbackHttpStub.Reply;
import com.sforce.ws.ConnectionException;

/**
 * Unit test of {@link SalesforceOAuthService}, the authorization-code flow of {@code salesforce-oauth.xml}
 * ({@code sfdc:config-with-oauth}, {@code sfdc:oauth-callback-config} and {@code sfdc:authorize display="PAGE"}),
 * against a Mockito-mocked {@link SalesforceTokenClient} and {@link ContactQueryService}; no Spring context, external
 * network or credentials.
 *
 * <p>It fixes the flow contract: the authorize redirect built from the original connector settings, with the
 * {@code state} of the authorization it starts and the cookie that binds it to the browser, the code exchange on
 * {@code /oauth2callback} that stores the token and answers with the contact query text, the session supplied to
 * the Partner API client, and the refresh grant that renews it. Every failure branch is pinned: a callback without a
 * code, a callback with a code but without the state and cookie of an authorization still pending (unsolicited,
 * mismatched, reused, expired or forgotten), a rejected or unreachable token endpoint, an incomplete token response,
 * a refused refresh that clears the token, and the upstream failures that pass through unchanged and keep it. A
 * {@link MutableClock} lets a pending authorization expire without waiting.
 *
 * <p>One case leaves the mocked query service aside and drives the real {@link ContactQueryService} and SC-15
 * {@link SalesforcePartnerClient} against a local WireMock Partner API, so the session each completed grant stores is
 * observed on the SOAP requests themselves. Another leaves both mocks aside and adds the real
 * {@link SalesforceTokenClient} against a {@link LoopbackHttpStub} token endpoint, so a refresh whose error body is cut
 * off in transit is observed failing as unavailable through every layer the application runs.
 */
@ExtendWith(MockitoExtension.class)
class SalesforceOAuthServiceTest {

    private static final String CODE = "abc";

    private static final String QUERY_TEXT = "Salesforce query returned 3 contacts.";

    /** A later {@code /} request's answer, distinct from the callback's so it is seen to come from a fresh query. */
    private static final String LATER_QUERY_TEXT = "Salesforce query returned 2 contacts.";

    /** The flow's answer when the real contact query returns one record. */
    private static final String ONE_CONTACT_TEXT = "Salesforce query returned 1 contacts.";

    private static final String INSTANCE_URL = "https://na1.salesforce.com";

    private static final String SOAP_ENDPOINT = "https://na1.salesforce.com/services/Soap/u/65.0";

    /** Identity URL of an invented org and user, joined from parts so no account-identifier literal appears. */
    private static final String IDENTITY_URL = "https://login.salesforce.com/id/" + "00D" + "000000000001" + "/"
            + "005" + "000000000001";

    private static final String ISSUED_AT = "1700000000000";

    private static final String TOKEN_TYPE = "Bearer";

    private static final String SCOPE = "api refresh_token";

    private static final String REDIRECT_URI = "http://localhost:8081/oauth2callback";

    private static final String AUTHORIZE_ENDPOINT = "https://login.salesforce.com/services/oauth2/authorize";

    private static final String ENCODED_REDIRECT_URI = "http%3A%2F%2Flocalhost%3A8081%2Foauth2callback";

    private static final String AUTHORIZATION_URL = AUTHORIZE_ENDPOINT
            + "?response_type=code&client_id=test-key&redirect_uri=" + ENCODED_REDIRECT_URI + "&display=page";

    private static final String TOKEN_PATH = "/services/oauth2/token";

    /** A fixed {@code state} for the URL-building cases, which take the state as given. */
    private static final String STATE = "test-state";

    /** 32 random bytes in base64url without padding: 43 characters of the URL- and cookie-safe alphabet. */
    private static final String STATE_PATTERN = "[A-Za-z0-9_-]{43}";

    /** The default {@code sfdc.oauth.state-ttl}. */
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private static final String NO_STATE = "Salesforce authorization failed: no state parameter";

    private static final String NO_STATE_COOKIE = "Salesforce authorization failed: no state cookie";

    private static final String STATE_MISMATCH =
            "Salesforce authorization failed: state does not match this browser's authorization request";

    private static final String UNKNOWN_STATE = "Salesforce authorization failed: unknown or already used state";

    private static final String EXPIRED_STATE = "Salesforce authorization failed: authorization request expired";

    private static final String INVALID_STATE_TTL =
            "sfdc.oauth.state-ttl must be a positive duration of at most 400 days";

    /**
     * A 400 whose status line, headers and first body bytes arrive while the rest of its declared 200-byte body
     * never does: the start of Salesforce's error object, cut off in transit.
     */
    private static final String ERROR_HEAD_AND_PARTIAL_BODY = "HTTP/1.1 400 Bad Request\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 200\r\n"
            + "\r\n"
            + "{\"error\":\"invalid_grant\",";

    @Mock
    private SalesforceTokenClient tokenClient;

    @Mock
    private ContactQueryService contactQueryService;

    private SalesforceOAuthProperties properties;

    private final MutableClock clock = new MutableClock(Instant.parse("2024-01-02T03:04:05Z"));

    private SalesforceOAuthService service;

    @BeforeEach
    void setUp() {
        properties = new SalesforceOAuthProperties();
        properties.setKey("test-key");
        properties.setSecret("test-secret");
        service = new SalesforceOAuthService(properties, tokenClient, contactQueryService, clock);
    }

    @Test
    void authorizationUrlAndRedirectUriFollowTheOriginalConnectorSettings() throws Exception {
        assertThat(service.redirectUri()).isEqualTo(REDIRECT_URI);
        assertThat(service.authorizationUrl(STATE))
                .hasToString(AUTHORIZATION_URL + "&state=" + STATE)
                .isEqualTo(URI.create(AUTHORIZATION_URL + "&state=" + STATE));
    }

    @Test
    void authorizationUrlJoinsAConfiguredQueryStringWithAnAmpersand() throws Exception {
        properties.getOauth().setAuthorizationUrl("  " + AUTHORIZE_ENDPOINT + "?prompt=login  ");

        assertThat(service.authorizationUrl(STATE)).hasToString(AUTHORIZE_ENDPOINT
                + "?prompt=login&response_type=code&client_id=test-key&redirect_uri=" + ENCODED_REDIRECT_URI
                + "&display=page&state=" + STATE);
    }

    @ParameterizedTest
    @CsvSource({", ''", "'key with space&more=1', key+with+space%26more%3D1"})
    void authorizationUrlFormEncodesTheConsumerKeyAndSendsAnUnconfiguredOneEmpty(String key, String encodedKey)
            throws Exception {
        properties.setKey(key);

        assertThat(service.authorizationUrl(STATE)).hasToString(AUTHORIZE_ENDPOINT + "?response_type=code&client_id="
                + encodedKey + "&redirect_uri=" + ENCODED_REDIRECT_URI + "&display=page&state=" + STATE);
    }

    @Test
    void authorizationUrlFormEncodesTheStateAndRequiresOne() throws Exception {
        assertThat(service.authorizationUrl("a b&c=d")).hasToString(AUTHORIZATION_URL + "&state=a+b%26c%3Dd");
        assertThat(assertThrows(NullPointerException.class, () -> service.authorizationUrl(null)))
                .hasMessage("state");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void authorizationUrlFailsWhenNoAuthorizationEndpointIsConfigured(String configured) throws Exception {
        properties.getOauth().setAuthorizationUrl(configured);

        IllegalStateException direct = assertThrows(IllegalStateException.class,
                () -> service.authorizationUrl(STATE));
        IllegalStateException viaAuthorize = assertThrows(IllegalStateException.class, service::authorize);

        assertThat(direct).hasMessage("sfdc.oauth.authorization-url is not configured");
        assertThat(viaAuthorize).hasMessage("sfdc.oauth.authorization-url is not configured");
        assertThat(pendingAuthorizations(service)).as("authorizations remembered").isZero();
        verifyNoInteractions(tokenClient, contactQueryService);
    }

    @Test
    void noTokenIsStoredBeforeAnyCallback() throws Exception {
        assertThat(service.isAuthorized()).isFalse();
    }

    @Test
    void authorizeWithoutATokenRedirectsWithAFreshStateInTheUrlAndInTheStateCookie() throws Exception {
        AuthorizeOutcome outcome = service.authorize();

        assertThat(outcome.isRedirect()).isTrue();
        ResponseCookie cookie = outcome.stateCookie();
        String state = cookie.getValue();
        assertThat(state).matches(STATE_PATTERN);
        assertThat(Base64.getUrlDecoder().decode(state)).hasSize(32);
        assertThat(outcome.redirectLocation())
                .isEqualTo(service.authorizationUrl(state))
                .hasToString(AUTHORIZATION_URL + "&state=" + state);
        assertThat(outcome.body()).isNull();
        assertThat(cookie.getName()).isEqualTo(SalesforceOAuthService.STATE_COOKIE).isEqualTo("sfdc_oauth_state");
        assertThat(cookie.getPath()).isEqualTo("/oauth2callback");
        assertThat(cookie.getMaxAge()).isEqualTo(STATE_TTL);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isFalse();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getDomain()).isNull();
        assertThat(pendingAuthorizations(service)).isEqualTo(1);
        verifyNoInteractions(tokenClient, contactQueryService);
    }

    @Test
    void everyRedirectStartsAnAuthorizationWithADistinctState() throws Exception {
        Set<String> states = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            AuthorizeOutcome outcome = service.authorize();
            String state = outcome.stateCookie().getValue();
            assertThat(state).matches(STATE_PATTERN);
            assertThat(outcome.redirectLocation()).hasToString(AUTHORIZATION_URL + "&state=" + state);
            states.add(state);
        }

        assertThat(states).hasSize(200);
        assertThat(pendingAuthorizations(service)).isEqualTo(200);
    }

    @ParameterizedTest(name = "sfdc.oauth.callback.path {0} -> Path {1}")
    @CsvSource({"oauth2callback, /oauth2callback", "/oauth2callback, /oauth2callback",
            "sfdc/callback, /sfdc/callback", "'', /", ", /"})
    void stateCookieIsScopedToTheCallbackPath(String configuredPath, String cookiePath) throws Exception {
        properties.getOauth().getCallback().setPath(configuredPath);

        AuthorizeOutcome outcome = service.authorize();

        assertThat(outcome.stateCookie().getPath()).isEqualTo(cookiePath);
        assertThat(service.redirectUri()).isEqualTo("http://localhost:8081" + cookiePath);
    }

    @ParameterizedTest(name = "sfdc.oauth.state-ttl {0} ms -> Max-Age {1}")
    @CsvSource({"600000, 600", "90000, 90", "1500, 2", "1, 1", "34560000000, 34560000"})
    void stateCookieLivesForTheStateLifetimeRoundedUpToWholeSeconds(long ttlMillis, long maxAgeSeconds)
            throws Exception {
        properties.getOauth().setStateTtl(Duration.ofMillis(ttlMillis));

        ResponseCookie cookie = service.authorize().stateCookie();

        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofSeconds(maxAgeSeconds));
    }

    @ParameterizedTest(name = "{0} -> Secure {1}")
    @CsvSource({"https://app.example.invalid:8443/oauth2callback, true",
            "HTTPS://app.example.invalid/oauth2callback, true",
            "http://localhost:8081/oauth2callback, false"})
    void stateCookieIsSecureOnlyForAnHttpsRedirectUri(String redirectUri, boolean secure) throws Exception {
        properties.getOauth().setCallback(new SalesforceOAuthProperties.Callback() {
            @Override
            public String redirectUri() {
                return redirectUri;
            }
        });

        assertThat(service.authorize().stateCookie().isSecure()).isEqualTo(secure);
    }

    static Stream<Duration> invalidStateLifetimes() {
        return Stream.of(null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(400).plusNanos(1));
    }

    @ParameterizedTest(name = "sfdc.oauth.state-ttl {0}")
    @MethodSource("invalidStateLifetimes")
    void authorizeFailsOnAStateLifetimeThatIsNotPositiveOrLongerThan400DaysAndRemembersNothing(Duration ttl)
            throws Exception {
        properties.getOauth().setStateTtl(ttl);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, service::authorize);

        assertThat(thrown).hasMessage(INVALID_STATE_TTL);
        assertThat(pendingAuthorizations(service)).isZero();
        verifyNoInteractions(tokenClient, contactQueryService);
    }

    @Test
    void callbackExchangesTheCodeStoresTheTokenAndAnswersWithTheQueryText() throws Exception {
        String body = authorizeWith(token("access-1", "refresh-1"));

        assertThat(body).isEqualTo(QUERY_TEXT);
        verify(tokenClient).exchangeCode(CODE, REDIRECT_URI);
        verify(contactQueryService, times(1)).queryContacts();
        verifyNoMoreInteractions(tokenClient, contactQueryService);
        assertThat(service.isAuthorized()).isTrue();
    }

    @Test
    void authorizeWithAStoredTokenRunsTheQueryWithoutAnotherTokenRequest() throws Exception {
        authorizeWith(token("access-1", "refresh-1"));
        when(contactQueryService.queryContacts()).thenReturn(LATER_QUERY_TEXT);

        AuthorizeOutcome outcome = service.authorize();

        assertThat(outcome.isRedirect()).isFalse();
        assertThat(outcome.redirectLocation()).isNull();
        assertThat(outcome.body()).isEqualTo(LATER_QUERY_TEXT);
        assertThat(outcome).isEqualTo(AuthorizeOutcome.completed(LATER_QUERY_TEXT));
        verify(contactQueryService, times(2)).queryContacts();
        verify(tokenClient, times(1)).exchangeCode(CODE, REDIRECT_URI);
        verifyNoMoreInteractions(tokenClient, contactQueryService);
    }

    @ParameterizedTest
    @MethodSource("queryFailures")
    void authorizeWithAStoredTokenPropagatesAQueryFailureUnchanged(Exception failure) throws Exception {
        authorizeWith(token("access-1", "refresh-1"));
        doThrow(failure).when(contactQueryService).queryContacts();

        Exception thrown = assertThrows(Exception.class, service::authorize);

        assertSame(failure, thrown);
        assertThat(service.isAuthorized()).isTrue();
        verify(contactQueryService, times(2)).queryContacts();
    }

    @ParameterizedTest
    @MethodSource("queryFailures")
    void callbackPropagatesAQueryFailureUnchangedAndKeepsTheExchangedToken(Exception failure) throws Exception {
        String state = initiate(service);
        when(tokenClient.exchangeCode(CODE, REDIRECT_URI)).thenReturn(token("access-1", "refresh-1"));
        when(contactQueryService.queryContacts()).thenThrow(failure);

        Exception thrown = assertThrows(Exception.class,
                () -> service.completeAuthorization(CODE, null, null, state, state));

        assertSame(failure, thrown);
        assertThat(service.isAuthorized()).isTrue();
        assertThat(service.current()).isEqualTo(new Session("access-1", SOAP_ENDPOINT));
    }

    @ParameterizedTest
    @ValueSource(strings = {INSTANCE_URL, INSTANCE_URL + "/", "  " + INSTANCE_URL + "/  "})
    void currentSuppliesTheAccessTokenAndThePartnerEndpointOfTheInstance(String instanceUrl) throws Exception {
        authorizeWith(response("access-1", "refresh-1", instanceUrl));

        Session session = service.current();

        assertThat(session).isEqualTo(new Session("access-1", SOAP_ENDPOINT));
        assertThat(session.serviceEndpoint()).endsWith(SalesforceOAuthService.SOAP_ENDPOINT_PATH);
    }

    @Test
    void currentWithoutATokenFailsAsASalesforceAuthenticationFailure() throws Exception {
        UpstreamAuthenticationException failure = assertThrows(UpstreamAuthenticationException.class,
                service::current);

        assertThat(failure.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
        assertThat(failure).hasMessage("Salesforce is not authorized").hasNoCause();
        verifyNoInteractions(tokenClient, contactQueryService);
    }

    @Test
    void refreshStoresEachRenewedAccessTokenAndKeepsTheOriginalRefreshToken() throws Exception {
        authorizeWith(token("access-1", "refresh-1"));
        when(tokenClient.refresh("refresh-1")).thenReturn(token("access-2", null), token("access-3", null));

        assertThat(service.refresh()).isEqualTo(new Session("access-2", SOAP_ENDPOINT));
        assertThat(service.current()).isEqualTo(new Session("access-2", SOAP_ENDPOINT));
        assertThat(service.refresh()).isEqualTo(new Session("access-3", SOAP_ENDPOINT));
        assertThat(service.current()).isEqualTo(new Session("access-3", SOAP_ENDPOINT));

        verify(tokenClient, times(2)).refresh("refresh-1");
        assertThat(service.isAuthorized()).isTrue();
    }

    @Test
    void refreshAdoptsTheRefreshTokenAndInstanceUrlAResponseSuppliesAndKeepsThemOtherwise() throws Exception {
        String otherEndpoint = "https://na2.salesforce.com" + SalesforceOAuthService.SOAP_ENDPOINT_PATH;
        authorizeWith(token("access-1", "refresh-1"));
        when(tokenClient.refresh("refresh-1"))
                .thenReturn(response("access-2", "refresh-2", "https://na2.salesforce.com"));
        when(tokenClient.refresh("refresh-2"))
                .thenReturn(response("access-3", "   ", null), response("access-4", null, ""));

        assertThat(service.refresh()).isEqualTo(new Session("access-2", otherEndpoint));
        assertThat(service.refresh()).isEqualTo(new Session("access-3", otherEndpoint));
        assertThat(service.refresh()).isEqualTo(new Session("access-4", otherEndpoint));

        verify(tokenClient).refresh("refresh-1");
        verify(tokenClient, times(2)).refresh("refresh-2");
    }

    @Test
    void refreshKeepsAGrantCompletedWhileTheRefreshExchangeWasInFlight() throws Exception {
        String instanceB = "https://na2.salesforce.com";
        Session grantB = new Session("access-b", instanceB + SalesforceOAuthService.SOAP_ENDPOINT_PATH);
        // A second browser starts its authorization at / before the first one completes.
        String stateB = initiate(service);
        authorizeWith(token("access-1", "refresh-1"));
        when(tokenClient.exchangeCode("code-b", REDIRECT_URI))
                .thenReturn(response("access-b", "refresh-b", instanceB));
        when(tokenClient.refresh("refresh-1")).thenAnswer(invocation -> {
            // A browser completes a new authorization while Salesforce is still answering the refresh of grant A.
            assertThat(service.completeAuthorization("code-b", null, null, stateB, stateB)).isEqualTo(QUERY_TEXT);
            return token("access-2", null);
        });
        when(tokenClient.refresh("refresh-b")).thenReturn(response("access-b2", null, null));

        assertThat(service.refresh()).isEqualTo(grantB);
        assertThat(service.current()).isEqualTo(grantB);
        assertThat(service.isAuthorized()).isTrue();
        // Grant B was kept whole: its own refresh token renews it, on its own instance.
        assertThat(service.refresh()).isEqualTo(new Session("access-b2", grantB.serviceEndpoint()));

        verify(tokenClient).refresh("refresh-1");
        verify(tokenClient).refresh("refresh-b");
        verify(tokenClient).exchangeCode("code-b", REDIRECT_URI);
        verify(contactQueryService, times(2)).queryContacts();
    }

    /**
     * Two successful authorizations whose sessions both stay valid, driven through the real
     * {@link ContactQueryService} and SC-15 {@link SalesforcePartnerClient} in session mode, with this service as its
     * {@link SessionSource}, against a WireMock Partner API serving grant A's instance under {@code /a} and grant B's
     * under {@code /b}: the callback of each grant, and every later {@code /} request, queries with the latest grant's
     * session on its instance, and no refresh grant is spent to get there.
     */
    @Test
    void eachCompletedGrantDrivesThePartnerApiCallsThatFollowIt() throws Exception {
        WireMockServer partnerApi = new WireMockServer(options().dynamicPort());
        partnerApi.start();
        try {
            String base = "http://localhost:" + partnerApi.port();
            String instanceA = "/a" + SalesforceOAuthService.SOAP_ENDPOINT_PATH;
            String instanceB = "/b" + SalesforceOAuthService.SOAP_ENDPOINT_PATH;
            partnerApi.stubFor(post(urlEqualTo(instanceA)).willReturn(oneContactQueryResponse()));
            partnerApi.stubFor(post(urlEqualTo(instanceB)).willReturn(oneContactQueryResponse()));
            when(tokenClient.exchangeCode("code-a", REDIRECT_URI))
                    .thenReturn(response("access-a", "refresh-a", base + "/a"));
            when(tokenClient.exchangeCode("code-b", REDIRECT_URI))
                    .thenReturn(response("access-b", "refresh-b", base + "/b"));
            SalesforceOAuthService flow = sessionModeFlow(base);
            // Two browsers start their authorization at / before either one completes.
            String stateA = initiate(flow);
            String stateB = initiate(flow);

            assertThat(flow.completeAuthorization("code-a", null, null, stateA, stateA)).isEqualTo(ONE_CONTACT_TEXT);
            assertThat(partnerApi.findAll(postRequestedFor(urlEqualTo(instanceA))))
                    .singleElement()
                    .satisfies(request -> assertContactQueryUnder(request, "access-a", "access-b"));
            assertThat(partnerApi.findAll(postRequestedFor(urlEqualTo(instanceB)))).isEmpty();

            assertThat(flow.completeAuthorization("code-b", null, null, stateB, stateB)).isEqualTo(ONE_CONTACT_TEXT);
            assertThat(flow.authorize()).isEqualTo(AuthorizeOutcome.completed(ONE_CONTACT_TEXT));

            // Grant A's session was never rejected, yet nothing reached its instance after grant B completed.
            assertThat(partnerApi.findAll(postRequestedFor(urlEqualTo(instanceA)))).hasSize(1);
            assertThat(partnerApi.findAll(postRequestedFor(urlEqualTo(instanceB))))
                    .hasSize(2)
                    .allSatisfy(request -> assertContactQueryUnder(request, "access-b", "access-a"));
            assertThat(partnerApi.getAllServeEvents()).hasSize(3);
            verify(tokenClient, never()).refresh(any());
        } finally {
            partnerApi.stop();
        }
    }

    /**
     * A rejected session whose refresh is cut off in transit, driven through the real {@link SalesforceTokenClient},
     * {@link ContactQueryService} and SC-15 {@link SalesforcePartnerClient} behind real SC-9 executors: a WireMock
     * Partner API answers the callback's query and then rejects the next one with {@code INVALID_SESSION_ID}, and a
     * {@link LoopbackHttpStub} token endpoint answers the code exchange and then sends the refresh grant's error
     * status and the start of its body before it stalls past the read timeout or resets the connection. The refresh
     * fails as unavailable with the socket's own message, the 503, not as a refused grant, the 502: the stored token is
     * kept, so the next {@code /} does not redirect, and neither the refresh nor the query is retried.
     */
    @ParameterizedTest(name = "refresh error body {0}")
    @MethodSource("refreshErrorBodyFailures")
    void refreshWhoseErrorBodyIsCutOffFailsAsUnavailableKeepsTheTokenAndIsNotRetried(Ending ending, Duration timeout,
            Class<? extends IOException> socketFailure, String socketMessage) throws Exception {
        WireMockServer partnerApi = new WireMockServer(options().dynamicPort());
        partnerApi.start();
        try (LoopbackHttpStub tokenEndpoint = LoopbackHttpStub.start(
                new Reply(codeExchangeAnswer(partnerApi.baseUrl()), Ending.CLOSE),
                new Reply(ERROR_HEAD_AND_PARTIAL_BODY, ending))) {
            String queryPath = SalesforceOAuthService.SOAP_ENDPOINT_PATH;
            partnerApi.stubFor(post(urlEqualTo(queryPath)).inScenario("session")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(oneContactQueryResponse())
                    .willSetStateTo("expired"));
            partnerApi.stubFor(post(urlEqualTo(queryPath)).inScenario("session")
                    .whenScenarioStateIs("expired")
                    .willReturn(invalidSessionFault()));
            properties.getOauth().setAccessTokenUrl(tokenEndpoint.baseUrl() + TOKEN_PATH);
            SalesforceOAuthService flow = sessionModeFlow(partnerApi.baseUrl(), realTokenClient(timeout));
            String state = initiate(flow);
            assertThat(flow.completeAuthorization(CODE, null, null, state, state)).isEqualTo(ONE_CONTACT_TEXT);
            Session authorized = flow.current();

            UpstreamUnavailableException thrown = assertThrows(UpstreamUnavailableException.class, flow::authorize);

            assertThat(thrown.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
            assertThat(thrown).hasMessage(socketMessage);
            assertThat(thrown.getCause()).isInstanceOf(socketFailure).hasMessage(socketMessage);
            assertThat(flow.isAuthorized()).isTrue();
            assertThat(flow.current()).isEqualTo(authorized);
            assertThat(tokenEndpoint.requests())
                    .satisfiesExactly(
                            exchange -> assertThat(exchange).contains("grant_type=authorization_code"),
                            refresh -> assertThat(refresh).contains("grant_type=refresh_token")
                                    .contains("refresh_token=refresh-1"));
            assertThat(partnerApi.findAll(postRequestedFor(urlEqualTo(queryPath))))
                    .hasSize(2)
                    .allSatisfy(request -> assertThat(request.getBodyAsString()).contains(">access-1<"));
        } finally {
            partnerApi.stop();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void refreshWithoutAStoredRefreshTokenClearsTheTokenAndNeverCallsSalesforce(String refreshToken)
            throws Exception {
        authorizeWith(token("access-1", refreshToken));

        UpstreamAuthenticationException failure = assertThrows(UpstreamAuthenticationException.class,
                service::refresh);

        assertThat(failure.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
        assertThat(failure).hasMessage("no refresh token").hasNoCause();
        assertThat(service.isAuthorized()).isFalse();
        assertThrows(UpstreamAuthenticationException.class, service::current);
        verify(tokenClient, never()).refresh(any());
    }

    @Test
    void refreshWithoutAnyTokenFailsAndNeverCallsSalesforce() throws Exception {
        UpstreamAuthenticationException failure = assertThrows(UpstreamAuthenticationException.class,
                service::refresh);

        assertThat(failure.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
        assertThat(failure).hasMessage("no refresh token").hasNoCause();
        assertThat(service.isAuthorized()).isFalse();
        verifyNoInteractions(tokenClient, contactQueryService);
    }

    @ParameterizedTest
    @CsvSource({
            "400, invalid_grant, expired access/refresh token, expired access/refresh token",
            "400, invalid_grant, , invalid_grant",
            "400, invalid_grant, '   ', invalid_grant",
            "401, , , HTTP 401"})
    void refreshRefusedByTheTokenEndpointRequiresAuthorizationAgain(int status, String error, String description,
            String expectedMessage) throws Exception {
        authorizeWith(token("access-1", "refresh-1"));
        TokenEndpointException refused = new TokenEndpointException(status, error, description);
        when(tokenClient.refresh("refresh-1")).thenThrow(refused);

        UpstreamAuthenticationException failure = assertThrows(UpstreamAuthenticationException.class,
                service::refresh);

        assertThat(failure.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
        assertThat(failure).hasMessage(expectedMessage);
        assertSame(refused, failure.getCause());
        assertThat(service.isAuthorized()).isFalse();
        AuthorizeOutcome next = service.authorize();
        assertThat(next.isRedirect()).isTrue();
        assertThat(next.redirectLocation())
                .hasToString(AUTHORIZATION_URL + "&state=" + next.stateCookie().getValue());
        verify(tokenClient, times(1)).refresh("refresh-1");
    }

    @ParameterizedTest
    @MethodSource("uncheckedRefreshFailures")
    void refreshRethrowsOtherUncheckedFailuresUnchangedAndKeepsTheToken(RuntimeException failure) throws Exception {
        authorizeWith(token("access-1", "refresh-1"));
        when(tokenClient.refresh("refresh-1")).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, service::refresh);

        assertSame(failure, thrown);
        assertThat(service.isAuthorized()).isTrue();
        assertThat(service.current()).isEqualTo(new Session("access-1", SOAP_ENDPOINT));
        verify(tokenClient, times(1)).refresh("refresh-1");
    }

    @ParameterizedTest
    @MethodSource("checkedRefreshFailures")
    void refreshWrapsACheckedFailureAndKeepsTheToken(Exception failure, String expectedMessage) throws Exception {
        authorizeWith(token("access-1", "refresh-1"));
        when(tokenClient.refresh("refresh-1")).thenAnswer(invocation -> {
            throw failure;
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class, service::refresh);

        assertThat(thrown).hasMessage(expectedMessage);
        assertSame(failure, thrown.getCause());
        assertThat(service.current()).isEqualTo(new Session("access-1", SOAP_ENDPOINT));
        verify(tokenClient, times(1)).refresh("refresh-1");
    }

    @ParameterizedTest
    @MethodSource("refreshResponsesWithoutAccessToken")
    void refreshResponseWithoutAnAccessTokenFailsAndKeepsTheToken(TokenResponse response) throws Exception {
        authorizeWith(token("access-1", "refresh-1"));
        when(tokenClient.refresh("refresh-1")).thenReturn(response);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, service::refresh);

        assertThat(thrown).hasMessage("Salesforce token response is missing access_token");
        assertThat(service.current()).isEqualTo(new Session("access-1", SOAP_ENDPOINT));
    }

    @ParameterizedTest
    @CsvSource({
            "access_denied, , Salesforce authorization failed: access_denied",
            "access_denied, '   ', Salesforce authorization failed: access_denied",
            "access_denied, end-user denied authorization, "
                    + "Salesforce authorization failed: access_denied: end-user denied authorization",
            "'   ', end-user denied authorization, Salesforce authorization failed: no authorization code"})
    void callbackWithAnAuthorizationErrorFailsWithoutAnExchange(String error, String description,
            String expectedMessage) throws Exception {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(null, error, description, null, null));

        assertThat(thrown).hasMessage(expectedMessage);
        assertThat(thrown.getMessage()).startsWith("Salesforce authorization failed: ");
        verifyNoInteractions(tokenClient, contactQueryService);
        assertThat(service.isAuthorized()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void callbackWithoutACodeFailsWithoutAnExchange(String code) throws Exception {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(code, null, null, null, null));

        assertThat(thrown).hasMessage("Salesforce authorization failed: no authorization code");
        verifyNoInteractions(tokenClient, contactQueryService);
        assertThat(service.isAuthorized()).isFalse();
    }

    /**
     * A refused login that Salesforce sends back with the state and cookie of a pending authorization gets the same
     * message as one without them, and that authorization is discarded, so its state cannot complete a login later.
     */
    @Test
    void callbackWithAnAuthorizationErrorDiscardsTheMatchingPendingAuthorization() throws Exception {
        String state = initiate(service);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> service
                .completeAuthorization(null, "access_denied", "end-user denied authorization", state, state));
        IllegalStateException later = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(CODE, null, null, state, state));

        assertThat(refused)
                .hasMessage("Salesforce authorization failed: access_denied: end-user denied authorization");
        assertThat(later).hasMessage(UNKNOWN_STATE);
        assertThat(pendingAuthorizations(service)).isZero();
        verifyNoInteractions(tokenClient, contactQueryService);
        assertThat(service.isAuthorized()).isFalse();
    }

    /** The state and cookie a refused login carries, from a state the browser was issued and one issued elsewhere. */
    static Stream<StateCase> statesThatDoNotDiscard() {
        return Stream.of(
                new StateCase("cookie of another browser", (issued, other) -> issued, (issued, other) -> other, null),
                new StateCase("no state cookie", (issued, other) -> issued, (issued, other) -> null, null),
                new StateCase("no state parameter", (issued, other) -> null, (issued, other) -> issued, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("statesThatDoNotDiscard")
    void callbackWithAnAuthorizationErrorKeepsAPendingAuthorizationItDoesNotMatch(StateCase presented)
            throws Exception {
        String issued = initiate(service);
        String other = initiate(service);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> service.completeAuthorization(
                null, "access_denied", null, presented.state(issued, other), presented.cookie(issued, other)));

        assertThat(refused).hasMessage("Salesforce authorization failed: access_denied");
        assertThat(pendingAuthorizations(service)).isEqualTo(2);
        verifyNoInteractions(tokenClient, contactQueryService);
        assertThat(completeWithIssuedState(issued)).isEqualTo(QUERY_TEXT);
    }

    /**
     * Every way a callback with a code can fail to prove that this browser started a pending authorization, from a
     * state issued to the browser and one issued to another, with the reason it is refused.
     */
    static Stream<StateCase> unprovenStates() {
        return Stream.of(
                new StateCase("unsolicited: no state, no cookie", (issued, other) -> null, (issued, other) -> null,
                        NO_STATE),
                new StateCase("no state parameter", (issued, other) -> null, (issued, other) -> issued, NO_STATE),
                new StateCase("blank state parameter", (issued, other) -> "   ", (issued, other) -> issued, NO_STATE),
                new StateCase("no state cookie", (issued, other) -> issued, (issued, other) -> null, NO_STATE_COOKIE),
                new StateCase("empty state cookie", (issued, other) -> issued, (issued, other) -> "", NO_STATE_COOKIE),
                new StateCase("cookie of another browser", (issued, other) -> issued, (issued, other) -> other,
                        STATE_MISMATCH),
                new StateCase("state of another browser", (issued, other) -> other, (issued, other) -> issued,
                        STATE_MISMATCH),
                new StateCase("state differing in one character", (issued, other) -> lastCharacterChanged(issued),
                        (issued, other) -> issued, STATE_MISMATCH),
                new StateCase("forged state and matching cookie", (issued, other) -> "forged-state",
                        (issued, other) -> "forged-state", UNKNOWN_STATE));
    }

    /**
     * The unsolicited callback of the finding and its variants: refused with a reason before any token request, no
     * token stored, and the browser's own pending authorization left intact, so the real login still completes.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unprovenStates")
    void callbackWithACodeButWithoutProofOfAPendingAuthorizationFailsBeforeAnyTokenRequest(StateCase presented)
            throws Exception {
        String issued = initiate(service);
        String other = initiate(service);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> service.completeAuthorization(
                "attacker-code", null, null, presented.state(issued, other), presented.cookie(issued, other)));

        assertThat(refused).hasMessage(presented.reason());
        verifyNoInteractions(tokenClient, contactQueryService);
        assertThat(service.isAuthorized()).isFalse();
        assertThat(pendingAuthorizations(service)).isEqualTo(2);
        assertThat(completeWithIssuedState(issued)).isEqualTo(QUERY_TEXT);
        verify(tokenClient).exchangeCode(CODE, REDIRECT_URI);
        verify(tokenClient, never()).exchangeCode("attacker-code", REDIRECT_URI);
    }

    @Test
    void callbackCannotCompleteTheSameAuthorizationTwice() throws Exception {
        String state = initiate(service);
        assertThat(completeWithIssuedState(state)).isEqualTo(QUERY_TEXT);

        IllegalStateException replayed = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization("code-2", null, null, state, state));

        assertThat(replayed).hasMessage(UNKNOWN_STATE);
        verify(tokenClient, times(1)).exchangeCode(any(), any());
        verify(tokenClient, never()).exchangeCode("code-2", REDIRECT_URI);
        assertThat(service.current()).isEqualTo(new Session("access-1", SOAP_ENDPOINT));
    }

    @ParameterizedTest(name = "{0} ms after the state lifetime")
    @ValueSource(longs = {0, 1, 600_000})
    void callbackAfterTheStateLifetimeFailsBeforeAnyTokenRequestAndConsumesTheState(long millisLate)
            throws Exception {
        String state = initiate(service);
        clock.advance(STATE_TTL.plusMillis(millisLate));

        IllegalStateException expired = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(CODE, null, null, state, state));

        assertThat(expired).hasMessage(EXPIRED_STATE);
        assertThat(pendingAuthorizations(service)).isZero();
        verifyNoInteractions(tokenClient, contactQueryService);
        assertThat(service.isAuthorized()).isFalse();
    }

    @Test
    void callbackJustBeforeTheStateLifetimeEndsCompletes() throws Exception {
        String state = initiate(service);
        clock.advance(STATE_TTL.minusMillis(1));

        assertThat(completeWithIssuedState(state)).isEqualTo(QUERY_TEXT);
        assertThat(service.isAuthorized()).isTrue();
    }

    @Test
    void expiredAuthorizationsAreForgottenWhenTheNextOneStarts() throws Exception {
        String first = initiate(service);
        clock.advance(STATE_TTL);
        String second = initiate(service);

        IllegalStateException forgotten = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(CODE, null, null, first, first));

        assertThat(forgotten).hasMessage(UNKNOWN_STATE);
        assertThat(pendingAuthorizations(service)).isEqualTo(1);
        verifyNoInteractions(tokenClient, contactQueryService);
        assertThat(completeWithIssuedState(second)).isEqualTo(QUERY_TEXT);
    }

    @Test
    void pendingAuthorizationsAreBoundedAndTheOldestIsForgottenFirst() throws Exception {
        List<String> states = new ArrayList<>();
        for (int i = 0; i <= SalesforceOAuthService.MAX_PENDING_AUTHORIZATIONS; i++) {
            states.add(initiate(service));
        }

        assertThat(pendingAuthorizations(service)).isEqualTo(SalesforceOAuthService.MAX_PENDING_AUTHORIZATIONS);
        IllegalStateException forgotten = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(CODE, null, null, states.get(0), states.get(0)));
        assertThat(forgotten).hasMessage(UNKNOWN_STATE);
        verifyNoInteractions(tokenClient, contactQueryService);
        assertThat(completeWithIssuedState(states.get(1))).isEqualTo(QUERY_TEXT);
        assertThat(pendingAuthorizations(service))
                .isEqualTo(SalesforceOAuthService.MAX_PENDING_AUTHORIZATIONS - 1);
    }

    /** Browsers racing with the same state and cookie: exactly one exchanges its code, every other is refused. */
    @Test
    void concurrentCallbacksWithOneStateExchangeOnlyOnce() throws Exception {
        String state = initiate(service);
        when(tokenClient.exchangeCode(CODE, REDIRECT_URI)).thenReturn(token("access-1", "refresh-1"));
        when(contactQueryService.queryContacts()).thenReturn(QUERY_TEXT);
        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<String> outcomes = new ArrayList<>();
        try {
            List<Future<String>> calls = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return service.completeAuthorization(CODE, null, null, state, state);
                }));
            }
            start.countDown();
            for (Future<String> call : calls) {
                try {
                    outcomes.add(call.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException refused) {
                    outcomes.add(refused.getCause().getMessage());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).filteredOn(QUERY_TEXT::equals).hasSize(1);
        assertThat(outcomes).filteredOn(UNKNOWN_STATE::equals).hasSize(callers - 1);
        verify(tokenClient, times(1)).exchangeCode(CODE, REDIRECT_URI);
    }

    @ParameterizedTest
    @MethodSource("exchangeFailures")
    void callbackExchangeFailureIsRethrownUnchangedAndStoresNothing(RuntimeException failure) throws Exception {
        String state = initiate(service);
        when(tokenClient.exchangeCode(CODE, REDIRECT_URI)).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.completeAuthorization(CODE, null, null, state, state));

        assertSame(failure, thrown);
        assertThat(service.isAuthorized()).isFalse();
        verifyNoInteractions(contactQueryService);
    }

    @ParameterizedTest
    @MethodSource("incompleteCodeExchangeResponses")
    void callbackWithAnIncompleteTokenResponseFailsAndStoresNothing(TokenResponse response, String missingField)
            throws Exception {
        String state = initiate(service);
        when(tokenClient.exchangeCode(CODE, REDIRECT_URI)).thenReturn(response);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(CODE, null, null, state, state));

        assertThat(thrown).hasMessage("Salesforce token response is missing " + missingField);
        assertThat(service.isAuthorized()).isFalse();
        verifyNoInteractions(contactQueryService);
    }

    @Test
    void authorizeOutcomeTellsARedirectFromACompletedFlow() throws Exception {
        URI location = URI.create(AUTHORIZATION_URL + "&state=" + STATE);
        ResponseCookie cookie = ResponseCookie.from(SalesforceOAuthService.STATE_COOKIE, STATE).build();

        AuthorizeOutcome redirect = AuthorizeOutcome.redirect(location, cookie);
        AuthorizeOutcome completed = AuthorizeOutcome.completed(QUERY_TEXT);

        assertThat(redirect.isRedirect()).isTrue();
        assertThat(redirect.redirectLocation()).isSameAs(location);
        assertThat(redirect.stateCookie()).isSameAs(cookie);
        assertThat(redirect.body()).isNull();
        assertThat(redirect).isEqualTo(new AuthorizeOutcome(location, cookie, null));
        assertThat(completed.isRedirect()).isFalse();
        assertThat(completed.redirectLocation()).isNull();
        assertThat(completed.stateCookie()).isNull();
        assertThat(completed.body()).isEqualTo(QUERY_TEXT);
        assertThat(completed).isEqualTo(new AuthorizeOutcome(null, null, QUERY_TEXT));
        assertThat(assertThrows(NullPointerException.class, () -> AuthorizeOutcome.redirect(null, cookie)))
                .hasMessage("location");
        assertThat(assertThrows(NullPointerException.class, () -> AuthorizeOutcome.redirect(location, null)))
                .hasMessage("stateCookie");
    }

    @Test
    void constructorRejectsAMissingCollaborator() throws Exception {
        assertThat(assertThrows(NullPointerException.class,
                () -> new SalesforceOAuthService(null, tokenClient, contactQueryService))).hasMessage("properties");
        assertThat(assertThrows(NullPointerException.class,
                () -> new SalesforceOAuthService(properties, null, contactQueryService))).hasMessage("tokenClient");
        assertThat(assertThrows(NullPointerException.class,
                () -> new SalesforceOAuthService(properties, tokenClient, null))).hasMessage("contactQueryService");
        assertThat(assertThrows(NullPointerException.class,
                () -> new SalesforceOAuthService(properties, tokenClient, contactQueryService, null)))
                .hasMessage("clock");
    }

    @ParameterizedTest
    @CsvSource({"refresh-1, ****", ", null"})
    void storedTokenRenderingMasksBothTokens(String refreshToken, String renderedRefreshToken) throws Exception {
        authorizeWith(token("access-1", refreshToken));

        assertThat(storedTokenRendering())
                .isEqualTo("StoredToken[accessToken=****, refreshToken=" + renderedRefreshToken + ", instanceUrl="
                        + INSTANCE_URL + "]")
                .doesNotContain("access-1")
                .doesNotContain("refresh-1");
    }

    static Stream<Exception> queryFailures() {
        return Stream.of(
                new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "Read timed out",
                        new SocketTimeoutException("Read timed out")),
                new ConnectionException("boom"));
    }

    static Stream<RuntimeException> uncheckedRefreshFailures() {
        return Stream.of(
                new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "connect timed out",
                        new SocketTimeoutException()),
                new UpstreamRateLimitException(UpstreamSystem.SALESFORCE, "REQUEST_LIMIT_EXCEEDED", null, null),
                new IllegalStateException("sfdc.oauth.access-token-url is not configured"));
    }

    static Stream<Arguments> checkedRefreshFailures() {
        return Stream.of(
                Arguments.of(new IOException("Broken pipe"), "Salesforce token refresh failed: Broken pipe"),
                Arguments.of(new IOException(), "Salesforce token refresh failed: java.io.IOException"));
    }

    static Stream<Arguments> refreshErrorBodyFailures() {
        return Stream.of(
                Arguments.of(Ending.STALL, Duration.ofSeconds(2), SocketTimeoutException.class, "Read timed out"),
                Arguments.of(Ending.RESET, Duration.ofSeconds(10), SocketException.class, "Connection reset"));
    }

    static Stream<Arguments> refreshResponsesWithoutAccessToken() {
        return Stream.of(
                Arguments.of((TokenResponse) null),
                Arguments.of(token(null, null)),
                Arguments.of(token("   ", null)));
    }

    static Stream<RuntimeException> exchangeFailures() {
        return Stream.of(
                new TokenEndpointException(400, "invalid_grant", "expired authorization code"),
                new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "connect timed out",
                        new SocketTimeoutException()));
    }

    static Stream<Arguments> incompleteCodeExchangeResponses() {
        return Stream.of(
                Arguments.of(null, "access_token"),
                Arguments.of(token(null, "refresh-1"), "access_token"),
                Arguments.of(token("   ", "refresh-1"), "access_token"),
                Arguments.of(response("access-1", "refresh-1", null), "instance_url"),
                Arguments.of(response("access-1", "refresh-1", "   "), "instance_url"));
    }

    /**
     * Starts an authorization at {@code /} and runs its callback for {@link #CODE}, with the state and cookie the
     * redirect issued, Salesforce answering {@code response} and the query returning 3.
     */
    private String authorizeWith(TokenResponse response) throws Exception {
        String state = initiate(service);
        when(tokenClient.exchangeCode(CODE, service.redirectUri())).thenReturn(response);
        when(contactQueryService.queryContacts()).thenReturn(QUERY_TEXT);
        return service.completeAuthorization(CODE, null, null, state, state);
    }

    /**
     * Runs the callback for {@link #CODE} with {@code state} as both the parameter and the cookie, as Salesforce's
     * redirect back to the browser that started the authorization does, Salesforce answering the exchange with
     * {@code access-1} and the query returning 3.
     */
    private String completeWithIssuedState(String state) throws Exception {
        when(tokenClient.exchangeCode(CODE, REDIRECT_URI)).thenReturn(token("access-1", "refresh-1"));
        when(contactQueryService.queryContacts()).thenReturn(QUERY_TEXT);
        return service.completeAuthorization(CODE, null, null, state, state);
    }

    /**
     * Starts an authorization on {@code flow} as a browser without a stored token does at {@code /}.
     *
     * @return the state of the redirect, which its cookie also carries
     */
    private static String initiate(SalesforceOAuthService flow) throws Exception {
        AuthorizeOutcome outcome = flow.authorize();
        assertThat(outcome.isRedirect()).as("authorize without a stored token redirects").isTrue();
        String state = outcome.stateCookie().getValue();
        assertThat(outcome.redirectLocation().getRawQuery()).endsWith("&state=" + state);
        return state;
    }

    /** {@code state} with its last character replaced by another one of the base64url alphabet. */
    private static String lastCharacterChanged(String state) {
        char last = state.charAt(state.length() - 1);
        return state.substring(0, state.length() - 1) + (last == 'A' ? 'B' : 'A');
    }

    /**
     * Counts the authorizations {@code flow} holds as pending, read from its private store under the store's own lock,
     * so the bound and the purge can be asserted on the store itself.
     */
    private static int pendingAuthorizations(SalesforceOAuthService flow) throws Exception {
        Field store = SalesforceOAuthService.class.getDeclaredField("pendingStates");
        store.setAccessible(true);
        Map<?, ?> pending = (Map<?, ?>) store.get(flow);
        synchronized (pending) {
            return pending.size();
        }
    }

    /** A token response on {@link #INSTANCE_URL}, as Salesforce answers both grants. */
    private static TokenResponse token(String accessToken, String refreshToken) {
        return response(accessToken, refreshToken, INSTANCE_URL);
    }

    private static TokenResponse response(String accessToken, String refreshToken, String instanceUrl) {
        return new TokenResponse(accessToken, refreshToken, instanceUrl, IDENTITY_URL, ISSUED_AT, TOKEN_TYPE, SCOPE);
    }

    /**
     * Wires the flow as {@link #sessionModeFlow(String, SalesforceTokenClient)} does, with only the token endpoint
     * mocked: the mocked {@link #tokenClient} exchanges and refreshes the tokens.
     *
     * @param partnerApiBase the WireMock base URL; the login endpoint points there too, so no request can leave it
     */
    private SalesforceOAuthService sessionModeFlow(String partnerApiBase) {
        return sessionModeFlow(partnerApiBase, tokenClient);
    }

    /**
     * Wires the flow as the application context does: a real {@link ContactQueryService} over a real
     * {@link SalesforcePartnerClient} behind the real SC-9 executor and Salesforce classifier, and the new service
     * registered as the client's {@link SessionSource}.
     *
     * @param partnerApiBase the WireMock base URL; the login endpoint points there too, so no request can leave it
     * @param tokens         the token client the service exchanges and refreshes the tokens with
     */
    private SalesforceOAuthService sessionModeFlow(String partnerApiBase, SalesforceTokenClient tokens) {
        SalesforceProperties sfdc = new SalesforceProperties();
        sfdc.setAuthEndpoint(partnerApiBase + SalesforceOAuthService.SOAP_ENDPOINT_PATH);
        sfdc.setConnectTimeout(Duration.ofSeconds(2));
        sfdc.setReadTimeout(Duration.ofSeconds(5));
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        SalesforcePartnerClient partnerClient = new SalesforcePartnerClient(sfdc,
                new UpstreamCallExecutor(List.of(new SalesforceFailureClassifier())),
                beanFactory.getBeanProvider(SessionSource.class));
        SalesforceOAuthService flow = new SalesforceOAuthService(properties, tokens,
                new ContactQueryService(partnerClient));
        beanFactory.registerSingleton("sessionSource", flow);
        return flow;
    }

    /**
     * A real token client, behind its own SC-9 executor and Salesforce classifier, posting to
     * {@code sfdc.oauth.access-token-url} of {@link #properties}.
     *
     * @param timeout its connect and read timeout
     */
    private SalesforceTokenClient realTokenClient(Duration timeout) {
        SalesforceProperties timeouts = new SalesforceProperties();
        timeouts.setConnectTimeout(timeout);
        timeouts.setReadTimeout(timeout);
        return new SalesforceTokenClient(RestClient.builder(), properties, timeouts,
                new UpstreamCallExecutor(List.of(new SalesforceFailureClassifier())));
    }

    /**
     * A token endpoint's raw answer to the code exchange: a token on {@code instanceUrl}, with the connection closed
     * after it, so the refresh grant that follows opens a connection of its own.
     */
    private static String codeExchangeAnswer(String instanceUrl) {
        String json = "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\",\"instance_url\":\""
                + instanceUrl + "\",\"token_type\":\"Bearer\"}";
        return "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + json.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + json;
    }

    /** A Partner API fault rejecting the session, as Salesforce answers a query under an expired access token. */
    private static ResponseDefinitionBuilder invalidSessionFault() {
        String message = "Invalid Session ID found in SessionHeader: Illegal Session";
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " xmlns:sf=\"urn:fault.partner.soap.sforce.com\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><soapenv:Body><soapenv:Fault>"
                + "<faultcode>sf:INVALID_SESSION_ID</faultcode>"
                + "<faultstring>INVALID_SESSION_ID: " + message + "</faultstring>"
                + "<detail><sf:UnexpectedErrorFault xsi:type=\"sf:UnexpectedErrorFault\">"
                + "<sf:exceptionCode>INVALID_SESSION_ID</sf:exceptionCode>"
                + "<sf:exceptionMessage>" + message + "</sf:exceptionMessage>"
                + "</sf:UnexpectedErrorFault></detail></soapenv:Fault></soapenv:Body></soapenv:Envelope>";
        return aResponse().withStatus(500).withHeader("Content-Type", "text/xml; charset=utf-8").withBody(body);
    }

    /** A Partner API {@code queryResponse} with one {@code Contact}, its {@code Id} repeated as Salesforce sends it. */
    private static ResponseDefinitionBuilder oneContactQueryResponse() {
        String contactId = "003" + "000000000001";
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " xmlns=\"urn:partner.soap.sforce.com\" xmlns:sf=\"urn:sobject.partner.soap.sforce.com\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><soapenv:Body>"
                + "<queryResponse><result xsi:type=\"QueryResult\"><done>true</done>"
                + "<queryLocator xsi:nil=\"true\"/>"
                + "<records xsi:type=\"sf:sObject\"><sf:type>Contact</sf:type>"
                + "<sf:Id>" + contactId + "</sf:Id><sf:Id>" + contactId + "</sf:Id>"
                + "<sf:LastName>Doe</sf:LastName><sf:LastModifiedDate>2024-01-02T03:04:05.000Z</sf:LastModifiedDate>"
                + "</records><size>1</size></result></queryResponse></soapenv:Body></soapenv:Envelope>";
        return aResponse().withStatus(200).withHeader("Content-Type", "text/xml; charset=utf-8").withBody(body);
    }

    /** Asserts a journaled Partner API request: the contact query, sent under one grant's session and not another's. */
    private static void assertContactQueryUnder(LoggedRequest request, String accessToken, String otherAccessToken) {
        assertThat(request.getBodyAsString())
                .contains(ContactQueryService.CONTACT_QUERY)
                .contains("SessionHeader")
                .contains(">" + accessToken + "<")
                .doesNotContain(otherAccessToken);
    }

    /**
     * Renders the service's private token slot, the only place a {@code StoredToken} exists, so its masking
     * {@code toString()} can be asserted.
     */
    private String storedTokenRendering() throws Exception {
        Field slot = SalesforceOAuthService.class.getDeclaredField("token");
        slot.setAccessible(true);
        return String.valueOf(((AtomicReference<?>) slot.get(service)).get());
    }

    /**
     * The {@code state} parameter and the state cookie a callback presents, each derived from the state issued to this
     * browser and one issued to another browser, with the refusal message expected, or {@code null} where the case
     * asserts none.
     */
    record StateCase(String label, BinaryOperator<String> stateOf, BinaryOperator<String> cookieOf, String reason) {

        String state(String issued, String other) {
            return stateOf.apply(issued, other);
        }

        String cookie(String issued, String other) {
            return cookieOf.apply(issued, other);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * A clock that stands still until the test advances it, so a pending authorization expires on demand. A copy for
     * another zone shares the same instant.
     */
    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        private final ZoneId zone;

        MutableClock(Instant start) {
            this(new AtomicReference<>(start), ZoneOffset.UTC);
        }

        private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new MutableClock(now, newZone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
