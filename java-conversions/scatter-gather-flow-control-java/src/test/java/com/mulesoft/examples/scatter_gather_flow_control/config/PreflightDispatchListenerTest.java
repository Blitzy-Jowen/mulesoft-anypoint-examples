/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.HttpEncodingAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.mulesoft.examples.scatter_gather_flow_control.controller.ScatterGatherController;
import com.mulesoft.examples.scatter_gather_flow_control.exception.GlobalExceptionHandler;
import com.mulesoft.examples.scatter_gather_flow_control.service.ScatterGatherService;
import com.mulesoft.examples.scatter_gather_flow_control.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the {@code /scatterGather} listener: a CORS pre-flight runs
 * the flow and is answered exactly as a plain {@code OPTIONS} request is, 200 with the payload of
 * {@code outboundFlow}, its {@code Content-Length} and no {@code Content-Type}, as the Mule listener sent the flow's
 * untyped {@code set-payload}, and with no {@code Access-Control-*} or {@code Vary} header, because the original
 * listener declares no {@code allowedMethods} and applies no CORS policy
 * (scatter-gather-flow-control/src/main/app/scatter-gather.xml:7).
 *
 * <p>The cases run the real controller and SC-1 handler on Undertow ({@link WireConfig}, random port) and send
 * requests through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the
 * listener writes them. {@link ScatterGatherService}, which runs both routes and mails the report, is a mock
 * returning the flow's response payload (scatter-gather.xml:63); each request's single call to it shows that the
 * flow ran.
 */
@SpringBootTest(classes = PreflightDispatchListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreflightDispatchListenerTest {

    private static final String PATH = "/scatterGather";

    /** Payload of {@code outboundFlow}'s {@code set-payload} (scatter-gather.xml:63), the flow's response. */
    private static final String REPORT_BODY = "Please find attached your Aggregation Report";

    private static final String ORIGIN = "https://example.invalid";

    @LocalServerPort
    private int port;

    @MockBean
    private ScatterGatherService scatterGatherService;

    @BeforeEach
    void stubTheFlow() {
        when(scatterGatherService.process()).thenReturn(REPORT_BODY);
    }

    /** Requested method of the pre-flight, then its {@code Access-Control-Request-Headers}, or {@code null}. */
    private static Stream<Arguments> preflights() {
        return Stream.of(
                Arguments.of("GET", null),
                Arguments.of("POST", null),
                Arguments.of("PUT", null),
                Arguments.of("DELETE", null),
                Arguments.of("PATCH", null),
                Arguments.of("OPTIONS", null),
                Arguments.of("POST", "content-type"));
    }

    @ParameterizedTest(name = "Access-Control-Request-Method {0}, Access-Control-Request-Headers {1}")
    @MethodSource("preflights")
    void preflightRunsTheFlowLikeAPlainOptionsRequest(String requestedMethod, String requestedHeaders) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", PATH, null, null);
        verify(scatterGatherService).process();

        HttpTestClient.Response preflight = client.send("OPTIONS", PATH,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        verify(scatterGatherService, times(2)).process();
        verifyNoMoreInteractions(scatterGatherService);
        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 200 OK", preflight.statusLine(), preflight::toString);
        assertNull(preflight.header("Content-Type"), preflight::toString);
        assertEquals("44", preflight.header("Content-Length"), preflight::toString);
        assertArrayEquals(REPORT_BODY.getBytes(StandardCharsets.UTF_8), preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
    }

    /**
     * The listener serves {@code /scatterGather} exactly, so a pre-flight to any other path, a sub-path included,
     * gets the no-listener 404 a plain {@code OPTIONS} request to it gets, without running the flow.
     *
     * @param path the unserved request path
     */
    @ParameterizedTest(name = "OPTIONS {0}")
    @ValueSource(strings = {"/unknown", "/scatterGather/extra"})
    void preflightToAnUnservedPathGetsTheNoListener404(String path) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", path, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", path, preflightHeaders("POST", null), null);

        verifyNoInteractions(scatterGatherService);
        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals(List.of("text/plain"), preflight.headers("Content-Type"), preflight::toString);
        assertArrayEquals(("No listener for endpoint: " + path).getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
    }

    /**
     * A cross-origin request that is not a pre-flight still runs the flow and gets no CORS processing.
     *
     * @param method the request method, sent with an {@code Origin} header only
     */
    @ParameterizedTest(name = "cross-origin {0} /scatterGather")
    @ValueSource(strings = {"GET", "POST"})
    void crossOriginRequestRunsTheFlowWithoutCorsHeaders(String method) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", ORIGIN);

        HttpTestClient.Response response = HttpTestClient.localhost(port).send(method, PATH, headers, null);

        verify(scatterGatherService).process();
        verifyNoMoreInteractions(scatterGatherService);
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertArrayEquals(REPORT_BODY.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
        assertNoCorsHeaders(response);
    }

    private static Map<String, String> preflightHeaders(String requestedMethod, String requestedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", ORIGIN);
        headers.put("Access-Control-Request-Method", requestedMethod);
        if (requestedHeaders != null) {
            headers.put("Access-Control-Request-Headers", requestedHeaders);
        }
        return headers;
    }

    /** Same status line, same body bytes and the same headers but {@code Date}, compared as sorted lines. */
    private static void assertSameResponse(HttpTestClient.Response expected, HttpTestClient.Response actual) {
        assertEquals(expected.statusLine(), actual.statusLine(), actual::toString);
        assertArrayEquals(expected.body(), actual.body(), actual::toString);
        assertEquals(headerLines(expected), headerLines(actual), actual::toString);
    }

    /** Every header but {@code Date} as {@code name: value} with the name lower-cased, sorted, duplicates kept. */
    private static List<String> headerLines(HttpTestClient.Response response) {
        return response.headers().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("Date"))
                .map(header -> header.getKey().toLowerCase(Locale.ROOT) + ": " + header.getValue())
                .sorted()
                .toList();
    }

    private static void assertNoCorsHeaders(HttpTestClient.Response response) {
        assertTrue(response.headers().stream().map(Map.Entry::getKey).noneMatch(name ->
                        name.regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length())
                                || name.equalsIgnoreCase("Vary")),
                response::toString);
    }

    /**
     * The test's whole context: Spring Boot's servlet web stack on Undertow, {@link PreflightDispatchConfig} and the
     * listener's own beans, with the test's mock {@link ScatterGatherService} in place of the service that runs the
     * routes and mails the report.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listener and nothing else, no mail sender included; and it is not a
     * {@code @SpringBootConfiguration}, so the project's other {@code @SpringBootTest} classes still find exactly one
     * application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightDispatchConfig.class, ScatterGatherController.class, GlobalExceptionHandler.class})
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            ServletWebServerFactoryAutoConfiguration.class,
            EmbeddedWebServerFactoryCustomizerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            HttpEncodingAutoConfiguration.class})
    static class WireConfig {
    }
}
