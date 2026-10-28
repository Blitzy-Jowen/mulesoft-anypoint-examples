/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.service.BoxOAuthService;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support.HttpTestClient;
import com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.support.PortInUseRetryingContextLoader;

/**
 * Tests the {@code logging.level} caps of {@code application.yml}, which keep the Box authorization code, the Box
 * client secret and the Box tokens out of the log when the root level is overridden to DEBUG or TRACE
 * ({@code --logging.level.root}, {@code --debug}, {@code --trace}). The original flow logged none of them.
 *
 * <p>Two layers:
 * <ul>
 *   <li>Each capped category and its level, bound from the classpath {@code application.yml} exactly as Spring Boot
 *       binds {@code logging.level}; nothing else is capped, so the application's own loggers, the original
 *       {@code Redirecting user to Box login page} logger among them, keep their levels.</li>
 *   <li>The log itself. The whole application runs on both listeners, the {@code box-oauth} one over HTTPS with the
 *       carried {@code TLS_Context} stores of profile {@code test}, and WireMock stands in for Box. Each case sets
 *       the Logback root, and the loggers {@code --trace} raises besides it, to TRACE and applies the bound levels it
 *       selects through Spring Boot's own {@link LoggingSystem}. A browser then walks the original flow from
 *       {@code /web}: the local {@code /authorization} sends it to Box, Box's consent returns it to
 *       {@code /redirectUrl?code=<sentinel>}, the code is exchanged with the sentinel client secret for the sentinel
 *       tokens, and {@code /web/loginDone} searches Box with the sentinel access token, which Box rejects, so the
 *       sentinel refresh token renews it before the search is repeated, all through the production code. A Logback
 *       {@link ListAppender} on the root logger then names every logger that wrote a sentinel, in its formatted
 *       message or its throwable, and which sentinels it wrote. With every cap applied no logger writes one; the
 *       controls prove that the oracle sees the leak: without any cap the capped loggers write the sentinels, and
 *       with every cap but one exactly the loggers under that one do.</li>
 * </ul>
 *
 * <p>Every Box stand-in answer closes its connection, so each exchange opens a connection under the levels of its
 * case, as an application started at that level would: httpclient5 decides whether a connection logs its wire bytes
 * when it opens the connection.
 *
 * <p>The sentinels are dummy values: none is a credential and none identifies anyone. While the root is at TRACE its
 * own appenders are detached, so that output stays out of the build log. After each test the appender is detached,
 * the root's appenders are attached again and every logger level the test changed is restored; the context, which
 * holds the stored tokens and the listener ports, is discarded after the class.
 *
 * <p>Both listeners run on free ports drawn for each load of the context, never on the original 8081 and 8082, and
 * WireMock on a port of its own choosing. A drawn port is only free when it is drawn: when another process binds one
 * first, {@link PortInUseRetryingContextLoader} loads the context again on newly drawn ports, so the test reads both
 * ports from the loaded context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(loader = PortInUseRetryingContextLoader.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SensitiveLogLevelsTest {

    private static final String DISPATCHER_SERVLET = "org.springframework.web.servlet.DispatcherServlet";

    private static final String APACHE_HTTP = "org.apache.hc";

    /** The dummy values the flow carries; none may reach the log. */
    enum Sentinel {
        AUTHORIZATION_CODE("SENTINEL-CODE-3b7e"),
        CLIENT_SECRET("SENTINEL-CLIENT-SECRET-3b7e"),
        ACCESS_TOKEN("SENTINEL-ACCESS-TOKEN-3b7e"),
        REFRESH_TOKEN("SENTINEL-REFRESH-TOKEN-3b7e"),
        RENEWED_ACCESS_TOKEN("SENTINEL-RENEWED-TOKEN-3b7e"),
        ROTATED_REFRESH_TOKEN("SENTINEL-ROTATED-TOKEN-3b7e");

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
        caps.put(APACHE_HTTP, new Cap(LogLevel.INFO, Map.of(
                APACHE_HTTP + ".client5.http.headers",
                EnumSet.of(Sentinel.ACCESS_TOKEN, Sentinel.RENEWED_ACCESS_TOKEN),
                APACHE_HTTP + ".client5.http.wire", EnumSet.allOf(Sentinel.class))));
        return caps;
    }

    /**
     * The loggers {@code --trace} raises besides the root: Spring Boot's own and those of its {@code web} group. A
     * cap, set on the category itself, must hold against them too.
     */
    private static final List<String> TRACE_SWITCH_LOGGERS = List.of("org.springframework",
            "org.springframework.boot", "org.springframework.core.codec", "org.springframework.http",
            "org.springframework.web");

    private static final int ORIGINAL_HTTP_PORT = 8081;

    private static final int ORIGINAL_HTTPS_PORT = 8082;

    /** Redirects the browser follows before the test gives up, so a redirect loop fails instead of hanging. */
    private static final int MAX_REDIRECTS = 10;

    private static final String AUTHORIZE_PATH = "/api/oauth2/authorize";

    private static final String TOKEN_PATH = "/api/oauth2/token";

    private static final String SEARCH_PATH = "/2.0/search";

    private static final String SEARCH_RESPONSE = "{\"total_count\":0,\"entries\":[]}";

    /** The text of the original {@code userLoginDoneFlow} before the search result. */
    private static final String LOGIN_DONE_TEXT = "The user successfully authorized access to the resource. "
            + "Searching for files containing \"mule\": ";

    /** The original {@code boxUserLoginFlow} logger's message. */
    private static final String ORIGINAL_LOGGER_MESSAGE = "Redirecting user to Box login page";

    /** How long the captured log must stay as asserted, for lines written after the reply. */
    private static final Duration SETTLE = Duration.ofMillis(300);

    /** The WireMock stand-in for Box, started before the context on a port it binds itself. */
    @RegisterExtension
    static final WireMockExtension BOX = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

    private final LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());

    /** The Logback level each logger the test changed had before, {@code null} meaning inherited. */
    private final Map<Logger, Level> previousLevels = new LinkedHashMap<>();

    private final ListAppender<ILoggingEvent> appender = new FormattingListAppender();

    /** The root logger's own appenders, detached while the root is at TRACE. */
    private final List<Appender<ILoggingEvent>> detachedAppenders = new ArrayList<>();

    /** Main listener ({@code /web}, {@code /web/loginDone}), plain HTTP. */
    @LocalServerPort
    private int mainPort;

    /** {@code box-oauth} listener ({@code /authorization}, {@code /redirectUrl}), HTTPS with client certificate. */
    @Value("${ports.box-oauth}")
    private int httpsPort;

    @Value("${truststore.path}")
    private String trustStorePath;

    @Value("${truststore.password}")
    private String trustStorePassword;

    @Value("${keystore.path}")
    private String keyStorePath;

    @Value("${keystore.password}")
    private String keyStorePassword;

    /**
     * Moves both listeners to ports drawn for this load attempt, Spring invoking this method again for each attempt,
     * and every Box URL to WireMock, whose base URL the suppliers read once the extension has started; and supplies
     * the sentinel client secret here, never inline: the test framework's TRACE lines print inlined
     * {@code @SpringBootTest} properties. The HTTPS port is set through {@code ports.box-oauth}, which keeps the
     * listener's TLS settings and routes.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        int[] ports = drawFreePorts(2);
        registry.add("server.port", () -> ports[0]);
        registry.add("ports.box-oauth", () -> ports[1]);
        registry.add("box.secret", () -> Sentinel.CLIENT_SECRET.value);
        registry.add("box.authorization-url", () -> BOX.baseUrl() + AUTHORIZE_PATH);
        registry.add("box.token-url", () -> BOX.baseUrl() + TOKEN_PATH);
        registry.add("box.api-base-url", () -> BOX.baseUrl() + "/2.0");
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
     * categories inheriting TRACE, the browser flow, the code exchange, the rejected search and the token renewal
     * write exactly the {@code expectedLeaks}: none with every level applied. The original {@code boxUserLoginFlow}
     * logger still writes at INFO.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("levelSetups")
    void rootTraceLogCarriesSentinelsOnlyThroughCategoriesLeftUncapped(Set<String> applied,
            Map<String, Set<Sentinel>> expectedLeaks) throws IOException {
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
        stubBox();

        HttpTestClient.Response done = browseFromWeb();

        assertThat(done.status()).as("%s", done).isEqualTo(200);
        assertThat(done.bodyAsString()).isEqualTo(LOGIN_DONE_TEXT + SEARCH_RESPONSE);
        BOX.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withRequestBody(containing("grant_type=authorization_code")));
        BOX.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withRequestBody(containing("grant_type=refresh_token")));
        BOX.verify(2, getRequestedFor(urlPathEqualTo(SEARCH_PATH)));
        await().during(SETTLE).atMost(SETTLE.multipliedBy(5)).untilAsserted(() ->
                assertThat(leaks(events())).as("loggers that wrote a sentinel, with the sentinels they wrote")
                        .isEqualTo(expectedLeaks));
        List<ILoggingEvent> events = events();
        assertThat(events).as("TRACE events of loggers no level holds back")
                .anyMatch(event -> event.getLevel() == Level.TRACE);
        assertThat(events).as("the original boxUserLoginFlow logger")
                .anyMatch(event -> event.getLevel() == Level.INFO
                        && BoxOAuthService.class.getName().equals(event.getLoggerName())
                        && ORIGINAL_LOGGER_MESSAGE.equals(event.getFormattedMessage()));
    }

    /**
     * Box's consent, granted at once with the sentinel code and the state as received; the token endpoint, which
     * issues the sentinel tokens for that code and the sentinel client secret and renews them for the sentinel
     * refresh token; and the search, which rejects the first access token and answers under the renewed one. Every
     * answer closes its connection.
     */
    private void stubBox() {
        BOX.resetAll();
        String redirectUrl = "https://localhost:" + httpsPort + "/redirectUrl";
        BOX.stubFor(get(urlPathEqualTo(AUTHORIZE_PATH))
                .willReturn(closing(aResponse().withStatus(302)
                        .withHeader("Location", redirectUrl + "?code=" + Sentinel.AUTHORIZATION_CODE.value
                                + "&state={{urlEncode request.query.state}}")
                        .withTransformers("response-template"))));
        BOX.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                .withRequestBody(containing("grant_type=authorization_code"))
                .withRequestBody(containing("code=" + Sentinel.AUTHORIZATION_CODE.value))
                .withRequestBody(containing("client_secret=" + Sentinel.CLIENT_SECRET.value))
                .willReturn(closing(tokenResponse(Sentinel.ACCESS_TOKEN, Sentinel.REFRESH_TOKEN))));
        BOX.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                .withRequestBody(containing("grant_type=refresh_token"))
                .withRequestBody(containing("refresh_token=" + Sentinel.REFRESH_TOKEN.value))
                .withRequestBody(containing("client_secret=" + Sentinel.CLIENT_SECRET.value))
                .willReturn(closing(tokenResponse(Sentinel.RENEWED_ACCESS_TOKEN, Sentinel.ROTATED_REFRESH_TOKEN))));
        BOX.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .withQueryParam("query", equalTo("mule"))
                .withHeader("Authorization", equalTo("Bearer " + Sentinel.ACCESS_TOKEN.value))
                .willReturn(closing(aResponse().withStatus(401))));
        BOX.stubFor(get(urlPathEqualTo(SEARCH_PATH))
                .withQueryParam("query", equalTo("mule"))
                .withHeader("Authorization", equalTo("Bearer " + Sentinel.RENEWED_ACCESS_TOKEN.value))
                .willReturn(closing(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SEARCH_RESPONSE))));
    }

    /** A token response on one line: the grant's token extractors match the whole body without DOTALL. */
    private static ResponseDefinitionBuilder tokenResponse(Sentinel accessToken, Sentinel refreshToken) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"" + accessToken.value + "\",\"expires_in\":3600,"
                        + "\"refresh_token\":\"" + refreshToken.value + "\",\"token_type\":\"bearer\"}");
    }

    private static ResponseDefinitionBuilder closing(ResponseDefinitionBuilder response) {
        return response.withHeader("Connection", "close");
    }

    /**
     * Walks the browser's redirect chain from {@code /web} to {@code /web/loginDone}: plain HTTP to the main
     * listener and to WireMock, HTTPS with the {@code TLS_Context} stores to the {@code box-oauth} listener.
     *
     * @return the response that ends the chain
     */
    private HttpTestClient.Response browseFromWeb() {
        URI target = URI.create("http://localhost:" + mainPort + "/web");
        List<String> paths = new ArrayList<>();
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpTestClient.Response response = clientFor(target).get(requestTarget(target));
            paths.add(target.getRawPath());
            if (response.status() < 300 || response.status() >= 400) {
                assertThat(paths).as("paths the browser visited")
                        .containsExactly("/web", "/authorization", AUTHORIZE_PATH, "/redirectUrl", "/web/loginDone");
                return response;
            }
            String location = response.header("Location");
            assertThat(location).as("Location of %s", response).isNotNull();
            target = URI.create(location);
        }
        throw new AssertionError("More than " + MAX_REDIRECTS + " redirects: " + paths);
    }

    /** The client a browser would use for {@code target}; any target beyond the local servers fails the test. */
    private HttpTestClient clientFor(URI target) {
        assertThat(target.getHost()).as("host of %s", target).isEqualTo("localhost");
        int port = target.getPort();
        if ("http".equals(target.getScheme()) && (port == mainPort || port == BOX.getPort())) {
            return HttpTestClient.localhost(port);
        }
        if ("https".equals(target.getScheme()) && port == httpsPort) {
            return HttpTestClient.https("localhost", port, trustStorePath, "JKS", trustStorePassword,
                    keyStorePath, "JKS", keyStorePassword);
        }
        throw new AssertionError("Redirect to an unexpected listener: " + target);
    }

    /** The raw path and query of {@code target}, so the request carries the Location's encoding byte for byte. */
    private static String requestTarget(URI target) {
        String path = target.getRawPath() == null || target.getRawPath().isEmpty() ? "/" : target.getRawPath();
        return target.getRawQuery() == null ? path : path + "?" + target.getRawQuery();
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
            // ListAppender appends under this lock, and the listeners' worker threads may still be logging.
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

    /**
     * Draws {@code count} distinct free ports, none of them an original port, by holding every probe socket open
     * until all are found, then closes the probes for the application to bind the ports. They are free when drawn,
     * not reserved: a port another process binds in between fails the load with a port in use, which
     * {@link PortInUseRetryingContextLoader} answers by loading the context again on newly drawn ports.
     */
    private static int[] drawFreePorts(int count) {
        List<ServerSocket> held = new ArrayList<>();
        int[] ports = new int[count];
        try {
            int found = 0;
            while (found < count) {
                ServerSocket socket = new ServerSocket(0);
                held.add(socket);
                int port = socket.getLocalPort();
                if (port != ORIGINAL_HTTP_PORT && port != ORIGINAL_HTTPS_PORT) {
                    ports[found++] = port;
                }
            }
            return ports;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot draw free ports", e);
        } finally {
            for (ServerSocket socket : held) {
                try {
                    socket.close();
                } catch (IOException e) {
                    throw new UncheckedIOException("Cannot close the probe socket of port " + socket.getLocalPort(),
                            e);
                }
            }
        }
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
