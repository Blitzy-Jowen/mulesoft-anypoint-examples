/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.exception;

import org.springframework.http.MediaType;

/**
 * Builds the {@code { "message": ... }} JSON error body (shared component SC-1).
 *
 * <p>The spacing is APIkit's, byte for byte, as the original exception mappings write it, for example
 * {@code { "message": "Resource not found" }}: one space after the opening brace, one after the colon
 * and one before the closing brace. A JSON library would serialize the same value without those
 * spaces, so the body is assembled by hand and only the message is escaped.
 *
 * <p>The class depends on the JDK and {@link MediaType} alone so that every project can copy it with
 * only its {@code package} line changed. It shares its simple name with Spring's
 * {@code org.springframework.web.ErrorResponse}, which must therefore never be imported here.
 */
public final class ErrorResponse {

    /** Content-Type of every body this class produces. */
    public static final MediaType MEDIA_TYPE = MediaType.APPLICATION_JSON;

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private ErrorResponse() {
    }

    /**
     * Returns the error body for {@code message}.
     *
     * @param message the message to report; {@code null} is written as the JSON literal {@code null}
     * @return {@code { "message": "<escaped message>" }}, or {@code { "message": null }} when
     *         {@code message} is {@code null}
     */
    public static String json(String message) {
        if (message == null) {
            return "{ \"message\": null }";
        }
        return "{ \"message\": \"" + escape(message) + "\" }";
    }

    /**
     * Escapes {@code s} for use inside a JSON string literal.
     *
     * <p>Quotation mark, reverse solidus and the control characters U+0000 to U+001F are escaped as
     * RFC 8259 requires, using the two-character forms where they exist and otherwise the six-character
     * form (reverse solidus, {@code u}, four lower-case hex digits). U+2028 and U+2029 are escaped in the
     * six-character form as well, because JavaScript engines before ES2019 reject them unescaped inside
     * string literals. Every other character, {@code /} and non-ASCII text included, is copied unchanged.
     */
    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\u2028', '\u2029' -> appendUnicodeEscape(out, c);
                default -> {
                    if (c < 0x20) {
                        appendUnicodeEscape(out, c);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static void appendUnicodeEscape(StringBuilder out, char c) {
        out.append("\\u")
                .append(HEX_DIGITS[(c >> 12) & 0xF])
                .append(HEX_DIGITS[(c >> 8) & 0xF])
                .append(HEX_DIGITS[(c >> 4) & 0xF])
                .append(HEX_DIGITS[c & 0xF]);
    }
}
