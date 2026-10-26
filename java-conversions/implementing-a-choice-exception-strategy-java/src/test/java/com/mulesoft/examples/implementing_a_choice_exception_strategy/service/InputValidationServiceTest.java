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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
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
 * <p>JUnit 5 and Mockito with no Spring context: {@link #realService()} wires the ported validator, so the filter
 * chain runs as the flow ran it, and the mocked {@link InputDataValidator} isolates the service's own decisions.
 */
@ExtendWith(MockitoExtension.class)
class InputValidationServiceTest {

    /** Text placed in request bodies to show whether any of their bytes reach a message. */
    private static final String MARKER = "BLITZY-MARKER-91c4";

    private static final String NOT_AN_OBJECT = "JSON payload is not an object";

    private static final String NOT_VALID_JSON = "Payload is not valid JSON";

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
