/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.mulesoft.examples.rest_api_with_apikit.exception.ApiKitNotFoundException;
import com.mulesoft.examples.rest_api_with_apikit.exception.ConflictException;
import com.mulesoft.examples.rest_api_with_apikit.model.request.Score;
import com.mulesoft.examples.rest_api_with_apikit.model.request.UpdateTeam;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Match;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Position;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Team;

/**
 * Tests the ported {@link LeagueAPI} over the league it loads from {@code teams.json}: every operation, both
 * {@code ApiKitNotFoundException} messages and both {@code ConflictException} messages.
 *
 * <p>The league draws its first-round scores at random and keeps teams and matches in {@code HashMap}s, so no
 * assertion names a score or relies on iteration order. What the seed fixes is asserted exactly: 20 teams, a
 * 380-match double round robin in which each pairing has one played first-round leg and one unplayed
 * second-round leg (half dated yesterday, half tomorrow), and 19 matches played by every team. Standings are
 * checked against a tally of the fixture the same instance returns. Matches are picked by property (played,
 * due, not yet due) rather than by name, and messages naming a picked match are built from its ids.
 *
 * <p>Each test gets a fresh {@code new LeagueAPI()} initialized by hand, as Spring's {@code @PostConstruct}
 * would; no Spring context is started.
 */
class LeagueAPITest {

    private static final List<String> SEEDED_IDS = List.of(
            "ATH", "ATL", "BAR", "BET", "ESP", "GET", "GRA", "LEV", "MAG", "MAL",
            "OSA", "RAC", "RAY", "RMA", "RSC", "SEV", "SPG", "VAL", "VIL", "ZAR");

    private static final int SEEDED_TEAMS = SEEDED_IDS.size();

    /** One first-round match against each opponent, as {@code reply.json}'s {@code "matches": 19} records. */
    private static final int MATCHES_PER_TEAM = SEEDED_TEAMS - 1;

    /** Every ordered pair of distinct teams meets once. */
    private static final int FIXTURE_SIZE = SEEDED_TEAMS * (SEEDED_TEAMS - 1);

    private static final int GAMES_PER_ROUND = FIXTURE_SIZE / 2;

    /** The ranking {@code League.orderTeamsByPosition} applies: points, then goal difference, then goals scored. */
    private static final Comparator<Position> RANKING = Comparator.comparingInt(Position::getPoints)
            .thenComparingInt(position -> position.getGoalsInFavor() - position.getGoalsAgainst())
            .thenComparingInt(Position::getGoalsInFavor)
            .reversed();

    private LeagueAPI api;

    @BeforeEach
    void setUp() {
        api = new LeagueAPI();
        api.initialize();
    }

    @Test
    void getTeamReturnsTheSeededTeamWithItsMatchesPlayed() {
        Team team = api.getTeam("BAR");

        assertThat(team)
                .extracting(Team::getId, Team::getName, Team::getHomeCity, Team::getStadium, Team::getMatches)
                .containsExactly("BAR", "Barcelona", "Barcelona", "Camp Nou", MATCHES_PER_TEAM);
    }

    @Test
    void everySeededTeamHasPlayedOneMatchAgainstEachOpponent() {
        assertThat(SEEDED_IDS).allSatisfy(id ->
                assertThat(api.getTeam(id).getMatches()).as("matches of %s", id).isEqualTo(MATCHES_PER_TEAM));
    }

    @Test
    void getTeamsWithoutCityReturnsEverySeededTeamWithoutMatchCount() {
        List<Team> teams = api.getTeams(null).getTeams();

        assertThat(teams).extracting(Team::getId).containsExactlyInAnyOrderElementsOf(SEEDED_IDS);
        assertThat(teams).extracting(Team::getMatches).containsOnlyNulls();
    }

    @Test
    void getTeamsMapsEveryFieldOfTheTeamsItReturns() {
        assertThat(api.getTeams("Madrid").getTeams())
                .extracting(Team::getId, Team::getName, Team::getHomeCity, Team::getStadium, Team::getMatches)
                .containsExactlyInAnyOrder(
                        tuple("ATL", "Atletico Madrid", "Madrid", "Vicente Calderon", null),
                        tuple("RAY", "Rayo Vallecano", "Madrid", "Campo de Vallecas", null),
                        tuple("RMA", "Real Madrid", "Madrid", "Santiago Bernabeu", null));
    }

    static Stream<Arguments> cityFilters() {
        return Stream.of(
                Arguments.of("Madrid", List.of("ATL", "RAY", "RMA")),
                Arguments.of("Barcelona", List.of("BAR", "ESP")),
                Arguments.of("Seville", List.of("BET", "SEV")),
                Arguments.of("Bilbao", List.of("ATH")),
                Arguments.of("madrid", List.of()),
                Arguments.of("Lisbon", List.of()));
    }

    @ParameterizedTest(name = "city {0} -> {1}")
    @MethodSource("cityFilters")
    void getTeamsFiltersByExactHomeCity(String city, List<String> expectedIds) {
        List<Team> teams = api.getTeams(city).getTeams();

        assertThat(teams).extracting(Team::getId).containsExactlyInAnyOrderElementsOf(expectedIds);
        assertThat(teams).allSatisfy(team -> assertThat(team.getHomeCity()).isEqualTo(city));
    }

    static Stream<Arguments> unknownTeams() {
        List<Named<BiConsumer<LeagueAPI, String>>> operations = List.of(
                Named.of("getTeam", LeagueAPI::getTeam),
                Named.of("updateTeam", (league, id) -> league.updateTeam(id, updateTeam("Name", "City", "Stadium"))),
                Named.of("deleteTeam", LeagueAPI::deleteTeam));
        List<String> ids = new ArrayList<>(List.of("XXX", "bar"));
        ids.add(null);
        return operations.stream().flatMap(operation -> ids.stream().map(id -> Arguments.of(operation, id)));
    }

    @ParameterizedTest(name = "{0}({1})")
    @MethodSource("unknownTeams")
    void unknownTeamIsNotFoundAndLeavesTheLeagueUnchanged(BiConsumer<LeagueAPI, String> operation, String teamId) {
        assertThatThrownBy(() -> operation.accept(api, teamId))
                .isExactlyInstanceOf(ApiKitNotFoundException.class)
                .hasMessage("Team " + teamId + " does not exist");

        assertThat(api.getTeams(null).getTeams()).extracting(Team::getId).containsExactlyInAnyOrderElementsOf(SEEDED_IDS);
        assertThat(api.getFixture().getFixture()).hasSize(FIXTURE_SIZE);
    }

    static Stream<Arguments> unknownMatches() {
        List<Named<MatchOperation>> operations = List.of(
                Named.of("getMatch", LeagueAPI::getMatch),
                Named.of("updateScore", (league, home, away) -> league.updateScore(home, away, score(1, 0))));
        List<List<String>> pairs = List.of(
                List.of("BAR", "BAR"), List.of("BAR", "XXX"), List.of("XXX", "BAR"), List.of("bar", "rma"));
        return operations.stream()
                .flatMap(operation -> pairs.stream().map(pair -> Arguments.of(operation, pair.get(0), pair.get(1))));
    }

    @ParameterizedTest(name = "{0}({1}, {2})")
    @MethodSource("unknownMatches")
    void matchOutsideTheFixtureIsNotFound(MatchOperation operation, String homeTeamId, String awayTeamId) {
        assertThatThrownBy(() -> operation.accept(api, homeTeamId, awayTeamId))
                .isExactlyInstanceOf(ApiKitNotFoundException.class)
                .hasMessage("There is no match between team " + homeTeamId + " and team " + awayTeamId);
    }

    @Test
    void fixtureHoldsOnePlayedAndOneUnplayedLegOfEveryPairing() {
        Date now = new Date();
        List<Match> fixture = api.getFixture().getFixture();

        Set<String> expectedPairings = new HashSet<>();
        for (String home : SEEDED_IDS) {
            for (String away : SEEDED_IDS) {
                if (!home.equals(away)) {
                    expectedPairings.add(pairing(home, away));
                }
            }
        }
        assertThat(fixture).hasSize(FIXTURE_SIZE);
        assertThat(fixture).extracting(match -> pairing(match.getHomeTeam(), match.getAwayTeam()))
                .containsExactlyInAnyOrderElementsOf(expectedPairings);

        List<Match> played = fixture.stream().filter(LeagueAPITest::isPlayed).toList();
        List<Match> unplayed = fixture.stream().filter(Predicate.not(LeagueAPITest::isPlayed)).toList();
        assertThat(played).hasSize(GAMES_PER_ROUND)
                .allSatisfy(match -> assertThat(match.getDate()).isBefore(now));
        assertThat(unplayed).hasSize(GAMES_PER_ROUND)
                .allSatisfy(match -> assertThat(match.getHomeTeamScore()).isNull())
                .allSatisfy(match -> assertThat(match.getAwayTeamScore()).isNull());
        assertThat(unplayed).filteredOn(match -> match.getDate().before(now)).hasSize(GAMES_PER_ROUND / 2);
        assertThat(unplayed).filteredOn(match -> match.getDate().after(now)).hasSize(GAMES_PER_ROUND / 2);

        Set<String> playedPairings = played.stream()
                .map(match -> unorderedPairing(match.getHomeTeam(), match.getAwayTeam()))
                .collect(Collectors.toSet());
        assertThat(playedPairings).as("each pairing is played in exactly one direction").hasSize(GAMES_PER_ROUND);
    }

    @Test
    void getMatchReturnsTheMatchTheFixtureLists() {
        assertThat(api.getFixture().getFixture()).allSatisfy(match ->
                assertThat(api.getMatch(match.getHomeTeam(), match.getAwayTeam()))
                        .usingRecursiveComparison()
                        .isEqualTo(match));
    }

    @Test
    void getMatchReturnsEitherLegOfAPairingByHomeAndAwayTeam() {
        Match barcelonaAtHome = api.getMatch("BAR", "RMA");
        Match madridAtHome = api.getMatch("RMA", "BAR");

        assertThat(barcelonaAtHome).extracting(Match::getHomeTeam, Match::getAwayTeam).containsExactly("BAR", "RMA");
        assertThat(madridAtHome).extracting(Match::getHomeTeam, Match::getAwayTeam).containsExactly("RMA", "BAR");
        assertThat(barcelonaAtHome.getDate()).isNotNull();
        assertThat(madridAtHome.getDate()).isNotNull();
        assertThat(Stream.of(barcelonaAtHome, madridAtHome).filter(LeagueAPITest::isPlayed))
                .as("exactly one leg of the pairing has been played")
                .hasSize(1);
    }

    @Test
    void getPositionsRanksEveryTeamByTheResultsOfTheFixture() {
        assertStandingsAgreeWithFixture(SEEDED_IDS, MATCHES_PER_TEAM);
    }

    static Stream<Arguments> newTeamStadiums() {
        return Stream.of(Arguments.of("Estadio Nuevo"), Arguments.of((String) null));
    }

    @ParameterizedTest(name = "stadium {0}")
    @MethodSource("newTeamStadiums")
    void addTeamRegistersATeamThatHasNoMatches(String stadium) {
        String id = api.addTeam(requestTeam("NEW", "Nuevo Club", "Madrid", stadium));

        assertThat(id).isEqualTo("NEW");
        assertThat(api.getTeam("NEW"))
                .extracting(Team::getId, Team::getName, Team::getHomeCity, Team::getStadium, Team::getMatches)
                .containsExactly("NEW", "Nuevo Club", "Madrid", stadium, 0);
        assertThat(api.getTeams(null).getTeams()).hasSize(SEEDED_TEAMS + 1);
        assertThat(api.getTeams("Madrid").getTeams()).extracting(Team::getId)
                .containsExactlyInAnyOrder("ATL", "NEW", "RAY", "RMA");
        assertThat(api.getFixture().getFixture()).hasSize(FIXTURE_SIZE)
                .noneSatisfy(match -> assertThat(match.getHomeTeam()).isEqualTo("NEW"))
                .noneSatisfy(match -> assertThat(match.getAwayTeam()).isEqualTo("NEW"));
        assertThat(api.getPositions().getPositions()).hasSize(SEEDED_TEAMS + 1)
                .filteredOn(position -> "NEW".equals(position.getTeam()))
                .singleElement()
                .extracting(Position::getPoints, Position::getMatchesPlayed, Position::getMatchesWon,
                        Position::getMatchesDraw, Position::getMatchesLost, Position::getGoalsInFavor,
                        Position::getGoalsAgainst)
                .containsExactly(0, 0, 0, 0, 0, 0, 0);
        assertThatThrownBy(() -> api.getMatch("NEW", "BAR"))
                .isExactlyInstanceOf(ApiKitNotFoundException.class)
                .hasMessage("There is no match between team NEW and team BAR");
    }

    @Test
    void addTeamWithASeededIdIsAConflictAndKeepsTheSeededTeam() {
        assertThatThrownBy(() -> api.addTeam(requestTeam("BAR", "Impostor", "Madrid", "Elsewhere")))
                .isExactlyInstanceOf(ConflictException.class)
                .hasMessage("There is already a team with id BAR");

        assertThat(api.getTeam("BAR"))
                .extracting(Team::getId, Team::getName, Team::getHomeCity, Team::getStadium, Team::getMatches)
                .containsExactly("BAR", "Barcelona", "Barcelona", "Camp Nou", MATCHES_PER_TEAM);
        assertThat(api.getTeams(null).getTeams()).hasSize(SEEDED_TEAMS);
    }

    @Test
    void addTeamSubmittedTwiceIsAConflictTheSecondTime() {
        assertThat(api.addTeam(requestTeam("NEW", "Nuevo Club", "Madrid", "Estadio Nuevo"))).isEqualTo("NEW");

        assertThatThrownBy(() -> api.addTeam(requestTeam("NEW", "Second Club", "Getafe", "Other Stadium")))
                .isExactlyInstanceOf(ConflictException.class)
                .hasMessage("There is already a team with id NEW");

        assertThat(api.getTeam("NEW"))
                .extracting(Team::getName, Team::getHomeCity, Team::getStadium)
                .containsExactly("Nuevo Club", "Madrid", "Estadio Nuevo");
        assertThat(api.getTeams(null).getTeams()).hasSize(SEEDED_TEAMS + 1);
    }

    static Stream<Arguments> teamUpdates() {
        return Stream.of(
                Arguments.of("FC Barcelona", null, null, "FC Barcelona", "Barcelona", "Camp Nou"),
                Arguments.of(null, "Badalona", null, "Barcelona", "Badalona", "Camp Nou"),
                Arguments.of(null, null, "Estadi Olimpic", "Barcelona", "Barcelona", "Estadi Olimpic"),
                Arguments.of("FC Barcelona", "Badalona", "Estadi Olimpic", "FC Barcelona", "Badalona", "Estadi Olimpic"),
                Arguments.of(null, null, null, "Barcelona", "Barcelona", "Camp Nou"));
    }

    @ParameterizedTest(name = "update ({0}, {1}, {2})")
    @MethodSource("teamUpdates")
    void updateTeamChangesOnlyTheFieldsItCarries(String name, String homeCity, String stadium,
                                                 String expectedName, String expectedHomeCity,
                                                 String expectedStadium) {
        api.updateTeam("BAR", updateTeam(name, homeCity, stadium));

        assertThat(api.getTeam("BAR"))
                .extracting(Team::getId, Team::getName, Team::getHomeCity, Team::getStadium, Team::getMatches)
                .containsExactly("BAR", expectedName, expectedHomeCity, expectedStadium, MATCHES_PER_TEAM);
    }

    @Test
    void updateTeamHomeCityMovesTheTeamBetweenCityFilters() {
        api.updateTeam("BAR", updateTeam(null, "Madrid", null));

        assertThat(api.getTeams("Barcelona").getTeams()).extracting(Team::getId).containsExactly("ESP");
        assertThat(api.getTeams("Madrid").getTeams()).extracting(Team::getId)
                .containsExactlyInAnyOrder("ATL", "BAR", "RAY", "RMA");
    }

    static Stream<Arguments> dueMatches() {
        return Stream.of(
                Arguments.of(Named.<Predicate<Match>>of("a played first-round match", LeagueAPITest::isPlayed)),
                Arguments.of(Named.<Predicate<Match>>of("an unplayed match dated yesterday",
                        match -> !isPlayed(match) && match.getDate().before(new Date()))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dueMatches")
    void updateScoreRecordsTheScoreOfAMatchThatIsDue(Predicate<Match> selector) {
        Match due = pickMatch(selector);
        List<Position> standingsBefore = api.getPositions().getPositions();

        api.updateScore(due.getHomeTeam(), due.getAwayTeam(), score(4, 3));

        Match updated = api.getMatch(due.getHomeTeam(), due.getAwayTeam());
        assertThat(updated)
                .extracting(Match::getHomeTeam, Match::getAwayTeam, Match::getDate, Match::getHomeTeamScore,
                        Match::getAwayTeamScore)
                .containsExactly(due.getHomeTeam(), due.getAwayTeam(), due.getDate(), 4, 3);
        assertThat(api.getFixture().getFixture())
                .filteredOn(match -> match.getHomeTeam().equals(due.getHomeTeam())
                        && match.getAwayTeam().equals(due.getAwayTeam()))
                .singleElement()
                .usingRecursiveComparison()
                .isEqualTo(updated);
        assertThat(api.getPositions().getPositions())
                .as("standings are not recalculated by a score update")
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("position")
                .containsExactlyInAnyOrderElementsOf(standingsBefore);
    }

    @Test
    void updateScoreOfAMatchNotYetDueIsAConflictAndKeepsItUnplayed() {
        Date now = new Date();
        Match future = pickMatch(match -> match.getDate().after(now));
        String homeTeamId = future.getHomeTeam();
        String awayTeamId = future.getAwayTeam();

        assertThatThrownBy(() -> api.updateScore(homeTeamId, awayTeamId, score(2, 1)))
                .isExactlyInstanceOf(ConflictException.class)
                .hasMessage("The match between team " + homeTeamId + " and team " + awayTeamId
                        + " has not been played yet");

        assertThat(api.getMatch(homeTeamId, awayTeamId))
                .extracting(Match::getHomeTeamScore, Match::getAwayTeamScore)
                .containsOnlyNulls();
    }

    @Test
    void deleteTeamRemovesTheTeamAndRevertsTheMatchesItPlayed() {
        api.deleteTeam("BAR");

        List<String> remainingIds = SEEDED_IDS.stream().filter(id -> !id.equals("BAR")).toList();
        assertThatThrownBy(() -> api.getTeam("BAR"))
                .isExactlyInstanceOf(ApiKitNotFoundException.class)
                .hasMessage("Team BAR does not exist");
        assertThat(api.getTeams(null).getTeams()).extracting(Team::getId)
                .containsExactlyInAnyOrderElementsOf(remainingIds);
        assertThat(api.getTeams("Barcelona").getTeams()).extracting(Team::getId).containsExactly("ESP");
        assertThat(api.getFixture().getFixture()).hasSize((SEEDED_TEAMS - 1) * (SEEDED_TEAMS - 2))
                .noneSatisfy(match -> assertThat(match.getHomeTeam()).isEqualTo("BAR"))
                .noneSatisfy(match -> assertThat(match.getAwayTeam()).isEqualTo("BAR"));
        assertThatThrownBy(() -> api.getMatch("BAR", "RMA"))
                .isExactlyInstanceOf(ApiKitNotFoundException.class)
                .hasMessage("There is no match between team BAR and team RMA");
        assertThat(remainingIds).allSatisfy(id ->
                assertThat(api.getTeam(id).getMatches()).as("matches of %s", id).isEqualTo(MATCHES_PER_TEAM - 1));
        assertStandingsAgreeWithFixture(remainingIds, MATCHES_PER_TEAM - 1);

        assertThatThrownBy(() -> api.deleteTeam("BAR"))
                .as("a repeated delete")
                .isExactlyInstanceOf(ApiKitNotFoundException.class)
                .hasMessage("Team BAR does not exist");
    }

    @Test
    void initializeReloadsTheSeededLeague() {
        api.deleteTeam("BAR");
        api.addTeam(requestTeam("NEW", "Nuevo Club", "Madrid", "Estadio Nuevo"));

        api.initialize();

        assertThat(api.getTeams(null).getTeams()).extracting(Team::getId)
                .containsExactlyInAnyOrderElementsOf(SEEDED_IDS);
        assertThat(api.getTeam("BAR").getMatches()).isEqualTo(MATCHES_PER_TEAM);
        assertThat(api.getFixture().getFixture()).hasSize(FIXTURE_SIZE);
        assertStandingsAgreeWithFixture(SEEDED_IDS, MATCHES_PER_TEAM);
    }

    /**
     * Asserts that {@code getPositions} lists {@code teamIds} once each, numbered from 1, ranked by
     * {@link #RANKING}, and that each team's figures equal a tally of the played matches {@code getFixture}
     * returns.
     */
    private void assertStandingsAgreeWithFixture(List<String> teamIds, int matchesPerTeam) {
        Map<String, Tally> tallies = new HashMap<>();
        teamIds.forEach(id -> tallies.put(id, new Tally()));
        for (Match match : api.getFixture().getFixture()) {
            if (isPlayed(match)) {
                tallies.get(match.getHomeTeam()).add(match.getHomeTeamScore(), match.getAwayTeamScore());
                tallies.get(match.getAwayTeam()).add(match.getAwayTeamScore(), match.getHomeTeamScore());
            }
        }

        List<Position> positions = api.getPositions().getPositions();

        assertThat(positions).extracting(Position::getTeam).containsExactlyInAnyOrderElementsOf(teamIds);
        assertThat(positions).extracting(Position::getPosition)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, teamIds.size()).boxed().toList());
        assertThat(positions).isSortedAccordingTo(RANKING);
        assertThat(positions).allSatisfy(position -> {
            Tally tally = tallies.get(position.getTeam());
            assertThat(position)
                    .as("standing of %s", position.getTeam())
                    .extracting(Position::getMatchesPlayed, Position::getMatchesWon, Position::getMatchesDraw,
                            Position::getMatchesLost, Position::getPoints, Position::getGoalsInFavor,
                            Position::getGoalsAgainst)
                    .containsExactly(matchesPerTeam, tally.won, tally.drawn, tally.lost,
                            3 * tally.won + tally.drawn, tally.goalsInFavor, tally.goalsAgainst);
        });
    }

    /** Returns a match of the current fixture that satisfies {@code selector}; the seed always holds one. */
    private Match pickMatch(Predicate<Match> selector) {
        return api.getFixture().getFixture().stream()
                .filter(selector)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the seeded fixture holds no such match"));
    }

    private static boolean isPlayed(Match match) {
        return match.getHomeTeamScore() != null && match.getAwayTeamScore() != null;
    }

    private static String pairing(String homeTeamId, String awayTeamId) {
        return homeTeamId + ":" + awayTeamId;
    }

    private static String unorderedPairing(String teamId, String otherTeamId) {
        return teamId.compareTo(otherTeamId) < 0 ? pairing(teamId, otherTeamId) : pairing(otherTeamId, teamId);
    }

    private static com.mulesoft.examples.rest_api_with_apikit.model.request.Team requestTeam(
            String id, String name, String homeCity, String stadium) {
        com.mulesoft.examples.rest_api_with_apikit.model.request.Team team =
                new com.mulesoft.examples.rest_api_with_apikit.model.request.Team();
        team.setId(id);
        team.setName(name);
        team.setHomeCity(homeCity);
        team.setStadium(stadium);
        return team;
    }

    private static UpdateTeam updateTeam(String name, String homeCity, String stadium) {
        UpdateTeam updateTeam = new UpdateTeam();
        updateTeam.setName(name);
        updateTeam.setHomeCity(homeCity);
        updateTeam.setStadium(stadium);
        return updateTeam;
    }

    private static Score score(int homeTeamScore, int awayTeamScore) {
        Score score = new Score();
        score.setHomeTeamScore(homeTeamScore);
        score.setAwayTeamScore(awayTeamScore);
        return score;
    }

    /** A {@link LeagueAPI} operation addressed by home and away team. */
    @FunctionalInterface
    interface MatchOperation {
        void accept(LeagueAPI api, String homeTeamId, String awayTeamId);
    }

    /** Wins, draws, losses and goals of one team, counted from played matches. */
    private static final class Tally {
        private int won;
        private int drawn;
        private int lost;
        private int goalsInFavor;
        private int goalsAgainst;

        void add(int scored, int conceded) {
            if (scored > conceded) {
                won++;
            } else if (scored < conceded) {
                lost++;
            } else {
                drawn++;
            }
            goalsInFavor += scored;
            goalsAgainst += conceded;
        }
    }
}
