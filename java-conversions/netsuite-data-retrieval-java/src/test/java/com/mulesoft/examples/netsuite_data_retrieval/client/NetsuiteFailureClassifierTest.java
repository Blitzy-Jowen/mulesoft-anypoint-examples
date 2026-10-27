/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.netty.channel.epoll.Epoll;
import io.netty.channel.unix.Errors;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.timeout.ReadTimeoutException;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.client.PrematureCloseException;

import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamRateLimitException;
import com.mulesoft.examples.netsuite_data_retrieval.exception.UpstreamUnavailableException;

/**
 * Unit test of {@link NetsuiteFailureClassifier}, the NetSuite classification of the approved connector failure
 * handling (SC-16 over SC-9): HTTP 401 is an authentication failure, HTTP 429 a rate limit carrying NetSuite's
 * {@code Retry-After}, every timeout and connectivity failure is unavailable, and anything else is rethrown
 * unchanged.
 *
 * <p>The failures are the {@code WebClient} exception types the NetSuite REST client raises, built in memory: no
 * request is sent, no Spring context starts and no classpath resource is read. Wherever a classification decides
 * whether a call is repeated, the decision is proved through a real {@link UpstreamCallExecutor} that counts the
 * vendor call and the re-authentication (token eviction) step.
 *
 * <p>This is the reference copy of an SC-16 test: {@code netsuite-data-retrieval-java} carries it with only the
 * {@code package} line and the project prefix of the {@code exception} imports changed, so it uses nothing beyond
 * the SC-9 and SC-16 types, Spring's web types, Netty, Reactor Netty, the JDK, JUnit 5 and AssertJ.
 */
class NetsuiteFailureClassifierTest {

    /** Carried by the request exceptions only, as {@code WebClient} reports a failed SuiteQL call; never contacted. */
    private static final URI SUITEQL_URI = URI.create("http://localhost/services/rest/query/v1/suiteql");

    private static final String INVALID_QUERY_DETAIL = "Invalid search query. Field 'foo' was not found.";

    /** NetSuite's REST error format with one error detail. */
    private static final String FULL_ERROR_BODY = "{\"type\":\"https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.1\","
            + "\"title\":\"Bad Request\",\"status\":400,"
            + "\"o:errorDetails\":[{\"detail\":\"" + INVALID_QUERY_DETAIL + "\",\"o:errorCode\":\"INVALID_PARAMETER\"}]}";

    /** NetSuite's REST error format without error details, as the token and record endpoints answer a 401. */
    private static final String TITLE_ONLY_BODY = "{\"type\":\"https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.2\","
            + "\"title\":\"Unauthorized\",\"status\":401}";

    private static final String RETRY_AFTER_SECONDS = "30";

    private static final String OAUTH_ERROR_DESCRIPTION = "Invalid client assertion signature.";

    /** The OAuth 2.0 token error format (RFC 6749, section 5.2), as the token endpoint answers a rejected client. */
    private static final String OAUTH_ERROR_BODY = "{\"error\":\"invalid_client\",\"error_description\":\""
            + OAUTH_ERROR_DESCRIPTION + "\"}";

    /** Carried by the token request's response exceptions, so their message names it; never contacted. */
    private static final URI TOKEN_URI =
            URI.create("https://tstdrv1234567.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token");

    /** The token request as {@code WebClient} attaches it to the response exception of {@code retrieve()}. */
    private static final HttpRequest TOKEN_REQUEST = new HttpRequest() {

        @Override
        public HttpMethod getMethod() {
            return HttpMethod.POST;
        }

        @Override
        public URI getURI() {
            return TOKEN_URI;
        }

        @Override
        public HttpHeaders getHeaders() {
            return new HttpHeaders();
        }
    };

    /**
     * The SC-9 interface's own message lookup, implementing nothing but the two abstract methods, so the fallback
     * cases compare with whatever the interface default returns rather than with Spring's message wording.
     */
    private static final UpstreamFailureClassifier INTERFACE_DEFAULT = new UpstreamFailureClassifier() {

        @Override
        public UpstreamSystem system() {
            return UpstreamSystem.NETSUITE;
        }

        @Override
        public UpstreamFailureKind classify(Throwable failure) {
            return UpstreamFailureKind.OTHER;
        }
    };

    private final AtomicInteger calls = new AtomicInteger();

    private final AtomicInteger reauths = new AtomicInteger();

    /** The number of re-authentications already run when each attempt of the vendor call started. */
    private final List<Integer> reauthsBeforeEachCall = new ArrayList<>();

    private NetsuiteFailureClassifier classifier;

    private UpstreamCallExecutor executor;

    @BeforeEach
    void createClassifier() {
        classifier = new NetsuiteFailureClassifier();
        executor = new UpstreamCallExecutor(List.of(classifier));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // system()
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void servesNetsuite() {
        assertThat(classifier.system()).isEqualTo(UpstreamSystem.NETSUITE);
        assertThat(classifier.system().displayName()).isEqualTo("NetSuite");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // HTTP status: 401 → authentication, 429 → rate limit
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void unauthorizedIsAuthentication() {
        WebClientResponseException unauthorized = responseException(401, new HttpHeaders(), TITLE_ONLY_BODY);

        assertThat(classifier.classify(unauthorized)).isEqualTo(UpstreamFailureKind.AUTHENTICATION);
        assertThat(classifier.classify(new RuntimeException("wrapped", unauthorized)))
                .isEqualTo(UpstreamFailureKind.AUTHENTICATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {RETRY_AFTER_SECONDS, "Wed, 21 Oct 2015 07:28:00 GMT"})
    void tooManyRequestsIsRateLimitWithRetryAfter(String retryAfter) {
        UpstreamFailureKind kind = classifier.classify(responseException(429, retryAfterHeader(retryAfter), ""));

        assertThat(kind.category()).isEqualTo(UpstreamFailureKind.Category.RATE_LIMIT);
        assertThat(kind.retryAfter()).isEqualTo(retryAfter);
    }

    @Test
    void tooManyRequestsWithoutRetryAfterHasNullRetryAfter() {
        UpstreamFailureKind kind = classifier.classify(responseException(429, new HttpHeaders(), ""));

        assertThat(kind.category()).isEqualTo(UpstreamFailureKind.Category.RATE_LIMIT);
        assertThat(kind.retryAfter()).isNull();
    }

    static Stream<Arguments> unauthorizedResponses() {
        return Stream.of(
                arguments(named("REST record or SuiteQL call",
                        responseException(401, new HttpHeaders(), TITLE_ONLY_BODY)), "Unauthorized"),
                arguments(named("token request", tokenResponseException(401, OAUTH_ERROR_BODY)),
                        OAUTH_ERROR_DESCRIPTION));
    }

    @ParameterizedTest
    @MethodSource("unauthorizedResponses")
    void unauthorizedOnEveryAttemptEvictsTheTokenOnceAndFailsAfterOneRetry(WebClientResponseException unauthorized,
            String expectedMessage) {
        Throwable thrown = callThroughExecutor(unauthorized);

        assertThat(thrown).isInstanceOfSatisfying(UpstreamAuthenticationException.class, e -> {
            assertThat(e.getSystem()).isEqualTo(UpstreamSystem.NETSUITE);
            assertThat(e).hasMessage(expectedMessage);
            assertThat(e.getCause()).isInstanceOf(WebClientResponseException.class);
        });
        assertThat(calls).hasValue(2);
        assertThat(reauths).hasValue(1);
        assertThat(reauthsBeforeEachCall).as("the retry starts after the token eviction").containsExactly(0, 1);
    }

    @Test
    void tooManyRequestsIsNeverRetried() {
        Throwable thrown = callThroughExecutor(responseException(429, retryAfterHeader(RETRY_AFTER_SECONDS),
                "{\"title\":\"Too Many Requests\",\"status\":429}"));

        assertThat(thrown).isInstanceOfSatisfying(UpstreamRateLimitException.class, e -> {
            assertThat(e.getSystem()).isEqualTo(UpstreamSystem.NETSUITE);
            assertThat(e.getRetryAfter()).isEqualTo(RETRY_AFTER_SECONDS);
            assertThat(e).hasMessage("Too Many Requests");
        });
        assertThat(calls).hasValue(1);
        assertThat(reauths).hasValue(0);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Timeouts and connectivity failures → unavailable, never retried
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Every timeout and connectivity cause, the closed-connection failures Reactor Netty and Netty raise beneath
     * {@code WebClient} included. Reactor Netty's {@code PrematureCloseException} is its shared
     * {@code TEST_EXCEPTION}, which the cases only wrap and never give a cause. The native transport's
     * connection-reset {@code NativeIoException} is present where the epoll transport loads, because its constructor
     * needs that transport's library, without which the transport cannot raise it either.
     */
    static Stream<Named<Throwable>> unavailableCauses() {
        Stream<Named<Throwable>> causes = Stream.of(
                named("SocketTimeoutException", new SocketTimeoutException("Read timed out")),
                named("HttpTimeoutException", new HttpTimeoutException("request timed out")),
                named("Netty ReadTimeoutException", ReadTimeoutException.INSTANCE),
                named("ConnectException", new ConnectException("Connection refused")),
                named("UnknownHostException", new UnknownHostException("tstdrv.suitetalk.api.netsuite.com")),
                named("NoRouteToHostException", new NoRouteToHostException("No route to host")),
                named("SocketException (connection reset)", new SocketException("Connection reset")),
                named("Reactor Netty PrematureCloseException", PrematureCloseException.TEST_EXCEPTION),
                named("Reactor Netty AbortedException",
                        new AbortedException("Connection has been closed BEFORE send operation")),
                named("Netty PrematureChannelClosureException",
                        new PrematureChannelClosureException("Connection closed before received headers")),
                named("ClosedChannelException", new ClosedChannelException()));
        if (!Epoll.isAvailable()) {
            return causes;
        }
        return Stream.concat(causes, Stream.of(named("Netty NativeIoException (connection reset by peer)",
                new Errors.NativeIoException("recvAddress", Errors.ERRNO_ECONNRESET_NEGATIVE))));
    }

    @ParameterizedTest
    @MethodSource("unavailableCauses")
    void timeoutAndConnectivityFailuresAreUnavailableAndNeverRetried(Throwable cause) {
        WebClientRequestException failure = requestException(cause);

        assertThat(classifier.classify(failure)).isEqualTo(UpstreamFailureKind.UNAVAILABLE);
        assertThat(classifier.classify(cause)).isEqualTo(UpstreamFailureKind.UNAVAILABLE);

        Throwable thrown = callThroughExecutor(failure);

        assertThat(thrown).isInstanceOfSatisfying(UpstreamUnavailableException.class, e -> {
            assertThat(e.getSystem()).isEqualTo(UpstreamSystem.NETSUITE);
            assertThat(e.getCause()).isSameAs(failure);
            assertThat(e).hasMessage(classifier.upstreamMessage(failure));
        });
        assertThat(calls).hasValue(1);
        assertThat(reauths).hasValue(0);
    }

    /**
     * A timed-out or disconnected request may already have been processed, so a 401 in the same chain must not lead
     * to the re-authentication retry.
     */
    @ParameterizedTest
    @MethodSource("unavailableCauses")
    void unavailableTakesPrecedenceOverUnauthorizedInTheSameChain(Throwable cause) {
        WebClientResponseException unauthorized = responseException(401, new HttpHeaders(), TITLE_ONLY_BODY);
        unauthorized.initCause(cause);

        assertThat(classifier.classify(unauthorized)).isEqualTo(UpstreamFailureKind.UNAVAILABLE);

        assertThat(callThroughExecutor(unauthorized)).isInstanceOf(UpstreamUnavailableException.class);
        assertThat(calls).hasValue(1);
        assertThat(reauths).hasValue(0);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Everything else → OTHER, rethrown unchanged
    // ---------------------------------------------------------------------------------------------------------------

    static Stream<Named<Throwable>> otherFailures() {
        return Stream.of(
                named("400 invalid SuiteQL query", responseException(400, new HttpHeaders(), FULL_ERROR_BODY)),
                named("403", responseException(403, new HttpHeaders(), "")),
                named("404", responseException(404, new HttpHeaders(), "")),
                named("500", responseException(500, new HttpHeaders(), "")),
                named("IllegalStateException", new IllegalStateException("boom")));
    }

    @ParameterizedTest
    @MethodSource("otherFailures")
    void unrecognisedFailuresAreOther(Throwable failure) {
        assertThat(classifier.classify(failure)).isEqualTo(UpstreamFailureKind.OTHER);
    }

    @Test
    void nullIsOther() {
        assertThat(classifier.classify(null)).isEqualTo(UpstreamFailureKind.OTHER);
    }

    @Test
    void otherFailureIsRethrownByIdentityWithoutRetry() {
        WebClientResponseException badRequest = responseException(400, new HttpHeaders(), FULL_ERROR_BODY);

        assertThat(callThroughExecutor(badRequest)).isSameAs(badRequest);
        assertThat(calls).hasValue(1);
        assertThat(reauths).hasValue(0);
    }

    @Test
    void cyclicCauseChainTerminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(classifier.classify(a)).isEqualTo(UpstreamFailureKind.OTHER);
            assertThat(classifier.upstreamMessage(a)).isEqualTo("a");
        });
    }

    @Test
    void cyclicCauseChainHoldingUnauthorizedIsAuthentication() {
        WebClientResponseException unauthorized = responseException(401, new HttpHeaders(), TITLE_ONLY_BODY);
        RuntimeException wrapper = new RuntimeException("wrapped", unauthorized);
        unauthorized.initCause(wrapper);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(classifier.classify(wrapper)).isEqualTo(UpstreamFailureKind.AUTHENTICATION);
            assertThat(classifier.upstreamMessage(wrapper)).isEqualTo("Unauthorized");
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // upstreamMessage: NetSuite's own error text, else a status or neutral description, never a WebClient message
    // ---------------------------------------------------------------------------------------------------------------

    static Stream<Arguments> bodiesWithErrorText() {
        String type = "\"type\":\"https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.1\"";
        return Stream.of(
                arguments(named("first error detail", FULL_ERROR_BODY), INVALID_QUERY_DETAIL),
                arguments(named("title without error details", TITLE_ONLY_BODY), "Unauthorized"),
                arguments(named("title with empty error details",
                        "{" + type + ",\"title\":\"Bad Request\",\"status\":400,\"o:errorDetails\":[]}"), "Bad Request"),
                arguments(named("title with blank detail",
                        "{" + type + ",\"title\":\"Bad Request\",\"status\":400,"
                                + "\"o:errorDetails\":[{\"detail\":\"  \",\"o:errorCode\":\"INVALID_PARAMETER\"}]}"),
                        "Bad Request"),
                arguments(named("title before OAuth error fields",
                        "{\"title\":\"Unauthorized\",\"error\":\"invalid_client\",\"error_description\":\"x\"}"),
                        "Unauthorized"),
                arguments(named("OAuth error_description before error", OAUTH_ERROR_BODY), OAUTH_ERROR_DESCRIPTION),
                arguments(named("OAuth error alone", "{\"error\":\"invalid_client\"}"), "invalid_client"),
                arguments(named("OAuth error with blank error_description",
                        "{\"error\":\"invalid_grant\",\"error_description\":\"  \"}"), "invalid_grant"));
    }

    @ParameterizedTest
    @MethodSource("bodiesWithErrorText")
    void upstreamMessageIsNetsuiteErrorText(String body, String expected) {
        assertThat(classifier.upstreamMessage(responseException(400, new HttpHeaders(), body))).isEqualTo(expected);
    }

    @Test
    void upstreamMessageLooksThroughWrappers() {
        WebClientResponseException badRequest = responseException(400, new HttpHeaders(), FULL_ERROR_BODY);

        assertThat(classifier.upstreamMessage(new RuntimeException("wrapped", badRequest)))
                .isEqualTo(INVALID_QUERY_DETAIL);
    }

    static Stream<Arguments> responsesWithoutErrorText() {
        String badGateway = "NetSuite answered HTTP 502 Bad Gateway";
        return Stream.of(
                arguments(502, named("non-JSON", "<html>gateway</html>"), badGateway),
                arguments(502, named("empty", ""), badGateway),
                arguments(502, named("blank", "   "), badGateway),
                arguments(502, named("JSON without detail, title, error_description or error", "{\"status\":502}"),
                        badGateway),
                arguments(502, named("OAuth fields that are not strings", "{\"error\":7,\"error_description\":{}}"),
                        badGateway),
                arguments(401, named("empty", ""), "NetSuite answered HTTP 401 Unauthorized"),
                arguments(599, named("empty", ""), "NetSuite answered HTTP 599"));
    }

    /**
     * {@code WebClient} names the request's method and URL in a response exception's message, so a body without
     * error text is described by the status alone, never by that message.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("responsesWithoutErrorText")
    void upstreamMessageOfAResponseWithoutErrorTextIsItsStatusAndNeverTheUrl(int status, String body,
            String expected) {
        WebClientResponseException response = tokenResponseException(status, body);
        assertThat(response.getMessage()).contains(TOKEN_URI.toString());

        String message = classifier.upstreamMessage(response);

        assertThat(message).isEqualTo(expected).doesNotContain(TOKEN_URI.getHost());
        assertThat(classifier.upstreamMessage(new RuntimeException("wrapped " + TOKEN_URI, response)))
                .isEqualTo(expected);
    }

    static Stream<Arguments> transportFailuresWithMessages() {
        return Stream.of(
                arguments(named("connection refused", new ConnectException("Connection refused: /127.0.0.1:1")),
                        "NetSuite is unreachable"),
                arguments(named("unknown host", new UnknownHostException("tstdrv1234567.suitetalk.api.netsuite.com")),
                        "NetSuite is unreachable"),
                arguments(named("read timeout", new SocketTimeoutException("Read timed out")),
                        "NetSuite did not respond in time"),
                arguments(named("TLS handshake failure", new SSLHandshakeException(
                        "No subject alternative DNS name matching tstdrv1234567.suitetalk.api.netsuite.com found.")),
                        "NetSuite call failed"));
    }

    /**
     * {@code WebClient}'s request exception copies its cause's message, which names the remote address or host, so
     * a failure without a response is described neutrally, beneath a wrapper too, never by that message.
     */
    @ParameterizedTest
    @MethodSource("transportFailuresWithMessages")
    void upstreamMessageOfATransportFailureIsANeutralDescriptionWithoutItsMessage(Throwable cause, String expected) {
        WebClientRequestException failure = requestException(cause);
        assertThat(failure.getMessage()).isEqualTo(cause.getMessage());

        assertThat(classifier.upstreamMessage(failure)).isEqualTo(expected).doesNotContain(cause.getMessage());
        assertThat(classifier.upstreamMessage(new RuntimeException(cause.getMessage(), failure))).isEqualTo(expected);
    }

    static Stream<Named<Throwable>> failuresWithoutAWebClientException() {
        return Stream.of(
                named("missing setting", new IllegalStateException("netsuite.account is not configured")),
                named("body read failure after the response started", PrematureCloseException.TEST_EXCEPTION),
                named("messageless read timeout", ReadTimeoutException.INSTANCE));
    }

    @ParameterizedTest
    @MethodSource("failuresWithoutAWebClientException")
    void upstreamMessageOfAChainWithoutAWebClientExceptionIsTheInterfaceDefault(Throwable failure) {
        assertThat(classifier.upstreamMessage(failure)).isEqualTo(INTERFACE_DEFAULT.upstreamMessage(failure));
    }

    static Stream<Arguments> messagelessTransportFailures() {
        return Stream.of(
                arguments(named("read timeout (Netty ReadTimeoutException.INSTANCE)", ReadTimeoutException.INSTANCE),
                        "NetSuite did not respond in time"),
                arguments(named("closed connection (ClosedChannelException)", new ClosedChannelException()),
                        "NetSuite is unreachable"));
    }

    /**
     * {@code WebClient}'s request exception copies its cause's message, so over a messageless transport failure no
     * throwable in the chain carries one: the 503 message then describes the failure and names no class.
     */
    @ParameterizedTest
    @MethodSource("messagelessTransportFailures")
    void upstreamMessageOfAMessagelessTransportFailureIsANeutralDescription(Throwable cause, String expected) {
        WebClientRequestException failure = requestException(cause);

        assertThat(failure.getMessage()).isNull();
        assertThat(classifier.upstreamMessage(failure)).isEqualTo(expected);
    }

    @Test
    void upstreamMessageRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> classifier.upstreamMessage(null));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Runs a NetSuite call that fails with {@code failure} on every attempt through the executor, counting the
     * attempts and the re-authentications.
     *
     * @return what the executor threw
     */
    private Throwable callThroughExecutor(Exception failure) {
        return catchThrowable(() -> executor.call(UpstreamSystem.NETSUITE, () -> {
            calls.incrementAndGet();
            reauthsBeforeEachCall.add(reauths.get());
            throw failure;
        }, reauths::incrementAndGet));
    }

    /** An HTTP error response as {@code WebClient} raises it from {@code retrieve()}. */
    private static WebClientResponseException responseException(int status, HttpHeaders headers, String body) {
        return WebClientResponseException.create(status, HttpStatus.valueOf(status).getReasonPhrase(), headers,
                body.getBytes(UTF_8), UTF_8);
    }

    /**
     * An HTTP error response to the token request as {@code WebClient} raises it from {@code retrieve()}, with the
     * request attached, so its message names the request's method and URL. A status without a standard reason phrase
     * gets an empty one.
     */
    private static WebClientResponseException tokenResponseException(int status, String body) {
        HttpStatus known = HttpStatus.resolve(status);
        return WebClientResponseException.create(status, known == null ? "" : known.getReasonPhrase(),
                new HttpHeaders(), body.getBytes(UTF_8), UTF_8, TOKEN_REQUEST);
    }

    /** A transport failure as {@code WebClient} raises it for a request that got no response. */
    private static WebClientRequestException requestException(Throwable cause) {
        return new WebClientRequestException(cause, HttpMethod.POST, SUITEQL_URI, new HttpHeaders());
    }

    private static HttpHeaders retryAfterHeader(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, value);
        return headers;
    }
}
