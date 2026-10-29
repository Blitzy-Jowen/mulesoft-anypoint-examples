/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.document_integration_using_the_cmis_connector.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

import com.mulesoft.examples.document_integration_using_the_cmis_connector.controller.CmisController;
import com.mulesoft.examples.document_integration_using_the_cmis_connector.exception.GlobalExceptionHandler;
import com.mulesoft.examples.document_integration_using_the_cmis_connector.service.CmisDocumentService;
import com.mulesoft.examples.document_integration_using_the_cmis_connector.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the {@code /cmis} listener: a CORS pre-flight runs the flow,
 * creating one document as every other request does, and is answered exactly as a plain {@code OPTIONS} request is,
 * 200 with the created document's ID as JSON, with no {@code Access-Control-*} or {@code Vary} header, because the
 * original listener declares no {@code allowedMethods} and applies no CORS policy
 * (document-integration-using-the-cmis-connector/src/main/app/cmis-document-integration.xml:6).
 *
 * <p>The cases run the real controller and SC-1 handler on Undertow ({@link WireConfig}, random port) and send
 * requests through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the listener
 * writes them. {@link CmisDocumentService}, which calls the CMIS repository, is a mock returning a document ID of the
 * form the original test expects; each request's single call to it shows that the flow ran.
 */
@SpringBootTest(classes = PreflightDispatchListenerTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreflightDispatchListenerTest {

    private static final String PATH = "/cmis";

    /**
     * The service's reply for a created document, starting with {@code {"id":"/okm:root/pic} as the original test
     * expects (DocumentIntegrationUsingTheCmisConnectorIT.java:48-50).
     */
    private static final String CREATED_JSON = "{\"id\":\"/okm:root/pic1414000000000.jpg\"}";

    private static final String ORIGIN = "https://example.invalid";

    @LocalServerPort
    private int port;

    @MockBean
    private CmisDocumentService cmisDocumentService;

    @BeforeEach
    void stubTheDocumentCreation() {
        when(cmisDocumentService.createDocument()).thenReturn(CREATED_JSON);
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
        verify(cmisDocumentService).createDocument();

        HttpTestClient.Response preflight = client.send("OPTIONS", PATH,
                preflightHeaders(requestedMethod, requestedHeaders), null);

        verify(cmisDocumentService, times(2)).createDocument();
        verifyNoMoreInteractions(cmisDocumentService);
        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 200 OK", preflight.statusLine(), preflight::toString);
        assertArrayEquals(CREATED_JSON.getBytes(StandardCharsets.UTF_8), preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
    }

    @Test
    void preflightToAnUnservedPathGetsTheNoListener404() {
        HttpTestClient client = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = client.send("OPTIONS", "/unknown", null, null);

        HttpTestClient.Response preflight = client.send("OPTIONS", "/unknown", preflightHeaders("POST", null), null);

        verifyNoInteractions(cmisDocumentService);
        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("No listener for endpoint: /unknown".getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
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
     * listener's own beans, with the test's mock {@link CmisDocumentService} in place of the repository-backed service.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listener and nothing else, no CMIS client included; and it is not a
     * {@code @SpringBootConfiguration}, so the project's other {@code @SpringBootTest} classes still find exactly one
     * application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({PreflightDispatchConfig.class, CmisController.class, GlobalExceptionHandler.class})
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
