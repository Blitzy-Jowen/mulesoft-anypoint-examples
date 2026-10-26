/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.controller;

import java.util.List;

import com.mulesoft.examples.rest_api_with_apikit.config.JsonSchemaValidator;
import com.mulesoft.examples.rest_api_with_apikit.mapper.LeagueJsonMapper;
import com.mulesoft.examples.rest_api_with_apikit.service.LeagueAPI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The RAML resources {@code /fixture} and {@code /fixture/{homeTeamId}/{awayTeamId}} of {@code leagues.raml},
 * served under the original listener path {@code /api/*}. It replaces the {@code apikit:router} routing of
 * {@code rest-api-with-apikit/src/main/app/rest-api-with-apikit.xml} to three flows:
 * <ul>
 *   <li>{@code get:/fixture:leagues-config}: {@code payload = getFixture()}, written by the {@code Fixture}
 *       {@code toJson} transformer;</li>
 *   <li>{@code get:/fixture/{homeTeamId}/{awayTeamId}:leagues-config}: {@code payload = getMatch(homeTeamId,
 *       awayTeamId)}, written by Mule's automatic object-to-JSON transformer;</li>
 *   <li>{@code put:/fixture/{homeTeamId}/{awayTeamId}:leagues-config}: APIkit's
 *       {@code PayloadNormalizerTransformer}, then {@code updateScore(homeTeamId, awayTeamId, payload)}, then a
 *       null payload, which APIkit answered with the RAML's 204.</li>
 * </ul>
 *
 * <p>The controller only binds and validates the request, calls {@link LeagueAPI} and returns what
 * {@link LeagueJsonMapper} renders; it holds no transformation, routing or error-handling logic. APIkit's RAML
 * validation is reproduced at the boundary: both URI parameters must be exactly three characters
 * ({@code minLength}/{@code maxLength} 3, checked by Spring's built-in method validation), and the PUT body must
 * satisfy {@code schemas/match-schema-input.json} ({@link JsonSchemaValidator}).
 *
 * <p>Every failure propagates to the advices:
 * <ul>
 *   <li>an unknown match ({@code ApiKitNotFoundException}) → APIkit's 404 {@code { "message": "Resource not
 *       found" }};</li>
 *   <li>a score for a match not yet played ({@code ConflictException}) → 409 {@code { "message": "<exception
 *       message>" }} from {@code LeagueExceptionHandler}. The original flow mapped no 409; the RAML declares it
 *       ("Cannot update match before the match is actually played") and the RAML contract governs;</li>
 *   <li>a URI parameter of the wrong length, a missing body, or a body the schema rejects → APIkit's 400; a PUT
 *       body that is not {@code application/json} → 415; an {@code Accept} header excluding JSON on GET → 406;
 *       a method the RAML does not declare → 405, all from {@code ApiKitExceptionHandler}.</li>
 * </ul>
 */
@RestController
public class FixtureController {

    private static final String FIXTURE_PATH = "/api/fixture";

    private static final String MATCH_PATH = "/api/fixture/{homeTeamId}/{awayTeamId}";

    /** The PUT body schema, relative to the directory of {@code leagues.raml}, as its {@code !include} names it. */
    private static final String SCORE_SCHEMA = "schemas/match-schema-input.json";

    /** The methods {@code leagues.raml} declares for {@code /fixture}. */
    private static final List<String> FIXTURE_METHODS = List.of("GET");

    /** The methods {@code leagues.raml} declares for {@code /fixture/{homeTeamId}/{awayTeamId}}. */
    private static final List<String> MATCH_METHODS = List.of("GET", "PUT");

    private final LeagueAPI leagueAPI;

    private final LeagueJsonMapper leagueJsonMapper;

    private final JsonSchemaValidator jsonSchemaValidator;

    /**
     * @param leagueAPI           the league service the original flows called through {@code league-api}
     * @param leagueJsonMapper    the renderings of the original transformers and the PUT body reader
     * @param jsonSchemaValidator the RAML body-schema validation APIkit applied before invoking a flow
     */
    public FixtureController(LeagueAPI leagueAPI, LeagueJsonMapper leagueJsonMapper,
                             JsonSchemaValidator jsonSchemaValidator) {
        this.leagueAPI = leagueAPI;
        this.leagueJsonMapper = leagueJsonMapper;
        this.jsonSchemaValidator = jsonSchemaValidator;
    }

    /**
     * Flow {@code get:/fixture}: every match of the season.
     *
     * @return 200 {@code application/json}, the collection rendering: null scores written as {@code null} and
     *         dates as epoch milliseconds
     */
    @GetMapping(value = FIXTURE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getFixture() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(leagueJsonMapper.toJson(leagueAPI.getFixture()));
    }

    /**
     * Flow {@code get:/fixture/{homeTeamId}/{awayTeamId}}: one match.
     *
     * @param homeTeamId three-letter ID of the home team
     * @param awayTeamId three-letter ID of the away team
     * @return 200 {@code application/json}, the single-resource rendering: null scores omitted and the date
     *         formatted {@code yyyy-MM-dd'T'HH:mm:ssZ}
     */
    @GetMapping(value = MATCH_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getMatch(
            @PathVariable("homeTeamId") @Size(min = 3, max = 3) String homeTeamId,
            @PathVariable("awayTeamId") @Size(min = 3, max = 3) String awayTeamId) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(leagueJsonMapper.toJson(leagueAPI.getMatch(homeTeamId, awayTeamId)));
    }

    /**
     * Flow {@code put:/fixture/{homeTeamId}/{awayTeamId}}: records the result of a match already played.
     *
     * @param homeTeamId three-letter ID of the home team
     * @param awayTeamId three-letter ID of the away team
     * @param body       the raw JSON body, validated against the RAML schema before it is read
     * @return 204 with an empty body
     */
    @PutMapping(value = MATCH_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> updateScore(
            @PathVariable("homeTeamId") @Size(min = 3, max = 3) String homeTeamId,
            @PathVariable("awayTeamId") @Size(min = 3, max = 3) String awayTeamId,
            @RequestBody String body) {
        jsonSchemaValidator.validate(SCORE_SCHEMA, body);
        leagueAPI.updateScore(homeTeamId, awayTeamId, leagueJsonMapper.readScore(body));
        return ResponseEntity.noContent().build();
    }

    /**
     * Rejects HEAD and OPTIONS on {@code /fixture}, which the RAML does not declare, so they reach APIkit's 405
     * as every undeclared method did in the original router. Without this mapping Spring would answer HEAD
     * through {@link #getFixture()} and OPTIONS with its own 200 and {@code Allow} header. Spring ranks an
     * explicit HEAD mapping above the implicit HEAD of a GET mapping, and this one declares no {@code produces},
     * so it also claims a HEAD whose {@code Accept} header excludes JSON.
     *
     * @param request the current request, whose method is reported
     * @return never
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(value = FIXTURE_PATH, method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public ResponseEntity<Void> rejectUndeclaredFixtureMethod(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), FIXTURE_METHODS);
    }

    /**
     * Rejects HEAD and OPTIONS on {@code /fixture/{homeTeamId}/{awayTeamId}}, which the RAML does not declare,
     * so they reach APIkit's 405 instead of Spring's implicit HEAD and OPTIONS answers; see
     * {@link #rejectUndeclaredFixtureMethod(HttpServletRequest)}.
     *
     * @param request the current request, whose method is reported
     * @return never
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(value = MATCH_PATH, method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public ResponseEntity<Void> rejectUndeclaredMatchMethod(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), MATCH_METHODS);
    }
}
