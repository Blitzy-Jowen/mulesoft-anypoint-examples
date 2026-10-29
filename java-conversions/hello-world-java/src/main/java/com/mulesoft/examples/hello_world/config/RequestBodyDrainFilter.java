/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.hello_world.config;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads, once the request has been handled, whatever part of the request body the application left unread, so
 * that every request pipelined on an HTTP/1.1 connection is answered.
 *
 * <p>The Mule 3.8 HTTP listener ran on Grizzly, which read the whole request, body included, before the flow
 * started. A flow that ignored the body therefore never left bytes unread, and each request pipelined behind it on
 * the same connection received its response. Undertow streams the body to the application instead. When a handler
 * never reads it, Undertow ends the exchange with the fixed-length body still pending, logs
 * {@code UT005007: Request was not fully consumed} and makes the connection non-persistent; when that request was
 * pipelined behind a body-less one and carried {@code Connection: close}, Undertow then closes the connection
 * without flushing either response, and the client receives no bytes at all. Reading the remainder to its end
 * here leaves every request fully consumed when its exchange ends, as on the original listener.
 *
 * <p>The filter runs first ({@link Ordered#HIGHEST_PRECEDENCE}), so it wraps every other filter, the dispatcher and
 * the error responses of the exception package, and drains after all of them, whether the chain returned or threw.
 * It reads nothing before the chain, so a handler that binds the body still receives all of it; after such a
 * handler, or after a filter that parsed a form body, the stream is already at its end and the drain returns at
 * once. Draining never changes the response or the outcome of the chain:
 * <ul>
 *   <li>a request whose body the application read through {@link HttpServletRequest#getReader()} is left as it
 *       is, because the servlet API then refuses {@link HttpServletRequest#getInputStream()};</li>
 *   <li>a request whose asynchronous processing has started is left to that processing, which still owns the
 *       stream;</li>
 *   <li>a failure while draining, typically a client that closed the connection or stopped sending, or a stream
 *       the application switched to non-blocking reads, is logged at DEBUG and dropped: it never replaces an
 *       exception thrown by the chain and never fails a request that otherwise completed, and Undertow closes the
 *       connection as it would without this filter.</li>
 * </ul>
 * Like a handler that binds the body, the drain waits for body bytes the client announced but has not sent yet.
 *
 * <p>Every conversion that carries this filter holds a copy that differs from this one in the {@code package}
 * line alone, so the class depends only on the JDK, the Servlet API, Spring Web and SLF4J.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestBodyDrainFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestBodyDrainFilter.class);

    /** Size of the buffer the unread body is read into and discarded from, in bytes. */
    private static final int DRAIN_BUFFER_SIZE = 8192;

    /**
     * Runs the rest of the chain, then drains the request body the chain left unread.
     *
     * @throws ServletException if the chain throws it; draining adds none
     * @throws IOException if the chain throws it; draining adds none
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            drain(request);
        }
    }

    /**
     * Reads the request body to its end and discards it, unless the application read it as characters or handed
     * it to asynchronous processing. Never throws: a body that cannot be opened or read is logged at DEBUG and left
     * to the container.
     */
    private static void drain(HttpServletRequest request) {
        if (request.isAsyncStarted()) {
            return;
        }
        ServletInputStream body;
        try {
            body = request.getInputStream();
        } catch (IllegalStateException readerInUse) {
            return;
        } catch (IOException ex) {
            LOGGER.debug("Request body could not be opened for draining; the connection closes after this exchange",
                    ex);
            return;
        }
        if (body.isFinished()) {
            return;
        }
        try {
            discard(body);
        } catch (IOException | IllegalStateException ex) {
            LOGGER.debug("Unread request body could not be drained; the connection closes after this exchange", ex);
        }
    }

    private static void discard(InputStream body) throws IOException {
        byte[] buffer = new byte[DRAIN_BUFFER_SIZE];
        int read;
        do {
            read = body.read(buffer);
        } while (read != -1);
    }
}
