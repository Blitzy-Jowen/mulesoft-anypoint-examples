/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mulesoft.examples.service_orchestration_and_choice_routing.service.ProductPriceService;

/**
 * Price service on port 9999, replacing the {@code priceService} flow: an {@code http:listener} with
 * {@code allowedMethods="POST"} and path {@code api} in front of {@code jersey:resources} over the JAX-RS resource
 * {@code ProductPrice} ({@code @Path("/prices")}, {@code @Path("/{productId}")}, {@code text/plain}).
 *
 * <p>The mapping joins the listener path with the two JAX-RS paths, and only POST is mapped because the listener
 * accepts only POST: any other method gets the 405 of {@code exception.GlobalExceptionHandler}, an unmapped path
 * under {@code /api} its no-listener 404. Confining {@code /api/**} to port 9999 is the listener routing's job
 * ({@code listeners.additional} entry {@code prices}), not this class's.
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
 * </ol>
 */
@RestController
public class ProductPriceController {

    private final ProductPriceService productPriceService;

    public ProductPriceController(ProductPriceService productPriceService) {
        this.productPriceService = productPriceService;
    }

    /**
     * Returns the price of a product.
     *
     * @param productId the product identifier from the request path
     * @return 200 {@code text/plain} with the price
     */
    @PostMapping("/api/prices/{productId}")
    public ResponseEntity<String> getProductPrice(@PathVariable("productId") String productId) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(productPriceService.getProductPrice(productId));
    }
}
