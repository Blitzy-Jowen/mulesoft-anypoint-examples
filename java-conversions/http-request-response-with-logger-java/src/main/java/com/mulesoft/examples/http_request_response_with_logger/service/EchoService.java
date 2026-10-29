/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_request_response_with_logger.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reproduces the processors of flow {@code EchoFlow} (http-request-response-with-logger/src/main/app/echo.xml):
 * {@code set-payload #[message.inboundProperties['http.request.path']]} (line 6) followed by
 * {@code logger level="INFO" message="About to echo #[message.payload]"} (line 7).
 *
 * <p>{@code controller/EchoController} binds the listener and supplies the raw request path, so this class
 * never sees the HTTP request.
 */
@Service
public class EchoService {

    private static final Logger LOG = LoggerFactory.getLogger(EchoService.class);

    /**
     * Returns the request path as the response payload, logging it at INFO first, as the original flow does.
     *
     * <p>The path is passed through untouched: no trimming, decoding or null check, so {@code null} is logged as
     * {@code About to echo null} and returned.
     *
     * @param requestPath the raw request path, without query string and not decoded, as Mule's
     *                    {@code http.request.path} inbound property carries it
     * @return {@code requestPath}, unchanged
     */
    public String echo(String requestPath) {
        String payload = requestPath;
        LOG.info("About to echo {}", payload);
        return payload;
    }
}
