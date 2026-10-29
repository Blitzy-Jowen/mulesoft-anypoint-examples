/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.service.ContactQueryService;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.service.SalesforceOAuthService;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.support.HttpTestClient;

/**
 * Tests the {@code logging.level} caps of {@code application.yml}, which keep the OAuth authorization code, the
 * consumer secret and the Salesforce tokens out of the log when the root level is overridden to DEBUG or TRACE
 * ({@code --logging.level.root}, {@code --debug}, {@code --trace}). The original flow logged none of them.
 *
 * <p>Two layers:
 * <ul>
 *   <li>Each capped category and its level, bound from the classpath {@code application.yml} exactly as Spring Boot
 *       binds {@code logging.level}; nothing else is capped, so the application's own loggers, the original
 *       {@code contact: #[payload]} logger among them, keep their levels.</li>
 *   <li>The log itself. The whole application runs on its Undertow listener with WireMock standing in for
 *       {@code login.salesforce.com} and for the user's instance. Each case sets the Logback root, and the loggers
 *       {@code --trace} raises besides it, to TRACE and applies the bound levels it selects through Spring Boot's own
 *       {@link LoggingSystem}. A browser that has started its authorization at {@code /}, which redirects it with a
 *       {@code state} and the state cookie, then brings the callback
 *       {@code /oauth2callback?code=<sentinel>&state=<state>} with that cookie: the token client exchanges the code
 *       with the sentinel consumer secret, the Partner API query sent with the sentinel access token is rejected with
 *       {@code INVALID_SESSION_ID}, the session is renewed with the sentinel refresh token and the query is retried
 *       with the renewed sentinel access token, all through the production code. A Logback {@link ListAppender} on the
 *       root logger then names every
 *       logger that wrote a sentinel, in its formatted message or its throwable, and which sentinels it wrote. With
 *       every cap applied no logger writes one; the controls prove that the oracle sees the leak: without any cap
 *       the capped loggers write the sentinels, and with every cap but one exactly the loggers under that one do.</li>
 * </ul>
 *
 * <p>The sentinels are dummy values: none is a credential and none identifies anyone. The {@code state} is not one of
 * them: it is a one-time correlation value, not a credential, that travels in the authorization URL and the callback
 * URL by design, and the loggers that would write the callback's query are the ones capped for the code. While the
 * root is at TRACE its own appenders are detached, so that output stays out of the build log. After each test the
 * appender is detached, the root's appenders are attached again and every logger level the test changed is restored;
 * the context, which holds the authorized token slot and the listener port, is discarded after the class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SensitiveLogLevelsTest {

    private static final String DISPATCHER_SERVLET = "org.springframework.web.servlet.DispatcherServlet";

    private static final String HANDLER_METHOD = "org.springframework.web.method.HandlerMethod";

    private static final String DEFAULT_REST_CLIENT = "org.springframework.web.client.DefaultRestClient";

    /** The dummy values the flow carries; none may reach the log. */
    enum Sentinel {
        AUTHORIZATION_CODE("SENTINEL-CODE-8d41"),
        CLIENT_SECRET("SENTINEL-CLIENT-SECRET-8d41"),
        ACCESS_TOKEN("SENTINEL-ACCESS-TOKEN-8d41"),
        REFRESH_TOKEN("SENTINEL-REFRESH-TOKEN-8d41"),
        RENEWED_ACCESS_TOKEN("SENTINEL-RENEWED-TOKEN-8d41");

        /** Letters, digits and hyphens only, so no encoding on the wire or in a log line changes the value. */
        final String value;

        Sentinel(String value) {
            this.value = value;
        }
    }

    /** A capped category: its level in {@code application.yml} and what its loggers write without the cap. */
    private record Cap(LogLevel level, Map<String, Set<Sentinel>> leaksWithoutCap) {
    }

    /**
     * Every category {@code application.yml} caps, with the sentinels each logger under it writes when the category
     * inherits a root TRACE.
     */
    private static final Map<String, Cap> CAPS = capTable();

    private static Map<String, Cap> capTable() {
        Map<String, Cap> caps = new LinkedHashMap<>();
        caps.put(DISPATCHER_SERVLET, new Cap(LogLevel.WARN,
                Map.of(DISPATCHER_SERVLET, EnumSet.of(Sentinel.AUTHORIZATION_CODE))));
        caps.put(HANDLER_METHOD, new Cap(LogLevel.INFO,
                Map.of(HANDLER_METHOD, EnumSet.of(Sentinel.AUTHORIZATION_CODE))));
        caps.put(DEFAULT_REST_CLIENT, new Cap(LogLevel.INFO,
                Map.of(DEFAULT_REST_CLIENT, EnumSet.of(Sentinel.AUTHORIZATION_CODE, Sentinel.CLIENT_SECRET,
                        Sentinel.REFRESH_TOKEN))));
        return caps;
    }

    /**
     * The loggers {@code --trace} raises besides the root: Spring Boot's own and those of its {@code web} group. A
     * cap, set on the category itself, must hold against them too.
     */
    private static final List<String> TRACE_SWITCH_LOGGERS = List.of("org.springframework",
            "org.springframework.boot", "org.springframework.core.codec", "org.springframework.http",
            "org.springframework.web");

    private static final String TOKEN_PATH = "/services/oauth2/token";

    private static final String AUTHORIZE_PATH = "/services/oauth2/authorize";

    /** The Partner API endpoint on the instance: the token response's {@code instance_url} plus the SOAP path. */
    private static final String PARTNER_PATH = "/services/Soap/u/65.0";

    /** Not a secret: the consumer key travels in the browser's authorization URL. */
    private static final String CONSUMER_KEY = "test-consumer-key";

    private static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String PARTNER_NS = "urn:partner.soap.sforce.com";

    private static final String SOBJECT_NS = "urn:sobject.partner.soap.sforce.com";

    private static final String FAULT_NS = "urn:fault.partner.soap.sforce.com";

    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    private static final String TEXT_XML = "text/xml; charset=utf-8";

    /** The contact the instance returns, which the original {@code contact: #[payload]} logger writes. */
    private static final String CONTACT_LAST_NAME = "Pickwick";

    /** How long the captured log must stay as asserted, for lines written after the reply. */
    private static final Duration SETTLE = Duration.ofMillis(300);

    /** The listener port, fixed before the context starts: the callback's {@code redirect_uri} is built from it. */
    private static final int PORT = freePort();

    /** Stand-in for {@code login.salesforce.com} and for the user's instance. */
    private static final WireMockServer SALESFORCE = salesforce();

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    /** The Logback level each logger the test changed had before, {@code null} meaning inherited. */
    private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();

    private final ListAppender<ILoggingEvent> appender = new FormattingListAppender();

    /** The application's authorization flow, whose shared grant each case clears before it starts at {@code /}. */
    @Autowired
    private SalesforceOAuthService oauthService;

    /** The root logger's own appenders, detached while the root is at TRACE. */
    private final List<Appender<ILoggingEvent>> detachedAppenders = new ArrayList<>();

    /**
     * Moves the listener and every Salesforce URL to the test's servers and supplies the sentinel consumer secret
     * here, never inline: the test framework's TRACE lines print inlined {@code @SpringBootTest} properties.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("http.port", () -> PORT);
        registry.add("sfdc.oauth.callback.local-port", () -> PORT);
        registry.add("sfdc.key", () -> CONSUMER_KEY);
        registry.add("sfdc.secret", () -> Sentinel.CLIENT_SECRET.value);
        registry.add("sfdc.oauth.authorization-url", () -> SALESFORCE.baseUrl() + AUTHORIZE_PATH);
        registry.add("sfdc.oauth.access-token-url", () -> SALESFORCE.baseUrl() + TOKEN_PATH);
    }

    @AfterAll
    static void stopSalesforce() {
        SALESFORCE.stop();
    }

    @AfterEach
    void restoreLogging() {
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        appender.stop();
        detachedAppenders.forEach(root::addAppender);
        previousLevels.forEach(Logger::setLevel);
    }

    static Stream<Arguments> caps() {
        return CAPS.entrySet().stream().map(cap -> arguments(cap.getKey(), cap.getValue().level()));
    }

    /**
     * {@code application.yml} holds {@code category} at {@code level}, bound the way Spring Boot applies
     * {@code logging.level}, so a root DEBUG or TRACE override does not reach it.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("caps")
    void applicationYmlCapsSecretWritingCategory(String category, LogLevel level) throws IOException {
        assertThat(applicationYmlLevels()).as("logging.level in application.yml").containsEntry(category, level);
    }

    /** No other level is set, the root and the application's own packages included. */
    @Test
    void applicationYmlCapsNothingElse() throws IOException {
        assertThat(applicationYmlLevels().keySet()).as("logging.level in application.yml")
                .containsExactlyInAnyOrderElementsOf(CAPS.keySet());
    }

    static Stream<Arguments> levelSetups() {
        Stream<Arguments> allAndNone = Stream.of(
                arguments(named("application.yml levels", CAPS.keySet()), Map.of()),
                arguments(named("no levels (control)", Set.of()), leaksWithout(CAPS.keySet())));
        Stream<Arguments> allButOne = CAPS.keySet().stream().map(uncapped -> arguments(
                named("every level but " + uncapped + " (control)", CAPS.keySet().stream()
                        .filter(category -> !category.equals(uncapped)).collect(Collectors.toSet())),
                leaksWithout(Set.of(uncapped))));
        return Stream.concat(allAndNone, allButOne);
    }

    /**
     * With the root at TRACE and the {@code application.yml} levels of {@code applied} in force, the other
     * categories inheriting TRACE, the authorization-code callback, the code exchange, the rejected session and its
     * renewal write exactly the {@code expectedLeaks}: none with every level applied. The original {@code contact}
     * line is still written at INFO.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("levelSetups")
    void rootTraceLogCarriesSentinelsOnlyThroughCategoriesLeftUncapped(Set<String> applied,
            Map<String, Set<Sentinel>> expectedLeaks) throws IOException {
        String stateCookie = startAuthorization();
        String state = stateCookie.substring(stateCookie.indexOf('=') + 1);
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.iteratorForAppenders().forEachRemaining(detachedAppenders::add);
        detachedAppenders.forEach(root::detachAppender);
        appender.start();
        root.addAppender(appender);
        setLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.TRACE);
        TRACE_SWITCH_LOGGERS.forEach(name -> setLevel(name, LogLevel.TRACE));
        // A category left out inherits the root's TRACE, whatever an earlier Spring Boot context in this JVM set.
        applicationYmlLevels().forEach((category, level) ->
                setLevel(category, applied.contains(category) ? level : null));
        SALESFORCE.resetRequests();

        HttpTestClient.Response callback = HttpTestClient.localhost(PORT)
                .get("/oauth2callback?code=" + Sentinel.AUTHORIZATION_CODE.value + "&state=" + state,
                        Map.of("Cookie", stateCookie));

        assertThat(callback.status()).as("%s", callback).isEqualTo(200);
        assertThat(callback.bodyAsString()).isEqualTo("Salesforce query returned 1 contacts.");
        SALESFORCE.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withFormParam("grant_type", equalTo("authorization_code")));
        SALESFORCE.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withFormParam("grant_type", equalTo("refresh_token")));
        SALESFORCE.verify(2, postRequestedFor(urlEqualTo(PARTNER_PATH)));
        await().during(SETTLE).atMost(SETTLE.multipliedBy(5)).untilAsserted(() ->
                assertThat(leaks(events())).as("loggers that wrote a sentinel, with the sentinels they wrote")
                        .isEqualTo(expectedLeaks));
        List<ILoggingEvent> events = events();
        assertThat(events).as("TRACE events of loggers no level holds back")
                .anyMatch(event -> event.getLevel() == Level.TRACE);
        assertThat(events).as("the original contact: #[payload] logger")
                .anyMatch(event -> event.getLevel() == Level.INFO
                        && ContactQueryService.class.getName().equals(event.getLoggerName())
                        && event.getFormattedMessage().startsWith("contact: {")
                        && event.getFormattedMessage().contains("LastName=" + CONTACT_LAST_NAME));
    }

    /**
     * Starts an authorization as a browser does, before the case's levels and capture are set up, so neither the
     * build log nor the oracle sees it. The application first returns to the state of a fresh deployment: the shared
     * grant an earlier case stored is cleared, since only an application without a token redirects {@code /} and
     * issues a state; nothing else in the context changes.
     *
     * @return the state cookie as the browser sends it back, {@code sfdc_oauth_state=<state>}
     */
    private String startAuthorization() {
        ((AtomicReference<?>) ReflectionTestUtils.getField(oauthService, "token")).set(null);
        HttpTestClient.Response redirect = HttpTestClient.localhost(PORT).get("/");
        assertThat(redirect.status()).as("%s", redirect).isEqualTo(302);
        assertThat(redirect.headers("Set-Cookie")).as("%s", redirect).singleElement().asString()
                .startsWith(SalesforceOAuthService.STATE_COOKIE + "=");
        String setCookie = redirect.header("Set-Cookie");
        String stateCookie = setCookie.substring(0, setCookie.indexOf(';'));
        String state = stateCookie.substring(stateCookie.indexOf('=') + 1);
        assertThat(redirect.header("Location")).as("%s", redirect).endsWith("&state=" + state);
        return stateCookie;
    }

    /** The {@code logging.level} map of the classpath {@code application.yml}, bound as Spring Boot binds it. */
    private static Map<String, LogLevel> applicationYmlLevels() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        return new Binder(ConfigurationPropertySources.from(documents))
                .bind("logging.level", Bindable.mapOf(String.class, LogLevel.class))
                .orElseGet(Map::of);
    }

    /** The union of what the loggers under {@code uncapped} write. */
    private static Map<String, Set<Sentinel>> leaksWithout(Set<String> uncapped) {
        Map<String, Set<Sentinel>> leaks = new TreeMap<>();
        uncapped.forEach(category -> CAPS.get(category).leaksWithoutCap().forEach((logger, sentinels) ->
                leaks.computeIfAbsent(logger, name -> EnumSet.noneOf(Sentinel.class)).addAll(sentinels)));
        return leaks;
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

    private List<ILoggingEvent> events() {
        synchronized (appender) {
            // ListAppender appends under this lock, and the listener's worker threads may still be logging.
            return new ArrayList<>(appender.list);
        }
    }

    /** Each logger that wrote a sentinel, in its formatted message or its throwable, with the sentinels it wrote. */
    private static Map<String, Set<Sentinel>> leaks(List<ILoggingEvent> events) {
        Map<String, Set<Sentinel>> leaks = new TreeMap<>();
        for (ILoggingEvent event : events) {
            IThrowableProxy throwable = event.getThrowableProxy();
            String text = event.getFormattedMessage()
                    + (throwable == null ? "" : ThrowableProxyUtil.asString(throwable));
            for (Sentinel sentinel : Sentinel.values()) {
                if (text.contains(sentinel.value)) {
                    leaks.computeIfAbsent(event.getLoggerName(), name -> EnumSet.noneOf(Sentinel.class))
                            .add(sentinel);
                }
            }
        }
        return leaks;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not reserve a port for the listener", e);
        }
    }

    /**
     * Starts the Salesforce stand-in: the token endpoint answers the code exchange with the sentinel tokens and the
     * refresh grant with the renewed sentinel access token; the instance's Partner API rejects the first access
     * token as an invalid session and answers the query under the renewed one with one contact.
     */
    private static WireMockServer salesforce() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        String instanceUrl = server.baseUrl();
        server.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                .withFormParam("grant_type", equalTo("authorization_code"))
                .withFormParam("code", equalTo(Sentinel.AUTHORIZATION_CODE.value))
                .withFormParam("client_secret", equalTo(Sentinel.CLIENT_SECRET.value))
                .willReturn(tokenResponse("""
                        {"access_token": "%s", "refresh_token": "%s", "instance_url": "%s", "token_type": "Bearer"}
                        """.formatted(Sentinel.ACCESS_TOKEN.value, Sentinel.REFRESH_TOKEN.value, instanceUrl))));
        server.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                .withFormParam("grant_type", equalTo("refresh_token"))
                .withFormParam("refresh_token", equalTo(Sentinel.REFRESH_TOKEN.value))
                .withFormParam("client_secret", equalTo(Sentinel.CLIENT_SECRET.value))
                .willReturn(tokenResponse("""
                        {"access_token": "%s", "instance_url": "%s", "token_type": "Bearer"}
                        """.formatted(Sentinel.RENEWED_ACCESS_TOKEN.value, instanceUrl))));
        server.stubFor(post(urlEqualTo(PARTNER_PATH))
                .withRequestBody(sessionId(Sentinel.ACCESS_TOKEN))
                .willReturn(invalidSession()));
        server.stubFor(post(urlEqualTo(PARTNER_PATH))
                .withRequestBody(sessionId(Sentinel.RENEWED_ACCESS_TOKEN))
                .willReturn(contactQueryResponse()));
        return server;
    }

    private static ResponseDefinitionBuilder tokenResponse(String json) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(json);
    }

    /** Matches a SOAP request whose {@code SessionHeader} carries {@code token}, whatever prefix WSC writes. */
    private static StringValuePattern sessionId(Sentinel token) {
        return matching("(?s).*<(\\w+:)?sessionId>" + token.value + "</(\\w+:)?sessionId>.*");
    }

    /** The Partner API fault Salesforce sends for an expired session: HTTP 500 with {@code INVALID_SESSION_ID}. */
    private static ResponseDefinitionBuilder invalidSession() {
        String message = "Invalid Session ID found in SessionHeader: Illegal Session";
        return aResponse().withStatus(500).withHeader("Content-Type", TEXT_XML).withBody(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_NS + "\" xmlns:sf=\"" + FAULT_NS + "\""
                        + " xmlns:xsi=\"" + XSI_NS + "\"><soapenv:Body><soapenv:Fault>"
                        + "<faultcode>sf:INVALID_SESSION_ID</faultcode>"
                        + "<faultstring>INVALID_SESSION_ID: " + message + "</faultstring>"
                        + "<detail><sf:UnexpectedErrorFault xsi:type=\"sf:UnexpectedErrorFault\">"
                        + "<sf:exceptionCode>INVALID_SESSION_ID</sf:exceptionCode>"
                        + "<sf:exceptionMessage>" + message + "</sf:exceptionMessage>"
                        + "</sf:UnexpectedErrorFault></detail></soapenv:Fault></soapenv:Body></soapenv:Envelope>");
    }

    /** A {@code query} response holding one Contact, the shape of the original SOQL's rows. */
    private static ResponseDefinitionBuilder contactQueryResponse() {
        return aResponse().withStatus(200).withHeader("Content-Type", TEXT_XML).withBody(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                        + "<soapenv:Envelope xmlns:soapenv=\"" + SOAP_NS + "\" xmlns=\"" + PARTNER_NS + "\""
                        + " xmlns:sf=\"" + SOBJECT_NS + "\" xmlns:xsi=\"" + XSI_NS + "\"><soapenv:Body>"
                        + "<queryResponse><result xsi:type=\"QueryResult\"><done>true</done>"
                        + "<queryLocator xsi:nil=\"true\"/>"
                        + "<records xsi:type=\"sf:sObject\"><sf:type>Contact</sf:type>"
                        + "<sf:Id>0032000001INNfoAAH</sf:Id><sf:Id>0032000001INNfoAAH</sf:Id>"
                        + "<sf:LastName>" + CONTACT_LAST_NAME + "</sf:LastName>"
                        + "<sf:LastModifiedDate>2014-08-25T13:21:00.000Z</sf:LastModifiedDate></records>"
                        + "<size>1</size></result></queryResponse></soapenv:Body></soapenv:Envelope>");
    }

    /**
     * Formats each event as it arrives, as an appender that writes does: a logger may pass a mutable argument and
     * reuse it once the call returns, as httpclient5's wire log does with its thread's buffer.
     */
    private static final class FormattingListAppender extends ListAppender<ILoggingEvent> {

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    }
}
