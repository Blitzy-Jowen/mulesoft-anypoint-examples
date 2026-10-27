/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.web_service_consumer.service.TshirtOrderService;

/**
 * HTTP triggers of {@code web-service-consumer/src/main/app/tshirt-service-consumer.xml}: the listener on
 * {@code /orders} of flow {@code orderTshirt} (line 7) and the listener on {@code /inventory} of flow
 * {@code listInventory} (line 35). Everything after each listener, from the DataWeave transforms to the
 * {@code ws:consumer} call, runs in {@link TshirtOrderService}; this class only binds the request body and writes
 * the returned JSON.
 *
 * <p>Neither listener declares {@code allowedMethods}, so every method runs its flow. No mapping declares
 * {@code consumes}, {@code produces}, parameters or headers: the body is bound as raw bytes, which the byte-array
 * converter reads for any {@code Content-Type}, and the response Content-Type is preset, so no request is rejected
 * with 415 or 406. A missing body is passed on as {@code null}; the service decides what it means. The match is
 * exact: any other path, {@code /orders/} included, falls through to the no-listener 404 of
 * {@code GlobalExceptionHandler}.
 *
 * <p>Both response builders declare {@code statusCode="200"}, so a completed flow answers 200 with
 * {@code application/json}, the output type of the flows' last DataWeave script, and an empty body when the service
 * returned no payload. Neither flow declares an exception strategy, so nothing is caught here: every failure reaches
 * the platform-default 500 of {@code GlobalExceptionHandler}.
 */
@RestController
public class TshirtController {

    private final TshirtOrderService service;

    /**
     * @param service runs the processors of flows {@code orderTshirt} and {@code listInventory}
     */
    public TshirtController(TshirtOrderService service) {
        this.service = service;
    }

    /**
     * Runs flow {@code orderTshirt} for every method except {@code OPTIONS}. {@code TRACE} reaches this mapping
     * because {@code application.yml} sets {@code spring.mvc.dispatch-trace-request}; a {@code HEAD} response carries
     * the headers without the body.
     *
     * @param body the raw request body, {@code null} when the request has none
     * @return 200 {@code application/json} with the JSON of the {@code OrderTshirt} response
     */
    @RequestMapping("/orders")
    public ResponseEntity<String> orders(@RequestBody(required = false) byte[] body) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(service.orderTshirt(body));
    }

    /**
     * Runs flow {@code orderTshirt} for {@code OPTIONS} like every other method. A mapping without methods
     * deliberately does not match {@code OPTIONS}, which Spring MVC would otherwise answer itself with an
     * {@code Allow} header and no body; the Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param body the raw request body, {@code null} when the request has none
     * @return the response of {@link #orders(byte[])}
     */
    @RequestMapping(path = "/orders", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> ordersOptions(@RequestBody(required = false) byte[] body) {
        return orders(body);
    }

    /**
     * Runs flow {@code listInventory} for every method except {@code OPTIONS}, with the same {@code TRACE} and
     * {@code HEAD} handling as {@link #orders(byte[])}. The original test called it without a body, which the
     * service answers with the empty {@code ListInventory} request.
     *
     * @param body the raw request body, {@code null} when the request has none
     * @return 200 {@code application/json} with the JSON of the {@code ListInventoryResponse} content
     */
    @RequestMapping("/inventory")
    public ResponseEntity<String> inventory(@RequestBody(required = false) byte[] body) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(service.listInventory(body));
    }

    /**
     * Runs flow {@code listInventory} for {@code OPTIONS}, for the reason given at
     * {@link #ordersOptions(byte[])}.
     *
     * @param body the raw request body, {@code null} when the request has none
     * @return the response of {@link #inventory(byte[])}
     */
    @RequestMapping(path = "/inventory", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> inventoryOptions(@RequestBody(required = false) byte[] body) {
        return inventory(body);
    }
}
