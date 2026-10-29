package com.mulesoft.examples.login_form_using_the_http_connector.exception;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.mulesoft.examples.login_form_using_the_http_connector.mapper.ParseTemplateRenderer;
import com.mulesoft.examples.login_form_using_the_http_connector.service.LoginService;
import com.mulesoft.examples.login_form_using_the_http_connector.support.HttpTestClient;

/**
 * Tests {@link LoginExceptionHandler}, the {@code catch-exception-strategy} of {@code DoLoginFlow}
 * ({@code login-form-using-the-http-connector.xml}:27-30), in two layers that drive the same nested
 * {@link FailingController}.
 *
 * <ul>
 *   <li>Unit layer: standalone MockMvc with {@link GlobalExceptionHandler} registered before the handler, so only
 *       their {@code @Order} routes {@link LoginService.LoginFailedException} to the handler; the status, the
 *       {@code Content-Type} derived from the request's, the body bytes in the derived charset and the
 *       {@code Content-Length} are checked for every request {@code Content-Type} shape. Direct calls with mock
 *       servlet objects cover the path without an Undertow exchange and the untouched response after a rendering
 *       failure.</li>
 *   <li>Wire layer: the controller and both advices on Undertow ({@link WireConfig}, random port on IPv4
 *       loopback), called through the project's ST-1 client. Only here are the status line and the header's exact
 *       spelling observable: the status line carries the original listener's empty reason phrase
 *       ({@code HTTP/1.1 403 }) where Undertow would send its standard {@code Forbidden}, and Undertow would
 *       rewrite {@code ; charset=} to {@code ;charset=} had the value gone through the servlet API.</li>
 * </ul>
 *
 * <p>Expected pages are computed from the {@code login/loginFailure.html} resource and the request alone, never
 * through the handler or its constants; expected byte counts come from the original Mule 3.8 listener's
 * responses.
 */
@SpringBootTest(
        classes = LoginExceptionHandlerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Pins the listener to the address ST-1 connects to, whatever server.address the project sets.
                "server.address=" + LoginExceptionHandlerTest.LOOPBACK,
                // FailingController binds the form through servlet parameters, which Boot otherwise decodes as
                // UTF-8 whatever charset an ISO-8859-1 form declares; the Mule listener decoded a form in the
                // charset it declared. The handler reads only the request's Content-Type header either way.
                "server.servlet.encoding.force-request=false"})
class LoginExceptionHandlerTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    private static final String TEMPLATE = "login/loginFailure.html";

    private static final String EXPRESSION = "#[payload.username]";

    private static final String FORM = "application/x-www-form-urlencoded";

    private static final String FORM_UTF8 = FORM + "; charset=UTF-8";

    private static final String FORM_LATIN1 = FORM + "; charset=ISO-8859-1";

    private static final String JOSE = "Jos\u00e9";

    /**
     * The original Mule 3.8 listener's status line for the catch branch, received as {@code HTTP/1.1 403 \r\n}:
     * code, one space, empty reason phrase. ST-1 decodes the line byte for byte and drops only the terminator, so
     * comparing with this string compares those bytes, the trailing space included.
     */
    private static final String FAILURE_STATUS_LINE = "HTTP/1.1 403 ";

    @LocalServerPort
    int port;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LoginExceptionHandler handler = new LoginExceptionHandler(new ParseTemplateRenderer());
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new GlobalExceptionHandler(), handler)
                .build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contentTypeCases")
    void rejectedLoginAnswers403WithFailurePageShapedByRequestContentType(
            String description, String username, String requestContentType, String expectedContentType,
            Charset expectedCharset, int expectedLength) throws Exception {
        MockHttpServletRequestBuilder request = post("/login").param("password", "wrong");
        if (username != null) {
            request.param("username", username);
        }
        if (requestContentType != null) {
            // header(), not contentType(String), which rejects values that are not media types.
            request.header(HttpHeaders.CONTENT_TYPE, requestContentType);
        }

        MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();

        byte[] expected = expectedPage(String.valueOf(username), expectedCharset);
        assertEquals(403, response.getStatus());
        // setStatus, never sendError: a container error page could otherwise replace the template.
        assertNull(response.getErrorMessage());
        assertEquals(expectedContentType == null ? List.of() : List.of(expectedContentType),
                response.getHeaders(HttpHeaders.CONTENT_TYPE));
        assertArrayEquals(expected, response.getContentAsByteArray(),
                () -> "body was: " + new String(response.getContentAsByteArray(), expectedCharset));
        assertEquals(expectedLength, expected.length);
        assertEquals(expectedLength, response.getContentLength());
    }

    /**
     * Description, posted username ({@code null} posts none), request {@code Content-Type} ({@code null} sends
     * none), then the {@code Content-Type} expected ({@code null} for none), the page's charset and its length in
     * bytes.
     */
    private static Stream<Arguments> contentTypeCases() {
        return Stream.of(
                Arguments.of("form", "joe", FORM, FORM_UTF8, UTF_8, 229),
                Arguments.of("missing username renders null", null, FORM, FORM_UTF8, UTF_8, 230),
                Arguments.of("charset canonicalised", "joe", FORM + "; charset=utf-8", FORM_UTF8, UTF_8, 229),
                Arguments.of("space restored", "joe", FORM + ";charset=UTF-8", FORM_UTF8, UTF_8, 229),
                Arguments.of("type lower-cased", "joe", "Application/X-WWW-Form-Urlencoded", FORM_UTF8, UTF_8, 229),
                Arguments.of("other parameters dropped", "joe", FORM + "; foo=bar", FORM_UTF8, UTF_8, 229),
                Arguments.of("ISO-8859-1 page", JOSE, FORM_LATIN1, FORM_LATIN1, ISO_8859_1, 230),
                Arguments.of("UTF-8 page", JOSE, FORM, FORM_UTF8, UTF_8, 231),
                Arguments.of("JSON media type", "joe", "application/json", "application/json; charset=UTF-8",
                        UTF_8, 229),
                Arguments.of("text media type", "joe", "text/plain", "text/plain; charset=UTF-8", UTF_8, 229),
                Arguments.of("no request Content-Type", "joe", null, null, UTF_8, 229),
                Arguments.of("any media type", "joe", "*/*", null, UTF_8, 229),
                Arguments.of("any media type with charset", JOSE, "*/*; charset=ISO-8859-1", null, ISO_8859_1, 230),
                Arguments.of("not a media type", "joe", "garbage", null, UTF_8, 229),
                Arguments.of("unsupported charset", JOSE, FORM + "; charset=bogus", null, UTF_8, 231),
                Arguments.of("decode-only charset", JOSE, FORM + "; charset=ISO-2022-CN", null, UTF_8, 231));
    }

    @Test
    void otherExceptionsReachGlobalHandler() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/boom")).andReturn().getResponse();

        assertEquals(500, response.getStatus());
        String contentType = response.getContentType();
        assertNotNull(contentType, "Content-Type missing");
        assertTrue(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(MediaType.APPLICATION_JSON),
                () -> "Content-Type was " + contentType);
        assertArrayEquals("{ \"message\": \"boom\" }".getBytes(UTF_8), response.getContentAsByteArray());
    }

    @Test
    void claimsOnlyLoginFailedExceptionAheadOfGlobalHandler() {
        assertNotNull(LoginExceptionHandler.class.getAnnotation(RestControllerAdvice.class));
        List<Method> handlers = Arrays.stream(LoginExceptionHandler.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                .toList();
        assertEquals(1, handlers.size(), () -> "exception handler methods: " + handlers);
        assertArrayEquals(new Class<?>[] {LoginService.LoginFailedException.class},
                handlers.get(0).getAnnotation(ExceptionHandler.class).value());

        Order order = LoginExceptionHandler.class.getAnnotation(Order.class);
        Order globalOrder = GlobalExceptionHandler.class.getAnnotation(Order.class);
        assertNotNull(order);
        assertNotNull(globalOrder);
        assertEquals(0, order.value());
        assertTrue(order.value() < globalOrder.value());
    }

    @Test
    void templateConstantsNameTheCatchBranchTemplateAndItsOnlyExpression() {
        assertEquals(TEMPLATE, LoginExceptionHandler.FAILURE_TEMPLATE);
        assertEquals(EXPRESSION, LoginExceptionHandler.USERNAME_EXPRESSION);
        assertEquals("username", LoginExceptionHandler.USERNAME_FIELD);
        String template = template();
        int first = template.indexOf(EXPRESSION);
        assertTrue(first >= 0, "template lacks " + EXPRESSION);
        assertEquals(first, template.lastIndexOf(EXPRESSION), "template holds " + EXPRESSION + " more than once");
    }

    @Test
    void withoutUndertowExchangeContentTypeGoesThroughServletResponse() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setContentType(FORM + ";charset=ISO-8859-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new LoginExceptionHandler(new ParseTemplateRenderer()).handleLoginFailed(
                new LoginService.LoginFailedException(Map.of("username", JOSE)), request, response);

        byte[] expected = expectedPage(JOSE, ISO_8859_1);
        assertEquals(403, response.getStatus());
        assertEquals(FORM_LATIN1, response.getContentType());
        assertArrayEquals(expected, response.getContentAsByteArray());
        assertEquals(expected.length, response.getContentLength());
    }

    @Test
    void renderingFailureLeavesResponseUntouched() {
        // A class loader that sees no application resource, so the template cannot be found.
        ParseTemplateRenderer blindRenderer = new ParseTemplateRenderer(new ClassLoader(null) {
        });
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setContentType(FORM);
        MockHttpServletResponse response = new MockHttpServletResponse();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new LoginExceptionHandler(blindRenderer).handleLoginFailed(
                        new LoginService.LoginFailedException(Map.of("username", "joe")), request, response));

        assertEquals("Template not found: " + TEMPLATE, failure.getMessage());
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeaderNames().isEmpty(), () -> "headers: " + response.getHeaderNames());
        assertEquals(0, response.getContentAsByteArray().length);
        assertFalse(response.isCommitted());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("wireCases")
    void failurePageReachesTheWireWithVerbatimContentType(String description, String requestContentType,
                                                          String formBody, String renderedUsername,
                                                          String expectedContentType, Charset expectedCharset) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (requestContentType != null) {
            headers.put(HttpHeaders.CONTENT_TYPE, requestContentType);
        }

        HttpTestClient.Response response = new HttpTestClient(LOOPBACK, port)
                .send("POST", "/login", headers, formBody.getBytes(ISO_8859_1));

        byte[] expected = expectedPage(renderedUsername, expectedCharset);
        assertEquals(FAILURE_STATUS_LINE, response.statusLine(), response::toString);
        assertEquals("", response.reason(), response::toString);
        assertEquals(expectedContentType == null ? List.of() : List.of(expectedContentType),
                response.headers(HttpHeaders.CONTENT_TYPE), response::toString);
        assertArrayEquals(expected, response.body(), response::toString);
        assertEquals(List.of(String.valueOf(expected.length)), response.headers(HttpHeaders.CONTENT_LENGTH),
                response::toString);
    }

    /**
     * Description, request {@code Content-Type} ({@code null} sends none; ST-1 adds none of its own), the ASCII
     * form body, the username the page shows, then the {@code Content-Type} expected ({@code null} for none) and
     * the page's charset. Without a form media type the container parses no form, so the page shows {@code null}.
     */
    private static Stream<Arguments> wireCases() {
        String rejected = "username=joe&password=wrong";
        return Stream.of(
                Arguments.of("form", FORM, rejected, "joe", FORM_UTF8, UTF_8),
                Arguments.of("form without space", FORM + ";charset=UTF-8", rejected, "joe", FORM_UTF8, UTF_8),
                Arguments.of("form in ISO-8859-1", FORM_LATIN1, "username=Jos%E9&password=wrong", JOSE, FORM_LATIN1,
                        ISO_8859_1),
                Arguments.of("no request Content-Type", null, rejected, "null", null, UTF_8));
    }

    /** {@code loginFailure.html} with its expression replaced by {@code username}, encoded in {@code charset}. */
    private static byte[] expectedPage(String username, Charset charset) {
        return template().replace(EXPRESSION, username).getBytes(charset);
    }

    private static String template() {
        try (InputStream in = LoginExceptionHandlerTest.class.getClassLoader().getResourceAsStream(TEMPLATE)) {
            assertNotNull(in, TEMPLATE + " not on the classpath");
            return new String(in.readAllBytes(), UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Raises what {@code DoLoginFlow} raised on a rejected login, and an exception no login branch caught. */
    @RestController
    static class FailingController {

        @PostMapping("/login")
        String login(@RequestParam Map<String, String> form) {
            throw new LoginService.LoginFailedException(form);
        }

        @GetMapping("/boom")
        String boom() {
            throw new RuntimeException("boom");
        }
    }

    /**
     * The wire layer's whole context: Spring Boot's servlet web stack on Undertow, {@link FailingController}, both
     * advices and the SC-14 renderer.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so no
     * other project bean takes part; it is not a {@code @SpringBootConfiguration}, so the project's other
     * {@code @SpringBootTest} classes still find exactly one application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({FailingController.class, GlobalExceptionHandler.class, LoginExceptionHandler.class,
            ParseTemplateRenderer.class})
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
