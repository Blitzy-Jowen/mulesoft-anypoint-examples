/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.WebUtils;

/**
 * Unit test of {@link ApiKitMethodCaseFilter}: a request under the API base path whose method token is not upper
 * case reaches the rest of the chain with the token upper-cased and nothing else changed, and every other request
 * reaches it as the same request object.
 *
 * <p>The RAML-backed projects carry copies of this class that differ from this one in the {@code package} line
 * alone.
 */
class ApiKitMethodCaseFilterTest {

    private static final String BASE_PATH = "/api";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"get", "Get", "gEt", "post", "put", "delete", "options", "head", "trace", "patch",
            "propfind"})
    void nonUpperCaseTokenUnderTheBasePathReachesTheChainUpperCased(String token)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(token, "/api/teams");
        request.setQueryString("city=Barcelona");
        request.addHeader("Accept", "application/json");
        request.addHeader("X-Trace", "first");
        request.addHeader("X-Trace", "second");
        request.setAttribute("upstream", "set before the filter");

        HttpServletRequest forwarded = filter(new ApiKitMethodCaseFilter(BASE_PATH), request);

        assertNotSame(request, forwarded);
        assertEquals(token.toUpperCase(Locale.ROOT), forwarded.getMethod());
        assertEquals(token, request.getMethod());
        assertEquals("/api/teams", forwarded.getRequestURI());
        assertEquals("city=Barcelona", forwarded.getQueryString());
        assertEquals(Collections.list(request.getHeaderNames()), Collections.list(forwarded.getHeaderNames()));
        assertEquals("application/json", forwarded.getHeader("Accept"));
        assertEquals(List.of("first", "second"), Collections.list(forwarded.getHeaders("X-Trace")));
        assertEquals("set before the filter", forwarded.getAttribute("upstream"));
        forwarded.setAttribute("downstream", "set after the filter");
        assertEquals("set after the filter", request.getAttribute("downstream"));
    }

    /**
     * The base path, the context path, the request URI and the method token of each request, and whether the
     * filter passes it on upper-cased ({@code true}) or as the same request object ({@code false}).
     */
    private static Stream<Arguments> requests() {
        return Stream.of(
                // Paths under the base path, the base path itself included, and paths outside it
                Arguments.of(BASE_PATH, "", "/api", "get", true),
                Arguments.of(BASE_PATH, "", "/api/", "get", true),
                Arguments.of(BASE_PATH, "", "/api/x", "get", true),
                Arguments.of(BASE_PATH, "", "/api/teams/BAR", "get", true),
                Arguments.of(BASE_PATH, "", "/apix", "get", false),
                Arguments.of(BASE_PATH, "", "/apix/teams", "get", false),
                Arguments.of(BASE_PATH, "", "/other", "get", false),
                Arguments.of(BASE_PATH, "", "/", "get", false),
                Arguments.of(BASE_PATH, "", null, "get", false),
                // The context path is stripped before the path is compared with the base path
                Arguments.of(BASE_PATH, "/ctx", "/ctx/api/teams", "get", true),
                Arguments.of(BASE_PATH, "/ctx", "/ctx/api", "get", true),
                Arguments.of(BASE_PATH, "/ctx", "/ctx/apix", "get", false),
                Arguments.of(BASE_PATH, "/api", "/api/teams", "get", false),
                // Base paths written without a leading slash, with blanks or with trailing slashes
                Arguments.of("api", "", "/api/teams", "get", true),
                Arguments.of("api", "", "/apix", "get", false),
                Arguments.of(" /api/ ", "", "/api/teams", "get", true),
                Arguments.of(" /api/ ", "", "/other", "get", false),
                Arguments.of("/api//", "", "/api", "get", true),
                Arguments.of("/api//", "", "/apix", "get", false),
                // A base path of "/", however written, matches every path
                Arguments.of("/", "", "/other", "get", true),
                Arguments.of("/", "", "/", "get", true),
                Arguments.of("/", "", null, "get", true),
                Arguments.of("", "", "/other", "get", true),
                Arguments.of(null, "", "/other", "get", true),
                // Tokens that are already upper case, and no token at all
                Arguments.of(BASE_PATH, "", "/api/teams", "GET", false),
                Arguments.of(BASE_PATH, "", "/api/teams", "POST", false),
                Arguments.of(BASE_PATH, "", "/api/teams", "PROPFIND", false),
                Arguments.of(BASE_PATH, "", "/api/teams", "", false),
                Arguments.of(BASE_PATH, "", "/api/teams", null, false));
    }

    @ParameterizedTest(name = "base {0}, context {1}, URI {2}, method {3}: upper-cased {4}")
    @MethodSource("requests")
    void requestIsUpperCasedOnlyUnderTheBasePathAndOnlyWhenNotUpperCase(String basePath, String contextPath,
                                                                       String uri, String token,
                                                                       boolean upperCased)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(token, uri);
        request.setContextPath(contextPath);

        HttpServletRequest forwarded = filter(new ApiKitMethodCaseFilter(basePath), request);

        assertEquals(upperCased, forwarded != request, "passed on wrapped");
        assertEquals(upperCased ? token.toUpperCase(Locale.ROOT) : token, forwarded.getMethod());
        assertEquals(uri, forwarded.getRequestURI());
    }

    @Test
    void errorDispatchReachesTheChainUnchanged() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("get", "/api/teams");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, "/api/teams");

        HttpServletRequest forwarded = filter(new ApiKitMethodCaseFilter(BASE_PATH), request);

        assertSame(request, forwarded);
        assertEquals("get", forwarded.getMethod());
    }

    @Test
    void asyncDispatchReachesTheChainUnchanged() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("get", "/api/teams");
        request.setDispatcherType(DispatcherType.ASYNC);

        HttpServletRequest forwarded = filter(new ApiKitMethodCaseFilter(BASE_PATH), request);

        assertSame(request, forwarded);
        assertEquals("get", forwarded.getMethod());
    }

    @Test
    void runsAtTheHighestPrecedence() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE, new ApiKitMethodCaseFilter(BASE_PATH).getOrder());
    }

    /** Runs {@code filter} on {@code request} and returns the request it passed to the rest of the chain. */
    private static HttpServletRequest filter(ApiKitMethodCaseFilter filter, MockHttpServletRequest request)
            throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }
}
