/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Named.named;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.xml.namespace.QName;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.sforce.soap.partner.fault.ApiFault;
import com.sforce.soap.partner.fault.ExceptionCode;
import com.sforce.soap.partner.fault.InvalidFieldFault;
import com.sforce.soap.partner.fault.InvalidSObjectFault;
import com.sforce.soap.partner.fault.LoginFault;
import com.sforce.soap.partner.fault.MalformedQueryFault;
import com.sforce.soap.partner.fault.UnexpectedErrorFault;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.SoapFaultException;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;

import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamRateLimitException;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamUnavailableException;

/**
 * Unit test of {@link SalesforceFailureClassifier}, the Salesforce classification of the shared connector failure
 * handling (SC-15). The failures are the WSC fault and exception types themselves, and wherever a classification
 * decides whether a call is repeated, the decision is proved through a real {@link UpstreamCallExecutor} that counts
 * the vendor call and the re-login step.
 *
 * <p>Every timeout and connectivity failure the classifier must recognise is covered on its own, wrapped the way
 * WSC wraps transport failures, and in one cause chain with an {@code INVALID_SESSION_ID} or
 * {@code REQUEST_LIMIT_EXCEEDED} fault in either order. Each of those is unavailable: the call runs exactly once and
 * the re-login never runs, because a timed-out or disconnected request may already have been processed. Netty is
 * not on this project's classpath, so its {@code ReadTimeoutException} is a class of that name generated with Byte
 * Buddy; the classifier recognises it by name.
 *
 * <p>No Spring context and no mocks are involved. Every case creates its throwables afresh, so the chains built with
 * {@link Throwable#initCause(Throwable)} are never shared between cases.
 */
class SalesforceFailureClassifierTest {

    /** The namespace WSC binds the {@code sf} prefix of a Partner API fault code to. */
    private static final String PARTNER_FAULT_NAMESPACE = "urn:fault.partner.soap.sforce.com";

    private static final String SOAP_ENVELOPE_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String INVALID_SESSION_MESSAGE =
            "Invalid Session ID found in SessionHeader: Illegal Session. Session not found, missing session hash";

    private static final String REQUEST_LIMIT_MESSAGE = "TotalRequests Limit exceeded.";

    /** WSC's message when a request cannot be sent; the transport {@code IOException} is its cause. */
    private static final String SEND_FAILED_MESSAGE =
            "Failed to send request to https://login.salesforce.com/services/Soap/u/65.0";

    private static final String WRAPPER_MESSAGE = "Partner API call failed";

    /** The value the vendor call returns once it succeeds. */
    private static final String RECORDS = "records";

    /**
     * A stand-in for Netty's {@code ReadTimeoutException}: a {@link RuntimeException} subclass carrying Netty's
     * fully qualified class name, defined in a child-first class loader of its own.
     */
    private static final Class<? extends RuntimeException> NETTY_READ_TIMEOUT = new ByteBuddy()
            .subclass(RuntimeException.class)
            .name(UpstreamFailureClassifier.NETTY_READ_TIMEOUT_EXCEPTION)
            .make()
            .load(SalesforceFailureClassifierTest.class.getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST)
            .getLoaded();

    private final SalesforceFailureClassifier classifier = new SalesforceFailureClassifier();

    private final UpstreamCallExecutor executor = new UpstreamCallExecutor(List.of(new SalesforceFailureClassifier()));

    // system() and null

    @Test
    void servesSalesforce() {
        assertThat(classifier.system()).isEqualTo(UpstreamSystem.SALESFORCE);
    }

    @Test
    void nullIsOther() {
        assertThat(classifier.classify(null)).isEqualTo(UpstreamFailureKind.OTHER);
    }

    // INVALID_SESSION_ID → authentication: re-login, then one retry

    static Stream<Named<Exception>> sessionRejections() {
        return Stream.concat(
                signalShapes(ExceptionCode.INVALID_SESSION_ID, INVALID_SESSION_MESSAGE),
                Stream.of(
                        named("UnexpectedErrorFault INVALID_SESSION_ID caused by an IOException that is no timeout"
                                + " or connectivity failure",
                                causedBy(unexpected(ExceptionCode.INVALID_SESSION_ID, INVALID_SESSION_MESSAGE),
                                        new IOException("stream closed"))),
                        named("cyclic chain holding UnexpectedErrorFault INVALID_SESSION_ID",
                                cyclic(new ConnectionException(WRAPPER_MESSAGE),
                                        unexpected(ExceptionCode.INVALID_SESSION_ID, INVALID_SESSION_MESSAGE)))));
    }

    @ParameterizedTest
    @MethodSource("sessionRejections")
    void sessionRejectionWithoutUnavailableCauseIsAuthentication(Exception failure) {
        assertThat(classifier.classify(failure)).isEqualTo(UpstreamFailureKind.AUTHENTICATION);
    }

    @ParameterizedTest
    @MethodSource("sessionRejections")
    void sessionRejectionIsRetriedOnceAfterReLogin(Exception failure) {
        Outcome outcome = callThroughExecutor(failure);

        assertThat(outcome.thrown()).isNull();
        assertThat(outcome.value()).isEqualTo(RECORDS);
        assertThat(outcome.calls()).isEqualTo(2);
        assertThat(outcome.reauths()).isEqualTo(1);
    }

    @Test
    void sessionRejectedAgainAfterReLoginEndsTheAttempt() {
        UnexpectedErrorFault first = unexpected(ExceptionCode.INVALID_SESSION_ID, INVALID_SESSION_MESSAGE);
        UnexpectedErrorFault second = unexpected(ExceptionCode.INVALID_SESSION_ID, INVALID_SESSION_MESSAGE);

        Outcome outcome = callThroughExecutor(first, second);

        assertThat(outcome.thrown()).isInstanceOfSatisfying(UpstreamAuthenticationException.class, rejected -> {
            assertThat(rejected.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
            assertThat(rejected.getCause()).isSameAs(second);
            assertThat(rejected.getMessage()).isEqualTo(INVALID_SESSION_MESSAGE);
        });
        assertThat(outcome.calls()).isEqualTo(2);
        assertThat(outcome.reauths()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("unavailableCausesAlone")
    void retryAfterReLoginThatTimesOutOrDisconnectsIsNotRepeated(Exception retryFailure) {
        UnexpectedErrorFault rejection = unexpected(ExceptionCode.INVALID_SESSION_ID, INVALID_SESSION_MESSAGE);

        Outcome outcome = callThroughExecutor(rejection, retryFailure);

        assertThat(outcome.thrown()).isInstanceOfSatisfying(UpstreamUnavailableException.class, unavailable -> {
            assertThat(unavailable.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
            assertThat(unavailable.getCause()).isSameAs(retryFailure);
        });
        assertThat(outcome.calls()).isEqualTo(2);
        assertThat(outcome.reauths()).isEqualTo(1);
    }

    // REQUEST_LIMIT_EXCEEDED → rate limit without Retry-After, never retried

    static Stream<Named<Exception>> requestLimits() {
        return signalShapes(ExceptionCode.REQUEST_LIMIT_EXCEEDED, REQUEST_LIMIT_MESSAGE);
    }

    @ParameterizedTest
    @MethodSource("requestLimits")
    void requestLimitWithoutUnavailableCauseIsRateLimitWithoutRetryAfter(Exception failure) {
        UpstreamFailureKind kind = classifier.classify(failure);

        assertThat(kind).isEqualTo(UpstreamFailureKind.rateLimit(null));
        assertThat(kind.category()).isEqualTo(UpstreamFailureKind.Category.RATE_LIMIT);
        assertThat(kind.retryAfter()).isNull();
        assertThat(kind.retryAfterValue()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("requestLimits")
    void requestLimitIsNotRetried(Exception failure) {
        Outcome outcome = callThroughExecutor(failure);

        assertThat(outcome.thrown()).isInstanceOfSatisfying(UpstreamRateLimitException.class, limited -> {
            assertThat(limited.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
            assertThat(limited.getRetryAfter()).isNull();
            assertThat(limited.getCause()).isSameAs(failure);
        });
        assertThat(outcome.calls()).isEqualTo(1);
        assertThat(outcome.reauths()).isEqualTo(0);
    }

    // Timeout or connectivity failure → unavailable, never retried

    static Stream<Named<Exception>> unavailableCausesAlone() {
        return unavailableCauses().stream().map(cause -> named(cause.getName(), cause.getPayload().get()));
    }

    static Stream<Named<Exception>> unavailableFailures() {
        return unavailableCauses().stream()
                .flatMap(cause -> unavailableShapes().stream()
                        .map(shape -> named(shape.getName().formatted(cause.getName()),
                                shape.getPayload().apply(cause.getPayload().get()))));
    }

    @ParameterizedTest
    @MethodSource("unavailableFailures")
    void timeoutOrConnectivityFailureIsUnavailable(Exception failure) {
        assertThat(classifier.classify(failure)).isEqualTo(UpstreamFailureKind.UNAVAILABLE);
    }

    @ParameterizedTest
    @MethodSource("unavailableFailures")
    void timeoutOrConnectivityFailureIsNeverRetried(Exception failure) {
        assertUnavailableWithoutRetry(failure);
    }

    // A Salesforce signal and an unavailable cause in one chain, in either order → unavailable, never retried

    static Stream<Named<Exception>> mixedChains() {
        return unavailableCauses().stream()
                .flatMap(cause -> salesforceSignals().stream()
                        .flatMap(signal -> mixedShapes().stream()
                                .map(shape -> named(shape.getName().formatted(signal.getName(), cause.getName()),
                                        shape.getPayload().apply(signal.getPayload().get(),
                                                cause.getPayload().get())))));
    }

    @ParameterizedTest
    @MethodSource("mixedChains")
    void unavailableCauseWinsOverSalesforceSignalInTheSameChain(Exception failure) {
        assertThat(classifier.classify(failure)).isEqualTo(UpstreamFailureKind.UNAVAILABLE);
    }

    @ParameterizedTest
    @MethodSource("mixedChains")
    void mixedChainIsNeitherReAuthenticatedNorRetried(Exception failure) {
        assertUnavailableWithoutRetry(failure);
    }

    // Everything else → OTHER, rethrown unchanged

    static Stream<Named<Exception>> otherFailures() {
        return Stream.of(
                named("LoginFault INVALID_LOGIN", login(ExceptionCode.INVALID_LOGIN,
                        "INVALID_LOGIN: Invalid username, password, security token; or user locked out.")),
                named("MalformedQueryFault MALFORMED_QUERY", fault(new MalformedQueryFault(),
                        ExceptionCode.MALFORMED_QUERY, "unexpected token: FROM")),
                named("InvalidSObjectFault INVALID_TYPE", fault(new InvalidSObjectFault(), ExceptionCode.INVALID_TYPE,
                        "sObject type 'Nope' is not supported.")),
                named("InvalidFieldFault INVALID_FIELD", fault(new InvalidFieldFault(), ExceptionCode.INVALID_FIELD,
                        "No such column 'nope' on entity 'User'.")),
                named("ApiFault INVALID_LOGIN with fault code sf:INVALID_SESSION_ID (exception code decides)",
                        withFaultCode(fault(new ApiFault(), ExceptionCode.INVALID_LOGIN, "INVALID_LOGIN"),
                                partnerFaultCode(ExceptionCode.INVALID_SESSION_ID.name()))),
                named("ApiFault without exception code or fault code", new ApiFault()),
                named("SoapFaultException without fault code", new SoapFaultException(null, "fault without code")),
                named("SoapFaultException sf:INVALID_LOGIN", soapFault(ExceptionCode.INVALID_LOGIN.name(),
                        "INVALID_LOGIN: Invalid username, password, security token; or user locked out.")),
                named("SoapFaultException soapenv:Server", new SoapFaultException(
                        new QName(SOAP_ENVELOPE_NAMESPACE, "Server", "soapenv"), "Internal server error")),
                named("ConnectionException without cause", new ConnectionException(WRAPPER_MESSAGE)),
                named("ConnectionException caused by an IOException that is no timeout or connectivity failure",
                        new ConnectionException(SEND_FAILED_MESSAGE, new IOException("stream closed"))),
                named("IllegalStateException", new IllegalStateException("unexpected state")),
                named("cyclic chain without a Salesforce signal or unavailable cause",
                        cyclic(new ConnectionException(WRAPPER_MESSAGE), new ConnectionException(WRAPPER_MESSAGE))));
    }

    /** Runs under a preemptive time limit, so a cause cycle the walk does not end fails the case instead of hanging. */
    @ParameterizedTest
    @MethodSource("otherFailures")
    void unrecognisedFailureIsOther(Exception failure) {
        UpstreamFailureKind kind = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> classifier.classify(failure));

        assertThat(kind).isEqualTo(UpstreamFailureKind.OTHER);
    }

    @ParameterizedTest
    @MethodSource("otherFailures")
    void unrecognisedFailureIsRethrownUnchangedWithoutRetry(Exception failure) {
        Outcome outcome = callThroughExecutor(failure);

        assertThat(outcome.thrown()).isSameAs(failure);
        assertThat(outcome.calls()).isEqualTo(1);
        assertThat(outcome.reauths()).isEqualTo(0);
    }

    // upstreamMessage

    static Stream<Arguments> upstreamMessages() {
        ApiFault blankMessageFault = fault(new ApiFault(), ExceptionCode.INVALID_SESSION_ID, " ");
        blankMessageFault.initCause(unexpected(ExceptionCode.INVALID_SESSION_ID, INVALID_SESSION_MESSAGE));
        return Stream.of(
                Arguments.of(named("the fault's exception message",
                        unexpected(ExceptionCode.INVALID_SESSION_ID, INVALID_SESSION_MESSAGE)),
                        INVALID_SESSION_MESSAGE),
                Arguments.of(named("the fault's exception message beneath a wrapper with a message of its own",
                        new ConnectionException(SEND_FAILED_MESSAGE,
                                unexpected(ExceptionCode.REQUEST_LIMIT_EXCEEDED, REQUEST_LIMIT_MESSAGE))),
                        REQUEST_LIMIT_MESSAGE),
                Arguments.of(named("the first non-blank exception message, past a fault with a blank one",
                        blankMessageFault),
                        INVALID_SESSION_MESSAGE),
                Arguments.of(named("the exception message exactly as sent, untrimmed",
                        login(ExceptionCode.INVALID_LOGIN, "  INVALID_LOGIN  ")),
                        "  INVALID_LOGIN  "),
                Arguments.of(named("a fault without exception message: the wrapper's message",
                        new ConnectionException(WRAPPER_MESSAGE, new ApiFault())),
                        WRAPPER_MESSAGE),
                Arguments.of(named("a SoapFaultException: its fault string",
                        soapFault(ExceptionCode.INVALID_SESSION_ID.name(), INVALID_SESSION_MESSAGE)),
                        INVALID_SESSION_MESSAGE),
                Arguments.of(named("no fault: the first non-blank message of the chain",
                        new ConnectionException(SEND_FAILED_MESSAGE, new SocketTimeoutException("Read timed out"))),
                        SEND_FAILED_MESSAGE),
                Arguments.of(named("no fault: the cause's message beneath a wrapper without one",
                        new ConnectionException(null, new SocketTimeoutException("Read timed out"))),
                        "Read timed out"),
                Arguments.of(named("no message anywhere: the neutral description, never a class name",
                        new ApiFault()),
                        "Salesforce call failed"),
                Arguments.of(named("cyclic chain without a message: the neutral description, never a class name",
                        cyclic(new ConnectionException(), new ConnectionException())),
                        "Salesforce call failed"));
    }

    @ParameterizedTest
    @MethodSource("upstreamMessages")
    void upstreamMessageIsSalesforcesOwnText(Exception failure, String expected) {
        assertThat(classifier.upstreamMessage(failure)).isEqualTo(expected);
    }

    @Test
    void upstreamMessageRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> classifier.upstreamMessage(null));
    }

    // Case factories

    /**
     * The shapes in which a Salesforce signal reaches the classifier without any timeout or connectivity failure:
     * the typed faults WSC raises, the fault inside a wrapper (WSC's own, or any other one the cause-chain walk must
     * see through), and the code carried only by a fault code.
     */
    private static Stream<Named<Exception>> signalShapes(ExceptionCode code, String message) {
        String name = code.name();
        return Stream.of(
                named("UnexpectedErrorFault " + name, unexpected(code, message)),
                named("LoginFault " + name, login(code, message)),
                named("ApiFault " + name, fault(new ApiFault(), code, message)),
                named("ConnectionException caused by UnexpectedErrorFault " + name,
                        new ConnectionException(WRAPPER_MESSAGE, unexpected(code, message))),
                named("RuntimeException \"wrapped\" caused by UnexpectedErrorFault " + name,
                        new RuntimeException("wrapped", unexpected(code, message))),
                named("ApiFault without exception code, fault code sf:" + name,
                        withFaultCode(new ApiFault(), partnerFaultCode(name))),
                named("SoapFaultException sf:" + name, soapFault(name, message)),
                named("ConnectionException caused by SoapFaultException sf:" + name,
                        new ConnectionException(WRAPPER_MESSAGE, soapFault(name, message))));
    }

    /**
     * Every timeout and connectivity failure the classifier must treat as unavailable, each created afresh and
     * without a cause, so a case may chain it.
     */
    private static List<Named<Supplier<Exception>>> unavailableCauses() {
        return List.of(
                named("SocketTimeoutException", () -> new SocketTimeoutException("Read timed out")),
                named("HttpTimeoutException", () -> new HttpTimeoutException("request timed out")),
                named("Netty ReadTimeoutException", SalesforceFailureClassifierTest::nettyReadTimeout),
                named("ConnectException", () -> new ConnectException("Connection refused")),
                named("UnknownHostException", () -> new UnknownHostException("login.salesforce.com")),
                named("NoRouteToHostException", () -> new NoRouteToHostException("No route to host")),
                named("SocketException (connection reset)", () -> new SocketException("Connection reset")));
    }

    /**
     * The ways a timeout or connectivity failure reaches the classifier with no Salesforce signal: on its own, as
     * the cause WSC wraps in a {@link ConnectionException}, deeper beneath another wrapper, and inside a cyclic
     * chain. Each name holds {@code %s} for the cause's name.
     */
    private static List<Named<Function<Exception, Exception>>> unavailableShapes() {
        return List.of(
                named("%s", cause -> cause),
                named("ConnectionException caused by %s",
                        cause -> new ConnectionException(SEND_FAILED_MESSAGE, cause)),
                named("RuntimeException caused by ConnectionException caused by %s",
                        cause -> new RuntimeException(WRAPPER_MESSAGE,
                                new ConnectionException(SEND_FAILED_MESSAGE, cause))),
                named("ConnectionException caused by %s caused by the same ConnectionException",
                        cause -> cyclic(new ConnectionException(SEND_FAILED_MESSAGE), cause)));
    }

    /**
     * The Salesforce signals that select re-login and retry, or a rate limit, when no unavailable cause is present.
     */
    private static List<Named<Supplier<SoapFaultException>>> salesforceSignals() {
        return List.of(
                named("UnexpectedErrorFault INVALID_SESSION_ID",
                        () -> unexpected(ExceptionCode.INVALID_SESSION_ID, INVALID_SESSION_MESSAGE)),
                named("LoginFault INVALID_SESSION_ID",
                        () -> login(ExceptionCode.INVALID_SESSION_ID, INVALID_SESSION_MESSAGE)),
                named("SoapFaultException sf:INVALID_SESSION_ID", () -> soapFault(
                        ExceptionCode.INVALID_SESSION_ID.name(), INVALID_SESSION_MESSAGE)),
                named("UnexpectedErrorFault REQUEST_LIMIT_EXCEEDED",
                        () -> unexpected(ExceptionCode.REQUEST_LIMIT_EXCEEDED, REQUEST_LIMIT_MESSAGE)));
    }

    /**
     * The two orders of a Salesforce signal and an unavailable cause in one chain: the signal outermost with the
     * cause beneath it, directly or inside WSC's {@link ConnectionException}, and the cause outermost, alone or
     * wrapped in a {@link ConnectionException}, with the signal beneath it. Each name holds {@code %1$s} for the
     * signal's name and {@code %2$s} for the cause's.
     */
    private static List<Named<BiFunction<SoapFaultException, Exception, Exception>>> mixedShapes() {
        return List.of(
                named("%1$s caused by %2$s", SalesforceFailureClassifierTest::causedBy),
                named("%1$s caused by ConnectionException caused by %2$s",
                        (signal, cause) -> causedBy(signal, new ConnectionException(SEND_FAILED_MESSAGE, cause))),
                named("%2$s caused by %1$s", (signal, cause) -> causedBy(cause, signal)),
                named("ConnectionException caused by %2$s caused by %1$s",
                        (signal, cause) -> new ConnectionException(SEND_FAILED_MESSAGE, causedBy(cause, signal))));
    }

    /** An {@link UnexpectedErrorFault} with the exception code, exception message and fault code WSC parses. */
    static UnexpectedErrorFault unexpected(ExceptionCode code, String msg) {
        return fault(new UnexpectedErrorFault(), code, msg);
    }

    /** A {@link LoginFault} with the exception code, exception message and fault code WSC parses. */
    static LoginFault login(ExceptionCode code, String msg) {
        return fault(new LoginFault(), code, msg);
    }

    /** Sets the code, message and {@code sf:} fault code a parsed Partner API fault carries. */
    private static <F extends ApiFault> F fault(F fault, ExceptionCode code, String exceptionMessage) {
        fault.setExceptionCode(code);
        fault.setExceptionMessage(exceptionMessage);
        fault.setFaultCode(partnerFaultCode(code.name()));
        return fault;
    }

    private static <F extends SoapFaultException> F withFaultCode(F fault, QName faultCode) {
        fault.setFaultCode(faultCode);
        return fault;
    }

    /** A fault WSC could not bind to a typed {@link ApiFault}: a fault code and a fault string only. */
    private static SoapFaultException soapFault(String faultCodeLocalPart, String faultString) {
        return new SoapFaultException(partnerFaultCode(faultCodeLocalPart), faultString);
    }

    private static QName partnerFaultCode(String localPart) {
        return new QName(PARTNER_FAULT_NAMESPACE, localPart, "sf");
    }

    /** Sets {@code cause} as the cause of {@code throwable}, which must have none yet, and returns it. */
    private static <T extends Throwable> T causedBy(T throwable, Throwable cause) {
        throwable.initCause(cause);
        return throwable;
    }

    /** Links {@code outer} and {@code inner}, neither with a cause yet, into a two-element cause cycle. */
    private static <T extends Throwable> T cyclic(T outer, Throwable inner) {
        outer.initCause(inner);
        inner.initCause(outer);
        return outer;
    }

    /** A new instance of the Netty {@code ReadTimeoutException} stand-in, without message or cause. */
    private static RuntimeException nettyReadTimeout() {
        try {
            return NETTY_READ_TIMEOUT.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate the Netty ReadTimeoutException stand-in", e);
        }
    }

    // Executor runs

    /**
     * What one {@link UpstreamCallExecutor#call} run produced and how often each callback ran.
     *
     * @param value   the value the executor returned, or {@code null} when it threw
     * @param thrown  what the executor threw, or {@code null} when it returned
     * @param calls   how many times the vendor call ran
     * @param reauths how many times the re-login step ran
     */
    private record Outcome(Object value, Throwable thrown, int calls, int reauths) {
    }

    /**
     * Runs a Salesforce call through the real executor: attempt {@code n} throws {@code failures[n]}, and once the
     * failures are used up the call returns {@link #RECORDS}. The re-login step succeeds and only counts its runs.
     */
    private Outcome callThroughExecutor(Exception... failures) {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger reauths = new AtomicInteger();
        Object[] value = new Object[1];

        Throwable thrown = catchThrowable(() -> value[0] = executor.call(UpstreamSystem.SALESFORCE, () -> {
            int attempt = calls.getAndIncrement();
            if (attempt < failures.length) {
                throw failures[attempt];
            }
            return RECORDS;
        }, reauths::incrementAndGet));

        return new Outcome(value[0], thrown, calls.get(), reauths.get());
    }

    /**
     * Asserts that the executor answers {@code failure} with an {@link UpstreamUnavailableException} for Salesforce
     * that keeps {@code failure} as its cause, after exactly one call and no re-login.
     */
    private void assertUnavailableWithoutRetry(Exception failure) {
        Outcome outcome = callThroughExecutor(failure);

        assertThat(outcome.value()).isNull();
        assertThat(outcome.thrown()).isInstanceOfSatisfying(UpstreamUnavailableException.class, unavailable -> {
            assertThat(unavailable.getSystem()).isEqualTo(UpstreamSystem.SALESFORCE);
            assertThat(unavailable.getCause()).isSameAs(failure);
        });
        assertThat(outcome.calls()).isEqualTo(1);
        assertThat(outcome.reauths()).isEqualTo(0);
    }
}
