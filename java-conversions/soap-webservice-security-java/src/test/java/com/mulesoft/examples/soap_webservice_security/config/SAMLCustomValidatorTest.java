/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.security.auth.callback.Callback;

import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.saml.SAMLCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests of the SAML token support of the Greeter security paths, here {@link SAMLCallbackHandler}, which builds
 * the assertion the {@code samlToken} and {@code samlTokenSigned} clients send.
 *
 * <p>The handler's logger is captured at DEBUG, so the tests see the one diagnostic it writes when the issuer crypto
 * cannot be loaded and can assert that it names exception types and the throw site only.
 */
class SAMLCustomValidatorTest {

    /** Stands for keystore or credential text in an exception message; built at run time, never committed. */
    private static final String MESSAGE_SENTINEL = "kp-" + "SENTINEL-" + UUID.randomUUID();

    /** Stands for keystore or credential text in a cause's message; built at run time, never committed. */
    private static final String CAUSE_SENTINEL = "kc-" + "SENTINEL-" + UUID.randomUUID();

    private static final String UNAVAILABLE_PREFIX =
            "SAML issuer crypto is unavailable; the assertion carries no issuer crypto: ";

    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureHandlerLog() {
        logger = (Logger) LoggerFactory.getLogger(SAMLCallbackHandler.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseHandlerLog() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    static Stream<Arguments> cryptoFailures() {
        return Stream.of(
                Arguments.of("keystore failure with a security cause",
                        (Supplier<RuntimeException>) SAMLCustomValidatorTest::keystoreFailure,
                        UNAVAILABLE_PREFIX + IllegalStateException.class.getName() + " at "
                                + SAMLCustomValidatorTest.class.getName() + ".keystoreFailure(",
                        " <- " + GeneralSecurityException.class.getName()),
                Arguments.of("cause chain leading back to the failure",
                        (Supplier<RuntimeException>) () -> {
                            RuntimeException failure = keystoreFailure();
                            failure.getCause().initCause(failure);
                            return failure;
                        },
                        UNAVAILABLE_PREFIX + IllegalStateException.class.getName() + " at ",
                        " <- " + GeneralSecurityException.class.getName() + " <- [circular]"),
                Arguments.of("failure without a stack trace",
                        (Supplier<RuntimeException>) () -> {
                            RuntimeException failure = keystoreFailure();
                            failure.setStackTrace(new StackTraceElement[0]);
                            return failure;
                        },
                        UNAVAILABLE_PREFIX + IllegalStateException.class.getName() + " <- ",
                        " <- " + GeneralSecurityException.class.getName()),
                Arguments.of("throw site with line breaks and a reverse solidus",
                        (Supplier<RuntimeException>) () -> {
                            RuntimeException failure = keystoreFailure();
                            failure.setStackTrace(new StackTraceElement[] {
                                new StackTraceElement("com.example.Keystore", "load\r\nforged\\entry",
                                        "Keystore.java", 42)
                            });
                            return failure;
                        },
                        UNAVAILABLE_PREFIX + IllegalStateException.class.getName()
                                + " at com.example.Keystore.load\\u000d\\u000aforged\\u005centry(Keystore.java:42) <- ",
                        " <- " + GeneralSecurityException.class.getName()),
                Arguments.of("cause chain deeper than ten causes",
                        (Supplier<RuntimeException>) () -> {
                            GeneralSecurityException cause = new GeneralSecurityException(CAUSE_SENTINEL);
                            for (int i = 0; i < 10; i++) {
                                cause = new GeneralSecurityException(CAUSE_SENTINEL, cause);
                            }
                            return new IllegalStateException(MESSAGE_SENTINEL, cause);
                        },
                        UNAVAILABLE_PREFIX + IllegalStateException.class.getName() + " at ",
                        (" <- " + GeneralSecurityException.class.getName()).repeat(10) + " <- ..."));
    }

    /**
     * A crypto that cannot be loaded leaves the issuer crypto unset and keeps the issuer, while the one DEBUG line
     * names the exception types and the throw site only: neither message, nor the issuer, nor the throwable.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cryptoFailures")
    void cryptoFailureLeavesCryptoUnsetAndLogsTypesWithoutMessagesOrIssuer(String description,
            Supplier<RuntimeException> failure, String expectedStart, String expectedEnd) {
        SAMLCallbackHandler handler = new SAMLCallbackHandler(new ClassPathResource("saml.properties"), "", () -> {
            throw failure.get();
        });
        SAMLCallback callback = new SAMLCallback();

        assertThatCode(() -> handler.handle(new Callback[] {callback})).doesNotThrowAnyException();

        assertThat(callback.getIssuerCrypto()).isNull();
        assertThat(callback.getIssuer()).isEqualTo("self");
        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getFormattedMessage())
                .startsWith(expectedStart)
                .endsWith(expectedEnd)
                .contains(IllegalStateException.class.getName(), GeneralSecurityException.class.getName())
                .doesNotContain(MESSAGE_SENTINEL, CAUSE_SENTINEL, "self");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void loadedCryptoIsSetOnTheCallbackWithoutLogging() throws Exception {
        Crypto crypto = Mockito.mock(Crypto.class);
        SAMLCallbackHandler handler =
                new SAMLCallbackHandler(new ClassPathResource("saml.properties"), "", () -> crypto);
        SAMLCallback callback = new SAMLCallback();

        handler.handle(new Callback[] {callback});

        assertThat(callback.getIssuerCrypto()).isSameAs(crypto);
        assertThat(callback.getIssuer()).isEqualTo("self");
        assertThat(appender.list).isEmpty();
    }

    /** A keystore failure whose message and cause carry credential text, as a CryptoFactory failure can. */
    private static RuntimeException keystoreFailure() {
        return new IllegalStateException("Cannot load keystore keystore.jks with password " + MESSAGE_SENTINEL,
                new GeneralSecurityException("Keystore integrity check failed for " + CAUSE_SENTINEL));
    }
}
