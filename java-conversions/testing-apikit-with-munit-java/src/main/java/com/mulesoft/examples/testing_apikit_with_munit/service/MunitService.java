package com.mulesoft.examples.testing_apikit_with_munit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Flow logic of the {@code /munit} resource, replacing the four APIkit-routed stub flows of the original
 * {@code testing-apikit-with-munit/src/main/app/api.xml}:
 * <ul>
 *   <li>{@code get:/munit:api-config} (lines 45-48)</li>
 *   <li>{@code put:/munit:api-config} (lines 49-52)</li>
 *   <li>{@code delete:/munit:api-config} (lines 53-56)</li>
 *   <li>{@code post:/munit:api-config} (lines 57-60)</li>
 * </ul>
 * Each flow only sets a string literal as the payload. APIkit wrote that String to the RAML-declared
 * {@code application/json} response as a JSON string, so the MUnit suite asserts the literal with its
 * surrounding double quotes ({@code api-test-suite.xml:20,28,36,44}). The methods therefore return the
 * JSON-serialized literal, computed once at construction; the instance holds no other state.
 */
@Service
public class MunitService {

    /** Payload of flow {@code get:/munit:api-config} ({@code api.xml:47}). */
    public static final String GET_RESPONSE = "GET RESPONSE";

    /** Payload of flow {@code post:/munit:api-config} ({@code api.xml:59}). */
    public static final String POST_RESPONSE = "POST RESPONSE";

    /** Payload of flow {@code put:/munit:api-config} ({@code api.xml:51}). */
    public static final String PUT_RESPONSE = "PUT RESPONSE";

    /** Payload of flow {@code delete:/munit:api-config} ({@code api.xml:55}). */
    public static final String DELETE_RESPONSE = "DELETE RESPONSE";

    private final String getResponse;
    private final String postResponse;
    private final String putResponse;
    private final String deleteResponse;

    /**
     * Serializes the four flow literals as JSON strings.
     *
     * @param objectMapper the application's Jackson mapper
     * @throws IllegalStateException if a literal cannot be serialized
     */
    public MunitService(ObjectMapper objectMapper) {
        this.getResponse = toJsonString(objectMapper, GET_RESPONSE);
        this.postResponse = toJsonString(objectMapper, POST_RESPONSE);
        this.putResponse = toJsonString(objectMapper, PUT_RESPONSE);
        this.deleteResponse = toJsonString(objectMapper, DELETE_RESPONSE);
    }

    /**
     * Replaces flow {@code get:/munit:api-config}.
     *
     * @return {@code "GET RESPONSE"} as a JSON string, quotes included
     */
    public String get() {
        return getResponse;
    }

    /**
     * Replaces flow {@code post:/munit:api-config}.
     *
     * @return {@code "POST RESPONSE"} as a JSON string, quotes included
     */
    public String post() {
        return postResponse;
    }

    /**
     * Replaces flow {@code put:/munit:api-config}.
     *
     * @return {@code "PUT RESPONSE"} as a JSON string, quotes included
     */
    public String put() {
        return putResponse;
    }

    /**
     * Replaces flow {@code delete:/munit:api-config}.
     *
     * @return {@code "DELETE RESPONSE"} as a JSON string, quotes included
     */
    public String delete() {
        return deleteResponse;
    }

    private static String toJsonString(ObjectMapper objectMapper, String literal) {
        try {
            return objectMapper.writeValueAsString(literal);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize response literal '" + literal + "' as JSON", e);
        }
    }
}
