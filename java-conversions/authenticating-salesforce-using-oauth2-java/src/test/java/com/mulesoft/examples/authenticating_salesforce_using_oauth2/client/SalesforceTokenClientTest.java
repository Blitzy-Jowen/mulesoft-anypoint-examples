/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforceTokenClient.TokenEndpointException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforceTokenClient.TokenResponse;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.config.SalesforceOAuthProperties;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.config.SalesforceProperties;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamUnavailableException;

/**
 * Unit test of {@link SalesforceTokenClient}, the code exchange and token refresh that the original
 * {@code sfdc:authorize} performed against {@code https://login.salesforce.com/services/oauth2/token} with the
 * {@code consumerKey}/{@code consumerSecret} of {@code sfdc:config-with-oauth}.
 *
 * <p>A WireMock server stands in for the Salesforce token endpoint, so no credential and no live host is involved.
 * The client runs behind a real {@link UpstreamCallExecutor} and a spied, otherwise real,
 * {@link SalesforceFailureClassifier}; counting the classifier's invocations together with the requests WireMock
 * received proves that a rejected grant is rethrown unchanged and that a read timeout or a refused connection
 * becomes {@link UpstreamUnavailableException} for Salesforce after exactly one attempt, never retried.
 *
 * <p>No Spring context is started: the client is built directly from fresh configuration beans. Only the read-timeout
 * case shortens the timeouts, to {@link #SHORT_TIMEOUT}.
 */
class SalesforceTokenClientTest {

    private static final String TOKEN_PATH = "/services/oauth2/token";

    /** The callback of the original {@code sfdc:oauth-callback-config}. */
    private static final String REDIRECT_URI = "http://localhost:8081/oauth2callback";

    private static final String CLIENT_ID = "test-key";

    private static final String CLIENT_SECRET = "test-secret";

    private static final String AUTHORIZATION_CODE = "test-code";

    private static final String REFRESH_TOKEN = "5Aep861test";

    /**
     * Connect and read timeout of the clients that expect an answer: the 10 s production default. A shorter value
     * makes these cases time out on a loaded host, where WireMock's first answer can take seconds.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Connect and read timeout of the read-timeout case only; far below the delayed stub's 3000 ms. */
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(500);

    private static final String SUCCESS_JSON = "{\"access_token\":\"00Dxx0000000001!AQ4AQtest\","
            + "\"refresh_token\":\"5Aep861test\","
            + "\"instance_url\":\"https://na1.salesforce.com\","
            + "\"id\":\"https://login.salesforce.com/id/00Dxx0000000001EAA/005xx000001Sv6AAAS\","
            + "\"issued_at\":\"1700000000000\","
            + "\"token_type\":\"Bearer\","
            + "\"scope\":\"api refresh_token\"}";

    private static final String INVALID_GRANT_JSON =
            "{\"error\":\"invalid_grant\",\"error_description\":\"expired authorization code\"}";

    private final SalesforceFailureClassifier classifier = spy(new SalesforceFailureClassifier());

    private final UpstreamCallExecutor executor = new UpstreamCallExecutor(List.of(classifier));

    private WireMockServer wireMock;

    @BeforeEach
    void startTokenEndpoint() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stopTokenEndpoint() {
        wireMock.stop();
    }

    @Test
    void exchangeCodePostsAuthorizationCodeForm() {
        stubTokenEndpoint(success());

        newClient(tokenUrl()).exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI);

        LoggedRequest request = singleTokenRequest();
        assertThat(request.getHeader("Content-Type")).contains("application/x-www-form-urlencoded");
        assertThat(formOf(request)).isEqualTo(Map.of(
                "grant_type", "authorization_code",
                "code", AUTHORIZATION_CODE,
                "client_id", CLIENT_ID,
                "client_secret", CLIENT_SECRET,
                "redirect_uri", REDIRECT_URI));
    }

    @Test
    void refreshPostsRefreshTokenForm() {
        stubTokenEndpoint(success());

        newClient(tokenUrl()).refresh(REFRESH_TOKEN);

        LoggedRequest request = singleTokenRequest();
        assertThat(request.getHeader("Content-Type")).contains("application/x-www-form-urlencoded");
        assertThat(formOf(request)).isEqualTo(Map.of(
                "grant_type", "refresh_token",
                "refresh_token", REFRESH_TOKEN,
                "client_id", CLIENT_ID,
                "client_secret", CLIENT_SECRET));
    }

    @Test
    void successResponseParsesEveryTokenField() {
        stubTokenEndpoint(success());

        TokenResponse token = newClient(tokenUrl()).exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI);

        assertThat(token.access_token()).isEqualTo("00Dxx0000000001!AQ4AQtest");
        assertThat(token.refresh_token()).isEqualTo("5Aep861test");
        assertThat(token.instance_url()).isEqualTo("https://na1.salesforce.com");
        assertThat(token.id()).isEqualTo("https://login.salesforce.com/id/00Dxx0000000001EAA/005xx000001Sv6AAAS");
        assertThat(token.issued_at()).isEqualTo("1700000000000");
        assertThat(token.token_type()).isEqualTo("Bearer");
        assertThat(token.scope()).isEqualTo("api refresh_token");
    }

    @Test
    void errorResponseThrowsTokenEndpointException() {
        stubTokenEndpoint(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody(INVALID_GRANT_JSON));
        SalesforceTokenClient client = newClient(tokenUrl());

        TokenEndpointException thrown = assertThrows(TokenEndpointException.class,
                () -> client.exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI));

        assertThat(thrown).hasMessage("invalid_grant: expired authorization code");
        assertThat(thrown.getStatus()).isEqualTo(400);
        assertThat(thrown.getError()).isEqualTo("invalid_grant");
        assertThat(thrown.getErrorDescription()).isEqualTo("expired authorization code");
        // Classified OTHER and rethrown unchanged: the exact type also rules out UpstreamRateLimitException, which
        // shares no type with TokenEndpointException.
        assertThat(thrown)
                .isExactlyInstanceOf(TokenEndpointException.class)
                .isNotInstanceOf(UpstreamAuthenticationException.class)
                .isNotInstanceOf(UpstreamUnavailableException.class);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        verify(classifier, times(1)).classify(any());
    }

    @Test
    void errorResponseWithoutErrorFieldsUsesHttpStatusMessage() {
        stubTokenEndpoint(aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "text/plain")
                .withBody("oops"));
        SalesforceTokenClient client = newClient(tokenUrl());

        TokenEndpointException thrown = assertThrows(TokenEndpointException.class,
                () -> client.exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI));

        assertThat(thrown).hasMessage("HTTP 500");
        assertThat(thrown.getStatus()).isEqualTo(500);
        assertThat(thrown.getError()).isNull();
        assertThat(thrown.getErrorDescription()).isNull();
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        verify(classifier, times(1)).classify(any());
    }

    @Test
    void readTimeoutIsUnavailableAndNotRetried() {
        // With the 10 s default read timeout this delayed answer would succeed, so the failure also proves that the
        // configured read timeout is the one applied.
        stubTokenEndpoint(success().withFixedDelay(3000));
        SalesforceTokenClient client = newClient(tokenUrl(), SHORT_TIMEOUT);

        UpstreamUnavailableException thrown = assertThrows(UpstreamUnavailableException.class,
                () -> client.exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI));

        assertThat(thrown.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
        assertThat(causeChain(thrown)).anyMatch(SocketTimeoutException.class::isInstance);
        // WireMock may journal a request whose answer is still being delayed only after the client gave up.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> wireMock.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH))));
        verify(classifier, times(1)).classify(any());
    }

    @Test
    void closedPortIsUnavailableAndNotRetried() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        SalesforceTokenClient client = newClient("http://127.0.0.1:" + closedPort + TOKEN_PATH);

        UpstreamUnavailableException thrown = assertThrows(UpstreamUnavailableException.class,
                () -> client.exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI));

        assertThat(thrown.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
        assertThat(causeChain(thrown)).anyMatch(ConnectException.class::isInstance);
        verify(classifier, times(1)).classify(any());
    }

    private SalesforceTokenClient newClient(String tokenUrl) {
        return newClient(tokenUrl, TIMEOUT);
    }

    /**
     * Builds a client over fresh configuration. Every property is set before construction, so the client sees the
     * same values whether it reads them at construction or on each call.
     */
    private SalesforceTokenClient newClient(String tokenUrl, Duration timeout) {
        SalesforceOAuthProperties oauthProperties = new SalesforceOAuthProperties();
        oauthProperties.setKey(CLIENT_ID);
        oauthProperties.setSecret(CLIENT_SECRET);
        oauthProperties.getOauth().setAccessTokenUrl(tokenUrl);
        SalesforceProperties salesforceProperties = new SalesforceProperties();
        salesforceProperties.setConnectTimeout(timeout);
        salesforceProperties.setReadTimeout(timeout);
        return new SalesforceTokenClient(RestClient.builder(), oauthProperties, salesforceProperties, executor);
    }

    private String tokenUrl() {
        return wireMock.baseUrl() + TOKEN_PATH;
    }

    private void stubTokenEndpoint(ResponseDefinitionBuilder response) {
        wireMock.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(response));
    }

    private static ResponseDefinitionBuilder success() {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(SUCCESS_JSON);
    }

    /**
     * Returns the one request the token endpoint received, failing when the client sent any other request or
     * more than one.
     */
    private LoggedRequest singleTokenRequest() {
        assertThat(wireMock.getAllServeEvents()).hasSize(1);
        List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    /**
     * Decodes an {@code application/x-www-form-urlencoded} body into its parameters, failing when a parameter is
     * sent more than once, which a map would otherwise hide.
     */
    private static Map<String, String> formOf(LoggedRequest request) {
        Map<String, String> form = new LinkedHashMap<>();
        String body = request.getBodyAsString();
        if (body == null || body.isEmpty()) {
            return form;
        }
        for (String pair : body.split("&", -1)) {
            int separator = pair.indexOf('=');
            String name = URLDecoder.decode(separator < 0 ? pair : pair.substring(0, separator),
                    StandardCharsets.UTF_8);
            String value = separator < 0 ? "" : URLDecoder.decode(pair.substring(separator + 1),
                    StandardCharsets.UTF_8);
            assertThat(form.put(name, value)).as("form parameter %s sent more than once", name).isNull();
        }
        return form;
    }

    /** Lists a throwable and its causes, each once, so a cause chain that loops back on itself still ends. */
    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
