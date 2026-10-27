/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.client;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.netty.handler.timeout.ReadTimeoutException;

import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.UpstreamFailureKind.Category;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamRateLimitException;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamUnavailableException;

/**
 * Unit test of {@link DynamicsFailureClassifier}, the Microsoft Dynamics 365 classification of the approved connector
 * failure handling (SC-9): HTTP 401 from Dataverse or Entra and an Entra token rejection are authentication failures,
 * HTTP 429 is a rate limit carrying the vendor's {@code Retry-After}, every timeout and connectivity failure is
 * unavailable, and anything else is rethrown unchanged.
 *
 * <p>The failures are the {@code WebClient} and Spring Security OAuth 2.0 exception types the Dataverse Web API
 * client and its Entra client-credentials token request raise, built in memory: no request is sent, no Spring context
 * starts and no credential is read. Wherever a classification decides whether a contact create is repeated, the
 * decision is proved through a real {@link UpstreamCallExecutor} that counts the vendor call and the
 * re-authentication (token eviction) step.
 */
class DynamicsFailureClassifierTest {

    /** Carried by the request exceptions only, as {@code WebClient} reports a failed contact create; never called. */
    private static final URI CONTACTS_URI = URI.create("http://localhost/api/data/v9.2/contacts");

    /** The Spring Security client registration the Entra token request runs under; any name serves the classifier. */
    private static final String CLIENT_REGISTRATION_ID = "dynamics";

    private static final String CONTACT_NOT_FOUND = "Contact With Id = x Does Not Exist";

    /** Dataverse's OData error body. */
    private static final String DATAVERSE_NOT_FOUND_BODY =
            "{\"error\":{\"code\":\"0x80040217\",\"message\":\"" + CONTACT_NOT_FOUND + "\"}}";

    private static final String THROTTLED_MESSAGE =
            "Number of requests exceeded the limit of 6000 over time window of 300 seconds.";

    /** Dataverse's service-protection error body, sent with HTTP 429. */
    private static final String DATAVERSE_THROTTLED_BODY =
            "{\"error\":{\"code\":\"0x80072322\",\"message\":\"" + THROTTLED_MESSAGE + "\"}}";

    private static final String INVALID_CLIENT = "invalid_client";

    private static final String INVALID_SECRET_DESCRIPTION = "AADSTS7000215: Invalid client secret provided.";

    private static final String RETRY_AFTER_SECONDS = "7";

    private static final String OK = "ok";

    /**
     * The SC-9 interface's own message lookup, implementing nothing but the two abstract methods, so the fallback
     * cases compare with whatever the interface default returns rather than with Spring's message wording.
     */
    private static final UpstreamFailureClassifier INTERFACE_DEFAULT = new UpstreamFailureClassifier() {

        @Override
        public UpstreamSystem system() {
            return UpstreamSystem.MICROSOFT_DYNAMICS_365;
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

    private DynamicsFailureClassifier classifier;

    private UpstreamCallExecutor executor;

    @BeforeEach
    void createClassifier() {
        classifier = new DynamicsFailureClassifier();
        executor = new UpstreamCallExecutor(List.of(classifier));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // system()
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void servesMicrosoftDynamics365() {
        assertThat(classifier.system()).isEqualTo(UpstreamSystem.MICROSOFT_DYNAMICS_365);
        assertThat(classifier.system().displayName()).isEqualTo("Microsoft Dynamics 365");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // HTTP status: 401 → authentication, 429 → rate limit
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void unauthorizedIsAuthentication() {
        WebClientResponseException unauthorized = responseException(401, new HttpHeaders(), "");

        assertThat(classifier.classify(unauthorized).category()).isEqualTo(Category.AUTHENTICATION);
        assertThat(classifier.classify(new IllegalStateException("wrapped", unauthorized)))
                .isEqualTo(UpstreamFailureKind.AUTHENTICATION);
    }

    @Test
    void tooManyRequestsIsRateLimitCarryingRetryAfter() {
        WebClientResponseException throttled =
                responseException(429, retryAfterHeader(RETRY_AFTER_SECONDS), DATAVERSE_THROTTLED_BODY);

        UpstreamFailureKind kind = classifier.classify(throttled);

        assertThat(kind.category()).isEqualTo(Category.RATE_LIMIT);
        assertThat(kind.retryAfter()).isEqualTo(RETRY_AFTER_SECONDS);
        assertThat(kind.retryAfterValue()).contains(RETRY_AFTER_SECONDS);
        assertThat(classifier.classify(new RuntimeException("wrapped", throttled))).isEqualTo(kind);
    }

    @Test
    void tooManyRequestsWithoutRetryAfterHasNullRetryAfter() {
        UpstreamFailureKind kind =
                classifier.classify(responseException(429, new HttpHeaders(), DATAVERSE_THROTTLED_BODY));

        assertThat(kind.category()).isEqualTo(Category.RATE_LIMIT);
        assertThat(kind.retryAfter()).isNull();
        assertThat(kind.retryAfterValue()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Entra token rejection → authentication
    // ---------------------------------------------------------------------------------------------------------------

    /** Entra rejecting the client or grant, as Spring Security reports the token request's OAuth 2.0 error. */
    static Stream<Named<OAuth2AuthorizationException>> entraRejections() {
        return Stream.of(
                named("OAuth2AuthorizationException invalid_client",
                        new OAuth2AuthorizationException(new OAuth2Error(INVALID_CLIENT))),
                named("ClientAuthorizationException invalid_client",
                        new ClientAuthorizationException(new OAuth2Error(INVALID_CLIENT), CLIENT_REGISTRATION_ID)),
                named("ClientAuthorizationException unauthorized_client",
                        new ClientAuthorizationException(new OAuth2Error("unauthorized_client"),
                                CLIENT_REGISTRATION_ID)),
                named("ClientAuthorizationException invalid_grant",
                        new ClientAuthorizationException(new OAuth2Error("invalid_grant"), CLIENT_REGISTRATION_ID)),
                named("ClientAuthorizationException invalid_scope",
                        new ClientAuthorizationException(new OAuth2Error("invalid_scope"), CLIENT_REGISTRATION_ID)));
    }

    @ParameterizedTest
    @MethodSource("entraRejections")
    void entraTokenRejectionIsAuthentication(OAuth2AuthorizationException rejection) {
        assertThat(classifier.classify(rejection)).isEqualTo(UpstreamFailureKind.AUTHENTICATION);
        assertThat(classifier.classify(new RuntimeException("wrapped", rejection)))
                .isEqualTo(UpstreamFailureKind.AUTHENTICATION);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Timeouts and connectivity failures → unavailable, never retried
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Every timeout and connectivity cause of the approved failure handling, each built afresh on every use, so no
     * case can alter a cause another case uses. Netty's {@code ReadTimeoutException} is its shared, stackless
     * {@code INSTANCE}, which the cases only wrap and never give a cause.
     */
    static Stream<Named<Supplier<Exception>>> unavailableCauses() {
        return Stream.of(
                cause("SocketTimeoutException", () -> new SocketTimeoutException("read timed out")),
                cause("HttpTimeoutException", () -> new HttpTimeoutException("timed out")),
                cause("Netty ReadTimeoutException", () -> ReadTimeoutException.INSTANCE),
                cause("ConnectException", () -> new ConnectException("Connection refused")),
                cause("UnknownHostException", () -> new UnknownHostException("nohost")),
                cause("NoRouteToHostException", () -> new NoRouteToHostException("no route")),
                cause("SocketException (connection reset)", () -> new SocketException("Connection reset")));
    }

    /**
     * Each unavailable cause bare, as the JDK or Netty raises it, and wrapped in a {@link WebClientRequestException},
     * as {@code WebClient} reports a Dataverse or Entra request that got no response.
     */
    static Stream<Named<Exception>> unavailableFailures() {
        return unavailableCauses().flatMap(cause -> Stream.<Named<Exception>>of(
                named(cause.getName(), cause.getPayload().get()),
                named(cause.getName() + " in WebClientRequestException", requestException(cause.getPayload().get()))));
    }

    @ParameterizedTest
    @MethodSource("unavailableFailures")
    void timeoutAndConnectivityFailuresAreUnavailableAndNeverRetried(Exception failure) {
        assertThat(classifier.classify(failure)).isEqualTo(UpstreamFailureKind.UNAVAILABLE);

        Throwable thrown = execute(alwaysFailing(failure));

        assertThat(thrown).isInstanceOfSatisfying(UpstreamUnavailableException.class, e -> {
            assertThat(e.getSystem()).isEqualTo(UpstreamSystem.MICROSOFT_DYNAMICS_365);
            assertThat(e.getCause()).isSameAs(failure);
            assertThat(e).hasMessage(classifier.upstreamMessage(failure));
        });
        assertThat(calls).hasValue(1);
        assertThat(reauths).hasValue(0);
    }

    /**
     * Each unavailable cause behind an authentication signal: a Dataverse 401 it caused, and an Entra rejection
     * wrapping the token request that got no response.
     */
    static Stream<Named<Exception>> unavailableBehindAuthenticationSignals() {
        return unavailableCauses().flatMap(cause -> Stream.<Named<Exception>>of(
                named("401 caused by " + cause.getName(), unauthorizedCausedBy(cause.getPayload().get())),
                named("Entra rejection caused by " + cause.getName(),
                        new ClientAuthorizationException(new OAuth2Error(INVALID_CLIENT), CLIENT_REGISTRATION_ID,
                                requestException(cause.getPayload().get())))));
    }

    /**
     * A timed-out or disconnected request may already have created the contact, so an authentication signal in the
     * same chain must not lead to the re-authentication retry.
     */
    @ParameterizedTest
    @MethodSource("unavailableBehindAuthenticationSignals")
    void unavailableTakesPrecedenceOverAuthenticationInTheSameChain(Exception failure) {
        assertThat(classifier.classify(failure)).isEqualTo(UpstreamFailureKind.UNAVAILABLE);

        assertThat(execute(alwaysFailing(failure))).isInstanceOf(UpstreamUnavailableException.class);
        assertThat(calls).hasValue(1);
        assertThat(reauths).hasValue(0);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Everything else → OTHER, rethrown unchanged
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Dataverse and Entra failures outside the three approved modes, the OAuth 2.0 codes of a failed token exchange
     * (an unreadable token response, an Entra server error or overload) included.
     */
    static Stream<Named<Exception>> otherFailures() {
        return Stream.of(
                named("400 Dataverse error", responseException(400, new HttpHeaders(), DATAVERSE_NOT_FOUND_BODY)),
                named("403", responseException(403, new HttpHeaders(), "")),
                named("404 Dataverse error", responseException(404, new HttpHeaders(), DATAVERSE_NOT_FOUND_BODY)),
                named("500", responseException(500, new HttpHeaders(), "")),
                named("IllegalArgumentException", new IllegalArgumentException("bad")),
                named("unreadable token response",
                        new OAuth2AuthorizationException(new OAuth2Error("invalid_token_response"))),
                named("Entra server_error",
                        new ClientAuthorizationException(new OAuth2Error("server_error"), CLIENT_REGISTRATION_ID)),
                named("Entra temporarily_unavailable",
                        new ClientAuthorizationException(new OAuth2Error("temporarily_unavailable"),
                                CLIENT_REGISTRATION_ID)));
    }

    @ParameterizedTest
    @MethodSource("otherFailures")
    void unrecognisedFailuresAreOther(Exception failure) {
        assertThat(classifier.classify(failure)).isEqualTo(UpstreamFailureKind.OTHER);
    }

    @Test
    void nullIsOther() {
        assertThat(classifier.classify(null)).isEqualTo(UpstreamFailureKind.OTHER);
    }

    @Test
    void cyclicCauseChainTerminates() {
        Exception a = new Exception("A");
        Exception b = new Exception("B", a);
        a.initCause(b);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(classifier.classify(a)).isEqualTo(UpstreamFailureKind.OTHER);
            assertThat(classifier.upstreamMessage(a)).isEqualTo("A");
        });
    }

    @Test
    void cyclicCauseChainHoldingUnauthorizedIsAuthentication() {
        WebClientResponseException unauthorized = responseException(401, new HttpHeaders(), "");
        RuntimeException wrapper = new RuntimeException("wrapped", unauthorized);
        unauthorized.initCause(wrapper);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(classifier.classify(wrapper)).isEqualTo(UpstreamFailureKind.AUTHENTICATION);
            assertThat(classifier.upstreamMessage(wrapper)).isEqualTo("wrapped");
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // upstreamMessage: Dataverse's or Entra's own error text, else the interface default
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void upstreamMessageIsTheDataverseErrorMessage() {
        WebClientResponseException notFound = responseException(400, new HttpHeaders(), DATAVERSE_NOT_FOUND_BODY);

        assertThat(classifier.upstreamMessage(notFound)).isEqualTo(CONTACT_NOT_FOUND);
        assertThat(classifier.upstreamMessage(new RuntimeException("wrapped", notFound))).isEqualTo(CONTACT_NOT_FOUND);
    }

    /** Entra OAuth 2.0 error bodies, as the token WebClient keeps a token 401, 429 or 5xx with its status. */
    static Stream<Arguments> entraErrorBodies() {
        return Stream.of(
                arguments(named("error with description", "{\"error\":\"" + INVALID_CLIENT
                        + "\",\"error_description\":\"" + INVALID_SECRET_DESCRIPTION + "\"}"),
                        INVALID_SECRET_DESCRIPTION),
                arguments(named("error without description", "{\"error\":\"temporarily_unavailable\"}"),
                        "temporarily_unavailable"),
                arguments(named("error with blank description",
                        "{\"error\":\"" + INVALID_CLIENT + "\",\"error_description\":\" \"}"), INVALID_CLIENT));
    }

    @ParameterizedTest
    @MethodSource("entraErrorBodies")
    void upstreamMessageOfAnEntraErrorBodyIsItsDescriptionElseItsCode(String body, String expected) {
        assertThat(classifier.upstreamMessage(responseException(401, new HttpHeaders(), body))).isEqualTo(expected);
    }

    /** Entra OAuth 2.0 errors as Spring Security parses them from a rejected token request. */
    static Stream<Arguments> entraParsedErrors() {
        return Stream.of(
                arguments(named("OAuth2AuthorizationException with description", new OAuth2AuthorizationException(
                        new OAuth2Error(INVALID_CLIENT, INVALID_SECRET_DESCRIPTION, null))),
                        INVALID_SECRET_DESCRIPTION),
                arguments(named("ClientAuthorizationException with description", new ClientAuthorizationException(
                        new OAuth2Error(INVALID_CLIENT, INVALID_SECRET_DESCRIPTION, null), CLIENT_REGISTRATION_ID)),
                        INVALID_SECRET_DESCRIPTION),
                arguments(named("without description",
                        new OAuth2AuthorizationException(new OAuth2Error(INVALID_CLIENT))), INVALID_CLIENT),
                arguments(named("blank description",
                        new OAuth2AuthorizationException(new OAuth2Error(INVALID_CLIENT, "  ", null))), INVALID_CLIENT),
                arguments(named("wrapped", new RuntimeException("wrapped", new OAuth2AuthorizationException(
                        new OAuth2Error(INVALID_CLIENT, INVALID_SECRET_DESCRIPTION, null)))),
                        INVALID_SECRET_DESCRIPTION));
    }

    @ParameterizedTest
    @MethodSource("entraParsedErrors")
    void upstreamMessageOfAnEntraRejectionIsItsDescriptionElseItsCode(Exception rejection, String expected) {
        assertThat(classifier.upstreamMessage(rejection)).isEqualTo(expected);
    }

    @Test
    void upstreamMessageOfAPlainFailureIsTheFirstMessageOfItsChain() {
        IllegalStateException plain = new IllegalStateException("plain message");

        assertThat(classifier.upstreamMessage(plain)).isEqualTo("plain message")
                .isEqualTo(INTERFACE_DEFAULT.upstreamMessage(plain));
    }

    static Stream<Named<String>> bodiesWithoutErrorText() {
        return Stream.of(
                named("HTML", "<html>"),
                named("empty", ""),
                named("blank", "   "),
                named("Dataverse error without message", "{\"error\":{\"code\":\"0x80040217\"}}"),
                named("Dataverse error with blank message", "{\"error\":{\"code\":\"0x80040217\",\"message\":\" \"}}"),
                named("Entra error with blank code", "{\"error\":\" \"}"),
                named("JSON without error", "{\"status\":502}"),
                named("JSON array", "[]"),
                named("JSON null", "null"));
    }

    @ParameterizedTest
    @MethodSource("bodiesWithoutErrorText")
    void upstreamMessageFallsBackToTheInterfaceDefault(String body) {
        WebClientResponseException badGateway = responseException(502, new HttpHeaders(), body);

        assertThatCode(() -> classifier.upstreamMessage(badGateway)).doesNotThrowAnyException();
        assertThat(classifier.upstreamMessage(badGateway)).isEqualTo(INTERFACE_DEFAULT.upstreamMessage(badGateway))
                .isNotBlank();
    }

    @Test
    void upstreamMessageRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> classifier.upstreamMessage(null));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Through the executor: one token eviction and retry for authentication, no retry otherwise
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void tooManyRequestsIsNeverRetriedAndCarriesRetryAfter() {
        Throwable thrown = execute(alwaysFailing(
                responseException(429, retryAfterHeader(RETRY_AFTER_SECONDS), DATAVERSE_THROTTLED_BODY)));

        assertThat(thrown).isInstanceOfSatisfying(UpstreamRateLimitException.class, e -> {
            assertThat(e.getSystem()).isEqualTo(UpstreamSystem.MICROSOFT_DYNAMICS_365);
            assertThat(e.getRetryAfter()).isEqualTo(RETRY_AFTER_SECONDS);
            assertThat(e).hasMessage(THROTTLED_MESSAGE);
        });
        assertThat(calls).hasValue(1);
        assertThat(reauths).hasValue(0);
    }

    @Test
    void unauthorizedOnEveryAttemptEvictsTheTokenOnceAndFailsAfterOneRetry() {
        WebClientResponseException unauthorized = responseException(401, new HttpHeaders(), "");

        Throwable thrown = execute(alwaysFailing(unauthorized));

        assertThat(thrown).isInstanceOfSatisfying(UpstreamAuthenticationException.class, e -> {
            assertThat(e.getSystem()).isEqualTo(UpstreamSystem.MICROSOFT_DYNAMICS_365);
            assertThat(e.getCause()).isSameAs(unauthorized);
            assertThat(e).hasMessage(INTERFACE_DEFAULT.upstreamMessage(unauthorized));
        });
        assertThat(calls).hasValue(2);
        assertThat(reauths).hasValue(1);
        assertThat(reauthsBeforeEachCall).as("the retry starts after the token eviction").containsExactly(0, 1);
    }

    @Test
    void unauthorizedOnceThenSuccessReturnsTheRetriedResult() throws Exception {
        String result = executor.call(UpstreamSystem.MICROSOFT_DYNAMICS_365,
                failingThenSucceeding(responseException(401, new HttpHeaders(), "")), reauths::incrementAndGet);

        assertThat(result).isEqualTo(OK);
        assertThat(calls).hasValue(2);
        assertThat(reauths).hasValue(1);
        assertThat(reauthsBeforeEachCall).as("the retry starts after the token eviction").containsExactly(0, 1);
    }

    @Test
    void entraRejectionOfTheRenewedTokenEndsTheAttempt() {
        ClientAuthorizationException rejected = new ClientAuthorizationException(
                new OAuth2Error(INVALID_CLIENT, INVALID_SECRET_DESCRIPTION, null), CLIENT_REGISTRATION_ID);

        Throwable thrown = execute(failingThenSucceeding(responseException(401, new HttpHeaders(), ""), rejected));

        assertThat(thrown).isInstanceOfSatisfying(UpstreamAuthenticationException.class, e -> {
            assertThat(e.getSystem()).isEqualTo(UpstreamSystem.MICROSOFT_DYNAMICS_365);
            assertThat(e.getCause()).isSameAs(rejected);
            assertThat(e).hasMessage(INVALID_SECRET_DESCRIPTION);
        });
        assertThat(calls).hasValue(2);
        assertThat(reauths).hasValue(1);
    }

    @Test
    void timeoutOfTheRetryIsNotRetriedAgain() {
        WebClientRequestException timedOut = requestException(new SocketTimeoutException("read timed out"));

        Throwable thrown = execute(failingThenSucceeding(responseException(401, new HttpHeaders(), ""), timedOut));

        assertThat(thrown).isInstanceOfSatisfying(UpstreamUnavailableException.class,
                e -> assertThat(e.getCause()).isSameAs(timedOut));
        assertThat(calls).hasValue(2);
        assertThat(reauths).hasValue(1);
    }

    @Test
    void otherFailureIsRethrownByIdentityWithoutRetry() {
        WebClientResponseException badRequest = responseException(400, new HttpHeaders(), DATAVERSE_NOT_FOUND_BODY);

        assertThat(execute(alwaysFailing(badRequest))).isSameAs(badRequest);
        assertThat(calls).hasValue(1);
        assertThat(reauths).hasValue(0);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Runs a Dataverse call through the executor with a counting re-authentication (token eviction) step.
     *
     * @return what the executor threw, or {@code null} when the call succeeded
     */
    private Throwable execute(UpstreamCall<String> call) {
        return catchThrowable(
                () -> executor.call(UpstreamSystem.MICROSOFT_DYNAMICS_365, call, reauths::incrementAndGet));
    }

    /** A Dataverse call that fails with {@code failure} on every attempt. */
    private UpstreamCall<String> alwaysFailing(Exception failure) {
        return () -> {
            recordAttempt();
            throw failure;
        };
    }

    /** A Dataverse call whose first attempts fail with {@code failures}, in order, and whose next attempt succeeds. */
    private UpstreamCall<String> failingThenSucceeding(Exception... failures) {
        return () -> {
            int attempt = recordAttempt();
            if (attempt < failures.length) {
                throw failures[attempt];
            }
            return OK;
        };
    }

    /** @return the zero-based number of the attempt now starting */
    private int recordAttempt() {
        reauthsBeforeEachCall.add(reauths.get());
        return calls.getAndIncrement();
    }

    private static Named<Supplier<Exception>> cause(String name, Supplier<Exception> cause) {
        return named(name, cause);
    }

    /** An HTTP error response as {@code WebClient} raises it from {@code retrieve()}. */
    private static WebClientResponseException responseException(int status, HttpHeaders headers, String body) {
        return WebClientResponseException.create(status, HttpStatus.valueOf(status).getReasonPhrase(), headers,
                body.getBytes(UTF_8), UTF_8);
    }

    /** A transport failure as {@code WebClient} raises it for a request that got no response. */
    private static WebClientRequestException requestException(Throwable cause) {
        return new WebClientRequestException(cause, HttpMethod.POST, CONTACTS_URI, new HttpHeaders());
    }

    private static WebClientResponseException unauthorizedCausedBy(Throwable cause) {
        WebClientResponseException unauthorized = responseException(401, new HttpHeaders(), "");
        unauthorized.initCause(cause);
        return unauthorized;
    }

    private static HttpHeaders retryAfterHeader(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, value);
        return headers;
    }
}
