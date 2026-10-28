/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit test of {@link ListenerMethodFilter}, which matches {@code POST} on the listener {@code path="/"} ignoring
 * case, as Mule's {@code MethodRequestMatcher} did for {@code allowedMethods="POST"}
 * (adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml:8): the filter alone
 * over a {@link MockFilterChain}, checking which request the chain receives and the method it reports. The answers
 * those requests get on the wire are asserted by {@code controller/WorkdayCustomerControllerTest}.
 */
class ListenerMethodFilterTest {

    /** A {@code root/Account} body like the original test resource {@code customer.xml}. */
    private static final byte[] CUSTOMER =
            "<root><Account><CustomerName>NAME</CustomerName></Account></root>".getBytes(StandardCharsets.UTF_8);

    @ParameterizedTest
    @ValueSource(strings = {"post", "Post", "pOsT", "POSt"})
    void caseVariantOfPostToTheListenerPathReachesTheChainAsPost(String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/");
        request.setQueryString("source=test");
        request.setContentType("application/xml");
        request.addHeader("Origin", "https://example.invalid");
        request.setContent(CUSTOMER);
        MockFilterChain chain = new MockFilterChain();

        new ListenerMethodFilter().doFilter(request, new MockHttpServletResponse(), chain);

        ServletRequest passed = chain.getRequest();
        assertNotSame(request, passed);
        HttpServletRequest wrapped = (HttpServletRequest) passed;
        assertEquals("POST", wrapped.getMethod());
        // Everything but the method is the original request's.
        assertEquals("/", wrapped.getRequestURI());
        assertEquals("source=test", wrapped.getQueryString());
        assertEquals("application/xml", wrapped.getContentType());
        assertEquals("https://example.invalid", wrapped.getHeader("Origin"));
        assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo(CUSTOMER);
        assertEquals(method, request.getMethod());
    }

    @Test
    void caseVariantOfPostToTheListenerPathUnderAContextPathReachesTheChainAsPost() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("post", "/app/");
        request.setContextPath("/app");
        MockFilterChain chain = new MockFilterChain();

        new ListenerMethodFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("POST", ((HttpServletRequest) chain.getRequest()).getMethod());
    }

    /** Method token, then request URI and context path, of a request the filter must pass on unchanged. */
    private static Stream<Arguments> requestsPassedUnchanged() {
        return Stream.of(
                // The listener's own method, and other spellings of methods the listener does not allow.
                Arguments.of("POST", "/", ""),
                Arguments.of("GET", "/", ""),
                Arguments.of("get", "/", ""),
                Arguments.of("options", "/", ""),
                Arguments.of("OPTIONS", "/", ""),
                Arguments.of("trace", "/", ""),
                Arguments.of("postx", "/", ""),
                Arguments.of("pos", "/", ""),
                // U+017F lower-cases to itself, so this token never compares equal to "post".
                Arguments.of("po\u017Ft", "/", ""),
                // A case variant of POST to a path no listener serves keeps the no-listener 404.
                Arguments.of("post", "/other", ""),
                Arguments.of("post", "/other/", ""),
                Arguments.of("post", "//", ""),
                Arguments.of("post", "/%2F", ""),
                Arguments.of("Post", "/services/x", ""),
                // Paths outside, or other than "/" within, the context path.
                Arguments.of("post", "/app", "/app"),
                Arguments.of("post", "/app/other", "/app"),
                Arguments.of("post", "/", "/app"));
    }

    @ParameterizedTest(name = "{0} {1} (context path [{2}])")
    @MethodSource("requestsPassedUnchanged")
    void otherRequestPassesUnchanged(String method, String uri, String contextPath) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContextPath(contextPath);
        MockFilterChain chain = new MockFilterChain();

        new ListenerMethodFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertSame(request, chain.getRequest());
        assertEquals(method, request.getMethod());
    }

    @Test
    void requestWithoutMethodPassesUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(null, "/");
        MockFilterChain chain = new MockFilterChain();

        new ListenerMethodFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertSame(request, chain.getRequest());
    }

    @Test
    void requestWithoutUriPassesUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("post", null);
        MockFilterChain chain = new MockFilterChain();

        new ListenerMethodFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertSame(request, chain.getRequest());
        assertEquals("post", request.getMethod());
    }
}
