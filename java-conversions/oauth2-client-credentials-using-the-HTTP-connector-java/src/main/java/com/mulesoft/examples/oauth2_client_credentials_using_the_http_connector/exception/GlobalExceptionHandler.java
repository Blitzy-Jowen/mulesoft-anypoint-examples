/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.exception;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import jakarta.servlet.ServletException;
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
import org.springframework.web.servlet.HandlerMapping;
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
 *   <li>any other exception: 500, {@code application/json}, {@code { "message": "<exception message>" }}; an
 *       {@link Error} escaping a handler is reported with its own message, never with the text of the
 *       dispatcher's wrapper, which names the error's class, and a JDBC failure with the database driver's own
 *       message, never with the text of a data-access wrapper, which repeats the SQL statement.</li>
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

    /** Longest logged route pattern, in characters after escaping. */
    private static final int ROUTE_LOG_LIMIT = 256;

    /** Longest logged class, method or file name, in characters after escaping. */
    private static final int NAME_LOG_LIMIT = 256;

    /** Number of causes named after the outermost exception before the chain is cut short. */
    private static final int CAUSE_LOG_LIMIT = 10;

    /** Longest logged database diagnostic message, in characters after escaping. */
    private static final int DATABASE_MESSAGE_LOG_LIMIT = 256;

    private static final String NULL_LOG_TOKEN = "(null)";

    /** Logged in place of the route when the dispatcher matched no handler mapping. */
    private static final String NO_ROUTE_LOG_TOKEN = "(no route)";

    private static final String TRUNCATION_LOG_MARKER = "...[truncated]";

    private static final String CAUSE_SEPARATOR = " <- ";

    private static final String MORE_CAUSES_MARKER = "...";

    private static final String CIRCULAR_CAUSE_MARKER = "[circular]";

    private static final String SQL_STATE_LOG_PREFIX = "; SQL state ";

    private static final String ERROR_CODE_LOG_PREFIX = ", error code ";

    private static final String DATABASE_MESSAGE_LOG_PREFIX = ": ";

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
     * <p>The single log line names the request method and the matched route pattern, or {@code (no route)}
     * when the dispatcher matched no handler mapping, the exception's class with the frame that threw it, and
     * the classes of its causes in order, for example
     * {@code Unhandled exception processing PUT /teams/{teamId}: java.lang.IllegalStateException at
     * a.b.C.d(C.java:18) <- java.io.IOException}. The request's own path is never logged: its segments are
     * chosen by the caller and can carry personal data or tokens. The exception itself is not handed to the
     * logger, and no stack trace or suppressed exception is written.
     *
     * <p>No exception text is logged except the SQL state, error code and message of the innermost
     * {@link SQLException} in the chain: the database driver's account of the schema or connection state, which
     * the Mule default exception strategy logged, for example
     * {@code Unhandled exception processing GET /: org.springframework.jdbc.BadSqlGrammarException at
     * a.b.C.d(C.java:18) <- java.sql.SQLSyntaxErrorException; SQL state 42S02, error code 1146: Table
     * 'company.employees' doesn't exist}. Every other message and cause message stays out: those texts can
     * repeat submitted request content, credentials or tokens, and can carry line breaks that forge log lines.
     * The caller still receives the message in the response body. Every logged value is bounded in length, and
     * its control, line-separator, format and unpaired surrogate characters and reverse solidus are escaped, so
     * the event stays on one unambiguous line.
     *
     * <p>The {@code DispatcherServlet} hands an {@link Error} thrown by a handler, such as an
     * {@link OutOfMemoryError} or a {@link StackOverflowError}, to this method wrapped in a
     * {@link ServletException} whose message is {@code Handler dispatch failed: } followed by the error's
     * class name and message. The body reports the error's own message instead, so it never names a Java
     * class: {@code { "message": "Java heap space" }} for an {@code OutOfMemoryError("Java heap space")}.
     * The log line still names the wrapper and, as its cause, the error's class.
     *
     * @param ex the unhandled exception
     * @param request the failed request, whose method and matched route are logged
     * @return 500 {@code application/json} {@code { "message": "<exception message>" }}, carrying the
     *         {@link Error}'s own message when the exception is the dispatcher's wrapper of one and the
     *         database driver's message for a JDBC failure, with {@code { "message": null }} when that message
     *         is absent
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnhandled(Exception ex, HttpServletRequest request) {
        LOGGER.error("Unhandled exception processing {} {}: {}",
                sanitize(request.getMethod(), METHOD_LOG_LIMIT),
                route(request),
                describe(ex));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(ErrorResponse.MEDIA_TYPE)
                .body(ErrorResponse.json(responseMessage(ex)));
    }

    /**
     * Returns the message the 500 body reports for {@code ex}: the cause's message when {@code ex} is a
     * {@link ServletException} wrapping a {@link Throwable} that is not an {@link Exception}, which is how
     * the {@code DispatcherServlet} passes on an {@link Error} a handler threw; otherwise the message of the
     * innermost {@link SQLException} that {@link #databaseFailure} finds, the JDBC driver's own text, because a
     * data-access wrapper's message repeats the SQL statement; otherwise the message of {@code ex} itself. Any
     * of them may be {@code null}, and a {@code null} driver message is reported as such rather than replaced
     * by the wrapper's text.
     */
    private static String responseMessage(Exception ex) {
        Throwable cause = ex.getCause();
        if (ex instanceof ServletException && cause != null && !(cause instanceof Exception)) {
            return cause.getMessage();
        }
        SQLException database = databaseFailure(ex);
        if (database != null) {
            return database.getMessage();
        }
        return ex.getMessage();
    }

    /**
     * Returns the innermost {@link SQLException} among {@code ex} and its first {@link #CAUSE_LOG_LIMIT} causes,
     * the exceptions {@link #describe} names, or {@code null} when there is none. The innermost one is the
     * driver's own report: a connection pool's {@link SQLException} wraps the driver's, which names the actual
     * schema or connection failure. The walk stops at a cause already visited, so a circular chain terminates.
     */
    private static SQLException databaseFailure(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        SQLException innermost = null;
        Throwable current = ex;
        for (int depth = 0; current != null && depth <= CAUSE_LOG_LIMIT && seen.add(current); depth++) {
            if (current instanceof SQLException sql) {
                innermost = sql;
            }
            current = current.getCause();
        }
        return innermost;
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
     * Returns the escaped, bounded pattern of the handler mapping the dispatcher matched for {@code request}, such
     * as {@code /teams/{teamId}}, or {@code (no route)} when it matched none. The pattern is the one the
     * application's code declares, so it identifies the operation without any segment the caller chose.
     */
    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String route ? sanitize(route, ROUTE_LOG_LIMIT) : NO_ROUTE_LOG_TOKEN;
    }

    /**
     * Returns the type identifiers of {@code ex}: its class name and throw-site frame, then the class name of
     * each cause, separated by {@code " <- "}. The walk stops at a cause already named, marked
     * {@code [circular]}, and after {@link #CAUSE_LOG_LIMIT} causes, marked {@code ...}, so cyclic and very deep
     * chains terminate. Suppressed exceptions are not named. When {@link #databaseFailure} finds a
     * {@link SQLException} among the named exceptions, {@code "; SQL state <state>, error code <code>: <message>"}
     * of that one follows, its state and message escaped and bounded; no other exception's text is included.
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
        SQLException database = databaseFailure(ex);
        if (database != null) {
            out.append(SQL_STATE_LOG_PREFIX)
                    .append(sanitize(database.getSQLState(), NAME_LOG_LIMIT))
                    .append(ERROR_CODE_LOG_PREFIX)
                    .append(database.getErrorCode())
                    .append(DATABASE_MESSAGE_LOG_PREFIX)
                    .append(sanitize(database.getMessage(), DATABASE_MESSAGE_LOG_LIMIT));
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
