/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.filtering_a_message.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit test of {@link DiscountService}, the processors of flow {@code filteringFlow1} in
 * {@code filtering-a-message/src/main/app/filtering.xml}, lines 9-17: the {@code and-filter} on body presence and
 * the {@code post} method, the {@code json-to-object-transformer returnClass="java.util.HashMap"}, the
 * {@code FreeMembershipDiscountFilter} {@code custom-filter} and the granted {@code set-payload}.
 *
 * <p>A rejection by either filter is not an error: neither declares {@code throwOnUnaccepted}, so the service returns
 * {@link Optional#empty()}. The flow has no exception strategy, so a body the transformer cannot read and every
 * exception of the custom filter propagate unchanged.
 *
 * <p>JUnit 5 and Mockito with no Spring context: a real Jackson {@link ObjectMapper} reads the body as production does,
 * and the mocked {@link FreeMembershipDiscountFilter} isolates the service's own decisions; the rule itself is
 * covered by {@link FreeMembershipDiscountFilterTest}. Strict stubs: a test stubs the filter only when its input
 * reaches it, and every other test asserts that the filter is never called.
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
        service = new DiscountService(new ObjectMapper(), freeMembershipDiscountFilter);
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
    void andFilterRejectionReturnsEmptyWithoutParsing(String httpMethod, String body) throws JsonProcessingException {
        assertEquals(Optional.empty(), service.applyDiscountFilters(httpMethod, body));
        verifyNoInteractions(freeMembershipDiscountFilter);
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
    // as do JSON values that are not objects and malformed JSON.
    @ParameterizedTest(name = "body [{0}]")
    @ValueSource(strings = {" ", "\n\t ", "[1]", "42", "\"free\"", "{\"months\": "})
    void bodyThatIsNotAJsonObjectThrowsWithoutReachingTheFilter(String body) {
        assertThrows(JsonProcessingException.class, () -> service.applyDiscountFilters("POST", body));
        verifyNoInteractions(freeMembershipDiscountFilter);
    }

    @ParameterizedTest(name = "body [{0}]")
    @MethodSource("payloadsTheFilterFailsOn")
    void filterExceptionPropagatesUnchanged(String body, Map<String, Object> expectedPayload) {
        NullPointerException failure = new NullPointerException();
        when(freeMembershipDiscountFilter.accept(any())).thenThrow(failure);

        NullPointerException thrown =
                assertThrows(NullPointerException.class, () -> service.applyDiscountFilters("POST", body));

        assertSame(failure, thrown);
        verify(freeMembershipDiscountFilter).accept(payloadCaptor.capture());
        verifyNoMoreInteractions(freeMembershipDiscountFilter);
        assertEquals(expectedPayload, payloadCaptor.getValue());
    }

    /**
     * Bodies on which the ported filter throws: the JSON literal {@code null}, which the transformer reads as a
     * {@code null} map, and an object without the {@code membership} key.
     */
    static Stream<Arguments> payloadsTheFilterFailsOn() {
        return Stream.of(
                Arguments.of("null", null),
                Arguments.of("{\"purchases\": 2000, \"months\": 12}", Map.of("purchases", 2000, "months", 12)));
    }

    /** Reads a test resource as the UTF-8 request body the HTTP listener delivers. */
    private static String resource(String name) throws IOException {
        try (InputStream in = DiscountServiceTest.class.getResourceAsStream(name)) {
            assertNotNull(in, () -> "test resource " + name + " is missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
