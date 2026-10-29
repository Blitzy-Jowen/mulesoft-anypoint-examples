/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Unit test of {@link ApiKitAcceptNegotiator}: {@link ApiKitAcceptNegotiator#bestMatch} chooses the declared type
 * APIkit 3.8.0's {@code RestContentTypeParser.bestMatch} chose for each {@code Accept} header, quirks included, and
 * {@link ApiKitAcceptNegotiator#negotiate} rejects with {@link HttpMediaTypeNotAcceptableException}, carrying the
 * declared types, exactly the requests whose selected handler method declares {@code produces} and for which none of
 * the declared types is chosen, and refuses a request for which Spring MVC selected no handler method.
 *
 * <p>The RAML-backed projects carry copies of this class that differ from this one in the {@code package} line
 * alone.
 */
class ApiKitAcceptNegotiatorTest {

    private static final String JSON = "application/json";

    private static final String TEXT = "text/plain";

    private static final List<String> JSON_ONLY = List.of(JSON);

    private static final List<String> JSON_THEN_TEXT = List.of(JSON, TEXT);

    private static final String JSON_UTF8 = "application/json;charset=UTF-8";

    private static final List<String> JSON_UTF8_ONLY = List.of(JSON_UTF8);

    /** A header that gives the probe's JSON quality 0. */
    private static final String JSON_Q0 = "application/json;q=0";

    /**
     * The declared types, the {@code Accept} header, then the type APIkit chose, {@code null} where it raised its
     * 406.
     */
    private static Stream<Arguments> acceptHeaders() {
        return Stream.of(
                // JSON at quality 0, however written or combined
                Arguments.of(JSON_ONLY, JSON_Q0, null),
                Arguments.of(JSON_ONLY, "application/json;q=0.0", null),
                Arguments.of(JSON_ONLY, "application/json;q=0.000", null),
                Arguments.of(JSON_ONLY, "application/json; q=0", null),
                Arguments.of(JSON_ONLY, "application/json;q= 0 ", null),
                Arguments.of(JSON_ONLY, "text/xml, application/json;q=0", null),
                Arguments.of(JSON_ONLY, "application/*;q=0", null),
                Arguments.of(JSON_ONLY, "*/json;q=0", null),
                // The fittest range decides, so the exact JSON range outweighs */* in either order
                Arguments.of(JSON_ONLY, "application/json;q=0, */*", null),
                Arguments.of(JSON_ONLY, "*/*, application/json;q=0", null),
                // Of equally fit ranges the first decides
                Arguments.of(JSON_ONLY, "application/json;q=0, application/json", null),
                Arguments.of(JSON_ONLY, "application/json, application/json;q=0", JSON),
                // Of two q parameters the later counts; a q that is not a number reads as 0
                Arguments.of(JSON_ONLY, "application/json;q=1;q=0", null),
                Arguments.of(JSON_ONLY, "application/json;q=0;q=1", JSON),
                Arguments.of(JSON_ONLY, "application/json;q=abc", null),
                // No range matches: types are compared case-sensitively, and commas alone hold no range
                Arguments.of(JSON_ONLY, "text/xml", null),
                Arguments.of(JSON_ONLY, "APPLICATION/JSON", null),
                Arguments.of(JSON_ONLY, "application/JSON", null),
                Arguments.of(JSON_ONLY, ",,", null),
                // A */* range lends the type its own weight, so its q of 0 is ignored
                Arguments.of(JSON_ONLY, "*/*;q=0", JSON),
                Arguments.of(JSON_ONLY, "*;q=0", JSON),
                Arguments.of(JSON_ONLY, "*/*", JSON),
                Arguments.of(JSON_ONLY, "*", JSON),
                // Acceptable ranges and qualities
                Arguments.of(JSON_ONLY, JSON, JSON),
                Arguments.of(JSON_ONLY, "application/*", JSON),
                Arguments.of(JSON_ONLY, "*/json", JSON),
                Arguments.of(JSON_ONLY, "application/json;q=0.001", JSON),
                Arguments.of(JSON_ONLY, "application/json;q=0.5", JSON),
                Arguments.of(JSON_ONLY, "application/json;q=1", JSON),
                Arguments.of(JSON_ONLY, "application/json;charset=UTF-8", JSON),
                Arguments.of(JSON_ONLY, "text/xml, application/json", JSON),
                Arguments.of(JSON_ONLY, "text/xml;q=1, application/json;q=0.1", JSON),
                // A q outside 0..1 or blank reads as 1; parameter names are case-sensitive; -0 is not 0
                Arguments.of(JSON_ONLY, "application/json;q=-1", JSON),
                Arguments.of(JSON_ONLY, "application/json;q=1.5", JSON),
                Arguments.of(JSON_ONLY, "application/json;q=", JSON),
                Arguments.of(JSON_ONLY, "application/json;Q=0", JSON),
                Arguments.of(JSON_ONLY, "application/json;q=-0", JSON),
                // A range without a type and subtype is ignored
                Arguments.of(JSON_ONLY, "application/json, ", JSON),
                Arguments.of(JSON_ONLY, "text, application/json", JSON),
                Arguments.of(JSON_ONLY, "/json, application/json", JSON),
                Arguments.of(JSON_ONLY, "application/json,;", JSON),
                Arguments.of(JSON_ONLY, "application/json;q= ", JSON),
                Arguments.of(JSON_ONLY, "text", null),
                Arguments.of(JSON_ONLY, ";", null),
                // A range repeating a declared type's parameter is fitter than one without it
                Arguments.of(JSON_UTF8_ONLY, JSON, JSON_UTF8),
                Arguments.of(JSON_UTF8_ONLY, "application/json, application/json;charset=UTF-8;q=0", null),
                Arguments.of(JSON_UTF8_ONLY, "application/json;q=0, application/json;charset=UTF-8", JSON_UTF8),
                Arguments.of(JSON_UTF8_ONLY, "application/json;charset=ISO-8859-1;q=0, application/json", null),
                // The first declared type is weighed 1 and every later one 0.5
                Arguments.of(JSON_THEN_TEXT, "*/*", JSON),
                Arguments.of(List.of(TEXT, JSON), "*/*", TEXT),
                Arguments.of(JSON_THEN_TEXT, "*/*;q=0", JSON),
                Arguments.of(JSON_THEN_TEXT, TEXT, TEXT),
                Arguments.of(JSON_THEN_TEXT, "application/json;q=0, text/plain", TEXT),
                Arguments.of(JSON_THEN_TEXT, "application/json;q=0.4, text/plain;q=0.6", TEXT),
                Arguments.of(JSON_THEN_TEXT, "application/json;q=0.6, text/plain;q=0.4", JSON),
                // Fitness ranks before quality, and of equal entries the later declared one wins
                Arguments.of(JSON_THEN_TEXT, "application/json;q=0, */*", null),
                Arguments.of(JSON_THEN_TEXT, "text/plain, application/json", TEXT),
                Arguments.of(JSON_THEN_TEXT, "text/xml", null));
    }

    @ParameterizedTest(name = "{0} with Accept [{1}] -> {2}")
    @MethodSource("acceptHeaders")
    void bestMatchChoosesTheTypeApiKitChose(List<String> supported, String header, String expected) {
        assertEquals(expected, ApiKitAcceptNegotiator.bestMatch(supported, header));
    }

    /** Declared type lists no mapping can hold: none at all, or a type without type and subtype. */
    private static Stream<List<String>> undeclarableTypes() {
        return Stream.of(List.of(), List.of("text"), List.of(JSON, "/json"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("undeclarableTypes")
    void bestMatchRefusesTypesNoMappingDeclares(List<String> supported) {
        assertThrows(IllegalArgumentException.class, () -> ApiKitAcceptNegotiator.bestMatch(supported, "*/*"));
    }

    /**
     * The probe handler method selected for a request and the request's {@code Accept} header lines, then the types
     * the rejection carries, {@code null} where the request passes.
     */
    private static Stream<Arguments> negotiatedRequests() {
        return Stream.of(
                // Absent, blank and wildcard headers accept the declared type
                Arguments.of(probe("json"), List.of(), null),
                Arguments.of(probe("json"), List.of(""), null),
                Arguments.of(probe("json"), List.of("  "), null),
                Arguments.of(probe("json"), List.of("*/*"), null),
                Arguments.of(probe("json"), List.of(JSON_Q0), JSON_ONLY),
                // Several header lines are read as one value joined by commas
                Arguments.of(probe("json"), List.of("text/xml", JSON_Q0), JSON_ONLY),
                Arguments.of(probe("json"), List.of(JSON_Q0, JSON), JSON_ONLY),
                Arguments.of(probe("json"), List.of(JSON, JSON_Q0), null),
                Arguments.of(probe("json"), List.of("text/xml", JSON), null),
                // Two declared types: the rejection carries both
                Arguments.of(probe("jsonOrText"), List.of(TEXT), null),
                Arguments.of(probe("jsonOrText"), List.of(JSON_Q0 + ", " + TEXT), null),
                Arguments.of(probe("jsonOrText"), List.of("text/xml"), JSON_THEN_TEXT),
                // Negated expressions are no declared type
                Arguments.of(probe("negated"), List.of(JSON_Q0), JSON_ONLY),
                Arguments.of(probe("negated"), List.of(JSON), null),
                // Handlers without produces are not negotiated
                Arguments.of(probe("plain"), List.of(JSON_Q0), null),
                Arguments.of(probe("rejectUndeclaredMethod"), List.of(JSON_Q0), null),
                // A method without produces inherits its class's; a method's own produces replace them
                Arguments.of(typeLevelProbe("inherited"), List.of(JSON_Q0), JSON_ONLY),
                Arguments.of(typeLevelProbe("inherited"), List.of(TEXT + ", " + JSON_Q0), JSON_ONLY),
                Arguments.of(typeLevelProbe("own"), List.of(TEXT + ", " + JSON_Q0), null),
                Arguments.of(typeLevelProbe("own"), List.of(JSON), List.of(TEXT)));
    }

    @ParameterizedTest(name = "{0} with Accept {1} -> rejected with {2}")
    @MethodSource("negotiatedRequests")
    void selectedHandlerDeclaringProducesIsNegotiated(HandlerMethod handler, List<String> acceptLines,
                                                      List<String> rejectedWith) {
        MockHttpServletRequest request = requestHandledBy(handler);
        acceptLines.forEach(line -> request.addHeader("Accept", line));

        if (rejectedWith == null) {
            assertDoesNotThrow(() -> ApiKitAcceptNegotiator.negotiate(request));
        } else {
            HttpMediaTypeNotAcceptableException rejection = assertThrows(HttpMediaTypeNotAcceptableException.class,
                    () -> ApiKitAcceptNegotiator.negotiate(request));
            assertEquals(MediaType.parseMediaTypes(rejectedWith), rejection.getSupportedMediaTypes());
        }
    }

    @Test
    void requestWhoseContainerHidesItsHeadersIsReadAsAcceptingAnyType() {
        MockHttpServletRequest request = requestHandledBy(probe("json"));
        request.addHeader("Accept", JSON_Q0);
        HttpServletRequest hidingHeaders = new HttpServletRequestWrapper(request) {
            @Override
            public Enumeration<String> getHeaders(String name) {
                return null;
            }
        };

        assertDoesNotThrow(() -> ApiKitAcceptNegotiator.negotiate(hidingHeaders));
    }

    /**
     * Values of {@link HandlerMapping#BEST_MATCHING_HANDLER_ATTRIBUTE} that name no handler method: none at all, a
     * plain object, a bean name and a controller instance.
     */
    private static Stream<Arguments> handlersThatAreNoHandlerMethod() {
        return Stream.of(
                Arguments.of((Object) null),
                Arguments.of(new Object()),
                Arguments.of("probeController"),
                Arguments.of(new ProbeController()));
    }

    @ParameterizedTest(name = "selected handler {0}")
    @MethodSource("handlersThatAreNoHandlerMethod")
    void requestWithoutSelectedHandlerMethodIsRefused(Object handler) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/probe");
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, handler);
        request.addHeader("Accept", JSON_Q0);

        assertThrows(IllegalStateException.class, () -> ApiKitAcceptNegotiator.negotiate(request));
    }

    /** A request to {@code /api/probe} for which Spring MVC selected {@code handler}, as the dispatcher records it. */
    private static MockHttpServletRequest requestHandledBy(HandlerMethod handler) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/probe");
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, handler);
        return request;
    }

    /** The handler method {@code name} of a {@link ProbeController}. */
    private static HandlerMethod probe(String name) {
        return handlerMethod(new ProbeController(), name);
    }

    /** The handler method {@code name} of a {@link TypeLevelProbeController}. */
    private static HandlerMethod typeLevelProbe(String name) {
        return handlerMethod(new TypeLevelProbeController(), name);
    }

    private static HandlerMethod handlerMethod(Object controller, String name) {
        try {
            return new HandlerMethod(controller, controller.getClass().getMethod(name));
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("No probe method " + name, e);
        }
    }

    /** Handlers whose mappings declare their types on the method, as the RAML controllers do. */
    static class ProbeController {

        @GetMapping(value = "/api/probe", produces = JSON)
        public String json() {
            return "{}";
        }

        @GetMapping(value = "/api/probe", produces = {JSON, TEXT})
        public String jsonOrText() {
            return "{}";
        }

        @GetMapping(value = "/api/probe", produces = {"!text/xml", JSON})
        public String negated() {
            return "{}";
        }

        @GetMapping("/api/probe")
        public String plain() {
            return "";
        }

        @RequestMapping(value = "/api/probe", method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
        public void rejectUndeclaredMethod() {
            // The RAML controllers throw their 405 here, declare no produces and never negotiate.
        }
    }

    /** Handlers whose class mapping declares the type. */
    @RequestMapping(value = "/api/probe", produces = JSON)
    static class TypeLevelProbeController {

        @GetMapping
        public String inherited() {
            return "{}";
        }

        @GetMapping(produces = TEXT)
        public String own() {
            return "";
        }
    }
}
