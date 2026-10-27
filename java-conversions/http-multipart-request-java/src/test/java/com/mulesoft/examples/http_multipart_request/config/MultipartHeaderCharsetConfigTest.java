/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_multipart_request.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.filter.OrderedCharacterEncodingFilter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tests {@link MultipartHeaderCharsetConfig}: its filter sets ISO-8859-1 on a multipart request that is not a
 * {@code GET}, whether or not the multipart {@code Content-Type} names a charset, and leaves every other request with
 * the UTF-8 that Spring Boot's character-encoding filter forced; its registration maps the filter to request
 * dispatches of {@code /uploadFile} alone, directly after that filter. The wire-level effect on the stored file name,
 * which also depends on that order, is asserted by {@code UploadControllerTest}.
 */
class MultipartHeaderCharsetConfigTest {

    private static final String FORCED_ENCODING = "UTF-8";

    private static final String PART_HEADER_ENCODING = "ISO-8859-1";

    private final MultipartHeaderCharsetConfig.MultipartHeaderCharsetFilter filter =
            new MultipartHeaderCharsetConfig.MultipartHeaderCharsetFilter();

    /** Multipart requests the upload listener parses: a method other than {@code GET} and its {@code Content-Type}. */
    private static Stream<Arguments> multipartRequests() {
        return Stream.of(
                Arguments.of("POST", "multipart/form-data; boundary=b"),
                Arguments.of("POST", "multipart/form-data; boundary=b; charset=UTF-8"),
                Arguments.of("post", "multipart/form-data; charset=UTF-8; boundary=b"),
                Arguments.of("Post", "Multipart/Form-Data; boundary=b"),
                Arguments.of("PUT", "multipart/mixed; boundary=b"));
    }

    @ParameterizedTest(name = "{0} with Content-Type {1}")
    @MethodSource("multipartRequests")
    void multipartRequestIsDecodedAsIso88591(String method, String contentType)
            throws ServletException, IOException {
        MockHttpServletRequest request = forcedUtf8Request(method, contentType);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(PART_HEADER_ENCODING, request.getCharacterEncoding());
        assertSame(request, chain.getRequest());
    }

    /** Requests the filter passes on unchanged: a {@code GET} in any letter case, and non-multipart requests. */
    private static Stream<Arguments> otherRequests() {
        return Stream.of(
                Arguments.of("GET", "multipart/form-data; boundary=b"),
                Arguments.of("get", "multipart/form-data; boundary=b"),
                Arguments.of("GET", null),
                Arguments.of("POST", "text/plain"),
                Arguments.of("POST", "application/x-www-form-urlencoded"),
                Arguments.of("POST", "text/multipart"),
                Arguments.of("POST", null));
    }

    @ParameterizedTest(name = "{0} with Content-Type {1}")
    @MethodSource("otherRequests")
    void otherRequestKeepsItsEncoding(String method, String contentType) throws ServletException, IOException {
        MockHttpServletRequest request = forcedUtf8Request(method, contentType);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals(FORCED_ENCODING, request.getCharacterEncoding());
        assertSame(request, chain.getRequest());
    }

    @Test
    void registrationMapsTheFilterToTheUploadPathDirectlyAfterTheCharacterEncodingFilter() {
        FilterRegistrationBean<MultipartHeaderCharsetConfig.MultipartHeaderCharsetFilter> registration =
                new MultipartHeaderCharsetConfig().multipartHeaderCharsetFilter();

        assertInstanceOf(MultipartHeaderCharsetConfig.MultipartHeaderCharsetFilter.class, registration.getFilter());
        assertEquals(List.of("/uploadFile"), List.copyOf(registration.getUrlPatterns()));
        assertEquals(List.of(), List.copyOf(registration.getServletNames()));
        assertEquals(EnumSet.of(DispatcherType.REQUEST), registration.determineDispatcherTypes());
        assertEquals(new OrderedCharacterEncodingFilter().getOrder() + 1, registration.getOrder());
    }

    /**
     * A request as it leaves Spring Boot's character-encoding filter, which forces UTF-8 after the container has read
     * any charset of the {@code Content-Type}.
     */
    private static MockHttpServletRequest forcedUtf8Request(String method, String contentType) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/uploadFile");
        if (contentType != null) {
            request.setContentType(contentType);
        }
        request.setCharacterEncoding(FORCED_ENCODING);
        return request;
    }
}
