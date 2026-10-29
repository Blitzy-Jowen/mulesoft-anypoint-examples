/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.track_a_custom_business_event.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.function.Supplier;
import java.util.stream.Stream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tests {@link RequestCharsetFilter} directly with Spring's servlet mocks: a request whose character encoding names
 * no usable charset reaches the rest of the chain as a view that declares none, with its {@code Content-Type}
 * untouched, and every other request reaches it as the very same instance.
 */
class RequestCharsetFilterTest {

    private final RequestCharsetFilter filter = new RequestCharsetFilter();

    /** Illegal names, the empty one included, and a legal name of a charset the JVM lacks. */
    @ParameterizedTest(name = "charset [{0}]")
    @ValueSource(strings = {"bad[charset]", "", "foo"})
    void unusableCharsetIsHiddenFromTheChain(String encoding) throws ServletException, IOException {
        MockHttpServletRequest request = request(encoding);
        String contentType = request.getContentType();

        HttpServletRequest passed = filter(request);

        assertNotSame(request, passed);
        assertNull(passed.getCharacterEncoding());
        assertEquals(contentType, passed.getContentType());
        assertEquals("application/json; charset=" + encoding, passed.getHeader("Content-Type"));
        assertEquals(encoding, request.getCharacterEncoding(), "the wrapped request is not changed");
    }

    /** Undertow cannot read a {@code charset=} without a value at the end of the header and throws instead. */
    @Test
    void unreadableCharsetIsHiddenFromTheChain() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/customBusinessEvents") {
            @Override
            public String getCharacterEncoding() {
                throw new StringIndexOutOfBoundsException("String index out of range: 25");
            }
        };
        request.setContentType("application/json;charset=");

        HttpServletRequest passed = filter(request);

        assertNotSame(request, passed);
        assertNull(passed.getCharacterEncoding());
        assertEquals("application/json;charset=", passed.getContentType());
        assertEquals("application/json;charset=", passed.getHeader("Content-Type"));
    }

    @ParameterizedTest(name = "charset {0}")
    @NullSource
    @ValueSource(strings = {"UTF-8", "latin1"})
    void usableOrAbsentCharsetPassesTheSameRequest(String encoding) throws ServletException, IOException {
        MockHttpServletRequest request = request(encoding);

        assertSame(request, filter(request));
    }

    /** As the servlet API specifies, an encoding set on the request overrides the one its Content-Type declares. */
    @Test
    void encodingSetOnTheViewIsReported() throws ServletException, IOException {
        MockHttpServletRequest request = request("bad[charset]");
        HttpServletRequest passed = filter(request);

        passed.setCharacterEncoding("UTF-8");

        assertEquals("UTF-8", passed.getCharacterEncoding());
        assertEquals("UTF-8", request.getCharacterEncoding());
    }

    /**
     * Undertow ignores a set once the body is being read, as the servlet API lets it, and the wrapped request then
     * still reports the {@code Content-Type}'s illegal charset or fails to read it: the view still reports none.
     */
    @ParameterizedTest(name = "Content-Type {0}")
    @MethodSource("readStartedRequests")
    void encodingSetThatTheWrappedRequestIgnoresLeavesNone(String contentType, Supplier<String> headerEncoding)
            throws ServletException, IOException {
        MockHttpServletRequest request = new ReadStartedRequest(headerEncoding);
        request.setContentType(contentType);
        HttpServletRequest passed = filter(request);

        passed.setCharacterEncoding("UTF-8");

        assertNotSame(request, passed);
        assertNull(assertDoesNotThrow(passed::getCharacterEncoding));
        assertEquals(contentType, passed.getHeader("Content-Type"));
    }

    static Stream<Arguments> readStartedRequests() {
        return Stream.of(
                Arguments.of("application/json; charset=bad[charset]", (Supplier<String>) () -> "bad[charset]"),
                Arguments.of("application/json;charset=", (Supplier<String>) () -> {
                    throw new StringIndexOutOfBoundsException("String index out of range: 25");
                }));
    }

    /** Runs the filter over {@code request} and returns the request the rest of the chain received. */
    private HttpServletRequest filter(MockHttpServletRequest request) throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return assertInstanceOf(HttpServletRequest.class, chain.getRequest());
    }

    /**
     * A POST whose {@code Content-Type} is {@code application/json} with {@code encoding} as its charset, and whose
     * character encoding is {@code encoding} verbatim, as Undertow reports the raw charset parameter when no
     * character-encoding filter runs; {@code null} sends {@code application/json} without a charset.
     */
    private static MockHttpServletRequest request(String encoding) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/customBusinessEvents");
        if (encoding == null) {
            request.setContentType("application/json");
        } else {
            request.setContentType("application/json; charset=" + encoding);
            request.setCharacterEncoding(encoding);
        }
        assertEquals(encoding, request.getCharacterEncoding());
        return request;
    }

    /**
     * A request whose body is being read, as Undertow's request behaves then: {@code setCharacterEncoding} has no
     * effect, and {@code getCharacterEncoding} keeps reading the {@code Content-Type}'s charset through
     * {@code headerEncoding}.
     */
    private static final class ReadStartedRequest extends MockHttpServletRequest {

        private final Supplier<String> headerEncoding;

        ReadStartedRequest(Supplier<String> headerEncoding) {
            super("POST", "/customBusinessEvents");
            this.headerEncoding = headerEncoding;
        }

        @Override
        public String getCharacterEncoding() {
            return headerEncoding.get();
        }

        @Override
        public void setCharacterEncoding(String characterEncoding) {
            // Ignored, as a set after reading has started has no effect.
        }
    }
}
