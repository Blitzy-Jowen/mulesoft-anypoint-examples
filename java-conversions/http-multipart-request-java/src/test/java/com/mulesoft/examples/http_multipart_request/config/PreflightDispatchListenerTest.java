/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_multipart_request.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
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
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.mulesoft.examples.http_multipart_request.controller.UploadController;
import com.mulesoft.examples.http_multipart_request.exception.GlobalExceptionHandler;
import com.mulesoft.examples.http_multipart_request.service.UploadService;
import com.mulesoft.examples.http_multipart_request.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the {@code /uploadFile} listeners, which together accept
 * {@code GET} and {@code POST} only: a CORS pre-flight is answered exactly as a plain {@code OPTIONS} request is, with
 * the listener's 405 {@code text/plain} {@code Method not allowed for endpoint: /uploadFile} and no
 * {@code Access-Control-*} or {@code Vary} header, and neither flow runs, whatever origin it comes from and whatever
 * method it asks for, because the original listeners apply no CORS policy
 * (http-multipart-request/src/main/app/http-multipart-request.xml:8,15).
 *
 * <p>The cases run the real controller and SC-1 handler on Undertow ({@link WireConfig}, random port) with
 * {@link UploadService} mocked, so any form rendering or upload would be recorded, and send requests through ST-1
 * {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the listener writes them.
 */
@SpringBootTest(classes = PreflightDispatchListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to IPv4 loopback whatever server.address the project's application.yml sets.
        properties = "server.address=127.0.0.1")
class PreflightDispatchListenerTest {

    private static final String PATH = "/uploadFile";

    private static final String ORIGIN = "https://example.invalid";

    private static final String BOUNDARY = "preflight-boundary-0001";

    @MockBean
    private UploadService uploadService;

    @LocalServerPort
    private int port;

    /**
     * Origin of the pre-flight, its requested method, then its {@code Access-Control-Request-Headers}, or
     * {@code null}; every case is sent from each of two origins.
     */
    private static Stream<Arguments> preflights() {
        return Stream.of(ORIGIN, "http://a.example").flatMap(origin -> Stream.of(
                Arguments.of(origin, "GET", null),
                Arguments.of(origin, "POST", null),
                Arguments.of(origin, "PUT", null),
                Arguments.of(origin, "DELETE", null),
                Arguments.of(origin, "PATCH", null),
                Arguments.of(origin, "OPTIONS", null),
                Arguments.of(origin, "POST", "content-type")));
    }

    @ParameterizedTest(name = "Origin {0}, Access-Control-Request-Method {1}, Access-Control-Request-Headers {2}")
    @MethodSource("preflights")
    void preflightGetsTheListenerMethod405LikeAPlainOptionsRequest(String origin, String requestedMethod,
                                                                   String requestedHeaders) {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", PATH, null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", PATH,
                preflightHeaders(origin, requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 405 Method Not Allowed", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("Method not allowed for endpoint: /uploadFile".getBytes(StandardCharsets.UTF_8),
                preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(uploadService);
    }

    @Test
    void crossOriginGetStillRendersTheForm() {
        String form = "<html><body>form</body></html>";
        when(uploadService.uploadForm()).thenReturn(form);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", ORIGIN);

        HttpTestClient.Response response = HttpTestClient.localhost(port).send("GET", PATH, headers, null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertArrayEquals(form.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
        assertNoCorsHeaders(response);
        verify(uploadService).uploadForm();
        verifyNoMoreInteractions(uploadService);
    }

    @Test
    void crossOriginPostStillReachesTheUpload() throws Exception {
        byte[] content = "file uploaded".getBytes(StandardCharsets.UTF_8);
        when(uploadService.upload(argThat(part -> part != null && "file".equals(part.getName()))))
                .thenReturn(content);
        byte[] body = ("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "file uploaded\r\n"
                + "--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", ORIGIN);
        headers.put("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);

        HttpTestClient.Response response = HttpTestClient.localhost(port).send("POST", PATH, headers, body);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertArrayEquals(content, response.body(), response::toString);
        assertNoCorsHeaders(response);
        verify(uploadService).upload(argThat(part -> part != null && "file".equals(part.getName())));
        verifyNoMoreInteractions(uploadService);
    }

    @Test
    void preflightToAnUnservedPathGetsTheNoListener404() {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", "/unknown", null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", "/unknown",
                preflightHeaders(ORIGIN, "POST", null), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("No listener for endpoint: /unknown".getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(uploadService);
    }

    private static Map<String, String> preflightHeaders(String origin, String requestedMethod,
                                                        String requestedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", origin);
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
     * The test's whole context: Spring Boot's servlet web stack on Undertow with multipart support,
     * {@link PreflightDispatchConfig} and the listener's own beans, with {@link UploadService} supplied by the test as
     * a mock.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listener alone; and it is not a {@code @SpringBootConfiguration}, so the project's other
     * {@code @SpringBootTest} classes still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightDispatchConfig.class, UploadController.class, GlobalExceptionHandler.class})
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            ServletWebServerFactoryAutoConfiguration.class,
            EmbeddedWebServerFactoryCustomizerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            HttpEncodingAutoConfiguration.class,
            MultipartAutoConfiguration.class})
    static class WireConfig {
    }
}
