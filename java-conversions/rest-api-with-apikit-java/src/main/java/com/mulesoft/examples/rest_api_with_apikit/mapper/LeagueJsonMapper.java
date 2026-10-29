/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.mapper;

import java.io.UncheckedIOException;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mulesoft.examples.rest_api_with_apikit.model.request.Score;
import com.mulesoft.examples.rest_api_with_apikit.model.request.UpdateTeam;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Fixture;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Match;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Positions;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Team;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Teams;

/**
 * Reads and writes the league API's JSON bodies, reproducing the bytes the original Mule application
 * produced. The controllers call it so that they carry no transformation logic.
 *
 * <p>The original rendered league objects in two different ways, and both are kept:
 * <ul>
 *   <li><b>Single resources</b> ({@code toJson(Team)} for GET {@code /api/teams/{teamId}},
 *       {@code toJson(Match)} for GET {@code /api/fixture/{homeTeamId}/{awayTeamId}}) went through Mule's
 *       automatic object-to-JSON transformer, which honoured the beans' Jackson annotations. They are
 *       written with annotations honoured: null properties are omitted ({@code @JsonInclude(NON_NULL)}) and
 *       {@code Match.date} is a string formatted by {@link JsonDateSerializer}.</li>
 *   <li><b>Collections</b> ({@code toJson(Teams)} for GET {@code /api/teams}, {@code toJson(Positions)} for
 *       GET {@code /api/positions}, {@code toJson(Fixture)} for GET {@code /api/fixture}) were written by
 *       each wrapper's own {@code toJson} transformer, a plain Jackson 2 {@code ObjectMapper} that ignored
 *       the Jackson 1 annotations its elements carried. They are written with annotation processing
 *       disabled, so the elements' annotations are ignored in the same way: properties come from public
 *       getters, nulls are written (for example {@code "matches":null} on every team of the list) and
 *       dates are epoch milliseconds.</li>
 * </ul>
 * Both renderings are compact, with properties in the beans' field-declaration order.
 *
 * <p>The mappers are private and built here, never Spring Boot's auto-configured {@code ObjectMapper}: Boot
 * disables {@code WRITE_DATES_AS_TIMESTAMPS} and {@code FAIL_ON_UNKNOWN_PROPERTIES} and applies any
 * application customisation, each of which would change the bytes or the accepted bodies. Every reader and
 * writer is immutable, so the singleton serves concurrent requests safely.
 *
 * <p>The readers replace APIkit's {@code PayloadNormalizerTransformer}, which turned the validated body of
 * POST {@code /teams}, PUT {@code /teams/{teamId}} and PUT {@code /fixture/{homeTeamId}/{awayTeamId}} into
 * the request beans. Callers validate the body against its RAML schema first, so blank, malformed and
 * mistyped bodies are already answered with APIkit's 400. None of the three schemas forbids additional
 * properties, so a body with an unknown property passes validation and reaches the reader, which keeps
 * Jackson's default {@code FAIL_ON_UNKNOWN_PROPERTIES} (also the default of the Jackson 1 mapper behind
 * Mule's JSON-to-object transformation) and rejects it. That failure reaches the generic 500, as the
 * original's unmapped transformer exception reached the platform default; the exact response is a Tier 2A
 * verification point.
 *
 * <p>Every {@link JsonProcessingException} is rethrown as an {@link UncheckedIOException} carrying Jackson's
 * original message without its source-location suffix, so the generic exception handler answers a
 * deterministic 500 {@code { "message": "<message>" }}. No project or APIkit handler claims it.
 */
@Component
public class LeagueJsonMapper {

    private final ObjectWriter resourceWriter;

    private final ObjectWriter collectionWriter;

    private final ObjectReader teamReader;

    private final ObjectReader updateTeamReader;

    private final ObjectReader scoreReader;

    /** Builds the private Jackson readers and writers; needs no Spring context. */
    public LeagueJsonMapper() {
        ObjectMapper annotatedMapper = new ObjectMapper();
        this.resourceWriter = annotatedMapper.writer();
        this.collectionWriter = JsonMapper.builder()
                .disable(MapperFeature.USE_ANNOTATIONS)
                .build()
                .writer();
        this.teamReader = annotatedMapper.readerFor(
                com.mulesoft.examples.rest_api_with_apikit.model.request.Team.class);
        this.updateTeamReader = annotatedMapper.readerFor(UpdateTeam.class);
        this.scoreReader = annotatedMapper.readerFor(Score.class);
    }

    /** Writes a single team with nulls omitted, as GET {@code /api/teams/{teamId}} returns it. */
    public String toJson(Team team) {
        return write(resourceWriter, team);
    }

    /** Writes a single match with nulls omitted and a string date, as GET {@code /api/fixture/{h}/{a}} returns it. */
    public String toJson(Match match) {
        return write(resourceWriter, match);
    }

    /** Writes the team list with nulls kept, as GET {@code /api/teams} returns it. */
    public String toJson(Teams teams) {
        return write(collectionWriter, teams);
    }

    /** Writes the league table with every property, as GET {@code /api/positions} returns it. */
    public String toJson(Positions positions) {
        return write(collectionWriter, positions);
    }

    /** Writes the fixture with nulls kept and epoch-millisecond dates, as GET {@code /api/fixture} returns it. */
    public String toJson(Fixture fixture) {
        return write(collectionWriter, fixture);
    }

    /** Reads the schema-validated body of POST {@code /teams}. */
    public com.mulesoft.examples.rest_api_with_apikit.model.request.Team readTeam(String json) {
        return read(teamReader, json);
    }

    /** Reads the schema-validated body of PUT {@code /teams/{teamId}}; absent fields stay null. */
    public UpdateTeam readUpdateTeam(String json) {
        return read(updateTeamReader, json);
    }

    /** Reads the schema-validated body of PUT {@code /fixture/{homeTeamId}/{awayTeamId}}. */
    public Score readScore(String json) {
        return read(scoreReader, json);
    }

    private static String write(ObjectWriter writer, Object value) {
        try {
            return writer.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e.getOriginalMessage(), e);
        }
    }

    private static <T> T read(ObjectReader reader, String json) {
        try {
            return reader.readValue(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e.getOriginalMessage(), e);
        }
    }
}
