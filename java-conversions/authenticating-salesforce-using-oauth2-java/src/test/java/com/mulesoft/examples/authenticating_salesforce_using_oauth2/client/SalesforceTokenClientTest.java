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
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforceTokenClient.TokenEndpointException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.client.SalesforceTokenClient.TokenResponse;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.config.SalesforceOAuthProperties;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.config.SalesforceProperties;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamUnavailableException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.support.LoopbackHttpStub;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.support.LoopbackHttpStub.Ending;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.support.LoopbackHttpStub.Reply;

/**
 * Unit test of {@link SalesforceTokenClient}, the code exchange and token refresh that the original
 * {@code sfdc:authorize} performed against {@code https://login.salesforce.com/services/oauth2/token} with the
 * {@code consumerKey}/{@code consumerSecret} of {@code sfdc:config-with-oauth}.
 *
 * <p>A WireMock server stands in for the Salesforce token endpoint, so no credential and no live host is involved.
 * Where an error status and part of its body must arrive before the connection stalls or is reset, which WireMock
 * cannot time deterministically, a {@link LoopbackHttpStub} writes the raw answer instead. The client runs behind a
 * real {@link UpstreamCallExecutor} and a spied, otherwise real, {@link SalesforceFailureClassifier}; counting the
 * classifier's invocations together with the requests the endpoint received proves that a rejected grant is
 * rethrown unchanged and that a read timeout, a reset or a refused connection, before the answer or within an error
 * body, becomes {@link UpstreamUnavailableException} for Salesforce with the socket's own message after exactly one
 * attempt, never retried.
 *
 * <p>No Spring context is started: the client is built directly from fresh configuration beans. Only the cases that
 * wait for a read timeout shorten the timeouts, to {@link #SHORT_TIMEOUT}.
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

    /**
     * A 400 whose status line, headers and first body bytes arrive while the rest of its declared 200-byte body
     * never does: the start of Salesforce's error object, cut off in transit.
     */
    private static final String ERROR_HEAD_AND_PARTIAL_BODY = "HTTP/1.1 400 Bad Request\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: 200\r\n"
            + "\r\n"
            + "{\"error\":\"invalid_grant\",";

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

    /**
     * An error answer without a readable error object, its body absent included, is a status-only grant failure: an
     * answer without a body is not a transport failure.
     */
    @ParameterizedTest
    @CsvSource({
            "500, text/plain, oops",
            "400, application/json, ",
            "400, text/html, <html><body>Bad Request</body></html>",
            "404, , "})
    void errorResponseWithoutErrorFieldsUsesHttpStatusMessage(int status, String contentType, String body) {
        ResponseDefinitionBuilder answer = aResponse().withStatus(status);
        if (contentType != null) {
            answer.withHeader("Content-Type", contentType);
        }
        if (body != null) {
            answer.withBody(body);
        }
        stubTokenEndpoint(answer);
        SalesforceTokenClient client = newClient(tokenUrl());

        TokenEndpointException thrown = assertThrows(TokenEndpointException.class,
                () -> client.exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI));

        assertThat(thrown).isExactlyInstanceOf(TokenEndpointException.class).hasMessage("HTTP " + status);
        assertThat(thrown.getStatus()).isEqualTo(status);
        assertThat(thrown.getError()).isNull();
        assertThat(thrown.getErrorDescription()).isNull();
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        verify(classifier, times(1)).classify(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresBeforeTheAnswer")
    void transportFailureBeforeTheAnswerIsUnavailableWithTheSocketMessageAndNotRetried(
            ResponseDefinitionBuilder answer, Duration timeout, Class<? extends IOException> socketFailure,
            String socketMessage) {
        stubTokenEndpoint(answer);
        SalesforceTokenClient client = newClient(tokenUrl(), timeout);

        UpstreamUnavailableException thrown = assertThrows(UpstreamUnavailableException.class,
                () -> client.exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI));

        assertUnavailableWithSocketMessage(thrown, socketFailure, socketMessage);
        // WireMock may journal a request whose answer is still being delayed only after the client gave up.
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> wireMock.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH))));
        verify(classifier, times(1)).classify(any());
    }

    @Test
    void closedPortIsUnavailableAndNotRetried() throws IOException {
        try (Socket reservation = refusingLoopbackPort()) {
            SalesforceTokenClient client = newClient(refusingBaseUrl(reservation) + TOKEN_PATH);

            UpstreamUnavailableException thrown = assertThrows(UpstreamUnavailableException.class,
                    () -> client.exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI));

            assertUnavailableWithSocketMessage(thrown, ConnectException.class, "Connection refused");
            verify(classifier, times(1)).classify(any());
        }
    }

    /**
     * The error status and headers arrive, then the error body stalls past the read timeout or is reset: the body was
     * lost in transit, not absent, so the grant fails as unavailable rather than as a status-only refusal, for both
     * grants and after one request.
     */
    @ParameterizedTest(name = "{0}, error body {1}")
    @MethodSource("errorBodyTransportFailures")
    void errorBodyCutOffInTransitIsUnavailableWithTheSocketMessageAndNotRetried(Grant grant, Ending ending,
            Duration timeout, Class<? extends IOException> socketFailure, String socketMessage) throws IOException {
        try (LoopbackHttpStub tokenEndpoint = LoopbackHttpStub.start(new Reply(ERROR_HEAD_AND_PARTIAL_BODY, ending))) {
            SalesforceTokenClient client = newClient(tokenEndpoint.baseUrl() + TOKEN_PATH, timeout);

            UpstreamUnavailableException thrown = assertThrows(UpstreamUnavailableException.class,
                    () -> grant.request(client));

            assertUnavailableWithSocketMessage(thrown, socketFailure, socketMessage);
            assertThat(tokenEndpoint.requests())
                    .singleElement()
                    .asString()
                    .startsWith("POST " + TOKEN_PATH + " ")
                    .contains(grant.formField);
            verify(classifier, times(1)).classify(any());
        }
    }

    /**
     * An error body that cannot be opened is absent, and leaves the status-only error, unless opening it failed for
     * a timeout or connectivity failure. The endpoint answers with a full {@code invalid_grant} object, so a status-only
     * error also proves that no body was read.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("errorBodyOpenings")
    void errorBodyThatCannotBeOpenedIsAbsentUnlessTheTransportFailed(BodyOpening opening,
            Class<? extends RuntimeException> expected, String message) {
        stubTokenEndpoint(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody(INVALID_GRANT_JSON));
        SalesforceTokenClient client = newClient(tokenUrl(), TIMEOUT, openingBodiesWith(opening));

        RuntimeException thrown = assertThrows(expected, () -> client.exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI));

        assertThat(thrown).isExactlyInstanceOf(expected).hasMessage(message);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        verify(classifier, times(1)).classify(any());
    }

    /**
     * A failure {@code RestClient} wraps without a timeout or connectivity cause, a body read failing on its own or a
     * success body that is not JSON, is rethrown unchanged after one request: it is neither a refused grant nor an
     * unavailable endpoint.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresWithoutATransportCause")
    void restClientFailureWithoutATransportCauseIsRethrownUnchanged(ResponseDefinitionBuilder answer,
            BodyOpening opening, Class<? extends Exception> cause) {
        stubTokenEndpoint(answer);
        SalesforceTokenClient client = newClient(tokenUrl(), TIMEOUT, openingBodiesWith(opening));

        RestClientException thrown = assertThrows(RestClientException.class,
                () -> client.exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI));

        assertThat(thrown).isExactlyInstanceOf(RestClientException.class).hasCauseInstanceOf(cause);
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        verify(classifier, times(1)).classify(any());
    }

    static Stream<Arguments> errorBodyOpenings() {
        return Stream.of(
                Arguments.of(Named.<BodyOpening>of("no body stream", answer -> null),
                        TokenEndpointException.class, "HTTP 400"),
                Arguments.of(Named.<BodyOpening>of("no error stream", answer -> {
                    throw new IOException("Server returned HTTP response code: 400 for URL: " + TOKEN_PATH);
                }), TokenEndpointException.class, "HTTP 400"),
                Arguments.of(Named.<BodyOpening>of("timed out while opening", answer -> {
                    throw new SocketTimeoutException("Read timed out");
                }), UpstreamUnavailableException.class, "Read timed out"));
    }

    static Stream<Arguments> failuresWithoutATransportCause() {
        return Stream.of(
                Arguments.of(Named.of("error body read failing without a transport cause", aResponse()
                                .withStatus(400)
                                .withHeader("Content-Type", "application/json")
                                .withBody(INVALID_GRANT_JSON)),
                        (BodyOpening) answer -> new InputStream() {
                            @Override
                            public int read() throws IOException {
                                throw new IOException("Premature EOF");
                            }
                        },
                        IOException.class),
                Arguments.of(Named.of("success body that is not JSON", aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("not json")),
                        (BodyOpening) ClientHttpResponse::getBody,
                        HttpMessageNotReadableException.class));
    }

    static Stream<Arguments> failuresBeforeTheAnswer() {
        return Stream.of(
                // With the 10 s default read timeout this delayed answer would succeed, so the failure also proves
                // that the configured read timeout is the one applied.
                Arguments.of(Named.of("answer delayed past the read timeout", success().withFixedDelay(3000)),
                        SHORT_TIMEOUT, SocketTimeoutException.class, "Read timed out"),
                Arguments.of(Named.of("connection reset before the answer",
                        aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
                        TIMEOUT, SocketException.class, "Connection reset"));
    }

    static Stream<Arguments> errorBodyTransportFailures() {
        return Stream.of(Grant.values()).flatMap(grant -> Stream.of(
                Arguments.of(grant, Ending.STALL, SHORT_TIMEOUT, SocketTimeoutException.class, "Read timed out"),
                Arguments.of(grant, Ending.RESET, TIMEOUT, SocketException.class, "Connection reset")));
    }

    /** The two grants the client posts, each with the form field that names it on the wire. */
    enum Grant {

        CODE_EXCHANGE("grant_type=authorization_code") {
            @Override
            TokenResponse request(SalesforceTokenClient client) {
                return client.exchangeCode(AUTHORIZATION_CODE, REDIRECT_URI);
            }
        },

        REFRESH("grant_type=refresh_token") {
            @Override
            TokenResponse request(SalesforceTokenClient client) {
                return client.refresh(REFRESH_TOKEN);
            }
        };

        private final String formField;

        Grant(String formField) {
            this.formField = formField;
        }

        abstract TokenResponse request(SalesforceTokenClient client);
    }

    /** How the client is handed the body of each answer, in place of the stream the connection exposes. */
    @FunctionalInterface
    interface BodyOpening {

        InputStream open(ClientHttpResponse answer) throws IOException;
    }

    /** A builder whose requests return each answer with its body opened by {@code opening}. */
    private static RestClient.Builder openingBodiesWith(BodyOpening opening) {
        return RestClient.builder().requestInterceptor((request, body, execution) ->
                new BodyOpeningResponse(execution.execute(request, body), opening));
    }

    /** An answer exactly as received, except that {@link #getBody()} goes through {@code opening}. */
    private record BodyOpeningResponse(ClientHttpResponse answer, BodyOpening opening) implements ClientHttpResponse {

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return answer.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return answer.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return answer.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            return opening.open(answer);
        }

        @Override
        public void close() {
            answer.close();
        }
    }

    /**
     * Asserts the 503 of a transport failure: Salesforce as the upstream, the socket failure itself as the cause, and
     * its own message as the caller-facing one, never the text of Spring's wrapper, which names internal types.
     */
    private static void assertUnavailableWithSocketMessage(UpstreamUnavailableException thrown,
            Class<? extends IOException> socketFailure, String socketMessage) {
        assertThat(thrown.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
        assertThat(thrown.getCause()).isInstanceOf(socketFailure).hasMessage(socketMessage);
        assertThat(thrown)
                .hasMessage(socketMessage)
                .message()
                .doesNotContain("TokenResponse")
                .doesNotContain("Error while extracting")
                .doesNotContain("I/O error on");
    }

    private SalesforceTokenClient newClient(String tokenUrl) {
        return newClient(tokenUrl, TIMEOUT);
    }

    /** Builds a client over fresh configuration and a plain {@link RestClient} builder. */
    private SalesforceTokenClient newClient(String tokenUrl, Duration timeout) {
        return newClient(tokenUrl, timeout, RestClient.builder());
    }

    /**
     * Builds a client over fresh configuration and {@code restClientBuilder}. Every property is set before
     * construction, so the client sees the same values whether it reads them at construction or on each call.
     */
    private SalesforceTokenClient newClient(String tokenUrl, Duration timeout, RestClient.Builder restClientBuilder) {
        SalesforceOAuthProperties oauthProperties = new SalesforceOAuthProperties();
        oauthProperties.setKey(CLIENT_ID);
        oauthProperties.setSecret(CLIENT_SECRET);
        oauthProperties.getOauth().setAccessTokenUrl(tokenUrl);
        SalesforceProperties salesforceProperties = new SalesforceProperties();
        salesforceProperties.setConnectTimeout(timeout);
        salesforceProperties.setReadTimeout(timeout);
        return new SalesforceTokenClient(restClientBuilder, oauthProperties, salesforceProperties, executor);
    }

    private String tokenUrl() {
        return wireMock.baseUrl() + TOKEN_PATH;
    }

    /**
     * Reserves a free TCP port on 127.0.0.1 that refuses every connection: the socket is bound with address reuse
     * disabled and never listens. While it stays open no other socket can bind or listen on that port, so close it
     * only after the refused call and its assertions have run.
     */
    private static Socket refusingLoopbackPort() throws IOException {
        Socket reservation = new Socket();
        try {
            reservation.setReuseAddress(false);
            reservation.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0));
            return reservation;
        } catch (IOException | RuntimeException e) {
            reservation.close();
            throw e;
        }
    }

    /** The {@code http://127.0.0.1:<port>} base URL of a reservation made by {@link #refusingLoopbackPort()}. */
    private static String refusingBaseUrl(Socket reservation) {
        return "http://" + reservation.getLocalAddress().getHostAddress() + ":" + reservation.getLocalPort();
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
}
