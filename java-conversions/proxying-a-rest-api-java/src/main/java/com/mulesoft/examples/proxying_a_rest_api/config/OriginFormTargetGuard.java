/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import org.springframework.boot.web.embedded.undertow.UndertowDeploymentInfoCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mulesoft.examples.proxying_a_rest_api.config.RequestTargetEscapingFilter.TargetComponent;

/**
 * Answers a request whose path does not start with {@code /} with the listener's no-listener response, 404
 * {@code text/plain} {@code No listener for endpoint: <path>}, as the generic error handling writes it for a path no
 * handler serves. Nothing is forwarded.
 *
 * <p>The original listener served {@code path="/*"} ({@code proxying-a-rest-api.xml:12}), which no such path
 * matches. Undertow's HTTP/1.1 parser hands one over for the asterisk form ({@code OPTIONS *}, {@code PRI *}), for
 * the authority form of {@code CONNECT} ({@code CONNECT host:port}) and for an absolute-form target whose authority
 * runs straight into its path ({@code http://host:port@x/y}, {@code http://host:portx}); it answers every other
 * target not in origin form with 400 itself. Undertow's servlet path matching then fails on such a path with
 * {@code UT000068}, before any filter, servlet or advice runs, and the caller would receive a bare 500 with an empty
 * body. The handler registered here runs ahead of that matching, so the request never reaches the servlet layer.
 *
 * <p>The reported path is the one the proxy would have used, with every character RFC 3986 does not allow in a path
 * percent-encoded by {@link RequestTargetEscapingFilter#escape}, as that filter hands paths to the dispatcher, so the
 * body holds only ASCII. An empty path, which the parser never produces, is left to Undertow's own servlet path
 * handling.
 */
@Configuration(proxyBeanMethods = false)
public class OriginFormTargetGuard {

    /** Body prefix of the no-listener response. */
    static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    /** Content-Type of the no-listener response, without a charset parameter. */
    static final String NO_LISTENER_CONTENT_TYPE = "text/plain";

    /**
     * Wraps Undertow's servlet initial handler in a {@link NonOriginFormPathHandler}, so it runs after the HTTP/1.1
     * parser and before servlet path matching.
     *
     * @return the customizer Spring Boot applies to the deployment of the embedded Undertow server
     */
    @Bean
    public UndertowDeploymentInfoCustomizer originFormTargetGuardCustomizer() {
        return deploymentInfo -> deploymentInfo.addInitialHandlerChainWrapper(NonOriginFormPathHandler::new);
    }

    /**
     * Writes the no-listener response for a non-empty path that does not start with {@code /} and passes every other
     * request to the next handler unchanged.
     */
    static final class NonOriginFormPathHandler implements HttpHandler {

        private final HttpHandler next;

        /**
         * @param next the handler every request with a path that starts with {@code /}, or an empty one, goes to
         */
        NonOriginFormPathHandler(HttpHandler next) {
            this.next = next;
        }

        /**
         * Answers or passes on {@code exchange} as the class describes. The response is sent without reading the
         * request body; Undertow discards what the caller still sends.
         *
         * @param exchange the request as Undertow parsed it, its relative path undecoded
         * @throws Exception whatever the next handler throws
         */
        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            String path = exchange.getRelativePath();
            if (path == null || path.isEmpty() || path.charAt(0) == '/') {
                next.handleRequest(exchange);
                return;
            }
            byte[] body = (NO_LISTENER_PREFIX + RequestTargetEscapingFilter.escape(path, TargetComponent.PATH))
                    .getBytes(StandardCharsets.US_ASCII);
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, NO_LISTENER_CONTENT_TYPE);
            exchange.setResponseContentLength(body.length);
            exchange.getResponseSender().send(ByteBuffer.wrap(body));
        }
    }
}
