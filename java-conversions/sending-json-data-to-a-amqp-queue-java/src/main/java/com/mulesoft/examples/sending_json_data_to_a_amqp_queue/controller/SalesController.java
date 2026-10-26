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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.util.StreamUtils;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.sending_json_data_to_a_amqp_queue.service.SalesPublisherService;

/**
 * The HTTP listener of flow {@code json-to-rabbitmqFlow}
 * (sending-json-data-to-a-amqp-queue/src/main/app/json-to-rabbitmq.xml): line 4,
 * {@code http:listener-config host="0.0.0.0" port="${http.port}"}, is {@code server.address} and
 * {@code server.port} in {@code application.yml}; line 6, {@code http:listener allowedMethods="POST" path="/"},
 * is this class, mapped to {@code /} with no method, {@code consumes}, {@code produces} or parameter condition,
 * because the Mule listener compared the method with {@code allowedMethods} ignoring case, accepted any
 * {@code Content-Type} and ignored {@code Accept}; {@link #send} admits {@code POST} in any letter case.
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
 * service of a {@code POST} rebuilt from the parsed request parameters ({@code test} becomes {@code test=}), while
 * the Mule listener turned such a body into a parameter map, so neither forwards those raw bytes; clients send JSON
 * or plain text, as the original example does. Spring rebuilds the form body only for the method spelled
 * {@code POST}, so the raw bytes of a form body sent with another spelling, such as {@code post}, are published as
 * they arrive. A {@code Content-Type} Spring cannot parse, such as one naming an unsupported charset, fails body
 * binding and reaches the generic 500 handler; the Mule listener's answer to it is likewise a fixture verification
 * point.
 */
@RestController
public class SalesController {

    /**
     * The listener's {@code allowedMethods}. Mule 3.8's {@code MethodRequestMatcher} lowercased both the request
     * method and this value, so {@code post} and {@code Post} are admitted too.
     */
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
     * Publishes the request body of a {@code POST /}, in any letter case, and answers with the AMQP reply.
     *
     * <p>The method check comes first, and the body is bound only after it, so a rejected method is answered with the
     * 405 whatever its body or {@code Content-Type}. The response is written to the servlet response directly, and
     * this method returns nothing, so no message converter runs: returning the bytes would make
     * {@code ByteArrayHttpMessageConverter} add {@code Content-Type: application/octet-stream}, which the original
     * response did not carry. The publish happens before the response is touched, so a failure leaves it
     * uncommitted for the 500 of the generic handler.
     *
     * @param request  the request, whose method and body are read; a request without a body is published as an
     *                 empty payload
     * @param response the response the reply is written to
     * @throws HttpRequestMethodNotSupportedException when the method is not {@code POST} in some letter case; it
     *                                                reaches the generic 405 handler
     * @throws IOException when reading the request body or writing the response fails
     * @throws org.springframework.http.InvalidMediaTypeException when the {@code Content-Type} cannot be parsed; it
     *                                                            propagates to the generic 500 handler
     * @throws org.springframework.amqp.AmqpException when the publish fails; it propagates to the generic 500
     *                                                handler
     */
    @RequestMapping("/")
    public void send(HttpServletRequest request, HttpServletResponse response)
            throws HttpRequestMethodNotSupportedException, IOException {
        if (!ALLOWED_METHOD.equalsIgnoreCase(request.getMethod())) {
            throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of(ALLOWED_METHOD));
        }
        byte[] payload = requestBody(request);

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
     * <p>The listener allowed {@code POST} only. A mapping without methods does not match {@code OPTIONS}, so
     * without this one Spring MVC would answer it itself with 200 and an {@code Allow} header. Every other method
     * reaches {@link #send}, which rejects it.
     *
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(path = "/", method = RequestMethod.OPTIONS)
    public void rejectOptions() throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(RequestMethod.OPTIONS.name(), List.of(ALLOWED_METHOD));
    }

    /**
     * Binds the request body as {@code @RequestBody(required = false) byte[]} did, inside the handler so that the
     * method check runs first: the {@code Content-Type} is parsed before the body is read, so one that cannot be
     * parsed raises {@link org.springframework.http.InvalidMediaTypeException} with the text body binding reported;
     * the body is then read through Spring's request adapter, which rebuilds a form {@code POST} from the parsed
     * request parameters. A request without a body gives an empty array.
     */
    private static byte[] requestBody(HttpServletRequest request) throws IOException {
        ServletServerHttpRequest inputMessage = new ServletServerHttpRequest(request);
        // Parsed for its failure only: an unparseable Content-Type ends the request before the body is read.
        inputMessage.getHeaders().getContentType();
        return StreamUtils.copyToByteArray(inputMessage.getBody());
    }
}
