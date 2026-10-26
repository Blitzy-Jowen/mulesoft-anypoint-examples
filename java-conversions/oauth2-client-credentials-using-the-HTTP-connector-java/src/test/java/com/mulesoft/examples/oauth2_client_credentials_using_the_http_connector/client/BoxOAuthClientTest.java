/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.config.TlsProperties;

/**
 * The contract of {@link BoxOAuthClient}, the {@code HTTP_Request_Configuration} with its
 * {@code oauth2:client-credentials-grant-type} and the GET {@code path="/"} of the {@code /oauth} flow in
 * {@code oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml} (lines 12-28
 * and 34), against a WireMock stand-in for both {@code https://app.box.com/api/oauth2/token} and
 * {@code app.box.com:443/api/oauth2/authorize}: the token is requested once with the client-credentials grant and an
 * HTTP Basic client authentication, cached and sent as a Bearer header whatever {@code token_type} says; a 401 or
 * 403 re-runs the grant and retries the GET exactly once; any other status of 400 or above fails at once with
 * Mule's {@code Response code <code> mapped as failure.}; a failed or token-less token response fails before the
 * GET; redirects are followed; a timed-out GET is never retried; and an https base URL whose TLS stores cannot be
 * opened fails before any request is sent.
 */
class BoxOAuthClientTest {

    private static final String TOKEN_PATH = "/api/oauth2/token";
    private static final String BASE_PATH = "/api/oauth2/authorize";
    /** The GET of line 34, {@code path="/"}, appended to the request configuration's basePath. */
    private static final String AUTHORIZE_PATH = BASE_PATH + "/";

    private static final String CLIENT_ID = "id";
    private static final String CLIENT_SECRET = "secret";

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @BeforeEach
    void resetUpstream() {
        wm.resetAll();
    }

    @Test
    void tokenRequestUsesClientCredentialsGrant() {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200).withBody("authorized")));
        BoxOAuthClient client = httpClient();
        // The token is requested lazily, so construction contacts no upstream.
        assertThat(wm.getAllServeEvents()).isEmpty();

        ResponseEntity<byte[]> response = client.authorize();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("authorized".getBytes(UTF_8));
        // Mule 3.8 defaults encodeClientCredentialsInBody to false: the credentials travel in the Basic header only.
        String basic = "Basic " + Base64.getEncoder().encodeToString("id:secret".getBytes(UTF_8));
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withHeader("Authorization", equalTo(basic))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(notContaining("client_secret")));
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
    }

    @Test
    void cachedTokenIsReused() {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200).withBody("authorized")));
        BoxOAuthClient client = httpClient();

        client.authorize();
        client.authorize();

        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        wm.verify(2, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        wm.verify(2, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)).withHeader("Authorization", equalTo("Bearer tok-1")));
    }

    /** Mule's default {@code refreshTokenWhen}: the resource server rejected the access token. */
    @ParameterizedTest(name = "{0} refreshes the token and retries once")
    @ValueSource(ints = {401, 403})
    void rejectedTokenIsRefreshedAndRetriedOnce(int rejection) {
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).inScenario("token")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(tokenResponse("tok-1"))
                .willSetStateTo("second"));
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).inScenario("token")
                .whenScenarioStateIs("second")
                .willReturn(tokenResponse("tok-2")));
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).inScenario("authz")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(rejection))
                .willSetStateTo("ok"));
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).inScenario("authz")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200).withBody("granted")));

        ResponseEntity<byte[]> response = httpClient().authorize();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("granted".getBytes(UTF_8));
        wm.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        wm.verify(2, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        List<LoggedRequest> gets = wm.findAll(getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        assertThat(gets).extracting(logged -> logged.getHeader("Authorization"))
                .containsExactly("Bearer tok-1", "Bearer tok-2");
    }

    @ParameterizedTest(name = "a second {0} fails without a further retry")
    @ValueSource(ints = {401, 403})
    void secondRejectionFailsWithoutFurtherRetry(int rejection) {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(rejection)));
        BoxOAuthClient client = httpClient();

        assertThatThrownBy(client::authorize)
                .isInstanceOfSatisfying(RestClientResponseException.class, failure -> {
                    assertThat(failure).hasMessage("Response code " + rejection + " mapped as failure.");
                    assertThat(failure.getStatusCode().value()).isEqualTo(rejection);
                });

        wm.verify(2, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        wm.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @ParameterizedTest(name = "{0} fails at once")
    @ValueSource(ints = {400, 404, 500})
    void failureStatusOtherThanRejectionIsNotRetried(int status) {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(status).withBody("boom")));
        BoxOAuthClient client = httpClient();

        assertThatThrownBy(client::authorize)
                .isInstanceOfSatisfying(RestClientResponseException.class, failure -> {
                    assertThat(failure).hasMessage("Response code " + status + " mapped as failure.");
                    assertThat(failure.getStatusCode().value()).isEqualTo(status);
                    assertThat(failure.getResponseBodyAsString(UTF_8)).isEqualTo("boom");
                });

        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @ParameterizedTest(name = "token endpoint {0} fails before the GET")
    @ValueSource(ints = {401, 500})
    void tokenEndpointErrorThrowsIllegalState(int status) {
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(status)));
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200)));
        BoxOAuthClient client = httpClient();

        assertThatThrownBy(client::authorize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(wm.baseUrl() + TOKEN_PATH)
                .hasMessageContaining(String.valueOf(status));

        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        wm.verify(0, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
    }

    @ParameterizedTest(name = "token response {0} carries no access token")
    @ValueSource(strings = {
            "{\"token_type\":\"bearer\",\"expires_in\":3600}",
            "{\"access_token\":\"\",\"token_type\":\"bearer\"}",
            "{\"access_token\":null,\"token_type\":\"bearer\"}",
            "not json"})
    void tokenWithoutAccessTokenThrowsIllegalState(String tokenBody) {
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(tokenBody)));
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200)));
        BoxOAuthClient client = httpClient();

        assertThatThrownBy(client::authorize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(wm.baseUrl() + TOKEN_PATH)
                .hasMessageContaining("access_token");

        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        wm.verify(0, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
    }

    /** Line 18 extracts {@code token_type} into the token store; the resource request is still a Bearer call. */
    @ParameterizedTest(name = "token_type {0} still sends Bearer")
    @ValueSource(strings = {"bearer", "Bearer", "mac"})
    void tokenTypeInResponseStillSendsBearer(String tokenType) {
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"tok-1\",\"token_type\":\"" + tokenType + "\",\"expires_in\":3600}")));
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200).withBody("authorized")));

        httpClient().authorize();

        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)).withHeader("Authorization", equalTo("Bearer tok-1")));
    }

    @Test
    void redirectIsFollowed() {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", wm.baseUrl() + "/elsewhere")));
        wm.stubFor(get(urlEqualTo("/elsewhere")).willReturn(aResponse().withStatus(200).withBody("moved-body")));

        ResponseEntity<byte[]> response = httpClient().authorize();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("moved-body".getBytes(UTF_8));
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        wm.verify(1, getRequestedFor(urlEqualTo("/elsewhere")));
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void timeoutIsNotRetried() {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withBody("late")
                .withFixedDelay(1500)));
        BoxOAuthClient client = client(wm.baseUrl(), Duration.ofMillis(300));

        Throwable thrown = catchThrowable(client::authorize);

        assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        // Spring 6.1's JdkClientHttpRequest reports headers not received in time as an IOException caused by a
        // TimeoutException; a body stalled after the headers surfaces as an HttpTimeoutException.
        assertThat(causeChain(thrown))
                .anyMatch(cause -> cause instanceof TimeoutException || cause instanceof HttpTimeoutException);
        wm.verify(1, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        wm.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    void httpsWithBlankTlsPasswordsFailsBeforeAuthorize() {
        stubToken();
        wm.stubFor(get(urlEqualTo(AUTHORIZE_PATH)).willReturn(aResponse().withStatus(200)));
        BoxOAuthClient client = client("https://127.0.0.1:" + wm.getPort(), Duration.ofSeconds(2));

        assertThatThrownBy(client::authorize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keystore.password");

        wm.verify(0, getRequestedFor(urlEqualTo(AUTHORIZE_PATH)));
        // The TLS context is built before the token request, so no client secret leaves on a broken TLS setup.
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    /** The carried stores of {@code src/main/resources}, with {@code password} in all three password slots. */
    private static TlsProperties tls(String password) {
        return new TlsProperties("keystore.jks", password, password, "trust-store", password);
    }

    private static BoxOAuthClient client(String baseUrl, Duration timeout) {
        return new BoxOAuthClient(wm.baseUrl() + TOKEN_PATH, CLIENT_ID, CLIENT_SECRET, baseUrl, BASE_PATH,
                tls(""), timeout);
    }

    /** An http base URL, so the resource request never reads the TLS stores. */
    private static BoxOAuthClient httpClient() {
        return client(wm.baseUrl(), Duration.ofSeconds(2));
    }

    private static void stubToken() {
        wm.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(tokenResponse("tok-1")));
    }

    private static ResponseDefinitionBuilder tokenResponse(String accessToken) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"" + accessToken + "\",\"token_type\":\"bearer\",\"expires_in\":3600}");
    }

    private static Stream<Throwable> causeChain(Throwable thrown) {
        return Stream.iterate(thrown, Objects::nonNull, Throwable::getCause);
    }
}
