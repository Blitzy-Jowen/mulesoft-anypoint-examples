/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.exception;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.mulesoft.examples.soap_webservice_security.controller.GreeterClientController;

/**
 * The {@code catch-exception-strategy} of flow {@code SecurityClients} (soap-webservice-security/src/main/app/
 * service-clients.xml:36-39): every exception the {@code /client} front raises is logged and answered with the
 * payload {@code There has been an Error processing the request} and {@code Content-Type: text/plain}. The strategy
 * marks the exception handled, so the listener answers with its default status 200.
 *
 * <p>The strategy has a single branch for every exception type, so this advice declares a single {@link Exception}
 * method. It applies only to {@link GreeterClientController} and runs at {@code @Order(0)}, ahead of the SC-1
 * {@link GlobalExceptionHandler}, so no client failure becomes that handler's 500 JSON reply, while unknown paths,
 * unsupported methods and every other controller's failures stay with SC-1. The Greeter SOAP endpoints are dispatched
 * by {@code MessageDispatcherServlet}, outside this advice, and fault through {@code SoapFaultConfig}.
 *
 * <p>What reaches this class is whatever {@code client/SecureGreeterClient} throws: a {@code SoapFaultClientException}
 * for a fault the Greeter service returned (a rejected WS-Security header included), a
 * {@code WebServiceIOException} or {@code WebServiceTransportException} when the service cannot be reached or
 * answers with a transport error, an {@link IllegalStateException} for missing client configuration, and any WSS4J
 * securement failure. The choice's {@code otherwise} ({@code Client type is not supported}, line 32) is a normal
 * result of {@code SecureGreeterClient.invoke}, not an exception, and never reaches this class.
 */
@RestControllerAdvice(assignableTypes = GreeterClientController.class)
@Order(0)
public class GreeterClientExceptionHandler {

    /** The catch strategy's {@code set-payload} value (service-clients.xml:37). */
    public static final String ERROR_TEXT = "There has been an Error processing the request";

    private static final Logger log = LoggerFactory.getLogger(GreeterClientExceptionHandler.class);

    /** Number of causes named after the caught exception before the chain is cut short. */
    private static final int CAUSE_LOG_LIMIT = 10;

    /**
     * Logs the caught exception at ERROR and answers 200 {@code text/plain} with {@link #ERROR_TEXT}, built exactly as
     * {@link GreeterClientController} builds its greeting reply, so both branches carry the same {@code Content-Type}
     * header, as both original branches set the same {@code text/plain} property (lines 35 and 38).
     *
     * @param ex any exception the {@code /client} controller raised
     * @return 200 {@code text/plain} {@link #ERROR_TEXT}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handle(Exception ex) {
        // Type identifiers only, never the message or the throwable, as GlobalExceptionHandler logs: a fault string,
        // a transport error or a decoding failure can repeat the caller's query or upstream text, line breaks
        // included, which would forge log lines.
        log.error("Caught exception in Exception Strategy: {}", describe(ex));
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)                             // service-clients.xml:38
                .body(ERROR_TEXT);                                             // service-clients.xml:37
    }

    /**
     * Returns the type identifiers of {@code ex}: its class name and the frame that threw it, then the class name of
     * each cause, separated by {@code " <- "}, for example {@code org.springframework.ws.client.WebServiceIOException
     * at a.b.C.d(C.java:18) <- java.net.ConnectException}. The walk stops at a cause already named, marked
     * {@code [circular]}, and after {@link #CAUSE_LOG_LIMIT} causes, marked {@code ...}. Every value is a JVM class,
     * method or file name, never text from the request or a SOAP fault.
     */
    private static String describe(Throwable ex) {
        StringBuilder out = new StringBuilder(128).append(ex.getClass().getName());
        StackTraceElement[] trace = ex.getStackTrace();
        if (trace != null && trace.length > 0 && trace[0] != null) {
            StackTraceElement frame = trace[0];
            out.append(" at ").append(frame.getClassName()).append('.').append(frame.getMethodName()).append('(');
            if (frame.isNativeMethod()) {
                out.append("Native Method");
            } else if (frame.getFileName() == null) {
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
