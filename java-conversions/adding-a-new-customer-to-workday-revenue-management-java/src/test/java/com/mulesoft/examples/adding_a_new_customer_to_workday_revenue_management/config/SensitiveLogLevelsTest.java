/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.support.HttpTestClient;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.support.WorkdayTestRequests;

/**
 * Tests the {@code logging.level} caps of {@code application.yml}, which keep the Workday password, the
 * UsernameToken's {@code user@tenant} principal and the customer out of the log when the root level is overridden to
 * DEBUG or TRACE. {@code add_a_new_customer.xml} has no logger, so the original logged none of them at any level.
 * Below INFO, Spring WS's message tracing writes every secured envelope, the JDK {@code HttpURLConnection} under the
 * Workday sender every request line and header, and the Spring MVC method processors the posted customer XML, the
 * response body and a handled exception carrying Workday's fault text.
 *
 * <p>Two layers:
 * <ul>
 *   <li>Each cap bound from the classpath {@code application.yml} exactly as Spring Boot binds
 *       {@code logging.level}.</li>
 *   <li>The log itself: the whole application runs on a random port against an in-process WireMock Workday, with
 *       sentinel values for {@code wday.user}, {@code wday.tenant} and {@code wday.password}. The Logback root is set
 *       to TRACE and the bound levels are applied through Spring Boot's own {@link LoggingSystem}, as the application
 *       does at start-up with {@code logging.level.root=TRACE}; then a customer is added and a second one meets a
 *       Workday validation fault that quotes it. A Logback {@link ListAppender} on the root names every logger that
 *       wrote a sentinel, outside the application's own packages and the WireMock stand-in: none. The control setup
 *       clears the caps, so each capped category inherits the root's TRACE, and proves that the oracle sees the
 *       leak: exactly the capped loggers write the sentinels.</li>
 * </ul>
 *
 * <p>The sentinels are dummy values: none is a credential and none identifies anyone. While the root is at TRACE its
 * own appenders are detached, so that output stays out of the build log. After each test the appender is detached,
 * the root's appenders are attached again and every logger level the test changed is restored.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SensitiveLogLevelsTest {

    private static final String MESSAGE_TRACING = "org.springframework.ws.client.MessageTracing";

    private static final String URL_CONNECTION = "sun.net.www.protocol.http.HttpURLConnection";

    private static final String HANDLER_METHOD = "org.springframework.web.method.HandlerMethod";

    private static final String MVC_METHOD_PROCESSORS = "org.springframework.web.servlet.mvc.method.annotation.";

    private static final String BODY_READER = MVC_METHOD_PROCESSORS + "RequestResponseBodyMethodProcessor";

    private static final String BODY_WRITER = MVC_METHOD_PROCESSORS + "HttpEntityMethodProcessor";

    private static final String EXCEPTION_RESOLVER = MVC_METHOD_PROCESSORS + "ExceptionHandlerExceptionResolver";

    /** Unpinned; its TRACE line closes every request, after the handler and the error handling returned. */
    private static final String REQUEST_CONTEXT_FILTER =
            "org.springframework.boot.web.servlet.filter.OrderedRequestContextFilter";

    private static final String REQUEST_END = "Cleared thread-bound request context";

    /** The application's own loggers, outside what the caps govern. */
    private static final String APPLICATION_LOGGERS = "com.mulesoft.examples.";

    /** The in-process WireMock Workday, which stands in for the remote service and is no part of the application. */
    private static final List<String> STAND_IN_LOGGERS = List.of("com.github.tomakehurst.wiremock.", "wiremock.");

    /** Dummy {@code wday.user}, sent only inside the UsernameToken principal. */
    private static final String USER = "sentinel_wday_user";

    /** Dummy {@code wday.tenant}, part of the principal and of the endpoint path. */
    private static final String TENANT = "sentinel_wday_tenant";

    /** Dummy {@code wday.password}, sent as the UsernameToken's {@code PasswordText}. */
    private static final String PASSWORD = "sentinel_wday_password";

    /** The customer of the add that succeeds. */
    private static final String CUSTOMER_NAME = "SentinelCustomerName";

    /** The descriptor Workday answers the successful add with, which becomes the response body. */
    private static final String DESCRIPTOR = "Sentinel Customer Descriptor";

    /** The customer of the add that Workday rejects with a fault quoting it. */
    private static final String FAULT_CUSTOMER_NAME = "SentinelFaultCustomer";

    private static final String FAULT_STRING =
            "Invalid ID value. '" + FAULT_CUSTOMER_NAME + "' is not a valid ID value for type = 'Customer_Name'";

    /** Every value that must stay out of the framework loggers' output. */
    private static final List<String> SENTINELS = List.of(USER, TENANT, USER + "@" + TENANT, PASSWORD, CUSTOMER_NAME,
            DESCRIPTOR, FAULT_CUSTOMER_NAME);

    /** {@link #SENTINELS} and the decimal renderings Spring logs a {@code byte[]} body with. */
    private static final List<String> SENTINEL_RENDERINGS = Stream.concat(SENTINELS.stream(),
            SENTINELS.stream().map(SensitiveLogLevelsTest::decimalBytes)).toList();

    private static final String ENDPOINT_PATH = "/ccx/service/" + TENANT + "/Revenue_Management/v25.0";

    private static final String TEXT_XML = "text/xml;charset=UTF-8";

    private static final String PUT_CUSTOMER_RESPONSE = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">\
            <soapenv:Body>\
            <wd:Put_Customer_Response xmlns:wd="urn:com.workday/bsvc" wd:version="v25.0">\
            <wd:Customer_Reference wd:Descriptor="%s">\
            <wd:ID wd:type="WID">3bc6c1b9f4a0100156c2a0f8e9b10000</wd:ID>\
            </wd:Customer_Reference>\
            </wd:Put_Customer_Response>\
            </soapenv:Body>\
            </soapenv:Envelope>""".formatted(DESCRIPTOR);

    private static final String VALIDATION_FAULT = """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">\
            <soapenv:Body>\
            <soapenv:Fault xmlns:wd="urn:com.workday/bsvc">\
            <faultcode>wd:Validation_Error</faultcode>\
            <faultstring>%s</faultstring>\
            </soapenv:Fault>\
            </soapenv:Body>\
            </soapenv:Envelope>""".formatted(FAULT_STRING);

    /** Longest wait for the request threads' last log line. */
    private static final Duration REQUEST_END_TIMEOUT = Duration.ofSeconds(10);

    /** Started while the class loads, so its port exists when Spring resolves {@link #workday}'s suppliers. */
    private static final WireMockServer WORKDAY = startWorkday();

    @LocalServerPort
    private int port;

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    /** The Logback level each logger the test changed had before, {@code null} meaning inherited. */
    private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    /** The root logger's own appenders, detached while the root is at TRACE. */
    private final List<Appender<ILoggingEvent>> detachedAppenders = new ArrayList<>();

    @DynamicPropertySource
    static void workday(DynamicPropertyRegistry registry) {
        registry.add("wday.user", () -> USER);
        registry.add("wday.tenant", () -> TENANT);
        registry.add("wday.password", () -> PASSWORD);
        // The endpoint application.yml derives from host and tenant, on the stand-in's port.
        registry.add("wday.endpoint", () -> WORKDAY.baseUrl() + ENDPOINT_PATH);
        registry.add("wday.read-timeout", () -> "5s");
    }

    @AfterAll
    static void stopWorkday() {
        WORKDAY.stop();
    }

    @AfterEach
    void restoreLogging() {
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        appender.stop();
        detachedAppenders.forEach(root::addAppender);
        previousLevels.forEach(Logger::setLevel);
    }

    /**
     * {@code application.yml} holds {@code logger} at {@code level}, bound the way Spring Boot applies
     * {@code logging.level}, so a root DEBUG or TRACE override, or {@code --debug}/{@code --trace}, does not reach it.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            MESSAGE_TRACING + ", INFO",
            URL_CONNECTION + ", INFO",
            HANDLER_METHOD + ", INFO",
            BODY_READER + ", INFO",
            BODY_WRITER + ", INFO",
            EXCEPTION_RESOLVER + ", INFO",
            "org.springframework.web.HttpLogging, INFO"
    })
    void applicationYmlCapsSensitiveLogger(String logger, LogLevel level) throws IOException {
        assertThat(applicationYmlLevels()).as("logging.level in application.yml").containsEntry(logger, level);
    }

    static Stream<Arguments> levelSetups() {
        return Stream.of(
                arguments(named("application.yml levels", true), Set.of()),
                arguments(named("no levels (control)", false), Set.of(MESSAGE_TRACING + ".sent",
                        MESSAGE_TRACING + ".received", URL_CONNECTION, HANDLER_METHOD, BODY_READER, BODY_WRITER,
                        EXCEPTION_RESOLVER)));
    }

    /**
     * With the root at TRACE and the {@code application.yml} levels applied, or cleared for the control, a customer
     * is added and another rejected by Workday exactly as without any override, and exactly the
     * {@code expectedWriters} loggers write a sentinel: none with the levels applied.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("levelSetups")
    void rootTraceLogCarriesSentinelsOnlyThroughLoggersLeftBelowInfo(boolean levelsApplied,
            Set<String> expectedWriters) throws IOException {
        WORKDAY.resetAll();
        WORKDAY.stubFor(post(urlEqualTo(ENDPOINT_PATH)).withRequestBody(containing(CUSTOMER_NAME))
                .willReturn(soapResponse(200, PUT_CUSTOMER_RESPONSE)));
        WORKDAY.stubFor(post(urlEqualTo(ENDPOINT_PATH)).withRequestBody(containing(FAULT_CUSTOMER_NAME))
                .willReturn(soapResponse(500, VALIDATION_FAULT)));
        captureAtRootTrace(levelsApplied);

        HttpTestClient.Response added = HttpTestClient.localhost(port)
                .post("/", "application/xml", WorkdayTestRequests.customerXml(CUSTOMER_NAME));
        HttpTestClient.Response rejected = HttpTestClient.localhost(port)
                .post("/", "application/xml", WorkdayTestRequests.customerXml(FAULT_CUSTOMER_NAME));
        List<ILoggingEvent> events = eventsOnceRequestsEnded(2);

        assertThat(added.statusLine()).as(added.toString()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(added.bodyAsString()).isEqualTo("Added customer: " + DESCRIPTOR);
        assertThat(rejected.statusLine()).as(rejected.toString()).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(rejected.bodyAsString()).isEqualTo("{ \"message\": \"" + FAULT_STRING + "\" }");
        // Each Put_Customer went out once with the sentinel UsernameToken.
        for (String name : List.of(CUSTOMER_NAME, FAULT_CUSTOMER_NAME)) {
            WORKDAY.verify(1, postRequestedFor(urlEqualTo(ENDPOINT_PATH))
                    .withRequestBody(containing(name))
                    .withRequestBody(containing(">" + USER + "@" + TENANT + "<"))
                    .withRequestBody(containing(">" + PASSWORD + "<")));
        }
        assertThat(sentinelWriters(events)).as("loggers that wrote a sentinel")
                .containsExactlyInAnyOrderElementsOf(expectedWriters);
    }

    /**
     * Detaches the root's appenders, attaches {@link #appender} instead, sets the root to TRACE and applies the
     * {@code application.yml} levels, or clears them so that their categories inherit TRACE.
     */
    private void captureAtRootTrace(boolean levelsApplied) throws IOException {
        // JUL records, those of HttpURLConnection among them, reach Logback as at application start-up.
        assertThat(SLF4JBridgeHandler.isInstalled()).as("SLF4JBridgeHandler installed").isTrue();
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.iteratorForAppenders().forEachRemaining(detachedAppenders::add);
        detachedAppenders.forEach(root::detachAppender);
        appender.setContext(loggerContext);
        appender.start();
        root.addAppender(appender);
        setLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.TRACE);
        // A category left out inherits the root's TRACE, whatever an earlier Spring Boot context in this JVM set.
        applicationYmlLevels().forEach((category, level) -> setLevel(category, levelsApplied ? level : null));
    }

    /**
     * Waits until the unpinned request-context filter has closed {@code requests} requests at TRACE, which also shows
     * that the capture receives TRACE events, then returns every event captured so far.
     */
    private List<ILoggingEvent> eventsOnceRequestsEnded(int requests) {
        await().atMost(REQUEST_END_TIMEOUT).until(() -> capturedEvents().stream()
                .filter(event -> event.getLevel() == Level.TRACE
                        && REQUEST_CONTEXT_FILTER.equals(event.getLoggerName())
                        && event.getFormattedMessage().startsWith(REQUEST_END))
                .count() >= requests);
        return capturedEvents();
    }

    private List<ILoggingEvent> capturedEvents() {
        synchronized (appender) {
            // ListAppender appends under this lock, and the request threads may still be logging.
            return new ArrayList<>(appender.list);
        }
    }

    /** Names the loggers outside the application and the stand-in whose events carry a sentinel. */
    private static Set<String> sentinelWriters(List<ILoggingEvent> events) {
        return events.stream()
                .filter(event -> !event.getLoggerName().startsWith(APPLICATION_LOGGERS)
                        && STAND_IN_LOGGERS.stream().noneMatch(event.getLoggerName()::startsWith))
                .filter(event -> loggedTexts(event).stream()
                        .anyMatch(text -> SENTINEL_RENDERINGS.stream().anyMatch(text::contains)))
                .map(ILoggingEvent::getLoggerName)
                .collect(Collectors.toCollection(TreeSet::new));
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
     * Every text an event renders: its formatted message, each argument, and the class name and message of its
     * throwable and of every cause and suppressed throwable.
     */
    private static List<String> loggedTexts(ILoggingEvent event) {
        List<String> texts = new ArrayList<>();
        texts.add(String.valueOf(event.getFormattedMessage()));
        Object[] arguments = event.getArgumentArray();
        if (arguments != null) {
            for (Object argument : arguments) {
                texts.add(String.valueOf(argument));
            }
        }
        addThrowableTexts(event.getThrowableProxy(), texts);
        return texts;
    }

    private static void addThrowableTexts(IThrowableProxy proxy, List<String> texts) {
        if (proxy == null || proxy.isCyclic()) {
            return;
        }
        texts.add(proxy.getClassName());
        texts.add(String.valueOf(proxy.getMessage()));
        addThrowableTexts(proxy.getCause(), texts);
        IThrowableProxy[] suppressed = proxy.getSuppressed();
        if (suppressed != null) {
            for (IThrowableProxy each : suppressed) {
                addThrowableTexts(each, texts);
            }
        }
    }

    /** {@code text}'s UTF-8 bytes as {@code ObjectUtils.nullSafeToString} lists them: decimal, comma and space. */
    private static String decimalBytes(String text) {
        StringJoiner joined = new StringJoiner(", ");
        for (byte b : text.getBytes(UTF_8)) {
            joined.add(Byte.toString(b));
        }
        return joined.toString();
    }

    private static ResponseDefinitionBuilder soapResponse(int status, String envelope) {
        return aResponse().withStatus(status).withHeader("Content-Type", TEXT_XML).withBody(envelope);
    }

    private static WireMockServer startWorkday() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }
}
