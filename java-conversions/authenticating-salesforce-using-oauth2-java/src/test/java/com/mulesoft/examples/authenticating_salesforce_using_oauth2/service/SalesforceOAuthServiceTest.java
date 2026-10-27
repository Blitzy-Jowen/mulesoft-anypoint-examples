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
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
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
import com.sforce.ws.ConnectionException;

/**
 * Unit test of {@link SalesforceOAuthService}, the authorization-code flow of {@code salesforce-oauth.xml}
 * ({@code sfdc:config-with-oauth}, {@code sfdc:oauth-callback-config} and {@code sfdc:authorize display="PAGE"}),
 * against a Mockito-mocked {@link SalesforceTokenClient} and {@link ContactQueryService}; no Spring context, external
 * network or credentials.
 *
 * <p>It fixes the flow contract: the authorize redirect built from the original connector settings, the code exchange
 * on {@code /oauth2callback} that stores the token and answers with the contact query text, the session supplied to
 * the Partner API client, and the refresh grant that renews it. Every failure branch is pinned: a callback without a
 * code, a rejected or unreachable token endpoint, an incomplete token response, a refused refresh that clears the
 * token, and the upstream failures that pass through unchanged and keep it.
 *
 * <p>One case leaves the mocked query service aside and drives the real {@link ContactQueryService} and SC-15
 * {@link SalesforcePartnerClient} against a local WireMock Partner API, so the session each completed grant stores is
 * observed on the SOAP requests themselves.
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

    @Mock
    private SalesforceTokenClient tokenClient;

    @Mock
    private ContactQueryService contactQueryService;

    private SalesforceOAuthProperties properties;

    private SalesforceOAuthService service;

    @BeforeEach
    void setUp() {
        properties = new SalesforceOAuthProperties();
        properties.setKey("test-key");
        properties.setSecret("test-secret");
        service = new SalesforceOAuthService(properties, tokenClient, contactQueryService);
    }

    @Test
    void authorizationUrlAndRedirectUriFollowTheOriginalConnectorSettings() throws Exception {
        assertThat(service.redirectUri()).isEqualTo(REDIRECT_URI);
        assertThat(service.authorizationUrl())
                .hasToString(AUTHORIZATION_URL)
                .isEqualTo(URI.create(AUTHORIZATION_URL));
    }

    @Test
    void authorizationUrlJoinsAConfiguredQueryStringWithAnAmpersand() throws Exception {
        properties.getOauth().setAuthorizationUrl("  " + AUTHORIZE_ENDPOINT + "?prompt=login  ");

        assertThat(service.authorizationUrl()).hasToString(AUTHORIZE_ENDPOINT
                + "?prompt=login&response_type=code&client_id=test-key&redirect_uri=" + ENCODED_REDIRECT_URI
                + "&display=page");
    }

    @ParameterizedTest
    @CsvSource({", ''", "'key with space&more=1', key+with+space%26more%3D1"})
    void authorizationUrlFormEncodesTheConsumerKeyAndSendsAnUnconfiguredOneEmpty(String key, String encodedKey)
            throws Exception {
        properties.setKey(key);

        assertThat(service.authorizationUrl()).hasToString(AUTHORIZE_ENDPOINT + "?response_type=code&client_id="
                + encodedKey + "&redirect_uri=" + ENCODED_REDIRECT_URI + "&display=page");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void authorizationUrlFailsWhenNoAuthorizationEndpointIsConfigured(String configured) throws Exception {
        properties.getOauth().setAuthorizationUrl(configured);

        IllegalStateException direct = assertThrows(IllegalStateException.class, service::authorizationUrl);
        IllegalStateException viaAuthorize = assertThrows(IllegalStateException.class, service::authorize);

        assertThat(direct).hasMessage("sfdc.oauth.authorization-url is not configured");
        assertThat(viaAuthorize).hasMessage("sfdc.oauth.authorization-url is not configured");
        verifyNoInteractions(tokenClient, contactQueryService);
    }

    @Test
    void noTokenIsStoredBeforeAnyCallback() throws Exception {
        assertThat(service.isAuthorized()).isFalse();
    }

    @Test
    void authorizeWithoutATokenRedirectsTheBrowserToSalesforce() throws Exception {
        AuthorizeOutcome outcome = service.authorize();

        assertThat(outcome.isRedirect()).isTrue();
        assertThat(outcome.redirectLocation())
                .isEqualTo(service.authorizationUrl())
                .hasToString(AUTHORIZATION_URL);
        assertThat(outcome.body()).isNull();
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
        when(tokenClient.exchangeCode(CODE, REDIRECT_URI)).thenReturn(token("access-1", "refresh-1"));
        when(contactQueryService.queryContacts()).thenThrow(failure);

        Exception thrown = assertThrows(Exception.class, () -> service.completeAuthorization(CODE, null, null));

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
        authorizeWith(token("access-1", "refresh-1"));
        when(tokenClient.exchangeCode("code-b", REDIRECT_URI))
                .thenReturn(response("access-b", "refresh-b", instanceB));
        when(tokenClient.refresh("refresh-1")).thenAnswer(invocation -> {
            // A browser completes a new authorization while Salesforce is still answering the refresh of grant A.
            assertThat(service.completeAuthorization("code-b", null, null)).isEqualTo(QUERY_TEXT);
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

            assertThat(flow.completeAuthorization("code-a", null, null)).isEqualTo(ONE_CONTACT_TEXT);
            assertThat(partnerApi.findAll(postRequestedFor(urlEqualTo(instanceA))))
                    .singleElement()
                    .satisfies(request -> assertContactQueryUnder(request, "access-a", "access-b"));
            assertThat(partnerApi.findAll(postRequestedFor(urlEqualTo(instanceB)))).isEmpty();

            assertThat(flow.completeAuthorization("code-b", null, null)).isEqualTo(ONE_CONTACT_TEXT);
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
        assertThat(service.authorize()).isEqualTo(AuthorizeOutcome.redirect(URI.create(AUTHORIZATION_URL)));
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
                () -> service.completeAuthorization(null, error, description));

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
                () -> service.completeAuthorization(code, null, null));

        assertThat(thrown).hasMessage("Salesforce authorization failed: no authorization code");
        verifyNoInteractions(tokenClient, contactQueryService);
        assertThat(service.isAuthorized()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("exchangeFailures")
    void callbackExchangeFailureIsRethrownUnchangedAndStoresNothing(RuntimeException failure) throws Exception {
        when(tokenClient.exchangeCode(CODE, REDIRECT_URI)).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.completeAuthorization(CODE, null, null));

        assertSame(failure, thrown);
        assertThat(service.isAuthorized()).isFalse();
        verifyNoInteractions(contactQueryService);
    }

    @ParameterizedTest
    @MethodSource("incompleteCodeExchangeResponses")
    void callbackWithAnIncompleteTokenResponseFailsAndStoresNothing(TokenResponse response, String missingField)
            throws Exception {
        when(tokenClient.exchangeCode(CODE, REDIRECT_URI)).thenReturn(response);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(CODE, null, null));

        assertThat(thrown).hasMessage("Salesforce token response is missing " + missingField);
        assertThat(service.isAuthorized()).isFalse();
        verifyNoInteractions(contactQueryService);
    }

    @Test
    void authorizeOutcomeTellsARedirectFromACompletedFlow() throws Exception {
        URI location = URI.create(AUTHORIZATION_URL);

        AuthorizeOutcome redirect = AuthorizeOutcome.redirect(location);
        AuthorizeOutcome completed = AuthorizeOutcome.completed(QUERY_TEXT);

        assertThat(redirect.isRedirect()).isTrue();
        assertThat(redirect.redirectLocation()).isSameAs(location);
        assertThat(redirect.body()).isNull();
        assertThat(redirect).isEqualTo(new AuthorizeOutcome(location, null));
        assertThat(completed.isRedirect()).isFalse();
        assertThat(completed.redirectLocation()).isNull();
        assertThat(completed.body()).isEqualTo(QUERY_TEXT);
        assertThat(completed).isEqualTo(new AuthorizeOutcome(null, QUERY_TEXT));
        assertThat(assertThrows(NullPointerException.class, () -> AuthorizeOutcome.redirect(null)))
                .hasMessage("location");
    }

    @Test
    void constructorRejectsAMissingCollaborator() throws Exception {
        assertThat(assertThrows(NullPointerException.class,
                () -> new SalesforceOAuthService(null, tokenClient, contactQueryService))).hasMessage("properties");
        assertThat(assertThrows(NullPointerException.class,
                () -> new SalesforceOAuthService(properties, null, contactQueryService))).hasMessage("tokenClient");
        assertThat(assertThrows(NullPointerException.class,
                () -> new SalesforceOAuthService(properties, tokenClient, null))).hasMessage("contactQueryService");
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

    /** Runs the callback for {@link #CODE} with Salesforce answering {@code response} and the query returning 3. */
    private String authorizeWith(TokenResponse response) throws Exception {
        when(tokenClient.exchangeCode(CODE, service.redirectUri())).thenReturn(response);
        when(contactQueryService.queryContacts()).thenReturn(QUERY_TEXT);
        return service.completeAuthorization(CODE, null, null);
    }

    /** A token response on {@link #INSTANCE_URL}, as Salesforce answers both grants. */
    private static TokenResponse token(String accessToken, String refreshToken) {
        return response(accessToken, refreshToken, INSTANCE_URL);
    }

    private static TokenResponse response(String accessToken, String refreshToken, String instanceUrl) {
        return new TokenResponse(accessToken, refreshToken, instanceUrl, IDENTITY_URL, ISSUED_AT, TOKEN_TYPE, SCOPE);
    }

    /**
     * Wires the flow as the application context does: a real {@link ContactQueryService} over a real
     * {@link SalesforcePartnerClient} behind the real SC-9 executor and Salesforce classifier, the new service
     * registered as the client's {@link SessionSource}, and only the token endpoint mocked.
     *
     * @param partnerApiBase the WireMock base URL; the login endpoint points there too, so no request can leave it
     */
    private SalesforceOAuthService sessionModeFlow(String partnerApiBase) {
        SalesforceProperties sfdc = new SalesforceProperties();
        sfdc.setAuthEndpoint(partnerApiBase + SalesforceOAuthService.SOAP_ENDPOINT_PATH);
        sfdc.setConnectTimeout(Duration.ofSeconds(2));
        sfdc.setReadTimeout(Duration.ofSeconds(5));
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        SalesforcePartnerClient partnerClient = new SalesforcePartnerClient(sfdc,
                new UpstreamCallExecutor(List.of(new SalesforceFailureClassifier())),
                beanFactory.getBeanProvider(SessionSource.class));
        SalesforceOAuthService flow = new SalesforceOAuthService(properties, tokenClient,
                new ContactQueryService(partnerClient));
        beanFactory.registerSingleton("sessionSource", flow);
        return flow;
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
}
