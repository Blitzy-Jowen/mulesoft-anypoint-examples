package com.mulesoft.examples.netsuite_data_retrieval.exception;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Stream;

import com.mulesoft.examples.netsuite_data_retrieval.config.JsonSchemaValidator;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Tests shared component SC-3's {@link ApiKitExceptionHandler}: the five mappings of the original
 * {@code apikit:mapping-exception-strategy}, each answering with its status, {@code Content-Type: application/json}
 * and a literal body whose spacing is APIkit's, plus the listener-default answers the advice writes outside the
 * API base path and its precedence ahead of SC-1's {@link GlobalExceptionHandler}.
 *
 * <p>Standalone MockMvc drives the nested {@link ApiTestController}, whose endpoints raise each exception the
 * advice maps exactly as Spring MVC raises it for a RAML violation, so every case also asserts which exception was
 * resolved. Bodies are compared as exact bytes, because APIkit's literal payloads are the contract.
 *
 * <p>Every RAML-backed project carries this class with only its {@code package} line and the package root of the
 * {@link JsonSchemaValidator} import changed: it depends on the JDK, JUnit, Spring, Jakarta Validation, the three
 * SC-3 classes and SC-1's {@link GlobalExceptionHandler} alone, and uses an inline schema rather than any project
 * resource.
 */
class ApiKitExceptionHandlerTest {

    /** The API base path, the original listener path {@code /api/*} without its wildcard. */
    private static final String BASE_PATH = "/api";

    /** What {@link ApiKitExceptionHandler} sets: the bare media type, as APIkit's {@code set-property} did. */
    private static final String APIKIT_CONTENT_TYPE = "application/json";

    /** Draft-03 schema in the RAML schema files' style: a required three-character {@code id}. */
    private static final String SCHEMA = "{\"$schema\":\"http://json-schema.org/draft-03/schema\","
            + "\"type\":\"object\","
            + "\"properties\":{\"id\":{\"type\":\"string\",\"required\":true,\"minLength\":3,\"maxLength\":3}}}";

    private LocalValidatorFactoryBean validator;

    private JsonSchemaValidator schemaValidator;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        // validateAgainst never reads the RAML, so the location is a neutral name rather than a project file.
        schemaValidator = new JsonSchemaValidator("api.raml");
        mockMvc = buildMockMvc(new ApiKitExceptionHandler(BASE_PATH), new GlobalExceptionHandler());
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    static Stream<Arguments> badRequestTriggers() {
        return Stream.of(
                Arguments.of("JSON schema rejects the body",
                        post("/api/schema").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"TOOLONG\"}"),
                        JsonSchemaValidator.SchemaValidationException.class),
                Arguments.of("body is not JSON",
                        post("/api/readable").contentType(MediaType.APPLICATION_JSON).content("{not json"),
                        HttpMessageNotReadableException.class),
                Arguments.of("required query parameter missing",
                        get("/api/required-param"),
                        MissingServletRequestParameterException.class),
                Arguments.of("URI parameter outside its length constraint",
                        get("/api/teams/TOOLONG"),
                        HandlerMethodValidationException.class),
                Arguments.of("constraint violation thrown by the API",
                        get("/api/constraint"),
                        ConstraintViolationException.class),
                Arguments.of("bound body fails bean validation",
                        post("/api/dto").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"TOOLONG\"}"),
                        MethodArgumentNotValidException.class),
                Arguments.of("query parameter of the wrong type",
                        get("/api/typed").param("n", "abc"),
                        MethodArgumentTypeMismatchException.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badRequestTriggers")
    void ramlValidationFailureAnswersApiKit400(String description, RequestBuilder request,
                                               Class<? extends Exception> expectedException) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();

        assertInstanceOf(expectedException, result.getResolvedException());
        assertApiKit(result, 400, "Bad request");
    }

    @Test
    void bodySatisfyingSchemaReachesController() throws Exception {
        MvcResult result = mockMvc.perform(
                post("/api/schema").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"BAR\"}")).andReturn();

        assertNull(result.getResolvedException());
        assertEquals(200, result.getResponse().getStatus());
        assertBody(result, "ok");
    }

    @Test
    void thrownApiKitNotFoundExceptionAnswersApiKit404() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/not-found")).andReturn();

        assertInstanceOf(ApiKitNotFoundException.class, result.getResolvedException());
        assertApiKit(result, 404, "Resource not found");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/unmapped", "/api"})
    void unmappedPathUnderBasePathAnswersApiKit404(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();

        assertInstanceOf(NoHandlerFoundException.class, result.getResolvedException());
        assertApiKit(result, 404, "Resource not found");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/other/unmapped", "/apix/unmapped"})
    void unmappedPathOutsideBasePathAnswersNoListener404(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();

        assertInstanceOf(NoHandlerFoundException.class, result.getResolvedException());
        assertEquals(404, result.getResponse().getStatus());
        assertContentType(result, MediaType.TEXT_PLAIN);
        assertBody(result, "No listener for endpoint: " + path);
    }

    @Test
    void undeclaredMethodUnderBasePathAnswersApiKit405() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/get-only")).andReturn();

        assertInstanceOf(HttpRequestMethodNotSupportedException.class, result.getResolvedException());
        assertApiKit(result, 405, "Method not allowed");
        // APIkit's 405 mapping set Content-Type alone; Spring's own 405 handling would add Allow.
        assertNull(result.getResponse().getHeader(HttpHeaders.ALLOW));
    }

    @Test
    void undeclaredMethodOutsideBasePathAnswersListener405() throws Exception {
        MvcResult result = mockMvc.perform(post("/other/get-only")).andReturn();

        assertInstanceOf(HttpRequestMethodNotSupportedException.class, result.getResolvedException());
        assertEquals(405, result.getResponse().getStatus());
        assertContentType(result, MediaType.TEXT_PLAIN);
        assertBody(result, "Method not allowed for endpoint: /other/get-only");
        assertNull(result.getResponse().getHeader(HttpHeaders.ALLOW));
    }

    @Test
    void unsupportedRequestMediaTypeAnswersApiKit415() throws Exception {
        MvcResult result = mockMvc.perform(
                post("/api/json-only").contentType(MediaType.TEXT_PLAIN).content("plain")).andReturn();

        assertInstanceOf(HttpMediaTypeNotSupportedException.class, result.getResolvedException());
        assertApiKit(result, 415, "Unsupported media type");
    }

    @Test
    void unacceptableResponseMediaTypeStillReceivesApiKit406Json() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/produces-json").accept(MediaType.TEXT_XML)).andReturn();

        assertInstanceOf(HttpMediaTypeNotAcceptableException.class, result.getResolvedException());
        assertApiKit(result, 406, "Not acceptable");
    }

    @Test
    void exceptionOutsideApiKitMappingsFallsThroughToGlobalHandler500() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/boom")).andReturn();

        assertInstanceOf(IllegalStateException.class, result.getResolvedException());
        assertEquals(500, result.getResponse().getStatus());
        assertContentType(result, MediaType.APPLICATION_JSON);
        assertBody(result, "{ \"message\": \"boom\" }");
    }

    @Test
    void orderAnnotationPutsApiKitAheadOfGlobalHandlerWhateverTheRegistrationOrder() throws Exception {
        // Were the advice consulted in registration order, SC-1's Exception method would claim this as a 500.
        MockMvc reversed = buildMockMvc(new GlobalExceptionHandler(), new ApiKitExceptionHandler(BASE_PATH));

        MvcResult result = reversed.perform(get("/api/not-found")).andReturn();

        assertApiKit(result, 404, "Resource not found");
    }

    private MockMvc buildMockMvc(Object... controllerAdvice) {
        return MockMvcBuilders.standaloneSetup(new ApiTestController(schemaValidator), new OtherTestController())
                .setControllerAdvice(controllerAdvice)
                .setValidator(validator)
                .build();
    }

    /** Asserts an APIkit mapping's answer: status, the exact Content-Type it sets, Content-Length and body bytes. */
    private static void assertApiKit(MvcResult result, int status, String message) {
        MockHttpServletResponse response = result.getResponse();
        String expectedBody = "{ \"message\": \"" + message + "\" }";

        assertEquals(status, response.getStatus());
        String contentType = response.getHeader(HttpHeaders.CONTENT_TYPE);
        assertEquals(APIKIT_CONTENT_TYPE, contentType);
        assertTrue(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(MediaType.APPLICATION_JSON));
        assertEquals(expectedBody.getBytes(UTF_8).length, response.getContentLength());
        assertBody(result, expectedBody);
    }

    /** Asserts the media type and subtype of a listener-default or SC-1 answer, whatever charset it carries. */
    private static void assertContentType(MvcResult result, MediaType expected) {
        String contentType = result.getResponse().getContentType();
        assertNotNull(contentType, "Content-Type");
        assertTrue(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(expected),
                () -> "expected " + expected + " but Content-Type was " + contentType);
    }

    private static void assertBody(MvcResult result, String expected) {
        byte[] actual = result.getResponse().getContentAsByteArray();
        assertArrayEquals(expected.getBytes(UTF_8), actual, () -> "body was: " + new String(actual, UTF_8));
    }

    /** Bound body of {@code /api/dto}; Jackson binds the public field. */
    public static class TeamDto {

        @NotNull
        @Size(min = 3, max = 3)
        public String id;
    }

    /**
     * Endpoints under the base path, one per exception the advice maps. Not {@code @Validated}: the
     * {@code @Size} URI parameter relies on Spring MVC's built-in method validation, and {@code /api/dto} carries
     * no parameter constraint, so its {@code @Valid} body raises {@link MethodArgumentNotValidException}.
     */
    @RestController
    static class ApiTestController {

        private final JsonSchemaValidator schemaValidator;

        ApiTestController(JsonSchemaValidator schemaValidator) {
            this.schemaValidator = schemaValidator;
        }

        @PostMapping(value = "/api/schema", consumes = "application/json")
        String schema(@RequestBody String body) {
            schemaValidator.validateAgainst(SCHEMA, body);
            return "ok";
        }

        @PostMapping(value = "/api/readable", consumes = "application/json")
        String readable(@RequestBody Map<String, Object> body) {
            return "ok";
        }

        @GetMapping("/api/required-param")
        String required(@RequestParam("city") String city) {
            return city;
        }

        @GetMapping("/api/teams/{teamId}")
        String team(@PathVariable("teamId") @Size(min = 3, max = 3) String teamId) {
            return teamId;
        }

        @GetMapping("/api/constraint")
        String constraint() {
            throw new ConstraintViolationException("violated", Collections.emptySet());
        }

        @PostMapping(value = "/api/dto", consumes = "application/json")
        String dto(@Valid @RequestBody TeamDto dto) {
            return "ok";
        }

        @GetMapping("/api/typed")
        String typed(@RequestParam("n") int n) {
            return String.valueOf(n);
        }

        @GetMapping("/api/not-found")
        String notFound() {
            throw new ApiKitNotFoundException("x");
        }

        @GetMapping("/api/get-only")
        String getOnly() {
            return "ok";
        }

        @PostMapping(value = "/api/json-only", consumes = "application/json")
        String jsonOnly(@RequestBody String b) {
            return "ok";
        }

        @GetMapping(value = "/api/produces-json", produces = "application/json")
        String producesJson() {
            return "{}";
        }

        @GetMapping("/api/boom")
        String boom() {
            throw new IllegalStateException("boom");
        }
    }

    /** An endpoint outside the base path, where the listener defaults apply. */
    @RestController
    static class OtherTestController {

        @GetMapping("/other/get-only")
        String getOnly() {
            return "ok";
        }
    }
}
