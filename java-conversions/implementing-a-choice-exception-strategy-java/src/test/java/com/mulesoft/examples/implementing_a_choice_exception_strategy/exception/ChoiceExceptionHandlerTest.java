/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.exception;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.controller.ValidationController;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.service.InputDataValidator;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.service.InputValidationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Tests {@link ChoiceExceptionHandler}, the {@code choice-exception-strategy} of flow
 * {@code choice-error-handlingFlow1}
 * ({@code implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml:15-47}), through standalone
 * MockMvc over the real {@link ValidationController} and a mocked {@link InputValidationService} that throws what the
 * validator throws.
 *
 * <p>Covered: the "Catch Invalid Data" branch for an {@link IllegalArgumentException} and a
 * {@link NumberFormatException}, the "Catch Missing Data" branch for a {@link NullPointerException} with the original
 * IT's fragments, an exception neither branch names reaching the SC-1 500, the four constant scripts, and the ERROR
 * line of each branch, which a Logback {@link ListAppender} on the handler's logger proves free of exception
 * messages, cause messages, payload values and throwables while the response body is unchanged, and whose exact
 * form, throw site, cause chain with its cycle and depth limits, escaping and truncation, it checks.
 *
 * <p>Status, Content-Type and body bytes are asserted here. The reason phrases {@code Invalid input data} and
 * {@code Missing input data} are asserted on the wire by the controller and ported tests through ST-1, because
 * MockMvc has no Undertow exchange for {@code ReasonPhrase} to write to.
 *
 * <p>The payloads are {@link HashMap}s with the entries of {@code message.json} and {@code message_wrong.json},
 * the type the original {@code json-to-object-transformer} produced, so the bodies render the map's
 * {@code toString()} as the original did.
 */
@ExtendWith(MockitoExtension.class)
class ChoiceExceptionHandlerTest {

    private static final String TEXT_PLAIN_UTF8 = "text/plain;charset=UTF-8";

    private static final String INVALID_DATA_BRANCH = "Catch Invalid Data";

    private static final String MISSING_DATA_BRANCH = "Catch Missing Data";

    /** Texts standing for sensitive caller values an exception message, a cause or the payload can carry. */
    private static final String SENTINEL = "pw-" + "SENTINEL-" + UUID.randomUUID();

    private static final String SENTINEL2 = "pw-" + "SENTINEL-" + UUID.randomUUID();

    private static final String PAYLOAD_SENTINEL = "pw-" + "SENTINEL-" + UUID.randomUUID();

    @Mock
    private InputValidationService inputValidationService;

    private MockMvc mockMvc;

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        // Registered on purpose in reverse precedence order: Spring sorts advices by @Order, so only the handler's
        // @Order(0) lets its branches win over the SC-1 catch-all Exception method. A precedence defect shows up
        // here as a 500 and must be fixed in the advice, never by reordering this registration.
        mockMvc = MockMvcBuilders.standaloneSetup(new ValidationController(inputValidationService))
                .setControllerAdvice(new GlobalExceptionHandler(), new ChoiceExceptionHandler())
                .build();
        logger = (Logger) LoggerFactory.getLogger(ChoiceExceptionHandler.class);
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
    void illegalArgumentExceptionAnswersInvalidInputData() throws Exception {
        HashMap<String, Object> payload = order();
        byte[] body = resource("/message.json");
        when(inputValidationService.toPayload(aryEq(body))).thenReturn(payload);
        when(inputValidationService.validate(same(payload)))
                .thenThrow(new IllegalArgumentException("item units is negative"));

        MockHttpServletResponse response = perform(body);

        assertBranchResponse(response, "Invalid input data: " + payload);
    }

    @Test
    void numberFormatExceptionAnswersInvalidInputData() throws Exception {
        HashMap<String, Object> payload = order();
        byte[] body = resource("/message.json");
        when(inputValidationService.toPayload(aryEq(body))).thenReturn(payload);
        when(inputValidationService.validate(same(payload)))
                .thenThrow(new NumberFormatException("For input string: \"ten\""));

        MockHttpServletResponse response = perform(body);

        assertBranchResponse(response, "Invalid input data: " + payload);
    }

    @Test
    void nullPointerExceptionAnswersMissingInputData() throws Exception {
        HashMap<String, Object> payload = orderWithoutEmail();
        byte[] body = resource("/message_wrong.json");
        when(inputValidationService.toPayload(aryEq(body))).thenReturn(payload);
        when(inputValidationService.validate(same(payload))).thenThrow(new NullPointerException("email is missing"));

        MockHttpServletResponse response = perform(body);

        assertBranchResponse(response, "Missing input data: " + payload);
        // The original IT's assertions (ImplementingChoiceExceptionStrategyIT.java:65-73), with its contains semantics.
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        for (String fragment : List.of("Missing input data:", "item price per unit=1", "membership=free",
                "item name=aa", "item units=10")) {
            assertTrue(text.contains(fragment), () -> "missing [" + fragment + "] in: " + text);
        }
    }

    @Test
    void unlistedExceptionReachesGlobalHandler() throws Exception {
        byte[] body = resource("/message.json");
        when(inputValidationService.toPayload(aryEq(body))).thenThrow(new ClassCastException("boom"));

        MockHttpServletResponse response = perform(body);

        assertEquals(500, response.getStatus());
        String contentType = response.getContentType();
        assertNotNull(contentType, "Content-Type missing");
        // Type and subtype only: the string converter may append a charset parameter.
        assertTrue(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(MediaType.APPLICATION_JSON),
                () -> "Content-Type was " + contentType);
        assertBody(response, "{ \"message\": \"boom\" }");
        verify(inputValidationService, never()).validate(any());
        assertTrue(appender.list.isEmpty(), () -> "choice branch logged: " + appender.list);
    }

    @Test
    void constantsMatchTheOriginalScripts() {
        // choice-error-handling.xml lines 20, 24 ("Catch Invalid Data") and 35, 39 ("Catch Missing Data").
        assertEquals("400", ChoiceExceptionHandler.INVALID_DATA_STATUS_CODE);
        assertEquals("Invalid input data", ChoiceExceptionHandler.INVALID_DATA_REASON);
        assertEquals("400", ChoiceExceptionHandler.MISSING_DATA_STATUS_CODE);
        assertEquals("Missing input data", ChoiceExceptionHandler.MISSING_DATA_REASON);
    }

    /**
     * An exception whose message, cause message and payload carry sentinels yields one ERROR event that starts with
     * the branch name and names the exception's class, with no sentinel and no throwable attached, while the
     * response is the branch's unchanged 400 with the payload in its body, as the original rendered it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sensitiveFailures")
    void errorLineOmitsMessagesCausesAndPayload(String description, HashMap<String, Object> payload,
                                                RuntimeException failure, String branch, String reason)
            throws Exception {
        byte[] body = resource("/message.json");
        when(inputValidationService.toPayload(aryEq(body))).thenReturn(payload);
        // thenAnswer, not thenThrow, which would refill the stack trace: the validator's own failure keeps its frame.
        when(inputValidationService.validate(same(payload))).thenAnswer(invocation -> {
            throw failure;
        });

        MockHttpServletResponse response = perform(body);

        assertBranchResponse(response, reason + ": " + payload);
        List<ILoggingEvent> errors = errors();
        assertEquals(1, errors.size(), () -> "ERROR events: " + appender.list);
        String line = errors.get(0).getFormattedMessage();
        assertTrue(line.startsWith(branch + ": "), () -> "line was: " + line);
        assertTrue(line.contains(failure.getClass().getName()), () -> "line was: " + line);
        for (ILoggingEvent event : appender.list) {
            String logged = event.getFormattedMessage();
            for (String sentinel : List.of(SENTINEL, SENTINEL2, PAYLOAD_SENTINEL)) {
                assertFalse(logged.contains(sentinel), () -> "sensitive text logged: " + logged);
            }
            assertNull(event.getThrowableProxy(), () -> "throwable logged with: " + logged);
        }
    }

    /** Description, payload, the exception {@code validate} throws, then the expected branch name and reason. */
    static Stream<Arguments> sensitiveFailures() {
        HashMap<String, Object> nonIntegerUnits = order();
        nonIntegerUnits.put("item units", SENTINEL);
        HashMap<String, Object> sensitiveName = order();
        sensitiveName.put("item name", PAYLOAD_SENTINEL);
        HashMap<String, Object> sensitiveMembership = orderWithoutEmail();
        sensitiveMembership.put("membership", PAYLOAD_SENTINEL);
        NullPointerException missing = new NullPointerException(SENTINEL);
        missing.initCause(new IllegalStateException("nested " + SENTINEL2));
        return Stream.of(
                Arguments.of("malformed numeric value", nonIntegerUnits,
                        new NumberFormatException("For input string: \"" + SENTINEL + "\""),
                        INVALID_DATA_BRANCH, ChoiceExceptionHandler.INVALID_DATA_REASON),
                Arguments.of("validator's own NumberFormatException", nonIntegerUnits,
                        validatorFailure(nonIntegerUnits),
                        INVALID_DATA_BRANCH, ChoiceExceptionHandler.INVALID_DATA_REASON),
                Arguments.of("nested cause", sensitiveName,
                        new IllegalArgumentException("x " + SENTINEL, new RuntimeException("nested " + SENTINEL2)),
                        INVALID_DATA_BRANCH, ChoiceExceptionHandler.INVALID_DATA_REASON),
                Arguments.of("missing data with cause", sensitiveMembership, missing,
                        MISSING_DATA_BRANCH, ChoiceExceptionHandler.MISSING_DATA_REASON));
    }

    /**
     * The ERROR line is the branch name, the exception's class and throw-site frame, then each cause's class: an
     * empty stack trace adds no frame, a native or file-less frame is written as {@link StackTraceElement} writes
     * it, a repeated cause ends the chain with {@code [circular]}, more than ten causes end it with {@code ...},
     * and names are escaped and bounded.
     *
     * <p>The branch methods are called directly: Spring's handler lookup walks the cause chain recursively and
     * overflows on a circular one before any advice runs, and Mockito's {@code thenThrow} would refill the stack
     * traces these cases set.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("describedFailures")
    void errorLineNamesTypeThrowSiteAndCauseTypes(String description, RuntimeException failure, String expectedLine) {
        HashMap<String, Object> payload = order();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ChoiceExceptionHandler.PAYLOAD_ATTRIBUTE, payload);
        ChoiceExceptionHandler handler = new ChoiceExceptionHandler();

        ResponseEntity<String> response = failure instanceof NullPointerException missing
                ? handler.handleMissingInputData(missing, request)
                : handler.handleInvalidInputData((IllegalArgumentException) failure, request);

        String reason = failure instanceof NullPointerException
                ? ChoiceExceptionHandler.MISSING_DATA_REASON
                : ChoiceExceptionHandler.INVALID_DATA_REASON;
        assertEquals(400, response.getStatusCode().value());
        assertEquals(reason + ": " + payload, response.getBody());
        List<ILoggingEvent> errors = errors();
        assertEquals(1, errors.size(), () -> "ERROR events: " + appender.list);
        assertEquals(expectedLine, errors.get(0).getFormattedMessage());
        assertNull(errors.get(0).getThrowableProxy());
    }

    /** Description, the exception the branch handles, then the exact ERROR line expected. */
    static Stream<Arguments> describedFailures() {
        IllegalArgumentException withFrame = withTrace(new IllegalArgumentException("item units is negative",
                withTrace(new IllegalStateException("inner"))),
                new StackTraceElement("org.example.Validator", "accept", "Validator.java", 32));

        NullPointerException circular = withTrace(new NullPointerException("email is missing"));
        IllegalStateException middle = withTrace(new IllegalStateException("middle", circular));
        circular.initCause(middle);

        NullPointerException tenCauses = withTrace(new NullPointerException("ten"));
        Throwable tail = tenCauses;
        for (int i = 0; i < 10; i++) {
            IllegalStateException next = withTrace(new IllegalStateException("cause " + i));
            tail.initCause(next);
            tail = next;
        }
        NullPointerException elevenCauses = withTrace(new NullPointerException("eleven"));
        tail = elevenCauses;
        for (int i = 0; i < 11; i++) {
            IllegalStateException next = withTrace(new IllegalStateException("cause " + i));
            tail.initCause(next);
            tail = next;
        }
        String tenNames = " <- java.lang.IllegalStateException".repeat(10);

        String longMethod = "m".repeat(300);
        return Stream.of(
                Arguments.of("frame and cause", withFrame, "Catch Invalid Data: java.lang.IllegalArgumentException"
                        + " at org.example.Validator.accept(Validator.java:32) <- java.lang.IllegalStateException"),
                Arguments.of("empty stack trace", withTrace(new NullPointerException("email is missing")),
                        "Catch Missing Data: java.lang.NullPointerException"),
                Arguments.of("native frame", withTrace(new IllegalArgumentException("x"),
                                new StackTraceElement("org.example.Native", "call", "Native.java", -2)),
                        "Catch Invalid Data: java.lang.IllegalArgumentException"
                                + " at org.example.Native.call(Native Method)"),
                Arguments.of("frame without file", withTrace(new IllegalArgumentException("x"),
                                new StackTraceElement("org.example.Generated", "run", null, 7)),
                        "Catch Invalid Data: java.lang.IllegalArgumentException"
                                + " at org.example.Generated.run(Unknown Source)"),
                Arguments.of("frame without line", withTrace(new IllegalArgumentException("x"),
                                new StackTraceElement("org.example.Lined", "run", "Lined.java", -1)),
                        "Catch Invalid Data: java.lang.IllegalArgumentException"
                                + " at org.example.Lined.run(Lined.java)"),
                Arguments.of("circular causes", circular, "Catch Missing Data: java.lang.NullPointerException"
                        + " <- java.lang.IllegalStateException <- [circular]"),
                Arguments.of("ten causes", tenCauses, "Catch Missing Data: java.lang.NullPointerException" + tenNames),
                Arguments.of("eleven causes", elevenCauses,
                        "Catch Missing Data: java.lang.NullPointerException" + tenNames + " <- ..."),
                Arguments.of("escaped names", withTrace(new IllegalArgumentException("x"),
                                new StackTraceElement("a\nb\\c\u202Ed\uD800", "m\r\u2028", "F\u0000.java", 1)),
                        "Catch Invalid Data: java.lang.IllegalArgumentException"
                                + " at a\\u000ab\\u005cc\\u202ed\\ud800.m\\u000d\\u2028(F\\u0000.java:1)"),
                Arguments.of("overlong name", withTrace(new IllegalArgumentException("x"),
                                new StackTraceElement("org.example.Long", longMethod, "Long.java", 3)),
                        "Catch Invalid Data: java.lang.IllegalArgumentException at org.example.Long."
                                + "m".repeat(256) + "...[truncated](Long.java:3)"));
    }

    /** Replaces the stack trace of {@code failure} with {@code frames}, none meaning an empty trace. */
    private static <T extends Throwable> T withTrace(T failure, StackTraceElement... frames) {
        failure.setStackTrace(frames);
        return failure;
    }

    /** The exception the ported validator raises for {@code payload}, which must make it fail. */
    private static RuntimeException validatorFailure(HashMap<String, Object> payload) {
        try {
            new InputDataValidator().accept(payload);
        } catch (RuntimeException e) {
            return e;
        }
        return fail("the validator accepted " + payload);
    }

    /** The entries of {@code message.json}. */
    private static HashMap<String, Object> order() {
        HashMap<String, Object> payload = orderWithoutEmail();
        payload.put("email", "aaa@aaa.aa");
        return payload;
    }

    /** The entries of {@code message_wrong.json}: {@code message.json} without {@code email}. */
    private static HashMap<String, Object> orderWithoutEmail() {
        HashMap<String, Object> payload = new HashMap<>();
        payload.put("item name", "aa");
        payload.put("item units", 10);
        payload.put("item price per unit", 1);
        payload.put("membership", "free");
        return payload;
    }

    private MockHttpServletResponse perform(byte[] body) throws Exception {
        return mockMvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn()
                .getResponse();
    }

    private List<ILoggingEvent> errors() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = ChoiceExceptionHandlerTest.class.getResourceAsStream(name)) {
            assertNotNull(in, () -> name + " is missing from the test classpath");
            return in.readAllBytes();
        }
    }

    /** A branch's 400 with {@code text/plain;charset=UTF-8} and exactly {@code expectedBody}. */
    private static void assertBranchResponse(MockHttpServletResponse response, String expectedBody) {
        assertEquals(400, response.getStatus());
        assertEquals(TEXT_PLAIN_UTF8, response.getContentType());
        assertBody(response, expectedBody);
    }

    private static void assertBody(MockHttpServletResponse response, String expected) {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), response.getContentAsByteArray(),
                () -> "body was: " + new String(response.getContentAsByteArray(), StandardCharsets.UTF_8));
    }
}
