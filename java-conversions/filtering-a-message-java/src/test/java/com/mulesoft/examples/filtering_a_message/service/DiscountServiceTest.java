/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.filtering_a_message.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Unit test of {@link DiscountService}, the processors of flow {@code filteringFlow1} in
 * {@code filtering-a-message/src/main/app/filtering.xml}, lines 9-17: the {@code and-filter} on the payload type and
 * the {@code post} method, the {@code json-to-object-transformer returnClass="java.util.HashMap"}, the
 * {@code FreeMembershipDiscountFilter} {@code custom-filter} and the granted {@code set-payload}.
 *
 * <p>A rejection by either filter is not an error: neither declares {@code throwOnUnaccepted}, so the service returns
 * {@link Optional#empty()}. The flow has no exception strategy, so a body the transformer cannot read and a payload
 * the custom filter fails on reach the platform 500, each with a fixed message and the original failure as its cause.
 *
 * <p>JUnit 5 and Mockito with no Spring context: the service reads the body with its own Jackson mapper as production
 * does, and the mocked {@link FreeMembershipDiscountFilter} isolates the service's own decisions; the rule itself is
 * covered by {@link FreeMembershipDiscountFilterTest}, and the leak checks run the real filter. Strict stubs: a test
 * stubs the filter only when its input reaches it, and every other test asserts that the filter is never called.
 */
@ExtendWith(MockitoExtension.class)
class DiscountServiceTest {

    @Mock
    private FreeMembershipDiscountFilter freeMembershipDiscountFilter;

    @Captor
    private ArgumentCaptor<Map<String, Object>> payloadCaptor;

    private DiscountService service;

    @BeforeEach
    void createService() {
        service = new DiscountService(freeMembershipDiscountFilter);
    }

    // The and-filter: a null or empty body carries no request stream, and only post passes the method filter.
    @ParameterizedTest(name = "{0} body [{1}]")
    @CsvSource(delimiter = '|', nullValues = "NULL", textBlock = """
            POST   | NULL
            POST   | ''
            post   | ''
            GET    | '{"purchases": 2000, "months": 12, "membership": "free"}'
            PUT    | '{"purchases": 2000, "months": 12, "membership": "free"}'
            DELETE | '{"purchases": 2000, "months": 12, "membership": "free"}'
            get    | '{"purchases": 2000, "months": 12, "membership": "free"}'
            POSTS  | '{"purchases": 2000, "months": 12, "membership": "free"}'
            """)
    void andFilterRejectionReturnsEmptyWithoutParsing(String httpMethod, String body) {
        assertEquals(Optional.empty(), service.applyDiscountFilters(httpMethod, body));
        verifyNoInteractions(freeMembershipDiscountFilter);
    }

    /**
     * The payload-type filter rejects the parameter map the listener made of a form-urlencoded body, even one whose
     * fields would earn the discount or whose single name is the JSON text curl posts by default.
     */
    @ParameterizedTest(name = "map {0}")
    @MethodSource("formParameterPayloads")
    void formParameterMapIsRejectedByThePayloadTypeFilter(Map<String, String> payload) {
        assertEquals(Optional.empty(), service.applyDiscountFilters("POST", payload));
        verifyNoInteractions(freeMembershipDiscountFilter);
    }

    static Stream<Map<String, String>> formParameterPayloads() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("purchases", "2000");
        fields.put("months", "12");
        fields.put("membership", "free");
        return Stream.of(
                fields,
                Collections.singletonMap("{\"purchases\": 2000, \"months\": 12, \"membership\": \"free\"}", null),
                Map.of());
    }

    // The method filter is case-insensitive; message.json is the payload the original test was granted a discount for.
    @ParameterizedTest(name = "method {0}")
    @ValueSource(strings = {"POST", "post", "Post"})
    void grantedWhenBothFiltersAcceptAndTheFilterReceivesAHashMap(String httpMethod) throws IOException {
        when(freeMembershipDiscountFilter.accept(any())).thenReturn(true);

        Optional<String> result = service.applyDiscountFilters(httpMethod, resource("/message.json"));

        assertEquals(Optional.of(DiscountService.GRANTED_MESSAGE), result);
        assertEquals("the discount was granted.", result.get());
        verify(freeMembershipDiscountFilter).accept(payloadCaptor.capture());
        verifyNoMoreInteractions(freeMembershipDiscountFilter);
        Map<String, Object> payload = payloadCaptor.getValue();
        // The exact class the transformer's returnClass names: its toString() order reaches callers.
        assertEquals(HashMap.class, payload.getClass());
        assertEquals(Map.of("purchases", 2000, "months", 12, "membership", "free"), payload);
    }

    @Test
    void customFilterRejectionReturnsEmpty() throws IOException {
        when(freeMembershipDiscountFilter.accept(any())).thenReturn(false);

        // message1.json is the payload the original test expected an empty reply for.
        assertEquals(Optional.empty(), service.applyDiscountFilters("POST", resource("/message1.json")));
        verify(freeMembershipDiscountFilter).accept(payloadCaptor.capture());
        verifyNoMoreInteractions(freeMembershipDiscountFilter);
        assertEquals(Map.of("purchases", 100, "months", 6, "membership", "free"), payloadCaptor.getValue());
    }

    // A whitespace-only body is a non-empty request stream, so it passes the and-filter and fails in the transformer,
    // as do JSON values that are not objects and malformed JSON; each failure carries a fixed message.
    @ParameterizedTest(name = "body [{0}]")
    @MethodSource("bodiesThatAreNotJsonObjects")
    void bodyThatIsNotAJsonObjectThrowsWithoutReachingTheFilter(String body, String expectedMessage) {
        UncheckedIOException thrown =
                assertThrows(UncheckedIOException.class, () -> service.applyDiscountFilters("POST", body));

        assertEquals(expectedMessage, thrown.getMessage());
        assertInstanceOf(JsonProcessingException.class, thrown.getCause());
        verifyNoInteractions(freeMembershipDiscountFilter);
    }

    static Stream<Arguments> bodiesThatAreNotJsonObjects() {
        return Stream.of(
                Arguments.of(" ", "JSON payload is not an object"),
                Arguments.of("\n\t ", "JSON payload is not an object"),
                Arguments.of("[1]", "JSON payload is not an object"),
                Arguments.of("42", "JSON payload is not an object"),
                Arguments.of("\"free\"", "JSON payload is not an object"),
                Arguments.of("{\"months\": ", "Payload is not valid JSON"));
    }

    // The transformer reads the JSON literal null as a null map, which the original filter failed to cast to a Map.
    @Test
    void jsonNullThrowsWithoutReachingTheFilter() {
        ClassCastException thrown =
                assertThrows(ClassCastException.class, () -> service.applyDiscountFilters("POST", "null"));

        assertEquals("JSON payload is not an object", thrown.getMessage());
        verifyNoInteractions(freeMembershipDiscountFilter);
    }

    // The filter's own failures carry one fixed message, with the filter's exception kept as the cause.
    @ParameterizedTest(name = "body [{0}] {2}")
    @MethodSource("payloadsTheFilterFailsOn")
    void filterExceptionIsReportedWithAFixedMessage(String body, Map<String, Object> expectedPayload,
                                                    RuntimeException failure) {
        when(freeMembershipDiscountFilter.accept(any())).thenThrow(failure);

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> service.applyDiscountFilters("POST", body));

        assertEquals(
                "JSON payload does not hold the membership, months and purchases values the discount filter requires",
                thrown.getMessage());
        assertSame(failure, thrown.getCause());
        verify(freeMembershipDiscountFilter).accept(payloadCaptor.capture());
        verifyNoMoreInteractions(freeMembershipDiscountFilter);
        assertEquals(expectedPayload, payloadCaptor.getValue());
    }

    /**
     * Objects on which the ported filter throws: one without the {@code membership} key, which fails with a
     * {@link NullPointerException}, and one whose {@code months} value is not an integer, which fails with a
     * {@link NumberFormatException}.
     */
    static Stream<Arguments> payloadsTheFilterFailsOn() {
        return Stream.of(
                Arguments.of("{\"purchases\": 2000, \"months\": 12}", Map.of("purchases", 2000, "months", 12),
                        new NullPointerException()),
                Arguments.of("{\"purchases\": 2000, \"months\": \"x\", \"membership\": \"free\"}",
                        Map.of("purchases", 2000, "months", "x", "membership", "free"),
                        new NumberFormatException()));
    }

    /**
     * The four bodies the 500 once answered with Jackson's or the JDK's text, run through the real ported filter:
     * the message that reaches the response names no Java type and quotes nothing of the request, and no message in
     * the cause chain quotes the request either.
     */
    @ParameterizedTest(name = "body [{0}]")
    @MethodSource("bodiesThatFail")
    void failureMessagesExposeNoInternalsOrRequestBytes(String body, Class<? extends RuntimeException> expectedType,
                                                        String expectedMessage) {
        DiscountService realFilterService = new DiscountService(new FreeMembershipDiscountFilter());

        RuntimeException thrown = assertThrows(expectedType, () -> realFilterService.applyDiscountFilters("POST", body));

        assertEquals(expectedMessage, thrown.getMessage());
        for (String leak : List.of("java.", "HashMap", "JsonToken", "Source", "hunter2")) {
            assertFalse(thrown.getMessage().contains(leak), () -> leak + " in " + thrown.getMessage());
        }
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage());
            assertFalse(message.contains("hunter2"), message);
            assertFalse(message.contains("[1,2]"), message);
        }
    }

    static Stream<Arguments> bodiesThatFail() {
        return Stream.of(
                Arguments.of("[1,2]", UncheckedIOException.class, "JSON payload is not an object"),
                Arguments.of("{\"purchases\": 2000, \"months\": 12}", IllegalArgumentException.class,
                        "JSON payload does not hold the membership, months and purchases values the discount filter"
                                + " requires"),
                Arguments.of("null", ClassCastException.class, "JSON payload is not an object"),
                Arguments.of("{\"password\":\"hunter2\" xx}", UncheckedIOException.class, "Payload is not valid JSON"));
    }

    /** Reads a test resource as the UTF-8 request body the HTTP listener delivers. */
    private static String resource(String name) throws IOException {
        try (InputStream in = DiscountServiceTest.class.getResourceAsStream(name)) {
            assertNotNull(in, () -> "test resource " + name + " is missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
