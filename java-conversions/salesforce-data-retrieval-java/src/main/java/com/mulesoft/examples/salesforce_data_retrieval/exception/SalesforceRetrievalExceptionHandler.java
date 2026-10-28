/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.exception;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;

import com.mulesoft.examples.salesforce_data_retrieval.controller.SalesforceRetrievalController;

/**
 * The exception handling of the two flows of {@code salesforce-id-retrieval.xml}, scoped to
 * {@link SalesforceRetrievalController}.
 *
 * <ul>
 *   <li>{@code salesforceDataRetrievalFlow} (POST {@code /}) declares a {@code catch-exception-strategy} whose only
 *       processor is {@code set-payload value="Invalid Salesforce query."} (xml:37-39), after which the listener
 *       answered 200 with that text. Every failure of {@link SalesforceRetrievalController#query} is answered the
 *       same way: the Salesforce faults the upstream executor rethrows unchanged ({@code INVALID_LOGIN},
 *       {@code INVALID_TYPE}, {@code INVALID_FIELD}, {@code MALFORMED_QUERY}, a duplicate {@code Id} or {@code Name}
 *       field), the {@link IllegalArgumentException} of the object and field identifier checks, an absent
 *       {@code field} (the original's MEL {@code payload.field.isEmpty()} failure) and any failure while the
 *       request is bound.</li>
 *   <li>{@code showFormFlow} (GET {@code /}) declares no exception strategy, so its failures get exactly the
 *       platform-default 500 of {@link GlobalExceptionHandler} (AAP 0.4.7), which this advice delegates to rather
 *       than rendering a second copy.</li>
 * </ul>
 *
 * <p>Precedence (AAP 0.4.7, 0.4.8): {@code UpstreamExceptionHandler} runs first, so an authentication failure that
 * survived the retry, throttling and an unavailable upstream reach the caller as 502, 429 and 503 even when they
 * are the cause of another exception, ahead of the catch-all here. This advice runs at {@code @Order(0)}, ahead of
 * {@link GlobalExceptionHandler}. Being limited to {@link SalesforceRetrievalController}, it never sees exceptions
 * raised without that controller as the handler: an unserved path, a method neither listener accepts, and the
 * controller's own {@code HEAD}/{@code OPTIONS} guard all keep SC-1's 404 and 405. The controller's local handler
 * for an undecodable form body runs before every advice and keeps the listener's 400.
 *
 * <p>Stateless and thread-safe.
 */
@RestControllerAdvice(assignableTypes = SalesforceRetrievalController.class)
@Order(0)
public class SalesforceRetrievalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SalesforceRetrievalExceptionHandler.class);

    /** The payload the POST flow's catch branch sets (xml:38). */
    static final String INVALID_QUERY_MESSAGE = "Invalid Salesforce query.";

    /**
     * Content-Type of the catch branch's reply. The original {@code set-payload} names no MIME type and left it to
     * the platform, so this value is a Tier 2A verification point: a captured fixture of the original governs where
     * it differs.
     */
    static final MediaType TEXT_PLAIN_UTF8 = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    /** Name of the controller method that serves {@code salesforceDataRetrievalFlow}. */
    private static final String QUERY_METHOD = "query";

    /** Number of causes named after the outermost exception before the chain is cut short. */
    private static final int CAUSE_LOG_LIMIT = 10;

    private final GlobalExceptionHandler globalExceptionHandler;

    /**
     * @param globalExceptionHandler SC-1's advice, whose catch-all answers the GET flow's failures
     */
    public SalesforceRetrievalExceptionHandler(GlobalExceptionHandler globalExceptionHandler) {
        this.globalExceptionHandler = globalExceptionHandler;
    }

    /**
     * Answers a failure of either controller method with its flow's original reply.
     *
     * <p>For {@link SalesforceRetrievalController#query} the failure is logged once at ERROR, as Mule's catch
     * strategy logged the exception it caught, and the reply is 200 {@code text/plain;charset=UTF-8} with the body
     * bytes {@code Invalid Salesforce query.} and no line break. Any other method gets
     * {@link GlobalExceptionHandler#handleUnhandled}'s reply unchanged: 500 {@code application/json}
     * {@code { "message": "<exception message>" }}.
     *
     * @param ex            the failure the controller method raised
     * @param handlerMethod the controller method that raised it
     * @param request       the failed request, passed on to SC-1 for its log line
     * @return the flow's reply
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handle(Exception ex, HandlerMethod handlerMethod, HttpServletRequest request) {
        if (isQuery(handlerMethod)) {
            // Only type identifiers are logged, never the message or the exception itself: Salesforce fault text
            // repeats the SOQL with the caller's search value, and the identifier checks quote the caller's field
            // list, so either could put caller text, line breaks included, into the log. The project's other
            // handlers and the upstream executor log failures the same way.
            log.error("Salesforce query failed: {}", describe(ex));
            return ResponseEntity.ok().contentType(TEXT_PLAIN_UTF8).body(INVALID_QUERY_MESSAGE);
        }
        return globalExceptionHandler.handleUnhandled(ex, request);
    }

    /** Whether {@code handlerMethod} is the controller method of the POST flow, the only flow with a catch branch. */
    private static boolean isQuery(HandlerMethod handlerMethod) {
        return handlerMethod != null
                && SalesforceRetrievalController.class.equals(handlerMethod.getBeanType())
                && QUERY_METHOD.equals(handlerMethod.getMethod().getName());
    }

    /**
     * Returns the type identifiers of {@code ex}: its class name and the frame that threw it, then the class name of
     * each cause, separated by {@code " <- "}, for example {@code com.sforce.soap.partner.fault.InvalidFieldFault at
     * com.sforce.ws.transport.SoapConnection.parseDetail(SoapConnection.java:247)}. The walk stops at a cause already
     * named, marked {@code [circular]}, and after {@link #CAUSE_LOG_LIMIT} causes, marked {@code ...}. Every value is
     * a JVM class, method or file name, never text from the request or the vendor.
     */
    private static String describe(Throwable ex) {
        StringBuilder out = new StringBuilder(128).append(ex.getClass().getName());
        StackTraceElement[] trace = ex.getStackTrace();
        if (trace.length > 0) {
            StackTraceElement frame = trace[0];
            out.append(" at ").append(frame.getClassName()).append('.').append(frame.getMethodName()).append('(');
            if (frame.getFileName() == null) {
                out.append("Unknown Source");
            } else {
                out.append(frame.getFileName());
                if (frame.getLineNumber() >= 0) {
                    out.append(':').append(frame.getLineNumber());
                }
            }
            out.append(')');
        }
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
            out.append(cause.getClass().getName());
            causes++;
        }
        return out.toString();
    }
}
