/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.addition_using_javascript_transformer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests of {@link AdditionService}, the flow {@code javascript-calculatorFlow1}
 * (addition-using-javascript-transformer/src/main/app/javascript-calculator.xml:6-15): the result types of
 * {@link AdditionService#sum(String)}, the response and log line of {@link AdditionService#handle}, and the
 * conversions {@link AdditionService#render(Object)} and {@link AdditionService#jsToString(Object)}.
 *
 * <p>The corpus of Rhino 1.7R4 literal forms and failures lives in {@link AdditionServiceLiteralParityTest} and is
 * not repeated here: primitive and empty roots, duplicate keys, comments, signs, empty, blank and missing bodies,
 * charset decoding, and the conversion of Java lists and maps. A string root such as {@code "ab"} sums to
 * {@code 0.0} there, not to the ES5 {@code "0ab"}, because the original's engine, Rhino 1.7R4, enumerates no
 * string indices in {@code for-in}.
 */
class AdditionServiceTest {

    /** The body the original test posts (AdditionUsingJavascriptTransformerIT.MESSAGE). */
    private static final String ORIGINAL_MESSAGE = "{ \"a\" : 1, \"b\": 2 }";

    private final AdditionService service = new AdditionService();

    /** Bodies whose values all convert to numbers, with the sum and the response that renders it. */
    static Stream<Arguments> numericSums() {
        return Stream.of(
                arguments(ORIGINAL_MESSAGE, 3.0d, "Sum is: 3.0."),
                arguments("{ \"a\": 0.1, \"b\": 0.2 }", 0.30000000000000004d, "Sum is: 0.30000000000000004."),
                arguments("{ \"a\": -5, \"b\": 2 }", -3.0d, "Sum is: -3.0."),
                arguments("{ \"a\": 1e2, \"b\": 1 }", 101.0d, "Sum is: 101.0."),
                // MEL renders the final Double with Double.toString, not with JavaScript's "1e+21".
                arguments("{ \"a\": 1e21 }", 1e21d, "Sum is: 1.0E21."),
                arguments("{ \"a\": Infinity }", Double.POSITIVE_INFINITY, "Sum is: Infinity."),
                arguments("{ \"a\": NaN }", Double.NaN, "Sum is: NaN."),
                // ToNumber: true is 1, false and null are 0.
                arguments("{ \"a\": true, \"b\": false, \"c\": null, \"d\": 2 }", 3.0d, "Sum is: 3.0."),
                // An unquoted key, a single-quoted key and a trailing comma are valid JavaScript object syntax.
                arguments("{a: 1, 'b': 2,}", 3.0d, "Sum is: 3.0.")
        );
    }

    /** Bodies holding a string value, with the concatenation the sum becomes from that value on. */
    static Stream<Arguments> concatenatedSums() {
        return Stream.of(
                arguments("{ \"a\": 1, \"b\": \"x\" }", "1x"),
                arguments("{ \"a\": \"x\", \"b\": 1 }", "0x1"),
                arguments("{ \"a\": 1.5, \"b\": \"x\" }", "1.5x"),
                // The running number joins the string through JavaScript ToString, not Double.toString.
                arguments("{ \"a\": 1e21, \"b\": \"x\" }", "1e+21x")
        );
    }

    /** Values with the string ES5 9.8 and 9.8.1 give them. */
    static Stream<Arguments> javaScriptStrings() {
        return Stream.of(
                arguments(0.0d, "0"),
                arguments(-0.0d, "0"),
                arguments(3, "3"),
                arguments(0.5d, "0.5"),
                arguments(-1.5d, "-1.5"),
                arguments(123.456d, "123.456"),
                arguments(1e-6d, "0.000001"),
                arguments(1e-7d, "1e-7"),
                arguments(1e20d, "100000000000000000000"),
                arguments(1.5e21d, "1.5e+21"),
                arguments(-1.5e21d, "-1.5e+21"),
                // Shortest round-trip digits, where JDK 17's Double.toString gives 1.15292150460684698E18.
                arguments(Math.pow(2, 60), "1152921504606847000"),
                arguments(Double.MIN_VALUE, "5e-324"),
                arguments(Double.MAX_VALUE, "1.7976931348623157e+308"),
                arguments(Double.POSITIVE_INFINITY, "Infinity"),
                arguments(Double.NEGATIVE_INFINITY, "-Infinity"),
                arguments(Double.NaN, "NaN"),
                arguments(true, "true"),
                arguments(false, "false"),
                arguments(null, "null"),
                arguments("x", "x"),
                arguments(Map.of(1, 2), "[object Object]")
        );
    }

    /** Script results with the text MEL's {@code #[payload]} renders them as. */
    static Stream<Arguments> renderedResults() {
        return Stream.of(
                arguments(3.0d, "3.0"),
                arguments(1e21d, "1.0E21"),
                arguments(Double.NaN, "NaN"),
                arguments(Double.NEGATIVE_INFINITY, "-Infinity"),
                arguments("1x", "1x")
        );
    }

    /** Bodies Rhino rejects as invalid syntax: a truncated object and a token after the literal. */
    static Stream<String> malformedBodies() {
        return Stream.of("{ \"a\": ", "{ \"a\": 1 } x");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("numericSums")
    void sumsNumbersToADoubleRenderedWithDoubleToString(String body, double expectedSum, String expectedResponse) {
        Object sum = service.sum(body);

        assertInstanceOf(Double.class, sum);
        assertEquals(expectedSum, (Double) sum);
        assertEquals(expectedResponse, handleUtf8(body));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("concatenatedSums")
    void concatenatesOnceAStringIsAdded(String body, String expectedSum) {
        Object sum = service.sum(body);

        assertInstanceOf(String.class, sum);
        assertEquals(expectedSum, sum);
        assertEquals("Sum is: " + expectedSum + ".", handleUtf8(body));
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @MethodSource("javaScriptStrings")
    void convertsValuesWithJavaScriptToString(Object value, String expected) {
        assertEquals(expected, AdditionService.jsToString(value));
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @MethodSource("renderedResults")
    void rendersTheResultAsMelDoes(Object result, String expected) {
        assertEquals(expected, AdditionService.render(result));
    }

    @Test
    void rejectsAMissingResult() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AdditionService.render(null));

        assertEquals("Unsupported script result type: null", e.getMessage());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("malformedBodies")
    void rejectsBodiesThatAreNotValidJavaScript(String body) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> handleUtf8(body));

        assertTrue(e.getMessage().startsWith("SyntaxError: "), e.getMessage());
    }

    @Test
    void rejectsAMissingText() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.sum(null));

        assertEquals(AdditionService.NO_CONTENT, e.getMessage());
    }

    @Test
    void logsTheSumOnceAtInfoWithoutTheResponsePeriod() {
        String[] response = new String[1];

        List<ILoggingEvent> events = captureServiceLog(() -> response[0] = handleUtf8(ORIGINAL_MESSAGE));

        assertEquals("Sum is: 3.0.", response[0]);
        assertEquals(1, events.size(), events::toString);
        assertEquals(Level.INFO, events.get(0).getLevel());
        assertEquals("Sum is: 3.0", events.get(0).getFormattedMessage());
    }

    @Test
    void logsNothingWhenTheScriptFails() {
        // The flow's logger follows the script, so a failing script ends the flow before it.
        List<ILoggingEvent> events = captureServiceLog(
                () -> assertThrows(IllegalArgumentException.class, () -> handleUtf8("{ \"a\": ")));

        assertEquals(List.of(), events);
    }

    private String handleUtf8(String json) {
        return service.handle(json.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /**
     * Runs {@code action} and returns what {@link AdditionService} logged meanwhile. The logger's own level is set to
     * INFO for the run and restored after it, so the result does not depend on a logging configuration a Spring
     * context in the same JVM may have installed.
     */
    private static List<ILoggingEvent> captureServiceLog(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(AdditionService.class);
        Level savedLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.setLevel(savedLevel);
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
