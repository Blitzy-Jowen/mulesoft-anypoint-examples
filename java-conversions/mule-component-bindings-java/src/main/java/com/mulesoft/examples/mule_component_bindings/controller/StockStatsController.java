/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.controller;

import jakarta.servlet.http.HttpServletRequest;

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
 * {@code search} over the query parameters {@code stock} and {@code date}. It only binds the query parameters and
 * writes the result; validation, the Stocklytics, Twitter and Sentiment140 calls and every error text belong to
 * {@link StockStatsService}.
 *
 * <p>The response reproduces what the JAX-RS runtime sent: the stats as JSON, 204 without a body when the service
 * returns no stats, and for a {@code WebApplicationException(Response.status(s).entity(message))}, now a
 * {@link ResponseStatusException}, status {@code s} with the raw message as the body, typed {@code application/json}
 * by the resource's {@code @Produces}.
 *
 * <p>The mapping declares no request or response media type condition: the resource's declared JSON request type
 * never rejected a GET without a body, and the explicit Content-Type of each response bypasses {@code Accept}
 * negotiation. Other methods, other paths and the wrong port are answered by
 * {@code exception/GlobalExceptionHandler} (405, 404) and {@code config/PortRoutingFilter} (404); the port and host
 * come from {@code application.yml}.
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
     * <p>The parameters are read with {@link HttpServletRequest#getParameter(String)} rather than bound by a Spring
     * MVC parameter annotation, to keep {@code @QueryParam} semantics: the first value of a repeated parameter (where
     * a bound {@code String} would join the values with commas) and {@code null} when absent (where a required bound
     * parameter would answer Spring's own 400), so the service produces {@code stock is required} and
     * {@code date is required} itself.
     *
     * @param request the current request, the source of the {@code stock} and {@code date} query parameters
     * @return 200 with the stats as JSON, or 204 without a body when the service has no stats for the date
     */
    @GetMapping("/api/stockStats")
    public ResponseEntity<StockStats> search(HttpServletRequest request) {
        String stock = request.getParameter("stock");
        String date = request.getParameter("date");
        StockStats stats = stockStatsService.search(stock, date);
        if (stats == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(stats);
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
