/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.controller;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.sending_json_data_to_a_amqp_queue.service.SalesPublisherService;

/**
 * The HTTP listener of flow {@code json-to-rabbitmqFlow}
 * (sending-json-data-to-a-amqp-queue/src/main/app/json-to-rabbitmq.xml): line 4,
 * {@code http:listener-config host="0.0.0.0" port="${http.port}"}, is {@code server.address} and
 * {@code server.port} in {@code application.yml}; line 6, {@code http:listener allowedMethods="POST" path="/"},
 * is this class, mapped to {@code POST /} with no {@code consumes}, {@code produces} or parameter condition,
 * because the Mule listener accepted any {@code Content-Type} and ignored {@code Accept}.
 *
 * <p>The request body is handed to {@link SalesPublisherService#publish(byte[])} as raw bytes and never parsed,
 * because the example's own sample body is not valid JSON. The flow's response is the AMQP reply, so a successful
 * request is answered in one of two ways, both plan defaults and verification points for fixtures captured from
 * the original application:
 * <ul>
 *   <li>200 with the reply bytes and their {@code Content-Length}, when a reply arrives;</li>
 *   <li>200 with an empty body, when no reply arrives within the endpoint's 10000 ms {@code responseTimeout}.</li>
 * </ul>
 * Neither carries a {@code Content-Type}, because the original sent the AMQP reply without a declared type.
 * Other methods, unserved paths and a failed publish reach the generic handler of the exception package, which
 * answers 405, 404 and 500 as the Mule listener and runtime did.
 *
 * <p>An {@code application/x-www-form-urlencoded} body, which curl's {@code --data} sends by default, reaches the
 * service rebuilt from the parsed request parameters ({@code test} becomes {@code test=}), while the Mule listener
 * turned such a body into a parameter map, so neither forwards those raw bytes; clients send JSON or plain text,
 * as the original example does. A {@code Content-Type} Spring cannot parse, such as one naming an unsupported
 * charset, fails body binding and reaches the generic 500 handler; the Mule listener's answer to it is likewise a
 * fixture verification point.
 */
@RestController
public class SalesController {

    private static final String ALLOWED_METHOD = "POST";

    private final SalesPublisherService salesPublisherService;

    /**
     * @param salesPublisherService logs the payload, publishes it request-response to {@code sales_exchange} and
     *                              returns the reply body
     */
    public SalesController(SalesPublisherService salesPublisherService) {
        this.salesPublisherService = salesPublisherService;
    }

    /**
     * Publishes the request body and answers with the AMQP reply.
     *
     * <p>The response is written to the servlet response directly, and this method returns nothing, so no message
     * converter runs: returning the bytes would make {@code ByteArrayHttpMessageConverter} add
     * {@code Content-Type: application/octet-stream}, which the original response did not carry. The publish
     * happens before the response is touched, so a failure leaves it uncommitted for the 500 of the generic
     * handler.
     *
     * @param body     the request body, or {@code null} when the request has none, which is published as an empty
     *                 payload
     * @param response the response the reply is written to
     * @throws IOException when writing the response fails
     * @throws org.springframework.amqp.AmqpException when the publish fails; it propagates to the generic 500
     *                                                handler
     */
    @PostMapping("/")
    public void send(@RequestBody(required = false) byte[] body, HttpServletResponse response) throws IOException {
        byte[] payload = (body != null) ? body : new byte[0];

        byte[] reply = salesPublisherService.publish(payload);

        response.setStatus(HttpServletResponse.SC_OK);
        if (reply != null && reply.length > 0) {
            // A Content-Length rather than chunked encoding, as Mule sent a byte-array payload.
            response.setContentLength(reply.length);
            ServletOutputStream stream = response.getOutputStream();
            stream.write(reply);
            stream.flush();
        }
    }

    /**
     * Rejects {@code OPTIONS} on {@code /} with the listener's 405, which the generic handler of the exception
     * package writes as {@code Method not allowed for endpoint: /}.
     *
     * <p>The listener allowed {@code POST} only. Without this mapping Spring MVC would answer {@code OPTIONS}
     * itself with 200 and an {@code Allow} header. Every other method is already rejected by the handler mapping.
     *
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(path = "/", method = RequestMethod.OPTIONS)
    public void rejectOptions() throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(RequestMethod.OPTIONS.name(), List.of(ALLOWED_METHOD));
    }
}
