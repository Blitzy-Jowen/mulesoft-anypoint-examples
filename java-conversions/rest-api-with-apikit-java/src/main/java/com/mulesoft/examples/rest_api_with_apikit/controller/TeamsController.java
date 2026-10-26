/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.controller;

import com.mulesoft.examples.rest_api_with_apikit.config.JsonSchemaValidator;
import com.mulesoft.examples.rest_api_with_apikit.mapper.LeagueJsonMapper;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Teams;
import com.mulesoft.examples.rest_api_with_apikit.service.LeagueAPI;

import jakarta.validation.constraints.Size;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The RAML resources {@code /teams} and {@code /teams/{teamId}} of {@code leagues.raml}, served under the
 * original listener path {@code /api/*}. Each method replaces one APIkit flow of
 * {@code rest-api-with-apikit.xml}:
 * <ul>
 *   <li>{@code get:/teams} → {@link #getTeams(String)}: 200 {@code application/json};</li>
 *   <li>{@code post:/teams} → {@link #addTeam(String)}: 201 with {@code Location} and an empty body;</li>
 *   <li>{@code get:/teams/{teamId}} → {@link #getTeam(String)}: 200 {@code application/json};</li>
 *   <li>{@code put:/teams/{teamId}} → {@link #updateTeam(String, String)}: 204 with an empty body;</li>
 *   <li>{@code delete:/teams/{teamId}} → {@link #deleteTeam(String)}: 204 with an empty body.</li>
 * </ul>
 * Each returns the 2xx its RAML method declares, as APIkit wrote it for a flow that set no status.
 *
 * <p>The controller only binds and validates input, calls {@link LeagueAPI} and returns its output. The JSON
 * bodies are read and written by {@link LeagueJsonMapper}, and request bodies are checked against the RAML's
 * draft-03 schemas by {@link JsonSchemaValidator}, the APIkit router's validation; it holds no transformation
 * or routing logic of its own. The {@code teamId} URI parameter carries the RAML's {@code minLength} and
 * {@code maxLength} 3 as {@link Size}, enforced by Spring MVC's built-in method validation.
 *
 * <p>It declares no exception handling. Every failure propagates to the advices:
 * <ul>
 *   <li>an unknown team ({@code ApiKitNotFoundException}) → APIkit's 404 mapping in {@code ApiKitExceptionHandler};</li>
 *   <li>an invalid {@code teamId}, a missing body or one its schema rejects → APIkit's 400 mapping;</li>
 *   <li>an undeclared method, body media type or {@code Accept} type → APIkit's 405, 415 or 406 mapping;</li>
 *   <li>a duplicate team on POST ({@code ConflictException}) → 409 in {@code LeagueExceptionHandler}. The
 *       original flow mapped no 409; the RAML declares it, and the RAML contract governs, as recorded in
 *       the repository's {@code DECISIONS.md} and {@code RAML-CONTROLLER-DIFF.md}.</li>
 * </ul>
 */
@RestController
public class TeamsController {

    private static final String TEAMS_PATH = "/api/teams";

    private static final String TEAM_PATH = "/api/teams/{teamId}";

    private static final String TEAM_POST_SCHEMA = "schemas/teams-schema-output.json";

    private static final String TEAM_PUT_SCHEMA = "schemas/teamid-schema-input.json";

    private final LeagueAPI leagueAPI;

    private final LeagueJsonMapper leagueJsonMapper;

    private final JsonSchemaValidator jsonSchemaValidator;

    private final Environment environment;

    public TeamsController(LeagueAPI leagueAPI, LeagueJsonMapper leagueJsonMapper,
                           JsonSchemaValidator jsonSchemaValidator, Environment environment) {
        this.leagueAPI = leagueAPI;
        this.leagueJsonMapper = leagueJsonMapper;
        this.jsonSchemaValidator = jsonSchemaValidator;
        this.environment = environment;
    }

    /**
     * GET {@code /api/teams}: the league's teams, optionally only those of one home city.
     *
     * @param city the optional {@code city} query parameter, passed through unchanged as the original's
     *             {@code http.query.params} lookup passed it: {@code null} when absent, {@code ""} for
     *             {@code ?city=}
     * @return 200 {@code application/json} with the team list
     */
    @GetMapping(value = TEAMS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getTeams(@RequestParam(value = "city", required = false) String city) {
        Teams teams = leagueAPI.getTeams(city);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(leagueJsonMapper.toJson(teams));
    }

    /**
     * POST {@code /api/teams}: adds a team.
     *
     * <p>The steps keep the original flow's order: the body is validated and normalised, the
     * {@code Location} value is computed from the body's {@code id} (the flow's {@code set-property}), and only
     * then is the team added. A body without {@code id} therefore yields {@code .../api/teams/null}, as the
     * flow's string concatenation did.
     *
     * @param body the raw JSON body, validated against {@code schemas/teams-schema-output.json}
     * @return 201 with {@code Location: http://localhost:<port>/api/teams/<id>} and an empty body
     */
    @PostMapping(value = TEAMS_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> addTeam(@RequestBody String body) {
        jsonSchemaValidator.validate(TEAM_POST_SCHEMA, body);
        com.mulesoft.examples.rest_api_with_apikit.model.request.Team team = leagueJsonMapper.readTeam(body);
        String location = "http://localhost:" + port() + TEAMS_PATH + "/" + team.getId();
        leagueAPI.addTeam(team);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }

    /**
     * GET {@code /api/teams/{teamId}}: one team with its number of matches played.
     *
     * @param teamId the three-letter team code
     * @return 200 {@code application/json} with the team, null properties omitted
     */
    @GetMapping(value = TEAM_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getTeam(@PathVariable("teamId") @Size(min = 3, max = 3) String teamId) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(leagueJsonMapper.toJson(leagueAPI.getTeam(teamId)));
    }

    /**
     * PUT {@code /api/teams/{teamId}}: updates the name, home city or stadium the body supplies.
     *
     * @param teamId the three-letter team code
     * @param body   the raw JSON body, validated against {@code schemas/teamid-schema-input.json}
     * @return 204 with an empty body
     */
    @PutMapping(value = TEAM_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> updateTeam(@PathVariable("teamId") @Size(min = 3, max = 3) String teamId,
                                           @RequestBody String body) {
        jsonSchemaValidator.validate(TEAM_PUT_SCHEMA, body);
        leagueAPI.updateTeam(teamId, leagueJsonMapper.readUpdateTeam(body));
        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE {@code /api/teams/{teamId}}: removes a team.
     *
     * @param teamId the three-letter team code
     * @return 204 with an empty body
     */
    @DeleteMapping(TEAM_PATH)
    public ResponseEntity<Void> deleteTeam(@PathVariable("teamId") @Size(min = 3, max = 3) String teamId) {
        leagueAPI.deleteTeam(teamId);
        return ResponseEntity.noContent().build();
    }

    /**
     * The port the original wrote into {@code Location} as {@code ${http.port}}, its listener port: the
     * running server's actual port first, so a random or overridden port is reported correctly, then the
     * configured {@code http.port}.
     */
    private String port() {
        return environment.getProperty("local.server.port", environment.getProperty("http.port", "8080"));
    }
}
