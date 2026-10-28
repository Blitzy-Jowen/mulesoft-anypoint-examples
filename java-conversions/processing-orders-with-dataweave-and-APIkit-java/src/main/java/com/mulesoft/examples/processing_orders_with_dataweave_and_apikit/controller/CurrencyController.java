/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.controller;

import java.util.List;

import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.config.ApiKitAcceptNegotiator;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.service.CurrencyService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The RAML resource {@code /currencies} of {@code api/currency.raml}, served under the APIkit listener path
 * {@code /api/*} of the original {@code processing-orders-with-dataweave-and-APIkit/src/main/app/currency.xml}.
 * It replaces the {@code currency-main} flow's {@code apikit:router} and its routing of {@code GET /currencies}
 * to the flow {@code get:/currencies:currency-config}.
 *
 * <p>The RAML declares a single method, GET, answering 200 {@code application/json}, with no URI or query
 * parameter and no request body. The body is the flow payload from {@link CurrencyService} unchanged, the
 * rendered {@code currency.json} template; the response is not validated against {@code currency-schema.json},
 * as APIkit validated requests only. Query parameters the RAML does not declare are ignored, as APIkit ignored
 * them.
 *
 * <p>Error responses come from the advices: a method the resource does not declare raises
 * {@link HttpRequestMethodNotSupportedException}, an {@code Accept} header excluding JSON, or one under which
 * {@link ApiKitAcceptNegotiator} chooses no declared type after the HEAD check, raises
 * {@link HttpMediaTypeNotAcceptableException}, and {@code ApiKitExceptionHandler} answers them with APIkit's
 * 405 and 406 mappings; a failure rendering the template reaches {@code GlobalExceptionHandler}'s 500.
 */
@RestController
public class CurrencyController {

    /** The resource path: the APIkit listener base path followed by the RAML resource. */
    private static final String CURRENCIES_PATH = "${apikit.base-path:/api}/currencies";

    /** The methods {@code /currencies} declares in the RAML. */
    private static final List<String> SUPPORTED_METHODS = List.of("GET");

    private final CurrencyService currencyService;

    /**
     * @param currencyService the flow logic of {@code get:/currencies:currency-config}
     */
    public CurrencyController(CurrencyService currencyService) {
        this.currencyService = currencyService;
    }

    /**
     * Routes to flow {@code get:/currencies:currency-config}.
     *
     * @param request the current request, whose method is checked and whose {@code Accept} header is negotiated
     * @return 200, {@code Content-Type: application/json}, with the currency document
     * @throws HttpRequestMethodNotSupportedException for a HEAD request, which the RAML does not declare
     * @throws HttpMediaTypeNotAcceptableException    when APIkit's negotiation chooses no declared type for the
     *                                                {@code Accept} header
     */
    @GetMapping(path = CURRENCIES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getCurrencies(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException, HttpMediaTypeNotAcceptableException {
        // Spring maps HEAD to GET handlers implicitly; this keeps getCurrencies() from ever answering HEAD,
        // whichever mapping the dispatcher selects for it.
        if (RequestMethod.HEAD.name().equalsIgnoreCase(request.getMethod())) {
            throw new HttpRequestMethodNotSupportedException(request.getMethod(), SUPPORTED_METHODS);
        }
        ApiKitAcceptNegotiator.negotiate(request);
        // The bare media type, without a charset parameter, is the Content-Type the original sent.
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(currencyService.getCurrencies());
    }

    /**
     * Rejects HEAD and OPTIONS, which the RAML does not declare, so they reach APIkit's 405 mapping. Without
     * this mapping Spring would answer OPTIONS itself with 200 and an {@code Allow} header. No
     * {@code produces} is declared, so the mapping also claims HEAD whose {@code Accept} header excludes JSON.
     *
     * @param request the current request, whose method is reported
     * @return never
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(path = CURRENCIES_PATH, method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public ResponseEntity<String> rejectUndeclaredMethod(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), SUPPORTED_METHODS);
    }
}
