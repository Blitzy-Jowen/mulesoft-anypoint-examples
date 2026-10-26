/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.track_a_custom_business_event.controller;

import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
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
 * the original JSON transformer ignored the request's {@code Content-Type}, and the response's Content-Type is
 * preset, so Spring writes the body without consulting {@code Accept} and never answers 406 or 415. The match is
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
 * <p>The response Content-Type {@code text/plain;charset=UTF-8} is a plan default for the text the original
 * {@code object-to-string-transformer} produced; a fixture captured from the original application governs it.
 */
@RestController
@RequestMapping("/customBusinessEvents")
public class BusinessEventController {

    private static final MediaType TEXT_PLAIN_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final PriceDiscountService priceDiscountService;

    /**
     * @param priceDiscountService runs the flow after the listener and returns the reduced unit price as text
     */
    public BusinessEventController(PriceDiscountService priceDiscountService) {
        this.priceDiscountService = priceDiscountService;
    }

    /**
     * Answers {@code /customBusinessEvents} for every method except {@code OPTIONS}, which
     * {@link #handleOptions(byte[])} maps; a {@code HEAD} response carries the headers without the body.
     *
     * @param body the raw request body, whatever its {@code Content-Type}; {@code null} when the request carries
     *             none
     * @return 200 {@code text/plain;charset=UTF-8} with the service's result, e.g. {@code 8.5} for shoes at 10
     */
    @RequestMapping
    public ResponseEntity<String> handle(@RequestBody(required = false) byte[] body) {
        return ResponseEntity.ok().contentType(TEXT_PLAIN_UTF8).body(priceDiscountService.processPriceRequest(body));
    }

    /**
     * Answers {@code OPTIONS /customBusinessEvents} like every other method. A mapping without methods deliberately
     * does not match {@code OPTIONS}, which Spring MVC would otherwise answer itself with an {@code Allow} header and
     * no body; the Mule listener ran the flow for {@code OPTIONS} too. What the original returned for an
     * {@code OPTIONS} request without a body is a verification point for captured fixtures.
     *
     * @param body the raw request body, whatever its {@code Content-Type}; {@code null} when the request carries
     *             none
     * @return the response of {@link #handle(byte[])} for the same body
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    public ResponseEntity<String> handleOptions(@RequestBody(required = false) byte[] body) {
        return handle(body);
    }
}
