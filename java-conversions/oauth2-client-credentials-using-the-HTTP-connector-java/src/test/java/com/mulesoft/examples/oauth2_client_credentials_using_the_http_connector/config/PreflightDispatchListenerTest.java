/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.controller.FimProxyController;
import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.exception.GlobalExceptionHandler;
import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.service.FimProxyService;
import com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the {@code /FIM/*} listener: a CORS pre-flight runs the flow
 * and is answered exactly as a plain {@code OPTIONS} request is, with the flow's response and no
 * {@code Access-Control-*} or {@code Vary} header, because the original listener declares no {@code allowedMethods}
 * and applies no CORS policy, and its flow forwards every method, {@code OPTIONS} included, to the FIM server
 * (oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:40-45).
 *
 * <p>The cases run the real controller and SC-1 handler on Undertow ({@link WireConfig}, random port) with
 * {@link FimProxyService} mocked, so each forward is recorded with the request it ran in and no FIM server is
 * called, and send requests through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are
 * asserted as the listener writes them. The flow forwards no inbound header, so the controller hands the service the
 * method, raw URI, Content-Type and body only; the pre-flight's own headers are read from the request the forward ran
 * in, to show that the handler received the pre-flight unchanged.
 */
@SpringBootTest(classes = PreflightDispatchListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreflightDispatchListenerTest {

    private static final String PATH = "/FIM/x";

    private static final String ORIGIN = "https://example.invalid";

    /** Content-Type of the mocked flow response. */
    private static final String REPLY_CONTENT_TYPE = "text/plain";

    /** Body of the mocked flow response. */
    private static final byte[] REPLY = "FIM OPTIONS reply".getBytes(StandardCharsets.UTF_8);

    @MockBean
    private FimProxyService fimProxyService;

    @LocalServerPort
    private int port;

    /** The request headers each forward ran with, in call order; written on the listener's worker thread. */
    private final List<ForwardedRequest> forwardedRequests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void recordEachForward() {
        when(fimProxyService.forward(any(), any(), any(), any())).thenAnswer(invocation -> {
            HttpServletRequest request =
                    ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
            forwardedRequests.add(new ForwardedRequest(request.getHeader(HttpHeaders.ORIGIN),
                    request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD)));
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, REPLY_CONTENT_TYPE).body(REPLY);
        });
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

        HttpTestClient.Response preflight = client.send("OPTIONS", PATH,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 200 OK", preflight.statusLine(), preflight::toString);
        assertEquals(REPLY_CONTENT_TYPE, preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals(REPLY, preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
        // One forward per request, each as OPTIONS to the same raw URI without Content-Type or body.
        verify(fimProxyService, times(2)).forward(eq("OPTIONS"), eq(PATH), isNull(), isNull());
        verifyNoMoreInteractions(fimProxyService);
        assertEquals(List.of(new ForwardedRequest(null, null), new ForwardedRequest(ORIGIN, requestedMethod)),
                forwardedRequests);
    }

    @Test
    void preflightToAnUnservedPathGetsTheNoListener404() {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", "/other", null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", "/other", preflightHeaders("POST", null), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("No listener for endpoint: /other".getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(fimProxyService);
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
     * The {@code Origin} and {@code Access-Control-Request-Method} headers of the request a forward ran in, each
     * {@code null} when the request did not carry it.
     */
    private record ForwardedRequest(String origin, String requestedMethod) {
    }

    /**
     * The test's whole context: Spring Boot's servlet web stack on Undertow, {@link PreflightDispatchConfig} and the
     * listener's own beans, with {@link FimProxyService} supplied by the test as a mock.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listener and no FIM or Box client; and it is not a {@code @SpringBootConfiguration}, so the
     * project's other {@code @SpringBootTest} classes still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightDispatchConfig.class, FimProxyController.class, GlobalExceptionHandler.class})
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
