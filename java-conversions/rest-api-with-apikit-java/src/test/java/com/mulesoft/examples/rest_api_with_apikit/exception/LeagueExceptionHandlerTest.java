/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.exception;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tests {@link LeagueExceptionHandler}, the 409 that {@code leagues.raml} declares for POST {@code /teams} and
 * PUT {@code /fixture/{homeTeamId}/{awayTeamId}} although the original flow's APIkit strategy mapped only 404,
 * 405, 415, 406 and 400.
 *
 * <p>Standalone MockMvc carries the project's three advices, registered on purpose in reverse precedence order:
 * Spring sorts them by {@code @Order}, so only {@code LeagueExceptionHandler}'s {@code @Order(0)} lets the 409
 * win over {@link GlobalExceptionHandler}'s catch-all {@link Exception} method. A precedence defect therefore
 * shows up here as a 500 and must be fixed in the advice, never by reordering this registration.
 *
 * <p>Bodies are compared as exact bytes, because APIkit's {@code { "message": ... }} spacing is the contract.
 */
class LeagueExceptionHandlerTest {

    private static final String TEAM_CONFLICT_BODY = "{ \"message\": \"There is already a team with id BAR\" }";

    private static final String MATCH_CONFLICT_BODY =
            "{ \"message\": \"The match between team BAR and team RMA has not been played yet\" }";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ConflictTestController())
                .setControllerAdvice(new GlobalExceptionHandler(),
                                     new ApiKitExceptionHandler("/api"),
                                     new LeagueExceptionHandler())
                .build();
    }

    @Test
    void conflictOnAddTeamReturns409WithMessage() throws Exception {
        MvcResult result = perform(post("/api/teams"));

        assertEquals(409, result.getResponse().getStatus());
        assertJsonContentType(result);
        assertBody(result, TEAM_CONFLICT_BODY);
    }

    @Test
    void conflictOnUpdateScoreReturns409WithMessage() throws Exception {
        MvcResult result = perform(put("/api/fixture/BAR/RMA"));

        assertEquals(409, result.getResponse().getStatus());
        assertJsonContentType(result);
        assertBody(result, MATCH_CONFLICT_BODY);
    }

    @Test
    void resolvedExceptionIsConflictException() throws Exception {
        MvcResult result = perform(post("/api/teams"));

        ConflictException resolved = assertInstanceOf(ConflictException.class, result.getResolvedException());
        assertEquals("There is already a team with id BAR", resolved.getMessage());
    }

    @Test
    void otherExceptionsStillReachGlobalHandler() throws Exception {
        MvcResult result = perform(get("/api/other-failure"));

        assertEquals(500, result.getResponse().getStatus());
        assertJsonContentType(result);
        assertBody(result, "{ \"message\": \"other\" }");
        assertInstanceOf(IllegalStateException.class, result.getResolvedException());
    }

    private MvcResult perform(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private static void assertBody(MvcResult result, String expected) {
        byte[] actual = result.getResponse().getContentAsByteArray();
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), actual,
                () -> "body was: " + new String(actual, StandardCharsets.UTF_8));
    }

    /**
     * Compares type and subtype only: the handler presets the type on a {@code ResponseEntity} and the string
     * message converter that writes the body may append a {@code charset} parameter.
     */
    private static void assertJsonContentType(MvcResult result) {
        String contentType = result.getResponse().getContentType();
        assertNotNull(contentType, "Content-Type missing");
        assertTrue(MediaType.parseMediaType(contentType).equalsTypeAndSubtype(MediaType.APPLICATION_JSON),
                () -> "expected application/json but Content-Type was " + contentType);
    }

    /**
     * Mirrors the two RAML resources that declare 409, raising the {@link ConflictException} messages the league
     * service raises, plus a control path whose exception no project or APIkit advice claims. No service is
     * called, so the advices are the only behaviour under test.
     */
    @RestController
    static class ConflictTestController {

        @PostMapping("/api/teams")
        String addTeam() {
            throw new ConflictException("There is already a team with id BAR");
        }

        @PutMapping("/api/fixture/{homeTeamId}/{awayTeamId}")
        String updateScore(@PathVariable("homeTeamId") String h, @PathVariable("awayTeamId") String a) {
            throw new ConflictException("The match between team " + h + " and team " + a + " has not been played yet");
        }

        @GetMapping("/api/other-failure")
        String other() {
            throw new IllegalStateException("other");
        }
    }
}
