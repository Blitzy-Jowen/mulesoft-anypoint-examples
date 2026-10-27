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

import com.mulesoft.examples.netsuite_data_retrieval.service.NetsuiteSearchService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The RAML resource {@code /items} of {@code api/netsuite-api.raml}, served under the APIkit listener path
 * {@code /api/*} of the original {@code netsuite-data-retrieval/src/main/app/netsuite-api.xml}. It replaces the
 * {@code netsuite-api-main} flow's {@code apikit:router} and the flow-mapping that routed {@code GET /items} to the
 * flow {@code get:/items:netsuite-api-config}.
 *
 * <p>The RAML declares a single method, GET, with two query parameters and no request body, answering 200
 * {@code application/json}:
 * <ul>
 *   <li>{@code quantity}: integer, default {@code 0};</li>
 *   <li>{@code operator}: one of {@code LESS_THAN}, {@code GREATER_THAN}, {@code EQUAL_TO},
 *       {@code LESS_THAN_OR_EQUAL_TO}, {@code GREATER_THAN_OR_EQUAL_TO}, default {@code GREATER_THAN}.</li>
 * </ul>
 * APIkit 3.8 applied a parameter's default only when the parameter was absent, validated every present value
 * against its RAML type (an integer parse, or case-sensitive enumeration membership), so an empty value failed,
 * and rejected a repeated value of a non-repeatable parameter. Binding reproduces the defaults, the {@code int}
 * conversion and the enumeration check; {@code requireValidQueryValue} adds the empty and repeated value checks,
 * which Spring's {@code defaultValue} and multi-value binding would otherwise hide, and
 * {@code requireIntegerQueryValue} the strict decimal form of {@code quantity}, which Spring's lenient number
 * conversion would otherwise widen. Every failure reaches APIkit's 400 mapping before the flow runs. Query
 * parameters the RAML does not declare are ignored, as APIkit ignored them.
 *
 * <p>This class only binds, validates and answers: the item supply plan criteria, the NetSuite reads, the
 * {@code payload map $} output and the success log are {@link NetsuiteSearchService#getItems(int, String)}'s, and
 * its JSON text is returned unchanged.
 *
 * <p>Error responses come from the advices: {@code UpstreamExceptionHandler} answers the approved upstream
 * failures (502, 429, 503); {@code ApiKitExceptionHandler} answers APIkit's 400 for an invalid query parameter,
 * 405 for a method the resource does not declare and 406 for an {@code Accept} header excluding JSON; and
 * {@code GlobalExceptionHandler} answers every other exception with its 500.
 */
@RestController
@Validated
@RequestMapping("${apikit.base-path:/api}")
public class ItemsController {

    /** The enumeration the RAML declares for {@code operator}, matched case-sensitively, as APIkit matched it. */
    static final String OPERATOR_PATTERN =
            "LESS_THAN|GREATER_THAN|EQUAL_TO|LESS_THAN_OR_EQUAL_TO|GREATER_THAN_OR_EQUAL_TO";

    /** The resource path below the API base path. */
    private static final String ITEMS_PATH = "/items";

    /** The methods {@code /items} declares in the RAML. */
    private static final List<String> SUPPORTED_METHODS = List.of("GET");

    private static final String QUANTITY = "quantity";

    private static final String OPERATOR = "operator";

    private final NetsuiteSearchService netsuiteSearchService;

    /**
     * @param netsuiteSearchService the flow logic of {@code get:/items:netsuite-api-config}
     */
    public ItemsController(NetsuiteSearchService netsuiteSearchService) {
        this.netsuiteSearchService = netsuiteSearchService;
    }

    /**
     * Routes to flow {@code get:/items:netsuite-api-config}.
     *
     * @param quantity the {@code quantity} query parameter, {@code 0} when absent; a value that is not an
     *                 {@code int} fails binding with APIkit's 400
     * @param operator the {@code operator} query parameter, {@code GREATER_THAN} when absent; a value outside the
     *                 RAML enumeration fails validation with APIkit's 400
     * @param request  the current request, whose method and raw query parameter values are checked
     * @return 200, {@code Content-Type: application/json}, with the item supply plans as the flow wrote them
     * @throws HttpRequestMethodNotSupportedException for a HEAD request, which the RAML does not declare
     * @throws ConstraintViolationException           for an empty or repeated {@code quantity} or {@code operator},
     *                                                or a {@code quantity} that is not a decimal {@code int}
     * @throws Exception                              the search service's exceptions, unchanged
     */
    @GetMapping(path = ITEMS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getItems(
            @RequestParam(name = QUANTITY, defaultValue = "0") Integer quantity,
            @RequestParam(name = OPERATOR, defaultValue = "GREATER_THAN") @Pattern(regexp = OPERATOR_PATTERN)
            String operator,
            HttpServletRequest request) throws Exception {
        // Spring maps HEAD to GET handlers implicitly; this keeps getItems() from ever answering HEAD, whichever
        // mapping the dispatcher selects for it.
        if (RequestMethod.HEAD.name().equals(request.getMethod())) {
            throw new HttpRequestMethodNotSupportedException(request.getMethod(), SUPPORTED_METHODS);
        }
        requireValidQueryValue(request, QUANTITY);
        requireIntegerQueryValue(request, QUANTITY);
        requireValidQueryValue(request, OPERATOR);
        String json = netsuiteSearchService.getItems(quantity, operator);
        // The bare media type, without a charset parameter; the original's exact Content-Type is a Tier 2A
        // verification point.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
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
    @RequestMapping(path = ITEMS_PATH, method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public ResponseEntity<Void> rejectUndeclaredMethod(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), SUPPORTED_METHODS);
    }

    /**
     * Applies APIkit's checks on a present query parameter that binding cannot express: the parameter is not
     * repeatable, and an empty value is validated against the RAML type, which it never satisfies, rather than
     * replaced by the default as Spring's {@code defaultValue} would replace it. An absent parameter passes, so its
     * RAML default applies.
     *
     * @param request the current request
     * @param name    the query parameter to check
     * @throws ConstraintViolationException when the parameter is repeated or its value is empty
     */
    private static void requireValidQueryValue(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) {
            return;
        }
        if (values.length > 1) {
            throw new ConstraintViolationException("Query parameter " + name + " is not repeatable", Set.of());
        }
        if (values[0].isEmpty()) {
            throw new ConstraintViolationException("Invalid value '' for query parameter " + name, Set.of());
        }
    }

    /**
     * Applies APIkit's RAML {@code integer} check to the raw value of a present query parameter: an optional sign
     * followed by decimal digits, within {@code int} range.
     *
     * <p>Binding alone is more lenient than the RAML type: Spring's number conversion strips every whitespace
     * character and decodes hexadecimal, so {@code " 5"}, {@code "1 0"}, {@code "0x10"} and {@code "#10"} would
     * bind as numbers and a blank value as the default, where APIkit rejected each of them with 400. A raw value
     * that passes this check binds to exactly the {@code int} it denotes.
     *
     * @param request the current request
     * @param name    the integer query parameter to check, already known to be absent or single and non-empty
     * @throws ConstraintViolationException when the raw value is not a decimal {@code int}
     */
    private static void requireIntegerQueryValue(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        if (value == null) {
            return;
        }
        try {
            Integer.parseInt(value);
        } catch (NumberFormatException e) {
            // The raw value is not echoed: it is caller input and the message never reaches the response.
            throw new ConstraintViolationException("Query parameter " + name + " is not an integer", Set.of());
        }
    }
}
