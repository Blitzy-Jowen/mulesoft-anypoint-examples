/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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

import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.exception.GlobalExceptionHandler;
import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.service.EmployeeUploadService;
import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.support.HttpTestClient;

/**
 * Wire-level test of how {@link EmployeeUploadController} binds the charset a request declares: the charset parameter
 * of the first {@code Content-Type} value, matched ignoring the letter case of its name and unquoted, reaches
 * {@link EmployeeUploadService} with the body bytes unchanged, the charset the Mule listener made the message encoding
 * (upload-to-ftp-after-converting-json-to-xml/src/main/app/upload-to-ftp.xml:6); a request that declares none passes
 * {@code null}; and a {@code Content-Type} that is not a media type, or names an illegal or unsupported charset, is
 * answered with the generic 500 before the service runs, so nothing is uploaded.
 *
 * <p>The cases run the real controller and SC-1 handler on Undertow ({@link WireConfig}, random port), with Spring
 * Boot's {@code CharacterEncodingFilter} forcing the request encoding to UTF-8 as in the application, and send requests
 * through ST-1 {@link HttpTestClient}, so header lines reach the listener exactly as written, repeated ones included.
 * {@link EmployeeUploadService} is a mock; its single call shows what the controller bound.
 */
@SpringBootTest(classes = EmployeeUploadCharsetBindingTest.WireConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmployeeUploadCharsetBindingTest {

    private static final String PATH = "/";

    private static final String CONTENT_TYPE = "Content-Type";

    /** A body the mock service receives unchanged; its bytes are not text in any one charset on purpose. */
    private static final byte[] BODY = {'{', '"', 'n', '"', ':', '"', (byte) 0xE9, (byte) 0xFC, '"', '}'};

    /** The mock service's XML, returned byte for byte. */
    private static final byte[] XML = "<?xml version='1.0' encoding='UTF-8'?>\n<employees/>"
            .getBytes(StandardCharsets.UTF_8);

    @LocalServerPort
    private int port;

    @MockBean
    private EmployeeUploadService employeeUploadService;

    /**
     * Method, the request's {@code Content-Type} header lines in sending order (none when empty), and the charset the
     * service must receive, {@code null} for none.
     */
    private static Stream<Arguments> boundCharsets() {
        Charset latin1 = StandardCharsets.ISO_8859_1;
        return Stream.of(
                Arguments.of("POST", List.of("application/json; charset=ISO-8859-1"), latin1),
                Arguments.of("POST", List.of("application/json;charset=\"ISO-8859-1\""), latin1),
                Arguments.of("POST", List.of("application/json; CHARSET=iso-8859-1"), latin1),
                Arguments.of("POST", List.of("application/json; Charset=\"latin1\""), latin1),
                // Spring's parser unquotes single quotes too, so both spellings of the name read them alike.
                Arguments.of("POST", List.of("application/json; charset='ISO-8859-1'"), latin1),
                Arguments.of("POST", List.of("application/json; CHARSET='ISO-8859-1'"), latin1),
                Arguments.of("PUT", List.of("text/plain; charset=windows-1252"), Charset.forName("windows-1252")),
                Arguments.of("POST", List.of("application/json; charset=UTF-16LE"), StandardCharsets.UTF_16LE),
                Arguments.of("POST", List.of("application/json"), null),
                Arguments.of("POST", List.of(), null),
                // A parameter without a value is no parameter for Spring's parser, so no charset is declared.
                Arguments.of("POST", List.of("application/json; charset"), null),
                Arguments.of("POST",
                        List.of("application/json; charset=ISO-8859-1", "application/json; charset=UTF-16"), latin1),
                Arguments.of("POST", List.of("application/json", "application/json; charset=x-no-such"), null),
                Arguments.of("OPTIONS", List.of("application/json; charset=ISO-8859-1"), latin1),
                Arguments.of("OPTIONS", List.of("application/json; CHARSET=\"UTF-16LE\""), StandardCharsets.UTF_16LE),
                Arguments.of("OPTIONS", List.of(), null));
    }

    @ParameterizedTest(name = "{0} with Content-Type {1} -> {2}")
    @MethodSource("boundCharsets")
    void declaredCharsetReachesTheServiceWithTheBody(String method, List<String> contentTypes, Charset expected) {
        when(employeeUploadService.process(aryEq(BODY), eq(expected))).thenReturn(XML);

        HttpTestClient.Response response = HttpTestClient.localhost(port)
                .sendHeaderPairs(method, PATH, contentTypeLines(contentTypes), BODY);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertEquals("application/xml;charset=UTF-8", response.header(CONTENT_TYPE), response::toString);
        assertArrayEquals(XML, response.body(), response::toString);
        verify(employeeUploadService).process(aryEq(BODY), eq(expected));
        verifyNoMoreInteractions(employeeUploadService);
    }

    /**
     * Method, the request's single {@code Content-Type} value, and the message of the 500 body: Spring rejects the
     * value while binding the body when it is not a media type or its lower-case {@code charset} parameter names an
     * illegal or unsupported charset, and the controller rejects the same names under any other spelling with the same
     * message.
     */
    private static Stream<Arguments> rejectedContentTypes() {
        return Stream.of(
                Arguments.of("POST", "application/json; charset=x-no-such",
                        "Invalid mime type \\\"application/json; charset=x-no-such\\\": "
                        + "unsupported charset 'x-no-such'"),
                Arguments.of("POST", "application/json; CHARSET=x-no-such",
                        "Invalid mime type \\\"application/json; CHARSET=x-no-such\\\": "
                        + "unsupported charset 'x-no-such'"),
                Arguments.of("OPTIONS", "application/json; Charset=\"x-no-such\"",
                        "Invalid mime type \\\"application/json; Charset=\\\"x-no-such\\\"\\\": "
                        + "unsupported charset 'x-no-such'"),
                Arguments.of("POST", "application/json; charset=\"bad name\"",
                        "Invalid mime type \\\"application/json; charset=\\\"bad name\\\"\\\": bad name"),
                Arguments.of("POST", "application/json; CHARSET=\"bad name\"",
                        "Invalid mime type \\\"application/json; CHARSET=\\\"bad name\\\"\\\": bad name"),
                Arguments.of("POST", "application/json; CHARSET=x",
                        "Invalid mime type \\\"application/json; CHARSET=x\\\": unsupported charset 'x'"),
                // An unbalanced quote is part of the name, and a quote is not a legal charset name character.
                Arguments.of("POST", "application/json; CHARSET='ISO-8859-1",
                        "Invalid mime type \\\"application/json; CHARSET='ISO-8859-1\\\": 'ISO-8859-1"),
                Arguments.of("POST", "garbage", "Invalid mime type \\\"garbage\\\": does not contain '/'"));
    }

    @ParameterizedTest(name = "{0} with Content-Type {1}")
    @MethodSource("rejectedContentTypes")
    void unusableContentTypeIsAnswered500BeforeTheService(String method, String contentType, String escapedMessage) {
        when(employeeUploadService.process(any(), any())).thenReturn(XML);

        HttpTestClient.Response response = HttpTestClient.localhost(port)
                .sendHeaderPairs(method, PATH, contentTypeLines(List.of(contentType)), BODY);

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertEquals("application/json", response.header(CONTENT_TYPE), response::toString);
        assertEquals("{ \"message\": \"" + escapedMessage + "\" }", response.bodyAsString(), response::toString);
        verifyNoInteractions(employeeUploadService);
    }

    private static List<Map.Entry<String, String>> contentTypeLines(List<String> values) {
        return values.stream().map(value -> Map.entry(CONTENT_TYPE, value)).toList();
    }

    /**
     * The test's whole context: Spring Boot's servlet web stack on Undertow with the application's
     * {@code CharacterEncodingFilter}, the listener's controller and the SC-1 handler, with the test's mock
     * {@link EmployeeUploadService} in place of the FTP-backed service.
     *
     * <p>It names the web auto-configurations rather than enabling auto-configuration and scans nothing, so the
     * context holds the listener and nothing else, no FTP client included; and it is not a
     * {@code @SpringBootConfiguration}, so the project's other {@code @SpringBootTest} classes still find exactly one
     * application class.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({EmployeeUploadController.class, GlobalExceptionHandler.class})
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
