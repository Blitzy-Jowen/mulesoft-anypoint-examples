/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.cache_scope_with_fibonacci.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mulesoft.examples.cache_scope_with_fibonacci.model.FibonacciResult;
import com.mulesoft.examples.cache_scope_with_fibonacci.service.FibonacciService.FibonacciReply;

/**
 * {@link FibonacciService} against flow {@code cache-exampleFlow1}
 * (cache-scope-with-fibonacci/src/main/app/cache-scope.xml:43-59): the favicon {@code message-filter} with its
 * case-sensitive {@code wildcard-filter} on the payload, the {@code n < 20} choice, the call to
 * {@code calculateFibonacci} and the two {@code expression-component} payloads.
 *
 * <p>Expected texts are the reference flow's own: {@code "Fibonacci(" + n + ") = " + payload + "\nCOST: " + cost}
 * (cache-scope.xml:53) and {@code "ERROR: n must be less than 20"} (cache-scope.xml:56). The cache service is a
 * strict Mockito mock, so a call with arguments other than the stubbed ones fails the test.
 */
@ExtendWith(MockitoExtension.class)
class FibonacciServiceTest {

    /** Payload of the choice's {@code otherwise} branch (cache-scope.xml:56). */
    private static final String TOO_HIGH = "ERROR: n must be less than 20";

    @Mock
    private FibonacciCacheService cacheService;

    private FibonacciService service;

    @BeforeEach
    void setUp() {
        service = new FibonacciService(cacheService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"10", "20"})
    void faviconBodyIsFilteredWithAnEmptyReplyAndNoCost(String n) {
        assertEquals(new FibonacciReply("", null), service.handle(n, "/favicon.ico", false, false));
        verifyNoInteractions(cacheService);
    }

    /**
     * A null payload (no body, or a multipart body) and a form body's parameter map, even one naming the path, pass
     * the filter like any text other than {@code "/favicon.ico"}.
     *
     * @param payload the listener payload the filter tests
     */
    @ParameterizedTest
    @NullSource
    @MethodSource("payloadsTheFilterPasses")
    void filterMatchesOnlyTheExactCaseSensitiveText(Object payload) {
        when(cacheService.calculate("10", true, true)).thenReturn(new FibonacciResult("55", 11));

        assertEquals(new FibonacciReply("Fibonacci(10) = 55\nCOST: 11", 11),
                service.handle("10", payload, false, false));
        verify(cacheService).calculate("10", true, true);
        verifyNoMoreInteractions(cacheService);
    }

    /**
     * @return non-null listener payloads other than the text {@code /favicon.ico}: near-miss texts and form
     *         parameter maps
     */
    static Stream<Object> payloadsTheFilterPasses() {
        return Stream.of(
                "/FAVICON.ICO",
                "x/favicon.ico",
                // The form body "/favicon.ico": one bare name, whose value is null.
                Collections.singletonMap("/favicon.ico", null),
                Map.of("icon", "/favicon.ico"),
                Map.of());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"20"})
    void missingOrTooHighNTakesTheOtherwiseBranch(String n) {
        assertEquals(new FibonacciReply(TOO_HIGH, null), service.handle(n, "", false, false));
        verifyNoInteractions(cacheService);
    }

    static Stream<Arguments> belowTwenty() {
        return Stream.of(
                Arguments.of("19", new FibonacciResult("4181", 20), "Fibonacci(19) = 4181\nCOST: 20"),
                // The raw query text is the cache key and is echoed unchanged in the reply.
                Arguments.of("05", new FibonacciResult("5", 0), "Fibonacci(05) = 5\nCOST: 0"),
                Arguments.of("-3", new FibonacciResult("-3", 1), "Fibonacci(-3) = -3\nCOST: 1"));
    }

    @ParameterizedTest
    @MethodSource("belowTwenty")
    void belowTwentyCallsTheCacheWithTheRawNAndComposesTheReply(String n, FibonacciResult result, String expected) {
        when(cacheService.calculate(n, true, true)).thenReturn(result);

        assertEquals(new FibonacciReply(expected, result.cost()), service.handle(n, "", false, false));
        verify(cacheService).calculate(n, true, true);
        verifyNoMoreInteractions(cacheService);
    }

    @ParameterizedTest
    @CsvSource({
        // nocacheHeader, nocacheQuery, useCache, childrenUseCache
        "true,  false, false, true",
        "false, true,  true,  false",
        "true,  true,  false, false"
    })
    void nocacheFlagsReachTheCacheServiceInverted(boolean nocacheHeader, boolean nocacheQuery, boolean useCache,
            boolean childrenUseCache) {
        when(cacheService.calculate("10", useCache, childrenUseCache)).thenReturn(new FibonacciResult("55", 11));

        assertEquals(new FibonacciReply("Fibonacci(10) = 55\nCOST: 11", 11),
                service.handle("10", "", nocacheHeader, nocacheQuery));
        verify(cacheService).calculate("10", useCache, childrenUseCache);
        verifyNoMoreInteractions(cacheService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", " 5"})
    void nonNumericNIsRejectedWithoutTouchingTheCache(String n) {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> service.handle(n, "", false, false));

        assertEquals("Invalid value for n: " + n, thrown.getMessage());
        assertInstanceOf(NumberFormatException.class, thrown.getCause());
        verifyNoInteractions(cacheService);
    }
}
