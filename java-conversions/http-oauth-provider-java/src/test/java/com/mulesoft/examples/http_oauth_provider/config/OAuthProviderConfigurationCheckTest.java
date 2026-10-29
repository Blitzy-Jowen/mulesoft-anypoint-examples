/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_oauth_provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.http_oauth_provider.HttpOauthProviderApplication;
import com.mulesoft.examples.http_oauth_provider.config.OAuthProviderProperties.Owner;

/**
 * Tests {@link OAuthProviderConfigurationCheck}, the startup WARN that names every blank client or resource-owner
 * credential key of {@link OAuthProviderProperties}.
 *
 * <p>The unit layer builds the properties in code and calls the check directly: each blank combination writes
 * exactly one WARN line naming exactly the blank keys and no configured value, and a complete configuration writes
 * nothing at any level. The startup layer boots {@link HttpOauthProviderApplication} itself without a web server, so
 * the check is shown to be found by the application's own component scan, to run when the application is ready, and
 * to let the context start with blank credentials.
 */
class OAuthProviderConfigurationCheckTest {

    /** Configured values, distinct from every key name, so a value written into the line would be detected. */
    private static final String CLIENT_ID = "SENTINEL-cid-5e1";
    private static final String CLIENT_SECRET = "SENTINEL-csec-5e1";
    private static final String OWNER_USERNAME = "SENTINEL-ouser-5e1";
    private static final String OWNER_PASSWORD = "SENTINEL-opass-5e1";

    private static final List<String> CONFIGURED_VALUES =
            List.of(CLIENT_ID, CLIENT_SECRET, OWNER_USERNAME, OWNER_PASSWORD);

    private static final List<String> ALL_KEYS = List.of(
            OAuthProviderConfigurationCheck.CLIENT_ID_KEY,
            OAuthProviderConfigurationCheck.CLIENT_SECRET_KEY,
            OAuthProviderConfigurationCheck.OWNER_USERNAME_KEY,
            OAuthProviderConfigurationCheck.OWNER_PASSWORD_KEY);

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(OAuthProviderConfigurationCheck.class);
        previousLevel = logger.getLevel();
        appender = new ListAppender<>();
        appender.start();
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    /**
     * Provider configuration of the original {@code oauth2-provider:config} (xml lines 26-37) with the given client
     * type and credentials.
     */
    private static OAuthProviderProperties properties(String clientType, String clientId, String clientSecret,
                                                      Owner owner) {
        return new OAuthProviderProperties(
                "SampleAPI",
                clientId,
                clientSecret,
                "Mule Bookstore",
                "Mule-powered On-line Bookstore",
                clientType,
                List.of("http://localhost*"),
                List.of("AUTHORIZATION_CODE"),
                List.of("READ_RESOURCE", "POST_RESOURCE"),
                "READ_RESOURCE",
                86400L,
                600L,
                owner);
    }

    private static OAuthProviderProperties confidential(String clientId, String clientSecret,
                                                        String ownerUsername, String ownerPassword) {
        return properties("CONFIDENTIAL", clientId, clientSecret,
                new Owner(ownerUsername, ownerPassword, List.of("RESOURCE_OWNER")));
    }

    /** The exact WARN line the check writes for {@code blankKeys}. */
    private static String expectedWarning(List<String> blankKeys) {
        return "OAuth provider credentials are not configured (" + String.join(", ", blankKeys)
                + "): authorization and token requests are rejected until they are supplied";
    }

    static Stream<Arguments> incompleteConfigurations() {
        return Stream.of(
                Arguments.of("client id empty",
                        confidential("", CLIENT_SECRET, OWNER_USERNAME, OWNER_PASSWORD),
                        List.of(OAuthProviderConfigurationCheck.CLIENT_ID_KEY)),
                Arguments.of("client secret empty",
                        confidential(CLIENT_ID, "", OWNER_USERNAME, OWNER_PASSWORD),
                        List.of(OAuthProviderConfigurationCheck.CLIENT_SECRET_KEY)),
                Arguments.of("owner username empty",
                        confidential(CLIENT_ID, CLIENT_SECRET, "", OWNER_PASSWORD),
                        List.of(OAuthProviderConfigurationCheck.OWNER_USERNAME_KEY)),
                Arguments.of("owner password empty",
                        confidential(CLIENT_ID, CLIENT_SECRET, OWNER_USERNAME, ""),
                        List.of(OAuthProviderConfigurationCheck.OWNER_PASSWORD_KEY)),
                Arguments.of("client secret and owner username empty, the others configured",
                        confidential(CLIENT_ID, "", "", OWNER_PASSWORD),
                        List.of(OAuthProviderConfigurationCheck.CLIENT_SECRET_KEY,
                                OAuthProviderConfigurationCheck.OWNER_USERNAME_KEY)),
                Arguments.of("all four empty, as the placeholders leave them",
                        confidential("", "", "", ""),
                        ALL_KEYS),
                Arguments.of("all four whitespace only",
                        confidential(" ", "\t", "  ", "\r\n"),
                        ALL_KEYS),
                Arguments.of("all four unset, normalised to empty by the record",
                        properties("CONFIDENTIAL", null, null, null),
                        ALL_KEYS),
                Arguments.of("PUBLIC client with id and secret empty: the secret is not required",
                        properties("PUBLIC", "", "", new Owner(OWNER_USERNAME, OWNER_PASSWORD, List.of())),
                        List.of(OAuthProviderConfigurationCheck.CLIENT_ID_KEY)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteConfigurations")
    void blankCredentialsAreNamedInOneWarning(String scenario, OAuthProviderProperties props,
                                              List<String> blankKeys) {
        logger.setLevel(Level.ALL);
        logger.addAppender(appender);

        new OAuthProviderConfigurationCheck(props).reportMissingCredentials();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).isEqualTo(expectedWarning(blankKeys));
        assertThat(event.getThrowableProxy()).isNull();
        for (String key : ALL_KEYS) {
            if (blankKeys.contains(key)) {
                assertThat(event.getFormattedMessage()).contains(key);
            } else {
                assertThat(event.getFormattedMessage()).doesNotContain(key);
            }
        }
        for (String value : CONFIGURED_VALUES) {
            assertThat(event.getFormattedMessage()).doesNotContain(value);
        }
    }

    static Stream<Arguments> completeConfigurations() {
        return Stream.of(
                Arguments.of("all four configured",
                        confidential(CLIENT_ID, CLIENT_SECRET, OWNER_USERNAME, OWNER_PASSWORD)),
                Arguments.of("PUBLIC client, lower case, with no secret",
                        properties("public", CLIENT_ID, "", new Owner(OWNER_USERNAME, OWNER_PASSWORD, List.of()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("completeConfigurations")
    void completeConfigurationLogsNothing(String scenario, OAuthProviderProperties props) {
        logger.setLevel(Level.ALL);
        logger.addAppender(appender);

        new OAuthProviderConfigurationCheck(props).reportMissingCredentials();

        assertThat(appender.list).isEmpty();
    }

    @Test
    void nullPropertiesAreRejected() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OAuthProviderConfigurationCheck(null))
                .withMessage("props");
    }

    static Stream<Arguments> startups() {
        return Stream.of(
                Arguments.of("credentials left blank, as application.yml leaves them",
                        new String[] {
                                "--oauth.provider.client-id=",
                                "--oauth.provider.client-secret=",
                                "--oauth.provider.owner.username=",
                                "--oauth.provider.owner.password="},
                        List.of(expectedWarning(ALL_KEYS))),
                Arguments.of("demo credentials of the test profile",
                        new String[] {"--spring.profiles.active=test"},
                        List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startups")
    void applicationStartupRunsTheCheck(String scenario, String[] credentialArgs, List<String> expectedWarnings) {
        String[] args = Stream.concat(
                        Stream.of("--spring.main.banner-mode=off"),
                        Stream.of(credentialArgs))
                .toArray(String[]::new);
        SpringApplicationBuilder application = new SpringApplicationBuilder(HttpOauthProviderApplication.class)
                .web(WebApplicationType.NONE)
                .listeners(new AttachOnPrepared(logger, appender));

        try (ConfigurableApplicationContext context = application.run(args)) {
            assertThat(context.isActive()).isTrue();
            assertThat(context.getBeansOfType(OAuthProviderConfigurationCheck.class)).hasSize(1);
            assertThat(appender.list)
                    .allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN))
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactlyElementsOf(expectedWarnings);
        }
    }

    /**
     * Attaches the capturing appender once Spring Boot has initialised the logging system, which resets Logback and
     * so detaches any appender added before the application starts.
     */
    private static final class AttachOnPrepared implements ApplicationListener<ApplicationPreparedEvent> {

        private final Logger logger;

        private final ListAppender<ILoggingEvent> appender;

        AttachOnPrepared(Logger logger, ListAppender<ILoggingEvent> appender) {
            this.logger = logger;
            this.appender = appender;
        }

        @Override
        public void onApplicationEvent(ApplicationPreparedEvent event) {
            logger.addAppender(appender);
        }
    }
}
