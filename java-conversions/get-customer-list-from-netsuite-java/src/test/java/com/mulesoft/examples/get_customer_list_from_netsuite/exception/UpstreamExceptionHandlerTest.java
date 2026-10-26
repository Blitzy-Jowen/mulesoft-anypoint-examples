/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.exception;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.get_customer_list_from_netsuite.client.UpstreamSystem;

/**
 * Tests shared component SC-9's {@link UpstreamExceptionHandler} through standalone MockMvc: a
 * {@link ThrowingController} raises each upstream exception on {@code /upstream} and the handler, registered alone as
 * every HTTP-triggered connector project registers it, answers it.
 *
 * <p>Covered: 502, 429 and 503 with the two-field body for every upstream, the {@code Retry-After} copy and its
 * omission, JSON escaping of the message, a messageless exception, precedence over a {@link CatchAllAdvice} that
 * stands for a project's catch-all branch and is registered first, every other exception left unresolved, the
 * advice's order, scope and declared exception types, and its ERROR line, which a Logback {@link ListAppender} on the
 * handler's logger proves free of the upstream's message and of any throwable while the body still carries that
 * message.
 *
 * <p>Expected bodies are written as literal strings, never built with the handler's own helper, and compared as exact
 * UTF-8 bytes, because APIkit's JSON spacing is the contract. The {@code upstream} values are literal display names,
 * not {@code displayName()}, so the bytes are checked independently.
 *
 * <p>This is the SC-9 reference test of the handler. It is copied to every HTTP-triggered connector project with only
 * the package root changed, in the {@code package} line and in the import of the project's own
 * {@link UpstreamSystem}, so it depends on the JDK, JUnit, AssertJ, Spring's test support, Logback and the SC-9 types
 * alone.
 */
class UpstreamExceptionHandlerTest {

    /** The Content-Type of every upstream failure body: exactly {@code application/json}, without a charset. */
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    /** Text standing for request data, identifiers or tokens a vendor message can repeat. */
    private static final String SENTINEL = "SENTINEL-5d1e";

    private ThrowingController controller;

    /** The upstream advice alone. */
    private MockMvc mockMvc;

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        controller = new ThrowingController();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new UpstreamExceptionHandler())
                .build();
        logger = (Logger) LoggerFactory.getLogger(UpstreamExceptionHandler.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("An authentication failure is answered 502 with the two-field JSON body and no Retry-After")
    void authenticationIs502() throws Exception {
        MockHttpServletResponse response = perform(mockMvc,
                new UpstreamAuthenticationException(UpstreamSystem.SALESFORCE, "boom", null));

        assertEquals(502, response.getStatus());
        assertJson(response, "{ \"message\": \"boom\", \"upstream\": \"Salesforce\" }");
        assertNull(response.getHeader(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("A rate limit is answered 429 with the two-field JSON body and the vendor's Retry-After")
    void rateLimitIs429WithRetryAfter() throws Exception {
        MockHttpServletResponse response = perform(mockMvc,
                new UpstreamRateLimitException(UpstreamSystem.SALESFORCE, "boom", "30", null));

        assertEquals(429, response.getStatus());
        assertJson(response, "{ \"message\": \"boom\", \"upstream\": \"Salesforce\" }");
        assertEquals(List.of("30"), response.getHeaders(HttpHeaders.RETRY_AFTER));
    }

    @ParameterizedTest(name = "Retry-After [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("A rate limit without a usable Retry-After is answered 429 without the header")
    void rateLimitWithoutRetryAfterOmitsHeader(String retryAfter) throws Exception {
        MockHttpServletResponse response = perform(mockMvc,
                new UpstreamRateLimitException(UpstreamSystem.SALESFORCE, "boom", retryAfter, null));

        assertEquals(429, response.getStatus());
        assertJson(response, "{ \"message\": \"boom\", \"upstream\": \"Salesforce\" }");
        assertNull(response.getHeader(HttpHeaders.RETRY_AFTER));
    }

    @ParameterizedTest(name = "Retry-After [{0}]")
    @ValueSource(strings = {"120", "Wed, 21 Oct 2026 07:28:00 GMT", " 30 "})
    @DisplayName("A rate limit copies the vendor's Retry-After exactly as received")
    void rateLimitCopiesRetryAfterExactlyAsReceived(String retryAfter) throws Exception {
        MockHttpServletResponse response = perform(mockMvc,
                new UpstreamRateLimitException(UpstreamSystem.SALESFORCE, "throttled", retryAfter, null));

        assertEquals(429, response.getStatus());
        assertEquals(List.of(retryAfter), response.getHeaders(HttpHeaders.RETRY_AFTER));
        assertJson(response, "{ \"message\": \"throttled\", \"upstream\": \"Salesforce\" }");
    }

    @Test
    @DisplayName("An unavailable upstream is answered 503 with the two-field JSON body and no Retry-After")
    void unavailableIs503() throws Exception {
        MockHttpServletResponse response = perform(mockMvc,
                new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "boom", null));

        assertEquals(503, response.getStatus());
        assertJson(response, "{ \"message\": \"boom\", \"upstream\": \"Salesforce\" }");
        assertNull(response.getHeader(HttpHeaders.RETRY_AFTER));
    }

    @ParameterizedTest(name = "{0} from {2}")
    @MethodSource("everyFailureFromEveryUpstream")
    @DisplayName("The upstream field is the system's display name, for every failure from every upstream")
    void upstreamNameIsTheSystemDisplayName(Failure failure, UpstreamSystem system, String displayName)
            throws Exception {
        MockHttpServletResponse response = perform(mockMvc, failure.create(system, "boom", null));

        assertEquals(failure.status, response.getStatus());
        assertJson(response, "{ \"message\": \"boom\", \"upstream\": \"" + displayName + "\" }");
        assertNull(response.getHeader(HttpHeaders.RETRY_AFTER));
    }

    /** Each failure from each upstream, with the display name written out rather than read from the enum. */
    private static Stream<Arguments> everyFailureFromEveryUpstream() {
        return Stream.of(Failure.values()).flatMap(failure -> Stream.of(
                Arguments.of(failure, UpstreamSystem.SALESFORCE, "Salesforce"),
                Arguments.of(failure, UpstreamSystem.NETSUITE, "NetSuite"),
                Arguments.of(failure, UpstreamSystem.WORKDAY, "Workday"),
                Arguments.of(failure, UpstreamSystem.MICROSOFT_DYNAMICS_365, "Microsoft Dynamics 365")));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("Quotation marks, reverse solidi and line breaks in the message are JSON-escaped")
    void messageIsJsonEscaped(Failure failure) throws Exception {
        MockHttpServletResponse response = perform(mockMvc,
                failure.create(UpstreamSystem.SALESFORCE, "say \"hi\" C:\\tmp\nnext", null));

        assertEquals(failure.status, response.getStatus());
        assertJson(response, "{ \"message\": \"say \\\"hi\\\" C:\\\\tmp\\nnext\", \"upstream\": \"Salesforce\" }");
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("escapedMessages")
    @DisplayName("Each JSON escape the handler writes: quote, reverse solidus, short escapes, other C0 controls")
    void messageIsEscapedAsAJsonString(String message, String expectedLiteral) throws Exception {
        MockHttpServletResponse response = perform(mockMvc,
                Failure.UNAVAILABLE.create(UpstreamSystem.SALESFORCE, message, null));

        assertJson(response, "{ \"message\": " + expectedLiteral + ", \"upstream\": \"Salesforce\" }");
    }

    /** A message, then its JSON literal: quote and reverse solidus, short escapes, other C0 controls, the rest. */
    private static Stream<Arguments> escapedMessages() {
        return Stream.of(
                Arguments.of("a\"b\\c", "\"a\\\"b\\\\c\""),
                Arguments.of("line1\nline2\r\tend", "\"line1\\nline2\\r\\tend\""),
                Arguments.of("\b\f\u0001\u001f", "\"\\u0008\\u000c\\u0001\\u001f\""),
                Arguments.of("caf\u00e9 \u2713 \u2028", "\"caf\u00e9 \u2713 \u2028\""),
                Arguments.of("", "\"\""),
                Arguments.of(null, "null"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("A messageless failure renders the message as an unquoted null")
    void nullMessageIsUnquotedNull(Failure failure) throws Exception {
        MockHttpServletResponse response = perform(mockMvc, failure.create(UpstreamSystem.SALESFORCE, null, null));

        assertEquals(failure.status, response.getStatus());
        assertJson(response, "{ \"message\": null, \"upstream\": \"Salesforce\" }");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("The upstream advice answers ahead of a catch-all advice registered before it, which keeps the rest")
    void upstreamAdvicePrecedesACatchAllAdvice(Failure failure) throws Exception {
        MockMvc withCatchAll = withCatchAllAdvice();

        MockHttpServletResponse upstream =
                perform(withCatchAll, failure.create(UpstreamSystem.SALESFORCE, "boom", null));

        assertEquals(failure.status, upstream.getStatus());
        assertJson(upstream, "{ \"message\": \"boom\", \"upstream\": \"Salesforce\" }");

        MockHttpServletResponse other = perform(withCatchAll, new IllegalStateException("other"));

        assertEquals(200, other.getStatus());
        assertEquals("catch-all", other.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Any other exception is left unresolved by the upstream advice, which logs nothing for it")
    void otherExceptionsAreNotHandled() {
        controller.toThrow = new IllegalStateException("other");

        assertThatThrownBy(() -> mockMvc.perform(get("/upstream")))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("other");
        assertEquals(List.of(), errors());
    }

    @Test
    @DisplayName("The upstream advice runs at the highest precedence and applies to every controller")
    void isHighestPrecedenceUnscopedAdvice() {
        Order order = AnnotationUtils.findAnnotation(UpstreamExceptionHandler.class, Order.class);

        assertNotNull(order, "@Order missing");
        assertEquals(Ordered.HIGHEST_PRECEDENCE, order.value());

        ControllerAdvice advice =
                AnnotatedElementUtils.findMergedAnnotation(UpstreamExceptionHandler.class, ControllerAdvice.class);

        assertNotNull(advice, "@RestControllerAdvice or @ControllerAdvice missing");
        assertArrayEquals(new String[0], advice.basePackages(), "basePackages");
        assertArrayEquals(new Class<?>[0], advice.basePackageClasses(), "basePackageClasses");
        assertArrayEquals(new Class<?>[0], advice.assignableTypes(), "assignableTypes");
        assertArrayEquals(new Class<?>[0], advice.annotations(), "annotations");
    }

    @Test
    @DisplayName("The upstream advice declares handlers for exactly the three upstream exceptions")
    void adviceDeclaresExactlyTheThreeUpstreamExceptions() {
        Set<Class<? extends Throwable>> declared = new HashSet<>();
        for (Method method : UpstreamExceptionHandler.class.getDeclaredMethods()) {
            ExceptionHandler handler = method.getAnnotation(ExceptionHandler.class);
            if (handler != null) {
                declared.addAll(Arrays.asList(handler.value()));
            }
        }

        assertEquals(Set.of(UpstreamAuthenticationException.class, UpstreamRateLimitException.class,
                UpstreamUnavailableException.class), declared);
    }

    /**
     * An upstream message that repeats a sentinel and forges a log line, over a sentinel-bearing vendor cause and
     * suppressed exception, yields one ERROR event naming the upstream, the status and the exception's simple class
     * name only, with no throwable attached; the body still carries the message, escaped.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("The ERROR line names the upstream, the status and the exception type, but no vendor text")
    void errorLineNamesUpstreamStatusAndTypeButNoVendorText(Failure failure) throws Exception {
        String message = "rejected " + SENTINEL + "\r\n2026-10-26 ERROR forged entry " + SENTINEL;
        RuntimeException exception = failure.create(UpstreamSystem.NETSUITE, message, "30");
        exception.addSuppressed(new IllegalStateException("suppressed " + SENTINEL + "\r\nforged"));

        MockHttpServletResponse response = perform(mockMvc, exception);

        assertEquals(failure.status, response.getStatus());
        assertJson(response, "{ \"message\": \"rejected " + SENTINEL + "\\r\\n2026-10-26 ERROR forged entry "
                + SENTINEL + "\", \"upstream\": \"NetSuite\" }");
        List<ILoggingEvent> errors = errors();
        assertEquals(1, errors.size(), () -> "ERROR events: " + errors);
        assertEquals("Upstream NetSuite failure (" + failure.status + "): " + exception.getClass().getSimpleName(),
                errors.get(0).getFormattedMessage());
        for (ILoggingEvent event : appender.list) {
            String line = event.getFormattedMessage();
            assertFalse(line.contains(SENTINEL), () -> "vendor text logged: " + line);
            assertFalse(line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0, () -> "line break logged: " + line);
            assertNull(event.getThrowableProxy(), () -> "throwable logged with: " + line);
        }
    }

    /** A second MockMvc with a project catch-all advice registered ahead of the upstream advice. */
    private MockMvc withCatchAllAdvice() {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new CatchAllAdvice(), new UpstreamExceptionHandler())
                .build();
    }

    /** Makes the controller throw {@code toThrow} and returns the response {@code GET /upstream} receives. */
    private MockHttpServletResponse perform(MockMvc mvc, RuntimeException toThrow) throws Exception {
        controller.toThrow = toThrow;
        return mvc.perform(get("/upstream")).andReturn().getResponse();
    }

    /** ERROR events of the upstream advice's logger, in order. */
    private List<ILoggingEvent> errors() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    /** The Content-Type is exactly {@code application/json}, without a charset, and the body is these bytes. */
    private static void assertJson(MockHttpServletResponse response, String expectedBody) {
        assertEquals(JSON.toString(), response.getHeader(HttpHeaders.CONTENT_TYPE));
        assertArrayEquals(expectedBody.getBytes(StandardCharsets.UTF_8), response.getContentAsByteArray(),
                () -> "body was: " + new String(response.getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    /** The three upstream exceptions with the status each is answered with. */
    private enum Failure {

        AUTHENTICATION(502) {
            @Override
            RuntimeException create(UpstreamSystem system, String message, String retryAfter) {
                return new UpstreamAuthenticationException(system, message, vendorCause());
            }
        },
        RATE_LIMIT(429) {
            @Override
            RuntimeException create(UpstreamSystem system, String message, String retryAfter) {
                return new UpstreamRateLimitException(system, message, retryAfter, vendorCause());
            }
        },
        UNAVAILABLE(503) {
            @Override
            RuntimeException create(UpstreamSystem system, String message, String retryAfter) {
                return new UpstreamUnavailableException(system, message, vendorCause());
            }
        };

        final int status;

        Failure(int status) {
            this.status = status;
        }

        /** Creates the exception; {@code retryAfter} is used by the rate limit alone. */
        abstract RuntimeException create(UpstreamSystem system, String message, String retryAfter);

        private static IOException vendorCause() {
            return new IOException("vendor cause " + SENTINEL + "\nforged");
        }
    }

    /** Throws, for any method on {@code /upstream}, the exception the current case set. */
    @RestController
    static class ThrowingController {

        /** The exception the next request raises; every case sets it before it performs a request. */
        volatile RuntimeException toThrow;

        @RequestMapping("/upstream")
        String upstream() {
            throw toThrow;
        }
    }

    /**
     * A project's catch-all branch, declaring {@code Exception}: it claims every exception the upstream advice
     * leaves, and only the upstream advice's higher precedence keeps the three upstream exceptions from it.
     */
    @RestControllerAdvice
    @Order(0)
    static class CatchAllAdvice {

        @ExceptionHandler(Exception.class)
        ResponseEntity<String> handle() {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("catch-all");
        }
    }
}
