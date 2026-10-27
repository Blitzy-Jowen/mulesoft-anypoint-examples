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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.security.auth.callback.Callback;

import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.common.saml.OpenSAMLUtil;
import org.apache.wss4j.common.saml.SAMLCallback;
import org.apache.wss4j.common.saml.SamlAssertionWrapper;
import org.apache.wss4j.common.saml.bean.Version;
import org.apache.wss4j.common.saml.builder.SAML2Constants;
import org.apache.wss4j.dom.engine.WSSConfig;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.validate.Credential;
import org.apache.wss4j.dom.validate.SamlAssertionValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
 * Unit tests of the SAML token support of the Greeter security paths: {@link SAMLCustomValidator}, the validator of
 * {@code /services/saml} and {@code /services/signedsaml}, and {@link SAMLCallbackHandler}, which builds the assertion
 * the {@code samlToken} and {@code samlTokenSigned} clients send.
 *
 * <p>The validator is fed unsigned assertions the production handler built from the carried {@code saml.properties}
 * or {@code wrong-saml.properties}; a rejection case changes only the one callback field its check reads, and a plain
 * {@link SamlAssertionValidator} accepting the same credential shows that the custom check, not WSS4J's own
 * validation, rejects it. No Spring context and no keystore are involved.
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

    /** Message ID of every rejection, the original validator's {@code invalidSAMLsecurity}. */
    private static final String INVALID_SAML_SECURITY = "invalidSAMLsecurity";

    /**
     * A placeholder rather than joe's demo key password: an unsigned assertion never uses the issuer key, so the value
     * only has to be non-empty for the handler to set it.
     */
    private static final String UNUSED_ISSUER_KEY_PASSWORD = "unused-issuer-key-password";

    /**
     * The handler resolves the issuer crypto on every callback, signed or not, so a supplier that fails when called
     * cannot stand for "never needed"; returning {@code null} leaves the issuer crypto unset and loads no keystore.
     */
    private static final Supplier<Crypto> NO_ISSUER_CRYPTO = () -> null;

    private Logger logger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeAll
    static void initSaml() {
        OpenSAMLUtil.initSamlEngine();
    }

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

    /**
     * The assertion the {@code samlToken} client sends with {@code saml.properties} passes every check: issued by
     * {@code self}, SAML 2.0, sender-vouches, subject {@code AllowGreetingServices}.
     */
    @Test
    void validSelfIssuedSaml2SenderVouchesAssertionPasses() throws Exception {
        Credential credential = credentialOf(filledCallback("saml.properties"));
        SamlAssertionWrapper assertion = credential.getSamlAssertion();

        Credential validated = new SAMLCustomValidator().validate(credential, samlRequestData());

        assertThat(validated).isNotNull();
        assertThat(validated.getSamlAssertion()).isSameAs(assertion);
        assertThat(assertion.isSigned()).isFalse();
        assertThat(assertion.getIssuerString()).isEqualTo("self");
        assertThat(assertion.getSaml2()).isNotNull();
        assertThat(assertion.getConfirmationMethods()).containsExactly(SAML2Constants.CONF_SENDER_VOUCHES);
        assertThat(assertion.getSaml2().getSubject().getNameID().getValue()).isEqualTo("AllowGreetingServices");
    }

    /**
     * One case per custom check: the properties file the handler reads, the one callback change after the handler
     * filled it, and whether the validators require bearer assertions to be signed.
     *
     * <p>The original's null-confirmation-method check has no case of its own: the handler always sets a method, and
     * an assertion without one is rejected by the base validator's standard-method rule
     * ({@code requireStandardSubjectConfirmationMethod}) before any custom check runs.
     */
    static Stream<Arguments> rejectedAssertions() {
        return Stream.of(
                Arguments.of("issuer www.example.com of wrong-saml.properties", "wrong-saml.properties",
                        (Consumer<SAMLCallback>) callback -> { }, true),
                // SAML 1.1 carries the subject on each statement, which the base validator then accepts.
                Arguments.of("SAML 1.1 assertion", "saml.properties",
                        (Consumer<SAMLCallback>) callback -> {
                            callback.setSamlVersion(Version.SAML_11);
                            callback.getAuthenticationStatementData().get(0).setSubject(callback.getSubject());
                        }, true),
                // An unsigned bearer assertion would otherwise fail the base validator's bearer-signature rule first.
                Arguments.of("bearer subject confirmation", "saml.properties",
                        (Consumer<SAMLCallback>) callback ->
                                callback.getSubject().setSubjectConfirmationMethod(SAML2Constants.CONF_BEARER),
                        false),
                Arguments.of("subject NameID other than AllowGreetingServices", "saml.properties",
                        (Consumer<SAMLCallback>) callback -> callback.getSubject().setSubjectName("SomeoneElse"),
                        true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedAssertions")
    void customCheckRejectsAssertionTheBaseValidatorAccepts(String description, String propertiesFile,
            Consumer<SAMLCallback> change, boolean requireBearerSignature) throws Exception {
        SAMLCallback callback = filledCallback(propertiesFile);
        change.accept(callback);
        Credential credential = credentialOf(callback);
        RequestData data = samlRequestData();

        SamlAssertionValidator baseValidator = new SamlAssertionValidator();
        baseValidator.setRequireBearerSignature(requireBearerSignature);
        SAMLCustomValidator validator = new SAMLCustomValidator();
        validator.setRequireBearerSignature(requireBearerSignature);

        assertThatCode(() -> baseValidator.validate(credential, data)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(credential, data))
                .isInstanceOfSatisfying(WSSecurityException.class, rejection -> {
                    assertThat(rejection.getErrorCode()).isEqualTo(WSSecurityException.ErrorCode.FAILURE);
                    assertThat(rejection.getMsgID()).isEqualTo(INVALID_SAML_SECURITY);
                });
    }

    /** A fresh callback filled by the production handler from the given classpath SAML properties file. */
    private static SAMLCallback filledCallback(String propertiesFile) throws Exception {
        SAMLCallbackHandler handler = new SAMLCallbackHandler(new ClassPathResource(propertiesFile),
                UNUSED_ISSUER_KEY_PASSWORD, NO_ISSUER_CRYPTO);
        SAMLCallback callback = new SAMLCallback();
        handler.handle(new Callback[] {callback});
        return callback;
    }

    /** The credential WSS4J hands a SAML validator for the unsigned assertion the callback describes. */
    private static Credential credentialOf(SAMLCallback callback) throws WSSecurityException {
        Credential credential = new Credential();
        credential.setSamlAssertion(new SamlAssertionWrapper(callback));
        return credential;
    }

    /**
     * The request data {@code PathScopedSecurityInterceptor} builds for {@code /services/saml}, whose policy in
     * {@code WsSecurityConfig} is {@code new PathPolicy("SAMLTokenUnsigned Timestamp", null, null, null,
     * samlCustomValidator, false, false)}: no callback handler or crypto, subject-confirmation validation off, BSP
     * enforcement on, revocation off.
     */
    private static RequestData samlRequestData() {
        RequestData data = new RequestData();
        data.setWssConfig(WSSConfig.getNewInstance());
        data.setEnableRevocation(false);
        data.setValidateSamlSubjectConfirmation(false);
        data.setDisableBSPEnforcement(false);
        return data;
    }

}
