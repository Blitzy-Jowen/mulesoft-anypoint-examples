/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.import_contacts_into_ms_dynamics.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.noContent;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.DynamicsFailureClassifier;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.DynamicsWebApiClient;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.client.UpstreamCallExecutor;
import com.mulesoft.examples.import_contacts_into_ms_dynamics.exception.UpstreamRateLimitException;

/**
 * Tests the {@code logging.level} caps of {@code application.yml}, which keep the Microsoft Entra access token, the
 * client secret and the contact data out of the log when the root level is overridden to DEBUG or TRACE. Below INFO,
 * Spring's JSON decoder writes the Entra token response with its {@code access_token}, its string encoder and decoder
 * the contact create request and contact query response bodies, and both {@code ExchangeFunctions} and Reactor
 * Netty's {@code HttpClientConnect} the request URL, whose contact query {@code $filter} carries the e-mail address.
 * The codec loggers are composites that write through {@code org.springframework.web.HttpLogging} wherever their own
 * category is held at INFO, so that category is capped as well.
 *
 * <p>Two layers:
 * <ul>
 *   <li>The five categories bound from the classpath {@code application.yml} exactly as Spring Boot binds
 *       {@code logging.level}, and no cap on the root or on the application's own package, whose upstream failure
 *       classification lines must keep reaching the log.</li>
 *   <li>The log itself, with the Logback root at DEBUG or TRACE and the bound levels applied through Spring Boot's own
 *       {@link LoggingSystem}, as the application does at start-up: the real {@link DynamicsOAuthConfig} beans and
 *       {@link DynamicsWebApiClient}, wired by an {@link ApplicationContextRunner} over Boot's Jackson, codecs and
 *       WebClient auto-configuration, obtain an Entra token, create a contact, query it by e-mail address, delete
 *       it, and make one throttled query, against an in-process WireMock. A Logback {@link ListAppender} on the root
 *       logger then names every logger whose message or throwable carries a sentinel. Control setups prove that the
 *       oracle sees each leak: without any cap the codec and transport loggers write the sentinels, with only the two
 *       codec categories capped the codec output reaches the log through {@code org.springframework.web.HttpLogging},
 *       and leaving out any single cap lets exactly the loggers that cap guards write.</li>
 * </ul>
 *
 * <p>The client secret is sent in the token request's form body, which Spring's form writer masks whatever the level,
 * and no bearer header is logged; the sentinel check covers both in every setup. The sentinels are obvious dummies:
 * none is a credential, none identifies anyone, and the e-mail addresses are in the unresolvable
 * {@code example.invalid} domain. While the root is below INFO its own appenders are detached, so that output stays
 * out of the build log. After each test the appender is detached, the root's appenders are attached again and every
 * logger level the test changed is restored.
 */
class ApplicationLogLevelsTest {

    private static final String CORE_CODEC = "org.springframework.core.codec";

    private static final String HTTP_CODEC = "org.springframework.http.codec";

    private static final String HTTP_LOGGING = "org.springframework.web.HttpLogging";

    private static final String EXCHANGE_FUNCTIONS =
            "org.springframework.web.reactive.function.client.ExchangeFunctions";

    private static final String HTTP_CLIENT_CONNECT = "reactor.netty.http.client.HttpClientConnect";

    /** The categories {@code application.yml} must hold at INFO. */
    private static final List<String> CAPPED_CATEGORIES =
            List.of(CORE_CODEC, HTTP_CODEC, HTTP_LOGGING, EXCHANGE_FUNCTIONS, HTTP_CLIENT_CONNECT);

    /** Writes the Entra token response it decodes. */
    private static final String JSON_DECODER = HTTP_CODEC + ".json.Jackson2JsonDecoder";

    /** Writes the contact create request body, which the client sends as a JSON string. */
    private static final String CHAR_SEQUENCE_ENCODER = CORE_CODEC + ".CharSequenceEncoder";

    /** Writes the contact query response body, which the client reads as a string. */
    private static final String STRING_DECODER = CORE_CODEC + ".StringDecoder";

    /** The package whose own log lines, the upstream failure classification included, must never be capped. */
    private static final String APPLICATION_PACKAGE = "com.mulesoft.examples.import_contacts_into_ms_dynamics";

    private static final String TOKEN_PATH = "/sentinel-tenant/oauth2/v2.0/token";

    private static final String CONTACTS_PATH = "/api/data/v9.2/contacts";

    private static final String CONTACT_GUID = "00000000-0000-0000-0000-000000000001";

    private static final String CONTACT_PATH = CONTACTS_PATH + "(" + CONTACT_GUID + ")";

    /** A dummy standing in for an Entra access token. */
    private static final String TOKEN_SENTINEL = "SENTINEL-ENTRA-TOKEN-5b9e";

    /** A dummy standing in for the Entra client secret. */
    private static final String CLIENT_SECRET_SENTINEL = "SENTINEL-CLIENT-SECRET-8d2f";

    private static final String FIRST_NAME_SENTINEL = "SENTINEL-FIRSTNAME-1a7c";

    private static final String LAST_NAME_SENTINEL = "SENTINEL-LASTNAME-6e3d";

    /** The local part of the contact's e-mail address, which percent-encoding in a query leaves unchanged. */
    private static final String EMAIL_SENTINEL = "sentinel.contact.4f0b";

    private static final String EMAIL = EMAIL_SENTINEL + "@example.invalid";

    private static final String PHONE_SENTINEL = "SENTINEL-PHONE-2c8a";

    /** The local part of the e-mail address of the query Dataverse throttles. */
    private static final String THROTTLED_EMAIL_SENTINEL = "sentinel.throttled.9d3e";

    private static final String THROTTLED_EMAIL = THROTTLED_EMAIL_SENTINEL + "@example.invalid";

    private static final List<String> SENTINELS = List.of(TOKEN_SENTINEL, CLIENT_SECRET_SENTINEL, FIRST_NAME_SENTINEL,
            LAST_NAME_SENTINEL, EMAIL_SENTINEL, PHONE_SENTINEL, THROTTLED_EMAIL_SENTINEL);

    /** An Entra client-credentials token response, its members in the order Entra writes them. */
    private static final String TOKEN_JSON = "{\"token_type\":\"Bearer\",\"expires_in\":3599,\"ext_expires_in\":3599,"
            + "\"access_token\":\"" + TOKEN_SENTINEL + "\"}";

    private static final String CONTACT_QUERY_JSON = "{\"value\":[{\"contactid\":\"" + CONTACT_GUID
            + "\",\"firstname\":\"" + FIRST_NAME_SENTINEL + "\",\"lastname\":\"" + LAST_NAME_SENTINEL + "\"}]}";

    private static final String THROTTLED_JSON = "{\"error\":{\"code\":\"0x80072321\","
            + "\"message\":\"Number of requests exceeded the limit of 6000 over time window of 300 seconds.\"}}";

    private static final String CLASSIFICATION_LINE =
            "Upstream Microsoft Dynamics 365 call failed [RATE_LIMIT]: ";

    @RegisterExtension
    static WireMockExtension dynamics = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    /** The Logback level each logger the test changed had before, {@code null} meaning inherited. */
    private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    /** The root logger's own appenders, detached while the root is below INFO. */
    private final List<Appender<ILoggingEvent>> detachedAppenders = new ArrayList<>();

    @AfterEach
    void restoreLogging() {
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        appender.stop();
        detachedAppenders.forEach(root::addAppender);
        previousLevels.forEach(Logger::setLevel);
    }

    /**
     * {@code application.yml} holds {@code category} at INFO, bound the way Spring Boot applies
     * {@code logging.level}, so a root DEBUG or TRACE override does not reach it.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {CORE_CODEC, HTTP_CODEC, HTTP_LOGGING, EXCHANGE_FUNCTIONS, HTTP_CLIENT_CONNECT})
    void applicationYmlHoldsValueWritingCategoryAtInfo(String category) throws IOException {
        assertThat(applicationYmlLevels()).as("logging.level in application.yml")
                .containsEntry(category, LogLevel.INFO);
    }

    /**
     * {@code application.yml} sets no level on the root or on the application's package, its ancestors or its
     * descendants, so a root override still reaches the application's own log lines.
     */
    @Test
    void applicationYmlLeavesRootAndApplicationCategoriesUncapped() throws IOException {
        assertThat(applicationYmlLevels().keySet()).as("logging.level categories in application.yml")
                .noneMatch(category -> category.equalsIgnoreCase(LoggingSystem.ROOT_LOGGER_NAME)
                        || category.equals(APPLICATION_PACKAGE)
                        || APPLICATION_PACKAGE.startsWith(category + ".")
                        || category.startsWith(APPLICATION_PACKAGE + "."));
    }

    static Stream<Arguments> levelSetups() {
        List<Arguments> setups = new ArrayList<>();
        for (LogLevel rootLevel : List.of(LogLevel.DEBUG, LogLevel.TRACE)) {
            setups.add(arguments(rootLevel, named("application.yml levels", (Predicate<String>) category -> true),
                    Set.of()));
            setups.add(arguments(rootLevel, named("no levels (control)", (Predicate<String>) category -> false),
                    Set.of(JSON_DECODER, CHAR_SEQUENCE_ENCODER, STRING_DECODER, EXCHANGE_FUNCTIONS,
                            HTTP_CLIENT_CONNECT)));
            setups.add(arguments(rootLevel, named("codec categories only (control)",
                            (Predicate<String>) Set.of(CORE_CODEC, HTTP_CODEC)::contains),
                    Set.of(HTTP_LOGGING, EXCHANGE_FUNCTIONS, HTTP_CLIENT_CONNECT)));
            Map<String, Set<String>> writersGuardedBy = Map.of(
                    CORE_CODEC, Set.of(CHAR_SEQUENCE_ENCODER, STRING_DECODER),
                    HTTP_CODEC, Set.of(JSON_DECODER),
                    HTTP_LOGGING, Set.of(HTTP_LOGGING),
                    EXCHANGE_FUNCTIONS, Set.of(EXCHANGE_FUNCTIONS),
                    HTTP_CLIENT_CONNECT, Set.of(HTTP_CLIENT_CONNECT));
            for (String omitted : CAPPED_CATEGORIES) {
                setups.add(arguments(rootLevel, named("every level but " + omitted + " (control)",
                                (Predicate<String>) category -> !category.equals(omitted)),
                        writersGuardedBy.get(omitted)));
            }
        }
        return setups.stream();
    }

    /**
     * With the root at {@code rootLevel} and the {@code application.yml} levels that {@code applied} selects in
     * force, the other categories inheriting the root's level, exactly the {@code expectedWriters} loggers write a
     * sentinel: none with every level applied. The application's own DEBUG request line and ERROR classification
     * line reach the log in every setup.
     */
    @ParameterizedTest(name = "root {0}, {1}")
    @MethodSource("levelSetups")
    void rootOverrideLogCarriesSentinelsOnlyThroughCategoriesLeftBelowInfo(LogLevel rootLevel,
            Predicate<String> applied, Set<String> expectedWriters) throws Exception {
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.iteratorForAppenders().forEachRemaining(detachedAppenders::add);
        detachedAppenders.forEach(root::detachAppender);
        appender.start();
        root.addAppender(appender);
        setLevel(LoggingSystem.ROOT_LOGGER_NAME, rootLevel);
        // A category left out inherits the root's level, whatever an earlier Spring Boot context in this JVM set.
        applicationYmlLevels().forEach((category, level) -> setLevel(category, applied.test(category) ? level : null));

        importThroughDynamicsStandIn();

        List<ILoggingEvent> events;
        synchronized (appender) {
            // ListAppender appends under this lock, and Reactor Netty threads may still be logging.
            events = new ArrayList<>(appender.list);
        }
        Set<String> writers = events.stream()
                .filter(ApplicationLogLevelsTest::carriesSentinel)
                .map(ILoggingEvent::getLoggerName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(writers).as("loggers that wrote a sentinel").containsExactlyInAnyOrderElementsOf(expectedWriters);
        assertThat(events).as("the application's own log lines")
                .anySatisfy(event -> {
                    assertThat(event.getLoggerName()).isEqualTo(DynamicsWebApiClient.class.getName());
                    assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                    assertThat(event.getFormattedMessage()).isEqualTo("Dataverse POST " + CONTACTS_PATH);
                })
                .anySatisfy(event -> {
                    assertThat(event.getLoggerName()).isEqualTo(UpstreamCallExecutor.class.getName());
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getFormattedMessage()).startsWith(CLASSIFICATION_LINE);
                });
    }

    /** The {@code logging.level} map of the classpath {@code application.yml}, bound as Spring Boot binds it. */
    private static Map<String, LogLevel> applicationYmlLevels() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        return new Binder(ConfigurationPropertySources.from(documents))
                .bind("logging.level", Bindable.mapOf(String.class, LogLevel.class))
                .orElseGet(Map::of);
    }

    /**
     * Sets a level through Spring Boot's logging system, {@code null} meaning inherited, remembering the logger's
     * Logback level the first time.
     */
    private void setLevel(String name, LogLevel level) {
        Logger logger = LoggingSystem.ROOT_LOGGER_NAME.equals(name)
                ? loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
                : loggerContext.getLogger(name);
        previousLevels.putIfAbsent(logger, logger.getLevel());
        loggingSystem.setLogLevel(name, level);
    }

    /**
     * Starts the application's Dynamics beans with the sentinel client secret, against WireMock as both the Entra
     * token endpoint and the Dataverse Web API, and makes the client's calls: a contact create, which obtains the
     * sentinel token first, a query by the contact's e-mail address, a delete, and a query Dataverse throttles, whose
     * rate limit the executor classifies and logs at ERROR.
     */
    private static void importThroughDynamicsStandIn() {
        dynamics.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(okJson(TOKEN_JSON)));
        dynamics.stubFor(post(urlPathEqualTo(CONTACTS_PATH)).willReturn(noContent()
                .withHeader("OData-EntityId", dynamics.baseUrl() + CONTACT_PATH)));
        dynamics.stubFor(get(urlPathEqualTo(CONTACTS_PATH)).atPriority(5).willReturn(okJson(CONTACT_QUERY_JSON)));
        dynamics.stubFor(get(urlPathEqualTo(CONTACTS_PATH)).atPriority(1)
                .withQueryParam("$filter", containing(THROTTLED_EMAIL_SENTINEL))
                .willReturn(aResponse().withStatus(429)
                        .withHeader(HttpHeaders.RETRY_AFTER, "7")
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(THROTTLED_JSON)));
        dynamics.stubFor(delete(urlPathEqualTo(CONTACT_PATH)).willReturn(noContent()));

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        CodecsAutoConfiguration.class, WebClientAutoConfiguration.class))
                .withUserConfiguration(DynamicsOAuthConfig.class, UpstreamCallExecutor.class,
                        DynamicsFailureClassifier.class, DynamicsWebApiClient.class)
                .withPropertyValues(
                        "dynamics.service-url=" + dynamics.baseUrl(),
                        "dynamics.oauth.tenant-id=sentinel-tenant",
                        "dynamics.oauth.client-id=sentinel-client-id",
                        "dynamics.oauth.client-secret=" + CLIENT_SECRET_SENTINEL,
                        "dynamics.oauth.token-uri=" + dynamics.baseUrl() + TOKEN_PATH)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DynamicsWebApiClient client = context.getBean(DynamicsWebApiClient.class);

                    assertThat(client.createContact(sentinelContact())).isEqualTo(CONTACT_GUID);
                    assertThat(client.findContactsByEmail(EMAIL)).singleElement()
                            .satisfies(contact -> assertThat(contact)
                                    .containsEntry("firstname", FIRST_NAME_SENTINEL)
                                    .containsEntry("lastname", LAST_NAME_SENTINEL));
                    client.deleteContact(CONTACT_GUID);
                    assertThatThrownBy(() -> client.findContactsByEmail(THROTTLED_EMAIL))
                            .isInstanceOfSatisfying(UpstreamRateLimitException.class,
                                    rateLimit -> assertThat(rateLimit.getRetryAfter()).isEqualTo("7"));
                });
    }

    /** The four contact columns {@code DynamicsContactMapper} hands the client, in the flow's order. */
    private static Map<String, Object> sentinelContact() {
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("firstname", FIRST_NAME_SENTINEL);
        contact.put("lastname", LAST_NAME_SENTINEL);
        contact.put("emailaddress1", EMAIL);
        contact.put("telephone1", PHONE_SENTINEL);
        return contact;
    }

    /**
     * Whether the event's formatted message, or the message of its throwable or of any of its causes and suppressed
     * throwables, carries a sentinel.
     */
    private static boolean carriesSentinel(ILoggingEvent event) {
        if (containsSentinel(event.getFormattedMessage())) {
            return true;
        }
        List<IThrowableProxy> pending = new ArrayList<>();
        Set<IThrowableProxy> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        if (event.getThrowableProxy() != null) {
            pending.add(event.getThrowableProxy());
        }
        while (!pending.isEmpty()) {
            IThrowableProxy proxy = pending.remove(pending.size() - 1);
            if (!visited.add(proxy)) {
                continue;
            }
            if (containsSentinel(proxy.getMessage())) {
                return true;
            }
            if (proxy.getCause() != null) {
                pending.add(proxy.getCause());
            }
            if (proxy.getSuppressed() != null) {
                pending.addAll(List.of(proxy.getSuppressed()));
            }
        }
        return false;
    }

    private static boolean containsSentinel(String text) {
        return text != null && SENTINELS.stream().anyMatch(text::contains);
    }
}
