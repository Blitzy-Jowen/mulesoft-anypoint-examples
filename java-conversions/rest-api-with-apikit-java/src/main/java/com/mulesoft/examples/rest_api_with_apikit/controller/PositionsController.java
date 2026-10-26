/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.controller;

import com.mulesoft.examples.rest_api_with_apikit.mapper.LeagueJsonMapper;
import com.mulesoft.examples.rest_api_with_apikit.service.LeagueAPI;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
 * <p>Errors are answered by the advices, never here: an {@code Accept} header excluding JSON reaches
 * APIkit's 406 mapping and any other method its 405 mapping ({@code ApiKitExceptionHandler}), and a
 * rendering failure the generic 500 ({@code GlobalExceptionHandler}).
 */
@RestController
public class PositionsController {

    private final LeagueAPI leagueAPI;

    private final LeagueJsonMapper leagueJsonMapper;

    public PositionsController(LeagueAPI leagueAPI, LeagueJsonMapper leagueJsonMapper) {
        this.leagueAPI = leagueAPI;
        this.leagueJsonMapper = leagueJsonMapper;
    }

    /** GET {@code /api/positions}: the current standing for the current season. */
    @GetMapping(value = "/api/positions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPositions() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(leagueJsonMapper.toJson(leagueAPI.getPositions()));
    }
}
