/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.dataweave_with_flowreflookup.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;

import com.mulesoft.examples.dataweave_with_flowreflookup.client.UpstreamFailureKind.Category;
import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamRateLimitException;
import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamUnavailableException;

/**
 * Tests shared component SC-9: {@link UpstreamCallExecutor} driven by a scripted vendor call, a scripted
 * re-authentication step and a {@link ScriptedClassifier}, together with the {@link UpstreamFailureClassifier}
 * defaults every vendor classifier relies on ({@link UpstreamFailureClassifier#isUnavailable} and
 * {@link UpstreamFailureClassifier#upstreamMessage}).
 *
 * <p>Covered: the single re-authentication and retry after an authentication rejection, and how a failure of either
 * step ends the attempt; no retry at all for a rate limit, a timeout or a connectivity failure, and the unchanged
 * rethrow of every other failure; the mechanics (interrupt flag, the classification of {@link Error}s and of the
 * upstream exceptions of nested executors, which leave as the same instance unless classified into another
 * category, the classifier registry and null arguments); every timeout, connectivity and closed-connection type,
 * Netty's read timeout and the connection closures of Netty and Reactor Netty included; and the default upstream
 * message.
 *
 * <p>The executor's ERROR line is asserted rather than left unchecked: a Logback {@link ListAppender} on the
 * executor's logger proves that the line names the upstream, the category and the failure's types while no vendor
 * message, cause message, suppressed exception, line break or throwable reaches the log, and that the raised
 * exception still carries the upstream's message for the caller.
 *
 * <p>This is the SC-9 reference test. It is copied to every connector project with only the package root changed,
 * in the {@code package} line and in the imports of the project's own SC-9 types, so it depends on the JDK, JUnit,
 * Logback, Byte Buddy (on every project's test classpath through {@code spring-boot-starter-test}) and the SC-9 types
 * alone. The Netty and Reactor Netty types the classifier recognises by name ({@code ReadTimeoutException},
 * {@code PrematureCloseException}, {@code AbortedException}, {@code PrematureChannelClosureException} and
 * {@code NativeIoException}) are defined under their real names by Byte Buddy in throwaway class loaders, so no
 * project needs Netty to run it; where they are on the classpath, the real classes are checked as well.
 */
class UpstreamCallExecutorTest {

    /** The upstream most cases call; the executor treats every system alike. */
    private static final UpstreamSystem SYSTEM = UpstreamSystem.NETSUITE;

    /** A system for which {@link #executor} has no classifier. */
    private static final UpstreamSystem UNREGISTERED_SYSTEM = UpstreamSystem.WORKDAY;

    /** The {@code Retry-After} value rate-limit classifications carry unless a case says otherwise. */
    private static final String RETRY_AFTER = "30";

    /** Text standing for request data, identifiers or tokens a vendor message can repeat. */
    private static final String SENTINEL = "SENTINEL-5d1e";

    /** A vendor message that repeats request data and, through its line break, forges a second log line. */
    private static final String FORGING_MESSAGE =
            "rejected " + SENTINEL + "\r\n2026-10-26 ERROR forged entry " + SENTINEL;

    /** Longest name the ERROR line writes before cutting it short. */
    private static final int NAME_LOG_LIMIT = 256;

    /** Number of causes the ERROR line names before cutting the chain short. */
    private static final int CAUSE_LOG_LIMIT = 10;

    /** Upper bound on a walk over a cyclic cause chain; a walk that never ends fails here instead of hanging. */
    private static final Duration CYCLE_TIMEOUT = Duration.ofSeconds(5);

    /** What Reactor Netty's {@code PrematureCloseException} says when the server closes before responding. */
    private static final String PREMATURE_CLOSE_MESSAGE = "Connection prematurely closed BEFORE response";

    /** What Reactor Netty's {@code AbortedException} says when the connection closed before the request was sent. */
    private static final String ABORTED_MESSAGE = "Connection has been closed BEFORE send operation";

    /** What Netty's {@code PrematureChannelClosureException} says when the connection closes inside the headers. */
    private static final String PREMATURE_CHANNEL_CLOSURE_MESSAGE = "Connection closed before received headers";

    /** What Netty's native {@code NativeIoException} says for a connection the peer reset. */
    private static final String NATIVE_IO_MESSAGE = "recvAddress(..) failed: Connection reset by peer";

    /** Netty's native transports, whose {@code isAvailable()} loads the transport's library or reports it absent. */
    private static final List<String> NATIVE_TRANSPORTS =
            List.of("io.netty.channel.epoll.Epoll", "io.netty.channel.kqueue.KQueue");

    /** Every vendor call and re-authentication in the order the executor made them. */
    private final List<String> journal = new ArrayList<>();

    private ScriptedClassifier classifier;

    private UpstreamCallExecutor executor;

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        classifier = new ScriptedClassifier(SYSTEM);
        executor = new UpstreamCallExecutor(List.of(classifier));
        logger = (Logger) LoggerFactory.getLogger(UpstreamCallExecutor.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
        // An interrupt case that failed before its own cleanup must not leak the flag into later tests.
        Thread.interrupted();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Retry semantics: one re-authentication and one retry after an authentication rejection
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void authenticationRejectionReauthenticatesOnceAndReturnsTheRetrysValue() throws Exception {
        VendorException expired = classifier.returns(UpstreamFailureKind.AUTHENTICATION,
                new VendorException("expired"));

        String result = executor.call(SYSTEM, new ScriptedCall(expired, "records"), reauthenticate());

        assertEquals("records", result);
        assertEquals(Stage.RETRY.journal, journal);
        assertEquals(List.of(expired), classifier.classified);
        assertEquals(1, classifier.classifyCalls.get());
        assertEquals(List.of("Upstream " + SYSTEM.displayName()
                + " rejected the session [AUTHENTICATION]; re-authenticating and retrying once"), lines(Level.WARN));
        assertEquals(List.of(), lines(Level.ERROR));
    }

    /**
     * A {@code null} re-authentication step is accepted, not rejected: the executor's contract makes an
     * authentication failure without a re-authentication step final, so no retry is made.
     */
    @Test
    void authenticationRejectionWithoutReauthenticationStepIsFinal() {
        VendorException expired = classifier.returns(UpstreamFailureKind.AUTHENTICATION,
                new VendorException("expired"));

        Exception thrown = assertThrows(Exception.class, () -> executor.call(SYSTEM, new ScriptedCall(expired), null));

        assertTranslated(Category.AUTHENTICATION, SYSTEM, expired, thrown);
        assertEquals(Stage.FIRST_ATTEMPT.journal, journal);
        assertEquals(1, classifier.classifyCalls.get());
        assertEquals(List.of(), lines(Level.WARN));
    }

    /**
     * A classified failure ends the attempt at the stage it occurs: at the first attempt for every category but
     * authentication, and at the re-authentication step or the retry for every category, an authentication
     * rejection included. No stage is ever repeated.
     */
    @ParameterizedTest(name = "{1} at {0}")
    @MethodSource("terminalFailures")
    void classifiedFailureEndsTheAttemptAtItsStage(Stage stage, Category category) {
        Exception failure = classifier.returns(kindOf(category), new VendorException(category + " at " + stage));

        Attempt attempt = failAt(stage, failure);

        assertTranslated(category, SYSTEM, failure, attempt.thrown());
        assertEquals(stage.journal, journal);
        assertEquals(stage == Stage.FIRST_ATTEMPT ? List.of(failure) : List.of(attempt.firstFailure(), failure),
                classifier.classified);
        assertEquals(stage == Stage.FIRST_ATTEMPT ? 1 : 2, classifier.classifyCalls.get());
    }

    private static Stream<Arguments> terminalFailures() {
        Stream<Arguments> firstAttempt = Stream.of(Category.RATE_LIMIT, Category.UNAVAILABLE, Category.OTHER)
                .map(category -> Arguments.of(Stage.FIRST_ATTEMPT, category));
        Stream<Arguments> laterStages = Stream.of(Stage.REAUTHENTICATION, Stage.RETRY)
                .flatMap(stage -> Stream.of(Category.values()).map(category -> Arguments.of(stage, category)));
        return Stream.concat(firstAttempt, laterStages);
    }

    @Test
    void runExecutesASuccessfulActionOnce() throws Exception {
        executor.run(SYSTEM, () -> journal.add("call"), reauthenticate());

        assertEquals(Stage.FIRST_ATTEMPT.journal, journal);
        assertEquals(0, classifier.classifyCalls.get());
        assertEquals(List.of(), appender.list);
    }

    @Test
    void runAppliesTheSameRetryToAVoidCall() throws Exception {
        VendorException expired = classifier.returns(UpstreamFailureKind.AUTHENTICATION,
                new VendorException("expired"));
        ScriptedCall call = new ScriptedCall(expired, "ignored");

        executor.run(SYSTEM, call::call, reauthenticate());

        assertEquals(Stage.RETRY.journal, journal);
        assertEquals(List.of(expired), classifier.classified);
        assertEquals(1, classifier.classifyCalls.get());
    }

    @Test
    void runRethrowsAnUnclassifiedFailureUnchanged() {
        VendorException failure = classifier.returns(UpstreamFailureKind.OTHER, new VendorException("unrecognised"));
        ScriptedCall call = new ScriptedCall(failure);

        Exception thrown = assertThrows(Exception.class, () -> executor.run(SYSTEM, call::call, reauthenticate()));

        assertSame(failure, thrown);
        assertEquals(Stage.FIRST_ATTEMPT.journal, journal);
        assertEquals(1, classifier.classifyCalls.get());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // No-retry semantics: rate limits, timeouts and connectivity failures are never retried
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"120", "Wed, 21 Oct 2026 07:28:00 GMT", " "})
    void rateLimitIsNeverRetriedAndCarriesRetryAfterAsClassified(String retryAfter) {
        VendorException throttled = classifier.returns(UpstreamFailureKind.rateLimit(retryAfter),
                new VendorException("throttled"));

        UpstreamRateLimitException thrown = assertThrows(UpstreamRateLimitException.class,
                () -> executor.call(SYSTEM, new ScriptedCall(throttled), reauthenticate()));

        assertEquals(retryAfter, thrown.getRetryAfter());
        assertSame(throttled, thrown.getCause());
        assertEquals(Stage.FIRST_ATTEMPT.journal, journal);
        assertEquals(1, classifier.classifyCalls.get());
    }

    /**
     * Each timeout and connectivity failure, thrown as it is (a real {@code SocketTimeoutException("Read timed out")}
     * among them) and wrapped in a checked vendor exception, is classified by a classifier that delegates to
     * {@link UpstreamFailureClassifier#isUnavailable}: the call is made once and re-authentication never runs.
     */
    @ParameterizedTest
    @MethodSource("unavailableFailures")
    void timeoutOrConnectivityFailureIsNeverRetried(Throwable failure) {
        ScriptedClassifier delegating = ScriptedClassifier.delegatingToIsUnavailable(SYSTEM);
        UpstreamCallExecutor delegatingExecutor = new UpstreamCallExecutor(List.of(delegating));

        UpstreamUnavailableException thrown = assertThrows(UpstreamUnavailableException.class,
                () -> delegatingExecutor.call(SYSTEM, new ScriptedCall(failure), reauthenticate()));

        assertEquals(SYSTEM, thrown.getSystem());
        assertSame(failure, thrown.getCause());
        assertEquals(Stage.FIRST_ATTEMPT.journal, journal);
        assertEquals(List.of(failure), delegating.classified);
        assertEquals(1, delegating.classifyCalls.get());
    }

    /** Every unavailable cause, thrown directly and as the cause of a checked vendor exception. */
    private static Stream<Throwable> unavailableFailures() {
        return unavailableCauses().flatMap(cause -> Stream.<Throwable>of(cause,
                new VendorException("vendor call failed", cause)));
    }

    @ParameterizedTest
    @MethodSource("unclassifiedFailures")
    void otherFailureIsRethrownUnchangedWithoutRetryOrLog(Exception failure) {
        classifier.returns(UpstreamFailureKind.OTHER, failure);

        Exception thrown = assertThrows(Exception.class,
                () -> executor.call(SYSTEM, new ScriptedCall(failure), reauthenticate()));

        assertSame(failure, thrown);
        assertEquals(Stage.FIRST_ATTEMPT.journal, journal);
        assertEquals(1, classifier.classifyCalls.get());
        assertEquals(List.of(), appender.list);
    }

    private static Stream<Exception> unclassifiedFailures() {
        return Stream.of(new VendorException("checked vendor fault"), new IOException("malformed response"),
                new IllegalArgumentException("bad query"));
    }

    @Test
    void classifierReturningNullIsReadAsOther() {
        VendorException failure = classifier.returns(null, new VendorException("unclassifiable"));

        Exception thrown = assertThrows(Exception.class,
                () -> executor.call(SYSTEM, new ScriptedCall(failure), reauthenticate()));

        assertSame(failure, thrown);
        assertEquals(Stage.FIRST_ATTEMPT.journal, journal);
        assertEquals(1, classifier.classifyCalls.get());
    }

    @Test
    void successfulCallReturnsItsValueWithoutClassificationOrLog() throws Exception {
        assertEquals("records", executor.call(SYSTEM, new ScriptedCall("records"), reauthenticate()));

        assertEquals(Stage.FIRST_ATTEMPT.journal, journal);
        assertEquals(List.of(), classifier.classified);
        assertEquals(0, classifier.classifyCalls.get());
        assertEquals(List.of(), appender.list);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Mechanics
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * An {@link Error} or an upstream exception a nested executor raised is classified like any other failure, and
     * leaves as the same instance, with no ERROR line and no further attempt, when classified {@code OTHER}, as its
     * own category, as {@code AUTHENTICATION} for a rate-limit or unavailable exception, or as
     * {@code AUTHENTICATION} for an authentication exception at a stage where that ends the attempt.
     */
    @ParameterizedTest(name = "{2} classified {1} at {0}")
    @MethodSource("failuresRethrownUnchanged")
    void classifiedFailureCarryingItsOutcomeIsRethrownAsTheSameInstance(Stage stage, Category category,
            Throwable failure) {
        classifier.returns(kindOf(category), failure);

        Attempt attempt = failAt(stage, failure);

        assertSame(failure, attempt.thrown());
        assertEquals(stage.journal, journal);
        assertEquals(stage == Stage.FIRST_ATTEMPT ? List.of(failure) : List.of(attempt.firstFailure(), failure),
                classifier.classified);
        assertEquals(stage == Stage.FIRST_ATTEMPT ? 1 : 2, classifier.classifyCalls.get());
        assertEquals(List.of(), lines(Level.ERROR));
    }

    private static Stream<Arguments> failuresRethrownUnchanged() {
        return Stream.of(Stage.values()).flatMap(stage -> {
            List<Arguments> cases = new ArrayList<>(List.of(
                    Arguments.of(stage, Category.OTHER, new Error("fatal at " + stage)),
                    Arguments.of(stage, Category.OTHER, nestedAuthentication()),
                    Arguments.of(stage, Category.OTHER, nestedRateLimit()),
                    Arguments.of(stage, Category.OTHER, nestedUnavailable()),
                    Arguments.of(stage, Category.RATE_LIMIT, nestedRateLimit()),
                    Arguments.of(stage, Category.UNAVAILABLE, nestedUnavailable()),
                    Arguments.of(stage, Category.AUTHENTICATION, nestedRateLimit()),
                    Arguments.of(stage, Category.AUTHENTICATION, nestedUnavailable())));
            if (stage != Stage.FIRST_ATTEMPT) {
                // At the first attempt an authentication exception is re-authenticated and retried instead.
                cases.add(Arguments.of(stage, Category.AUTHENTICATION, nestedAuthentication()));
            }
            return cases.stream();
        });
    }

    /**
     * An upstream exception a nested executor raised, classified into another category, is translated like a
     * vendor failure: the category's upstream exception with the nested one as its cause, after one ERROR line.
     */
    @ParameterizedTest(name = "{2} classified {1} at {0}")
    @MethodSource("relabelledUpstreamExceptions")
    void upstreamExceptionClassifiedIntoAnotherCategoryIsTranslated(Stage stage, Category category,
            RuntimeException nested) {
        classifier.returns(kindOf(category), nested);

        Attempt attempt = failAt(stage, nested);

        assertTranslated(category, SYSTEM, nested, attempt.thrown());
        assertEquals(stage.journal, journal);
        assertEquals(1, lines(Level.ERROR).size());
    }

    private static Stream<Arguments> relabelledUpstreamExceptions() {
        return Stream.of(Stage.values()).flatMap(stage -> Stream.of(
                Arguments.of(stage, Category.RATE_LIMIT, nestedAuthentication()),
                Arguments.of(stage, Category.UNAVAILABLE, nestedAuthentication()),
                Arguments.of(stage, Category.UNAVAILABLE, nestedRateLimit()),
                Arguments.of(stage, Category.RATE_LIMIT, nestedUnavailable())));
    }

    /**
     * An {@link Error} or a nested executor's authentication exception classified {@code AUTHENTICATION} at the
     * first attempt earns the single re-authentication and retry, as a vendor rejection does.
     */
    @ParameterizedTest
    @MethodSource("authenticationRejectionsOfOtherTypes")
    void authenticationRejectionOfAnyThrowableTypeIsReauthenticatedAndRetriedOnce(Throwable expired)
            throws Exception {
        classifier.returns(UpstreamFailureKind.AUTHENTICATION, expired);

        String result = executor.call(SYSTEM, new ScriptedCall(expired, "records"), reauthenticate());

        assertEquals("records", result);
        assertEquals(Stage.RETRY.journal, journal);
        assertEquals(List.of(expired), classifier.classified);
        assertEquals(1, classifier.classifyCalls.get());
        assertEquals(List.of(), lines(Level.ERROR));
    }

    private static Stream<Throwable> authenticationRejectionsOfOtherTypes() {
        return Stream.of(new Error("session expired"), nestedAuthentication());
    }

    @Test
    void nestedAuthenticationExceptionWithoutReauthenticationStepIsRethrownAsTheSameInstance() {
        UpstreamAuthenticationException nested = classifier.returns(UpstreamFailureKind.AUTHENTICATION,
                nestedAuthentication());

        Exception thrown = assertThrows(Exception.class, () -> executor.call(SYSTEM, new ScriptedCall(nested), null));

        assertSame(nested, thrown);
        assertEquals(Stage.FIRST_ATTEMPT.journal, journal);
        assertEquals(List.of(nested), classifier.classified);
        assertEquals(List.of(), appender.list);
    }

    /**
     * The classifier sees an {@link Error} too: an {@link AssertionError} caused by a read timeout, classified by a
     * classifier that delegates to {@link UpstreamFailureClassifier#isUnavailable} as the vendor classifiers do,
     * becomes the unavailable exception at every stage, with its ERROR line and no further attempt.
     */
    @ParameterizedTest
    @EnumSource(Stage.class)
    void errorWithAnUnavailableCauseIsTranslatedAndNeverRetried(Stage stage) {
        classifier = ScriptedClassifier.delegatingToIsUnavailable(SYSTEM);
        executor = new UpstreamCallExecutor(List.of(classifier));
        AssertionError fatal = new AssertionError("failed at " + stage, new SocketTimeoutException("Read timed out"));

        Attempt attempt = failAt(stage, fatal);

        assertTranslated(Category.UNAVAILABLE, SYSTEM, fatal, attempt.thrown());
        assertEquals(stage.journal, journal);
        assertEquals(stage == Stage.FIRST_ATTEMPT ? List.of(fatal) : List.of(attempt.firstFailure(), fatal),
                classifier.classified);
        assertEquals(List.of("Upstream " + SYSTEM.displayName() + " call failed [UNAVAILABLE]: "
                + AssertionError.class.getName() + throwSite(fatal) + " <- " + SocketTimeoutException.class.getName()),
                lines(Level.ERROR));
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void interruptedExceptionRestoresTheInterruptFlagBeforeClassification(Stage stage) {
        InterruptedException interrupted = classifier.returns(UpstreamFailureKind.OTHER,
                new InterruptedException("interrupted while waiting"));
        try {
            Attempt attempt = failAt(stage, interrupted);

            assertSame(interrupted, attempt.thrown());
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag restored");
            List<Boolean> expected = stage == Stage.FIRST_ATTEMPT ? List.of(true) : List.of(false, true);
            assertEquals(expected, classifier.interruptedAtClassification);
            assertEquals(stage.journal, journal);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void callToASystemWithoutClassifierFailsBeforeAnyAttempt() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> executor.call(UNREGISTERED_SYSTEM, new ScriptedCall("never"), reauthenticate()));

        assertEquals("No UpstreamFailureClassifier registered for " + UNREGISTERED_SYSTEM, thrown.getMessage());
        assertEquals(List.of(), journal);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void executorWithoutClassifiersRejectsEveryCall(List<UpstreamFailureClassifier> none) {
        UpstreamCallExecutor empty = new UpstreamCallExecutor(none);

        for (UpstreamSystem system : UpstreamSystem.values()) {
            assertThrows(IllegalStateException.class,
                    () -> empty.call(system, new ScriptedCall("never"), reauthenticate()), system::name);
        }
        assertEquals(List.of(), journal);
    }

    @Test
    void duplicateClassifierForOneSystemIsRejected() {
        List<UpstreamFailureClassifier> duplicates =
                List.of(new ScriptedClassifier(SYSTEM), new ScriptedClassifier(SYSTEM));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new UpstreamCallExecutor(duplicates));

        String name = ScriptedClassifier.class.getName();
        assertEquals("Duplicate UpstreamFailureClassifier for " + SYSTEM + ": " + name + " and " + name,
                thrown.getMessage());
    }

    @Test
    void nullClassifierIsRejected() {
        List<UpstreamFailureClassifier> withNull = Arrays.asList(classifier, null);

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> new UpstreamCallExecutor(withNull));

        assertEquals("classifiers must not contain null", thrown.getMessage());
    }

    @Test
    void classifierWithoutSystemIsRejected() {
        List<UpstreamFailureClassifier> systemless = List.of(new ScriptedClassifier(null));

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> new UpstreamCallExecutor(systemless));

        assertEquals(ScriptedClassifier.class.getName() + ".system() returned null", thrown.getMessage());
    }

    /**
     * One executor holds a Salesforce classifier scripted to answer {@code AUTHENTICATION} and a NetSuite classifier
     * scripted to answer {@code UNAVAILABLE} for the same vendor exception type; the classifier whose
     * {@code system()} matches the call decides, and only that one is consulted, in both directions.
     */
    @Test
    void eachSystemIsClassifiedByItsOwnClassifier() {
        ScriptedClassifier salesforce = new ScriptedClassifier(UpstreamSystem.SALESFORCE)
                .returnsFor(VendorException.class, UpstreamFailureKind.AUTHENTICATION);
        ScriptedClassifier netsuite = new ScriptedClassifier(UpstreamSystem.NETSUITE)
                .returnsFor(VendorException.class, UpstreamFailureKind.UNAVAILABLE);
        UpstreamCallExecutor both = new UpstreamCallExecutor(List.of(salesforce, netsuite));

        VendorException timedOut = new VendorException("timed out");
        Exception unavailable = assertThrows(Exception.class,
                () -> both.call(UpstreamSystem.NETSUITE, new ScriptedCall(timedOut), reauthenticate()));

        assertTranslated(Category.UNAVAILABLE, UpstreamSystem.NETSUITE, timedOut, unavailable);
        assertEquals(1, netsuite.classifyCalls.get());
        assertEquals(0, salesforce.classifyCalls.get());

        VendorException expired = new VendorException("expired");
        Exception rejected = assertThrows(Exception.class,
                () -> both.call(UpstreamSystem.SALESFORCE, new ScriptedCall(expired), null));

        assertTranslated(Category.AUTHENTICATION, UpstreamSystem.SALESFORCE, expired, rejected);
        assertEquals(1, salesforce.classifyCalls.get());
        assertEquals(1, netsuite.classifyCalls.get());
        assertEquals(List.of("call", "call"), journal);
    }

    @Test
    void nullArgumentsAreRejectedBeforeAnyAttempt() {
        assertEquals("system", assertThrows(NullPointerException.class,
                () -> executor.call(null, new ScriptedCall("never"), null)).getMessage());
        assertEquals("call", assertThrows(NullPointerException.class,
                () -> executor.call(SYSTEM, null, null)).getMessage());
        assertEquals("action", assertThrows(NullPointerException.class,
                () -> executor.run(SYSTEM, null, null)).getMessage());
        assertEquals(List.of(), journal);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // UpstreamFailureClassifier.isUnavailable
    // ---------------------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("unavailableCauses")
    void isUnavailableRecognisesEachTimeoutAndConnectivityFailureAnywhereInTheChain(Throwable cause) {
        assertTrue(UpstreamFailureClassifier.isUnavailable(cause));
        assertTrue(UpstreamFailureClassifier.isUnavailable(new RuntimeException("wrap", cause)));
        assertTrue(UpstreamFailureClassifier.isUnavailable(new IOException("wrap", cause)));
        assertTrue(UpstreamFailureClassifier.isUnavailable(new VendorException("outer", cause)));
        assertTrue(UpstreamFailureClassifier.isUnavailable(
                new IllegalStateException("outermost", new VendorException("middle", cause))));
    }

    /**
     * Every timeout, connectivity and closed-connection type: the JDK's, a JDK subtype of
     * {@link ClosedChannelException}, and the stand-ins of the Netty and Reactor Netty types recognised by name, with
     * a subtype of each whose real class is not final.
     */
    private static Stream<Throwable> unavailableCauses() {
        return Stream.of(
                new SocketTimeoutException("Read timed out"),
                new HttpTimeoutException("request timed out"),
                new HttpConnectTimeoutException("HTTP connect timed out"),
                instantiate(NettyStandIns.READ_TIMEOUT, "read timed out"),
                instantiate(NettyStandIns.READ_TIMEOUT_SUBTYPE, "idle read timed out"),
                new ConnectException("Connection refused"),
                new UnknownHostException("vendor.invalid"),
                new NoRouteToHostException("No route to host"),
                new SocketException("Connection reset"),
                new ClosedChannelException(),
                new AsynchronousCloseException(),
                instantiate(NettyStandIns.PREMATURE_CLOSE, PREMATURE_CLOSE_MESSAGE),
                instantiate(NettyStandIns.ABORTED, ABORTED_MESSAGE),
                instantiate(NettyStandIns.ABORTED_SUBTYPE, ABORTED_MESSAGE),
                instantiate(NettyStandIns.PREMATURE_CHANNEL_CLOSURE, PREMATURE_CHANNEL_CLOSURE_MESSAGE),
                instantiate(NettyStandIns.PREMATURE_CHANNEL_CLOSURE_SUBTYPE, PREMATURE_CHANNEL_CLOSURE_MESSAGE),
                instantiate(NettyStandIns.NATIVE_IO, NATIVE_IO_MESSAGE));
    }

    /**
     * Pins {@link UpstreamFailureClassifier#NETTY_READ_TIMEOUT_EXCEPTION} to the class Reactor Netty really throws
     * in the projects that call through {@code WebClient}; a project without Netty checks the stand-in of that name
     * instead, so the case runs, and passes, in every copy.
     */
    @Test
    void isUnavailableRecognisesNettysReadTimeoutWhereverNettyIsPresent() {
        RuntimeException readTimeout = nettyReadTimeout();

        assertEquals(UpstreamFailureClassifier.NETTY_READ_TIMEOUT_EXCEPTION, readTimeout.getClass().getName());
        assertTrue(UpstreamFailureClassifier.isUnavailable(readTimeout));
        assertTrue(UpstreamFailureClassifier.isUnavailable(new RuntimeException("wrap", readTimeout)));
        assertTrue(UpstreamFailureClassifier.isUnavailable(new IOException("wrap", readTimeout)));
    }

    /**
     * Netty's shared {@code ReadTimeoutException.INSTANCE} when Netty is loadable, otherwise the stand-in; either one
     * carries no message, as {@code INSTANCE} does not.
     */
    private static RuntimeException nettyReadTimeout() {
        Class<?> netty;
        try {
            netty = Class.forName(UpstreamFailureClassifier.NETTY_READ_TIMEOUT_EXCEPTION, true,
                    UpstreamCallExecutorTest.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            return instantiate(NettyStandIns.READ_TIMEOUT, null);
        }
        try {
            return (RuntimeException) netty.getField("INSTANCE").get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + netty.getName() + ".INSTANCE", e);
        }
    }

    /**
     * Pins {@link UpstreamFailureClassifier#REACTOR_PREMATURE_CLOSE_EXCEPTION},
     * {@link UpstreamFailureClassifier#REACTOR_ABORTED_EXCEPTION},
     * {@link UpstreamFailureClassifier#NETTY_PREMATURE_CHANNEL_CLOSURE_EXCEPTION} and
     * {@link UpstreamFailureClassifier#NETTY_NATIVE_IO_EXCEPTION} to the classes Reactor Netty and Netty really throw
     * in the projects that call through {@code WebClient}; a project without them checks the stand-in of each name
     * instead, so the case runs, and passes, in every copy.
     *
     * @param name the fully qualified name the classifier matches
     */
    @ParameterizedTest
    @ValueSource(strings = {
            UpstreamFailureClassifier.REACTOR_PREMATURE_CLOSE_EXCEPTION,
            UpstreamFailureClassifier.REACTOR_ABORTED_EXCEPTION,
            UpstreamFailureClassifier.NETTY_PREMATURE_CHANNEL_CLOSURE_EXCEPTION,
            UpstreamFailureClassifier.NETTY_NATIVE_IO_EXCEPTION})
    void isUnavailableRecognisesEachConnectionClosureWhereverNettyIsPresent(String name) {
        Throwable closure = connectionClosure(name);

        assertEquals(name, closure.getClass().getName());
        assertTrue(UpstreamFailureClassifier.isUnavailable(closure));
        assertTrue(UpstreamFailureClassifier.isUnavailable(new RuntimeException("wrap", closure)));
        assertTrue(UpstreamFailureClassifier.isUnavailable(new IOException("wrap", closure)));
    }

    /**
     * The real throwable named {@code name} when its class is loadable, otherwise its stand-in. The real ones are
     * Reactor Netty's shared {@code PrematureCloseException.TEST_EXCEPTION}, which is only ever wrapped and never
     * given a cause, and new instances of {@code AbortedException}, {@code PrematureChannelClosureException} and a
     * connection-reset {@code NativeIoException}, with the messages the transport writes. {@code NativeIoException}
     * is real only once a native transport has loaded its library, because its constructor reads the error text from
     * that library and a failed attempt would leave Netty's {@code Errors} class unusable for every later test in the
     * JVM; without the library the transport cannot raise it either.
     */
    private static Throwable connectionClosure(String name) {
        Class<?> real;
        try {
            real = Class.forName(name, false, UpstreamCallExecutorTest.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            real = null;
        }
        try {
            return switch (name) {
                case UpstreamFailureClassifier.REACTOR_PREMATURE_CLOSE_EXCEPTION -> real != null
                        ? (Throwable) real.getField("TEST_EXCEPTION").get(null)
                        : instantiate(NettyStandIns.PREMATURE_CLOSE, PREMATURE_CLOSE_MESSAGE);
                case UpstreamFailureClassifier.REACTOR_ABORTED_EXCEPTION -> real != null
                        ? (Throwable) real.getConstructor(String.class).newInstance(ABORTED_MESSAGE)
                        : instantiate(NettyStandIns.ABORTED, ABORTED_MESSAGE);
                case UpstreamFailureClassifier.NETTY_PREMATURE_CHANNEL_CLOSURE_EXCEPTION -> real != null
                        ? (Throwable) real.getConstructor(String.class).newInstance(PREMATURE_CHANNEL_CLOSURE_MESSAGE)
                        : instantiate(NettyStandIns.PREMATURE_CHANNEL_CLOSURE, PREMATURE_CHANNEL_CLOSURE_MESSAGE);
                case UpstreamFailureClassifier.NETTY_NATIVE_IO_EXCEPTION -> real != null && isNativeTransportLoaded()
                        ? (Throwable) real.getConstructor(String.class, int.class).newInstance("recvAddress",
                                real.getDeclaringClass().getField("ERRNO_ECONNRESET_NEGATIVE").getInt(null))
                        : instantiate(NettyStandIns.NATIVE_IO, NATIVE_IO_MESSAGE);
                default -> throw new IllegalArgumentException("not a connection closure type: " + name);
            };
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot create " + name, e);
        }
    }

    /**
     * Tells whether one of Netty's native transports ({@link #NATIVE_TRANSPORTS}) is on the classpath and has
     * loaded its library; asking loads it where the platform supports it, as Reactor Netty itself does.
     */
    private static boolean isNativeTransportLoaded() throws ReflectiveOperationException {
        for (String transport : NATIVE_TRANSPORTS) {
            Class<?> type;
            try {
                type = Class.forName(transport, true, UpstreamCallExecutorTest.class.getClassLoader());
            } catch (ClassNotFoundException absent) {
                continue;
            }
            if (Boolean.TRUE.equals(type.getMethod("isAvailable").invoke(null))) {
                return true;
            }
        }
        return false;
    }

    @ParameterizedTest
    @MethodSource("availableFailures")
    void isUnavailableRejectsEveryOtherFailure(Throwable failure) {
        assertFalse(UpstreamFailureClassifier.isUnavailable(failure));
        assertFalse(UpstreamFailureClassifier.isUnavailable(new RuntimeException("wrap", failure)));
        assertFalse(UpstreamFailureClassifier.isUnavailable(new IOException("wrap", failure)));
        assertFalse(UpstreamFailureClassifier.isUnavailable(new VendorException("outer", failure)));
    }

    /** Failures whose type, message or simple name resembles an unavailable one without being one. */
    private static Stream<Throwable> availableFailures() {
        return Stream.of(
                new IOException("Read timed out"),
                new InterruptedIOException("interrupted"),
                new TimeoutException("future timed out"),
                new IllegalStateException("Connection reset"),
                new VendorException("connection refused"),
                instantiate(NettyStandIns.SAME_SIMPLE_NAME, "read timed out"),
                new IOException(PREMATURE_CLOSE_MESSAGE),
                new IllegalStateException(ABORTED_MESSAGE),
                new IOException(NATIVE_IO_MESSAGE),
                instantiate(NettyStandIns.PREMATURE_CLOSE_SAME_SIMPLE_NAME, PREMATURE_CLOSE_MESSAGE),
                instantiate(NettyStandIns.ABORTED_SAME_SIMPLE_NAME, ABORTED_MESSAGE),
                instantiate(NettyStandIns.PREMATURE_CHANNEL_CLOSURE_SAME_SIMPLE_NAME,
                        PREMATURE_CHANNEL_CLOSURE_MESSAGE),
                instantiate(NettyStandIns.NATIVE_IO_SAME_SIMPLE_NAME, NATIVE_IO_MESSAGE));
    }

    @Test
    void isUnavailableIsFalseForNull() {
        assertFalse(UpstreamFailureClassifier.isUnavailable(null));
    }

    @Test
    void isUnavailableIsFalseForACauseCycleWithoutAnUnavailableType() {
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);

        assertTimeoutPreemptively(CYCLE_TIMEOUT, () -> assertFalse(UpstreamFailureClassifier.isUnavailable(a)));
    }

    @Test
    void isUnavailableTerminatesOnACyclicChain() {
        IOException available = new IOException("loops back");
        VendorException availableLoop = new VendorException("outer", available);
        available.initCause(availableLoop);
        SocketException reset = new SocketException("Connection reset");
        VendorException unavailableLoop = new VendorException("outer", reset);
        reset.initCause(unavailableLoop);

        assertTimeoutPreemptively(CYCLE_TIMEOUT, () -> {
            assertFalse(UpstreamFailureClassifier.isUnavailable(availableLoop));
            assertTrue(UpstreamFailureClassifier.isUnavailable(unavailableLoop));
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // UpstreamFailureClassifier.upstreamMessage (default)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Without a message anywhere in the chain, the description names the upstream and never a class. Guarded by
     * {@link #CYCLE_TIMEOUT}, because three of the chains are cyclic.
     */
    @ParameterizedTest
    @MethodSource("upstreamMessages")
    void upstreamMessageIsTheFirstNonBlankMessageOrANeutralDescription(UpstreamSystem system, Throwable failure,
            String expected) {
        ScriptedClassifier systemClassifier = new ScriptedClassifier(system);

        assertEquals(expected,
                assertTimeoutPreemptively(CYCLE_TIMEOUT, () -> systemClassifier.upstreamMessage(failure)));
    }

    private static Stream<Arguments> upstreamMessages() {
        IOException messageless = new IOException((String) null);
        VendorException cycle = new VendorException(null, messageless);
        messageless.initCause(cycle);
        IOException tail = new IOException("from the loop");
        VendorException blankCycle = new VendorException(" ", tail);
        tail.initCause(blankCycle);
        ClosedChannelException closed = new ClosedChannelException();
        VendorException closedCycle = new VendorException(null, closed);
        closed.initCause(closedCycle);
        Exception anonymous = new Exception() {
            private static final long serialVersionUID = 1L;
        };
        return Stream.of(
                Arguments.of(SYSTEM, new VendorException("outer text", new IOException("inner text")), "outer text"),
                Arguments.of(SYSTEM, new IllegalStateException(null,
                        new VendorException(" \t ", new IOException("  vendor fault text  "))),
                        "  vendor fault text  "),
                Arguments.of(SYSTEM, new VendorException(null, new IOException((String) null)),
                        "NetSuite call failed"),
                Arguments.of(SYSTEM, anonymous, "NetSuite call failed"),
                Arguments.of(SYSTEM, cycle, "NetSuite call failed"),
                Arguments.of(SYSTEM, blankCycle, "from the loop"),
                Arguments.of(SYSTEM, new VendorException(null, new SocketTimeoutException()),
                        "NetSuite did not respond in time"),
                Arguments.of(SYSTEM, new VendorException(null, nettyReadTimeout()), "NetSuite did not respond in time"),
                Arguments.of(SYSTEM, new VendorException(null, instantiate(NettyStandIns.READ_TIMEOUT_SUBTYPE, null)),
                        "NetSuite did not respond in time"),
                Arguments.of(SYSTEM, new HttpConnectTimeoutException(null), "NetSuite did not respond in time"),
                Arguments.of(SYSTEM, new VendorException(null, new ConnectException()), "NetSuite is unreachable"),
                Arguments.of(SYSTEM, closedCycle, "NetSuite is unreachable"),
                Arguments.of(null, new VendorException(null), "The upstream system call failed"));
    }

    @Test
    void upstreamMessageRejectsNull() {
        assertEquals("failure", assertThrows(NullPointerException.class,
                () -> classifier.upstreamMessage(null)).getMessage());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ERROR line: upstream, category and types only; the vendor's text stays with the caller
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * A vendor failure whose message repeats a sentinel and forges a log line, with a sentinel-bearing cause and
     * suppressed exception, yields one ERROR event that names the upstream, the category, the failure's class and
     * throw site and its cause's class, and nothing else of the failure; the raised exception keeps the upstream's
     * message for the caller. For an authentication rejection the retry is rejected too.
     */
    @ParameterizedTest(name = "{0} from {1}")
    @MethodSource("loggedFailures")
    void errorLineNamesUpstreamCategoryAndTypesButNoVendorText(Category category, UpstreamSystem system) {
        // UNAVAILABLE is recognised from the SocketTimeoutException cause by delegating to isUnavailable; the other
        // categories are scripted explicitly below.
        ScriptedClassifier systemClassifier = category == Category.UNAVAILABLE
                ? ScriptedClassifier.delegatingToIsUnavailable(system)
                : new ScriptedClassifier(system);
        UpstreamCallExecutor systemExecutor = new UpstreamCallExecutor(List.of(systemClassifier));
        Throwable cause = category == Category.UNAVAILABLE
                ? new SocketTimeoutException("Read timed out " + SENTINEL + "\r\nforged")
                : new IOException("cause " + SENTINEL + "\nforged");
        VendorException failure = sentinelFailure(cause);
        ScriptedCall call;
        if (category == Category.AUTHENTICATION) {
            VendorException expired = systemClassifier.returns(UpstreamFailureKind.AUTHENTICATION,
                    sentinelFailure(new IOException("first " + SENTINEL)));
            systemClassifier.returns(UpstreamFailureKind.AUTHENTICATION, failure);
            call = new ScriptedCall(expired, failure);
        } else {
            if (category == Category.RATE_LIMIT) {
                systemClassifier.returns(UpstreamFailureKind.rateLimit(RETRY_AFTER), failure);
            }
            call = new ScriptedCall(failure);
        }

        Exception thrown = assertThrows(Exception.class, () -> systemExecutor.call(system, call, reauthenticate()));

        assertTranslated(category, system, failure, thrown);
        assertTrue(thrown.getMessage().contains(SENTINEL), "the caller still receives the vendor's text");
        assertEquals(List.of("Upstream " + system.displayName() + " call failed [" + category + "]: "
                + failure.getClass().getName() + throwSite(failure) + " <- " + cause.getClass().getName()),
                lines(Level.ERROR));
        assertFalse(lines(Level.ERROR).get(0).contains(IllegalStateException.class.getName()),
                "suppressed exceptions are not named");
        assertNothingDisclosed();
    }

    private static Stream<Arguments> loggedFailures() {
        return Stream.of(Category.AUTHENTICATION, Category.RATE_LIMIT, Category.UNAVAILABLE)
                .flatMap(category -> Stream.of(UpstreamSystem.values()).map(system -> Arguments.of(category, system)));
    }

    /** The throw-site frame is written in {@link StackTraceElement} form, escaped and bounded. */
    @ParameterizedTest
    @MethodSource("throwSites")
    void errorLineEscapesAndBoundsTheThrowSite(StackTraceElement[] trace, String expectedSite) {
        VendorException failure = classifier.returns(UpstreamFailureKind.rateLimit(null),
                new VendorException(FORGING_MESSAGE));
        failure.setStackTrace(trace);

        assertThrows(UpstreamRateLimitException.class,
                () -> executor.call(SYSTEM, new ScriptedCall(failure), reauthenticate()));

        assertEquals(List.of("Upstream " + SYSTEM.displayName() + " call failed [RATE_LIMIT]: "
                + VendorException.class.getName() + expectedSite), lines(Level.ERROR));
        assertNothingDisclosed();
    }

    private static Stream<Arguments> throwSites() {
        String longName = "a." + "x".repeat(NAME_LOG_LIMIT + 50);
        String escapeAtLimit = "x".repeat(NAME_LOG_LIMIT - 5) + "\n";
        String pairAtLimit = "x".repeat(NAME_LOG_LIMIT - 1) + "\ud83d\ude00";
        return Stream.of(
                Arguments.of(frame("a.b.Client", "query", "Client.java", 42), " at a.b.Client.query(Client.java:42)"),
                Arguments.of(frame("a.b.Client", "query", "Client.java", -1), " at a.b.Client.query(Client.java)"),
                Arguments.of(frame("a.b.Client", "query", null, 42), " at a.b.Client.query(Unknown Source)"),
                Arguments.of(frame("a.b.Net", "connect0", "Net.java", -2), " at a.b.Net.connect0(Native Method)"),
                Arguments.of(new StackTraceElement[0], ""),
                Arguments.of(frame("a.b.C\r\n2026-10-26 ERROR forged", "m\u2028\u202e", "F\\x.java", 7),
                        " at a.b.C\\u000d\\u000a2026-10-26 ERROR forged.m\\u2028\\u202e(F\\u005cx.java:7)"),
                Arguments.of(frame("a.b.C", "\ud800m\ud83d\ude00\u0085", "C.java", 1),
                        " at a.b.C.\\ud800m\ud83d\ude00\\u0085(C.java:1)"),
                Arguments.of(frame(longName, "m", "C.java", 1),
                        " at " + longName.substring(0, NAME_LOG_LIMIT) + "...[truncated].m(C.java:1)"),
                Arguments.of(frame("a.b.C", escapeAtLimit, "C.java", 1),
                        " at a.b.C." + "x".repeat(NAME_LOG_LIMIT - 5) + "...[truncated](C.java:1)"),
                Arguments.of(frame("a.b.C", pairAtLimit, "C.java", 1),
                        " at a.b.C." + "x".repeat(NAME_LOG_LIMIT - 1) + "...[truncated](C.java:1)"));
    }

    /**
     * Causes are named by class in order; the walk stops at a repeated cause and after the cause limit. Guarded by
     * {@link #CYCLE_TIMEOUT}, because one of the chains is cyclic.
     */
    @ParameterizedTest
    @MethodSource("causeChains")
    void errorLineNamesCausesByClassAndTerminates(Throwable failure, String expectedCauses) {
        classifier.returns(UpstreamFailureKind.rateLimit(null), failure);
        failure.setStackTrace(new StackTraceElement[0]);

        assertTimeoutPreemptively(CYCLE_TIMEOUT, () -> assertThrows(UpstreamRateLimitException.class,
                () -> executor.call(SYSTEM, new ScriptedCall(failure), reauthenticate())));

        assertEquals(List.of("Upstream " + SYSTEM.displayName() + " call failed [RATE_LIMIT]: "
                + failure.getClass().getName() + expectedCauses), lines(Level.ERROR));
        assertNothingDisclosed();
    }

    private static Stream<Arguments> causeChains() {
        IOException loopingCause = new IOException("cause " + SENTINEL);
        VendorException cyclic = new VendorException(FORGING_MESSAGE, loopingCause);
        loopingCause.initCause(cyclic);
        String io = " <- " + IOException.class.getName();
        return Stream.of(
                Arguments.of(new VendorException(FORGING_MESSAGE), ""),
                Arguments.of(sentinelFailure(new IOException("cause " + SENTINEL)), io),
                Arguments.of(cyclic, io + " <- [circular]"),
                Arguments.of(chainOf(CAUSE_LOG_LIMIT), io.repeat(CAUSE_LOG_LIMIT)),
                Arguments.of(chainOf(CAUSE_LOG_LIMIT + 2), io.repeat(CAUSE_LOG_LIMIT) + " <- ..."));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fakes and helpers
    // ---------------------------------------------------------------------------------------------------------------

    /** Where a scripted failure is injected, with the journal the executor must leave behind. */
    private enum Stage {

        FIRST_ATTEMPT(List.of("call")),
        REAUTHENTICATION(List.of("call", "reauthenticate")),
        RETRY(List.of("call", "reauthenticate", "call"));

        final List<String> journal;

        Stage(List<String> journal) {
            this.journal = journal;
        }
    }

    /** What {@link #failAt} observed: the throwable that escaped and the first attempt's rejection, if any. */
    private record Attempt(Throwable thrown, Exception firstFailure) {
    }

    /**
     * Calls the executor with {@code injected} thrown at {@code stage}; for a stage after the first attempt, the
     * first attempt is rejected for authentication and re-authentication succeeds unless it is the stage.
     */
    private Attempt failAt(Stage stage, Throwable injected) {
        VendorException expired = classifier.returns(UpstreamFailureKind.AUTHENTICATION,
                new VendorException("expired"));
        ScriptedCall call = switch (stage) {
            case FIRST_ATTEMPT -> new ScriptedCall(injected);
            case REAUTHENTICATION -> new ScriptedCall(expired);
            case RETRY -> new ScriptedCall(expired, injected);
        };
        UpstreamAction reauthentication = stage == Stage.REAUTHENTICATION
                ? reauthenticateFailingWith(injected)
                : reauthenticate();
        Throwable thrown = assertThrows(Throwable.class, () -> executor.call(SYSTEM, call, reauthentication));
        return new Attempt(thrown, stage == Stage.FIRST_ATTEMPT ? null : expired);
    }

    private static UpstreamFailureKind kindOf(Category category) {
        return switch (category) {
            case AUTHENTICATION -> UpstreamFailureKind.AUTHENTICATION;
            case RATE_LIMIT -> UpstreamFailureKind.rateLimit(RETRY_AFTER);
            case UNAVAILABLE -> UpstreamFailureKind.UNAVAILABLE;
            case OTHER -> UpstreamFailureKind.OTHER;
        };
    }

    /** An authentication exception as a nested executor raises it. */
    private static UpstreamAuthenticationException nestedAuthentication() {
        return new UpstreamAuthenticationException(SYSTEM, "nested rejection", null);
    }

    /** A rate-limit exception as a nested executor raises it, with a {@code Retry-After} of its own. */
    private static UpstreamRateLimitException nestedRateLimit() {
        return new UpstreamRateLimitException(SYSTEM, "nested throttle", "5", null);
    }

    /** An unavailable exception as a nested executor raises it. */
    private static UpstreamUnavailableException nestedUnavailable() {
        return new UpstreamUnavailableException(SYSTEM, "nested timeout", null);
    }

    /**
     * Asserts that {@code thrown} is what the executor raises for {@code failure} classified as {@code category}:
     * the category's upstream exception for {@code system}, with {@code failure} as its cause and the upstream's
     * message, or {@code failure} itself for {@code OTHER}.
     */
    private static void assertTranslated(Category category, UpstreamSystem system, Throwable failure,
            Throwable thrown) {
        String upstreamMessage = new ScriptedClassifier(system).upstreamMessage(failure);
        switch (category) {
            case AUTHENTICATION -> {
                UpstreamAuthenticationException e = assertInstanceOf(UpstreamAuthenticationException.class, thrown);
                assertEquals(system, e.getSystem());
                assertSame(failure, e.getCause());
                assertEquals(upstreamMessage, e.getMessage());
            }
            case RATE_LIMIT -> {
                UpstreamRateLimitException e = assertInstanceOf(UpstreamRateLimitException.class, thrown);
                assertEquals(system, e.getSystem());
                assertSame(failure, e.getCause());
                assertEquals(upstreamMessage, e.getMessage());
                assertEquals(RETRY_AFTER, e.getRetryAfter());
            }
            case UNAVAILABLE -> {
                UpstreamUnavailableException e = assertInstanceOf(UpstreamUnavailableException.class, thrown);
                assertEquals(system, e.getSystem());
                assertSame(failure, e.getCause());
                assertEquals(upstreamMessage, e.getMessage());
            }
            case OTHER -> assertSame(failure, thrown);
        }
    }

    /** Formatted messages the executor logged at {@code level}, in order. */
    private List<String> lines(Level level) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** No event of the executor carries the sentinel, a line break or a throwable. */
    private void assertNothingDisclosed() {
        assertFalse(appender.list.isEmpty(), "nothing was logged");
        for (ILoggingEvent event : appender.list) {
            String line = event.getFormattedMessage();
            assertFalse(line.contains(SENTINEL), () -> "vendor text logged: " + line);
            assertFalse(line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0, () -> "line break logged: " + line);
            assertNull(event.getThrowableProxy(), () -> "throwable logged with: " + line);
        }
    }

    /** A vendor failure carrying the sentinel in its message, its cause's message and a suppressed exception. */
    private static VendorException sentinelFailure(Throwable cause) {
        VendorException failure = new VendorException(FORGING_MESSAGE, cause);
        failure.addSuppressed(new IllegalStateException("suppressed " + SENTINEL + "\r\nforged"));
        return failure;
    }

    /** A vendor failure with {@code depth} nested {@link IOException} causes. */
    private static VendorException chainOf(int depth) {
        Throwable cause = null;
        for (int level = 0; level < depth; level++) {
            cause = new IOException("level " + level + " " + SENTINEL, cause);
        }
        return new VendorException(FORGING_MESSAGE, cause);
    }

    /** {@code failure}'s throw site as the ERROR line writes it for an ordinary frame. */
    private static String throwSite(Throwable failure) {
        StackTraceElement frame = failure.getStackTrace()[0];
        return " at " + frame.getClassName() + "." + frame.getMethodName()
                + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")";
    }

    private static StackTraceElement[] frame(String className, String methodName, String fileName, int line) {
        return new StackTraceElement[] {new StackTraceElement(className, methodName, fileName, line)};
    }

    private UpstreamAction reauthenticate() {
        return reauthenticateFailingWith(null);
    }

    /** A re-authentication step that journals each run and throws {@code failure} when it is not null. */
    private UpstreamAction reauthenticateFailingWith(Throwable failure) {
        return () -> {
            journal.add("reauthenticate");
            rethrow(failure);
        };
    }

    /** Throws {@code failure} as it is: an {@link Exception} or {@link Error}; returns for {@code null}. */
    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalArgumentException("not an Exception or Error: " + failure.getClass().getName());
        }
    }

    private static <T extends Throwable> T instantiate(Class<T> type, String message) {
        try {
            return type.getConstructor(String.class).newInstance(message);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot instantiate " + type.getName(), e);
        }
    }

    /** A vendor call that answers each attempt from a script of values and failures, journaling every attempt. */
    private final class ScriptedCall implements UpstreamCall<String> {

        private final List<Object> outcomes;

        private int attempts;

        ScriptedCall(Object... outcomes) {
            this.outcomes = Arrays.asList(outcomes);
        }

        @Override
        public String call() throws Exception {
            journal.add("call");
            if (attempts >= outcomes.size()) {
                throw new AssertionError("unexpected attempt " + (attempts + 1));
            }
            Object outcome = outcomes.get(attempts++);
            if (outcome instanceof Throwable failure) {
                rethrow(failure);
            }
            return (String) outcome;
        }
    }

    /**
     * A vendor classifier whose answers each case scripts. Rules are tried in registration order and the first
     * match decides: a throwable instance, matched by identity, or a class, matched by {@link Class#isInstance};
     * a rule may answer {@code null}, which breaks the classifier contract on purpose. A throwable no rule matches
     * gets the default kind, {@code OTHER}. Only the variant built by {@link #delegatingToIsUnavailable} also
     * answers {@code UNAVAILABLE} whenever {@link UpstreamFailureClassifier#isUnavailable} holds, ahead of every
     * scripted rule, as the vendor classifiers do. Counts every classification in {@link #classifyCalls} and
     * records each classified throwable and whether the thread was interrupted at that moment.
     * {@link #upstreamMessage} is the interface default.
     */
    static final class ScriptedClassifier implements UpstreamFailureClassifier {

        /** One scripted answer: the kind for every throwable the predicate accepts. */
        private record Rule(Predicate<Throwable> matches, UpstreamFailureKind kind) {
        }

        private final UpstreamSystem system;

        private final List<Rule> rules = new ArrayList<>();

        private final UpstreamFailureKind defaultKind = UpstreamFailureKind.OTHER;

        final AtomicInteger classifyCalls = new AtomicInteger();

        final List<Throwable> classified = new ArrayList<>();

        final List<Boolean> interruptedAtClassification = new ArrayList<>();

        ScriptedClassifier(UpstreamSystem system) {
            this.system = system;
        }

        /**
         * Creates a classifier that answers {@code UNAVAILABLE} for every throwable whose cause chain
         * {@link UpstreamFailureClassifier#isUnavailable} accepts, before any rule registered later.
         */
        static ScriptedClassifier delegatingToIsUnavailable(UpstreamSystem system) {
            ScriptedClassifier delegating = new ScriptedClassifier(system);
            delegating.rules.add(new Rule(UpstreamFailureClassifier::isUnavailable, UpstreamFailureKind.UNAVAILABLE));
            return delegating;
        }

        /** Registers {@code kind} as the classification of exactly {@code failure} and returns {@code failure}. */
        <T extends Throwable> T returns(UpstreamFailureKind kind, T failure) {
            rules.add(new Rule(candidate -> candidate == failure, kind));
            return failure;
        }

        /** Registers {@code kind} as the classification of every instance of {@code type} and returns this. */
        ScriptedClassifier returnsFor(Class<? extends Throwable> type, UpstreamFailureKind kind) {
            rules.add(new Rule(type::isInstance, kind));
            return this;
        }

        @Override
        public UpstreamSystem system() {
            return system;
        }

        @Override
        public UpstreamFailureKind classify(Throwable failure) {
            classifyCalls.incrementAndGet();
            classified.add(failure);
            interruptedAtClassification.add(Thread.currentThread().isInterrupted());
            for (Rule rule : rules) {
                if (rule.matches().test(failure)) {
                    return rule.kind();
                }
            }
            return defaultKind;
        }
    }

    /** A checked vendor exception, standing in for a connector's own fault type. */
    static final class VendorException extends Exception {

        private static final long serialVersionUID = 1L;

        VendorException(String message) {
            super(message);
        }

        VendorException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Netty and Reactor Netty stand-ins defined by Byte Buddy, each in its own child-first class loader so a Netty or
     * Reactor Netty jar on the classpath cannot shadow them: every type {@link UpstreamFailureClassifier#isUnavailable}
     * recognises by name, under its real name and with its real class's superclass kind ({@code RuntimeException}
     * for {@code ReadTimeoutException}, {@code AbortedException} and {@code PrematureChannelClosureException},
     * {@code IOException} for {@code PrematureCloseException} and {@code NativeIoException}); a subtype of each whose
     * real class is not final; and an unrelated class sharing each simple name.
     */
    private static final class NettyStandIns {

        static final Class<? extends RuntimeException> READ_TIMEOUT =
                define(UpstreamFailureClassifier.NETTY_READ_TIMEOUT_EXCEPTION, RuntimeException.class);

        static final Class<? extends RuntimeException> READ_TIMEOUT_SUBTYPE =
                define("example.transport.IdleReadTimeoutException", READ_TIMEOUT);

        static final Class<? extends RuntimeException> SAME_SIMPLE_NAME =
                define("example.transport.ReadTimeoutException", RuntimeException.class);

        static final Class<? extends IOException> PREMATURE_CLOSE =
                define(UpstreamFailureClassifier.REACTOR_PREMATURE_CLOSE_EXCEPTION, IOException.class);

        static final Class<? extends IOException> PREMATURE_CLOSE_SAME_SIMPLE_NAME =
                define("example.transport.PrematureCloseException", IOException.class);

        static final Class<? extends RuntimeException> ABORTED =
                define(UpstreamFailureClassifier.REACTOR_ABORTED_EXCEPTION, RuntimeException.class);

        static final Class<? extends RuntimeException> ABORTED_SUBTYPE =
                define("example.transport.SendAbortedException", ABORTED);

        static final Class<? extends RuntimeException> ABORTED_SAME_SIMPLE_NAME =
                define("example.transport.AbortedException", RuntimeException.class);

        static final Class<? extends RuntimeException> PREMATURE_CHANNEL_CLOSURE =
                define(UpstreamFailureClassifier.NETTY_PREMATURE_CHANNEL_CLOSURE_EXCEPTION, RuntimeException.class);

        static final Class<? extends RuntimeException> PREMATURE_CHANNEL_CLOSURE_SUBTYPE =
                define("example.transport.HeaderChannelClosureException", PREMATURE_CHANNEL_CLOSURE);

        static final Class<? extends RuntimeException> PREMATURE_CHANNEL_CLOSURE_SAME_SIMPLE_NAME =
                define("example.transport.PrematureChannelClosureException", RuntimeException.class);

        static final Class<? extends IOException> NATIVE_IO =
                define(UpstreamFailureClassifier.NETTY_NATIVE_IO_EXCEPTION, IOException.class);

        static final Class<? extends IOException> NATIVE_IO_SAME_SIMPLE_NAME =
                define("example.transport.Errors$NativeIoException", IOException.class);

        private static <T extends Throwable> Class<? extends T> define(String name, Class<T> superclass) {
            ClassLoader parent = superclass.getClassLoader() != null
                    ? superclass.getClassLoader()
                    : UpstreamCallExecutorTest.class.getClassLoader();
            return new ByteBuddy()
                    .subclass(superclass)
                    .name(name)
                    .make()
                    .load(parent, ClassLoadingStrategy.Default.CHILD_FIRST)
                    .getLoaded();
        }
    }
}
