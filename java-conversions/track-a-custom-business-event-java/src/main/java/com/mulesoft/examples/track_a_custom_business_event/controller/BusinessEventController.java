/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.track_a_custom_business_event.controller;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;

import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.util.Headers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.track_a_custom_business_event.service.PriceDiscountService;

/**
 * HTTP listener of flow {@code custom-business-eventsFlow1}
 * (track-a-custom-business-event/src/main/app/custom-business-events.xml:3-5): {@code http:listener
 * path="customBusinessEvents"} on host {@code 0.0.0.0} and port {@code ${http.port}}. Everything after the
 * listener, from the JSON-to-HashMap transformer to the tracking transaction, is {@link PriceDiscountService}.
 *
 * <p>The listener declares no {@code allowedMethods}, so every method is accepted and handled alike: a GET or PUT
 * carrying the JSON body is processed exactly as a POST. No handler declares {@code consumes} or {@code produces}:
 * the Mule listener accepted any {@code Content-Type}, and the response is written to the servlet response without
 * a message converter, so {@code Accept} is never consulted and Spring never answers 406 or 415. The match is
 * exact; any other path, {@code /customBusinessEvents/x} included, falls through to the no-listener 404 of
 * {@code GlobalExceptionHandler}.
 *
 * <p>The flow declares no exception strategy, so nothing is handled here: a body the service cannot read and an
 * item the script cannot price propagate unchanged to {@code GlobalExceptionHandler}, which answers 500
 * {@code { "message": "<exception message>" }}, e.g. {@code nil can't be coerced into Fixnum}.
 *
 * <p>A body sent as {@code application/x-www-form-urlencoded}, curl's default for {@code -d}, is not read as JSON:
 * Spring MVC hands it over rebuilt from the parsed form parameters, so the service rejects it with that 500. The
 * Mule listener likewise parsed a form body into a parameter map, which the JSON transformer could not read; the
 * exact error bytes are a verification point for captured fixtures.
 *
 * <p>The request's {@code Content-Type} decides the encoding and the response's {@code Content-Type}, as the Mule
 * message carried the inbound media type and encoding through the flow's transformers, none of which declares its
 * own:
 * <ul>
 *   <li>the encoding is the canonical name of the request's {@code charset} ({@code latin1} becomes
 *       {@code ISO-8859-1}), or UTF-8 when the request declares none or sends no {@code Content-Type}; the body is
 *       decoded with it and the result encoded with it, sent with its {@code Content-Length};</li>
 *   <li>the response's {@code Content-Type} is the request's type and subtype in lower case, every other parameter
 *       dropped, followed by {@code "; charset="} and the encoding, e.g. {@code application/json; charset=UTF-8};</li>
 *   <li>a request without {@code Content-Type}, or with the wildcard media type (any type, any subtype), is
 *       answered without one, as the listener sent none for a payload whose media type was the wildcard.</li>
 * </ul>
 */
@RestController
@RequestMapping("/customBusinessEvents")
public class BusinessEventController {

    private final PriceDiscountService priceDiscountService;

    /**
     * @param priceDiscountService runs the flow after the listener and returns the reduced unit price as text
     */
    public BusinessEventController(PriceDiscountService priceDiscountService) {
        this.priceDiscountService = priceDiscountService;
    }

    /**
     * Answers {@code /customBusinessEvents} for every method except {@code OPTIONS}, which
     * {@link #handleOptions(HttpHeaders, byte[], HttpServletResponse)} maps, with 200 and the service's result,
     * e.g. {@code 8.5} for shoes at 10; a {@code HEAD} response carries the headers without the body.
     *
     * <p>The service runs before the response is touched, so its failure leaves the response uncommitted for the
     * 500 of {@code GlobalExceptionHandler}. The result is written to the servlet response directly and this method
     * returns nothing, so no message converter adds a {@code Content-Type} of its own. The {@code Content-Type} is
     * put on the Undertow exchange, because the servlet response's {@code setContentType} and {@code setHeader}
     * rewrite it to {@code ;charset=} without the space the listener sent.
     *
     * @param headers  the request headers; Spring has already parsed their {@code Content-Type} while binding the
     *                 body, so one it cannot parse never reaches this method
     * @param body     the raw request body; {@code null} when the request carries none
     * @param response the response the result is written to
     * @throws IOException when writing the response fails
     */
    @RequestMapping
    public void handle(@RequestHeader HttpHeaders headers, @RequestBody(required = false) byte[] body,
            HttpServletResponse response) throws IOException {
        MediaType contentType = headers.getContentType();
        Charset encoding = contentType != null && contentType.getCharset() != null
                ? contentType.getCharset()
                : StandardCharsets.UTF_8;
        byte[] result = priceDiscountService.processPriceRequest(body, encoding).getBytes(encoding);

        response.setStatus(HttpServletResponse.SC_OK);
        if (contentType != null && !MediaType.ALL.equalsTypeAndSubtype(contentType)) {
            ServletRequestContext.requireCurrent().getExchange().getResponseHeaders().put(Headers.CONTENT_TYPE,
                    contentType.getType() + "/" + contentType.getSubtype() + "; charset=" + encoding.name());
        }
        response.setContentLength(result.length);
        ServletOutputStream stream = response.getOutputStream();
        stream.write(result);
        stream.flush();
    }

    /**
     * Answers {@code OPTIONS /customBusinessEvents} like every other method. A mapping without methods deliberately
     * does not match {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and
     * no body; the Mule listener ran the flow for {@code OPTIONS} too. What the original returned for an
     * {@code OPTIONS} request without a body is a verification point for captured fixtures.
     *
     * @param headers  the request headers, as {@link #handle(HttpHeaders, byte[], HttpServletResponse)} reads them
     * @param body     the raw request body; {@code null} when the request carries none
     * @param response the response the result is written to, as
     *                 {@link #handle(HttpHeaders, byte[], HttpServletResponse)} writes it
     * @throws IOException when writing the response fails
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    public void handleOptions(@RequestHeader HttpHeaders headers, @RequestBody(required = false) byte[] body,
            HttpServletResponse response) throws IOException {
        handle(headers, body, response);
    }
}
