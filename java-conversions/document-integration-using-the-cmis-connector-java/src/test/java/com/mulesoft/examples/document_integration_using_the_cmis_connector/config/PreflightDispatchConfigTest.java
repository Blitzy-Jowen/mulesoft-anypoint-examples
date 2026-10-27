/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Tests the handler mapping of {@link PreflightDispatchConfig} directly, over {@link ProbeController} in a minimal web
 * application context.
 *
 * <p>A CORS pre-flight to which no CORS configuration applies selects the handler a plain {@code OPTIONS} request to
 * its path selects, never the handler of the method it asks for, and its chain carries no CORS processing; a
 * pre-flight to a handler with a CORS configuration is still answered by Spring; other requests are dispatched as
 * Spring dispatches them; and the request view used for the plain lookup hides the requested method only.
 *
 * <p>The class references no project type, so every project carries it with only its {@code package} line changed.
 */
class PreflightDispatchConfigTest {

    private static final String ORIGIN = "https://example.invalid";

    /** Mapped for {@code POST}, with an explicit {@code OPTIONS} mapping, like a listener that allows one method. */
    private static final String POST_ONLY = "/post-only";

    /** Mapped without methods, with an explicit {@code OPTIONS} mapping, like a listener that allows every method. */
    private static final String ANY_METHOD = "/any-method";

    /** Mapped for {@code POST} with a parameter condition, with an explicit {@code OPTIONS} mapping. */
    private static final String WITH_PARAMETER = "/with-parameter";

    /** Mapped for {@code POST} with a {@code @CrossOrigin} configuration. */
    private static final String CROSS_ORIGIN = "/cross-origin";

    private static GenericWebApplicationContext context;

    private static PreflightDispatchConfig.PreflightDispatchHandlerMapping mapping;

    @BeforeAll
    static void initMapping() {
        context = new GenericWebApplicationContext(new MockServletContext());
        context.registerBean(ProbeController.class);
        context.refresh();
        mapping = new PreflightDispatchConfig.PreflightDispatchHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();
    }

    @AfterAll
    static void closeContext() {
        context.close();
    }

    /** Path, requested method of the pre-flight, then the probe method a plain {@code OPTIONS} to the path selects. */
    private static Stream<Arguments> preflightsWithoutCorsConfiguration() {
        Stream<Arguments> postOnly = Stream.of("POST", "PUT", "GET", "DELETE", "PATCH", "OPTIONS")
                .map(method -> Arguments.of(POST_ONLY, method, "postOnlyOptions"));
        Stream<Arguments> anyMethod = Stream.of("POST", "PUT", "GET", "DELETE", "PATCH", "OPTIONS")
                .map(method -> Arguments.of(ANY_METHOD, method, "anyMethodOptions"));
        // Spring's own lookup rejects this pre-flight for the missing parameter; the plain lookup still selects the
        // OPTIONS mapping, as it does for a plain OPTIONS request.
        Stream<Arguments> withParameter = Stream.of(Arguments.of(WITH_PARAMETER, "POST", "withParameterOptions"));
        return Stream.of(postOnly, anyMethod, withParameter).flatMap(arguments -> arguments);
    }

    @ParameterizedTest(name = "{0}, Access-Control-Request-Method {1}")
    @MethodSource("preflightsWithoutCorsConfiguration")
    void preflightSelectsTheHandlerOfAPlainOptionsRequestWithoutCorsProcessing(String path, String requestedMethod,
                                                                              String expectedMethod) throws Exception {
        HandlerExecutionChain chain = mapping.getHandler(preflight(path, requestedMethod));

        assertNotNull(chain);
        HandlerMethod handler = assertInstanceOf(HandlerMethod.class, chain.getHandler());
        assertEquals(ProbeController.class, handler.getBeanType());
        assertEquals(expectedMethod, handler.getMethod().getName());
        assertEquals(List.of(), chain.getInterceptorList());
    }

    @Test
    void preflightToAHandlerWithCorsConfigurationIsStillAnsweredBySpring() throws Exception {
        MockHttpServletRequest request = preflight(CROSS_ORIGIN, "POST");

        HandlerExecutionChain chain = mapping.getHandler(request);

        // Spring's pre-flight handler, not the handler method, answers; its CORS processing runs as an interceptor,
        // so the chain is executed the way the dispatcher servlet executes it.
        assertNotNull(chain);
        HttpRequestHandler preFlightHandler = assertInstanceOf(HttpRequestHandler.class, chain.getHandler());
        MockHttpServletResponse response = new MockHttpServletResponse();
        for (HandlerInterceptor interceptor : chain.getInterceptorList()) {
            assertTrue(interceptor.preHandle(request, response, preFlightHandler));
        }
        preFlightHandler.handleRequest(request, response);
        assertEquals(200, response.getStatus());
        assertEquals(ORIGIN, response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("POST", response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
    }

    @Test
    void preflightToAnUnmappedPathSelectsNoHandler() throws Exception {
        assertNull(mapping.getHandler(preflight("/unmapped", "POST")));
    }

    /** Method, path and headers of a request that is not a pre-flight, then the probe method Spring selects. */
    private static Stream<Arguments> ordinaryRequests() {
        return Stream.of(
                Arguments.of("POST", POST_ONLY, Map.of(), "postOnly"),
                Arguments.of("POST", POST_ONLY, Map.of(HttpHeaders.ORIGIN, ORIGIN), "postOnly"),
                Arguments.of("OPTIONS", POST_ONLY, Map.of(), "postOnlyOptions"),
                Arguments.of("GET", ANY_METHOD, Map.of(), "anyMethod"),
                Arguments.of("OPTIONS", ANY_METHOD, Map.of(HttpHeaders.ORIGIN, ORIGIN), "anyMethodOptions"));
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("ordinaryRequests")
    void requestThatIsNotAPreflightIsDispatchedAsSpringDispatchesIt(String method, String path,
                                                                  Map<String, String> headers,
                                                                  String expectedMethod) throws Exception {
        HandlerExecutionChain chain = mapping.getHandler(request(method, path, headers));

        assertNotNull(chain);
        HandlerMethod handler = assertInstanceOf(HandlerMethod.class, chain.getHandler());
        assertEquals(expectedMethod, handler.getMethod().getName());
        assertEquals(List.of(), chain.getInterceptorList());
    }

    @Test
    void chainWithoutCorsConfigurationIsReturnedUnchanged() {
        Object handler = new Object();
        HandlerExecutionChain chain = new HandlerExecutionChain(handler);

        HandlerExecutionChain result = mapping.getCorsHandlerExecutionChain(preflight(POST_ONLY, "POST"), chain, null);

        assertSame(chain, result);
        assertSame(handler, result.getHandler());
        assertEquals(List.of(), result.getInterceptorList());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Access-Control-Request-Method", "access-control-request-method",
            "ACCESS-CONTROL-REQUEST-METHOD"})
    void plainOptionsRequestHidesTheRequestedMethodWhateverItsCase(String name) {
        PreflightDispatchConfig.PlainOptionsRequest plain =
                new PreflightDispatchConfig.PlainOptionsRequest(preflight(POST_ONLY, "POST"));

        assertNull(plain.getHeader(name));
        assertFalse(plain.getHeaders(name).hasMoreElements());
        assertEquals(-1, plain.getIntHeader(name));
        assertEquals(-1L, plain.getDateHeader(name));
        assertFalse(Collections.list(plain.getHeaderNames()).stream().anyMatch(name::equalsIgnoreCase));
    }

    @Test
    void plainOptionsRequestKeepsEverythingElse() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HttpHeaders.ORIGIN, ORIGIN);
        headers.put(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        headers.put(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type");
        headers.put("Max-Forwards", "10");
        MockHttpServletRequest request = request("OPTIONS", POST_ONLY, headers);

        PreflightDispatchConfig.PlainOptionsRequest plain = new PreflightDispatchConfig.PlainOptionsRequest(request);
        plain.setAttribute("probe", "set through the view");

        assertTrue(CorsUtils.isPreFlightRequest(request));
        assertFalse(CorsUtils.isPreFlightRequest(plain));
        assertEquals(List.of(HttpHeaders.ORIGIN, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Max-Forwards"),
                Collections.list(plain.getHeaderNames()));
        assertEquals(ORIGIN, plain.getHeader("origin"));
        assertEquals(List.of("content-type"),
                Collections.list(plain.getHeaders(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS)));
        assertEquals(10, plain.getIntHeader("Max-Forwards"));
        assertEquals("OPTIONS", plain.getMethod());
        assertEquals(POST_ONLY, plain.getRequestURI());
        assertEquals("set through the view", request.getAttribute("probe"));
        assertEquals("POST", request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD));
    }

    /** A CORS pre-flight to {@code path} from {@link #ORIGIN} asking for {@code requestedMethod}. */
    private static MockHttpServletRequest preflight(String path, String requestedMethod) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HttpHeaders.ORIGIN, ORIGIN);
        headers.put(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, requestedMethod);
        return request("OPTIONS", path, headers);
    }

    /**
     * A request with its path parsed and cached, as the dispatcher servlet caches it before consulting handler
     * mappings that use parsed path patterns.
     */
    private static MockHttpServletRequest request(String method, String path, Map<String, String> headers) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        headers.forEach(request::addHeader);
        ServletRequestPathUtils.parseAndCache(request);
        return request;
    }

    /** The mappings the cases look up; no handler here is invoked, so each returns its own name. */
    @RestController
    static class ProbeController {

        @PostMapping(POST_ONLY)
        String postOnly() {
            return "postOnly";
        }

        @RequestMapping(path = POST_ONLY, method = RequestMethod.OPTIONS)
        String postOnlyOptions() {
            return "postOnlyOptions";
        }

        @RequestMapping(ANY_METHOD)
        String anyMethod() {
            return "anyMethod";
        }

        @RequestMapping(path = ANY_METHOD, method = RequestMethod.OPTIONS)
        String anyMethodOptions() {
            return "anyMethodOptions";
        }

        @PostMapping(path = WITH_PARAMETER, params = "token")
        String withParameter() {
            return "withParameter";
        }

        @RequestMapping(path = WITH_PARAMETER, method = RequestMethod.OPTIONS)
        String withParameterOptions() {
            return "withParameterOptions";
        }

        @CrossOrigin(origins = ORIGIN)
        @PostMapping(CROSS_ORIGIN)
        String crossOrigin() {
            return "crossOrigin";
        }
    }
}
