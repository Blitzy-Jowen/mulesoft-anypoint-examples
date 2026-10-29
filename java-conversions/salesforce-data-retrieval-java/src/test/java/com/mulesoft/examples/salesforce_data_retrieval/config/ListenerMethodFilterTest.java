/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit test of {@link ListenerMethodFilter} over a {@link MockFilterChain}: which request the chain receives and the
 * method it reports. The filter matches {@code GET} and {@code POST} on the {@code /} listeners ignoring case, as
 * Mule's {@code MethodRequestMatcher} did for their {@code allowedMethods}
 * (salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:8,26), and leaves every other request alone.
 * The same spellings on the wire are covered by {@code controller/SalesforceRetrievalControllerTest}.
 */
class ListenerMethodFilterTest {

    /** The form the original {@code SalesforceIdRetrievalIT#testGetData} posts. */
    private static final byte[] FORM =
            "object=user&field=email&searchKey=name&searchValue=mule".getBytes(StandardCharsets.US_ASCII);

    private static final String FORM_URLENCODED = "application/x-www-form-urlencoded";

    /**
     * A case variant of an allowed method, its canonical spelling, then the context path, request URI and query
     * string of a request for the listener path {@code /}.
     */
    static Stream<Arguments> caseVariantsOnTheListenerPath() {
        Stream<Arguments> variants = Stream.of(
                Arguments.of("get", "GET"), Arguments.of("Get", "GET"), Arguments.of("gEt", "GET"),
                Arguments.of("post", "POST"), Arguments.of("Post", "POST"), Arguments.of("pOsT", "POST"));
        return variants.flatMap(variant -> Stream.of(
                Arguments.of(variant.get()[0], variant.get()[1], "", "/", null),
                // The query string is not part of the path the listener matched.
                Arguments.of(variant.get()[0], variant.get()[1], "", "/", "object=user"),
                // The path is taken within the application.
                Arguments.of(variant.get()[0], variant.get()[1], "/app", "/app/", null)));
    }

    @ParameterizedTest(name = "{0} {2}|{3}?{4}")
    @MethodSource("caseVariantsOnTheListenerPath")
    void caseVariantOfAnAllowedMethodOnTheListenerPathReachesTheChainInItsCanonicalSpelling(String method,
            String canonical, String contextPath, String requestUri, String queryString) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, requestUri);
        request.setContextPath(contextPath);
        request.setQueryString(queryString);
        request.setContentType(FORM_URLENCODED);
        request.setContent(FORM);
        MockFilterChain chain = new MockFilterChain();

        new ListenerMethodFilter().doFilter(request, new MockHttpServletResponse(), chain);

        ServletRequest passed = chain.getRequest();
        assertNotSame(request, passed);
        HttpServletRequest wrapped = (HttpServletRequest) passed;
        assertEquals(canonical, wrapped.getMethod());
        // Everything but the method is the original request's.
        assertEquals(requestUri, wrapped.getRequestURI());
        assertEquals(contextPath, wrapped.getContextPath());
        assertEquals(queryString, wrapped.getQueryString());
        assertEquals(FORM_URLENCODED, wrapped.getContentType());
        assertArrayEquals(FORM, wrapped.getInputStream().readAllBytes());
        assertEquals(method, request.getMethod());
    }

    /**
     * A method token ({@code null} for none), the context path and the request URI ({@code null} for none) of a
     * request the filter passes unchanged: the canonical spellings, other methods in any letter case and unknown
     * tokens on {@code /}; case variants of the allowed methods on any other path, under another context path or
     * without a request URI; and a request without a method.
     */
    static Stream<Arguments> requestsThatPassUnchanged() {
        Stream<Arguments> otherTokensOnTheListenerPath = Stream.of("GET", "POST", "HEAD", "head", "OPTIONS",
                        "options", "Options", "PUT", "put", "DELETE", "delete", "patch", "trace", "postx", "ge",
                        "po\u017Ft")
                .map(method -> Arguments.of(method, "", "/"));
        Stream<Arguments> variantsElsewhere = Stream.of("get", "Post").flatMap(method -> Stream.of(
                Arguments.of(method, "", "/favicon.ico"),
                Arguments.of(method, "", "/x/"),
                Arguments.of(method, "", "//"),
                Arguments.of(method, "", ""),
                Arguments.of(method, "", null),
                Arguments.of(method, "/app", "/app/x"),
                // A request URI outside the context path is compared whole.
                Arguments.of(method, "/app/x", "/app/")));
        return Stream.of(otherTokensOnTheListenerPath, variantsElsewhere, Stream.of(Arguments.of(null, "", "/")))
                .flatMap(arguments -> arguments);
    }

    @ParameterizedTest(name = "{0} {1}|{2}")
    @MethodSource("requestsThatPassUnchanged")
    void otherRequestPassesUnchanged(String method, String contextPath, String requestUri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, requestUri);
        request.setContextPath(contextPath);
        MockFilterChain chain = new MockFilterChain();

        new ListenerMethodFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertSame(request, chain.getRequest());
        assertEquals(method, request.getMethod());
    }
}
