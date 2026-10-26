package com.mulesoft.examples.rest_api_with_apikit.exception;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
 * Tests shared component SC-1: {@link GlobalExceptionHandler} and {@link ErrorResponse}.
 *
 * <p>Every project with a Spring MVC controller carries this class with only its {@code package} line changed,
 * so it depends on the JDK, JUnit, Spring and the two SC-1 classes alone: the controller it drives is the nested
 * {@link TestController}, and MockMvc runs it standalone with no application context, server or project resource.
 * Bodies are compared as exact bytes because the Mule listener's default texts and APIkit's JSON spacing are the
 * contract.
 */
class GlobalExceptionHandlerTest {

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
}
