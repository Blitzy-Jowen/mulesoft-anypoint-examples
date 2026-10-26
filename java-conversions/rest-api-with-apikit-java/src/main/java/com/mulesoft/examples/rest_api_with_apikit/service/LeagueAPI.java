/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.service;

import com.mulesoft.examples.rest_api_with_apikit.exception.ApiKitNotFoundException;
import com.mulesoft.examples.rest_api_with_apikit.exception.ConflictException;
import com.mulesoft.examples.rest_api_with_apikit.model.League;
import com.mulesoft.examples.rest_api_with_apikit.model.request.Score;
import com.mulesoft.examples.rest_api_with_apikit.model.request.UpdateTeam;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Fixture;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Match;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Position;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Positions;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Team;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Teams;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class LeagueAPI {

    private League league;

    @PostConstruct
    public void initialize() {
        league = new League();
        league.initialize();
    }


    public void deleteTeam(String teamId) {
        if(!league.hasTeam(teamId)) {
            throw new ApiKitNotFoundException("Team " + teamId + " does not exist");
        }
        league.deleteTeam(teamId);
    }

    public Teams getTeams(String city) {
        List<Team> teamsList = new ArrayList<Team>();
        List<com.mulesoft.examples.rest_api_with_apikit.model.Team> teams = league.getTeams(city);
        for(com.mulesoft.examples.rest_api_with_apikit.model.Team team : teams) {
            Team teamResp = new Team();
            teamResp.setId(team.getId());
            teamResp.setName(team.getName());
            teamResp.setHomeCity(team.getHomeCity());
            teamResp.setStadium(team.getStadium());
            teamsList.add(teamResp);
        }

        Teams teamsResp = new Teams();
        teamsResp.setTeams(teamsList);
        return teamsResp;
    }

    public Match getMatch(String homeTeamId, String awayTeamId) {
        if(!league.hasMatch(homeTeamId, awayTeamId)) {
            throw new ApiKitNotFoundException("There is no match between team " + homeTeamId + " and team " + awayTeamId);
        }

        com.mulesoft.examples.rest_api_with_apikit.model.Match match = league.getMatch(homeTeamId, awayTeamId);
        Match matchResp = new Match();
        matchResp.setHomeTeam(match.getHomeTeam().getId());
        matchResp.setAwayTeam(match.getAwayTeam().getId());
        matchResp.setDate(match.getDate());
        matchResp.setHomeTeamScore(match.getHomeTeamScore());
        matchResp.setAwayTeamScore(match.getAwayTeamScore());
        return matchResp;
    }

    public Team getTeam(String teamId) {
        if(!league.hasTeam(teamId)) {
            throw new ApiKitNotFoundException("Team " + teamId + " does not exist");
        }

        com.mulesoft.examples.rest_api_with_apikit.model.Team team = league.getTeam(teamId);
        Team teamResp = new Team();
        teamResp.setId(team.getId());
        teamResp.setName(team.getName());
        teamResp.setHomeCity(team.getHomeCity());
        teamResp.setStadium(team.getStadium());
        teamResp.setMatches(team.getMatchesPlayed());
        return teamResp;
    }

    public Positions getPositions() {
        Positions positions = new Positions();
        List<com.mulesoft.examples.rest_api_with_apikit.model.Team> teams = league.orderTeamsByPosition();
        List<Position> positionsList = new ArrayList<Position>();

        int positionIndex = 1;
        for(com.mulesoft.examples.rest_api_with_apikit.model.Team team : teams) {
            Position position = new Position();
            position.setPosition(positionIndex);
            position.setTeam(team.getId());
            position.setPoints(team.getPoints());
            position.setMatchesPlayed(team.getMatchesPlayed());
            position.setMatchesWon(team.getMatchesWon());
            position.setMatchesLost(team.getMatchesLost());
            position.setMatchesDraw(team.getMatchesDraw());
            position.setGoalsAgainst(team.getGoalsAgainst());
            position.setGoalsInFavor(team.getGoalsInFavor());
            positionsList.add(position);
            positionIndex++;
        }

        positions.setPositions(positionsList);
        return positions;
    }

    public Fixture getFixture() {
        Fixture fixture = new Fixture();
        List<com.mulesoft.examples.rest_api_with_apikit.model.Match> matches = league.getMatches();
        List<Match> matchesList = new ArrayList<Match>();

        for(com.mulesoft.examples.rest_api_with_apikit.model.Match match : matches) {
            Match matchResp = new Match();
            matchResp.setHomeTeam(match.getHomeTeam().getId());
            matchResp.setAwayTeam(match.getAwayTeam().getId());
            matchResp.setDate(match.getDate());
            matchResp.setHomeTeamScore(match.getHomeTeamScore());
            matchResp.setAwayTeamScore(match.getAwayTeamScore());
            matchesList.add(matchResp);
        }

        fixture.setFixture(matchesList);
        return fixture;
    }

    public String addTeam(com.mulesoft.examples.rest_api_with_apikit.model.request.Team team) {
        if(league.hasTeam(team.getId())) {
            throw new ConflictException("There is already a team with id " + team.getId());
        }

        com.mulesoft.examples.rest_api_with_apikit.model.Team newTeam = new com.mulesoft.examples.rest_api_with_apikit.model.Team();
        newTeam.setId(team.getId());
        newTeam.setName(team.getName());
        newTeam.setHomeCity(team.getHomeCity());
        newTeam.setStadium(team.getStadium());

        league.addTeam(newTeam);
        return team.getId();
    }

    public void updateScore(String homeTeamId, String awayTeamId, Score score) {
        if(!league.hasMatch(homeTeamId, awayTeamId)) {
            throw new ApiKitNotFoundException("There is no match between team " + homeTeamId + " and team " + awayTeamId);
        }

        com.mulesoft.examples.rest_api_with_apikit.model.Match match = league.getMatch(homeTeamId, awayTeamId);
        if(new Date().compareTo(match.getDate()) >= 0) {
            match.setHomeTeamScore(score.getHomeTeamScore());
            match.setAwayTeamScore(score.getAwayTeamScore());
        } else {
            throw new ConflictException("The match between team " + homeTeamId + " and team " + awayTeamId + " has not been played yet");
        }
    }

    public void updateTeam(String teamId, UpdateTeam updateTeam) {
        if(!league.hasTeam(teamId)) {
            throw new ApiKitNotFoundException("Team " + teamId + " does not exist");
        }

        com.mulesoft.examples.rest_api_with_apikit.model.Team team = league.getTeam(teamId);
        if(updateTeam.getName() != null) {
            team.setName(updateTeam.getName());
        }

        if(updateTeam.getHomeCity() != null) {
            team.setHomeCity(updateTeam.getHomeCity());
        }

        if(updateTeam.getStadium() != null) {
            team.setStadium(updateTeam.getStadium());
        }
    }

}
