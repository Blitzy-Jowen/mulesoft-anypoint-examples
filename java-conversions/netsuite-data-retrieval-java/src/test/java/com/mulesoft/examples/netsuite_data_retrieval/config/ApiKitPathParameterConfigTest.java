/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.Ordered;
import org.springframework.http.server.RequestPath;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Unit test of {@link ApiKitPathParameterConfig}: its handler mapping runs first, selects no handler, leaves a request
 * whose path holds no {@code ;} as it is, and gives every other request a parsed path in which each {@code ;} after
 * the context path is {@code %3B}, which Spring MVC's path patterns match as segment content, while the request URI
 * stays as sent.
 *
 * <p>The RAML-backed projects carry copies of this class that differ from this one in the {@code package} line
 * alone.
 */
class ApiKitPathParameterConfigTest {

    private final ApiKitPathParameterConfig.PathParameterSegmentMapping mapping = new ApiKitPathParameterConfig()
            .apiKitPathParameterSegmentMapping();

    @Test
    void mappingRunsFirstAndReadsTheParsedPath() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE, mapping.getOrder());
        assertTrue(mapping.usesPathPatterns());
    }

    /**
     * The context path and request URI of each request carrying {@code ;} after the context path, then the value
     * and the path within the application of the parsed path the mapping leaves on it.
     */
    private static Stream<Arguments> pathsCarryingSemicolons() {
        return Stream.of(
                Arguments.of("", "/api/teams;x=1", "/api/teams%3Bx=1", "/api/teams%3Bx=1"),
                Arguments.of("", "/api/teams;", "/api/teams%3B", "/api/teams%3B"),
                Arguments.of("", "/api/teams/BAR;x=1", "/api/teams/BAR%3Bx=1", "/api/teams/BAR%3Bx=1"),
                Arguments.of("", "/api/teams/;x=1", "/api/teams/%3Bx=1", "/api/teams/%3Bx=1"),
                Arguments.of("", "/api/teams/SMC;jsessionid=1", "/api/teams/SMC%3Bjsessionid=1",
                        "/api/teams/SMC%3Bjsessionid=1"),
                Arguments.of("", "/api;x=1/teams", "/api%3Bx=1/teams", "/api%3Bx=1/teams"),
                Arguments.of("", "/a;b;c/d;", "/a%3Bb%3Bc/d%3B", "/a%3Bb%3Bc/d%3B"),
                // A ';' already sent as %3B is kept, and an encoded character elsewhere is not decoded
                Arguments.of("", "/api/teams/B%3B;x", "/api/teams/B%3B%3Bx", "/api/teams/B%3B%3Bx"),
                Arguments.of("", "/api/teams/B%20R;x", "/api/teams/B%20R%3Bx", "/api/teams/B%20R%3Bx"),
                // The context path is kept as it is and is not part of the path within the application
                Arguments.of("/ctx", "/ctx/api/teams;x=1", "/ctx/api/teams%3Bx=1", "/api/teams%3Bx=1"),
                Arguments.of("/ctx", "/ctx/;x=1", "/ctx/%3Bx=1", "/%3Bx=1"),
                // No context path, however reported
                Arguments.of("/", "/api/teams;x=1", "/api/teams%3Bx=1", "/api/teams%3Bx=1"),
                Arguments.of(null, "/api/teams;x=1", "/api/teams%3Bx=1", "/api/teams%3Bx=1"));
    }

    @ParameterizedTest(name = "context {0}, URI {1} -> {2}")
    @MethodSource("pathsCarryingSemicolons")
    void semicolonAfterTheContextPathIsParsedAsSegmentContent(String contextPath, String uri, String expectedValue,
                                                              String expectedPathWithinApplication) {
        MockHttpServletRequest request = request(contextPath, uri);
        ServletRequestPathUtils.parseAndCache(request);

        assertNull(mapping.getHandler(request));

        RequestPath parsed = ServletRequestPathUtils.getParsedRequestPath(request);
        assertEquals(expectedValue, parsed.value());
        assertEquals(expectedPathWithinApplication, parsed.pathWithinApplication().value());
        assertEquals(uri, request.getRequestURI());
    }

    @Test
    void semicolonPathIsParsedWhenNoPathWasParsedBefore() {
        MockHttpServletRequest request = request("", "/api/teams;x=1");

        assertNull(mapping.getHandler(request));

        assertEquals("/api/teams%3Bx=1", ServletRequestPathUtils.getParsedRequestPath(request).value());
    }

    /** The context path and request URI of each request the mapping leaves alone. */
    private static Stream<Arguments> requestsLeftAlone() {
        return Stream.of(
                Arguments.of("", "/api/teams"),
                Arguments.of("", "/api/teams/BAR"),
                Arguments.of("", "/api/teams%3Bx=1"),
                Arguments.of("", "/"),
                // A ';' in the context path alone is not part of the path the mappings match
                Arguments.of("/ctx;v=1", "/ctx;v=1/api/teams"),
                // URIs that do not continue their context path with a new segment, which Spring MVC cannot parse
                Arguments.of("/ctx", "/api/teams;x=1"),
                Arguments.of("/ctx", "/ctx;v=1"),
                Arguments.of("/ctx", "/ctxa/b;c"));
    }

    @ParameterizedTest(name = "context {0}, URI {1}")
    @MethodSource("requestsLeftAlone")
    void requestWithoutSemicolonInItsPathKeepsItsParsedPath(String contextPath, String uri) {
        MockHttpServletRequest request = request(contextPath, uri);
        RequestPath before = RequestPath.parse("/parsed-before", null);
        ServletRequestPathUtils.setParsedRequestPath(before, request);

        assertNull(mapping.getHandler(request));

        assertSame(before, ServletRequestPathUtils.getParsedRequestPath(request));
        assertEquals(uri, request.getRequestURI());
    }

    @Test
    void semicolonInTheQueryStringLeavesTheParsedPath() {
        MockHttpServletRequest request = request("", "/api/teams");
        request.setQueryString("city=a;b");
        RequestPath before = ServletRequestPathUtils.parseAndCache(request);

        assertNull(mapping.getHandler(request));

        assertSame(before, ServletRequestPathUtils.getParsedRequestPath(request));
    }

    @Test
    void requestWithoutUriIsLeftAlone() {
        MockHttpServletRequest request = request("", "/");
        request.setRequestURI(null);

        assertNull(mapping.getHandler(request));

        assertFalse(ServletRequestPathUtils.hasParsedRequestPath(request));
    }

    /**
     * The RAML URI template, the request URI and the URI variables the template binds from the parsed path the
     * mapping leaves, or {@code null} where the template matches no such path.
     */
    private static Stream<Arguments> templateMatches() {
        return Stream.of(
                Arguments.of("/api/teams", "/api/teams;x=1", null),
                Arguments.of("/api/teams", "/api/teams;", null),
                Arguments.of("/api/teams/{teamId}", "/api/teams;x=1", null),
                Arguments.of("/api/teams/{teamId}", "/api/teams/BAR;x=1", Map.of("teamId", "BAR;x=1")),
                Arguments.of("/api/teams/{teamId}", "/api/teams/BAR;", Map.of("teamId", "BAR;")),
                Arguments.of("/api/teams/{teamId}", "/api/teams/;x=1", Map.of("teamId", ";x=1")),
                Arguments.of("/api/teams/{teamId}", "/api/teams/B%20R;x", Map.of("teamId", "B R;x")),
                Arguments.of("/api/fixture/{homeTeamId}/{awayTeamId}", "/api/fixture/BAR;a/OSA",
                        Map.of("homeTeamId", "BAR;a", "awayTeamId", "OSA")),
                Arguments.of("/api/teams", "/api;x=1/teams", null),
                Arguments.of("/api/console/**", "/api/console;x=1", null));
    }

    @ParameterizedTest(name = "{0} against {1} -> {2}")
    @MethodSource("templateMatches")
    void templatesMatchTheSemicolonAsSegmentContent(String template, String uri, Map<String, String> expected) {
        MockHttpServletRequest request = request("", uri);
        mapping.getHandler(request);
        PathPattern pattern = PathPatternParser.defaultInstance.parse(template);

        PathPattern.PathMatchInfo match = pattern.matchAndExtract(
                ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication());

        if (expected == null) {
            assertNull(match, () -> template + " matched " + uri);
        } else {
            assertNotNull(match, () -> template + " did not match " + uri);
            assertEquals(expected, match.getUriVariables());
        }
    }

    private static MockHttpServletRequest request(String contextPath, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setContextPath(contextPath);
        return request;
    }
}
