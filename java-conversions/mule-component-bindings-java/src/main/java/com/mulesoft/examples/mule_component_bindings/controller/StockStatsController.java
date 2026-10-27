/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.controller;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.mulesoft.examples.mule_component_bindings.model.StockStats;
import com.mulesoft.examples.mule_component_bindings.service.StockStatsService;

/**
 * Replaces flow {@code StockServiceFlow} (mule-component-bindings/src/main/app/mule-component-bindings.xml:40-54):
 * the {@code http:listener} on localhost:8180 {@code /api/*} feeding the JAX-RS resource
 * {@code stockstats.StockStatsResource}, which declares {@code @Path("/stockStats")} and a {@code @GET} method
 * {@code search} over the query parameters {@code stock} and {@code date}. It only admits the request by its media
 * headers, binds the query parameters and writes the result; validation, the Stocklytics, Twitter and Sentiment140
 * calls and every error text belong to {@link StockStatsService}.
 *
 * <p>The response reproduces what the JAX-RS runtime sent: the stats as JSON, 204 without a body when the service
 * returns no stats, and for a {@code WebApplicationException(Response.status(s).entity(message))}, now a
 * {@link ResponseStatusException}, status {@code s} with the raw message as the body, typed {@code application/json}
 * by the resource's {@code @Produces}.
 *
 * <p>Before the service runs, {@link JaxRsMediaSelection} applies the checks with which the JAX-RS runtime selected
 * the method against the resource's {@code @Consumes("application/json")} and {@code @Produces("application/json")}:
 * a repeated or unparseable {@code Content-Type} or an unparseable {@code Accept} is answered 400, a
 * {@code Content-Type} not compatible with JSON 415, and an {@code Accept} that admits no JSON 406, each with the
 * standard reason phrase, no Content-Type and an empty body, because the runtime's exceptions carried no entity. A
 * request without {@code Content-Type}, the plain GET, passes. The mapping declares no Spring {@code consumes} or
 * {@code produces} condition instead, because Spring reads a missing Content-Type as
 * {@code application/octet-stream} and its media-type exceptions reach the generic 500 of
 * {@code exception/GlobalExceptionHandler}.
 *
 * <p>Other methods, other paths and the wrong port are answered by {@code exception/GlobalExceptionHandler}
 * (405, 404) and {@code config/PortRoutingFilter} (404); the port and host come from {@code application.yml}.
 *
 * <p>Tier 2A verification points, where a captured fixture of the original governs:
 * <ol>
 *   <li>the status line, headers and empty body of the 400, 406 and 415 as the Mule listener wrote the runtime's
 *       responses;</li>
 *   <li>{@code OPTIONS /api/stockStats}, answered here by Spring MVC with 200 and an {@code Allow} header, where the
 *       runtime generated a WADL description;</li>
 *   <li>the Content-Type of the 200, always {@code application/json} here, where the runtime derived it from the
 *       matching {@code Accept} element, keeping that element's letter case and parameters (for example
 *       {@code Accept: application/json;charset=utf-8});</li>
 *   <li>the 500 for a {@code Content-Type} whose {@code q} parameter is not a quality value, an unmapped exception
 *       in the runtime.</li>
 * </ol>
 */
@RestController
public class StockStatsController {

    private final StockStatsService stockStatsService;

    /**
     * @param stockStatsService the port of {@code StockStatsResourceImpl}
     */
    public StockStatsController(StockStatsService stockStatsService) {
        this.stockStatsService = stockStatsService;
    }

    /**
     * Serves {@code GET /api/stockStats}, and {@code HEAD}, which Spring MVC derives from the GET mapping as JAX-RS
     * did.
     *
     * <p>{@code stock} and {@code date} are read from the raw query string alone, as {@code @QueryParam} read them:
     * form fields of a request body never count, unlike {@link HttpServletRequest#getParameter(String)}, and no
     * Spring MVC parameter annotation is used, because a bound {@code String} joins repeated values with commas and
     * a required one answers Spring's own 400. {@link #queryParameter(String, String)} gives the first value of a
     * repeated parameter and {@code null} for an absent one, so the service produces {@code stock is required} and
     * {@code date is required} itself.
     *
     * <p>The media checks of {@link JaxRsMediaSelection} run first: a request they reject is answered with the
     * status alone and the service is not called.
     *
     * @param request the current request, the source of the media headers and the raw query string
     * @return 200 with the stats as JSON, 204 without a body when the service has no stats for the date, or 400, 406
     *         or 415 without a body when the media checks reject the request
     * @throws IllegalArgumentException when a term of the query carries a malformed percent-escape, or the
     *                                  {@code Content-Type} a {@code q} parameter that is not a quality value
     */
    @GetMapping("/api/stockStats")
    public ResponseEntity<StockStats> search(HttpServletRequest request) {
        Optional<HttpStatus> rejection = JaxRsMediaSelection.rejection(request);
        if (rejection.isPresent()) {
            return ResponseEntity.status(rejection.get()).build();
        }
        String query = request.getQueryString();
        String stock = queryParameter(query, "stock");
        String date = queryParameter(query, "date");
        StockStats stats = stockStatsService.search(stock, date);
        if (stats == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(stats);
    }

    /**
     * Returns the first value of the parameter {@code name} in a raw (undecoded) query string, as the JAX-RS
     * runtime's query decoding gave it to {@code @QueryParam}, or {@code null} when no term names it.
     *
     * <p>The query is split at every {@code &} only, so a {@code ;} belongs to the value. An empty term and a term
     * whose first {@code =} is its first character are skipped; a term without {@code =} names a parameter with the
     * empty value. Name and value are each decoded as {@code application/x-www-form-urlencoded} UTF-8, so {@code +}
     * is a space and {@code st%6Fck} names {@code stock}; names are compared case-sensitively. Every term is decoded,
     * including those after the first match, as the runtime decoded the whole query before binding.
     *
     * @param rawQuery the query string as received, without the {@code ?}; {@code null} or empty when there is none
     * @param name     the decoded parameter name
     * @return the decoded first value, {@code ""} for a bare name, or {@code null} when absent
     * @throws IllegalArgumentException when any term carries a malformed percent-escape
     */
    static String queryParameter(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        String first = null;
        int start = 0;
        do {
            int end = rawQuery.indexOf('&', start);
            String term = end < 0 ? rawQuery.substring(start) : rawQuery.substring(start, end);
            int equals = term.indexOf('=');
            String termName = null;
            String termValue = null;
            if (equals > 0) {
                termName = URLDecoder.decode(term.substring(0, equals), StandardCharsets.UTF_8);
                termValue = URLDecoder.decode(term.substring(equals + 1), StandardCharsets.UTF_8);
            } else if (equals < 0 && !term.isEmpty()) {
                termName = URLDecoder.decode(term, StandardCharsets.UTF_8);
                termValue = "";
            }
            if (first == null && name.equals(termName)) {
                first = termValue;
            }
            start = end + 1;
        } while (start > 0 && start < rawQuery.length());
        return first;
    }

    /**
     * Answers a {@link ResponseStatusException} from {@link #search} as the JAX-RS runtime answered the original
     * {@code WebApplicationException}: its status, Content-Type {@code application/json} and its reason as the raw
     * body text, unquoted (written by the String converter, not as a JSON string literal), or an empty body when
     * the reason is {@code null}. The reason phrase is the standard one for the status. Being local to this
     * controller, the handler takes precedence over every {@code @ControllerAdvice}, so these errors never reach
     * the generic {@code { "message": ... }} 500 of {@code GlobalExceptionHandler}.
     *
     * @param e the failure the service raised: 400 for invalid input, 500 for any other failure
     * @return the status and body the original resource produced for it
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleResponseStatus(ResponseStatusException e) {
        String reason = e.getReason();
        return ResponseEntity.status(e.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(reason == null ? "" : reason);
    }
}
