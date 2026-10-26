/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.exception;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

import com.mulesoft.examples.implementing_a_choice_exception_strategy.config.ReasonPhrase;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.controller.ValidationController;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The {@code choice-exception-strategy} of flow {@code choice-error-handlingFlow1}
 * ({@code implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml:15-47}).
 *
 * <p>Each catch branch set the flow variables {@code statusCode} and {@code reason}, which the listener's
 * {@code http:response-builder} put on the status line (lines 5-6), and set the payload to
 * {@code "<reason>: #[payload]"}. Here each branch is its own handler method, in the original declaration order:
 * {@link IllegalArgumentException} ("Catch Invalid Data", line 16) and {@link NullPointerException} ("Catch Missing
 * Data", line 31). The status code comes from the branch constant, the reason phrase is written through
 * {@link ReasonPhrase}, and the body renders the parsed {@code HashMap} payload that {@link ValidationController}
 * stored under {@link #PAYLOAD_ATTRIBUTE}. The class owns the four constant DataWeave scripts of the branches (lines
 * 20, 24, 35, 39) as constants.
 *
 * <p>Each branch logs one ERROR line: the branch name, then the exception's class with the frame that threw it and
 * the classes of its causes, for example {@code Catch Invalid Data: java.lang.NumberFormatException at
 * java.lang.NumberFormatException.forInputString(NumberFormatException.java:67)}. The exception is never handed to
 * the logger and no message, cause message, stack trace or payload is written, because the validator's messages
 * quote caller order values: a {@link NumberFormatException} repeats the rejected item value verbatim. Every logged
 * name is bounded in length and its control, line-separator and format characters, unpaired surrogates and reverse
 * solidus are escaped, so the event stays on one line.
 *
 * <p>The advice applies only to {@link ValidationController} and runs at {@code @Order(0)}, ahead of the SC-1
 * {@link GlobalExceptionHandler}. It declares only the two exception types the original branches name, so every other
 * failure, unreadable JSON included, still reaches the platform-default 500 of {@link GlobalExceptionHandler}, as an
 * exception no branch matched did in the original.
 */
@RestControllerAdvice(assignableTypes = ValidationController.class)
@Order(0)
public class ChoiceExceptionHandler {

    /**
     * Request attribute holding the {@code HashMap} payload that {@link ValidationController} stores before
     * validating; it stands for the Mule message payload the catch branches rendered as {@code #[payload]}.
     */
    public static final String PAYLOAD_ATTRIBUTE = ChoiceExceptionHandler.class.getName() + ".payload";

    /** {@code statusCode} of the "Catch Invalid Data" branch (line 20). */
    public static final String INVALID_DATA_STATUS_CODE = "400";

    /** {@code reason} of the "Catch Invalid Data" branch (line 24). */
    public static final String INVALID_DATA_REASON = "Invalid input data";

    /** {@code statusCode} of the "Catch Missing Data" branch (line 35). */
    public static final String MISSING_DATA_STATUS_CODE = "400";

    /** {@code reason} of the "Catch Missing Data" branch (line 39). */
    public static final String MISSING_DATA_REASON = "Missing input data";

    private static final Logger LOGGER = LoggerFactory.getLogger(ChoiceExceptionHandler.class);

    /** Longest logged class, method or file name, in characters after escaping. */
    private static final int NAME_LOG_LIMIT = 256;

    /** Number of causes named after the branch's exception before the chain is cut short. */
    private static final int CAUSE_LOG_LIMIT = 10;

    /**
     * Branch "Catch Invalid Data" ({@code when="#[exception.causedBy(java.lang.IllegalArgumentException)]"},
     * lines 16-30), which also catches the {@link NumberFormatException} of a non-integer item value. It logs the
     * exception's type, throw site and cause types at ERROR, never its message, which can quote the rejected value.
     *
     * @param ex      the validator's exception
     * @param request the failed request, carrying the payload under {@link #PAYLOAD_ATTRIBUTE}
     * @return 400 with reason {@code Invalid input data}, {@code text/plain;charset=UTF-8},
     *         {@code Invalid input data: <payload>}
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleInvalidInputData(IllegalArgumentException ex, HttpServletRequest request) {
        LOGGER.error("Catch Invalid Data: {}", describe(ex));
        ReasonPhrase.set(INVALID_DATA_REASON);
        return ResponseEntity.status(Integer.parseInt(INVALID_DATA_STATUS_CODE))
                .contentType(ValidationController.RESPONSE_CONTENT_TYPE)
                .body(INVALID_DATA_REASON + ": " + String.valueOf(request.getAttribute(PAYLOAD_ATTRIBUTE)));
    }

    /**
     * Branch "Catch Missing Data" ({@code when="#[exception.causedBy(java.lang.NullPointerException)]"},
     * lines 31-45). It logs the exception's type, throw site and cause types at ERROR, never its message.
     *
     * @param ex      the validator's exception
     * @param request the failed request, carrying the payload under {@link #PAYLOAD_ATTRIBUTE}
     * @return 400 with reason {@code Missing input data}, {@code text/plain;charset=UTF-8},
     *         {@code Missing input data: <payload>}
     */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<String> handleMissingInputData(NullPointerException ex, HttpServletRequest request) {
        LOGGER.error("Catch Missing Data: {}", describe(ex));
        ReasonPhrase.set(MISSING_DATA_REASON);
        return ResponseEntity.status(Integer.parseInt(MISSING_DATA_STATUS_CODE))
                .contentType(ValidationController.RESPONSE_CONTENT_TYPE)
                .body(MISSING_DATA_REASON + ": " + String.valueOf(request.getAttribute(PAYLOAD_ATTRIBUTE)));
    }

    /**
     * Returns the type identifiers of {@code ex} without any of its text: its class name and throw-site frame, then
     * the class name of each cause, separated by {@code " <- "}. The walk stops at a cause already named, marked
     * {@code [circular]}, and after {@link #CAUSE_LOG_LIMIT} causes, marked {@code ...}, so cyclic and very deep
     * chains terminate. Suppressed exceptions are not named.
     */
    private static String describe(Throwable ex) {
        StringBuilder out = new StringBuilder(128).append(sanitize(ex.getClass().getName()));
        appendThrowSite(out, ex);
        Set<Throwable> named = Collections.newSetFromMap(new IdentityHashMap<>());
        named.add(ex);
        int causes = 0;
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            out.append(" <- ");
            if (causes == CAUSE_LOG_LIMIT) {
                out.append("...");
                break;
            }
            if (!named.add(cause)) {
                out.append("[circular]");
                break;
            }
            out.append(sanitize(cause.getClass().getName()));
            causes++;
        }
        return out.toString();
    }

    /**
     * Appends {@code " at <class>.<method>(<file>:<line>)"} for the frame that threw {@code ex}, in the form
     * {@link StackTraceElement#toString()} uses but without its class loader and module prefix, or nothing when the
     * exception has no stack trace.
     */
    private static void appendThrowSite(StringBuilder out, Throwable ex) {
        StackTraceElement[] trace = ex.getStackTrace();
        if (trace == null || trace.length == 0 || trace[0] == null) {
            return;
        }
        StackTraceElement frame = trace[0];
        out.append(" at ").append(sanitize(frame.getClassName())).append('.')
                .append(sanitize(frame.getMethodName())).append('(');
        if (frame.isNativeMethod()) {
            out.append("Native Method");
        } else if (frame.getFileName() == null) {
            out.append("Unknown Source");
        } else {
            out.append(sanitize(frame.getFileName()));
            if (frame.getLineNumber() >= 0) {
                out.append(':').append(frame.getLineNumber());
            }
        }
        out.append(')');
    }

    /**
     * Makes a name safe to log on one line: ISO control characters, line and paragraph separators, format
     * characters, unpaired surrogates and the reverse solidus are each written as {@code \}{@code u} and four
     * lower-case hex digits per UTF-16 unit, and a value longer than {@link #NAME_LOG_LIMIT} characters after
     * escaping is cut before the first character or escape that would exceed it, then marked
     * {@code ...[truncated]}. {@code null} is written as {@code (null)}.
     */
    private static String sanitize(String value) {
        if (value == null) {
            return "(null)";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), NAME_LOG_LIMIT));
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            int units = Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            boolean escape = codePoint == '\\' || Character.isISOControl(codePoint)
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.FORMAT || type == Character.SURROGATE;
            if (out.length() + (escape ? 6 * units : units) > NAME_LOG_LIMIT) {
                return out.append("...[truncated]").toString();
            }
            for (int unit = i; unit < i + units; unit++) {
                char c = value.charAt(unit);
                if (escape) {
                    out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                } else {
                    out.append(c);
                }
            }
            i += units;
        }
        return out.toString();
    }
}
