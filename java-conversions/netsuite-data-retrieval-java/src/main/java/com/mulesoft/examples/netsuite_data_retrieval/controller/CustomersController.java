/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.controller;

import java.util.List;
import java.util.Set;

import com.mulesoft.examples.netsuite_data_retrieval.config.ApiKitAcceptNegotiator;
import com.mulesoft.examples.netsuite_data_retrieval.service.NetsuiteSearchService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The RAML resource {@code /customers} of {@code api/netsuite-api.raml}, served under the APIkit listener path
 * {@code /api/*} of the original {@code netsuite-data-retrieval/src/main/app/netsuite-api.xml}. It replaces the
 * {@code netsuite-api-main} flow's {@code apikit:router} and its flow mapping of {@code GET /customers} to the
 * flow {@code get:/customers:netsuite-api-config}.
 *
 * <p>The RAML declares a single method, GET, with the optional string query parameter {@code name}, no request
 * body and a 200 {@code application/json} response. The parameter reaches {@link NetsuiteSearchService} exactly
 * as received, {@code null} when absent and {@code ""} when sent empty, and the service's JSON text is returned
 * unchanged: the search criteria, the NetSuite calls, the {@code payload map $} rendering and the success log all
 * belong to the service. Query parameters the RAML does not declare are ignored, as APIkit ignored them.
 *
 * <p>Nothing is caught here; the advices answer every failure:
 * <ul>
 *   <li>a method the resource does not declare, HEAD and OPTIONS included, raises
 *       {@link HttpRequestMethodNotSupportedException}, an {@code Accept} header excluding JSON, or one under which
 *       {@link ApiKitAcceptNegotiator} chooses no declared type once {@code name} has passed its check, raises
 *       {@link HttpMediaTypeNotAcceptableException}, and a repeated {@code name} raises
 *       {@link ConstraintViolationException}; {@code ApiKitExceptionHandler} answers them with APIkit's 405, 406
 *       and 400 mappings;</li>
 *   <li>a NetSuite authentication, throttling or connectivity failure reaches {@code UpstreamExceptionHandler}
 *       (502, 429, 503);</li>
 *   <li>any other exception of the service reaches {@code GlobalExceptionHandler}'s 500.</li>
 * </ul>
 */
@RestController
@Validated
@RequestMapping("${apikit.base-path:/api}")
public class CustomersController {

    /** The resource path below the APIkit listener base path. */
    private static final String CUSTOMERS_PATH = "/customers";

    /** The RAML query parameter of {@code GET /customers}. */
    private static final String NAME = "name";

    /** The methods {@code /customers} declares in the RAML. */
    private static final List<String> SUPPORTED_METHODS = List.of("GET");

    private final NetsuiteSearchService netsuiteSearchService;

    /**
     * @param netsuiteSearchService the flow logic of {@code get:/customers:netsuite-api-config}
     */
    public CustomersController(NetsuiteSearchService netsuiteSearchService) {
        this.netsuiteSearchService = netsuiteSearchService;
    }

    /**
     * Routes to flow {@code get:/customers:netsuite-api-config}.
     *
     * @param name    the {@code name} query parameter, the company name prefix; {@code null} when absent
     * @param request the current request, whose method and repeated parameters are checked and whose
     *                {@code Accept} header is negotiated
     * @return 200, {@code Content-Type: application/json}, with the customers as the service rendered them, and
     *         {@code Cache-Control: no-store} and {@code X-Content-Type-Options: nosniff}, so that neither the
     *         client nor an intermediary keeps a copy of the customer records and no browser reads them as another
     *         content type; the original declared neither header, and no error reply carries them
     * @throws HttpRequestMethodNotSupportedException for a HEAD request, which the RAML does not declare
     * @throws ConstraintViolationException           when {@code name} is sent more than once
     * @throws HttpMediaTypeNotAcceptableException    when APIkit's negotiation chooses no declared type for the
     *                                                {@code Accept} header of a valid request
     * @throws Exception                              the service's exceptions, unchanged
     */
    @GetMapping(path = CUSTOMERS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getCustomers(@RequestParam(name = NAME, required = false) String name,
                                               HttpServletRequest request) throws Exception {
        // Spring maps HEAD to GET handlers implicitly; this keeps getCustomers() from ever answering HEAD,
        // whichever mapping the dispatcher selects for it.
        if (RequestMethod.HEAD.name().equals(request.getMethod())) {
            throw new HttpRequestMethodNotSupportedException(request.getMethod(), SUPPORTED_METHODS);
        }
        requireSingleValue(request, NAME);
        ApiKitAcceptNegotiator.negotiate(request);
        String json = netsuiteSearchService.getCustomers(name);
        // The bare media type the RAML declares, UTF-8 bytes; whether the original added a charset parameter is a
        // Tier 2A verification point, settled by a captured fixture.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(json);
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
    @RequestMapping(path = CUSTOMERS_PATH, method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public ResponseEntity<Void> rejectUndeclaredMethod(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), SUPPORTED_METHODS);
    }

    /**
     * Rejects a request that sends the query parameter {@code parameter} more than once. APIkit 3.8 validated
     * each RAML query parameter that is not declared {@code repeat: true} and answered a repeated value with its
     * 400 mapping, while Spring would bind the values joined by commas, {@code a,b}, as one search prefix.
     *
     * @param request   the current request
     * @param parameter the RAML query parameter, which the RAML does not declare repeatable
     * @throws ConstraintViolationException when the request carries more than one value for {@code parameter}
     */
    private static void requireSingleValue(HttpServletRequest request, String parameter) {
        String[] values = request.getParameterValues(parameter);
        if (values != null && values.length > 1) {
            throw new ConstraintViolationException("Query parameter " + parameter + " is not repeatable", Set.of());
        }
    }
}
