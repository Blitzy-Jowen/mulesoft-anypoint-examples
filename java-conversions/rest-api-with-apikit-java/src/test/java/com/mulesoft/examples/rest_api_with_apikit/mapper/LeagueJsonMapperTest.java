/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.json.JSONException;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.mulesoft.examples.rest_api_with_apikit.model.request.Score;
import com.mulesoft.examples.rest_api_with_apikit.model.request.UpdateTeam;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Fixture;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Match;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Position;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Positions;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Team;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Teams;

/**
 * Contract of {@link LeagueJsonMapper}: the two renderings the original application produced, the three
 * request readers, and the {@link UncheckedIOException} both its write and read paths raise.
 *
 * <ul>
 *   <li>Single resources ({@code Team}, {@code Match}) honour the beans' annotations: null properties are
 *       omitted and {@code Match.date} is a string in {@link JsonDateSerializer}'s pattern.</li>
 *   <li>Collections ({@code Teams}, {@code Positions}, {@code Fixture}) ignore those annotations: null
 *       properties are written as {@code null} and dates as epoch milliseconds.</li>
 * </ul>
 *
 * <p>Renderings are compared with JSONAssert in {@link JSONCompareMode#STRICT} mode, so a property that is
 * written but not expected (a null the single rendering must omit) or expected but missing (a null the
 * collection rendering must keep) fails the comparison, as does any difference in array order. The single
 * rendering's date string comes from a fresh {@link SimpleDateFormat} over the pattern spelled out here, so
 * the expectation holds in whatever default zone the build runs. No Spring context is started.
 */
class LeagueJsonMapperTest {

    /** The pattern of {@link JsonDateSerializer}; spelled out here so a change to it is caught. */
    private static final String DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ssZ";

    /** 2014-05-01T18:00:00Z, written as {@code 1398967200000} by the collection rendering. */
    private static final long KICK_OFF_MILLIS = 1_398_967_200_000L;

    /** 2014-05-08T20:30:00Z, written as {@code 1399581000000} by the collection rendering. */
    private static final long RETURN_LEG_MILLIS = 1_399_581_000_000L;

    private final LeagueJsonMapper mapper = new LeagueJsonMapper();

    /** A single team whose name getter fails, so the annotation-honouring writer reaches its catch block. */
    static final class UnreadableTeam extends Team {
        @Override
        public String getName() {
            throw new IllegalStateException("team name unavailable");
        }
    }

    /** A single match whose home-team getter fails, through the annotation-honouring writer. */
    static final class UnreadableMatch extends Match {
        @Override
        public String getHomeTeam() {
            throw new IllegalStateException("home team unavailable");
        }
    }

    /** A team list whose getter fails, so the annotation-ignoring writer reaches its catch block. */
    static final class UnreadableTeams extends Teams {
        @Override
        public List<Team> getTeams() {
            throw new IllegalStateException("team list unavailable");
        }
    }

    /** A league table whose getter fails, through the annotation-ignoring writer. */
    static final class UnreadablePositions extends Positions {
        @Override
        public List<Position> getPositions() {
            throw new IllegalStateException("league table unavailable");
        }
    }

    /** A fixture whose getter fails, through the annotation-ignoring writer. */
    static final class UnreadableFixture extends Fixture {
        @Override
        public List<Match> getFixture() {
            throw new IllegalStateException("fixture unavailable");
        }
    }

    static Stream<Arguments> renderings() {
        return Stream.of(
                rendering("single team with every property",
                        m -> m.toJson(barcelona()),
                        "{\"id\":\"BAR\",\"name\":\"Barcelona\",\"homeCity\":\"Barcelona\","
                                + "\"stadium\":\"Camp Nou\",\"matches\":19}"),
                rendering("single team omits its null stadium and matches",
                        m -> m.toJson(realMadridWithoutOptionals()),
                        "{\"id\":\"RMA\",\"name\":\"Real Madrid\",\"homeCity\":\"Madrid\"}"),
                rendering("single played match writes its date as a pattern string",
                        m -> m.toJson(playedMatch()),
                        "{\"homeTeam\":\"BAR\",\"awayTeam\":\"RMA\",\"date\":\"" + formatted(KICK_OFF_MILLIS) + "\","
                                + "\"homeTeamScore\":2,\"awayTeamScore\":1}"),
                rendering("single unplayed match omits its null scores",
                        m -> m.toJson(unplayedMatch()),
                        "{\"homeTeam\":\"RMA\",\"awayTeam\":\"BAR\",\"date\":\"" + formatted(RETURN_LEG_MILLIS) + "\"}"),
                rendering("team list writes null properties",
                        m -> m.toJson(teams(barcelona(), realMadridWithoutOptionals())),
                        "{\"teams\":["
                                + "{\"id\":\"BAR\",\"name\":\"Barcelona\",\"homeCity\":\"Barcelona\","
                                + "\"stadium\":\"Camp Nou\",\"matches\":19},"
                                + "{\"id\":\"RMA\",\"name\":\"Real Madrid\",\"homeCity\":\"Madrid\","
                                + "\"stadium\":null,\"matches\":null}]}"),
                rendering("empty team list",
                        m -> m.toJson(teams()),
                        "{\"teams\":[]}"),
                rendering("team list without a list writes null",
                        m -> m.toJson(new Teams()),
                        "{\"teams\":null}"),
                rendering("league table writes every property",
                        m -> m.toJson(positions(leader(), runnerUp())),
                        "{\"positions\":["
                                + "{\"position\":1,\"team\":\"Barcelona\",\"points\":45,\"matchesPlayed\":19,"
                                + "\"matchesWon\":14,\"matchesDraw\":3,\"matchesLost\":2,\"goalsInFavor\":48,"
                                + "\"goalsAgainst\":15},"
                                + "{\"position\":2,\"team\":\"Real Madrid\",\"points\":0,\"matchesPlayed\":0,"
                                + "\"matchesWon\":0,\"matchesDraw\":0,\"matchesLost\":0,\"goalsInFavor\":0,"
                                + "\"goalsAgainst\":0}]}"),
                rendering("fixture writes epoch-millisecond dates and null scores",
                        m -> m.toJson(fixture(playedMatch(), unplayedMatch())),
                        "{\"fixture\":["
                                + "{\"homeTeam\":\"BAR\",\"awayTeam\":\"RMA\",\"date\":1398967200000,"
                                + "\"homeTeamScore\":2,\"awayTeamScore\":1},"
                                + "{\"homeTeam\":\"RMA\",\"awayTeam\":\"BAR\",\"date\":1399581000000,"
                                + "\"homeTeamScore\":null,\"awayTeamScore\":null}]}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("renderings")
    void rendersExactJson(Function<LeagueJsonMapper, String> render, String expectedJson) throws JSONException {
        JSONAssert.assertEquals(expectedJson, render.apply(mapper), JSONCompareMode.STRICT);
    }

    @Test
    void readsTeamBody() {
        com.mulesoft.examples.rest_api_with_apikit.model.request.Team team = mapper.readTeam(
                "{\"name\":\"Barcelona\",\"id\":\"BAR\",\"homeCity\":\"Barcelona\",\"stadium\":\"Camp Nou\"}");

        assertEquals("BAR", team.getId());
        assertEquals("Barcelona", team.getName());
        assertEquals("Barcelona", team.getHomeCity());
        assertEquals("Camp Nou", team.getStadium());
    }

    @Test
    void readsUpdateTeamBodyLeavingAbsentFieldsNull() {
        UpdateTeam update = mapper.readUpdateTeam("{\"name\":\"Barcelona\",\"stadium\":\"Camp Nou\"}");

        assertEquals("Barcelona", update.getName());
        assertNull(update.getHomeCity(), "an absent homeCity stays null");
        assertEquals("Camp Nou", update.getStadium());
    }

    @Test
    void readsScoreBody() {
        Score score = mapper.readScore("{\"homeTeamScore\":3,\"awayTeamScore\":0}");

        assertEquals(3, score.getHomeTeamScore());
        assertEquals(0, score.getAwayTeamScore());
    }

    static Stream<Arguments> rejectedBodies() {
        return Stream.of(
                rejected("team body with an unknown property",
                        m -> m.readTeam("{\"id\":\"BAR\",\"name\":\"Barcelona\",\"homeCity\":\"Barcelona\","
                                + "\"founded\":1899}"),
                        UnrecognizedPropertyException.class,
                        "Unrecognized field \"founded\" (class "
                                + "com.mulesoft.examples.rest_api_with_apikit.model.request.Team), "
                                + "not marked as ignorable"),
                rejected("malformed team body",
                        m -> m.readTeam("{id:\"BAR\"}"),
                        JsonParseException.class,
                        "Unexpected character ('i' (code 105)): was expecting double-quote to start field name"),
                rejected("update body with an unknown property",
                        m -> m.readUpdateTeam("{\"name\":\"Barcelona\",\"id\":\"BAR\"}"),
                        UnrecognizedPropertyException.class,
                        "Unrecognized field \"id\" (class "
                                + "com.mulesoft.examples.rest_api_with_apikit.model.request.UpdateTeam), "
                                + "not marked as ignorable"),
                rejected("malformed update body",
                        m -> m.readUpdateTeam("{\"name\":'Barcelona'}"),
                        JsonParseException.class,
                        "Unexpected character (''' (code 39)): expected a valid value"),
                rejected("score body with an unknown property",
                        m -> m.readScore("{\"homeTeamScore\":2,\"awayTeamScore\":1,\"penalties\":3}"),
                        UnrecognizedPropertyException.class,
                        "Unrecognized field \"penalties\" (class "
                                + "com.mulesoft.examples.rest_api_with_apikit.model.request.Score), "
                                + "not marked as ignorable"),
                rejected("malformed score body",
                        m -> m.readScore("{\"homeTeamScore\":2,,\"awayTeamScore\":1}"),
                        JsonParseException.class,
                        "Unexpected character (',' (code 44)): was expecting double-quote to start field name"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedBodies")
    void rejectsBodyWithUncheckedIOExceptionCarryingJacksonsOriginalMessage(
            Function<LeagueJsonMapper, Object> read,
            Class<? extends JsonProcessingException> expectedCause,
            String expectedMessagePrefix) {
        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> read.apply(mapper));

        JsonProcessingException cause = assertInstanceOf(expectedCause, thrown.getCause());
        assertEquals(cause.getOriginalMessage(), thrown.getMessage());
        assertTrue(thrown.getMessage().startsWith(expectedMessagePrefix),
                () -> "message starts with [" + expectedMessagePrefix + "], was [" + thrown.getMessage() + "]");
        assertFalse(thrown.getMessage().contains("[Source:"),
                () -> "message carries no source location, was [" + thrown.getMessage() + "]");
    }

    static Stream<Arguments> failingGetters() {
        return Stream.of(
                failing("single team", m -> m.toJson(new UnreadableTeam()), "team name unavailable"),
                failing("single match", m -> m.toJson(new UnreadableMatch()), "home team unavailable"),
                failing("team list", m -> m.toJson(new UnreadableTeams()), "team list unavailable"),
                failing("league table", m -> m.toJson(new UnreadablePositions()), "league table unavailable"),
                failing("fixture", m -> m.toJson(new UnreadableFixture()), "fixture unavailable"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failingGetters")
    void wrapsGetterFailureInUncheckedIOExceptionCarryingItsMessage(
            Function<LeagueJsonMapper, String> render, String getterMessage) {
        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> render.apply(mapper));

        JsonMappingException cause = assertInstanceOf(JsonMappingException.class, thrown.getCause());
        assertEquals(getterMessage, thrown.getMessage(), "the message has no reference-chain suffix");
        assertEquals(getterMessage, cause.getOriginalMessage());
        IllegalStateException getterFailure = assertInstanceOf(IllegalStateException.class, cause.getCause());
        assertEquals(getterMessage, getterFailure.getMessage());
    }

    private static Arguments rendering(String name, Function<LeagueJsonMapper, String> render, String expectedJson) {
        return Arguments.of(Named.of(name, render), expectedJson);
    }

    private static Arguments rejected(String name, Function<LeagueJsonMapper, Object> read,
            Class<? extends JsonProcessingException> expectedCause, String expectedMessagePrefix) {
        return Arguments.of(Named.of(name, read), expectedCause, expectedMessagePrefix);
    }

    private static Arguments failing(String name, Function<LeagueJsonMapper, String> render, String getterMessage) {
        return Arguments.of(Named.of(name, render), getterMessage);
    }

    /** The single rendering's date text: {@link #DATE_PATTERN} in the JVM default zone and format locale. */
    private static String formatted(long millis) {
        return new SimpleDateFormat(DATE_PATTERN).format(new Date(millis));
    }

    private static Team barcelona() {
        Team team = new Team();
        team.setId("BAR");
        team.setName("Barcelona");
        team.setHomeCity("Barcelona");
        team.setStadium("Camp Nou");
        team.setMatches(19);
        return team;
    }

    /** A team whose optional {@code stadium} and {@code matches} were never set. */
    private static Team realMadridWithoutOptionals() {
        Team team = new Team();
        team.setId("RMA");
        team.setName("Real Madrid");
        team.setHomeCity("Madrid");
        return team;
    }

    private static Match playedMatch() {
        Match match = new Match();
        match.setHomeTeam("BAR");
        match.setAwayTeam("RMA");
        match.setDate(new Date(KICK_OFF_MILLIS));
        match.setHomeTeamScore(2);
        match.setAwayTeamScore(1);
        return match;
    }

    /** A match not yet played: its scores are null, as {@code LeagueAPI} leaves them. */
    private static Match unplayedMatch() {
        Match match = new Match();
        match.setHomeTeam("RMA");
        match.setAwayTeam("BAR");
        match.setDate(new Date(RETURN_LEG_MILLIS));
        match.setHomeTeamScore(null);
        match.setAwayTeamScore(null);
        return match;
    }

    private static Position leader() {
        Position position = new Position();
        position.setPosition(1);
        position.setTeam("Barcelona");
        position.setPoints(45);
        position.setMatchesPlayed(19);
        position.setMatchesWon(14);
        position.setMatchesDraw(3);
        position.setMatchesLost(2);
        position.setGoalsInFavor(48);
        position.setGoalsAgainst(15);
        return position;
    }

    /** A row whose counters were never set, so every one is written as {@code 0}. */
    private static Position runnerUp() {
        Position position = new Position();
        position.setPosition(2);
        position.setTeam("Real Madrid");
        return position;
    }

    private static Teams teams(Team... teams) {
        Teams list = new Teams();
        list.setTeams(List.of(teams));
        return list;
    }

    private static Positions positions(Position... rows) {
        Positions table = new Positions();
        table.setPositions(List.of(rows));
        return table;
    }

    private static Fixture fixture(Match... matches) {
        Fixture fixture = new Fixture();
        fixture.setFixture(List.of(matches));
        return fixture;
    }
}
