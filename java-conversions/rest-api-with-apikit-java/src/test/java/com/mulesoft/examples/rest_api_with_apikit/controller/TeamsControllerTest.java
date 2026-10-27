/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.rest_api_with_apikit.support.HttpTestClient;
import com.mulesoft.examples.rest_api_with_apikit.support.HttpTestClient.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Wire-level test of {@link TeamsController}, the RAML resources {@code /teams} and {@code /teams/{teamId}} of
 * {@code leagues.raml} that the original served through the APIkit flows {@code get:/teams}, {@code post:/teams}
 * and {@code get|put|delete:/teams/{teamId}} of {@code rest-api-with-apikit.xml}, on the application's own
 * Undertow listener with every bean real.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the
 * socket, so status code, reason phrase, {@code Content-Type}, {@code Location} and body bytes are asserted as
 * they reach the wire. Each RAML method gets its declared 2xx and the error mappings its input can select: the
 * APIkit 404, 405, 415, 406 and 400 bodies, compared as exact strings because their spacing is the contract, and
 * the RAML-declared 409 of a duplicate POST.
 *
 * <p>The league is an in-memory singleton that these tests change, so every write goes to a team id the writing
 * method creates itself, the seeded teams of {@code teams.json} are only read, and the context is discarded after
 * the class. The exact {@code reply.json} comparison of the original test is ported in {@code RestApiWithApiKitTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TeamsControllerTest {

    private static final String TEAMS = "/api/teams";

    private static final String NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    private static final String METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    private static final String UNSUPPORTED = "{ \"message\": \"Unsupported media type\" }";

    private static final String NOT_ACCEPTABLE = "{ \"message\": \"Not acceptable\" }";

    private static final String BAD_REQUEST = "{ \"message\": \"Bad request\" }";

    private static final Map<String, String> JSON_CONTENT = Map.of("Content-Type", "application/json");

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    private HttpTestClient client;

    @BeforeEach
    void setUp() {
        client = HttpTestClient.localhost(port);
    }

    // GET /api/teams

    @Test
    void getTeamsReturnsEveryTeamWithNullMatches() {
        Response response = client.get(TEAMS);

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertJsonContentType(response);
        JsonNode teams = json(response).get("teams");
        assertNotNull(teams, response::toString);
        assertTrue(teams.isArray(), response::toString);
        assertFalse(teams.isEmpty(), response::toString);
        Set<String> ids = new HashSet<>();
        for (JsonNode team : teams) {
            JsonNode id = team.get("id");
            assertTrue(id != null && id.isTextual(), () -> "textual id in " + team);
            assertEquals(3, id.textValue().length(), () -> "three-letter id in " + team);
            assertTrue(team.path("name").isTextual(), () -> "textual name in " + team);
            assertTrue(team.path("homeCity").isTextual(), () -> "textual homeCity in " + team);
            assertTrue(team.has("stadium"), () -> "stadium key in " + team);
            assertTrue(team.has("matches"), () -> "matches key in " + team);
            assertTrue(team.get("matches").isNull(), () -> "null matches in " + team);
            ids.add(id.textValue());
        }
        assertTrue(ids.containsAll(Set.of("BAR", "RMA", "ESP")), () -> "seeded teams in " + ids);
        // The collection is rendered without the single-team NON_NULL rule, so null properties stay on the wire.
        assertTrue(response.bodyAsString().contains("\"matches\":null"), response::toString);
    }

    @Test
    void getTeamsFilteredByCity() {
        JsonNode allTeams = json(client.get(TEAMS)).get("teams");
        int barcelonaTeams = 0;
        for (JsonNode team : allTeams) {
            if ("Barcelona".equals(team.path("homeCity").textValue())) {
                barcelonaTeams++;
            }
        }

        Response response = client.get(TEAMS + "?city=Barcelona");

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertJsonContentType(response);
        JsonNode teams = json(response).get("teams");
        assertNotNull(teams, response::toString);
        assertEquals(barcelonaTeams, teams.size(), response::toString);
        Set<String> ids = new HashSet<>();
        for (JsonNode team : teams) {
            assertEquals("Barcelona", team.path("homeCity").textValue(), () -> "homeCity of " + team);
            ids.add(team.path("id").textValue());
        }
        assertTrue(ids.contains("BAR"), () -> "BAR in " + ids);
    }

    @Test
    void getTeamsWithUnacceptableAcceptIsNotAcceptable() {
        Response response = client.get(TEAMS, Map.of("Accept", "text/xml"));

        assertApiKitError(response, 406, "Not Acceptable", NOT_ACCEPTABLE);
    }

    @Test
    void patchTeamsIsMethodNotAllowed() {
        Response response = request("PATCH", TEAMS, JSON_CONTENT, "{}");

        assertApiKitError(response, 405, "Method Not Allowed", METHOD_NOT_ALLOWED);
    }

    // POST /api/teams

    @Test
    void postTeamCreatesTeam() {
        Response response = postJson(TEAMS, "{\"id\":\"TST\",\"name\":\"Test\",\"homeCity\":\"Testville\","
                + "\"stadium\":\"Test Park\"}");

        assertEquals(201, response.status(), response::toString);
        assertEquals("Created", response.reason(), response::toString);
        // The original's http://localhost:${http.port}/api/teams/#[payload.id], with the port actually listened on.
        assertEquals("http://localhost:" + port + "/api/teams/TST", response.header("Location"), response::toString);
        assertEquals(0, response.body().length, response::toString);

        JsonNode team = getExistingTeam("TST");
        assertEquals("TST", team.path("id").textValue(), team::toString);
        assertEquals("Test", team.path("name").textValue(), team::toString);
        assertEquals("Testville", team.path("homeCity").textValue(), team::toString);
        assertEquals("Test Park", team.path("stadium").textValue(), team::toString);
        assertTrue(team.path("matches").isInt(), team::toString);
        assertEquals(0, team.path("matches").intValue(), team::toString);
    }

    @Test
    void postExistingTeamIsConflict() {
        Response response = postJson(TEAMS, "{\"id\":\"BAR\",\"name\":\"Barcelona\",\"homeCity\":\"Barcelona\","
                + "\"stadium\":\"Camp Nou\"}");

        assertApiKitError(response, 409, "Conflict", "{ \"message\": \"There is already a team with id BAR\" }");
    }

    @Test
    void postTeamWithFourCharacterIdIsBadRequest() {
        Response response = postJson(TEAMS, teamJson("TSTX", "Test"));

        assertApiKitError(response, 400, "Bad Request", BAD_REQUEST);
        assertFalse(teamIds().contains("TSTX"));
    }

    @Test
    void postMalformedJsonIsBadRequest() {
        Response response = postJson(TEAMS, "{\"id\":\"TSM\",\"name\":");

        assertApiKitError(response, 400, "Bad Request", BAD_REQUEST);
        assertFalse(teamIds().contains("TSM"));
    }

    @Test
    void postTextPlainIsUnsupportedMediaType() {
        Response response = request("POST", TEAMS, Map.of("Content-Type", "text/plain"),
                "{\"id\":\"TSP\",\"name\":\"P\",\"homeCity\":\"Testville\",\"stadium\":\"Test Park\"}");

        assertApiKitError(response, 415, "Unsupported Media Type", UNSUPPORTED);
        assertFalse(teamIds().contains("TSP"));
    }

    // GET /api/teams/{teamId}

    @Test
    void getTeamReturnsTeam() {
        Response response = client.get(TEAMS + "/BAR");

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertJsonContentType(response);
        JsonNode team = json(response);
        assertEquals("BAR", team.path("id").textValue(), response::toString);
        assertEquals("Barcelona", team.path("name").textValue(), response::toString);
        assertEquals("Barcelona", team.path("homeCity").textValue(), response::toString);
        assertEquals("Camp Nou", team.path("stadium").textValue(), response::toString);
        assertTrue(team.has("matches"), response::toString);
        assertTrue(team.get("matches").isInt(), response::toString);
        // A single team is rendered with the original codehaus NON_NULL rule: no null property reaches the wire.
        assertFalse(response.bodyAsString().contains("null"), response::toString);
    }

    @Test
    void getUnknownTeamIsNotFound() {
        assertApiKitError(client.get(TEAMS + "/ZZZ"), 404, "Not Found", NOT_FOUND);
    }

    @Test
    void getTeamWithFourCharacterIdIsBadRequest() {
        assertApiKitError(client.get(TEAMS + "/BARC"), 400, "Bad Request", BAD_REQUEST);
    }

    // PUT /api/teams/{teamId}

    @Test
    void putTeamUpdatesOnlyGivenFields() {
        createTeam("TSU", "Test Update");

        Response response = putJson(TEAMS + "/TSU", "{\"stadium\":\"New Park\"}");

        assertEquals(204, response.status(), response::toString);
        assertEquals("No Content", response.reason(), response::toString);
        assertEquals(0, response.body().length, response::toString);
        JsonNode team = getExistingTeam("TSU");
        assertEquals("New Park", team.path("stadium").textValue(), team::toString);
        assertEquals("Test Update", team.path("name").textValue(), team::toString);
        assertEquals("Testville", team.path("homeCity").textValue(), team::toString);
    }

    @Test
    void putUnknownTeamIsNotFound() {
        Response response = putJson(TEAMS + "/ZZZ", "{\"stadium\":\"New Park\"}");

        assertApiKitError(response, 404, "Not Found", NOT_FOUND);
    }

    @Test
    void putTeamWithInvalidBodyIsBadRequest() {
        createTeam("TSV", "Test Invalid");

        Response response = putJson(TEAMS + "/TSV", "{\"stadium\":5}");

        assertApiKitError(response, 400, "Bad Request", BAD_REQUEST);
        JsonNode team = getExistingTeam("TSV");
        assertEquals("Test Park", team.path("stadium").textValue(), team::toString);
    }

    // DELETE /api/teams/{teamId}

    @Test
    void deleteTeamRemovesTeam() {
        createTeam("TSD", "Test Delete");

        Response response = request("DELETE", TEAMS + "/TSD", null, null);

        assertEquals(204, response.status(), response::toString);
        assertEquals("No Content", response.reason(), response::toString);
        assertEquals(0, response.body().length, response::toString);
        assertApiKitError(client.get(TEAMS + "/TSD"), 404, "Not Found", NOT_FOUND);
    }

    @Test
    void deleteUnknownTeamIsNotFound() {
        assertApiKitError(request("DELETE", TEAMS + "/ZZZ", null, null), 404, "Not Found", NOT_FOUND);
    }

    @Test
    void deleteTeamWithFourCharacterIdIsBadRequest() {
        assertApiKitError(request("DELETE", TEAMS + "/BARC", null, null), 400, "Bad Request", BAD_REQUEST);

        Response bar = client.get(TEAMS + "/BAR");
        assertEquals(200, bar.status(), bar::toString);
    }

    // Helpers

    private Response request(String method, String path, Map<String, String> headers, String jsonOrNull) {
        byte[] body = jsonOrNull == null ? null : jsonOrNull.getBytes(StandardCharsets.UTF_8);
        return client.send(method, path, headers, body);
    }

    private Response postJson(String path, String json) {
        return request("POST", path, JSON_CONTENT, json);
    }

    private Response putJson(String path, String json) {
        return request("PUT", path, JSON_CONTENT, json);
    }

    private static void assertApiKitError(Response response, int status, String reason, String body) {
        assertEquals(status, response.status(), response::toString);
        assertEquals(reason, response.reason(), response::toString);
        assertJsonContentType(response);
        assertEquals(body, response.bodyAsString(), response::toString);
    }

    private static void assertJsonContentType(Response response) {
        String contentType = response.header("Content-Type");
        assertNotNull(contentType, response::toString);
        assertTrue(MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType)),
                response::toString);
    }

    private void createTeam(String id, String name) {
        Response response = postJson(TEAMS, teamJson(id, name));
        assertEquals(201, response.status(), response::toString);
    }

    private static String teamJson(String id, String name) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"homeCity\":\"Testville\","
                + "\"stadium\":\"Test Park\"}";
    }

    /** GET {@code /api/teams/<id>}, asserting 200, and the team as a JSON tree. */
    private JsonNode getExistingTeam(String id) {
        Response response = client.get(TEAMS + "/" + id);
        assertEquals(200, response.status(), response::toString);
        return json(response);
    }

    /** The ids of every team GET {@code /api/teams} returns. */
    private Set<String> teamIds() {
        Response response = client.get(TEAMS);
        assertEquals(200, response.status(), response::toString);
        Set<String> ids = new HashSet<>();
        for (JsonNode team : json(response).path("teams")) {
            ids.add(team.path("id").textValue());
        }
        return ids;
    }

    private static JsonNode json(Response response) {
        try {
            return JSON.readTree(response.bodyAsString());
        } catch (JsonProcessingException e) {
            return fail("Response body is not JSON: " + response, e);
        }
    }
}
