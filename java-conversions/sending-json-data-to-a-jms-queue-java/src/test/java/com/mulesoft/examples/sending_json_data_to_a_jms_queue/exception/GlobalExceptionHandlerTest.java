package com.mulesoft.examples.sending_json_data_to_a_jms_queue.exception;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.HttpEncodingAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Tests shared component SC-1: {@link GlobalExceptionHandler} and {@link ErrorResponse}, in two layers that drive
 * the same nested {@link TestController}.
 *
 * <ul>
 *   <li>Unit layer: standalone MockMvc with the advice, checking each handler method, the reported path, the
 *       {@code ErrorResponse} escaping and the advice's ERROR line without a server.</li>
 *   <li>Wire layer: the controller and the advice on Undertow ({@link WireConfig}, random port on IPv4 loopback),
 *       called through the project's ST-1 client, so the raw status line with Undertow's standard reason phrase,
 *       the {@code Content-Type} header, the absent {@code Allow} header and the body bytes are asserted as the
 *       listener writes them, unknown-path dispatch by the real {@code DispatcherServlet} included.</li>
 * </ul>
 *
 * <p>An {@link Error} a handler throws, an {@link OutOfMemoryError} or a {@link StackOverflowError}, reaches the
 * advice inside the dispatcher's {@code ServletException}, whose message names the error's class; both layers assert
 * that the 500 body carries the error's own message instead.
 *
 * <p>The log oracle is a Logback {@link ListAppender} on the advice's logger, attached at {@code ALL} for each test
 * and detached with the logger's level restored afterwards. Failure chains whose message, causes and suppressed
 * exception carry a sentinel and forged line breaks, raised for a forged method on a raw URI, must each yield
 * exactly one ERROR event whose line is the expected one: the escaped method and path, then the chain's types,
 * ending in {@code [circular]} for a loop and {@code ...} beyond ten causes. The event carries no sentinel, no
 * suppressed exception's class, no attached throwable and no unescaped control, separator, format or unpaired
 * surrogate character. Forged methods and paths are logged in exactly their escaped and length-bounded form.
 *
 * <p>Every project with a Spring MVC controller carries this class with only its {@code package} line changed,
 * so it depends on the JDK, JUnit, Spring Boot, Logback (Spring Boot's default logging binding) and the two SC-1
 * classes alone, and reaches ST-1 through {@link WireClient}, which locates the project's
 * {@code support.HttpTestClient} from this class's package.
 * Bodies are compared as exact bytes because the Mule listener's default texts and APIkit's JSON spacing are the
 * contract.
 */
@SpringBootTest(
        classes = GlobalExceptionHandlerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to the address ST-1 connects to, whatever server.address the project sets.
        properties = "server.address=" + GlobalExceptionHandlerTest.LOOPBACK)
class GlobalExceptionHandlerTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    /** Text standing for submitted content, credentials or tokens an exception message can repeat. */
    private static final String SENTINEL = "SENTINEL-3f9b";

    /** The outer message of every log-safety failure: the sentinel, and a line break forging a second entry. */
    private static final String FORGED_MESSAGE =
            "rejected " + SENTINEL + "\r\n2026-10-26 ERROR forged entry " + SENTINEL;

    /** The body every log-safety failure is answered with: its outer message, line break escaped. */
    private static final String FORGED_MESSAGE_BODY =
            "{ \"message\": \"rejected " + SENTINEL + "\\r\\n2026-10-26 ERROR forged entry " + SENTINEL + "\" }";

    /** A request method carrying CR LF; the servlet hands a method it does not know straight to the dispatcher. */
    private static final String FORGED_METHOD = "GET\r\nFORGED";

    /** A raw request URI carrying CR LF and a forged entry. */
    private static final String FORGED_URI = "/test/log-safety/x\r\n2026-10-26 ERROR forged";

    /** The ERROR line's start for {@link #FORGED_METHOD} on {@link #FORGED_URI}, both escaped. */
    private static final String FORGED_LINE_START = "Unhandled exception processing GET\\u000d\\u000aFORGED "
            + "/test/log-safety/x\\u000d\\u000a2026-10-26 ERROR forged: ";

    /** The single frame of the log-safety failures, so the throw site in the expected line is exact. */
    private static final StackTraceElement THROW_SITE = new StackTraceElement("a.b.C", "d", "C.java", 18);

    /** The ERROR line's description of the outer log-safety failure thrown at {@link #THROW_SITE}. */
    private static final String THROWN_AT_SITE = "java.lang.IllegalStateException at a.b.C.d(C.java:18)";

    @LocalServerPort
    int port;

    /** The unit layer's controller, which the log-safety cases tell what to throw. */
    private TestController controller;

    private MockMvc mockMvc;

    /** The SC-1 advice's logger, captured by {@link #appender} for the duration of each test. */
    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        controller = new TestController();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
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
    void thrownNoHandlerFoundExceptionReturnsNoListener404() throws Exception {
        MvcResult result = perform(get("/test/no-handler"));

        assertEquals(404, result.getResponse().getStatus());
        assertContentType(result, MediaType.TEXT_PLAIN);
        assertBody(result, "No listener for endpoint: /test/no-handler");
    }

    @Test
    void thrownNoResourceFoundExceptionReturnsNoListener404() throws Exception {
        MvcResult result = perform(get("/test/no-resource"));

        assertEquals(404, result.getResponse().getStatus());
        assertContentType(result, MediaType.TEXT_PLAIN);
        assertBody(result, "No listener for endpoint: /test/no-resource");
    }

    @Test
    void unmappedPathReturnsNoListener404() throws Exception {
        MvcResult result = perform(get("/test/unmapped"));

        assertEquals(404, result.getResponse().getStatus());
        assertContentType(result, MediaType.TEXT_PLAIN);
        assertBody(result, "No listener for endpoint: /test/unmapped");
    }

    @Test
    void reportedPathExcludesContextPathAndQueryString() throws Exception {
        MvcResult result = perform(get("/app/test/unmapped?lang=es").contextPath("/app"));

        assertEquals(404, result.getResponse().getStatus());
        assertContentType(result, MediaType.TEXT_PLAIN);
        assertBody(result, "No listener for endpoint: /test/unmapped");
    }

    @Test
    void methodOutsideMappingReturns405() throws Exception {
        MvcResult result = perform(post("/test/get-only"));

        assertEquals(405, result.getResponse().getStatus());
        assertContentType(result, MediaType.TEXT_PLAIN);
        assertBody(result, "Method not allowed for endpoint: /test/get-only");
        // The Mule listener's 405 carried no Allow header, so the handler must not add one.
        assertNull(result.getResponse().getHeader(HttpHeaders.ALLOW));
    }

    @Test
    void unhandledExceptionReturns500JsonWithEscapedMessage() throws Exception {
        MvcResult result = perform(get("/test/illegal-state"));

        assertEquals(500, result.getResponse().getStatus());
        assertContentType(result, MediaType.APPLICATION_JSON);
        assertBody(result, "{ \"message\": \"boom \\\"q\\\"\" }");
    }

    @Test
    void unhandledExceptionWithoutMessageRendersNull() throws Exception {
        MvcResult result = perform(get("/test/null-message"));

        assertEquals(500, result.getResponse().getStatus());
        assertContentType(result, MediaType.APPLICATION_JSON);
        assertBody(result, "{ \"message\": null }");
    }

    /**
     * The {@code DispatcherServlet} wraps an {@link Error} in a {@code ServletException} whose message names the
     * error's class; the body carries the error's own message instead, and the ERROR line names the types only.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("escapingErrorCases")
    void errorEscapingTheHandlerReturns500WithItsOwnMessage(String path, String expectedBody, String errorClass)
            throws Exception {
        MvcResult result = perform(get(path));

        assertEquals(500, result.getResponse().getStatus());
        assertContentType(result, MediaType.APPLICATION_JSON);
        assertBody(result, expectedBody);
        ILoggingEvent event = onlyEvent();
        assertEquals(Level.ERROR, event.getLevel());
        assertNull(event.getThrowableProxy(), event::getFormattedMessage);
        String line = event.getFormattedMessage();
        String expectedStart = "Unhandled exception processing GET " + path + ": jakarta.servlet.ServletException at ";
        assertTrue(line.startsWith(expectedStart), () -> "logged: " + line);
        assertTrue(line.endsWith(" <- " + errorClass), () -> "logged: " + line);
    }

    /** Request target, then the body and the class the ERROR line must end with. */
    private static Stream<Arguments> escapingErrorCases() {
        return Stream.of(
                Arguments.of("/test/out-of-memory", "{ \"message\": \"Java heap space\" }",
                        "java.lang.OutOfMemoryError"),
                Arguments.of("/test/stack-overflow", "{ \"message\": null }", "java.lang.StackOverflowError"));
    }

    /**
     * A failure whose message, causes and suppressed exception carry the sentinel and forged line breaks, sent with
     * a forged method and raw URI, yields one ERROR line naming the escaped method and path and the chain's types
     * alone, with no throwable attached; the body still carries the outer message, escaped.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("failureChainCases")
    void failureChainIsLoggedAsTypesOnly(String label, RuntimeException failure, String expectedTypes)
            throws Exception {
        controller.toThrow = failure;

        MvcResult result = performForged(FORGED_METHOD, FORGED_URI);

        assertEquals(500, result.getResponse().getStatus());
        assertContentType(result, MediaType.APPLICATION_JSON);
        assertBody(result, FORGED_MESSAGE_BODY);
        assertOnlySafeErrorLine(FORGED_LINE_START + expectedTypes);
    }

    /** Label, the failure the controller throws, then the types its ERROR line names after the path. */
    private static Stream<Arguments> failureChainCases() {
        IllegalStateException twoCauses = outerFailure(THROW_SITE);
        twoCauses.initCause(new IOException("cause " + SENTINEL + "\nforged",
                new IllegalArgumentException("root cause " + SENTINEL + "\r\nforged")));
        IllegalStateException noStackTrace = outerFailure();
        noStackTrace.initCause(new IOException("cause " + SENTINEL + "\nforged"));
        // The advice names at most ten causes, then marks the rest with "...".
        String tenCauses = " <- java.io.IOException".repeat(10);
        return Stream.of(
                Arguments.of("two causes", twoCauses,
                        THROWN_AT_SITE + " <- java.io.IOException <- java.lang.IllegalArgumentException"),
                Arguments.of("exactly the cause limit", chainOf(10), THROWN_AT_SITE + tenCauses),
                Arguments.of("one cause beyond the limit", chainOf(11), THROWN_AT_SITE + tenCauses + " <- ..."),
                Arguments.of("no stack trace", noStackTrace, "java.lang.IllegalStateException <- java.io.IOException"));
    }

    /**
     * A circular cause chain is cut at the first repeated cause, marked {@code [circular]}. The advice is called
     * directly: the dispatcher's exception resolver walks the cause chain with a guard against self-causation only,
     * so a circular chain never reaches an advice through a dispatch.
     */
    @Test
    void circularCauseChainIsLoggedWithCircularMarker() {
        IllegalStateException failure = outerFailure(THROW_SITE);
        IOException cause = new IOException("cause " + SENTINEL + "\nforged");
        failure.initCause(cause);
        cause.initCause(failure);

        ResponseEntity<String> response = new GlobalExceptionHandler()
                .handleUnhandled(failure, new MockHttpServletRequest(FORGED_METHOD, FORGED_URI));

        assertEquals(500, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertEquals(FORGED_MESSAGE_BODY, response.getBody());
        assertOnlySafeErrorLine(FORGED_LINE_START + THROWN_AT_SITE + " <- java.io.IOException <- [circular]");
    }

    /** A forged request method or raw path is logged in exactly its escaped and bounded form, on one line. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("forgedRequestFieldCases")
    void forgedRequestFieldsAreLoggedEscapedAndBounded(String label, String method, String rawPath,
                                                       String expectedMethod, String expectedPath) throws Exception {
        controller.toThrow = outerFailure(THROW_SITE);

        MvcResult result = performForged(method, rawPath);

        assertEquals(500, result.getResponse().getStatus());
        assertContentType(result, MediaType.APPLICATION_JSON);
        assertBody(result, FORGED_MESSAGE_BODY);
        assertOnlySafeErrorLine(
                "Unhandled exception processing " + expectedMethod + " " + expectedPath + ": " + THROWN_AT_SITE);
    }

    /**
     * Label, the method and raw path sent, then the method and path the ERROR line shows. A logged method holds at
     * most 32 characters and a logged path 256, after escaping; a longer one is cut before the first character or
     * escape that would exceed that, never inside a surrogate pair or an escape, and {@code ...[truncated]} follows.
     */
    private static Stream<Arguments> forgedRequestFieldCases() {
        String base = "/test/log-safety/";
        String pathAtLimit = base + "x".repeat(256 - base.length());
        String pathBeforeEscape = base + "x".repeat(254 - base.length());
        String pathBeforePair = base + "x".repeat(255 - base.length());
        return Stream.of(
                Arguments.of("CR LF", "GET", base + "a\r\nFORGED b", "GET", base + "a\\u000d\\u000aFORGED b"),
                Arguments.of("tab, NUL and DEL", "GET", base + "a\tb\u0000c\u007fd", "GET",
                        base + "a\\u0009b\\u0000c\\u007fd"),
                Arguments.of("next line", "GET", base + "a\u0085b", "GET", base + "a\\u0085b"),
                Arguments.of("line and paragraph separators", "GET", base + "a\u2028b\u2029c", "GET",
                        base + "a\\u2028b\\u2029c"),
                Arguments.of("bidi override, zero-width space and byte order mark", "GET",
                        base + "a\u202eb\u200bc\ufeffd", "GET", base + "a\\u202eb\\u200bc\\ufeffd"),
                Arguments.of("reverse solidus", "GET", base + "a\\u000ab", "GET", base + "a\\u005cu000ab"),
                Arguments.of("unpaired surrogates", "GET", base + "a\ud800b\udc00c", "GET",
                        base + "a\\ud800b\\udc00c"),
                Arguments.of("valid surrogate pair", "GET", base + "a\ud83d\ude00b", "GET", base + "a\ud83d\ude00b"),
                Arguments.of("path at the limit", "GET", pathAtLimit, "GET", pathAtLimit),
                Arguments.of("path over the limit", "GET", pathAtLimit + "yz", "GET", pathAtLimit + "...[truncated]"),
                Arguments.of("escape crossing the path limit", "GET", pathBeforeEscape + "\nz", "GET",
                        pathBeforeEscape + "...[truncated]"),
                Arguments.of("surrogate pair crossing the path limit", "GET", pathBeforePair + "\ud83d\ude00z", "GET",
                        pathBeforePair + "...[truncated]"),
                Arguments.of("method at the limit", "M".repeat(32), base + "m", "M".repeat(32), base + "m"),
                Arguments.of("method over the limit", "M".repeat(40), base + "m", "M".repeat(32) + "...[truncated]",
                        base + "m"));
    }

    @ParameterizedTest
    @MethodSource("errorResponseJsonCases")
    void errorResponseJsonEscapesAndHandlesNull(String message, String expectedJson) {
        assertEquals(expectedJson, ErrorResponse.json(message));
    }

    @Test
    void errorResponseMediaTypeIsApplicationJson() {
        assertEquals(MediaType.APPLICATION_JSON, ErrorResponse.MEDIA_TYPE);
    }

    @ParameterizedTest
    @MethodSource("platformDefaultErrorCases")
    void platformDefaultErrorsReachTheWire(String method, String path, String expectedStatusLine,
                                           String expectedContentType, String expectedBody) {
        WireResponse response = new WireClient(LOOPBACK, port).send(method, path);

        assertEquals(expectedStatusLine, response.statusLine(), response::toString);
        assertEquals(List.of(expectedContentType), response.headers(HttpHeaders.CONTENT_TYPE), response::toString);
        assertArrayEquals(expectedBody.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
        // The Mule listener sent no Allow header on these responses, so neither may the converted one.
        assertNull(response.header(HttpHeaders.ALLOW), response::toString);
    }

    /** Method, request target, then the status line, single {@code Content-Type} value and body expected. */
    private static Stream<Arguments> platformDefaultErrorCases() {
        return Stream.of(
                Arguments.of("GET", "/test/no-handler", "HTTP/1.1 404 Not Found", "text/plain",
                        "No listener for endpoint: /test/no-handler"),
                Arguments.of("GET", "/test/no-resource", "HTTP/1.1 404 Not Found", "text/plain",
                        "No listener for endpoint: /test/no-resource"),
                // No handler maps these targets: the DispatcherServlet's own unknown-path dispatch reaches SC-1.
                Arguments.of("GET", "/test/unmapped", "HTTP/1.1 404 Not Found", "text/plain",
                        "No listener for endpoint: /test/unmapped"),
                Arguments.of("GET", "/test/unmapped?lang=es", "HTTP/1.1 404 Not Found", "text/plain",
                        "No listener for endpoint: /test/unmapped"),
                Arguments.of("POST", "/test/get-only", "HTTP/1.1 405 Method Not Allowed", "text/plain",
                        "Method not allowed for endpoint: /test/get-only"),
                Arguments.of("GET", "/test/illegal-state", "HTTP/1.1 500 Internal Server Error", "application/json",
                        "{ \"message\": \"boom \\\"q\\\"\" }"),
                Arguments.of("GET", "/test/null-message", "HTTP/1.1 500 Internal Server Error", "application/json",
                        "{ \"message\": null }"),
                // An Error reaches SC-1 inside the dispatcher's ServletException; only the error's message is sent.
                Arguments.of("GET", "/test/out-of-memory", "HTTP/1.1 500 Internal Server Error", "application/json",
                        "{ \"message\": \"Java heap space\" }"),
                Arguments.of("GET", "/test/stack-overflow", "HTTP/1.1 500 Internal Server Error", "application/json",
                        "{ \"message\": null }"));
    }

    private static Stream<Arguments> errorResponseJsonCases() {
        return Stream.of(
                Arguments.of("plain", "{ \"message\": \"plain\" }"),
                Arguments.of("a\"b\\c", "{ \"message\": \"a\\\"b\\\\c\" }"),
                Arguments.of("line1\nline2", "{ \"message\": \"line1\\nline2\" }"),
                Arguments.of("\b\f\r\t", "{ \"message\": \"\\b\\f\\r\\t\" }"),
                Arguments.of("\u0001\u001f", "{ \"message\": \"\\u0001\\u001f\" }"),
                Arguments.of("\u2028\u2029", "{ \"message\": \"\\u2028\\u2029\" }"),
                Arguments.of("/teams caf\u00e9", "{ \"message\": \"/teams caf\u00e9\" }"),
                Arguments.of("", "{ \"message\": \"\" }"),
                Arguments.of(null, "{ \"message\": null }"));
    }

    private MvcResult perform(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    /** The one event the SC-1 logger received during the test; any other count fails, listing the events. */
    private ILoggingEvent onlyEvent() {
        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size(), () -> "events: " + events);
        return events.get(0);
    }

    /** Sends {@code method} to the log-safety mapping with the request URI replaced by {@code rawUri} as is. */
    private MvcResult performForged(String method, String rawUri) throws Exception {
        return perform(request(HttpMethod.valueOf(method), "/test/log-safety/x").with(forged -> {
            forged.setRequestURI(rawUri);
            return forged;
        }));
    }

    /**
     * Asserts that the SC-1 logger received exactly one event, at ERROR, whose line is {@code expectedLine}, and that
     * it carries no sentinel, no suppressed exception's class, no attached throwable and no unescaped unsafe
     * character.
     */
    private void assertOnlySafeErrorLine(String expectedLine) {
        ILoggingEvent event = onlyEvent();
        String line = event.getFormattedMessage();
        assertEquals(Level.ERROR, event.getLevel(), line);
        assertEquals(expectedLine, line);
        assertFalse(line.contains(SENTINEL), () -> "exception text logged: " + line);
        assertFalse(line.contains(TimeoutException.class.getSimpleName()), () -> "suppressed exception logged: " + line);
        assertNull(event.getThrowableProxy(), () -> "throwable logged with: " + line);
        assertNoUnsafeCharacter(line);
    }

    /**
     * Fails on the first code point a log line must never carry unescaped: an ISO control character (CR, LF, NEL
     * and the rest of C0, DEL and C1), a line or paragraph separator, a format character or an unpaired surrogate.
     */
    private static void assertNoUnsafeCharacter(String line) {
        line.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            boolean unsafe = Character.isISOControl(codePoint)
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.FORMAT
                    || type == Character.SURROGATE;
            assertFalse(unsafe, () -> String.format("unescaped U+%04X logged: %s", codePoint, line));
        });
    }

    /**
     * An {@link IllegalStateException} with {@link #FORGED_MESSAGE}, {@code trace} as its stack trace, no cause yet,
     * and a suppressed {@link TimeoutException} carrying the sentinel and a forged line break.
     */
    private static IllegalStateException outerFailure(StackTraceElement... trace) {
        IllegalStateException failure = new IllegalStateException(FORGED_MESSAGE);
        failure.setStackTrace(trace);
        failure.addSuppressed(new TimeoutException("suppressed " + SENTINEL + "\r\nforged"));
        return failure;
    }

    /** An {@link #outerFailure} thrown at {@link #THROW_SITE} over {@code causes} nested sentinel-bearing causes. */
    private static IllegalStateException chainOf(int causes) {
        IllegalStateException failure = outerFailure(THROW_SITE);
        Throwable innermost = failure;
        for (int i = 1; i <= causes; i++) {
            IOException cause = new IOException("cause " + i + " " + SENTINEL + "\nforged");
            innermost.initCause(cause);
            innermost = cause;
        }
        return failure;
    }

    private static void assertBody(MvcResult result, String expected) {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), result.getResponse().getContentAsByteArray(),
                () -> "body was: " + new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    /** Compares type and subtype only: the string converter may append a {@code charset} parameter. */
    private static void assertContentType(MvcResult result, MediaType expected) {
        String contentType = result.getResponse().getContentType();
        assertNotNull(contentType, "Content-Type missing");
        assertTrue(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(expected),
                () -> "expected " + expected + " but Content-Type was " + contentType);
    }

    /**
     * Each thrown exception carries exactly the request URI, so the expected body is the same whether the handler
     * reports the request's path or the exception's. The log-safety mapping accepts every method and throws the
     * failure the unit layer sets on its own instance; the wire layer never requests it.
     */
    @RestController
    static class TestController {

        /** The failure the next log-safety request raises; each log-safety case sets it before its request. */
        volatile RuntimeException toThrow;

        @GetMapping("/test/no-handler")
        String noHandler() throws NoHandlerFoundException {
            throw new NoHandlerFoundException("GET", "/test/no-handler", new HttpHeaders());
        }

        @GetMapping("/test/no-resource")
        String noResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/test/no-resource");
        }

        @GetMapping("/test/get-only")
        String getOnly() {
            return "ok";
        }

        @GetMapping("/test/illegal-state")
        String illegalState() {
            throw new IllegalStateException("boom \"q\"");
        }

        @GetMapping("/test/null-message")
        String nullMessage() {
            throw new RuntimeException();
        }

        @GetMapping("/test/out-of-memory")
        String outOfMemory() {
            throw new OutOfMemoryError("Java heap space");
        }

        @GetMapping("/test/stack-overflow")
        String stackOverflow() {
            throw new StackOverflowError();
        }

        @RequestMapping("/test/log-safety/**")
        String logSafety() {
            throw Objects.requireNonNull(toThrow, "no log-safety failure set");
        }
    }

    /**
     * The wire layer's whole context: Spring Boot's servlet web stack on Undertow, {@link TestController} and
     * {@link GlobalExceptionHandler}.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration, so the data, messaging and
     * security starters a project carries add nothing to it; it scans nothing, so no project or APIkit advice can
     * claim an exception before SC-1; and it is not a {@code @SpringBootConfiguration}, so the project's other
     * {@code @SpringBootTest} classes still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({TestController.class, GlobalExceptionHandler.class})
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            ServletWebServerFactoryAutoConfiguration.class,
            EmbeddedWebServerFactoryCustomizerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            HttpEncodingAutoConfiguration.class})
    static class WireConfig {
    }

    /**
     * Package-neutral bridge to the project's ST-1 client {@code <root>.support.HttpTestClient}, where
     * {@code <root>} is this class's package without its last segment.
     *
     * <p>Reflection is what keeps this file portable: each project's ST-1 copy lives in that project's own
     * package, so an import of it would make the copies differ in more than their {@code package} line. Only
     * ST-1's public API is called: the {@code (String host, int port)} constructor and
     * {@code send(String method, String path, Map<String, String> headers, byte[] body)}. An exception ST-1
     * throws, such as its {@code UncheckedIOException} naming the request and the endpoint, propagates unchanged;
     * a missing ST-1 class or member fails with an {@link IllegalStateException} naming it.
     */
    private static final class WireClient {

        private static final Class<?>[] SEND_PARAMETERS = {String.class, String.class, Map.class, byte[].class};

        private final Object client;

        WireClient(String host, int port) {
            String className = clientClassName();
            try {
                client = Class.forName(className).getConstructor(String.class, int.class).newInstance(host, port);
            } catch (InvocationTargetException e) {
                throw unwrap(e);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "ST-1 client " + className + " with a public (String, int) constructor is required: " + e, e);
            }
        }

        /** Sends {@code method path} with no headers beyond ST-1's own and no body. */
        WireResponse send(String method, String path) {
            return new WireResponse(invoke(client, "send", SEND_PARAMETERS, method, path, null, null));
        }

        private static String clientClassName() {
            String testPackage = GlobalExceptionHandlerTest.class.getPackageName();
            int lastDot = testPackage.lastIndexOf('.');
            if (lastDot < 0) {
                throw new IllegalStateException("cannot locate ST-1 from package '" + testPackage
                        + "': expected <root>.exception");
            }
            return testPackage.substring(0, lastDot) + ".support.HttpTestClient";
        }

        /** Calls a public method of an ST-1 object, propagating what the method throws unchanged. */
        static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments) {
            try {
                return target.getClass().getMethod(name, parameterTypes).invoke(target, arguments);
            } catch (InvocationTargetException e) {
                throw unwrap(e);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "ST-1 " + target.getClass().getName() + " has no callable public " + name + ": " + e, e);
            }
        }

        /** Returns the unchecked exception ST-1 threw, or throws the {@link Error} it threw. */
        private static RuntimeException unwrap(InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof RuntimeException runtime) {
                return runtime;
            }
            // ST-1's public API declares no checked exception, so only an incompatible copy reaches this line.
            return new IllegalStateException("ST-1 threw a checked exception: " + cause, cause);
        }
    }

    /** An ST-1 {@code HttpTestClient.Response}, read through its public accessors. */
    private static final class WireResponse {

        private static final Class<?>[] NO_PARAMETERS = {};

        private static final Class<?>[] NAME_PARAMETER = {String.class};

        private final Object response;

        WireResponse(Object response) {
            this.response = Objects.requireNonNull(response, "ST-1 send returned no response");
        }

        /** The raw status line without its terminator, e.g. {@code HTTP/1.1 404 Not Found}. */
        String statusLine() {
            return (String) WireClient.invoke(response, "statusLine", NO_PARAMETERS);
        }

        /** The first value of header {@code name}, or {@code null} when absent. */
        String header(String name) {
            return (String) WireClient.invoke(response, "header", NAME_PARAMETER, name);
        }

        /** Every value of header {@code name} in arrival order. */
        List<?> headers(String name) {
            return (List<?>) WireClient.invoke(response, "headers", NAME_PARAMETER, name);
        }

        /** The body bytes as received. */
        byte[] body() {
            return (byte[]) WireClient.invoke(response, "body", NO_PARAMETERS);
        }

        /** ST-1's rendering of the status line, headers and body, for assertion messages. */
        @Override
        public String toString() {
            return response.toString();
        }
    }
}
