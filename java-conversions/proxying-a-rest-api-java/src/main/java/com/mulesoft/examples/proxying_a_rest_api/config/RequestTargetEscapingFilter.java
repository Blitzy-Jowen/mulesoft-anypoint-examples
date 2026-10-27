/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets every request target the original listener accepted reach the proxy. The original listener
 * ({@code http:listener path="/*"}, {@code proxying-a-rest-api.xml:12}) accepted a target holding characters RFC 3986
 * does not allow there, such as a raw {@code |}, <code>{</code>, <code>}</code>, {@code ^}, {@code "}, {@code <},
 * {@code >}, {@code `} or {@code \}, or a {@code %} not followed by two hexadecimal digits, and forwarded the request
 * to the upstream. Every other character a component does not allow, a raw {@code #}, a control character or a
 * non-ASCII byte among them, is treated the same way.
 *
 * <p>Undertow accepts such a target and keeps it undecoded because {@code server.undertow.decode-url} is off and its
 * {@code ALLOW_UNESCAPED_CHARACTERS_IN_URL} option is on, each byte becoming one ISO-8859-1 character. The one
 * exception is a {@code %} followed by neither two hexadecimal digits nor another {@code %}, as in {@code %zz},
 * {@code %4} or a final {@code %}, which Undertow's HTTP/1.1 parser answers with 400 whatever its options, before this
 * filter runs; the filter still escapes such a {@code %} for a request that reaches it without that parser. Two later
 * steps would still refuse it: Spring MVC's path parsing fails on an invalid escape before any handler or advice
 * runs, and {@code java.net.URI}, which carries the upstream URL, rejects the raw characters and reads a {@code #} as
 * the start of a fragment, dropping the rest of the target. This filter runs before every other filter and the
 * {@code DispatcherServlet}, and hands them a request whose {@link HttpServletRequest#getRequestURI()},
 * {@link HttpServletRequest#getQueryString()} and {@link HttpServletRequest#getRequestURL()} return the target with
 * every character its component does not allow percent-encoded, as {@code escape} describes. Every valid escape and
 * every character the component allows stay as sent, so a target that is already valid is passed on unwrapped and
 * reaches the upstream byte for byte, and an escaped one reaches it with the caller's bytes restored.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTargetEscapingFilter extends OncePerRequestFilter {

    /**
     * The characters an RFC 3986 path keeps unescaped: {@code unreserved}, {@code sub-delims}, {@code :}, {@code @}
     * and the segment separator {@code /}.
     */
    private static final String PATH_CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/";

    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    /** A component of the request target, with the characters it keeps unescaped besides valid escapes. */
    enum TargetComponent {

        /** The path: RFC 3986 {@code pchar} and {@code /}. */
        PATH(PATH_CHARACTERS),

        /**
         * The query: RFC 3986 {@code pchar}, {@code /} and {@code ?}, plus {@code [} and {@code ]}, which
         * {@code java.net.URI} accepts in a query and callers send unescaped, as in {@code filter[name]=x}.
         */
        QUERY(PATH_CHARACTERS + "?[]");

        private final boolean[] kept = new boolean[128];

        TargetComponent(String keptCharacters) {
            for (int i = 0; i < keptCharacters.length(); i++) {
                kept[keptCharacters.charAt(i)] = true;
            }
        }

        boolean keeps(char c) {
            return c < kept.length && kept[c];
        }
    }

    /**
     * Passes the request on unchanged when its target needs no escaping, otherwise wrapped so that its path and
     * query are escaped. A request without a query string keeps none.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestUri = request.getRequestURI();
        String queryString = request.getQueryString();
        String escapedUri = requestUri == null ? null : escape(requestUri, TargetComponent.PATH);
        String escapedQuery = queryString == null ? null : escape(queryString, TargetComponent.QUERY);
        if (Objects.equals(requestUri, escapedUri) && Objects.equals(queryString, escapedQuery)) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new EscapedTargetRequest(request, escapedUri, escapedQuery), response);
    }

    /**
     * Percent-encodes every character of {@code raw} that {@code component} does not keep, and every {@code %} not
     * followed by two hexadecimal digits as {@code %25}; a valid escape is kept as sent, letter case included. A
     * character up to {@code U+00FF} is encoded as the one byte of that value, which restores the byte the caller
     * sent, because the container reads each byte of the target as one ISO-8859-1 character. A character above it,
     * which only a request built outside the container holds, is encoded as its UTF-8 bytes, an unpaired surrogate as
     * {@code %3F}. Escapes this method writes use upper-case hexadecimal digits.
     *
     * @param raw       the path or the query string, without {@code ?}, as the container returns it
     * @param component the component {@code raw} is
     * @return {@code raw} itself when nothing in it needs encoding, otherwise the encoded string
     */
    static String escape(String raw, TargetComponent component) {
        StringBuilder escaped = null;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean kept = c == '%'
                    ? i + 2 < raw.length() && isHexDigit(raw.charAt(i + 1)) && isHexDigit(raw.charAt(i + 2))
                    : component.keeps(c);
            if (kept) {
                if (escaped != null) {
                    escaped.append(c);
                }
                continue;
            }
            if (escaped == null) {
                escaped = new StringBuilder(raw.length() + 16).append(raw, 0, i);
            }
            if (c <= 0xFF) {
                appendEscape(escaped, c);
            } else {
                int codePoint = raw.codePointAt(i);
                for (byte b : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                    appendEscape(escaped, b & 0xFF);
                }
                i += Character.charCount(codePoint) - 1;
            }
        }
        return escaped == null ? raw : escaped.toString();
    }

    /** ASCII hexadecimal digits only: {@link Character#digit} would also accept other scripts' digits. */
    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }

    private static void appendEscape(StringBuilder out, int octet) {
        out.append('%').append(HEX_DIGITS[octet >> 4]).append(HEX_DIGITS[octet & 0xF]);
    }

    /** The caller's request with its path and query escaped; everything else is the wrapped request's. */
    private static final class EscapedTargetRequest extends HttpServletRequestWrapper {

        private final String requestUri;

        private final String queryString;

        EscapedTargetRequest(HttpServletRequest request, String requestUri, String queryString) {
            super(request);
            this.requestUri = requestUri;
            this.queryString = queryString;
        }

        @Override
        public String getRequestURI() {
            return requestUri;
        }

        @Override
        public String getQueryString() {
            return queryString;
        }

        /**
         * The container's URL with its request URI, the part after the scheme and authority, replaced by the
         * escaped one. A container URL that does not end with the container's request URI, which the Servlet
         * specification rules out, is returned unchanged.
         */
        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = super.getRequestURL();
            String containerUri = super.getRequestURI();
            if (url != null && containerUri != null && requestUri != null && url.toString().endsWith(containerUri)) {
                url.setLength(url.length() - containerUri.length());
                url.append(requestUri);
            }
            return url;
        }
    }
}
