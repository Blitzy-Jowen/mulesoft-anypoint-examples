/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_multipart_request.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.filter.OrderedCharacterEncodingFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Decodes the part headers of a multipart request to {@code /uploadFile} as ISO-8859-1, as the Mule 3.8.0 HTTP
 * listener decoded them.
 *
 * <p>Mule 3.8.0's {@code HttpParser.parseMultipartContent} parsed a multipart body with JavaMail's
 * {@code MimeMultipart}, whose {@code InternetHeaders} read each part-header byte as one ISO-8859-1 character, whatever
 * charset the request or its {@code Content-Type} named. The upload flow takes the file name from that
 * {@code Content-Disposition} text, so a browser's windows-1252 {@code filename="caf\xE9-latin1.txt"} was stored as
 * {@code café-latin1.txt}, and the UTF-8 bytes of {@code naïve.txt} as {@code naÃ¯ve.txt}, one character per byte.
 *
 * <p>Undertow decodes part headers with the request's character encoding instead, and Spring Boot's
 * {@link OrderedCharacterEncodingFilter} forces that encoding to UTF-8 on every request, which turns each byte that is
 * not valid UTF-8 into U+FFFD. Spring parses the parts in {@code DispatcherServlet.checkMultipart}, before any handler
 * runs, so the encoding is set here, by a filter that runs directly after Boot's and before the body is read: for a
 * multipart request, ISO-8859-1 replaces the forced UTF-8 and any {@code charset} parameter of the multipart
 * {@code Content-Type}, which Undertow would otherwise apply. The project's {@code server.servlet.encoding} keeps
 * Boot's defaults, from which Boot also takes the charset its string message converter writes.
 *
 * <p>The decoded name is not altered afterwards: {@code UploadService} takes it from the header as it stands, neither
 * unquoted, decoded nor sanitised. A {@code GET} in any letter case, which renders the form and binds no part, a
 * request whose {@code Content-Type} is not multipart, and every path other than {@code /uploadFile} are passed on
 * unchanged.
 */
@Configuration(proxyBeanMethods = false)
public class MultipartHeaderCharsetConfig {

    /** The path of the upload listener, the only path the filter is mapped to. */
    static final String UPLOAD_PATH = "/uploadFile";

    /**
     * Order of the filter: directly after Spring Boot's {@link OrderedCharacterEncodingFilter}, which runs at
     * {@link Ordered#HIGHEST_PRECEDENCE}, so the encoding set here replaces the one it forces, and ahead of every other
     * filter, so none of them reads the body first.
     */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    /**
     * Registers {@link MultipartHeaderCharsetFilter} for {@code /uploadFile} only, at {@link #ORDER}, for request
     * dispatches.
     *
     * @return the filter's registration
     */
    @Bean
    public FilterRegistrationBean<MultipartHeaderCharsetFilter> multipartHeaderCharsetFilter() {
        FilterRegistrationBean<MultipartHeaderCharsetFilter> registration =
                new FilterRegistrationBean<>(new MultipartHeaderCharsetFilter());
        registration.addUrlPatterns(UPLOAD_PATH);
        // The parts are parsed during the request dispatch; without this Boot maps a OncePerRequestFilter to every
        // dispatcher type.
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(ORDER);
        return registration;
    }

    /**
     * Sets the character encoding of a multipart request that is not a {@code GET} to ISO-8859-1 before the request
     * body is read, so Undertow decodes every part header byte as one ISO-8859-1 character.
     */
    public static class MultipartHeaderCharsetFilter extends OncePerRequestFilter {

        /** Content-Type prefix of a multipart request, the one Spring's multipart resolver tests ignoring case. */
        private static final String MULTIPART_PREFIX = "multipart/";

        /** Method of the form listener, which binds no part. */
        private static final String FORM_METHOD = "GET";

        /** Charset JavaMail's {@code InternetHeaders} read part-header bytes with. */
        private static final String PART_HEADER_CHARSET = StandardCharsets.ISO_8859_1.name();

        /**
         * @param request the current request
         * @return {@code true}, leaving the request unchanged, for a {@code GET} in any letter case and for a request
         *         whose {@code Content-Type} is absent or does not start with {@code multipart/}, ignoring case
         */
        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return FORM_METHOD.equalsIgnoreCase(request.getMethod())
                    || !StringUtils.startsWithIgnoreCase(request.getContentType(), MULTIPART_PREFIX);
        }

        /**
         * Sets the request's character encoding to ISO-8859-1 and passes the request on.
         *
         * @param request  the multipart request, whose body nothing has read yet
         * @param response the response, passed on unchanged
         * @param chain    the rest of the filter chain
         * @throws ServletException when a later filter or the servlet fails
         * @throws IOException      when a later filter or the servlet fails
         */
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            request.setCharacterEncoding(PART_HEADER_CHARSET);
            chain.doFilter(request, response);
        }
    }
}
