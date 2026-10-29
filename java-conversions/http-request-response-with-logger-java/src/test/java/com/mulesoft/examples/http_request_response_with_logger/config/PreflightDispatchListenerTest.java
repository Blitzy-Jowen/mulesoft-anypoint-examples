/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_request_response_with_logger.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.HttpEncodingAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.mulesoft.examples.http_request_response_with_logger.controller.EchoController;
import com.mulesoft.examples.http_request_response_with_logger.exception.GlobalExceptionHandler;
import com.mulesoft.examples.http_request_response_with_logger.service.EchoService;
import com.mulesoft.examples.http_request_response_with_logger.support.HttpTestClient;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the {@code /*} listener: a CORS pre-flight runs the flow and
 * is answered exactly as a plain {@code OPTIONS} request to its path is, 200 with the request path, query string
 * excluded, as body and no {@code Content-Type}, as the Mule listener sent the flow's untyped {@code set-payload}, and
 * the path is logged as {@code About to echo <path>}, with no {@code Access-Control-*} or {@code Vary} header, because
 * the original listener declares no {@code allowedMethods} and applies no CORS policy
 * (http-request-response-with-logger/src/main/app/echo.xml:5-7).
 *
 * <p>The cases run the real controller, service and SC-1 handler on Undertow ({@link WireConfig}, random port) and
 * send requests through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the
 * listener writes them. The listener serves every path, so no pre-flight gets the no-listener 404. The service's log
 * line is captured by a Logback {@link ListAppender} attached to its logger for the duration of each case.
 */
@SpringBootTest(classes = PreflightDispatchListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreflightDispatchListenerTest {

    private static final String ORIGIN = "https://example.invalid";

    @LocalServerPort
    private int port;

    private Logger serviceLogger;

    private ListAppender<ILoggingEvent> serviceLog;

    @BeforeEach
    void captureServiceLog() {
        serviceLogger = (Logger) LoggerFactory.getLogger(EchoService.class);
        serviceLog = new ListAppender<>();
        serviceLog.start();
        serviceLogger.addAppender(serviceLog);
    }

    @AfterEach
    void releaseServiceLog() {
        serviceLogger.detachAppender(serviceLog);
        serviceLog.stop();
    }

    /**
     * Request target, then the echoed path, then the requested method of the pre-flight, then its
     * {@code Access-Control-Request-Headers}, or {@code null}: every pre-flight is sent to every target.
     */
    private static Stream<Arguments> preflights() {
        return Stream.of(
                        Arguments.of("/pre", "/pre"),
                        Arguments.of("/message", "/message"),
                        Arguments.of("/a/b?x=1", "/a/b"))
                .flatMap(target -> Stream.of(
                                Arguments.of("GET", null),
                                Arguments.of("POST", null),
                                Arguments.of("PUT", null),
                                Arguments.of("DELETE", null),
                                Arguments.of("PATCH", null),
                                Arguments.of("OPTIONS", null),
                                Arguments.of("POST", "content-type"))
                        .map(preflight -> Arguments.of(target.get()[0], target.get()[1], preflight.get()[0],
                                preflight.get()[1])));
    }

    @ParameterizedTest(name = "{0}: Access-Control-Request-Method {2}, Access-Control-Request-Headers {3}")
    @MethodSource("preflights")
    void preflightRunsTheFlowLikeAPlainOptionsRequest(String target, String echoedPath, String requestedMethod,
                                                      String requestedHeaders) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", target, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", target,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 200 OK", preflight.statusLine(), preflight::toString);
        assertNull(preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals(echoedPath.getBytes(StandardCharsets.UTF_8), preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
        // One line for the plain request, one for the pre-flight: each ran the flow's logger once.
        assertEquals(List.of("About to echo " + echoedPath, "About to echo " + echoedPath), loggedMessages());
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
     * The formatted messages the service logged so far, in order. The listener's worker threads append under the
     * appender's own lock, which this read takes too, so every line written before the response was read is seen.
     */
    private List<String> loggedMessages() {
        synchronized (serviceLog) {
            return serviceLog.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        }
    }

    /**
     * The test's whole context: Spring Boot's servlet web stack on Undertow, {@link PreflightDispatchConfig} and the
     * listener's own beans.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listener and nothing else; and it is not a {@code @SpringBootConfiguration}, so the
     * project's other {@code @SpringBootTest} classes still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightDispatchConfig.class, EchoController.class, EchoService.class, GlobalExceptionHandler.class})
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
