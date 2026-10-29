/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.config;

import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.util.AttachmentKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a custom HTTP reason phrase on the status line of the current response (shared component SC-2 of the
 * conversion plan, AAP 0.4.4).
 *
 * <p>In the original Mule flow the catch branches of the choice exception strategy set the flow variable
 * {@code reason}, and the listener's {@code <http:response-builder reasonPhrase="#[flowVars['reason']]"
 * statusCode="#[flowVars['statusCode']]"/>} put it on the status line, for example
 * {@code HTTP/1.1 400 Invalid input data} ({@code choice-error-handling.xml:5-7}). Where no branch sets it, the
 * standard phrase of the status code is sent.
 *
 * <p>The Servlet API has no portable way to send a custom phrase and Tomcat 10.1 drops it, so this class talks to
 * the Undertow exchange behind the current servlet request and requires the application to run on Undertow.
 *
 * <p>The class depends on the JDK, SLF4J and Undertow alone: every project that sends a custom phrase carries a
 * copy that differs from this one in the {@code package} line only. It holds no mutable static state; the phrase
 * lives in attachments of the exchange it belongs to, so concurrent requests never see each other's phrase.
 */
public final class ReasonPhrase {

    private static final Logger LOG = LoggerFactory.getLogger(ReasonPhrase.class);

    /** The phrase the response must carry; absent means the standard phrase of the status code. */
    private static final AttachmentKey<String> REASON_PHRASE = AttachmentKey.create(String.class);

    /** Marks an exchange whose commit listener is already registered, so each exchange gets exactly one. */
    private static final AttachmentKey<Boolean> LISTENER_REGISTERED = AttachmentKey.create(Boolean.class);

    private ReasonPhrase() {
    }

    /**
     * Sets the reason phrase of the response to the servlet request being handled on the calling thread.
     *
     * <p>Call it before the response commits, typically from an {@code @ExceptionHandler} or controller method
     * that then returns the status through a {@code ResponseEntity}. The phrase is applied immediately and applied
     * again just before the status line is written, so a status code set after this call keeps the phrase.
     * Repeated calls during one request keep only the last phrase. Undertow writes the phrase verbatim, so it
     * must not contain CR or LF.
     *
     * <p>Without an Undertow servlet request on the calling thread (MockMvc, a plain unit call, another thread)
     * the call does nothing and logs at DEBUG.
     *
     * @param reasonPhrase the phrase to send, for example {@code "Invalid input data"}; {@code null} restores the
     *                     standard phrase of the status code
     * @throws IllegalStateException if the response channel of the exchange has already been obtained, that is,
     *                               the response is being or has been written
     */
    public static void set(String reasonPhrase) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context == null) {
            LOG.debug("No Undertow request context on this thread; reason phrase '{}' not applied", reasonPhrase);
            return;
        }
        HttpServerExchange exchange = context.getExchange();
        exchange.setReasonPhrase(reasonPhrase);
        if (reasonPhrase != null) {
            exchange.putAttachment(REASON_PHRASE, reasonPhrase);
        } else {
            exchange.removeAttachment(REASON_PHRASE);
        }
        if (exchange.getAttachment(LISTENER_REGISTERED) == null) {
            // Registered before the mark is set: if Undertow refuses the listener, no later call skips it.
            exchange.addResponseCommitListener(ex -> ex.setReasonPhrase(ex.getAttachment(REASON_PHRASE)));
            exchange.putAttachment(LISTENER_REGISTERED, Boolean.TRUE);
        }
    }
}
