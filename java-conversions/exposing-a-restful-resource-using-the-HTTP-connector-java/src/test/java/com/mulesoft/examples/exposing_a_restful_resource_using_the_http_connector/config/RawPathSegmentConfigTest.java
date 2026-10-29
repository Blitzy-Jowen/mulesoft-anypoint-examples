/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Unit test of {@link RawPathSegmentConfig.RawPathSegmentInterceptor} on mock requests, for the rules the person
 * listeners cannot reach on the wire: a request without a matched pattern, patterns whose segments are not literals or
 * a single {@code {name}} variable, a path and pattern of different lengths, several variables, a context path, the
 * attributes left untouched, and a request matched by an alias pattern held to its listener pattern.
 * {@code controller/PersonControllerTest} and {@code PreflightDispatchListenerTest} cover the listener paths
 * themselves on Undertow.
 */
class RawPathSegmentConfigTest {

    /** The item listener's alias, as {@code controller/PersonController} maps it. */
    private static final String ALIAS = "/person/*";

    /** The item listener's pattern, which a request matched by {@link #ALIAS} is held to. */
    private static final String LISTENER_PATTERN = "/person/{personId}";

    private final RawPathSegmentConfig.RawPathSegmentInterceptor interceptor =
            new RawPathSegmentConfig.RawPathSegmentInterceptor(Map.of(ALIAS, LISTENER_PATTERN));

    /**
     * A segment carrying {@code ;} parameters that is not bound to a single {@code {name}} variable, or a request
     * whose pattern is unknown or does not pair up with its path, gets the {@link NoHandlerFoundException} of a path
     * no handler serves, and the variables Spring bound are left as they were.
     *
     * @param uri     the raw request URI
     * @param pattern the pattern the selected handler matched; empty for a request without one
     */
    @ParameterizedTest(name = "{0} matched as ''{1}''")
    @CsvSource({
            "/person;x=1, /person",
            "/person;x=1/1, /person/{personId}",
            "/person;x=1, ''",
            "/static/a;x=1/b.css, /**",
            "/files;x=1, /{*path}",
            "/person/1;x=1, /person/{personId:\\d+}",
            "/person/1;x=1.json, /person/{personId}.json",
            "/person/a;x=1/1, /person/*/{personId}"})
    void segmentParametersOutsideASingleVariableAreAnUnservedPath(String uri, String pattern) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        if (!pattern.isEmpty()) {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        }
        Map<String, String> bound = Map.of("personId", "1");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, bound);
        request.addHeader("X-Probe", "kept");

        NoHandlerFoundException ex = assertThrows(NoHandlerFoundException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals("POST", ex.getHttpMethod());
        assertEquals(uri, ex.getRequestURL());
        assertEquals(List.of("kept"), ex.getRequestHeaders().get("X-Probe"));
        assertSame(bound, request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE));
    }

    @Test
    void variableSegmentIsBoundWholeAndDecodedWhileOtherVariablesKeepSpringsValues() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/teams/a%20b/players/7;x=%41;y");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/teams/{team}/players/{player}");
        Map<String, String> bound = new LinkedHashMap<>();
        bound.put("team", "a b");
        bound.put("player", "7");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, bound);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        Object rebound = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        assertEquals(Map.of("team", "a b", "player", "7;x=A;y"), rebound);
        assertEquals(List.of("team", "player"), List.copyOf(((Map<?, ?>) rebound).keySet()));
        assertEquals(Map.of("team", "a b", "player", "7"), bound, "Spring's own map must not be modified");
    }

    @Test
    void pathIsPairedWithThePatternWithinTheContextPath() throws Exception {
        MockHttpServletRequest bound = new MockHttpServletRequest("GET", "/app/person/1;x=1");
        bound.setContextPath("/app");
        bound.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/person/{personId}");
        bound.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("personId", "1"));

        assertTrue(interceptor.preHandle(bound, new MockHttpServletResponse(), new Object()));
        assertEquals(Map.of("personId", "1;x=1"), bound.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE));

        MockHttpServletRequest literal = new MockHttpServletRequest("GET", "/app/person;x=1");
        literal.setContextPath("/app");
        literal.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/person");

        NoHandlerFoundException ex = assertThrows(NoHandlerFoundException.class,
                () -> interceptor.preHandle(literal, new MockHttpServletResponse(), new Object()));
        assertEquals("/app/person;x=1", ex.getRequestURL());
    }

    /**
     * A request matched by the alias, which Spring selects for an id segment that is empty before its {@code ;}, is
     * held to the listener pattern: every variable of it is bound to its raw segment, decoded, in a new map, and the
     * matched pattern becomes the listener pattern.
     *
     * @param uri      the raw request URI
     * @param personId the value {@code personId} is bound to
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"/person/;x=1, ;x=1", "/person/;, ;", "/person/;x=%41;y, ;x=A;y"})
    void aliasMatchIsBoundToTheListenerPatternsRawSegments(String uri, String personId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, ALIAS);
        Map<String, String> bound = Map.of();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, bound);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(Map.of("personId", personId),
                request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE));
        assertEquals(LISTENER_PATTERN, request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        assertEquals(Map.of(), bound, "Spring's own map must not be modified");
    }

    /**
     * A request matched by the alias whose URI does not pair with the listener pattern, whose id segment is empty, or
     * whose literal segment carries {@code ;} gets the {@link NoHandlerFoundException} of a path no handler serves,
     * with or without {@code ;} in its URI, and its attributes are left as Spring set them.
     *
     * @param uri the raw request URI
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/person/", "/person;x=1/;x=1", "/person/;x=1/1"})
    void aliasMatchNotPairingWithTheListenerPatternIsAnUnservedPath(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", uri);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, ALIAS);
        Map<String, String> bound = Map.of();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, bound);

        NoHandlerFoundException ex = assertThrows(NoHandlerFoundException.class,
                () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals("OPTIONS", ex.getHttpMethod());
        assertEquals(uri, ex.getRequestURL());
        assertEquals(ALIAS, request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        assertSame(bound, request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE));
    }

    /**
     * A URI without {@code ;}, including one whose query carries it, runs its handler with every attribute as
     * Spring set it, whether or not a pattern was matched.
     *
     * @param uri     the raw request URI
     * @param pattern the pattern the selected handler matched; empty for a request without one
     */
    @ParameterizedTest(name = "{0} matched as ''{1}''")
    @CsvSource({"/person, /person", "/person/1, /person/{personId}", "/unknown, ''"})
    void uriWithoutSegmentParametersRunsItsHandlerUntouched(String uri, String pattern) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setQueryString("a=1;b");
        if (!pattern.isEmpty()) {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        }
        Map<String, String> bound = Map.of("personId", "1");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, bound);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertSame(bound, request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE));
    }
}
