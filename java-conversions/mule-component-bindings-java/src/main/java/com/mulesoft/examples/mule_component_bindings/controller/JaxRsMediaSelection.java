/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.controller;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * The request media checks with which the JAX-RS runtime of the original (Jersey 2.11) selected the resource method
 * {@code StockStatsResource.search}, declared {@code @Consumes("application/json")} and
 * {@code @Produces("application/json")}, before invoking it. A request it rejects never reaches the resource, so no
 * upstream is called.
 *
 * <p>The checks run in the runtime's order, the first failing one deciding the status:
 * <ol>
 *   <li>{@code Content-Type}: absent passes; more than one header, or a value that is not
 *       {@code type/subtype *(; name=value)}, is 400; a type not compatible with {@code application/json} is 415.</li>
 *   <li>{@code Accept}: every value joined with {@code ,}; absent or empty admits every type. A list that does not
 *       parse, or an element whose {@code q} is not a quality value, is 400; a list without an element compatible
 *       with {@code application/json} is 406, an empty list included. An element without {@code /} is a type with
 *       subtype {@code *}, and the quality of a compatible element does not matter: with a single resource method,
 *       {@code q=0} still selects it.</li>
 *   <li>A {@code q} parameter of the {@code Content-Type} is read last, once both media types matched; a value that
 *       is not a quality value fails the request with an {@link IllegalArgumentException}, as the runtime failed it
 *       with an unmapped exception.</li>
 * </ol>
 *
 * <p>Compatibility is {@code javax.ws.rs.core.MediaType.isCompatible}: either type is {@code *}, or the types are
 * equal ignoring case and either subtype is {@code *} or the subtypes are equal ignoring case. Parameters are ignored
 * and structured-syntax suffixes have no meaning, so {@code application/vnd.x+json} is not compatible.
 *
 * <p>Values are parsed with the runtime's header grammar: tokens of visible ASCII characters other than the
 * separators {@code ( ) < > @ , ; : \ " / [ ] ? = { }}; white space (space, tab, CR, LF) only before a type, around
 * {@code ;} and {@code ,} and at the end, never inside {@code type/subtype} or around {@code =}; parameter values a
 * token or a quoted string with backslash escapes; no comments; a parameter name compared ignoring case, a repeated
 * one keeping its last value. A quality value is {@code 0} or {@code 1}, optionally followed by {@code .} and
 * digits, or {@code .} followed by digits, at most five characters, and not above 1.
 */
final class JaxRsMediaSelection {

    private static final String WILDCARD = "*";

    private static final String JSON_TYPE = "application";

    private static final String JSON_SUBTYPE = "json";

    private static final String QUALITY_PARAMETER = "q";

    private JaxRsMediaSelection() {
    }

    /**
     * Applies the checks to the {@code Content-Type} and {@code Accept} headers of {@code request}.
     *
     * @param request the request the resource method would serve
     * @return the status the runtime answered without invoking the method, or empty when the method is selected
     * @throws IllegalArgumentException when the {@code Content-Type} carries a {@code q} that is not a quality value
     */
    static Optional<HttpStatus> rejection(HttpServletRequest request) {
        return rejection(headerValues(request, HttpHeaders.CONTENT_TYPE), headerValues(request, HttpHeaders.ACCEPT));
    }

    /**
     * Applies the checks to header values.
     *
     * @param contentTypes every {@code Content-Type} header value in arrival order, empty when there is none
     * @param accepts      every {@code Accept} header value in arrival order, empty when there is none
     * @return the status the runtime answered without invoking the method, or empty when the method is selected
     * @throws IllegalArgumentException when the {@code Content-Type} carries a {@code q} that is not a quality value
     */
    static Optional<HttpStatus> rejection(List<String> contentTypes, List<String> accepts) {
        MediaRange contentType = null;
        if (!contentTypes.isEmpty()) {
            if (contentTypes.size() > 1) {
                return Optional.of(HttpStatus.BAD_REQUEST);
            }
            try {
                contentType = MediaRange.parse(new HeaderReader(contentTypes.get(0), false), false);
            } catch (ParseException e) {
                return Optional.of(HttpStatus.BAD_REQUEST);
            }
            if (!contentType.isCompatibleWithJson()) {
                return Optional.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            }
        }

        List<MediaRange> acceptable;
        try {
            acceptable = acceptableRanges(String.join(",", accepts));
        } catch (ParseException e) {
            return Optional.of(HttpStatus.BAD_REQUEST);
        }
        if (acceptable.stream().noneMatch(MediaRange::isCompatibleWithJson)) {
            return Optional.of(HttpStatus.NOT_ACCEPTABLE);
        }

        if (contentType != null && contentType.quality != null) {
            try {
                checkQuality(contentType.quality);
            } catch (ParseException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return Optional.empty();
    }

    private static List<String> headerValues(HttpServletRequest request, String name) {
        // A container may deny access to the headers and return null; that reads as no header at all.
        Enumeration<String> values = request.getHeaders(name);
        return values == null ? List.of() : Collections.list(values);
    }

    /** Parses a joined {@code Accept} value: an empty value means every type. */
    private static List<MediaRange> acceptableRanges(String accept) throws ParseException {
        if (accept.isEmpty()) {
            return List.of(new MediaRange(WILDCARD, WILDCARD, null));
        }
        HeaderReader reader = new HeaderReader(accept, true);
        List<MediaRange> ranges = new ArrayList<>();
        while (reader.rawHasNext()) {
            MediaRange range = MediaRange.parse(reader, true);
            if (range.quality != null) {
                checkQuality(range.quality);
            }
            ranges.add(range);
            reader.resetElement();
            if (reader.rawHasNext()) {
                // The ',' ending the element.
                reader.rawNext(true);
            }
        }
        return ranges;
    }

    /**
     * Checks a quality value as the runtime read it.
     *
     * @throws ParseException with the runtime's message when {@code q} is not a quality value
     */
    private static void checkQuality(String q) throws ParseException {
        if (q.isEmpty()) {
            throw new ParseException("Quality value cannot be null or an empty String", 0);
        }
        int length = q.length();
        if (length > 5) {
            throw new ParseException("Quality value is greater than the maximum length, 5", 0);
        }
        int index = 0;
        char wholeNumber = q.charAt(index++);
        if (wholeNumber == '0' || wholeNumber == '1') {
            if (index == length) {
                return;
            }
            char c = q.charAt(index++);
            if (c != '.') {
                throw new ParseException(
                        "Error parsing Quality value: a decimal place is expected rather than '" + c + "'", index);
            }
        } else if (wholeNumber == '.') {
            if (index == length) {
                throw new ParseException(
                        "Error parsing Quality value: a decimal numeral is expected after the decimal point", index);
            }
        } else {
            throw new ParseException("Error parsing Quality value: a decimal numeral '0' or '1' is expected rather "
                    + "than '" + wholeNumber + "'", index);
        }
        boolean nonZeroFraction = false;
        while (index < length) {
            char c = q.charAt(index++);
            if (c < '0' || c > '9') {
                throw new ParseException(
                        "Error parsing Quality value: a decimal numeral is expected rather than '" + c + "'", index);
            }
            nonZeroFraction |= c != '0';
        }
        if (wholeNumber == '1' && nonZeroFraction) {
            throw new ParseException("The Quality value, " + q + ", is greater than 1", index);
        }
    }

    /** A parsed media type or media range: its type, subtype and the last {@code q} parameter, if any. */
    private static final class MediaRange {

        private final String type;
        private final String subtype;
        /** The raw value of the last {@code q} parameter, or {@code null} when there is none. */
        private final String quality;

        private MediaRange(String type, String subtype, String quality) {
            this.type = type;
            this.subtype = subtype;
            this.quality = quality;
        }

        /**
         * Parses {@code type/subtype *(; name=value)} from {@code reader}, which must be consumed to its end (or, in
         * a list, to the {@code ,} ending the element).
         *
         * @param subtypeOptional {@code true} for an {@code Accept} element, where a bare type means subtype
         *                        {@code *}
         */
        static MediaRange parse(HeaderReader reader, boolean subtypeOptional) throws ParseException {
            reader.hasNext();
            String type = reader.nextToken();
            String subtype;
            if (subtypeOptional) {
                subtype = WILDCARD;
                if (reader.hasNextSeparator('/', false)) {
                    reader.next(false);
                    subtype = reader.nextToken();
                }
            } else {
                reader.nextSeparator('/');
                subtype = reader.nextToken();
            }
            String quality = null;
            if (reader.hasNext()) {
                quality = readParameters(reader);
            }
            return new MediaRange(type, subtype, quality);
        }

        /** Reads {@code *(; name=value)}, ignoring empty {@code ;}s, and returns the last {@code q} value. */
        private static String readParameters(HeaderReader reader) throws ParseException {
            String quality = null;
            while (reader.hasNext()) {
                reader.nextSeparator(';');
                while (reader.hasNextSeparator(';', true)) {
                    reader.next(true);
                }
                if (!reader.hasNext()) {
                    break;
                }
                String name = reader.nextToken();
                reader.nextSeparator('=');
                String value = reader.nextTokenOrQuotedString();
                if (QUALITY_PARAMETER.equals(name.toLowerCase(Locale.ROOT))) {
                    quality = value;
                }
            }
            return quality;
        }

        boolean isCompatibleWithJson() {
            return WILDCARD.equals(type)
                    || (JSON_TYPE.equalsIgnoreCase(type)
                            && (WILDCARD.equals(subtype) || JSON_SUBTYPE.equalsIgnoreCase(subtype)));
        }
    }

    /**
     * The runtime's header tokenizer. In list mode an element ends at a {@code ,}: the element-level methods then
     * report the end of input, and {@link #rawHasNext()} and {@link #rawNext(boolean)} step over the separator.
     */
    private static final class HeaderReader {

        private enum Event { TOKEN, QUOTED_STRING, SEPARATOR, CONTROL }

        private final String header;
        private final int length;
        private final boolean list;
        private int index;
        private boolean elementEnded;
        private String value;

        HeaderReader(String header, boolean list) {
            this.header = header;
            this.length = header.length();
            this.list = list;
        }

        boolean hasNext() {
            if (!list) {
                return rawHasNext();
            }
            if (elementEnded) {
                return false;
            }
            if (rawHasNext()) {
                if (rawHasNextSeparator(',', true)) {
                    elementEnded = true;
                    return false;
                }
                return true;
            }
            return false;
        }

        boolean hasNextSeparator(char separator, boolean skipWhiteSpace) {
            if (!list) {
                return rawHasNextSeparator(separator, skipWhiteSpace);
            }
            if (elementEnded) {
                return false;
            }
            if (rawHasNextSeparator(',', skipWhiteSpace)) {
                elementEnded = true;
                return false;
            }
            return rawHasNextSeparator(separator, skipWhiteSpace);
        }

        Event next(boolean skipWhiteSpace) throws ParseException {
            if (list) {
                if (elementEnded) {
                    throw new ParseException("End of header", index);
                }
                if (rawHasNextSeparator(',', skipWhiteSpace)) {
                    elementEnded = true;
                    throw new ParseException("End of header", index);
                }
            }
            return rawNext(skipWhiteSpace);
        }

        String nextToken() throws ParseException {
            if (next(false) != Event.TOKEN) {
                throw new ParseException("Next event is not a Token", index);
            }
            return value;
        }

        void nextSeparator(char separator) throws ParseException {
            if (next(false) != Event.SEPARATOR) {
                throw new ParseException("Next event is not a Separator", index);
            }
            if (value.charAt(0) != separator) {
                throw new ParseException(
                        "Expected separator '" + separator + "' instead of '" + value.charAt(0) + "'", index);
            }
        }

        String nextTokenOrQuotedString() throws ParseException {
            Event event = next(false);
            if (event != Event.TOKEN && event != Event.QUOTED_STRING) {
                throw new ParseException("Next event is not a Token or a Quoted String, " + value, index);
            }
            return value;
        }

        void resetElement() {
            elementEnded = false;
        }

        boolean rawHasNext() {
            return skipWhiteSpace();
        }

        boolean rawHasNextSeparator(char separator, boolean skipWhiteSpace) {
            if (skipWhiteSpace) {
                skipWhiteSpace();
            }
            if (index >= length) {
                return false;
            }
            char c = header.charAt(index);
            return isSeparator(c) && c == separator;
        }

        Event rawNext(boolean skipWhiteSpace) throws ParseException {
            if (skipWhiteSpace) {
                skipWhiteSpace();
            }
            if (index >= length) {
                throw new ParseException("End of header.", index);
            }
            char c = header.charAt(index);
            if (c > 127 || (c < 32 && !isWhiteSpace(c)) || c == 127) {
                index++;
                value = String.valueOf(c);
                return Event.CONTROL;
            }
            if (isWhiteSpace(c)) {
                throw new ParseException("White space is not allowed", index);
            }
            if (c == '(') {
                throw new ParseException("Comments are not allowed", index);
            }
            if (c == '"') {
                readQuotedString();
                return Event.QUOTED_STRING;
            }
            if (isSeparator(c)) {
                index++;
                value = String.valueOf(c);
                return Event.SEPARATOR;
            }
            int start = index++;
            while (index < length && isToken(header.charAt(index))) {
                index++;
            }
            value = header.substring(start, index);
            return Event.TOKEN;
        }

        private boolean skipWhiteSpace() {
            for (; index < length; index++) {
                if (!isWhiteSpace(header.charAt(index))) {
                    return true;
                }
            }
            return false;
        }

        /** Reads a quoted string, the opening quote at {@link #index}, dropping each escaping backslash and CR. */
        private void readQuotedString() throws ParseException {
            boolean filter = false;
            int start = ++index;
            for (; index < length; index++) {
                char c = header.charAt(index);
                if (c == '\\') {
                    index++;
                    filter = true;
                } else if (c == '\r') {
                    filter = true;
                } else if (c == '"') {
                    value = filter ? unescape(start, index) : header.substring(start, index);
                    index++;
                    return;
                }
            }
            throw new ParseException("Unbalanced quoted string", index);
        }

        /** The quoted text between {@code start} and {@code end}: escapes resolved, unescaped CR and CRLF dropped. */
        private String unescape(int start, int end) {
            StringBuilder out = new StringBuilder(end - start);
            boolean escaped = false;
            boolean afterCr = false;
            for (int i = start; i < end; i++) {
                char c = header.charAt(i);
                if (c == '\n' && afterCr) {
                    afterCr = false;
                    continue;
                }
                afterCr = false;
                if (escaped) {
                    out.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '\r') {
                    afterCr = true;
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        private static boolean isWhiteSpace(char c) {
            return c == ' ' || c == '\t' || c == '\r' || c == '\n';
        }

        /** The grammar's separators; {@code (} and {@code "} open a comment and a quoted string instead. */
        private static boolean isSeparator(char c) {
            switch (c) {
                case ')': case '<': case '>': case '@': case ',': case ';': case ':': case '\\':
                case '/': case '[': case ']': case '?': case '=': case '{': case '}':
                    return true;
                default:
                    return false;
            }
        }

        private static boolean isToken(char c) {
            return c > 32 && c < 127 && c != '(' && c != '"' && !isSeparator(c);
        }
    }
}
