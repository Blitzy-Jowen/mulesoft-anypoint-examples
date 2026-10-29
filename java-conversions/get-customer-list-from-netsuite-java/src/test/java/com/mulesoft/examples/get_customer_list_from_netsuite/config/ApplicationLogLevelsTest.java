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
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.ServletRequestHandledEvent;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.get_customer_list_from_netsuite.support.HttpTestClient;

/**
 * Tests the {@code logging.level} caps of {@code application.yml}, which keep the NetSuite access token, the
 * NetSuite data and the {@code lastName} the request searches for out of the log when the root level is overridden
 * to DEBUG or TRACE: below INFO, Spring's string decoder writes every response body the NetSuite clients read, its
 * JSON encoder every request body they send, the MVC method processors every response body the application writes,
 * {@code org.springframework.web.servlet.DispatcherServlet} every request line with its query and, under
 * {@code spring.mvc.log-request-details}, the parameters and headers, and Undertow's {@code io.undertow.request.io}
 * the request-parse failure quoting a query value that does not decode. The codec loggers are composites created
 * with each codec, which write through {@code org.springframework.web.HttpLogging} wherever their own category is
 * held at INFO, so that category is capped as well. {@code DispatcherServlet} is held at WARN, because its only INFO
 * lines are the servlet start-up pair, which a root WARN must silence.
 *
 * <p>Three layers:
 * <ul>
 *   <li>The six categories bound from the classpath {@code application.yml} exactly as Spring Boot binds
 *       {@code logging.level}, each at its level.</li>
 *   <li>The client and response-body log, with the Logback root at TRACE and the bound levels applied through
 *       Spring Boot's own {@link LoggingSystem}, as the application does at start-up: a {@link WebClient} created
 *       afterwards, with its own exchange strategies as Boot's auto-configured builder gives each client, makes the
 *       three calls the NetSuite clients make (form token request, SuiteQL query with a JSON body, record read)
 *       against an in-process WireMock, and a standalone MockMvc answers with a rendered {@code byte[]} body. A
 *       Logback {@link ListAppender} on the root logger then names every logger that wrote a sentinel. Two control
 *       setups prove that the oracle sees the leak: without any cap the codec and method-processor loggers write the
 *       sentinels, and with only the two codec categories capped they reach the log through
 *       {@code org.springframework.web.HttpLogging}. MockMvc's servlet logs under its own
 *       {@code TestDispatcherServlet} category, so this layer never reaches the dispatcher's.</li>
 *   <li>The request log of the running application, {@link RequestLogging}: the real {@code DispatcherServlet} and
 *       Undertow under root DEBUG and TRACE.</li>
 * </ul>
 *
 * <p>The sentinels are dummy values: none is a credential and none identifies anyone. While the root is below INFO
 * its own appenders are detached, so that output stays out of the build log. After each test, those of
 * {@link RequestLogging} included, the appender is detached, the root's appenders are attached again and every
 * logger level the test changed is restored.
 */
class ApplicationLogLevelsTest {

    private static final String CORE_CODEC = "org.springframework.core.codec";

    private static final String HTTP_CODEC = "org.springframework.http.codec";

    private static final String HTTP_LOGGING = "org.springframework.web.HttpLogging";

    private static final String MVC_METHOD_PROCESSORS = "org.springframework.web.servlet.mvc.method.annotation";

    private static final String DISPATCHER_SERVLET = "org.springframework.web.servlet.DispatcherServlet";

    private static final String UNDERTOW_REQUEST_IO = "io.undertow.request.io";

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
     * {@code application.yml} holds {@code category} at {@code level}, bound the way Spring Boot applies
     * {@code logging.level}, so a root DEBUG or TRACE override does not reach it.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            CORE_CODEC + ", INFO",
            HTTP_CODEC + ", INFO",
            HTTP_LOGGING + ", INFO",
            MVC_METHOD_PROCESSORS + ", INFO",
            DISPATCHER_SERVLET + ", WARN",
            UNDERTOW_REQUEST_IO + ", INFO"})
    void applicationYmlHoldsDataWritingCategoryAtItsLevel(String category, LogLevel level) throws IOException {
        Map<String, LogLevel> levels = applicationYmlLevels();

        assertThat(levels).as("logging.level in application.yml").containsEntry(category, level);
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
        captureRootLog(LogLevel.TRACE, applied);

        callNetsuiteStandIn();
        writeRenderedBody();

        List<ILoggingEvent> events = capturedEvents();
        assertThat(events).as("events the root logger received at TRACE").isNotEmpty();
        Set<String> writers = events.stream()
                .filter(event -> carriesSentinel(event.getFormattedMessage()))
                .map(ILoggingEvent::getLoggerName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(writers).as("loggers that wrote a sentinel").containsExactlyInAnyOrderElementsOf(expectedWriters);
    }

    /**
     * Sends every event of the root logger to {@link #appender} alone, with the root at {@code rootLevel} and the
     * {@code application.yml} levels that {@code applied} selects in force. A category left out inherits the root's
     * level, whatever an earlier Spring Boot context in this JVM set.
     */
    private void captureRootLog(LogLevel rootLevel, Predicate<String> applied) throws IOException {
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.iteratorForAppenders().forEachRemaining(detachedAppenders::add);
        detachedAppenders.forEach(root::detachAppender);
        appender.start();
        root.addAppender(appender);
        setLevel(LoggingSystem.ROOT_LOGGER_NAME, rootLevel);
        applicationYmlLevels().forEach((category, level) -> setLevel(category, applied.test(category) ? level : null));
    }

    /** The events {@link #appender} has received so far. */
    private List<ILoggingEvent> capturedEvents() {
        synchronized (appender) {
            // ListAppender appends under this lock, and Reactor Netty and Undertow threads may still be logging.
            return new ArrayList<>(appender.list);
        }
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

    /**
     * The request log of the running application. The whole application runs on Undertow on a random port, its real
     * {@code DispatcherServlet} with {@code spring.mvc.log-request-details} on, and ST-1 {@link HttpTestClient} sends
     * each request over the socket. The WireMock above stands in for NetSuite through {@code netsuite.base-url}, and
     * the token provider signs its client assertion with an RSA key generated for this class; profile {@code test}
     * supplies the dummy account and OAuth identifiers.
     *
     * <p>Each {@link Exchange} searches for {@link #SURNAME}, a dummy surname, with the root at DEBUG or TRACE and
     * either the {@code application.yml} levels applied or, as the control proving that the oracle sees the leak, none
     * of them. Once the reply is read, and for a request the servlet serves once the dispatcher has published the
     * {@link ServletRequestHandledEvent} that follows its last log line of the request, the oracle names every logger
     * whose message or exception text carried the surname.
     *
     * <p>The context is discarded after the class; the enclosing class restores the levels and appenders after each
     * test.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "spring.mvc.log-request-details=true")
    @ActiveProfiles("test")
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class RequestLogging {

        /** A dummy surname, the {@code lastName} every exchange searches for. */
        private static final String SURNAME = "SENTINEL-SURNAME-7f3a";

        /** The encoder of the SuiteQL request body, whose query carries the surname. */
        private static final String SUITEQL_BODY_ENCODER = HTTP_CODEC + ".json.Jackson2JsonEncoder";

        private static final String TOKEN_RESPONSE =
                "{\"access_token\": \"test-access-token\", \"token_type\": \"bearer\", \"expires_in\": 3600}";

        /** A SuiteQL page holding one customer, none of whose values is the surname searched for. */
        private static final String SUITEQL_PAGE = "{\"hasMore\": false, \"items\": [{\"email\": \"" + EMAIL_SENTINEL
                + "\", \"firstname\": \"Sentinel\", \"lastname\": \"Customer\"}]}";

        /** How long the dispatcher may take to publish its request-handled event after the reply was read. */
        private static final long HANDLED_TIMEOUT_MILLIS = HttpTestClient.TIMEOUT_MILLIS;

        @TempDir
        static Path keyDirectory;

        /** PKCS#8 PEM file of an RSA key generated for this class; it signs the token request's client assertion. */
        private static Path privateKey;

        @LocalServerPort
        private int port;

        @Autowired
        private HandledRequests handledRequests;

        /**
         * The loggers that write the surname a served search sends to NetSuite at root DEBUG when no category is
         * capped: the dispatcher's request line alone, because DEBUG truncates the JSON encoder's SuiteQL request
         * body before the surname.
         */
        private static final Set<String> UNCAPPED_SEARCH_DEBUG_WRITERS = Set.of(DISPATCHER_SERVLET);

        /** {@link #UNCAPPED_SEARCH_DEBUG_WRITERS} and, at root TRACE, the untruncated SuiteQL request body. */
        private static final Set<String> UNCAPPED_SEARCH_TRACE_WRITERS =
                Set.of(DISPATCHER_SERVLET, SUITEQL_BODY_ENCODER);

        /**
         * The requests of the oracle, each with the status the application answers it with, and the loggers that
         * write the surname at root DEBUG and at root TRACE when no category is capped.
         */
        enum Exchange {

            /** NetSuite answers the customer query; the application renders the customer list. */
            CUSTOMER_LIST("/customers?lastName=" + SURNAME, okJson(SUITEQL_PAGE), 200, true,
                    UNCAPPED_SEARCH_DEBUG_WRITERS, UNCAPPED_SEARCH_TRACE_WRITERS),

            /** NetSuite fails the customer query; the flow has no exception strategy, so the platform 500 answers. */
            UPSTREAM_FAILURE("/customers?lastName=" + SURNAME, serverError(), 500, true,
                    UNCAPPED_SEARCH_DEBUG_WRITERS, UNCAPPED_SEARCH_TRACE_WRITERS),

            /** A query value that is not valid percent-encoding fails Undertow's parsing: 400 before any servlet. */
            UNDECODABLE_QUERY("/customers?lastName=" + SURNAME + "%zz", okJson(SUITEQL_PAGE), 400, false,
                    Set.of(UNDERTOW_REQUEST_IO), Set.of(UNDERTOW_REQUEST_IO));

            private final String path;

            private final ResponseDefinitionBuilder suiteQlAnswer;

            private final int status;

            private final boolean servletServes;

            private final Set<String> uncappedDebugWriters;

            private final Set<String> uncappedTraceWriters;

            Exchange(String path, ResponseDefinitionBuilder suiteQlAnswer, int status, boolean servletServes,
                    Set<String> uncappedDebugWriters, Set<String> uncappedTraceWriters) {
                this.path = path;
                this.suiteQlAnswer = suiteQlAnswer;
                this.status = status;
                this.servletServes = servletServes;
                this.uncappedDebugWriters = uncappedDebugWriters;
                this.uncappedTraceWriters = uncappedTraceWriters;
            }

            /** The loggers that write the surname under root {@code rootLevel} when no category is capped. */
            Set<String> uncappedWriters(LogLevel rootLevel) {
                return rootLevel == LogLevel.TRACE ? uncappedTraceWriters : uncappedDebugWriters;
            }
        }

        @DynamicPropertySource
        static void netsuiteProperties(DynamicPropertyRegistry registry) {
            registry.add("netsuite.base-url", netsuite::baseUrl);
            registry.add("netsuite.oauth.private-key-path", () -> privateKey.toString());
        }

        /** Writes the generated RSA-2048 private key as the unencrypted PKCS#8 PEM file the token provider reads. */
        @BeforeAll
        static void writePrivateKey() throws GeneralSecurityException, IOException {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes(US_ASCII))
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
            privateKey = keyDirectory.resolve("netsuite-test-key.pem");
            Files.writeString(privateKey, "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n",
                    US_ASCII);
        }

        static Stream<Arguments> requestLogCases() {
            return Stream.of(LogLevel.DEBUG, LogLevel.TRACE).flatMap(rootLevel -> Stream.of(Exchange.values())
                    .flatMap(exchange -> Stream.of(
                            arguments(rootLevel, exchange,
                                    named("application.yml levels", (Predicate<String>) category -> true), Set.of()),
                            arguments(rootLevel, exchange,
                                    named("no levels (control)", (Predicate<String>) category -> false),
                                    exchange.uncappedWriters(rootLevel)))));
        }

        /**
         * With the root at {@code rootLevel} and the {@code application.yml} levels that {@code applied} selects in
         * force, the other categories inheriting the root's level, the application answers {@code exchange} with its
         * status and exactly the {@code expectedWriters} loggers write the surname: none with every level applied.
         */
        @ParameterizedTest(name = "root {0}, {1}, {2}")
        @MethodSource("requestLogCases")
        void requestLogCarriesSurnameOnlyThroughCategoriesLeftBelowTheirCap(LogLevel rootLevel, Exchange exchange,
                Predicate<String> applied, Set<String> expectedWriters) throws Exception {
            stubNetsuite(exchange.suiteQlAnswer);
            handledRequests.clear();
            captureRootLog(rootLevel, applied);

            HttpTestClient.Response response = HttpTestClient.localhost(port).get(exchange.path);

            assertThat(response.status()).as("%s", response).isEqualTo(exchange.status);
            if (exchange.servletServes) {
                assertThat(handledRequests.await(HANDLED_TIMEOUT_MILLIS))
                        .as("request-handled event of %s", exchange.path)
                        .isNotNull();
            }
            assertThat(capturedEvents()).as("events the root logger received at %s", rootLevel).isNotEmpty();
            assertThat(writersOf(SURNAME)).as("loggers that wrote the surname")
                    .containsExactlyInAnyOrderElementsOf(expectedWriters);
        }

        /** Stubs NetSuite's token endpoint and SuiteQL with {@code suiteQlAnswer}. */
        private static void stubNetsuite(ResponseDefinitionBuilder suiteQlAnswer) {
            netsuite.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(okJson(TOKEN_RESPONSE)));
            netsuite.stubFor(post(urlPathEqualTo(SUITEQL_PATH)).willReturn(suiteQlAnswer));
        }

        /** The loggers whose captured events carry {@code value}. */
        private Set<String> writersOf(String value) {
            return capturedEvents().stream()
                    .filter(event -> carries(event, value))
                    .map(ILoggingEvent::getLoggerName)
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        /**
         * Whether {@code value} is in the event's formatted message or in the text of its exception, causes and
         * suppressed exceptions included.
         */
        private static boolean carries(ILoggingEvent event, String value) {
            String message = event.getFormattedMessage();
            return (message != null && message.contains(value))
                    || (event.getThrowableProxy() != null
                            && ThrowableProxyUtil.asString(event.getThrowableProxy()).contains(value));
        }

        /** Records the {@link ServletRequestHandledEvent} the dispatcher publishes after its last log line. */
        static class HandledRequests implements ApplicationListener<ServletRequestHandledEvent> {

            private final BlockingQueue<ServletRequestHandledEvent> events = new LinkedBlockingQueue<>();

            @Override
            public void onApplicationEvent(ServletRequestHandledEvent event) {
                events.add(event);
            }

            /** Forgets the events recorded so far. */
            void clear() {
                events.clear();
            }

            /** The next recorded event, waiting up to {@code timeoutMillis}; {@code null} when none arrives. */
            ServletRequestHandledEvent await(long timeoutMillis) throws InterruptedException {
                return events.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            }
        }

        /** Adds {@link HandledRequests} to the application context. */
        @TestConfiguration(proxyBeanMethods = false)
        static class HandledRequestsConfiguration {

            @Bean
            HandledRequests handledRequests() {
                return new HandledRequests();
            }
        }
    }
}
