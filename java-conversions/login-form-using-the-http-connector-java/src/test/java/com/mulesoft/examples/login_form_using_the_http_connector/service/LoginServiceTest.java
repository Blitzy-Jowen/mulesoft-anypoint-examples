/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.login_form_using_the_http_connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.login_form_using_the_http_connector.client.LoginRequesterClient;
import com.mulesoft.examples.login_form_using_the_http_connector.config.LoginProperties;
import com.mulesoft.examples.login_form_using_the_http_connector.mapper.ParseTemplateRenderer;
import com.mulesoft.examples.login_form_using_the_http_connector.service.LoginService.LoginFailedException;

/**
 * Unit test of {@link LoginService}, the processor logic of the three flows of
 * {@code login-form-using-the-http-connector.xml}, with {@link LoginRequesterClient} mocked and the real SC-14
 * {@link ParseTemplateRenderer} reading the carried {@code login/*.html} pages.
 *
 * <p>It covers {@code GetLoginPageFlow}'s page byte for byte; {@code DoLoginFlow}'s INFO log line (an ordinary
 * username verbatim, a character that could start or forge a line escaped), its credential
 * filter (exact, case-sensitive equality with the configured values, failing closed when they are empty, blank
 * or unset, with one WARN naming only the blank keys) and its catch strategy, reached by a filter rejection and
 * by a rendering failure alike; and
 * {@code CallLoginFlowUsingRequester}'s {@code HashMap} of the configured credentials and its unchanged relay of
 * the client's response or failure. No Spring context is started.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class LoginServiceTest {

    /** The only expression of {@code loginSuccessful.html}, as written in the template. */
    private static final String USERNAME_EXPRESSION = "#[payload.username]";

    /** Message of a login rejected by the credential filter, Mule's filter-unaccepted text. */
    private static final String FILTER_REJECTION = "Message has been rejected by filter.";

    /** The original example's demo credentials, supplied in production through {@code login.*}. */
    private static final LoginProperties DEMO = new LoginProperties("mule", "mule", "localhost");

    /** Credentials other than the demo values, showing that only configuration decides what is accepted. */
    private static final LoginProperties ALICE = new LoginProperties("alice", "s3cret", "localhost");

    @Mock
    LoginRequesterClient client;

    @Mock
    ParseTemplateRenderer failingRenderer;

    @Captor
    ArgumentCaptor<Map<String, String>> postedForm;

    Logger serviceLogger;

    Level previousLevel;

    ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureServiceLog() {
        serviceLogger = (Logger) LoggerFactory.getLogger(LoginService.class);
        previousLevel = serviceLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        serviceLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseServiceLog() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(previousLevel);
    }

    @Test
    void loginPageIsTheIndexTemplateUnchanged() {
        String page = service(DEMO, new ParseTemplateRenderer()).loginPage();

        assertThat(page).isEqualTo(classpath("login/index.html"));
        assertThat(appender.list).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void loginPageFailureIsNotTurnedIntoALoginFailure() {
        // GetLoginPageFlow has no exception strategy, so its failure must not reach DoLoginFlow's 403 branch.
        LoginService service = service(DEMO, new ParseTemplateRenderer(new ClassLoader(null) { }));

        Throwable thrown = catchThrowable(service::loginPage);

        assertThat(thrown).isExactlyInstanceOf(IllegalStateException.class).hasMessageContaining("Template not found");
        verifyNoInteractions(client);
    }

    static Stream<Arguments> acceptedLogins() {
        return Stream.of(
                Arguments.of("configured demo credentials", DEMO,
                        form("username", "mule", "password", "mule"), "mule"),
                Arguments.of("submit button field posted with the credentials", DEMO,
                        form("username", "mule", "password", "mule", "Submit", "Submit"), "mule"),
                Arguments.of("configured credentials other than the demo values", ALICE,
                        form("username", "alice", "password", "s3cret"), "alice"),
                // The log line escapes these characters; the check and the page still use the submitted value.
                Arguments.of("configured username holding characters the log line escapes",
                        new LoginProperties("ali\tce\u200B", "s3cret", "localhost"),
                        form("username", "ali\tce\u200B", "password", "s3cret"), "ali\tce\u200B"),
                Arguments.of("configured credentials with non-ASCII letters and a surrogate pair",
                        new LoginProperties("Jos\u00E9\uD83D\uDE00", "p\u00E4ss\u65E5\u672C\uD83D\uDE00", "localhost"),
                        form("username", "Jos\u00E9\uD83D\uDE00", "password", "p\u00E4ss\u65E5\u672C\uD83D\uDE00"),
                        "Jos\u00E9\uD83D\uDE00"),
                Arguments.of("configured credentials ending in an unpaired surrogate, submitted exactly",
                        new LoginProperties("mule\uD800", "mule\uD800", "localhost"),
                        form("username", "mule\uD800", "password", "mule\uD800"), "mule\uD800"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedLogins")
    void acceptedLoginRendersTheSuccessPageForTheUsername(String scenario, LoginProperties properties,
            Map<String, String> form, String username) {
        String page = service(properties, new ParseTemplateRenderer()).login(form);

        assertThat(page)
                .isEqualTo(classpath("login/loginSuccessful.html").replace(USERNAME_EXPRESSION, username))
                .contains("User " + username + " has been logged successfully!")
                .doesNotContain("#[");
        verifyNoInteractions(client);
    }

    static Stream<Arguments> rejectedLogins() {
        LoginProperties empty = new LoginProperties("", "", "localhost");
        LoginProperties unset = new LoginProperties(null, null, "localhost");
        // UTF-8 writes every unpaired surrogate as '?', so these values must still be told apart.
        LoginProperties unpaired = new LoginProperties("mule\uD800", "mule\uD800", "localhost");
        return Stream.of(
                Arguments.of("prefix of the configured username", DEMO, form("username", "mul", "password", "mule")),
                Arguments.of("prefix of the configured password", DEMO, form("username", "mule", "password", "mul")),
                Arguments.of("configured username plus a suffix", DEMO,
                        form("username", "mulex", "password", "mule")),
                Arguments.of("configured password plus a suffix", DEMO,
                        form("username", "mule", "password", "mulex")),
                Arguments.of("username of the same length differing in its last character", DEMO,
                        form("username", "mula", "password", "mule")),
                Arguments.of("password of the same length differing in its last character", DEMO,
                        form("username", "mule", "password", "mula")),
                Arguments.of("empty values submitted", DEMO, form("username", "", "password", "")),
                Arguments.of("other unpaired surrogate in the username", unpaired,
                        form("username", "mule\uD801", "password", "mule\uD800")),
                Arguments.of("question mark for the unpaired surrogate in the username", unpaired,
                        form("username", "mule?", "password", "mule\uD800")),
                Arguments.of("other unpaired surrogate in the password", unpaired,
                        form("username", "mule\uD800", "password", "mule\uD801")),
                Arguments.of("question mark for the unpaired surrogate in the password", unpaired,
                        form("username", "mule\uD800", "password", "mule?")),
                Arguments.of("wrong password", DEMO, form("username", "mule", "password", "wrong-pw")),
                Arguments.of("username differing only in case", DEMO, form("username", "Mule", "password", "mule")),
                Arguments.of("password differing only in case", DEMO, form("username", "mule", "password", "MULE")),
                Arguments.of("username missing", DEMO, form("password", "mule")),
                Arguments.of("password missing", DEMO, form("username", "mule")),
                Arguments.of("empty form", DEMO, form()),
                Arguments.of("no form", DEMO, null),
                Arguments.of("demo credentials while others are configured", ALICE,
                        form("username", "mule", "password", "mule")),
                Arguments.of("empty configured credentials, empty values submitted", empty,
                        form("username", "", "password", "")),
                Arguments.of("empty configured credentials, demo values submitted", empty,
                        form("username", "mule", "password", "mule")),
                Arguments.of("empty configured credentials, empty form", empty, form()),
                Arguments.of("empty configured password only, empty password submitted",
                        new LoginProperties("mule", "", "localhost"), form("username", "mule", "password", "")),
                Arguments.of("empty configured username only, empty username submitted",
                        new LoginProperties("", "mule", "localhost"), form("username", "", "password", "mule")),
                Arguments.of("unset configured credentials, empty form", unset, form()),
                Arguments.of("unset configured credentials, valueless fields submitted", unset,
                        form("username", null, "password", null)),
                Arguments.of("unset configured credentials, demo values submitted", unset,
                        form("username", "mule", "password", "mule")),
                Arguments.of("unset configured password only, valueless password submitted",
                        new LoginProperties("mule", null, "localhost"), form("username", "mule", "password", null)),
                Arguments.of("whitespace configured credentials, same whitespace submitted",
                        new LoginProperties("  ", "\t", "localhost"), form("username", "  ", "password", "\t")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedLogins")
    void rejectedLoginFailsWithAnUnmodifiableCopyOfTheSubmittedForm(String scenario, LoginProperties properties,
            Map<String, String> form) {
        Map<String, String> expectedForm = form == null ? new HashMap<>() : new HashMap<>(form);

        LoginFailedException failure = catchThrowableOfType(
                () -> service(properties, new ParseTemplateRenderer()).login(form), LoginFailedException.class);

        // No cause: the filter rejected the form; a wrapped NullPointerException would mean it never decided.
        assertThat(failure).isNotNull().hasMessage(FILTER_REJECTION).hasNoCause();
        assertThat(failure.form()).isEqualTo(expectedForm);
        if (form != null) {
            form.put("username", "changed-after-the-failure");
            assertThat(failure.form()).isEqualTo(expectedForm);
        }
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> failure.form().put("username", "mule"));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> failure.form().remove("password"));
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> failure.form().clear());
        verifyNoInteractions(client);
    }

    static Stream<Arguments> blankConfiguredCredentials() {
        String username = "Login rejected because login.username is not configured";
        String password = "Login rejected because login.password is not configured";
        String both = "Login rejected because login.username and login.password are not configured";
        List<String> usernameKey = List.of("login.username");
        List<String> passwordKey = List.of("login.password");
        List<String> bothKeys = List.of("login.username", "login.password");
        return Stream.of(
                Arguments.of("empty username", new LoginProperties("", "s3cret", "localhost"), username, usernameKey),
                Arguments.of("empty password", new LoginProperties("alice", "", "localhost"), password, passwordKey),
                Arguments.of("empty username and password", new LoginProperties("", "", "localhost"), both, bothKeys),
                Arguments.of("unset username", new LoginProperties(null, "s3cret", "localhost"), username,
                        usernameKey),
                Arguments.of("unset password", new LoginProperties("alice", null, "localhost"), password,
                        passwordKey),
                Arguments.of("unset username and password", new LoginProperties(null, null, "localhost"), both,
                        bothKeys),
                Arguments.of("whitespace username", new LoginProperties("  ", "s3cret", "localhost"), username,
                        usernameKey),
                Arguments.of("whitespace password", new LoginProperties("alice", "\t", "localhost"), password,
                        passwordKey),
                Arguments.of("whitespace username and password", new LoginProperties("  ", "\t", "localhost"), both,
                        bothKeys));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blankConfiguredCredentials")
    void loginAgainstBlankConfigurationIsRejectedWithOneWarningNamingOnlyTheBlankKeys(String scenario,
            LoginProperties properties, String expectedWarning, List<String> blankKeys) {
        Map<String, String> form = form("username", "mule", "password", "mule");

        LoginFailedException failure = catchThrowableOfType(
                () -> service(properties, new ParseTemplateRenderer()).login(form), LoginFailedException.class);

        assertThat(failure).isNotNull().hasMessage(FILTER_REJECTION).hasNoCause();
        assertThat(failure.form()).isEqualTo(form);
        assertThat(appender.list).satisfiesExactly(
                info -> {
                    assertThat(info.getLevel()).isEqualTo(Level.INFO);
                    assertThat(info.getFormattedMessage()).isEqualTo("User mule is trying to login");
                },
                warning -> {
                    assertThat(warning.getLevel()).isEqualTo(Level.WARN);
                    assertThat(warning.getFormattedMessage()).isEqualTo(expectedWarning);
                    assertThat(warning.getArgumentArray()).containsExactly(blankKeys.toArray());
                    // Neither the submitted credentials nor a configured value, blank or set, reaches the warning.
                    Stream.of("mule", properties.username(), properties.password())
                            .filter(value -> value != null && !value.isEmpty())
                            .forEach(value -> {
                                assertThat(warning.getFormattedMessage()).doesNotContain(value);
                                assertThat(Arrays.deepToString(warning.getArgumentArray())).doesNotContain(value);
                            });
                });
        verifyNoInteractions(client);
    }

    @Test
    void renderingFailureOfTheSuccessPageReachesTheCatchBranch() {
        IllegalStateException boom = new IllegalStateException("boom");
        when(failingRenderer.render(eq("login/loginSuccessful.html"), eq(Map.of(USERNAME_EXPRESSION, "mule"))))
                .thenThrow(boom);
        Map<String, String> form = form("username", "mule", "password", "mule");

        LoginFailedException failure = catchThrowableOfType(
                () -> service(DEMO, failingRenderer).login(form), LoginFailedException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getCause()).isSameAs(boom);
        assertThat(failure.form()).isEqualTo(form);
        verifyNoInteractions(client);
    }

    @Test
    void missingSuccessTemplateReachesTheCatchBranch() {
        Map<String, String> form = form("username", "mule", "password", "mule");
        LoginService service = service(DEMO, new ParseTemplateRenderer(new ClassLoader(null) { }));

        LoginFailedException failure = catchThrowableOfType(() -> service.login(form), LoginFailedException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.getCause())
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Template not found");
        assertThat(failure.form()).isEqualTo(form);
        verifyNoInteractions(client);
    }

    static Stream<Arguments> failuresBuiltWithoutForm() {
        return Stream.of(
                Arguments.of("filter rejection", (Supplier<LoginFailedException>) () -> new LoginFailedException(null),
                        FILTER_REJECTION),
                Arguments.of("failed step without cause",
                        (Supplier<LoginFailedException>) () -> new LoginFailedException(null, null), "Login failed"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresBuiltWithoutForm")
    void failureBuiltWithoutFormRecordsAnEmptyUnmodifiableForm(String scenario, Supplier<LoginFailedException> build,
            String expectedMessage) {
        LoginFailedException failure = build.get();

        assertThat(failure).hasMessage(expectedMessage).hasNoCause();
        assertThat(failure.form()).isEmpty();
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> failure.form().put("username", "mule"));
    }

    @Test
    void acceptedLoginLogsTheUsernameOnceAtInfo() {
        service(DEMO, new ParseTemplateRenderer()).login(form("username", "mule", "password", "mule"));

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("User mule is trying to login");
        });
    }

    @Test
    void attemptWithoutUsernameLogsNullAndNeverThePassword() {
        LoginService service = service(DEMO, new ParseTemplateRenderer());

        assertThatExceptionOfType(LoginFailedException.class)
                .isThrownBy(() -> service.login(form("password", "wrong-pw")));

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("User null is trying to login");
            assertThat(event.getMessage()).doesNotContain("wrong-pw");
            assertThat(Arrays.deepToString(event.getArgumentArray())).doesNotContain("wrong-pw");
        });
    }

    /** Usernames that could start or forge a log line: scenario, submitted value, its text in the log line. */
    static Stream<Arguments> hostileUsernames() {
        return Stream.of(
                Arguments.of("CR LF followed by a forged entry", "guest\r\nINFO forged-audit-entry",
                        "guest\\u000d\\u000aINFO forged-audit-entry"),
                Arguments.of("lone LF", "guest\nforged", "guest\\u000aforged"),
                Arguments.of("lone CR", "guest\rforged", "guest\\u000dforged"),
                Arguments.of("next line (NEL)", "guest\u0085forged", "guest\\u0085forged"),
                Arguments.of("line separator", "guest\u2028forged", "guest\\u2028forged"),
                Arguments.of("paragraph separator", "guest\u2029forged", "guest\\u2029forged"),
                Arguments.of("right-to-left override", "guest\u202Eforged", "guest\\u202eforged"),
                Arguments.of("tab", "guest\tforged", "guest\\u0009forged"),
                Arguments.of("NUL", "guest\0forged", "guest\\u0000forged"),
                Arguments.of("ESC of a terminal colour sequence", "guest\u001B[31mforged", "guest\\u001b[31mforged"),
                Arguments.of("DEL", "guest\u007Fforged", "guest\\u007fforged"),
                Arguments.of("zero-width space", "gu\u200Best", "gu\\u200best"),
                Arguments.of("byte order mark", "\uFEFFguest", "\\ufeffguest"),
                Arguments.of("unpaired high surrogate", "guest\uD800forged", "guest\\ud800forged"),
                Arguments.of("unpaired low surrogate", "guest\uDC00forged", "guest\\udc00forged"),
                Arguments.of("high surrogate ending the value", "guest\uD83D", "guest\\ud83d"),
                Arguments.of("surrogates in reverse order", "guest\uDE00\uD83D", "guest\\ude00\\ud83d"),
                Arguments.of("supplementary format character, one escape per UTF-16 unit", "guest\uDB40\uDC01",
                        "guest\\udb40\\udc01"));
    }

    /** Usernames the original logger already wrote on one line: scenario, submitted value, the same value. */
    static Stream<Arguments> ordinaryUsernames() {
        return Stream.of("mule", "Jos\u00E9", "DOMAIN\\user", "\u65E5\u672C", "mule\uD83D\uDE00", "guest\\u000d",
                        " mule user ", "mule\u00A0user", "")
                .map(username -> Arguments.of("'" + username + "'", username, username));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"hostileUsernames", "ordinaryUsernames"})
    void attemptLogsTheUsernameOnOneLineWhileTheCheckAndTheFailureKeepTheSubmittedValue(String scenario,
            String username, String loggedUsername) {
        Map<String, String> form = form("username", username, "password", "s3cret");

        LoginFailedException failure = catchThrowableOfType(
                () -> service(ALICE, new ParseTemplateRenderer()).login(form), LoginFailedException.class);

        assertThat(failure).isNotNull().hasMessage(FILTER_REJECTION).hasNoCause();
        assertThat(failure.form()).isEqualTo(form).containsEntry("username", username);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("User " + loggedUsername + " is trying to login")
                    .doesNotContain("\r", "\n", "\u0085", "\u2028", "\u2029");
        });
        verifyNoInteractions(client);
    }

    static Stream<Arguments> requesterCredentials() {
        return Stream.of(
                Arguments.of("configured demo credentials", DEMO, Map.of("username", "mule", "password", "mule")),
                Arguments.of("configured credentials other than the demo values", ALICE,
                        Map.of("username", "alice", "password", "s3cret")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requesterCredentials")
    void requesterLoginPostsTheConfiguredCredentialsAsHashMapAndRelaysTheResponse(String scenario,
            LoginProperties properties, Map<String, String> expectedForm) {
        LoginRequesterClient.LoginResponse relayed = new LoginRequesterClient.LoginResponse(
                200, "text/html; charset=UTF-8", "<html/>".getBytes(StandardCharsets.UTF_8));
        when(client.postLogin(anyMap())).thenReturn(relayed);

        LoginRequesterClient.LoginResponse response = service(properties, new ParseTemplateRenderer()).requesterLogin();

        assertThat(response).isSameAs(relayed);
        verify(client, times(1)).postLogin(postedForm.capture());
        assertThat(postedForm.getValue()).isExactlyInstanceOf(HashMap.class).isEqualTo(expectedForm);
        verifyNoMoreInteractions(client);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void requesterLoginRethrowsTheClientFailureUnchangedWithoutRetry() {
        IllegalStateException rejected = new IllegalStateException("Response code 403 mapped as failure.");
        when(client.postLogin(anyMap())).thenThrow(rejected);
        LoginService service = service(DEMO, new ParseTemplateRenderer());

        Throwable thrown = catchThrowable(service::requesterLogin);

        assertThat(thrown).isSameAs(rejected);
        verify(client, times(1)).postLogin(anyMap());
        verifyNoMoreInteractions(client);
    }

    private LoginService service(LoginProperties properties, ParseTemplateRenderer renderer) {
        return new LoginService(properties, renderer, client);
    }

    /** Reads a classpath resource as UTF-8, the expected bytes of a page rendered without substitution. */
    private static String classpath(String location) {
        try (InputStream in = LoginServiceTest.class.getClassLoader().getResourceAsStream(location)) {
            assertThat(in).as("classpath resource %s", location).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read classpath resource " + location, e);
        }
    }

    /** Builds a posted form from name/value pairs, in their order; a {@code null} value is a valueless field. */
    private static Map<String, String> form(String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected name/value pairs, got " + namesAndValues.length + " strings");
        }
        Map<String, String> form = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            form.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return form;
    }
}
