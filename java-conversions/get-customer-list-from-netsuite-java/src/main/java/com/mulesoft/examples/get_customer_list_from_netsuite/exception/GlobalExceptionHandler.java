/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.exception;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Generic Spring MVC error handling: shared component SC-1 of the conversion plan.
 *
 * <p>Answers what the Mule HTTP listener and runtime answered by default when no flow shaped the error:
 * <ul>
 *   <li>a path no handler serves: 404, {@code text/plain}, {@code No listener for endpoint: <path>};</li>
 *   <li>a method the handler does not accept: 405, {@code text/plain},
 *       {@code Method not allowed for endpoint: <path>};</li>
 *   <li>any other exception: 500, {@code application/json}, {@code { "message": "<exception message>" }}.</li>
 * </ul>
 * These bytes are platform defaults rather than flow output, so a captured fixture of the original
 * application overrides them.
 *
 * <p>The advice runs last ({@link Ordered#LOWEST_PRECEDENCE}): Spring uses the first advice in order that
 * declares a matching method, and the {@link Exception} method here matches everything, so every
 * project-specific, APIkit or upstream handler must be able to claim an exception first. It deliberately
 * does not extend {@code ResponseEntityExceptionHandler}, whose handlers for the other MVC exceptions would
 * answer with ProblemDetail bodies instead of the shapes above.
 *
 * <p>Every project with a Spring MVC controller carries a copy that differs from this one in the
 * {@code package} line alone, so the class depends only on the JDK, Spring Web, the Servlet API, SLF4J and
 * {@link ErrorResponse}. Each response presets its Content-Type, which makes Spring write the body without
 * consulting the request's {@code Accept} header, so an error response can never become a 406.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    private static final String METHOD_NOT_ALLOWED_PREFIX = "Method not allowed for endpoint: ";

    /** Longest logged request method, in characters after escaping. */
    private static final int METHOD_LOG_LIMIT = 32;

    /** Longest logged request path, in characters after escaping. */
    private static final int PATH_LOG_LIMIT = 256;

    /** Longest logged class, method or file name, in characters after escaping. */
    private static final int NAME_LOG_LIMIT = 256;

    /** Number of causes named after the outermost exception before the chain is cut short. */
    private static final int CAUSE_LOG_LIMIT = 10;

    private static final String NULL_LOG_TOKEN = "(null)";

    private static final String TRUNCATION_LOG_MARKER = "...[truncated]";

    private static final String CAUSE_SEPARATOR = " <- ";

    private static final String MORE_CAUSES_MARKER = "...";

    private static final String CIRCULAR_CAUSE_MARKER = "[circular]";

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /**
     * Answers a request for a path no controller or resource handler serves.
     *
     * @param ex the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised by the dispatcher
     * @param request the rejected request, whose path is reported
     * @return 404 {@code text/plain} {@code No listener for endpoint: <path>}
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<String> handleNoListener(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN)
                .body(NO_LISTENER_PREFIX + path(request));
    }

    /**
     * Answers a request whose method the mapped handler does not accept. No {@code Allow} header is sent,
     * because the Mule listener sent none.
     *
     * @param ex the exception raised by the handler mapping
     * @param request the rejected request, whose path is reported
     * @return 405 {@code text/plain} {@code Method not allowed for endpoint: <path>}
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<String> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .contentType(MediaType.TEXT_PLAIN)
                .body(METHOD_NOT_ALLOWED_PREFIX + path(request));
    }

    /**
     * Answers any exception no other advice claims, logging it at ERROR as the Mule default exception
     * strategy logged unhandled exceptions.
     *
     * <p>The single log line names the request method and path, the exception's class with the frame that
     * threw it, and the classes of its causes in order, for example
     * {@code Unhandled exception processing POST /teams: java.lang.IllegalStateException at
     * a.b.C.d(C.java:18) <- java.io.IOException}. The exception itself is not handed to the logger, and
     * no message, cause message, suppressed exception or stack trace is written: those texts can repeat
     * submitted request content, credentials or tokens, and can carry line breaks that forge log lines.
     * The caller still receives the message in the response body. Every logged value is bounded in
     * length, and its control, line-separator, format and unpaired surrogate characters and reverse
     * solidus are escaped, so the event stays on one unambiguous line.
     *
     * @param ex the unhandled exception
     * @param request the failed request, whose method and path are logged
     * @return 500 {@code application/json} {@code { "message": "<exception message>" }}, with
     *         {@code { "message": null }} when the exception carries no message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnhandled(Exception ex, HttpServletRequest request) {
        LOGGER.error("Unhandled exception processing {} {}: {}",
                sanitize(request.getMethod(), METHOD_LOG_LIMIT),
                sanitize(path(request), PATH_LOG_LIMIT),
                describe(ex));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(ErrorResponse.MEDIA_TYPE)
                .body(ErrorResponse.json(ex.getMessage()));
    }

    /**
     * Returns the raw request URI, without query string, relative to the context path, which is how the
     * Mule listener reported the endpoint.
     */
    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /**
     * Returns the type identifiers of {@code ex} without any of its text: its class name and throw-site
     * frame, then the class name of each cause, separated by {@code " <- "}. The walk stops at a cause
     * already named, marked {@code [circular]}, and after {@link #CAUSE_LOG_LIMIT} causes, marked
     * {@code ...}, so cyclic and very deep chains terminate. Suppressed exceptions are not named.
     */
    private static String describe(Throwable ex) {
        StringBuilder out = new StringBuilder(128);
        out.append(sanitize(ex.getClass().getName(), NAME_LOG_LIMIT));
        appendThrowSite(out, ex);
        Set<Throwable> named = Collections.newSetFromMap(new IdentityHashMap<>());
        named.add(ex);
        int causes = 0;
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            out.append(CAUSE_SEPARATOR);
            if (causes == CAUSE_LOG_LIMIT) {
                out.append(MORE_CAUSES_MARKER);
                break;
            }
            if (!named.add(cause)) {
                out.append(CIRCULAR_CAUSE_MARKER);
                break;
            }
            out.append(sanitize(cause.getClass().getName(), NAME_LOG_LIMIT));
            causes++;
        }
        return out.toString();
    }

    /**
     * Appends {@code " at <class>.<method>(<file>:<line>)"} for the frame that threw {@code ex}, in the form
     * {@link StackTraceElement#toString()} uses but without its class loader and module prefix, or nothing
     * when the exception has no stack trace.
     */
    private static void appendThrowSite(StringBuilder out, Throwable ex) {
        StackTraceElement[] trace = ex.getStackTrace();
        if (trace == null || trace.length == 0 || trace[0] == null) {
            return;
        }
        StackTraceElement frame = trace[0];
        out.append(" at ")
                .append(sanitize(frame.getClassName(), NAME_LOG_LIMIT))
                .append('.')
                .append(sanitize(frame.getMethodName(), NAME_LOG_LIMIT))
                .append('(');
        if (frame.isNativeMethod()) {
            out.append("Native Method");
        } else if (frame.getFileName() == null) {
            out.append("Unknown Source");
        } else {
            out.append(sanitize(frame.getFileName(), NAME_LOG_LIMIT));
            if (frame.getLineNumber() >= 0) {
                out.append(':').append(frame.getLineNumber());
            }
        }
        out.append(')');
    }

    /**
     * Makes {@code value} safe to log on one line.
     *
     * <p>ISO control characters (C0, DEL and C1, so CR, LF, TAB, NUL and NEL included), line and paragraph
     * separators, format characters (bidirectional overrides and isolates, zero-width characters, the byte
     * order mark), unpaired surrogates and the reverse solidus are each written as {@code \}{@code u} and
     * four lower-case hex digits per UTF-16 unit; escaping the reverse solidus keeps an escape in the log
     * distinguishable from the same six characters in the input. Every other character, a valid surrogate
     * pair included, is copied unchanged.
     *
     * @param value the value to log; {@code null} is written as {@code (null)}
     * @param limit the most characters the escaped value may occupy; a longer value is cut before the
     *              first character or escape that would exceed it, never inside a surrogate pair or an
     *              escape, and {@code ...[truncated]} is appended
     */
    private static String sanitize(String value, int limit) {
        if (value == null) {
            return NULL_LOG_TOKEN;
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), limit) + TRUNCATION_LOG_MARKER.length());
        int i = 0;
        while (i < value.length()) {
            int codePoint = value.codePointAt(i);
            int units = Character.charCount(codePoint);
            boolean escape = mustEscape(codePoint);
            if (out.length() + (escape ? 6 * units : units) > limit) {
                return out.append(TRUNCATION_LOG_MARKER).toString();
            }
            for (int unit = i; unit < i + units; unit++) {
                if (escape) {
                    appendUnicodeEscape(out, value.charAt(unit));
                } else {
                    out.append(value.charAt(unit));
                }
            }
            i += units;
        }
        return out.toString();
    }

    private static boolean mustEscape(int codePoint) {
        if (codePoint == '\\' || Character.isISOControl(codePoint)) {
            return true;
        }
        int type = Character.getType(codePoint);
        return type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.FORMAT
                || type == Character.SURROGATE;
    }

    private static void appendUnicodeEscape(StringBuilder out, char c) {
        out.append("\\u")
                .append(HEX_DIGITS[(c >> 12) & 0xF])
                .append(HEX_DIGITS[(c >> 8) & 0xF])
                .append(HEX_DIGITS[(c >> 4) & 0xF])
                .append(HEX_DIGITS[c & 0xF]);
    }
}
