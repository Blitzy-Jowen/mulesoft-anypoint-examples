/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.requestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.mulesoft.examples.proxying_a_rest_api.ProxyingARestApiApplication;
import com.mulesoft.examples.proxying_a_rest_api.support.HttpTestClient;

/**
 * Tests {@link SensitiveLogGuard} and its installation by {@link SensitiveLogGuardInstaller}.
 *
 * <p>The filter cases run on a Logback context of their own, so they leave the application's logging untouched.
 * The wire cases boot the application on Undertow (random port on IPv4 loopback) in front of a WireMock upstream,
 * once with {@code logging.level.root=TRACE} and once with explicit levels for the loggers the guard covers, both
 * with {@code spring.mvc.log-request-details=true}. Each sends the caller's bearer token, cookie, query token and
 * body through the proxy, asserts the upstream received them unchanged, and asserts that the captured console output
 * holds none of them while it does hold a {@code DEBUG} line of an unguarded logger, so the capture is known to see
 * the levels configured. Each scenario restores the logger levels it found and closes its context, so later test
 * classes see the logging configuration they would have seen without it.
 */
class SensitiveLogGuardTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    @ParameterizedTest(name = "[{index}] {0} guarded={1}")
    @CsvSource({
            "org.apache.hc, true",
            "org.apache.hc.client5.http.headers, true",
            "org.apache.hc.client5.http.wire, true",
            "org.apache.hc.client5.http.impl.classic.MainClientExec, true",
            "org.apache.hc.client5.http.impl.classic.RedirectExec, true",
            "org.apache.hc.client5.http.protocol.ResponseProcessCookies, true",
            "org.springframework.web.servlet.DispatcherServlet, true",
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver, true",
            "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor, true",
            "org.apache.hcx, false",
            "org.apache, false",
            "org.springframework.web.servlet, false",
            "org.springframework.web.servlet.DispatcherServletX, false",
            "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping, false",
            "com.mulesoft.examples.proxying_a_rest_api.client.BoxProxyClient, false",
            "ROOT, false"
    })
    void guardedLoggerLogsNothingBelowInfoWhateverItsLevelAndOtherLoggersAreUntouched(String name,
                                                                                    boolean guarded) {
        LoggerContext context = new LoggerContext();
        SensitiveLogGuard.install(context);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
        Logger logger = context.getLogger(name);
        logger.setLevel(Level.TRACE);

        logger.trace("trace");
        logger.debug("debug");
        logger.info("info");
        logger.warn("warn");
        logger.error("error");

        assertThat(logger.isTraceEnabled()).isEqualTo(!guarded);
        assertThat(logger.isDebugEnabled()).isEqualTo(!guarded);
        assertThat(logger.isInfoEnabled()).isTrue();
        assertThat(appender.list).extracting(ILoggingEvent::getLevel).containsExactlyElementsOf(guarded
                ? List.of(Level.INFO, Level.WARN, Level.ERROR)
                : List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR));
    }

    @Test
    void guardStaysFirstAndSingleAheadOfAnAcceptingFilterAcrossReinstallsResetsAndStops() {
        LoggerContext context = new LoggerContext();
        TurboFilter acceptAll = new TurboFilter() {
            @Override
            public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params,
                                      Throwable t) {
                return FilterReply.ACCEPT;
            }
        };
        acceptAll.start();
        context.addTurboFilter(acceptAll);
        Logger wire = context.getLogger("org.apache.hc.client5.http.wire");
        assertThat(wire.isDebugEnabled()).as("an accepting filter enables DEBUG without the guard").isTrue();

        SensitiveLogGuard.install(context);
        SensitiveLogGuard.install(context);

        assertThat(context.getTurboFilterList()).hasSize(2).first().isInstanceOf(SensitiveLogGuard.class);
        assertThat(context.getCopyOfListenerList()).hasSize(1);
        assertThat(wire.isDebugEnabled()).isFalse();

        // A reset, as any reconfiguration performs, drops every turbo filter; the guard is put back at once.
        context.reset();
        wire.setLevel(Level.DEBUG);

        assertThat(context.getTurboFilterList()).singleElement().isInstanceOf(SensitiveLogGuard.class);
        assertThat(context.getCopyOfListenerList()).hasSize(1);
        assertThat(wire.isDebugEnabled()).isFalse();

        // Spring Boot's re-initialization stops the context, which drops every context listener, and resets it
        // again; installing afterwards restores both.
        context.stop();
        context.reset();
        assertThat(context.getTurboFilterList()).isEmpty();
        assertThat(context.getCopyOfListenerList()).isEmpty();

        SensitiveLogGuard.install(context);
        wire.setLevel(Level.DEBUG);

        assertThat(context.getTurboFilterList()).singleElement().isInstanceOf(SensitiveLogGuard.class);
        assertThat(context.getCopyOfListenerList()).hasSize(1);
        assertThat(wire.isDebugEnabled()).isFalse();
    }

    @Test
    void unknownLevelIsDeniedOnlyForGuardedLoggersAndMissingLoggerIsLeftToTheLevels() {
        LoggerContext context = new LoggerContext();
        SensitiveLogGuard guard = new SensitiveLogGuard();

        assertThat(guard.decide(null, context.getLogger("org.apache.hc.client5.http.headers"), null, "x", null, null))
                .isEqualTo(FilterReply.DENY);
        assertThat(guard.decide(null, context.getLogger("com.example.Other"), null, "x", null, null))
                .isEqualTo(FilterReply.NEUTRAL);
        assertThat(guard.decide(null, null, Level.DEBUG, "x", null, null)).isEqualTo(FilterReply.NEUTRAL);
        assertThat(SensitiveLogGuard.isGuarded(null)).isFalse();
    }

    /** The application under {@code logging.level.root=TRACE}. */
    @Nested
    @TestPropertySource(properties = {
            "logging.level.root=TRACE",
            "spring.mvc.log-request-details=true"})
    class UnderRootTrace extends ProxiedExchangeLogging {
    }

    /**
     * The application under explicit levels for the guarded loggers, which outrank the {@code INFO} pins of
     * {@code application.yml}, as the command line or the environment would.
     */
    @Nested
    @TestPropertySource(properties = {
            "logging.level.org.apache.hc.client5.http.headers=DEBUG",
            "logging.level.org.apache.hc.client5.http.wire=DEBUG",
            "logging.level.org.apache.hc.client5.http.impl.classic=DEBUG",
            "logging.level.org.springframework.web.servlet.DispatcherServlet=TRACE",
            "logging.level.org.springframework.web.servlet.mvc.method.annotation=TRACE",
            "spring.mvc.log-request-details=true",
            "logging.level.com.mulesoft.examples.proxying_a_rest_api.client.BoxProxyClient=DEBUG"})
    class UnderExplicitGuardedLoggerLevels extends ProxiedExchangeLogging {
    }

    /** The wire cases both scenarios run. */
    @SpringBootTest(
            classes = ProxyingARestApiApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            // Pins the listener to the address ST-1 connects to, whatever server.address the project sets.
            properties = "server.address=" + LOOPBACK)
    @ActiveProfiles("test")
    @DirtiesContext
    @ExtendWith(OutputCaptureExtension.class)
    abstract static class ProxiedExchangeLogging {

        private static final String TOKEN = "Bearer SENTINEL_TOKEN";
        private static final String COOKIE = "session=SENTINEL_COOKIE";
        private static final String QUERY = "access_token=SENTINEL_QUERY";
        private static final String JSON_BODY = "{\"secret\":\"SENTINEL_BODY\"}";
        private static final String FORM_BODY = "grant_type=password&password=SENTINEL_FORM";

        /** Every sentinel the caller or the upstream sends; none may reach the log. */
        private static final List<String> SENTINELS = List.of("SENTINEL_TOKEN", "SENTINEL_COOKIE",
                "SENTINEL_QUERY", "SENTINEL_BODY", "SENTINEL_FORM", "SENTINEL_SET_COOKIE",
                "SENTINEL_RESPONSE_HEADER", "SENTINEL_RESPONSE_BODY", "SENTINEL_LOCATION", "SENTINEL_UPSTREAM_ERROR");

        /** How long the output must stay free of sentinels after the exchange, for lines written after the reply. */
        private static final Duration SETTLE = Duration.ofMillis(300);

        /** The logger levels set before this scenario's context started, by logger name. */
        private static final Map<String, Level> LEVELS_BEFORE = new HashMap<>();

        @RegisterExtension
        static WireMockExtension upstream = WireMockExtension.newInstance()
                .options(wireMockConfig().dynamicPort().bindAddress(LOOPBACK))
                .build();

        @LocalServerPort
        int port;

        @DynamicPropertySource
        static void upstreamUrl(DynamicPropertyRegistry registry) {
            registry.add("proxy.upstream-url", () -> "http://" + LOOPBACK + ":" + upstream.getPort());
        }

        @BeforeAll
        static void recordLevels() {
            LEVELS_BEFORE.clear();
            for (Logger logger : loggerContext().getLoggerList()) {
                if (logger.getLevel() != null) {
                    LEVELS_BEFORE.put(logger.getName(), logger.getLevel());
                }
            }
        }

        @AfterAll
        static void restoreLevels() {
            for (Logger logger : loggerContext().getLoggerList()) {
                Level before = LEVELS_BEFORE.get(logger.getName());
                // The root logger always has a level and cannot be given none.
                if (before != null || !org.slf4j.Logger.ROOT_LOGGER_NAME.equals(logger.getName())) {
                    logger.setLevel(before);
                }
            }
        }

        @BeforeEach
        void stubUpstream() {
            upstream.stubFor(any(urlPathEqualTo("/2.0/folders/0")).willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Set-Cookie", "sid=SENTINEL_SET_COOKIE")
                    .withHeader("X-Upstream", "SENTINEL_RESPONSE_HEADER")
                    .withBody("SENTINEL_RESPONSE_BODY")));
            upstream.stubFor(any(urlPathEqualTo("/2.0/folders")).willReturn(aResponse()
                    .withStatus(201)
                    .withBody("created")));
            upstream.stubFor(any(urlPathEqualTo("/oauth2/token")).willReturn(aResponse()
                    .withStatus(200)
                    .withBody("token-issued")));
            upstream.stubFor(any(urlPathEqualTo("/2.0/moved")).willReturn(aResponse()
                    .withStatus(302)
                    .withHeader("Location", "/2.0/landing?code=SENTINEL_LOCATION")));
            upstream.stubFor(any(urlPathEqualTo("/2.0/landing")).willReturn(aResponse()
                    .withStatus(200)
                    .withBody("landed")));
            upstream.stubFor(any(urlPathEqualTo("/2.0/broken")).willReturn(aResponse()
                    .withStatus(500)
                    .withBody("SENTINEL_UPSTREAM_ERROR")));
        }

        @Test
        void getCarriesTokenCookieAndQueryUpstreamWithoutLoggingThem(CapturedOutput output) {
            HttpTestClient.Response response = client().send("GET", "/2.0/folders/0?" + QUERY, credentials(), null);

            assertThat(response.status()).as(response::toString).isEqualTo(200);
            assertThat(response.bodyAsString()).isEqualTo("SENTINEL_RESPONSE_BODY");
            assertThat(response.header("Set-Cookie")).isEqualTo("sid=SENTINEL_SET_COOKIE");
            upstream.verify(getRequestedFor(urlEqualTo("/2.0/folders/0?" + QUERY))
                    .withHeader("Authorization", equalTo(TOKEN))
                    .withHeader("Cookie", equalTo(COOKIE)));
            assertLogHoldsNoSentinel(output);
            assertThat(output.getAll()).as("the unguarded BoxProxyClient DEBUG line is captured")
                    .contains("Proxying GET /2.0/folders/0");
        }

        @Test
        void jsonBodyReachesUpstreamUnchangedWithoutBeingLogged(CapturedOutput output) {
            Map<String, String> headers = credentials();
            headers.put("Content-Type", "application/json");

            HttpTestClient.Response response = client().send("POST", "/2.0/folders?" + QUERY, headers,
                    JSON_BODY.getBytes(StandardCharsets.UTF_8));

            assertThat(response.status()).as(response::toString).isEqualTo(200);
            assertThat(response.bodyAsString()).isEqualTo("created");
            upstream.verify(postRequestedFor(urlEqualTo("/2.0/folders?" + QUERY))
                    .withHeader("Authorization", equalTo(TOKEN))
                    .withRequestBody(equalTo(JSON_BODY)));
            assertLogHoldsNoSentinel(output);
        }

        // spring.mvc.log-request-details makes DispatcherServlet read the parameters to log them, which consumes a
        // form body of any method before the controller can forward it.
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"POST", "PUT", "PATCH"})
        void formBodyReachesUpstreamIntactWithoutBeingLogged(String method, CapturedOutput output) {
            Map<String, String> headers = credentials();
            headers.put("Content-Type", "application/x-www-form-urlencoded");

            HttpTestClient.Response response = client().send(method, "/oauth2/token?" + QUERY, headers,
                    FORM_BODY.getBytes(StandardCharsets.UTF_8));

            assertThat(response.status()).as(response::toString).isEqualTo(200);
            assertThat(response.bodyAsString()).isEqualTo("token-issued");
            upstream.verify(requestedFor(method, urlEqualTo("/oauth2/token?" + QUERY))
                    .withHeader("Authorization", equalTo(TOKEN))
                    .withRequestBody(equalTo(FORM_BODY)));
            assertLogHoldsNoSentinel(output);
        }

        @Test
        void followedRedirectLocationIsNotLogged(CapturedOutput output) {
            HttpTestClient.Response response = client().send("GET", "/2.0/moved?" + QUERY, credentials(), null);

            assertThat(response.status()).as(response::toString).isEqualTo(200);
            assertThat(response.bodyAsString()).isEqualTo("landed");
            upstream.verify(getRequestedFor(urlEqualTo("/2.0/landing?code=SENTINEL_LOCATION"))
                    .withHeader("Authorization", equalTo(TOKEN)));
            assertLogHoldsNoSentinel(output);
        }

        @Test
        void upstreamFailureIsStillLoggedAtErrorWithoutItsContent(CapturedOutput output) {
            HttpTestClient.Response response = client().send("GET", "/2.0/broken?" + QUERY, credentials(), null);

            assertThat(response.status()).as(response::toString).isEqualTo(500);
            assertLogHoldsNoSentinel(output);
            assertThat(output.getAll()).as("INFO and above pass the guard")
                    .contains("ERROR")
                    .contains("Unhandled exception processing GET /**: java.lang.IllegalStateException");
            assertThat(output.getAll().lines().filter(line -> line.contains("Unhandled exception processing")))
                    .as("the ERROR line names the route, never the request path")
                    .isNotEmpty()
                    .noneMatch(line -> line.contains("/2.0/broken"));
        }

        private HttpTestClient client() {
            return new HttpTestClient(LOOPBACK, port);
        }

        private static Map<String, String> credentials() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", TOKEN);
            headers.put("Cookie", COOKIE);
            return headers;
        }

        /** The output stays free of every sentinel for {@link #SETTLE} after the exchange. */
        private static void assertLogHoldsNoSentinel(CapturedOutput output) {
            await().during(SETTLE).atMost(SETTLE.multipliedBy(4))
                    .untilAsserted(() -> assertThat(output.getAll()).doesNotContain(SENTINELS.toArray(String[]::new)));
        }

        private static LoggerContext loggerContext() {
            return (LoggerContext) LoggerFactory.getILoggerFactory();
        }
    }
}
