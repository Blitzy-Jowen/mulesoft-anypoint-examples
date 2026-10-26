package com.mulesoft.examples.hello_world.exception;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Tests shared component SC-1: {@link GlobalExceptionHandler} and {@link ErrorResponse}, in two layers that drive
 * the same nested {@link TestController}.
 *
 * <ul>
 *   <li>Unit layer: standalone MockMvc with the advice, checking each handler method, the reported path and the
 *       {@code ErrorResponse} escaping without a server.</li>
 *   <li>Wire layer: the controller and the advice on Undertow ({@link WireConfig}, random port on IPv4 loopback),
 *       called through the project's ST-1 client, so the raw status line with Undertow's standard reason phrase,
 *       the {@code Content-Type} header, the absent {@code Allow} header and the body bytes are asserted as the
 *       listener writes them, unknown-path dispatch by the real {@code DispatcherServlet} included.</li>
 * </ul>
 *
 * <p>Every project with a Spring MVC controller carries this class with only its {@code package} line changed,
 * so it depends on the JDK, JUnit, Spring Boot and the two SC-1 classes alone, and reaches ST-1 through
 * {@link WireClient}, which locates the project's {@code support.HttpTestClient} from this class's package.
 * Bodies are compared as exact bytes because the Mule listener's default texts and APIkit's JSON spacing are the
 * contract.
 */
@SpringBootTest(
        classes = GlobalExceptionHandlerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to the address ST-1 connects to, whatever server.address the project sets.
        properties = "server.address=" + GlobalExceptionHandlerTest.LOOPBACK)
class GlobalExceptionHandlerTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    @LocalServerPort
    int port;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void thrownNoHandlerFoundExceptionReturnsNoListener404() throws Exception {
        MvcResult result = perform(get("/test/no-handler"));

        assertEquals(404, result.getResponse().getStatus());
        assertContentType(result, MediaType.TEXT_PLAIN);
        assertBody(result, "No listener for endpoint: /test/no-handler");
    }

    @Test
    void thrownNoResourceFoundExceptionReturnsNoListener404() throws Exception {
        MvcResult result = perform(get("/test/no-resource"));

        assertEquals(404, result.getResponse().getStatus());
        assertContentType(result, MediaType.TEXT_PLAIN);
        assertBody(result, "No listener for endpoint: /test/no-resource");
    }

    @Test
    void unmappedPathReturnsNoListener404() throws Exception {
        MvcResult result = perform(get("/test/unmapped"));

        assertEquals(404, result.getResponse().getStatus());
        assertContentType(result, MediaType.TEXT_PLAIN);
        assertBody(result, "No listener for endpoint: /test/unmapped");
    }

    @Test
    void reportedPathExcludesContextPathAndQueryString() throws Exception {
        MvcResult result = perform(get("/app/test/unmapped?lang=es").contextPath("/app"));

        assertEquals(404, result.getResponse().getStatus());
        assertContentType(result, MediaType.TEXT_PLAIN);
        assertBody(result, "No listener for endpoint: /test/unmapped");
    }

    @Test
    void methodOutsideMappingReturns405() throws Exception {
        MvcResult result = perform(post("/test/get-only"));

        assertEquals(405, result.getResponse().getStatus());
        assertContentType(result, MediaType.TEXT_PLAIN);
        assertBody(result, "Method not allowed for endpoint: /test/get-only");
        // The Mule listener's 405 carried no Allow header, so the handler must not add one.
        assertNull(result.getResponse().getHeader(HttpHeaders.ALLOW));
    }

    @Test
    void unhandledExceptionReturns500JsonWithEscapedMessage() throws Exception {
        MvcResult result = perform(get("/test/illegal-state"));

        assertEquals(500, result.getResponse().getStatus());
        assertContentType(result, MediaType.APPLICATION_JSON);
        assertBody(result, "{ \"message\": \"boom \\\"q\\\"\" }");
    }

    @Test
    void unhandledExceptionWithoutMessageRendersNull() throws Exception {
        MvcResult result = perform(get("/test/null-message"));

        assertEquals(500, result.getResponse().getStatus());
        assertContentType(result, MediaType.APPLICATION_JSON);
        assertBody(result, "{ \"message\": null }");
    }

    @ParameterizedTest
    @MethodSource("errorResponseJsonCases")
    void errorResponseJsonEscapesAndHandlesNull(String message, String expectedJson) {
        assertEquals(expectedJson, ErrorResponse.json(message));
    }

    @Test
    void errorResponseMediaTypeIsApplicationJson() {
        assertEquals(MediaType.APPLICATION_JSON, ErrorResponse.MEDIA_TYPE);
    }

    @ParameterizedTest
    @MethodSource("platformDefaultErrorCases")
    void platformDefaultErrorsReachTheWire(String method, String path, String expectedStatusLine,
                                           String expectedContentType, String expectedBody) {
        WireResponse response = new WireClient(LOOPBACK, port).send(method, path);

        assertEquals(expectedStatusLine, response.statusLine(), response::toString);
        assertEquals(List.of(expectedContentType), response.headers(HttpHeaders.CONTENT_TYPE), response::toString);
        assertArrayEquals(expectedBody.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
        // The Mule listener sent no Allow header on these responses, so neither may the converted one.
        assertNull(response.header(HttpHeaders.ALLOW), response::toString);
    }

    /** Method, request target, then the status line, single {@code Content-Type} value and body expected. */
    private static Stream<Arguments> platformDefaultErrorCases() {
        return Stream.of(
                Arguments.of("GET", "/test/no-handler", "HTTP/1.1 404 Not Found", "text/plain",
                        "No listener for endpoint: /test/no-handler"),
                Arguments.of("GET", "/test/no-resource", "HTTP/1.1 404 Not Found", "text/plain",
                        "No listener for endpoint: /test/no-resource"),
                // No handler maps these targets: the DispatcherServlet's own unknown-path dispatch reaches SC-1.
                Arguments.of("GET", "/test/unmapped", "HTTP/1.1 404 Not Found", "text/plain",
                        "No listener for endpoint: /test/unmapped"),
                Arguments.of("GET", "/test/unmapped?lang=es", "HTTP/1.1 404 Not Found", "text/plain",
                        "No listener for endpoint: /test/unmapped"),
                Arguments.of("POST", "/test/get-only", "HTTP/1.1 405 Method Not Allowed", "text/plain",
                        "Method not allowed for endpoint: /test/get-only"),
                Arguments.of("GET", "/test/illegal-state", "HTTP/1.1 500 Internal Server Error", "application/json",
                        "{ \"message\": \"boom \\\"q\\\"\" }"),
                Arguments.of("GET", "/test/null-message", "HTTP/1.1 500 Internal Server Error", "application/json",
                        "{ \"message\": null }"));
    }

    private static Stream<Arguments> errorResponseJsonCases() {
        return Stream.of(
                Arguments.of("plain", "{ \"message\": \"plain\" }"),
                Arguments.of("a\"b\\c", "{ \"message\": \"a\\\"b\\\\c\" }"),
                Arguments.of("line1\nline2", "{ \"message\": \"line1\\nline2\" }"),
                Arguments.of("\b\f\r\t", "{ \"message\": \"\\b\\f\\r\\t\" }"),
                Arguments.of("\u0001\u001f", "{ \"message\": \"\\u0001\\u001f\" }"),
                Arguments.of("\u2028\u2029", "{ \"message\": \"\\u2028\\u2029\" }"),
                Arguments.of("/teams caf\u00e9", "{ \"message\": \"/teams caf\u00e9\" }"),
                Arguments.of("", "{ \"message\": \"\" }"),
                Arguments.of(null, "{ \"message\": null }"));
    }

    private MvcResult perform(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private static void assertBody(MvcResult result, String expected) {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), result.getResponse().getContentAsByteArray(),
                () -> "body was: " + new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    /** Compares type and subtype only: the string converter may append a {@code charset} parameter. */
    private static void assertContentType(MvcResult result, MediaType expected) {
        String contentType = result.getResponse().getContentType();
        assertNotNull(contentType, "Content-Type missing");
        assertTrue(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(expected),
                () -> "expected " + expected + " but Content-Type was " + contentType);
    }

    /**
     * Each thrown exception carries exactly the request URI, so the expected body is the same whether the handler
     * reports the request's path or the exception's.
     */
    @RestController
    static class TestController {

        @GetMapping("/test/no-handler")
        String noHandler() throws NoHandlerFoundException {
            throw new NoHandlerFoundException("GET", "/test/no-handler", new HttpHeaders());
        }

        @GetMapping("/test/no-resource")
        String noResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/test/no-resource");
        }

        @GetMapping("/test/get-only")
        String getOnly() {
            return "ok";
        }

        @GetMapping("/test/illegal-state")
        String illegalState() {
            throw new IllegalStateException("boom \"q\"");
        }

        @GetMapping("/test/null-message")
        String nullMessage() {
            throw new RuntimeException();
        }
    }

    /**
     * The wire layer's whole context: Spring Boot's servlet web stack on Undertow, {@link TestController} and
     * {@link GlobalExceptionHandler}.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration, so the data, messaging and
     * security starters a project carries add nothing to it; it scans nothing, so no project or APIkit advice can
     * claim an exception before SC-1; and it is not a {@code @SpringBootConfiguration}, so the project's other
     * {@code @SpringBootTest} classes still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({TestController.class, GlobalExceptionHandler.class})
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

    /**
     * Package-neutral bridge to the project's ST-1 client {@code <root>.support.HttpTestClient}, where
     * {@code <root>} is this class's package without its last segment.
     *
     * <p>Reflection is what keeps this file portable: each project's ST-1 copy lives in that project's own
     * package, so an import of it would make the copies differ in more than their {@code package} line. Only
     * ST-1's public API is called: the {@code (String host, int port)} constructor and
     * {@code send(String method, String path, Map<String, String> headers, byte[] body)}. An exception ST-1
     * throws, such as its {@code UncheckedIOException} naming the request and the endpoint, propagates unchanged;
     * a missing ST-1 class or member fails with an {@link IllegalStateException} naming it.
     */
    private static final class WireClient {

        private static final Class<?>[] SEND_PARAMETERS = {String.class, String.class, Map.class, byte[].class};

        private final Object client;

        WireClient(String host, int port) {
            String className = clientClassName();
            try {
                client = Class.forName(className).getConstructor(String.class, int.class).newInstance(host, port);
            } catch (InvocationTargetException e) {
                throw unwrap(e);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "ST-1 client " + className + " with a public (String, int) constructor is required: " + e, e);
            }
        }

        /** Sends {@code method path} with no headers beyond ST-1's own and no body. */
        WireResponse send(String method, String path) {
            return new WireResponse(invoke(client, "send", SEND_PARAMETERS, method, path, null, null));
        }

        private static String clientClassName() {
            String testPackage = GlobalExceptionHandlerTest.class.getPackageName();
            int lastDot = testPackage.lastIndexOf('.');
            if (lastDot < 0) {
                throw new IllegalStateException("cannot locate ST-1 from package '" + testPackage
                        + "': expected <root>.exception");
            }
            return testPackage.substring(0, lastDot) + ".support.HttpTestClient";
        }

        /** Calls a public method of an ST-1 object, propagating what the method throws unchanged. */
        static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments) {
            try {
                return target.getClass().getMethod(name, parameterTypes).invoke(target, arguments);
            } catch (InvocationTargetException e) {
                throw unwrap(e);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "ST-1 " + target.getClass().getName() + " has no callable public " + name + ": " + e, e);
            }
        }

        /** Returns the unchecked exception ST-1 threw, or throws the {@link Error} it threw. */
        private static RuntimeException unwrap(InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof RuntimeException runtime) {
                return runtime;
            }
            // ST-1's public API declares no checked exception, so only an incompatible copy reaches this line.
            return new IllegalStateException("ST-1 threw a checked exception: " + cause, cause);
        }
    }

    /** An ST-1 {@code HttpTestClient.Response}, read through its public accessors. */
    private static final class WireResponse {

        private static final Class<?>[] NO_PARAMETERS = {};

        private static final Class<?>[] NAME_PARAMETER = {String.class};

        private final Object response;

        WireResponse(Object response) {
            this.response = Objects.requireNonNull(response, "ST-1 send returned no response");
        }

        /** The raw status line without its terminator, e.g. {@code HTTP/1.1 404 Not Found}. */
        String statusLine() {
            return (String) WireClient.invoke(response, "statusLine", NO_PARAMETERS);
        }

        /** The first value of header {@code name}, or {@code null} when absent. */
        String header(String name) {
            return (String) WireClient.invoke(response, "header", NAME_PARAMETER, name);
        }

        /** Every value of header {@code name} in arrival order. */
        List<?> headers(String name) {
            return (List<?>) WireClient.invoke(response, "headers", NAME_PARAMETER, name);
        }

        /** The body bytes as received. */
        byte[] body() {
            return (byte[]) WireClient.invoke(response, "body", NO_PARAMETERS);
        }

        /** ST-1's rendering of the status line, headers and body, for assertion messages. */
        @Override
        public String toString() {
            return response.toString();
        }
    }
}
