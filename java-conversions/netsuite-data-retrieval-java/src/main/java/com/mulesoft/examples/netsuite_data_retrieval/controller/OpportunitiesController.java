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
 * The RAML resource {@code /opportunities} of {@code api/netsuite-api.raml}, served under the APIkit listener path
 * {@code /api/*} of the original {@code netsuite-data-retrieval/src/main/app/netsuite-api.xml}. It replaces the
 * {@code netsuite-api-main} flow's {@code apikit:router} and the {@code apikit:flow-mapping} that routed
 * {@code GET /opportunities} to the flow {@code get:/oportunities:netsuite-api-config}; the flow name is misspelt in
 * the original, and the RAML path {@code /opportunities} governs the endpoint.
 *
 * <p>The RAML declares a single method, GET, with the optional string query parameter {@code title} (no default)
 * and the response 200 {@code application/json}. The parameter reaches {@link NetsuiteSearchService} unchanged:
 * {@code null} when absent and {@code ""} when sent empty, so the flow's {@code title != null} test decides the
 * criteria there. The body is the service's JSON text unchanged; it is never parsed, because opportunity addresses
 * can carry raw line breaks inside their {@code addrText} values. Query parameters the RAML does not declare are
 * ignored, as APIkit ignored them.
 *
 * <p>Error responses come from the advices, so nothing is caught here: a method the resource does not declare
 * raises {@link HttpRequestMethodNotSupportedException} and an {@code Accept} header excluding JSON, or one under
 * which {@link ApiKitAcceptNegotiator} chooses no declared type once {@code title} has passed its check, raises
 * {@link HttpMediaTypeNotAcceptableException}, answered by {@code ApiKitExceptionHandler} with APIkit's 405 and
 * 406 mappings; a repeated {@code title} gets its 400 mapping; the SC-9 upstream failures reach
 * {@code UpstreamExceptionHandler} (502/429/503) and every other failure reaches {@code GlobalExceptionHandler}'s
 * 500.
 */
@RestController
@Validated
@RequestMapping("${apikit.base-path:/api}")
public class OpportunitiesController {

    /** The RAML resource, relative to the APIkit listener base path. */
    private static final String OPPORTUNITIES_PATH = "/opportunities";

    /** The RAML query parameter of {@code GET /opportunities}. */
    private static final String TITLE = "title";

    /** The methods {@code /opportunities} declares in the RAML. */
    private static final List<String> SUPPORTED_METHODS = List.of("GET");

    private final NetsuiteSearchService netsuiteSearchService;

    /**
     * @param netsuiteSearchService the flow logic of {@code get:/oportunities:netsuite-api-config}
     */
    public OpportunitiesController(NetsuiteSearchService netsuiteSearchService) {
        this.netsuiteSearchService = netsuiteSearchService;
    }

    /**
     * Routes to flow {@code get:/oportunities:netsuite-api-config}.
     *
     * @param title   the {@code title} query parameter: {@code null} when absent, {@code ""} when sent empty
     * @param request the current request, whose method and repeated parameters are checked and whose
     *                {@code Accept} header is negotiated
     * @return 200, {@code Content-Type: application/json}, with the opportunities as DataWeave JSON, and
     *         {@code Cache-Control: no-store} and {@code X-Content-Type-Options: nosniff}, so that neither the
     *         client nor an intermediary keeps a copy of the opportunity records and no browser reads them as another
     *         content type; the original declared neither header, and no error reply carries them
     * @throws HttpRequestMethodNotSupportedException for a HEAD request, which the RAML does not declare
     * @throws ConstraintViolationException           when {@code title} is sent more than once
     * @throws HttpMediaTypeNotAcceptableException    when APIkit's negotiation chooses no declared type for the
     *                                                {@code Accept} header of a valid request
     * @throws Exception                              the service's exceptions, unchanged
     */
    @GetMapping(path = OPPORTUNITIES_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getOpportunities(@RequestParam(name = TITLE, required = false) String title,
                                                   HttpServletRequest request) throws Exception {
        // Spring maps HEAD to GET handlers implicitly; this keeps getOpportunities() from ever answering HEAD,
        // whichever mapping the dispatcher selects for it.
        if (RequestMethod.HEAD.name().equals(request.getMethod())) {
            throw new HttpRequestMethodNotSupportedException(request.getMethod(), SUPPORTED_METHODS);
        }
        requireSingleValue(request, TITLE);
        ApiKitAcceptNegotiator.negotiate(request);
        String json = netsuiteSearchService.getOpportunities(title);
        // The bare media type, without a charset parameter; the body is written as UTF-8 bytes.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(json);
    }

    /**
     * Rejects HEAD and OPTIONS, which the RAML does not declare, so they reach APIkit's 405 mapping. Without this
     * mapping Spring would answer OPTIONS itself with 200 and an {@code Allow} header. No {@code produces} is
     * declared, so the mapping also claims HEAD whose {@code Accept} header excludes JSON.
     *
     * @param request the current request, whose method is reported
     * @return never
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(path = OPPORTUNITIES_PATH, method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public ResponseEntity<Void> rejectUndeclaredMethod(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), SUPPORTED_METHODS);
    }

    /**
     * Rejects a query parameter sent more than once. APIkit 3.8 validated every RAML query parameter that is not
     * declared {@code repeat: true} as a single value and answered a repeated one with its 400 mapping, whereas
     * Spring would bind the values joined by commas.
     *
     * @param request the current request
     * @param name    the non-repeatable RAML query parameter
     * @throws ConstraintViolationException when {@code name} carries more than one value
     */
    private static void requireSingleValue(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values != null && values.length > 1) {
            throw new ConstraintViolationException("Query parameter " + name + " is not repeatable", Set.of());
        }
    }
}
