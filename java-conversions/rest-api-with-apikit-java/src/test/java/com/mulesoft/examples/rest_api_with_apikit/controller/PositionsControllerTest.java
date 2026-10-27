/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.rest_api_with_apikit.support.HttpTestClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;

/**
 * Wire-level test of {@link PositionsController}, the RAML resource {@code /positions} of {@code leagues.raml},
 * which replaces the APIkit flow {@code get:/positions:leagues-config} of {@code rest-api-with-apikit.xml}
 * (the flow set the payload to {@code league-api.getPositions()}).
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the
 * socket, so status code, reason phrase, {@code Content-Type} and body bytes are asserted as Undertow writes
 * them. Besides the happy path, the class covers the listener-level answers around the resource: APIkit's
 * 405 for an undeclared method and 406 for an {@code Accept} type the RAML does not declare, APIkit's 404 for an
 * unknown resource under {@code /api}, and the listener's no-listener 404 outside it.
 *
 * <p>The class only reads the league. It asserts relations derived at run time, never fixed counts or scores:
 * the fixture scores are random, and the cached application context it shares with the project's other
 * {@code @SpringBootTest} classes may hold teams or results those classes added, changed or removed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PositionsControllerTest {

    private static final String POSITIONS_PATH = "/api/positions";

    private static final String TEAMS_PATH = "/api/teams";

    /** Body of APIkit's 404 mapping, spacing included. */
    private static final String RESOURCE_NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    /** Body of APIkit's 405 mapping, spacing included. */
    private static final String METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    /** Body of APIkit's 406 mapping, spacing included. */
    private static final String NOT_ACCEPTABLE = "{ \"message\": \"Not acceptable\" }";

    /** The integer statistics every entry of the league table carries besides {@code position}. */
    private static final List<String> STATISTIC_FIELDS = List.of("points", "matchesPlayed", "matchesWon",
            "matchesDraw", "matchesLost", "goalsInFavor", "goalsAgainst");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    private HttpTestClient client;

    @BeforeEach
    void setUp() {
        client = HttpTestClient.localhost(port);
    }

    /**
     * GET {@code /api/positions} lists every team of {@code /api/teams} once, numbered 1..n in standing order:
     * points, then goal difference, then goals scored, each descending, as the ported
     * {@code League.orderTeamsByPosition} sorts them; ties keep any order.
     */
    @Test
    void getPositionsReturnsOrderedTable() throws IOException {
        HttpTestClient.Response teamsResponse = client.get(TEAMS_PATH);
        assertEquals(200, teamsResponse.status(), teamsResponse::toString);
        JsonNode teams = readJson(teamsResponse).get("teams");
        assertNotNull(teams, teamsResponse::toString);
        assertTrue(teams.isArray(), teamsResponse::toString);
        Set<String> teamIds = new HashSet<>();
        for (JsonNode team : teams) {
            assertTrue(team.path("id").isTextual(), () -> "team without a textual id: " + team);
            teamIds.add(team.get("id").textValue());
        }

        HttpTestClient.Response response = client.get(POSITIONS_PATH);

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertMediaType(response, MediaType.APPLICATION_JSON);
        JsonNode positions = readJson(response).get("positions");
        assertNotNull(positions, response::toString);
        assertTrue(positions.isArray(), response::toString);
        assertEquals(teams.size(), positions.size(), response::toString);

        Set<String> positionTeams = new HashSet<>();
        JsonNode previous = null;
        for (int i = 0; i < positions.size(); i++) {
            JsonNode entry = positions.get(i);
            int expectedPosition = i + 1;
            assertTrue(entry.path("position").isInt(), () -> "position is not an int: " + entry);
            assertEquals(expectedPosition, entry.get("position").intValue(), entry::toString);
            for (String field : STATISTIC_FIELDS) {
                assertTrue(entry.path(field).isInt(), () -> field + " is not an int: " + entry);
            }
            assertTrue(entry.path("team").isTextual(), () -> "team is not textual: " + entry);
            assertTrue(positionTeams.add(entry.get("team").textValue()),
                    () -> "team listed twice: " + entry);

            assertEquals(3 * value(entry, "matchesWon") + value(entry, "matchesDraw"), value(entry, "points"),
                    () -> "points are not 3 per win and 1 per draw: " + entry);
            assertEquals(value(entry, "matchesWon") + value(entry, "matchesDraw") + value(entry, "matchesLost"),
                    value(entry, "matchesPlayed"),
                    () -> "matches played are not wins + draws + losses: " + entry);

            if (previous != null) {
                JsonNode above = previous;
                assertTrue(compareStanding(above, entry) >= 0,
                        () -> "position " + expectedPosition + " ranks above the position before it: "
                                + above + " then " + entry);
            }
            previous = entry;
        }
        assertEquals(teamIds, positionTeams);
    }

    /** The RAML declares only GET on {@code /positions}, so POST reaches APIkit's 405 mapping. */
    @Test
    void postPositionsIsMethodNotAllowed() {
        HttpTestClient.Response response = client.post(POSITIONS_PATH, MediaType.APPLICATION_JSON_VALUE, "{}");

        assertApiKitError(response, 405, "Method Not Allowed", METHOD_NOT_ALLOWED);
    }

    /** The RAML declares only {@code application/json} for the 200, so {@code Accept: text/xml} gets 406. */
    @Test
    void getPositionsWithUnacceptableAcceptIsNotAcceptable() {
        HttpTestClient.Response response = client.get(POSITIONS_PATH, Map.of("Accept", "text/xml"));

        assertApiKitError(response, 406, "Not Acceptable", NOT_ACCEPTABLE);
    }

    /** Outside the listener path {@code /api/*} no APIkit router runs: the listener's own default 404 applies. */
    @Test
    void unknownPathOutsideApiHasNoListener() {
        HttpTestClient.Response response = client.get("/other");

        assertEquals(404, response.status(), response::toString);
        assertEquals("Not Found", response.reason(), response::toString);
        assertMediaType(response, MediaType.TEXT_PLAIN);
        assertEquals("No listener for endpoint: /other", response.bodyAsString(), response::toString);
    }

    /** A resource the RAML does not declare under {@code /api} reaches APIkit's 404 mapping. */
    @Test
    void unknownResourceUnderApiIsResourceNotFound() {
        HttpTestClient.Response response = client.get("/api/unknown");

        assertApiKitError(response, 404, "Not Found", RESOURCE_NOT_FOUND);
    }

    /** An APIkit mapping's answer: status, reason phrase, {@code application/json} and the exact literal body. */
    private static void assertApiKitError(HttpTestClient.Response response, int status, String reason,
                                          String body) {
        assertEquals(status, response.status(), response::toString);
        assertEquals(reason, response.reason(), response::toString);
        assertMediaType(response, MediaType.APPLICATION_JSON);
        assertEquals(body, response.bodyAsString(), response::toString);
    }

    /** The response's {@code Content-Type} has the expected type and subtype; parameters are not compared. */
    private static void assertMediaType(HttpTestClient.Response response, MediaType expected) {
        String contentType = response.header("Content-Type");
        assertNotNull(contentType, response::toString);
        MediaType actual = MediaType.parseMediaType(contentType);
        assertEquals(expected.getType(), actual.getType(), response::toString);
        assertEquals(expected.getSubtype(), actual.getSubtype(), response::toString);
    }

    /**
     * Compares two table entries by the ported standing order, higher standing first: positive when
     * {@code first} ranks above {@code second}, zero when they tie on points, goal difference and goals scored.
     */
    private static int compareStanding(JsonNode first, JsonNode second) {
        int byPoints = Integer.compare(value(first, "points"), value(second, "points"));
        if (byPoints != 0) {
            return byPoints;
        }
        int byGoalDifference = Integer.compare(goalDifference(first), goalDifference(second));
        if (byGoalDifference != 0) {
            return byGoalDifference;
        }
        return Integer.compare(value(first, "goalsInFavor"), value(second, "goalsInFavor"));
    }

    private static int goalDifference(JsonNode entry) {
        return value(entry, "goalsInFavor") - value(entry, "goalsAgainst");
    }

    private static int value(JsonNode entry, String field) {
        return entry.get(field).intValue();
    }

    private JsonNode readJson(HttpTestClient.Response response) throws IOException {
        return objectMapper.readTree(response.body());
    }
}
