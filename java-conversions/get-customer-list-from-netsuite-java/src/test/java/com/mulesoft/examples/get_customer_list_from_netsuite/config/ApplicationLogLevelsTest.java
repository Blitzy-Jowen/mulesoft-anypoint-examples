/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.config;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/**
 * Tests the {@code logging.level} caps of {@code application.yml}, which keep the NetSuite access token and the
 * NetSuite data out of the log when the root level is overridden to DEBUG or TRACE: below INFO, Spring's string
 * decoder writes every response body the NetSuite clients read, its JSON encoder every request body they send, and
 * the MVC method processors every response body the application writes. The codec loggers are composites created
 * with each codec, which write through {@code org.springframework.web.HttpLogging} wherever their own category is
 * held at INFO, so that category is capped as well.
 *
 * <p>Two layers:
 * <ul>
 *   <li>The four categories bound from the classpath {@code application.yml} exactly as Spring Boot binds
 *       {@code logging.level}.</li>
 *   <li>The log itself, with the Logback root at TRACE and the bound levels applied through Spring Boot's own
 *       {@link LoggingSystem}, as the application does at start-up: a {@link WebClient} created afterwards, with its
 *       own exchange strategies as Boot's auto-configured builder gives each client, makes the three calls the
 *       NetSuite clients make (form token request, SuiteQL query with a JSON body, record read) against an
 *       in-process WireMock, and a standalone MockMvc answers with a rendered {@code byte[]} body. A Logback
 *       {@link ListAppender} on the root logger then names every logger that wrote a sentinel. Two control setups
 *       prove that the oracle sees the leak: without any cap the codec and method-processor loggers write the
 *       sentinels, and with only the two codec categories capped they reach the log through
 *       {@code org.springframework.web.HttpLogging}.</li>
 * </ul>
 *
 * <p>The sentinels are dummy values: none is a credential and none identifies anyone. While the root is at TRACE its
 * own appenders are detached, so that output stays out of the build log. After each test the appender is detached,
 * the root's appenders are attached again and every logger level the test changed is restored.
 */
class ApplicationLogLevelsTest {

    private static final String CORE_CODEC = "org.springframework.core.codec";

    private static final String HTTP_CODEC = "org.springframework.http.codec";

    private static final String HTTP_LOGGING = "org.springframework.web.HttpLogging";

    private static final String MVC_METHOD_PROCESSORS = "org.springframework.web.servlet.mvc.method.annotation";

    private static final String TOKEN_PATH = "/services/rest/auth/oauth2/v1/token";

    private static final String SUITEQL_PATH = "/services/rest/query/v1/suiteql";

    private static final String RECORD_PATH = "/services/rest/record/v1/customer/1";

    private static final String RENDERED_PATH = "/rendered";

    /** A dummy standing in for a NetSuite access token. */
    private static final String TOKEN_SENTINEL = "SENTINEL-ACCESS-TOKEN-7f3a";

    /** A dummy standing in for a customer's e-mail address, in an unresolvable domain. */
    private static final String EMAIL_SENTINEL = "sentinel.customer@example.invalid";

    /** The rendered body the application writes as {@code byte[]}. */
    private static final byte[] RENDERED_BODY = ("<td>" + EMAIL_SENTINEL + "</td>").getBytes(UTF_8);

    /** {@link #RENDERED_BODY} as Spring's log formatting writes a {@code byte[]}: {@code {60, 116, 100, ...}}. */
    private static final String RENDERED_BODY_AS_LOGGED = ObjectUtils.nullSafeToString(RENDERED_BODY);

    @RegisterExtension
    static WireMockExtension netsuite = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    /** The Logback level each logger the test changed had before, {@code null} meaning inherited. */
    private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    /** The root logger's own appenders, detached while the root is at TRACE. */
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
    @ValueSource(strings = {CORE_CODEC, HTTP_CODEC, HTTP_LOGGING, MVC_METHOD_PROCESSORS})
    void applicationYmlHoldsBodyWritingCategoryAtInfo(String category) throws IOException {
        Map<String, LogLevel> levels = applicationYmlLevels();

        assertThat(levels).as("logging.level in application.yml").containsEntry(category, LogLevel.INFO);
    }

    static Stream<Arguments> levelSetups() {
        return Stream.of(
                arguments(named("application.yml levels", (Predicate<String>) category -> true), Set.of()),
                arguments(named("no levels (control)", (Predicate<String>) category -> false),
                        Set.of(CORE_CODEC + ".StringDecoder", HTTP_CODEC + ".json.Jackson2JsonEncoder",
                                MVC_METHOD_PROCESSORS + ".HttpEntityMethodProcessor")),
                arguments(named("codec categories only (control)",
                                (Predicate<String>) Set.of(CORE_CODEC, HTTP_CODEC)::contains),
                        Set.of(HTTP_LOGGING, MVC_METHOD_PROCESSORS + ".HttpEntityMethodProcessor")));
    }

    /**
     * With the root at TRACE and the {@code application.yml} levels that {@code applied} selects in force, the other
     * categories inheriting TRACE, exactly the {@code expectedWriters} loggers write a sentinel: none with every level
     * applied.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("levelSetups")
    void rootTraceLogCarriesSentinelsOnlyThroughCategoriesLeftBelowInfo(Predicate<String> applied,
            Set<String> expectedWriters) throws Exception {
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.iteratorForAppenders().forEachRemaining(detachedAppenders::add);
        detachedAppenders.forEach(root::detachAppender);
        appender.start();
        root.addAppender(appender);
        setLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.TRACE);
        // A category left out inherits the root's TRACE, whatever an earlier Spring Boot context in this JVM set.
        applicationYmlLevels().forEach((category, level) -> setLevel(category, applied.test(category) ? level : null));

        callNetsuiteStandIn();
        writeRenderedBody();

        List<ILoggingEvent> events;
        synchronized (appender) {
            // ListAppender appends under this lock, and Reactor Netty threads may still be logging.
            events = new ArrayList<>(appender.list);
        }
        assertThat(events).as("events the root logger received at TRACE").isNotEmpty();
        Set<String> writers = events.stream()
                .filter(event -> carriesSentinel(event.getFormattedMessage()))
                .map(ILoggingEvent::getLoggerName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(writers).as("loggers that wrote a sentinel").containsExactlyInAnyOrderElementsOf(expectedWriters);
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
     * Makes the NetSuite clients' three kinds of call through a {@link WebClient} created now, so its codecs pick
     * their loggers under the levels just set.
     */
    private static void callNetsuiteStandIn() {
        netsuite.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(okJson("{\"access_token\": \""
                + TOKEN_SENTINEL + "\", \"token_type\": \"bearer\", \"expires_in\": 3600}")));
        netsuite.stubFor(post(urlPathEqualTo(SUITEQL_PATH)).willReturn(okJson("{\"hasMore\": false, \"items\": "
                + "[{\"email\": \"" + EMAIL_SENTINEL + "\"}]}")));
        netsuite.stubFor(get(urlPathEqualTo(RECORD_PATH)).willReturn(okJson("{\"id\": \"1\", \"email\": \""
                + EMAIL_SENTINEL + "\"}")));
        // Without strategies of its own a builder reuses one JVM-wide set of codecs, whose loggers were picked when
        // that set was first used; Spring Boot's auto-configured builder creates the codecs with each client.
        WebClient webClient = WebClient.builder()
                .exchangeStrategies(ExchangeStrategies.builder().build())
                .baseUrl(netsuite.baseUrl())
                .build();

        String token = webClient.post().uri(TOKEN_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "client_credentials"))
                .retrieve().bodyToMono(String.class).block();
        String rows = webClient.post().uri(SUITEQL_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("q", "SELECT email FROM customer WHERE email = '" + EMAIL_SENTINEL + "'"))
                .retrieve().bodyToMono(String.class).block();
        String record = webClient.get().uri(RECORD_PATH)
                .retrieve().bodyToMono(String.class).block();

        assertThat(token).contains(TOKEN_SENTINEL);
        assertThat(rows).contains(EMAIL_SENTINEL);
        assertThat(record).contains(EMAIL_SENTINEL);
    }

    /** Answers one request with {@link #RENDERED_BODY} through the Spring MVC method processors. */
    private static void writeRenderedBody() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RenderingController()).build();

        byte[] body = mockMvc.perform(MockMvcRequestBuilders.get(RENDERED_PATH)).andReturn().getResponse()
                .getContentAsByteArray();

        assertThat(body).isEqualTo(RENDERED_BODY);
    }

    private static boolean carriesSentinel(String message) {
        return message != null && (message.contains(TOKEN_SENTINEL) || message.contains(EMAIL_SENTINEL)
                || message.contains(RENDERED_BODY_AS_LOGGED));
    }

    /** Answers as the application's controllers do, with a rendered body in a {@code ResponseEntity}. */
    @RestController
    static class RenderingController {

        @GetMapping(RENDERED_PATH)
        ResponseEntity<byte[]> rendered() {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(RENDERED_BODY.clone());
        }
    }
}
