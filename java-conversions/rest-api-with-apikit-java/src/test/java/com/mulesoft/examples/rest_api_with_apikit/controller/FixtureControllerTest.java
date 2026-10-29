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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.mulesoft.examples.rest_api_with_apikit.support.HttpTestClient;
import com.mulesoft.examples.rest_api_with_apikit.support.HttpTestClient.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Wire-level test of {@link FixtureController}, the RAML resources {@code /fixture} and
 * {@code /fixture/{homeTeamId}/{awayTeamId}} of {@code leagues.raml} that the original served through the APIkit
 * flows {@code get:/fixture}, {@code get:/fixture/{homeTeamId}/{awayTeamId}} and
 * {@code put:/fixture/{homeTeamId}/{awayTeamId}} of {@code rest-api-with-apikit.xml}, on the application's own
 * Undertow listener with every bean real.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the
 * socket, so status code, reason phrase, {@code Content-Type} and body bytes are asserted as they reach the wire.
 * Each RAML method gets its declared 2xx and the error answers its input can select: the APIkit 404, 405, 415, 406
 * and 400 bodies, compared as whole strings because their spacing is the contract, and the RAML-declared 409 for
 * a score sent before the match is played.
 *
 * <p>The two renderings of a match are both asserted: the collection keeps null scores and writes the date as
 * epoch milliseconds, the single match omits null scores and writes the date as {@value #SINGLE_MATCH_DATE_PATTERN}.
 *
 * <p>The fixture is built at start-up with random first-round scores and dates relative to that moment, so every
 * method picks its match at run time and derives its expectations from the fixture it reads; nothing depends on
 * fixed scores, dates or on another method's writes. Scores are written only to matches without
 * {@value #PROTECTED_TEAM}, whose team rendering {@code RestApiWithApiKitTest} compares with {@code reply.json},
 * and the context is discarded after the class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FixtureControllerTest {

    private static final String FIXTURE = "/api/fixture";

    private static final String NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    private static final String METHOD_NOT_ALLOWED = "{ \"message\": \"Method not allowed\" }";

    private static final String UNSUPPORTED_MEDIA_TYPE = "{ \"message\": \"Unsupported media type\" }";

    private static final String NOT_ACCEPTABLE = "{ \"message\": \"Not acceptable\" }";

    private static final String BAD_REQUEST = "{ \"message\": \"Bad request\" }";

    /** A valid PUT body; both values lie in the 0..4 range of the generated scores. */
    private static final String SCORE_3_0 = "{\"homeTeamScore\":3,\"awayTeamScore\":0}";

    /** The team whose matches are never written. */
    private static final String PROTECTED_TEAM = "BAR";

    /** The format of the single-match {@code date}, that of the ported {@code JsonDateSerializer}. */
    private static final String SINGLE_MATCH_DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ssZ";

    private static final Pattern SINGLE_MATCH_DATE =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{4}");

    private static final List<String> SCORE_FIELDS = List.of("homeTeamScore", "awayTeamScore");

    /** The generated scores are {@code Random.nextInt(5)}. */
    private static final int MAX_SCORE = 4;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @LocalServerPort
    private int port;

    private HttpTestClient client;

    @BeforeEach
    void setUp() {
        client = HttpTestClient.localhost(port);
    }

    // GET /api/fixture

    @Test
    void getFixtureReturnsEveryMatch() {
        Response response = client.get(FIXTURE);
        long now = System.currentTimeMillis();

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertJsonContentType(response);
        JsonNode fixture = json(response).get("fixture");
        assertNotNull(fixture, response::toString);
        assertTrue(fixture.isArray(), response::toString);
        assertFalse(fixture.isEmpty(), response::toString);

        Set<String> teams = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        boolean playedWithScores = false;
        boolean futureMatch = false;
        for (JsonNode match : fixture) {
            JsonNode home = match.get("homeTeam");
            JsonNode away = match.get("awayTeam");
            assertTrue(home != null && home.isTextual(), () -> "textual homeTeam in " + match);
            assertTrue(away != null && away.isTextual(), () -> "textual awayTeam in " + match);
            assertEquals(3, home.textValue().length(), () -> "three-letter homeTeam in " + match);
            assertEquals(3, away.textValue().length(), () -> "three-letter awayTeam in " + match);
            assertNotEquals(home.textValue(), away.textValue(), () -> "distinct teams in " + match);
            assertTrue(pairs.add(home.textValue() + ":" + away.textValue()), () -> "single entry for " + match);
            teams.add(home.textValue());
            teams.add(away.textValue());

            JsonNode date = match.get("date");
            assertTrue(date != null && date.isIntegralNumber(), () -> "epoch-millisecond date in " + match);
            for (String field : SCORE_FIELDS) {
                assertTrue(match.has(field), () -> field + " key in " + match);
                JsonNode score = match.get(field);
                if (!score.isNull()) {
                    assertTrue(score.isInt() && score.intValue() >= 0 && score.intValue() <= MAX_SCORE,
                            () -> field + " between 0 and " + MAX_SCORE + " in " + match);
                }
            }

            boolean scored = !match.get("homeTeamScore").isNull() && !match.get("awayTeamScore").isNull();
            if (date.longValue() > now) {
                futureMatch = true;
                assertTrue(match.get("homeTeamScore").isNull(), () -> "null homeTeamScore in future " + match);
                assertTrue(match.get("awayTeamScore").isNull(), () -> "null awayTeamScore in future " + match);
            } else if (scored) {
                playedWithScores = true;
            }
        }
        // One match for every ordered pair of distinct teams.
        assertEquals(teams.size() * (teams.size() - 1), fixture.size(), () -> "matches for teams " + teams);
        assertTrue(playedWithScores, "at least one played match with scores");
        assertTrue(futureMatch, "at least one match still to be played");
        // The collection is rendered without the single-match NON_NULL rule, so null scores stay on the wire.
        assertTrue(response.bodyAsString().contains("\"homeTeamScore\":null"), response::toString);
    }

    @Test
    void getFixtureWithUnacceptableAcceptIsNotAcceptable() {
        Response response = client.get(FIXTURE, Map.of("Accept", "text/xml"));

        assertApiKitError(response, 406, "Not Acceptable", NOT_ACCEPTABLE);
    }

    @Test
    void deleteFixtureIsMethodNotAllowed() {
        assertApiKitError(client.delete(FIXTURE), 405, "Method Not Allowed", METHOD_NOT_ALLOWED);
    }

    // GET /api/fixture/{homeTeamId}/{awayTeamId}

    @Test
    void getMatchReturnsSingleMatchRendering() throws ParseException {
        JsonNode listed = findMatch(fixture(), "BAR", "RMA");

        Response response = client.get(matchPath("BAR", "RMA"));

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertJsonContentType(response);
        JsonNode match = json(response);
        assertEquals("BAR", match.path("homeTeam").textValue(), response::toString);
        assertEquals("RMA", match.path("awayTeam").textValue(), response::toString);
        JsonNode date = match.get("date");
        assertTrue(date != null && date.isTextual(), response::toString);
        assertTrue(SINGLE_MATCH_DATE.matcher(date.textValue()).matches(), response::toString);
        // Match dates carry no milliseconds, so the second-precision string names the same instant.
        assertEquals(listed.get("date").longValue(),
                new SimpleDateFormat(SINGLE_MATCH_DATE_PATTERN).parse(date.textValue()).getTime(),
                response::toString);
        assertFalse(response.bodyAsString().contains("null"), response::toString);
        assertScoresAsListed(listed, match);
    }

    @Test
    void getSelfMatchIsNotFound() {
        assertApiKitError(client.get(matchPath("BAR", "BAR")), 404, "Not Found", NOT_FOUND);
    }

    @Test
    void getMatchWithFourCharacterIdIsBadRequest() {
        assertApiKitError(client.get(matchPath("BARC", "RMA")), 400, "Bad Request", BAD_REQUEST);
        assertApiKitError(client.get(matchPath("BAR", "RMAX")), 400, "Bad Request", BAD_REQUEST);
    }

    // PUT /api/fixture/{homeTeamId}/{awayTeamId}

    @Test
    void putScoreOnPlayedMatchUpdatesScore() {
        JsonNode played = pickMatch(true);
        String home = played.get("homeTeam").textValue();
        String away = played.get("awayTeam").textValue();

        Response response = putScore(home, away, SCORE_3_0);

        assertEquals(204, response.status(), response::toString);
        assertEquals("No Content", response.reason(), response::toString);
        assertEquals(0, response.body().length, response::toString);
        JsonNode updated = getExistingMatch(home, away);
        assertTrue(updated.path("homeTeamScore").isInt(), updated::toString);
        assertTrue(updated.path("awayTeamScore").isInt(), updated::toString);
        assertEquals(3, updated.get("homeTeamScore").intValue(), updated::toString);
        assertEquals(0, updated.get("awayTeamScore").intValue(), updated::toString);
    }

    @Test
    void putScoreOnFutureMatchIsConflict() {
        JsonNode future = pickMatch(false);
        String home = future.get("homeTeam").textValue();
        String away = future.get("awayTeam").textValue();

        Response response = putScore(home, away, SCORE_3_0);

        assertEquals(409, response.status(), response::toString);
        assertEquals("Conflict", response.reason(), response::toString);
        assertJsonContentType(response);
        assertEquals("{ \"message\": \"The match between team " + home + " and team " + away
                + " has not been played yet\" }", response.bodyAsString(), response::toString);
        JsonNode unchanged = getExistingMatch(home, away);
        assertFalse(unchanged.has("homeTeamScore"), unchanged::toString);
        assertFalse(unchanged.has("awayTeamScore"), unchanged::toString);
    }

    @Test
    void putNonIntegerScoreIsBadRequest() {
        JsonNode played = pickMatch(true);
        String home = played.get("homeTeam").textValue();
        String away = played.get("awayTeam").textValue();

        Response response = putScore(home, away, "{\"homeTeamScore\":\"three\",\"awayTeamScore\":0}");

        assertApiKitError(response, 400, "Bad Request", BAD_REQUEST);
        assertScoresAsListed(played, getExistingMatch(home, away));
    }

    @Test
    void putScoreOnUnknownMatchIsNotFound() {
        assertApiKitError(putScore("BAR", "BAR", SCORE_3_0), 404, "Not Found", NOT_FOUND);
    }

    @Test
    void putScoreAsTextPlainIsUnsupportedMediaType() {
        JsonNode played = pickMatch(true);
        String home = played.get("homeTeam").textValue();
        String away = played.get("awayTeam").textValue();

        Response response = client.put(matchPath(home, away), MediaType.TEXT_PLAIN_VALUE, SCORE_3_0);

        assertApiKitError(response, 415, "Unsupported Media Type", UNSUPPORTED_MEDIA_TYPE);
        assertScoresAsListed(played, getExistingMatch(home, away));
    }

    // Helpers

    private static String matchPath(String homeTeamId, String awayTeamId) {
        return FIXTURE + "/" + homeTeamId + "/" + awayTeamId;
    }

    private Response putScore(String homeTeamId, String awayTeamId, String json) {
        return client.put(matchPath(homeTeamId, awayTeamId), MediaType.APPLICATION_JSON_VALUE, json);
    }

    /** GET {@code /api/fixture}, asserting 200, and its {@code fixture} array. */
    private ArrayNode fixture() {
        Response response = client.get(FIXTURE);
        assertEquals(200, response.status(), response::toString);
        JsonNode fixture = json(response).get("fixture");
        assertTrue(fixture instanceof ArrayNode, response::toString);
        return (ArrayNode) fixture;
    }

    /**
     * The first match of the fixture dated before ({@code past}) or after now in which {@value #PROTECTED_TEAM}
     * does not play; fails when there is none.
     */
    private JsonNode pickMatch(boolean past) {
        ArrayNode fixture = fixture();
        long now = System.currentTimeMillis();
        for (JsonNode match : fixture) {
            if (PROTECTED_TEAM.equals(match.path("homeTeam").textValue())
                    || PROTECTED_TEAM.equals(match.path("awayTeam").textValue())) {
                continue;
            }
            long date = match.path("date").longValue();
            if (past ? date < now : date > now) {
                return match;
            }
        }
        return fail("No " + (past ? "played" : "future") + " match without " + PROTECTED_TEAM + " in " + fixture);
    }

    private static JsonNode findMatch(ArrayNode fixture, String homeTeamId, String awayTeamId) {
        for (JsonNode match : fixture) {
            if (homeTeamId.equals(match.path("homeTeam").textValue())
                    && awayTeamId.equals(match.path("awayTeam").textValue())) {
                return match;
            }
        }
        return fail("No match " + homeTeamId + " - " + awayTeamId + " in " + fixture);
    }

    /** GET {@code /api/fixture/<h>/<a>}, asserting 200, and the match as a JSON tree. */
    private JsonNode getExistingMatch(String homeTeamId, String awayTeamId) {
        Response response = client.get(matchPath(homeTeamId, awayTeamId));
        assertEquals(200, response.status(), response::toString);
        return json(response);
    }

    /**
     * The single-match rendering carries each score the collection lists, and omits each score the collection
     * lists as {@code null}.
     */
    private static void assertScoresAsListed(JsonNode listed, JsonNode single) {
        for (String field : SCORE_FIELDS) {
            JsonNode listedScore = listed.get(field);
            if (listedScore.isNull()) {
                assertFalse(single.has(field), () -> field + " omitted in " + single + ", listed " + listed);
            } else {
                assertTrue(single.path(field).isInt(), () -> field + " in " + single + ", listed " + listed);
                assertEquals(listedScore.intValue(), single.get(field).intValue(),
                        () -> field + " in " + single + ", listed " + listed);
            }
        }
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

    private JsonNode json(Response response) {
        try {
            return objectMapper.readTree(response.bodyAsString());
        } catch (JsonProcessingException e) {
            return fail("Response body is not JSON: " + response, e);
        }
    }
}
