/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test of {@link InputValidationService}, the processors of flow {@code choice-error-handlingFlow1} in
 * {@code implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml}, lines 8-14: the
 * {@code json-to-object-transformer returnClass="java.util.HashMap"}, the {@code InputDataValidator}
 * {@code custom-filter}, the flow variable {@code order}, the email {@code regex-filter} and the success
 * {@code set-payload}.
 *
 * <p>The thrown exceptions are the contract, because they select the branches of the choice exception strategy:
 * {@link NullPointerException} selects "Missing input data", {@link IllegalArgumentException} (including
 * {@link NumberFormatException}) selects "Invalid input data", and neither may be wrapped. A rejection by either
 * filter is not an error: {@link InputValidationService#validate} returns {@link Optional#empty()}.
 *
 * <p>An unreadable body raises neither, and the platform-default 500 returns its message, so that message is a
 * fixed text: bodies carrying a marker show that no request bytes and no Java or Jackson type name reach it, and
 * that the parser location chained as its cause quotes none of the body.
 *
 * <p>The email check must give the answer of the original pattern, {@code ORIGINAL_EMAIL_PATTERN}, on every text:
 * it is compared with that pattern on every short text over one character of each kind, on seeded random texts,
 * and on short instances of the long texts, which it must answer within a bound the original search exceeds.
 *
 * <p>JUnit 5 and Mockito with no Spring context: {@link #realService()} wires the ported validator, so the filter
 * chain runs as the flow ran it, and the mocked {@link InputDataValidator} isolates the service's own decisions.
 */
@ExtendWith(MockitoExtension.class)
class InputValidationServiceTest {

    /** Text placed in request bodies to show whether any of their bytes reach a message. */
    private static final String MARKER = "BLITZY-MARKER-91c4";

    private static final String NOT_AN_OBJECT = "JSON payload is not an object";

    private static final String NOT_VALID_JSON = "Payload is not valid JSON";

    /**
     * The line-13 {@code regex-filter} pattern verbatim, with {@code &amp;} decoded and the two spaces of its last
     * class: the reference the service's email check must agree with. Only short texts reach it, because its
     * unanchored search is quadratic and overflows the stack on a long dotted local part.
     */
    private static final Pattern ORIGINAL_EMAIL_PATTERN = Pattern.compile(
            "[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9  -]*[a-z0-9])?");

    /**
     * One character of each kind the pattern tells apart: in every class ({@code a}, {@code 0}), in the local-part
     * and label classes but unable to start or end a label ({@code -}), the separators ({@code .}, {@code @}), in
     * the local-part class only ({@code !}), in the tail class only (space) and in no class ({@code A}).
     */
    private static final char[] CLASS_ALPHABET = {'a', '0', '-', '.', '@', '!', ' ', 'A'};

    /**
     * Tokens of the random texts: every local-part special, letters, digits, the separators, a space, upper case,
     * the address punctuation outside every class, a non-ASCII letter and a surrogate pair.
     */
    private static final String[] RANDOM_TOKENS = {
            "!", "#", "$", "%", "&", "'", "*", "+", "/", "=", "?", "^", "_", "`", "{", "|", "}", "~", "-",
            "a", "b", "m", "z", "0", "5", "9", ".", "@", " ", "A", "Z",
            "\"", "(", ")", ",", ":", ";", "<", ">", "[", "\\", "]", "\u00e9", "\uD83D\uDE00"};

    /** The characters an address is built from, drawn for half the random tokens so that addresses occur. */
    private static final String[] ADDRESS_TOKENS = {"a", "0", "-", ".", "@"};

    private static final int RANDOM_SAMPLES = 100_000;

    /** Most tokens in one random text: short enough that the original pattern never overflows its stack. */
    private static final int RANDOM_MAX_TOKENS = 40;

    /** Repetitions of a long text's unit, giving at least 1,000,000 characters. */
    private static final int LONG_REPETITIONS = 1_000_000;

    /** Repetitions at which the original pattern still answers at once, to settle each long text's answer. */
    private static final int SHORT_REPETITIONS = 100;

    /** Generous for a linear scan of a few million characters, which takes milliseconds. */
    private static final Duration LONG_TEXT_BOUND = Duration.ofSeconds(5);

    @Mock
    private InputDataValidator inputDataValidator;

    @Test
    void messageJsonBecomesAHashMapEqualToTheOrder() throws IOException {
        byte[] body;
        try (InputStream in = getClass().getResourceAsStream("/message.json")) {
            assertNotNull(in, "message.json is missing from the test classpath");
            body = in.readAllBytes();
        }

        HashMap<String, Object> payload = realService().toPayload(body);

        // The exact class: the error bodies render the map's toString(), so a LinkedHashMap would change them.
        assertEquals(HashMap.class, payload.getClass());
        assertEquals(validMap(), payload);
    }

    @ParameterizedTest(name = "body [{0}]")
    @MethodSource("unreadableBodies")
    void unreadableBodyThrowsUncheckedIOException(String text, String expectedMessage,
            Class<? extends JsonProcessingException> expectedCause) {
        byte[] body = text == null ? null : text.getBytes(StandardCharsets.UTF_8);
        InputValidationService service = realService();

        UncheckedIOException ex = assertThrows(UncheckedIOException.class, () -> service.toPayload(body));
        // Neither choice branch catches it, so it reaches the platform-default 500.
        assertEquals(UncheckedIOException.class, ex.getClass());
        assertEquals(expectedMessage, ex.getMessage());
        assertNotNull(ex.getCause());
        assertEquals(expectedCause, ex.getCause().getClass());
        assertMessageDisclosesNothing(ex.getMessage());
        assertLocationQuotesNoRequestBytes(ex.getCause());
    }

    /** Bodies {@code toPayload} cannot turn into a map, with the fixed message and the parser failure they raise. */
    static Stream<Arguments> unreadableBodies() {
        return Stream.of(
                Arguments.of(null, NOT_AN_OBJECT, MismatchedInputException.class),
                Arguments.of("", NOT_AN_OBJECT, MismatchedInputException.class),
                Arguments.of("[1]", NOT_AN_OBJECT, MismatchedInputException.class),
                Arguments.of("[\"" + MARKER + "\"]", NOT_AN_OBJECT, MismatchedInputException.class),
                Arguments.of("\"" + MARKER + "\"", NOT_AN_OBJECT, MismatchedInputException.class),
                Arguments.of("42", NOT_AN_OBJECT, MismatchedInputException.class),
                Arguments.of("{bad", NOT_VALID_JSON, JsonParseException.class),
                Arguments.of("{\"item name\": \"" + MARKER + "\", \"item units\": ", NOT_VALID_JSON,
                        JsonEOFException.class));
    }

    @Test
    void jsonNullBodyThrowsClassCastException() {
        byte[] body = "null".getBytes(StandardCharsets.UTF_8);
        InputValidationService service = realService();

        ClassCastException ex = assertThrows(ClassCastException.class, () -> service.toPayload(body));
        assertEquals(ClassCastException.class, ex.getClass());
        assertEquals(NOT_AN_OBJECT, ex.getMessage());
        assertNull(ex.getCause());
        assertMessageDisclosesNothing(ex.getMessage());
    }

    // message.json's own address, then an address the regex-filter finds inside surrounding text (find()).
    @ParameterizedTest(name = "email [{0}]")
    @ValueSource(strings = {"aaa@aaa.aa", "Contact: <aaa@aaa.aa>."})
    void acceptedEmailReturnsTheSuccessMessage(String email) {
        HashMap<String, Object> payload = validMap();
        payload.put("email", email);

        Optional<String> result = realService().validate(payload);

        assertEquals(Optional.of(InputValidationService.SUCCESS_MESSAGE), result);
        assertEquals("Input data validation passed.", result.get());
    }

    // A null value is read as Mule's "{NullPayload}"; the pattern has no flags, so upper case does not match.
    @ParameterizedTest(name = "email [{0}]")
    @NullSource
    @ValueSource(strings = {"aaa.aa", "not an email", "AAA@AAA.AA"})
    void emailRejectedByTheRegexFilterReturnsEmpty(String email) {
        HashMap<String, Object> payload = validMap();
        payload.put("email", email);

        assertEquals(Optional.empty(), realService().validate(payload));
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    void everyShortTextOverTheClassAlphabetGetsTheOriginalPatternsAnswer(int length) {
        InputValidationService service = realService();
        HashMap<String, Object> payload = validMap();
        int texts = 1;
        for (int position = 0; position < length; position++) {
            texts *= CLASS_ALPHABET.length;
        }
        char[] text = new char[length];
        int accepted = 0;
        for (int index = 0; index < texts; index++) {
            int rest = index;
            for (int position = 0; position < length; position++) {
                text[position] = CLASS_ALPHABET[rest % CLASS_ALPHABET.length];
                rest /= CLASS_ALPHABET.length;
            }
            if (assertAgreesWithOriginalPattern(service, payload, new String(text))) {
                accepted++;
            }
        }
        // The shortest address, a@a.a, has five characters; the enumeration reaches it from length five on.
        assertEquals(length >= 5, accepted > 0, "accepted " + accepted + " of " + texts);
    }

    @Test
    void seededRandomTextsGetTheOriginalPatternsAnswer() {
        Random random = new Random(20141028L);
        InputValidationService service = realService();
        HashMap<String, Object> payload = validMap();
        StringBuilder text = new StringBuilder();
        int accepted = 0;
        for (int sample = 0; sample < RANDOM_SAMPLES; sample++) {
            text.setLength(0);
            int tokens = random.nextInt(RANDOM_MAX_TOKENS + 1);
            for (int token = 0; token < tokens; token++) {
                String[] pool = random.nextBoolean() ? ADDRESS_TOKENS : RANDOM_TOKENS;
                text.append(pool[random.nextInt(pool.length)]);
            }
            if (assertAgreesWithOriginalPattern(service, payload, text.toString())) {
                accepted++;
            }
        }
        // Both answers occur, so agreement cannot come from always giving one of them.
        String counts = "accepted " + accepted + " of " + RANDOM_SAMPLES;
        assertTrue(accepted > 0 && accepted < RANDOM_SAMPLES, counts);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("longTexts")
    void longTextIsAnsweredWithinTheTimeBound(String shape, IntFunction<String> text, boolean accepted) {
        // The original pattern settles the expected answer on a short text of the same shape.
        assertEquals(accepted, ORIGINAL_EMAIL_PATTERN.matcher(text.apply(SHORT_REPETITIONS)).find(), shape);
        String email = text.apply(LONG_REPETITIONS);
        assertTrue(email.length() >= 1_000_000, () -> shape + " has " + email.length() + " characters");
        HashMap<String, Object> payload = validMap();
        payload.put("email", email);
        InputValidationService service = realService();

        Optional<String> result = assertTimeoutPreemptively(LONG_TEXT_BOUND, () -> service.validate(payload), shape);

        assertEquals(accepted ? Optional.of(InputValidationService.SUCCESS_MESSAGE) : Optional.empty(), result);
    }

    /**
     * Texts of at least 1,000,000 characters, built from {@code n} repetitions of a unit: near misses on which the
     * original search backtracked for hours or overflowed its stack, and addresses after or inside such text.
     */
    static Stream<Arguments> longTexts() {
        return Stream.of(
                longText("a{n}", n -> "a".repeat(n), false),
                longText("a@a{n}", n -> "a@" + "a".repeat(n), false),
                longText("a@(a-){n}", n -> "a@" + "a-".repeat(n), false),
                longText("@{n}", n -> "@".repeat(n), false),
                longText("(a@){n}", n -> "a@".repeat(n), false),
                longText("(a.){n}", n -> "a.".repeat(n), false),
                longText("(a@a-.){n}", n -> "a@a-.".repeat(n), false),
                longText("(a@-a.a){n}", n -> "a@-a.a".repeat(n), false),
                longText("(a@a.-){n}", n -> "a@a.-".repeat(n), false),
                longText("(.@a.a){n}", n -> ".@a.a".repeat(n), false),
                // The original search overflowed its stack on this address.
                longText("(a.){n}a@b.c", n -> "a.".repeat(n) + "a@b.c", true),
                longText("a{n}@a.a", n -> "a".repeat(n) + "@a.a", true),
                longText("a@a{n}.a", n -> "a@" + "a".repeat(n) + ".a", true),
                longText("(a@a-.){n}a@b.c", n -> "a@a-.".repeat(n) + "a@b.c", true));
    }

    /** One long text: its shape as the display name, its builder from the repetition count, its expected answer. */
    private static Arguments longText(String shape, IntFunction<String> text, boolean accepted) {
        return Arguments.of(shape, text, accepted);
    }

    @Test
    void missingEmailThrowsNullPointerException() {
        HashMap<String, Object> payload = validMap();
        payload.remove("email");
        InputValidationService service = realService();

        NullPointerException ex = assertThrows(NullPointerException.class, () -> service.validate(payload));
        assertEquals(NullPointerException.class, ex.getClass());
        assertEquals("email is missing", ex.getMessage());
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({
            "item units, 0, item units is negative",
            "item price per unit, -1, item price per unit is negative"
    })
    void outOfRangeItemValueThrowsIllegalArgumentException(String key, int value, String message) {
        HashMap<String, Object> payload = validMap();
        payload.put(key, value);
        InputValidationService service = realService();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.validate(payload));
        // The exact class: assertThrows also accepts a subclass such as NumberFormatException.
        assertEquals(IllegalArgumentException.class, ex.getClass());
        assertEquals(message, ex.getMessage());
    }

    @Test
    void validatorRejectionReturnsEmpty() {
        HashMap<String, Object> payload = validMap();
        when(inputDataValidator.accept(same(payload))).thenReturn(false);

        // The email is valid, so only the custom filter's rejection empties the result.
        assertEquals(Optional.empty(), new InputValidationService(inputDataValidator).validate(payload));
    }

    @Test
    void validatorReceivesTheSamePayloadInstance() {
        HashMap<String, Object> payload = validMap();
        when(inputDataValidator.accept(same(payload))).thenReturn(true);

        Optional<String> result = new InputValidationService(inputDataValidator).validate(payload);

        assertEquals(Optional.of(InputValidationService.SUCCESS_MESSAGE), result);
        verify(inputDataValidator).accept(same(payload));
        verifyNoMoreInteractions(inputDataValidator);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validatorFailures")
    void validatorExceptionPropagatesUnwrapped(RuntimeException failure) {
        HashMap<String, Object> payload = validMap();
        when(inputDataValidator.accept(same(payload))).thenThrow(failure);
        InputValidationService service = new InputValidationService(inputDataValidator);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.validate(payload));
        assertSame(failure, thrown);
    }

    /** One failure per choice branch, plus the IllegalArgumentException subclass a non-integer value raises. */
    static Stream<RuntimeException> validatorFailures() {
        return Stream.of(
                new NullPointerException("email is missing"),
                new IllegalArgumentException("item units is negative"),
                new NumberFormatException("For input string: \"1.5\""));
    }

    /**
     * Asserts that {@code validate} answers {@code email} as the original filter did: the success message when the
     * original pattern occurs in it, otherwise empty.
     *
     * @return whether the original pattern occurs in {@code email}
     */
    private static boolean assertAgreesWithOriginalPattern(InputValidationService service,
            HashMap<String, Object> payload, String email) {
        boolean found = ORIGINAL_EMAIL_PATTERN.matcher(email).find();
        payload.put("email", email);
        Optional<String> expected = found ? Optional.of(InputValidationService.SUCCESS_MESSAGE) : Optional.empty();
        assertEquals(expected, service.validate(payload), () -> "email [" + email + "]");
        return found;
    }

    /** Asserts that a message the 500 body returns quotes no request bytes and names no Java or Jackson type. */
    private static void assertMessageDisclosesNothing(String message) {
        for (String disclosure : List.of(MARKER, "java.", "HashMap", "Source")) {
            assertFalse(message.contains(disclosure), () -> "message reveals " + disclosure + ": " + message);
        }
    }

    /**
     * Asserts that the location Jackson appends to the chained cause's message quotes none of the body, which holds
     * only with source inclusion disabled. The location alone is checked: for a string root Jackson's own text
     * quotes the string value, whatever the source setting.
     */
    private static void assertLocationQuotesNoRequestBytes(Throwable cause) {
        JsonProcessingException parserFailure = assertInstanceOf(JsonProcessingException.class, cause);
        String location = parserFailure.getMessage().substring(parserFailure.getOriginalMessage().length());
        assertTrue(location.contains("[Source: "), () -> "cause carries a location: " + location);
        assertFalse(location.contains(MARKER), () -> "location quotes the body: " + location);
        assertFalse(location.contains("(byte[])"), () -> "location quotes the body: " + location);
    }

    /** The service wired with the ported validator, as Spring wires it. */
    private static InputValidationService realService() {
        return new InputValidationService(new InputDataValidator());
    }

    /**
     * The original's {@code message.json} as {@code json-to-object-transformer} produced it: a new map on every
     * call, so each test mutates its own copy.
     */
    private static HashMap<String, Object> validMap() {
        HashMap<String, Object> order = new HashMap<>();
        order.put("email", "aaa@aaa.aa");
        order.put("item name", "aa");
        order.put("item units", 10);
        order.put("item price per unit", 1);
        order.put("membership", "free");
        return order;
    }
}
