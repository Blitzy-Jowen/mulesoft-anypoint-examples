/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.controller;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.web_service_consumer.service.TshirtOrderService;

/**
 * HTTP triggers of {@code web-service-consumer/src/main/app/tshirt-service-consumer.xml}: the listener on
 * {@code /orders} of flow {@code orderTshirt} (line 7) and the listener on {@code /inventory} of flow
 * {@code listInventory} (line 35). Everything after each listener, from the DataWeave transforms to the
 * {@code ws:consumer} call, runs in {@link TshirtOrderService}; this class only reads the request body and writes
 * the returned JSON.
 *
 * <p>Neither listener declares {@code allowedMethods}, so every method runs its flow. No mapping declares
 * {@code consumes}, {@code produces}, parameters or headers, and the response Content-Type is preset, so no request
 * is rejected with 415 or 406. The match is exact: any other path, {@code /orders/} included, falls through to the
 * no-listener 404 of {@code GlobalExceptionHandler}, and so does a listener path carrying {@code ;} segment
 * parameters, such as {@code /orders;x=1}, which {@code config.ListenerPathConfig} refuses before either handler runs.
 *
 * <p>The body is read raw from the servlet input stream, for every method and {@code Content-Type}, and handed to
 * the service unchanged; a request without a body hands it an empty array, and the service decides what that means.
 * It is not bound through {@code @RequestBody}, which rebuilds a form-urlencoded {@code POST} body from the
 * container's parsed parameters, and {@code application.yml} disables Boot's {@code FormContentFilter}, which would
 * otherwise parse, and so consume, a form-urlencoded {@code PUT}, {@code PATCH} or {@code DELETE} body before this
 * class runs. {@code DEBUG} logging of {@code org.springframework.web.servlet.DispatcherServlet} reads the request
 * parameters to log them, which consumes a form-urlencoded body before it can be read here.
 *
 * <p>A CORS pre-flight, an {@code OPTIONS} request carrying {@code Origin} and
 * {@code Access-Control-Request-Method}, reaches the {@code OPTIONS} mappings exactly as a plain {@code OPTIONS}
 * request does, because {@code config.PreflightDispatchConfig} dispatches it without Spring's CORS processing: it
 * runs the flow, and its response carries no {@code Access-Control-*} or {@code Vary} header.
 *
 * <p>Both response builders declare {@code statusCode="200"}, so a completed flow answers 200 with
 * {@code application/json}, the output type of the flows' last DataWeave script. {@code /orders} answers with an
 * empty body when the service returned no payload; {@code /inventory} always carries the JSON the service wrote,
 * the literal {@code null} included. Neither flow declares an exception strategy, so nothing is caught here: every
 * failure, a request body that cannot be read included, reaches the platform-default 500 of
 * {@code GlobalExceptionHandler}.
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
     * @param request the request, whose body is read raw
     * @return 200 {@code application/json} with the JSON of the {@code OrderTshirt} response
     * @throws IOException when the request body cannot be read
     */
    @RequestMapping("/orders")
    public ResponseEntity<String> orders(HttpServletRequest request) throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(service.orderTshirt(rawBody(request)));
    }

    /**
     * Runs flow {@code orderTshirt} for {@code OPTIONS}, a CORS pre-flight included, like every other method. A
     * mapping without methods deliberately does not match {@code OPTIONS}, which Spring MVC would otherwise answer
     * itself with an {@code Allow} header and no body; the Mule listener ran the flow for {@code OPTIONS} too.
     *
     * @param request the request, whose body is read raw
     * @return the response of {@link #orders(HttpServletRequest)}
     * @throws IOException when the request body cannot be read
     */
    @RequestMapping(path = "/orders", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> ordersOptions(HttpServletRequest request) throws IOException {
        return orders(request);
    }

    /**
     * Runs flow {@code listInventory} for every method except {@code OPTIONS}, with the same {@code TRACE} and
     * {@code HEAD} handling as {@link #orders(HttpServletRequest)}. The original test called it without a body,
     * which the service answers with the empty {@code ListInventory} request.
     *
     * @param request the request, whose body is read raw
     * @return 200 {@code application/json} with the JSON of the {@code ListInventoryResponse} content
     * @throws IOException when the request body cannot be read
     */
    @RequestMapping("/inventory")
    public ResponseEntity<String> inventory(HttpServletRequest request) throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(service.listInventory(rawBody(request)));
    }

    /**
     * Runs flow {@code listInventory} for {@code OPTIONS}, a CORS pre-flight included, for the reason given at
     * {@link #ordersOptions(HttpServletRequest)}.
     *
     * @param request the request, whose body is read raw
     * @return the response of {@link #inventory(HttpServletRequest)}
     * @throws IOException when the request body cannot be read
     */
    @RequestMapping(path = "/inventory", method = RequestMethod.OPTIONS)
    public ResponseEntity<String> inventoryOptions(HttpServletRequest request) throws IOException {
        return inventory(request);
    }

    /**
     * The request body exactly as the caller sent it, read from the servlet input stream, so no message converter
     * or form-parameter parsing takes part.
     *
     * @param request the current request
     * @return the body bytes, empty when the request has no body
     * @throws IOException when the request body cannot be read
     */
    private static byte[] rawBody(HttpServletRequest request) throws IOException {
        return request.getInputStream().readAllBytes();
    }
}
