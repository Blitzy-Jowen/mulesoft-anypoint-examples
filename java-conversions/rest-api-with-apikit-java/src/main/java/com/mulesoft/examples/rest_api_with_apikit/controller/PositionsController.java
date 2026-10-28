/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.controller;

import java.util.List;

import com.mulesoft.examples.rest_api_with_apikit.config.ApiKitAcceptNegotiator;
import com.mulesoft.examples.rest_api_with_apikit.mapper.LeagueJsonMapper;
import com.mulesoft.examples.rest_api_with_apikit.service.LeagueAPI;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The RAML resource {@code /positions} ("Position Table") of {@code leagues.raml}, replacing the APIkit
 * flow {@code get:/positions:leagues-config}, which set the payload to {@code league-api.getPositions()} and
 * let the {@code toJson} transformer of {@code Positions} write it.
 *
 * <p>GET {@code /api/positions} answers 200 {@code application/json} with the league table, one entry per
 * team in standing order. As APIkit did, the response is not validated against
 * {@code schemas/positions-output.json}. The controller holds no transformation logic: the table comes from
 * {@link LeagueAPI} and its bytes from {@link LeagueJsonMapper}.
 *
 * <p>Errors are answered by the advices, never here: an {@code Accept} header excluding JSON, or one under which
 * {@link ApiKitAcceptNegotiator} chooses no declared type, reaches APIkit's 406 mapping and any other method its
 * 405 mapping ({@code ApiKitExceptionHandler}), and a
 * rendering failure the generic 500 ({@code GlobalExceptionHandler}). HEAD and OPTIONS, which Spring would
 * otherwise answer itself, are claimed by {@link #rejectUndeclaredMethod(HttpServletRequest)}, which raises
 * the same 405.
 */
@RestController
public class PositionsController {

    /** The RAML resource {@code /positions} under the original listener path {@code /api/*}. */
    private static final String POSITIONS_PATH = "/api/positions";

    /** The methods {@code leagues.raml} declares for {@code /positions}. */
    private static final List<String> POSITIONS_METHODS = List.of("GET");

    private final LeagueAPI leagueAPI;

    private final LeagueJsonMapper leagueJsonMapper;

    public PositionsController(LeagueAPI leagueAPI, LeagueJsonMapper leagueJsonMapper) {
        this.leagueAPI = leagueAPI;
        this.leagueJsonMapper = leagueJsonMapper;
    }

    /**
     * GET {@code /api/positions}: the current standing for the current season.
     *
     * @param request the current request, whose {@code Accept} header is negotiated
     * @return 200 {@code application/json} with the league table
     * @throws HttpMediaTypeNotAcceptableException when APIkit's negotiation chooses no declared type for the
     *                                             {@code Accept} header
     */
    @GetMapping(value = POSITIONS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPositions(HttpServletRequest request)
            throws HttpMediaTypeNotAcceptableException {
        ApiKitAcceptNegotiator.negotiate(request);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(leagueJsonMapper.toJson(leagueAPI.getPositions()));
    }

    /**
     * Rejects HEAD and OPTIONS on {@code /positions}, which the RAML does not declare, so they reach APIkit's
     * 405 as every undeclared method did in the original router. Without this mapping Spring would answer HEAD
     * through {@link #getPositions(HttpServletRequest)} and OPTIONS with its own 200 and {@code Allow} header. Spring ranks an
     * explicit HEAD mapping above the implicit HEAD of a GET mapping, and this one declares no {@code produces},
     * so it also claims a HEAD whose {@code Accept} header excludes JSON.
     *
     * @param request the current request, whose method is reported
     * @return never
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(value = POSITIONS_PATH, method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public ResponseEntity<Void> rejectUndeclaredMethod(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), POSITIONS_METHODS);
    }
}
