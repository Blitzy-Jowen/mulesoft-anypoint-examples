/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.controller;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.mulesoft.examples.rest_api_with_apikit.config.ApiKitAcceptNegotiator;
import com.mulesoft.examples.rest_api_with_apikit.config.JsonSchemaValidator;
import com.mulesoft.examples.rest_api_with_apikit.mapper.LeagueJsonMapper;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Teams;
import com.mulesoft.examples.rest_api_with_apikit.service.LeagueAPI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The RAML resources {@code /teams} and {@code /teams/{teamId}} of {@code leagues.raml}, served under the
 * original listener path {@code /api/*}. Each HTTP method the RAML declares is served by one controller method
 * replacing one APIkit flow of {@code rest-api-with-apikit.xml}:
 * <ul>
 *   <li>{@code get:/teams} → {@link #getTeams(HttpServletRequest)}: 200 {@code application/json};</li>
 *   <li>{@code post:/teams} → {@link #addTeam(String)}: 201 with {@code Location} and an empty body;</li>
 *   <li>{@code get:/teams/{teamId}} → {@link #getTeam(String, HttpServletRequest)}: 200
 *       {@code application/json};</li>
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
 *   <li>an undeclared method, body media type or {@code Accept} type → APIkit's 405, 415 or 406 mapping. Each GET
 *       negotiates its {@code Accept} header with {@link ApiKitAcceptNegotiator} after its URI parameter
 *       validation and before it calls {@link LeagueAPI}, where APIkit negotiated it. HEAD
 *       and OPTIONS, which Spring would otherwise answer itself, are claimed on both resources by
 *       {@link #rejectUndeclaredTeamsMethod(HttpServletRequest)} and
 *       {@link #rejectUndeclaredTeamMethod(HttpServletRequest)}, which raise the same 405;</li>
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

    /** The query parameter of {@code get:/teams}, as {@code leagues.raml} and the original flow name it. */
    private static final String CITY_PARAMETER = "city";

    /** The methods {@code leagues.raml} declares for {@code /teams}. */
    private static final List<String> TEAMS_METHODS = List.of("GET", "POST");

    /** The methods {@code leagues.raml} declares for {@code /teams/{teamId}}. */
    private static final List<String> TEAM_METHODS = List.of("GET", "PUT", "DELETE");

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
     * <p>The {@code city} filter is the original's {@code message.inboundProperties['http.query.params'].city},
     * read from the query string alone by {@link #queryParameter(String, String)} and passed through unchanged:
     * {@code null} when absent or written without {@code =} ({@code ?city}), {@code ""} for {@code ?city=}, the
     * last value when repeated. A form body cannot supply or change it, as it could not in the original.
     *
     * @param request the current request, whose raw query string is read and whose {@code Accept} header is
     *                negotiated
     * @return 200 {@code application/json} with the team list
     * @throws HttpMediaTypeNotAcceptableException when APIkit's negotiation chooses no declared type for the
     *                                             {@code Accept} header
     */
    @GetMapping(value = TEAMS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getTeams(HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        ApiKitAcceptNegotiator.negotiate(request);
        Teams teams = leagueAPI.getTeams(queryParameter(request.getQueryString(), CITY_PARAMETER));
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
     * @param teamId  the three-letter team code
     * @param request the current request, whose {@code Accept} header is negotiated
     * @return 200 {@code application/json} with the team, null properties omitted
     * @throws HttpMediaTypeNotAcceptableException when APIkit's negotiation chooses no declared type for the
     *                                             {@code Accept} header
     */
    @GetMapping(value = TEAM_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getTeam(@PathVariable("teamId") @Size(min = 3, max = 3) String teamId,
                                          HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        ApiKitAcceptNegotiator.negotiate(request);
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
     * Rejects HEAD and OPTIONS on {@code /teams}, which the RAML does not declare, so they reach APIkit's 405
     * as every undeclared method did in the original router. Without this mapping Spring would answer HEAD
     * through {@link #getTeams(HttpServletRequest)} and OPTIONS with its own 200 and {@code Allow} header. Spring
     * ranks an explicit HEAD mapping above the implicit HEAD of a GET mapping, and this one declares no
     * {@code produces}, so it also claims a HEAD whose {@code Accept} header excludes JSON.
     *
     * @param request the current request, whose method is reported
     * @return never
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(value = TEAMS_PATH, method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public ResponseEntity<Void> rejectUndeclaredTeamsMethod(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), TEAMS_METHODS);
    }

    /**
     * Rejects HEAD and OPTIONS on {@code /teams/{teamId}}, which the RAML does not declare, so they reach APIkit's
     * 405 instead of Spring's implicit HEAD and OPTIONS answers; see
     * {@link #rejectUndeclaredTeamsMethod(HttpServletRequest)}. It binds no {@code teamId}: APIkit rejected the
     * method before it validated URI parameters, so a {@code teamId} of the wrong length still yields the 405,
     * not the 400.
     *
     * @param request the current request, whose method is reported
     * @return never
     * @throws HttpRequestMethodNotSupportedException always
     */
    @RequestMapping(value = TEAM_PATH, method = {RequestMethod.HEAD, RequestMethod.OPTIONS})
    public ResponseEntity<Void> rejectUndeclaredTeamMethod(HttpServletRequest request)
            throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(request.getMethod(), TEAM_METHODS);
    }

    /**
     * The port the original wrote into {@code Location} as {@code ${http.port}}, its listener port: the
     * running server's actual port first, so a random or overridden port is reported correctly, then the
     * configured {@code http.port}.
     */
    private String port() {
        return environment.getProperty("local.server.port", environment.getProperty("http.port", "8080"));
    }

    /**
     * The value the Mule 3.8.0 HTTP listener put under {@code name} in {@code http.query.params}, read as the
     * original flow read it. The listener built that map from the query string alone, never from a form body,
     * with {@code HttpParser.decodeQueryString}, and a lookup returned {@code ParameterMap.get}:
     * <ul>
     *   <li>a {@code null} or blank query string has no parameters;</li>
     *   <li>the query string is split on {@code &} with {@link String#split(String)}, and each pair at its first
     *       {@code =};</li>
     *   <li>name and value are each decoded by {@link URLDecoder} as UTF-8, so {@code +} and {@code %20} become a
     *       space; a pair without {@code =} has the value {@code null};</li>
     *   <li>of several pairs with the same decoded name the last wins, a {@code null} value included.</li>
     * </ul>
     * Every pair is decoded, as the listener decoded them all before the flow ran.
     *
     * @param rawQuery the undecoded query string after the first {@code ?}, or {@code null} for none
     * @param name     the decoded parameter name, matched case-sensitively
     * @return the last value of {@code name}, or {@code null} when it is absent or its last pair has no
     *         {@code =}
     * @throws IllegalArgumentException for a malformed {@code %} escape, as {@link URLDecoder} throws it;
     *                                  Undertow's own URL decoding answers such a request 400 before it reaches
     *                                  a controller, as the Mule listener answered 400 when its decoding failed
     */
    private static String queryParameter(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        String value = null;
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            String pairName = URLDecoder.decode(separator == -1 ? pair : pair.substring(0, separator),
                    StandardCharsets.UTF_8);
            String pairValue = separator == -1
                    ? null
                    : URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            if (name.equals(pairName)) {
                value = pairValue;
            }
        }
        return value;
    }
}
