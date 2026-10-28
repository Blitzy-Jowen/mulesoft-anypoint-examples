/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.login_form_using_the_http_connector.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

import com.mulesoft.examples.login_form_using_the_http_connector.support.HttpTestClient;

/**
 * The three flows under {@code logging.level.root=TRACE}: the password a caller posts to {@code POST /login}, and the
 * configured one {@code /requesterLogin} posts there, reach the credential check as in the original and never reach
 * the log, because {@code application.yml} pins {@code DispatcherServlet} at WARN, and a password a caller puts in the
 * query never reaches it either, because {@code io.undertow.request.io} is pinned at INFO as well. The original
 * logged only the username ({@code Log username}, xml:18), which {@code LoginService} still writes at INFO.
 *
 * <p>Besides root TRACE, the {@code web} group is raised to TRACE, as {@code --debug} and {@code --trace} raise it,
 * and {@code spring.mvc.log-request-details} is on, under which {@code DispatcherServlet} would write the query and
 * every form parameter. Below WARN it would also read the form through the servlet parameters, which consumes the
 * body before {@code LoginController} reads it, so a correct login would be answered 403; each exchange therefore
 * asserts its answer as well as the log. Unpinned loggers still write their TRACE lines and the application still
 * writes its own lines, which the positive controls assert, so a clean log means the pins held rather than that
 * nothing was logged.
 *
 * <p>Every exchange goes over the socket of the application's Undertow listener, on a random port, through ST-1. The
 * configured password is a sentinel overriding the demo value of profile {@code test}; the username stays
 * {@code mule}. The logger levels found before the context starts are restored after the class, and the context is
 * discarded with it, so no later test runs at TRACE.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "logging.level.root=TRACE",
        "logging.level.web=TRACE",
        "spring.mvc.log-request-details=true"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class SensitiveLogLevelsTest {

    /** The configured {@code login.password}, which a correct login and the requester post. */
    private static final String PASSWORD = "SENTINEL_LOGIN_PASSWORD";

    /** The prefix every sentinel of this class carries; none may reach the log. */
    private static final String SENTINEL_PREFIX = "SENTINEL_";

    private static final String WRONG_PASSWORD = "SENTINEL_WRONG_LOGIN_PASSWORD";

    /** A password whose {@code %ZZ} is not valid percent-encoding, so the form cannot be URL-decoded. */
    private static final String UNDECODABLE_PASSWORD = "SENTINEL_UNDECODABLE_PASSWORD%ZZ";

    private static final String QUERY_PASSWORD = "SENTINEL_QUERY_PASSWORD";

    /** A password whose {@code %zz} is not valid percent-encoding, so the query cannot be URL-decoded. */
    private static final String UNDECODABLE_QUERY_PASSWORD = "SENTINEL_UNDECODABLE_QUERY_PASSWORD%zz";

    /** The {@code login.username} of profile {@code test}, which every login posts. */
    private static final String USERNAME = "mule";

    private static final String FORM = "application/x-www-form-urlencoded";

    private static final String SUCCESS_PAGE = "login/loginSuccessful.html";

    private static final String FAILURE_PAGE = "login/loginFailure.html";

    /** The only expression of {@code loginSuccessful.html} and {@code loginFailure.html} (line 13 of each). */
    private static final String USERNAME_EXPRESSION = "#[payload.username]";

    /** The 400 body for a form that cannot be URL-decoded. */
    private static final String UNDECODABLE_FORM_MESSAGE = "Cannot decode x-www-form-urlencoded payload";

    /** The line {@code LoginService} writes for every login, the original's {@code Log username} (xml:18). */
    private static final String USERNAME_LINE = "User " + USERNAME + " is trying to login";

    /** The WARN line {@code LoginController} writes for a form that cannot be URL-decoded. */
    private static final String UNDECODABLE_FORM_LINE =
            "Exception occurred parsing request: " + UNDECODABLE_FORM_MESSAGE;

    /** Written at TRACE by the unpinned {@code OrderedRequestContextFilter} for every request a servlet serves. */
    private static final String UNPINNED_TRACE_LINE = "Bound request context to thread";

    /** Written at TRACE by the unpinned {@code io.undertow.request}, the pinned logger's parent, per connection. */
    private static final String UNPINNED_UNDERTOW_TRACE_LINE = "Opened connection with";

    /** How long the output must stay free of sentinels after an exchange, for lines written after the reply. */
    private static final Duration SETTLE = Duration.ofMillis(300);

    /** The logger levels set before this class's context started, by logger name. */
    private static final Map<String, Level> LEVELS_BEFORE = new HashMap<>();

    @LocalServerPort
    int port;

    /**
     * Supplies the sentinel password here: the test framework's own TRACE lines print the inlined
     * {@code @SpringBootTest} properties, but never the values of a dynamic property source.
     */
    @DynamicPropertySource
    static void password(DynamicPropertyRegistry registry) {
        registry.add("login.password", () -> PASSWORD);
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

    /**
     * Root is at TRACE, yet each logger {@code application.yml} pins writes at the pinned level and above only:
     * INFO for {@code io.undertow.request.io}, WARN for {@code DispatcherServlet}, whose INFO stays off so that a root
     * WARN also silences its servlet start-up lines.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "org.springframework.web.servlet.DispatcherServlet, WARN",
            "io.undertow.request.io, INFO"})
    void pinnedLoggerStaysAtItsLevelUnderRootTrace(String name, String pinned) {
        assertThat(loggerContext().getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel()).isEqualTo(Level.TRACE);

        Logger logger = loggerContext().getLogger(name);
        Level pinnedLevel = Level.toLevel(pinned, null);

        assertThat(logger.getEffectiveLevel()).isEqualTo(pinnedLevel);
        for (Level level : List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR)) {
            assertThat(logger.isEnabledFor(level)).as("%s %s", name, level)
                    .isEqualTo(level.isGreaterOrEqual(pinnedLevel));
        }
    }

    /**
     * The exchanges that carry a password: a login with the configured password, one with a wrong password, the
     * requester's own login with the configured password, a form whose password cannot be URL-decoded, which the
     * listener rejects before the flow runs, and a password sent in the query, which never counts as the form.
     */
    static Stream<Arguments> passwordExchanges() {
        return Stream.of(
                Arguments.of("configured password", "POST", "/login", loginForm(PASSWORD),
                        200, page(SUCCESS_PAGE, USERNAME), USERNAME_LINE),
                Arguments.of("wrong password", "POST", "/login", loginForm(WRONG_PASSWORD),
                        403, page(FAILURE_PAGE, USERNAME), USERNAME_LINE),
                Arguments.of("requester login", "GET", "/requesterLogin", null,
                        200, page(SUCCESS_PAGE, USERNAME), USERNAME_LINE),
                Arguments.of("undecodable password", "POST", "/login", loginForm(UNDECODABLE_PASSWORD),
                        400, UNDECODABLE_FORM_MESSAGE, UNDECODABLE_FORM_LINE),
                Arguments.of("password in the query", "POST", "/login?" + loginForm(QUERY_PASSWORD), null,
                        403, page(FAILURE_PAGE, "null"), "User null is trying to login"));
    }

    /**
     * Each exchange gets the original's answer, and the log holds no password while still holding the exchange's
     * application line and an unpinned logger's TRACE line.
     *
     * @param description     names the case
     * @param method          the request method
     * @param path            the request path
     * @param form            the posted form body, or {@code null} for none
     * @param status          the expected status code
     * @param body            the expected body
     * @param applicationLine the line the application writes for this exchange
     * @param output          the output captured since the class started
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("passwordExchanges")
    void exchangeAnswersWithoutLoggingThePassword(String description, String method, String path, String form,
                                                  int status, String body, String applicationLine,
                                                  CapturedOutput output) {
        int applicationLinesBefore = occurrences(output, applicationLine);
        int unpinnedLinesBefore = occurrences(output, UNPINNED_TRACE_LINE);

        HttpTestClient.Response response = HttpTestClient.localhost(port).send(method, path,
                form == null ? null : Map.of("Content-Type", FORM),
                form == null ? null : form.getBytes(StandardCharsets.UTF_8));

        assertThat(response.status()).as("%s", response).isEqualTo(status);
        assertThat(response.bodyAsString()).as("%s", response).isEqualTo(body);
        assertLogHoldsNoSentinel(output);
        assertThat(occurrences(output, applicationLine))
                .as("the application still writes \"%s\"", applicationLine)
                .isGreaterThan(applicationLinesBefore);
        assertThat(occurrences(output, UNPINNED_TRACE_LINE))
                .as("the unpinned OrderedRequestContextFilter still writes at TRACE")
                .isGreaterThan(unpinnedLinesBefore);
    }

    /**
     * A query value that is not valid percent-encoding fails the listener's request parsing, which answers 400 with
     * no body before any servlet runs; the parse failure, whose exception quotes the value, is not logged, while the
     * listener's unpinned request logger still writes at TRACE.
     *
     * @param output the output captured since the class started
     */
    @Test
    void undecodableQueryPasswordIsBadRequestWithoutLoggingIt(CapturedOutput output) {
        int unpinnedLinesBefore = occurrences(output, UNPINNED_UNDERTOW_TRACE_LINE);

        HttpTestClient.Response response = HttpTestClient.localhost(port)
                .post("/login?" + loginForm(UNDECODABLE_QUERY_PASSWORD), null, null);

        assertThat(response.status()).as("%s", response).isEqualTo(400);
        assertThat(response.bodyAsString()).as("%s", response).isEmpty();
        assertLogHoldsNoSentinel(output);
        assertThat(occurrences(output, UNPINNED_UNDERTOW_TRACE_LINE))
                .as("the unpinned io.undertow.request still writes at TRACE")
                .isGreaterThan(unpinnedLinesBefore);
    }

    /** The form the login page submits, for {@link #USERNAME} and {@code password}, which is sent as given. */
    private static String loginForm(String password) {
        return "username=" + USERNAME + "&password=" + password;
    }

    /** A {@code login/*.html} template with {@link #USERNAME_EXPRESSION} replaced by {@code username}. */
    private static String page(String location, String username) {
        try (InputStream in = SensitiveLogLevelsTest.class.getClassLoader().getResourceAsStream(location)) {
            assertThat(in).as("%s on the test classpath", location).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace(USERNAME_EXPRESSION, username);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + location, e);
        }
    }

    /** How many times {@code text} occurs in the output captured so far. */
    private static int occurrences(CapturedOutput output, String text) {
        String all = output.getAll();
        int count = 0;
        for (int at = all.indexOf(text); at >= 0; at = all.indexOf(text, at + text.length())) {
            count++;
        }
        return count;
    }

    /**
     * Asserts that the output captured since the class started holds no sentinel, and still holds none after the
     * exchange has settled. A failure names only the offending lines.
     */
    private static void assertLogHoldsNoSentinel(CapturedOutput output) {
        await().during(SETTLE).atMost(SETTLE.multipliedBy(4)).untilAsserted(() -> assertThat(output.getAll().lines()
                .filter(line -> line.contains(SENTINEL_PREFIX)))
                .as("captured lines holding a sentinel")
                .isEmpty());
    }

    private static LoggerContext loggerContext() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }
}
