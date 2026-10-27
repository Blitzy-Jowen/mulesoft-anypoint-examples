/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.foreach_processing_and_choice_routing.exception;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.mulesoft.examples.foreach_processing_and_choice_routing.controller.LoanBrokerController;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * The {@code catch-exception-strategy} of flow {@code loan-broker-sync} (foreach-processing-and-choice-routing/
 * src/main/app/loanbroker-simple.xml:62-64): every exception the flow raises is logged and answered with the payload
 * {@code Error processing loan request}, framed like the flow's successful reply.
 *
 * <p>The strategy has a single branch for every exception type, so this advice declares a single {@link Exception}
 * method. It applies only to {@link LoanBrokerController} and runs at {@code @Order(0)}, ahead of the SC-1
 * {@link GlobalExceptionHandler}, so no loan-broker failure becomes that handler's 500 JSON reply. The credit agency
 * and bank SOAP endpoints are dispatched by {@code MessageDispatcherServlet}, outside this advice, and fault through
 * {@code SoapFaultConfig}.
 *
 * <p>The required-field choice's {@code otherwise} ({@code Error: incomplete request}, line 58) is a normal result of
 * {@code LoanBrokerService.quote}, not an exception, and never reaches this class.
 */
@ControllerAdvice(assignableTypes = LoanBrokerController.class)
@Order(0)
public class LoanBrokerExceptionHandler {

    /** The catch strategy's {@code set-payload} value (line 63), without a line break. */
    public static final String ERROR_PAYLOAD = "Error processing loan request";

    private static final Logger log = LoggerFactory.getLogger(LoanBrokerExceptionHandler.class);

    /** Number of causes named after the caught exception before the chain is cut short. */
    private static final int CAUSE_LOG_LIMIT = 10;

    /**
     * Logs the caught exception at ERROR and answers 200 with the UTF-8 bytes of {@link #ERROR_PAYLOAD}, framed as
     * {@link LoanBrokerController} frames a quote: a {@code Content-Length}, no {@code Content-Type}, written to the
     * output stream and flushed, so the reply is committed before the dispatcher regains control and
     * {@code FrameworkServlet} adds nothing to an {@code OPTIONS} or {@code TRACE} reply.
     *
     * @param ex       any exception the loan-broker controller raised
     * @param response the failed request's response; one already committed is left as it is
     * @throws IOException when writing the response fails
     */
    @ExceptionHandler(Exception.class)
    public void handle(Exception ex, HttpServletResponse response) throws IOException {
        // Type identifiers only, never the message or the throwable: a NumberFormatException quotes the caller's
        // URL-decoded ssn, amount or term, line breaks included, in its message and in the first line of its stack
        // trace. GlobalExceptionHandler logs unhandled failures the same way.
        log.error("Caught exception in Exception Strategy: {}", describe(ex));
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_OK);
        byte[] body = ERROR_PAYLOAD.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }

    /**
     * Returns the type identifiers of {@code ex}: its class name and the frame that threw it, then the class name of
     * each cause, separated by {@code " <- "}, for example {@code java.lang.NumberFormatException at
     * java.lang.NumberFormatException.forInputString(NumberFormatException.java:67)}. The walk stops at a cause
     * already named, marked {@code [circular]}, and after {@link #CAUSE_LOG_LIMIT} causes, marked {@code ...}. Every
     * value is a JVM class, method or file name, never text from the request or a SOAP fault.
     */
    private static String describe(Throwable ex) {
        StringBuilder out = new StringBuilder(128).append(ex.getClass().getName());
        StackTraceElement[] trace = ex.getStackTrace();
        if (trace.length > 0 && trace[0] != null) {
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
