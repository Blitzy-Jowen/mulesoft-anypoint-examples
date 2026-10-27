/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_expression_language_basics.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;

import com.mulesoft.examples.mule_expression_language_basics.controller.GreetingController;
import com.mulesoft.examples.mule_expression_language_basics.exception.GlobalExceptionHandler;
import com.mulesoft.examples.mule_expression_language_basics.mapper.DwCsvWriter;
import com.mulesoft.examples.mule_expression_language_basics.mapper.GreetingCsvMapper;
import com.mulesoft.examples.mule_expression_language_basics.service.FileOutboundWriter;
import com.mulesoft.examples.mule_expression_language_basics.service.GreetingService;
import com.mulesoft.examples.mule_expression_language_basics.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the six listeners {@code /greet1} to {@code /greet6}: a CORS
 * pre-flight runs the flow and is answered exactly as a plain {@code OPTIONS} request is, 200 {@code Hello Mule} with
 * its {@code Content-Length} and no {@code Content-Type}, as the Mule listener sent each flow's untyped
 * {@code set-payload}, with no {@code Access-Control-*} or {@code Vary} header, and, for {@code greet3} to
 * {@code greet6}, one new file per call, because the original listeners declare no {@code allowedMethods} and apply
 * no CORS policy (mule-expression-language-basics/src/main/app/greeting.xml:4-83).
 *
 * <p>The cases run the real controller, service, mapper, file writer and SC-1 handler on Undertow
 * ({@link WireConfig}, random port), with {@code greeting.output-dir} under a JUnit temporary directory, and send
 * requests through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the
 * listener writes them.
 */
@SpringBootTest(classes = PreflightDispatchListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreflightDispatchListenerTest {

    private static final String ORIGIN = "https://example.invalid";

    /** The query of the original test's {@code greet1} to {@code greet4} requests. */
    private static final String QUERY = "?username=Mule&age=1";

    private static final byte[] REPLY = "Hello Mule".getBytes(StandardCharsets.UTF_8);

    private static final String DIR_NAME = "Path_of_your_choice";

    @TempDir
    static Path tempDir;

    @LocalServerPort
    private int port;

    /**
     * Binds the flows' output directory to {@link #outputDir()}. The supplier is read when the application context
     * refreshes, after JUnit has injected the static {@link #tempDir}.
     */
    @DynamicPropertySource
    static void registerOutputDir(DynamicPropertyRegistry registry) {
        registry.add("greeting.output-dir", () -> outputDir().toString());
    }

    /** Leaves no file of one case for the count of the next. */
    @AfterEach
    void clean() throws IOException {
        FileSystemUtils.deleteRecursively(outputDir());
    }

    /**
     * Request target, then the test resource sent as the body or {@code null} for none, then whether the flow writes
     * a file, then the requested method of the pre-flight, then its {@code Access-Control-Request-Headers}, or
     * {@code null}: every pre-flight is sent to every listener.
     */
    private static Stream<Arguments> preflights() {
        return Stream.of(
                        Arguments.of("/greet1" + QUERY, null, false),
                        Arguments.of("/greet2" + QUERY, null, false),
                        Arguments.of("/greet3" + QUERY, null, true),
                        Arguments.of("/greet4" + QUERY, null, true),
                        Arguments.of("/greet5", "/message.xml", true),
                        Arguments.of("/greet6", "/message.json", true))
                .flatMap(listener -> Stream.of(
                                Arguments.of("GET", null),
                                Arguments.of("POST", null),
                                Arguments.of("PUT", null),
                                Arguments.of("DELETE", null),
                                Arguments.of("PATCH", null),
                                Arguments.of("OPTIONS", null),
                                Arguments.of("POST", "content-type"))
                        .map(preflight -> Arguments.of(listener.get()[0], listener.get()[1], listener.get()[2],
                                preflight.get()[0], preflight.get()[1])));
    }

    @ParameterizedTest(name = "{0}: Access-Control-Request-Method {3}, Access-Control-Request-Headers {4}")
    @MethodSource("preflights")
    void preflightRunsTheFlowLikeAPlainOptionsRequest(String target, String bodyResource, boolean writesFile,
                                                      String requestedMethod, String requestedHeaders)
            throws IOException {
        byte[] body = bodyResource == null ? null : resource(bodyResource);
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", target, null, body);
        assertEquals(writesFile ? 1 : 0, fileCount(), plain::toString);

        HttpTestClient.Response preflight = client.send("OPTIONS", target,
                preflightHeaders(requestedMethod, requestedHeaders), body);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 200 OK", preflight.statusLine(), preflight::toString);
        assertNull(preflight.header("Content-Type"), preflight::toString);
        assertEquals(List.of(String.valueOf(REPLY.length)), preflight.headers("Content-Length"),
                preflight::toString);
        assertArrayEquals(REPLY, preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
        // The pre-flight wrote its own file, as the plain OPTIONS request did.
        assertEquals(writesFile ? 2 : 0, fileCount(), preflight::toString);
    }

    @Test
    void preflightToAnUnservedPathGetsTheNoListener404() throws IOException {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", "/greet7", null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", "/greet7", preflightHeaders("POST", null), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("No listener for endpoint: /greet7".getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        assertEquals(0, fileCount(), preflight::toString);
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

    /** The number of entries in the output directory; the file names are random, so only the count is checked. */
    private static long fileCount() throws IOException {
        Path dir = outputDir();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> listing = Files.list(dir)) {
            return listing.count();
        }
    }

    private static Path outputDir() {
        return tempDir.resolve(DIR_NAME);
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = PreflightDispatchListenerTest.class.getResourceAsStream(name)) {
            assertNotNull(in, () -> "missing test resource " + name);
            return in.readAllBytes();
        }
    }

    /**
     * The test's whole context: Spring Boot's servlet web stack on Undertow, {@link PreflightDispatchConfig} and the
     * listeners' own beans.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listeners and nothing else; and it is not a {@code @SpringBootConfiguration}, so the
     * project's other {@code @SpringBootTest} classes still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightDispatchConfig.class, GreetingController.class, GreetingService.class, GreetingCsvMapper.class,
            DwCsvWriter.class, FileOutboundWriter.class, GlobalExceptionHandler.class})
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
