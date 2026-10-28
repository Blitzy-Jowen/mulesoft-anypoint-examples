/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.service_orchestration_and_choice_routing.service.ProductPriceService;

/**
 * Price service on port 9999, replacing the {@code priceService} flow: an {@code http:listener} with
 * {@code allowedMethods="POST"} and path {@code api} in front of {@code jersey:resources} over the JAX-RS resource
 * {@code ProductPrice} ({@code @Path("/prices")}, {@code @Path("/{productId}")}, {@code text/plain}).
 *
 * <p>The price mapping joins the listener path with the two JAX-RS paths, and the listener path {@code /api} itself
 * has a mapping of its own. Both serve POST alone, because the listener accepts only POST, compared ignoring case as
 * Mule 3.8's {@code MethodRequestMatcher} did, so {@code post} and {@code Post} are served as {@code POST}. Neither
 * mapping declares a method, so that each handler can compare the method ignoring case; a {@code @PostMapping} would
 * match {@code POST} in upper case only. Every other method, {@code HEAD} and {@code TRACE} included, gets the 405
 * {@code Method not allowed for endpoint: <path>} of {@code exception.GlobalExceptionHandler}, without an
 * {@code Allow} header, and an unmapped path under {@code /api} its no-listener 404. A mapping without methods does
 * not match {@code OPTIONS}, so {@link #rejectOptions(HttpServletRequest)} takes it on both paths; without it Spring
 * MVC would answer it itself with 200 and an {@code Allow} header. {@code PreflightDispatchConfig} dispatches a CORS
 * pre-flight there as the plain {@code OPTIONS} request it is, and {@code spring.mvc.dispatch-trace-request} hands
 * {@code TRACE} to the dispatcher. Confining {@code /api} and {@code /api/**} to port 9999 is the listener routing's
 * job ({@code listeners.additional} entry {@code prices}, whose pattern {@code /api/**} matches {@code /api} too),
 * not this class's.
 *
 * <p>A {@code POST} to {@code /api} passed the listener and reached Jersey, where no resource serves the root. Jersey
 * answers a request no resource matches with 404 and no entity, so {@link #listenerRoot} writes 404 with the
 * standard reason, {@code Content-Length: 0}, no Content-Type and no body, and commits the response so that no
 * message converter or exception handler adds anything.
 *
 * <p>The Content-Type is set on the response rather than declared with {@code produces}: a preset type makes Spring
 * write the body without {@code Accept} negotiation, so a caller's {@code Accept} header can never turn the price
 * into a 406 or 500 the original did not send.
 *
 * <p>Tier 2A verification points, listed in the project README as well; a captured fixture of the original governs
 * where it differs:
 * <ol>
 *   <li>The JAX-RS method is a {@code @GET} behind a POST-only listener, while the in-house order flow requests the
 *       price with GET ({@code fulfillment.xml:105}). The JAX-RS {@code @GET} is not carried over, so that GET is
 *       answered with 405 and {@code client.ProductPriceClient} fails as the original request would have.</li>
 *   <li>The original listener path is {@code api}, without a {@code /*} wildcard, so Mule 3.8's listener path
 *       matching may have answered every {@code /api/prices/...} request with the no-listener 404 instead.</li>
 *   <li>The response carries {@code Content-Type: text/plain} without a charset parameter, as the JAX-RS
 *       {@code @Produces} declared it: a Content-Type preset on the response is written unchanged. Whether the
 *       Mule listener added a charset to the Jersey response is unconfirmed.</li>
 *   <li>{@code POST /api}, in any letter case, gets the empty 404 of a Jersey request no resource matches; whether
 *       the Mule listener added a Content-Type or a body to that response is unconfirmed.</li>
 * </ol>
 */
@RestController
public class ProductPriceController {

    /** The listener's {@code path}, on which no JAX-RS resource sits. */
    private static final String LISTENER_PATH = "/api";

    /** Listener path {@code api} joined with the JAX-RS paths {@code /prices} and {@code /{productId}}. */
    private static final String PRICE_PATH = LISTENER_PATH + "/prices/{productId}";

    /** The listener's {@code allowedMethods}. */
    private static final String ALLOWED_METHOD = "POST";

    private final ProductPriceService productPriceService;

    public ProductPriceController(ProductPriceService productPriceService) {
        this.productPriceService = productPriceService;
    }

    /**
     * Returns the price of a product for {@code POST}, in any letter case. The method is checked before the price
     * service is called, so a rejected request never reaches it.
     *
     * @param productId the product identifier from the request path
     * @param request   the request, whose method is checked
     * @return 200 {@code text/plain} with the price
     * @throws HttpRequestMethodNotSupportedException when the method is not {@code POST} in some letter case
     */
    @RequestMapping(PRICE_PATH)
    public ResponseEntity<String> getProductPrice(@PathVariable("productId") String productId,
                                                  HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        requireAllowedMethod(request);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(productPriceService.getProductPrice(productId));
    }

    /**
     * Answers the listener path {@code /api} itself, matched exactly: {@code /api/} is an unserved path. A
     * {@code POST}, in any letter case, gets Jersey's answer for a request no resource matches: 404 with the
     * standard reason, {@code Content-Length: 0}, no Content-Type and no body. The response is committed here, so
     * nothing written later can add a header or a body. The price service is not called.
     *
     * @param request  the request, whose method is checked
     * @param response the response the empty 404 is written to
     * @throws HttpRequestMethodNotSupportedException when the method is not {@code POST} in some letter case
     * @throws IOException                            when committing the response fails
     */
    @RequestMapping(LISTENER_PATH)
    public void listenerRoot(HttpServletRequest request, HttpServletResponse response)
            throws HttpRequestMethodNotSupportedException, IOException {
        requireAllowedMethod(request);
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentLength(0);
        response.flushBuffer();
    }

    /**
     * Rejects {@code OPTIONS} on the price path and on {@code /api} with the listener's 405, which the generic
     * handler of the exception package writes as {@code Method not allowed for endpoint: <path>}, without an
     * {@code Allow} header.
     *
     * <p>The listener allowed {@code POST} only. A mapping without methods does not match {@code OPTIONS}, so without
     * this one Spring MVC would answer it itself with 200 and an {@code Allow} header. This match is exact, so
     * {@code options} in any other letter case reaches {@link #getProductPrice} or {@link #listenerRoot}, which
     * reject it. The price service is not called.
     *
     * @param request the rejected request, whose method is reported
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(path = {PRICE_PATH, LISTENER_PATH}, method = RequestMethod.OPTIONS)
    public void rejectOptions(HttpServletRequest request) throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of(ALLOWED_METHOD));
    }

    /**
     * Passes a request whose method is the listener's {@code allowedMethods} in some letter case.
     *
     * @param request the request, whose method is checked
     * @throws HttpRequestMethodNotSupportedException when the method is anything else, answered with the listener's
     *                                                405 by the generic handler of the exception package
     */
    private static void requireAllowedMethod(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        if (!ALLOWED_METHOD.equalsIgnoreCase(request.getMethod())) {
            throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of(ALLOWED_METHOD));
        }
    }
}
