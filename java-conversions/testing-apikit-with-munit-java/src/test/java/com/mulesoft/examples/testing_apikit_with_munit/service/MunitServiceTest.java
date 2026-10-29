package com.mulesoft.examples.testing_apikit_with_munit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.Closeable;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Contract of {@link MunitService}, the replacement of the four stub flows of the original
 * {@code testing-apikit-with-munit/src/main/app/api.xml}: each method returns its flow's literal
 * ({@code api.xml:47,59,51,55}) serialized as a JSON string, which is exactly the payload the original MUnit
 * suite asserts, quotes included ({@code api-test-suite.xml:20,28,36,44}).
 *
 * <p>Service level only: no Spring context, no HTTP and no mocks, since the service has no collaborator
 * beyond the {@link ObjectMapper} it is built with. Status codes and the HTTP exchange belong to
 * {@code ApiTestSuiteTest} and {@code controller/MunitControllerTest}.
 */
class MunitServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MunitService service;

    @BeforeEach
    void setUp() {
        service = new MunitService(new ObjectMapper());
    }

    /** Each response method with the flow literal it must return, named after the original MUnit test. */
    static Stream<Arguments> responses() {
        return Stream.of(
                response("api-test-get", MunitService::get, "GET RESPONSE"),
                response("api-test-post", MunitService::post, "POST RESPONSE"),
                response("api-test-put", MunitService::put, "PUT RESPONSE"),
                response("api-test-delete", MunitService::delete, "DELETE RESPONSE"));
    }

    private static Arguments response(String munitTest, Function<MunitService, String> call, String literal) {
        return Arguments.of(Named.of(munitTest, call), literal);
    }

    @Test
    @DisplayName("constants equal the set-payload literals of api.xml")
    void constantsEqualOriginalFlowLiterals() {
        assertThat(MunitService.GET_RESPONSE).isEqualTo("GET RESPONSE");
        assertThat(MunitService.POST_RESPONSE).isEqualTo("POST RESPONSE");
        assertThat(MunitService.PUT_RESPONSE).isEqualTo("PUT RESPONSE");
        assertThat(MunitService.DELETE_RESPONSE).isEqualTo("DELETE RESPONSE");
    }

    @Test
    @DisplayName("api-test-get: GET payload is \"GET RESPONSE\"")
    void getReturnsQuotedGetResponse() {
        assertThat(service.get()).isEqualTo("\"GET RESPONSE\"");
    }

    @Test
    @DisplayName("api-test-post: POST payload is \"POST RESPONSE\"")
    void postReturnsQuotedPostResponse() {
        assertThat(service.post()).isEqualTo("\"POST RESPONSE\"");
    }

    @Test
    @DisplayName("api-test-put: PUT payload is \"PUT RESPONSE\"")
    void putReturnsQuotedPutResponse() {
        assertThat(service.put()).isEqualTo("\"PUT RESPONSE\"");
    }

    @Test
    @DisplayName("api-test-delete: DELETE payload is \"DELETE RESPONSE\"")
    void deleteReturnsQuotedDeleteResponse() {
        assertThat(service.delete()).isEqualTo("\"DELETE RESPONSE\"");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("responses")
    void responsesAreJsonStringLiterals(Function<MunitService, String> call, String literal) throws Exception {
        JsonNode node = objectMapper.readTree(call.apply(service));

        assertThat(node.isTextual()).isTrue();
        assertThat(node.asText()).isEqualTo(literal);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("responses")
    void repeatedCallsReturnEqualValues(Function<MunitService, String> call, String literal) {
        String first = call.apply(service);

        // The value is serialized once at construction, so every call hands back the same string.
        assertThat(call.apply(service)).as("second call for %s", literal).isSameAs(first);
        assertThat(call.apply(new MunitService(new ObjectMapper())))
                .as("fresh instance for %s", literal)
                .isEqualTo(first);
    }

    @Test
    void constructorWrapsSerializationFailureInIllegalStateException() {
        FailingObjectMapper failingMapper = new FailingObjectMapper();

        assertThatIllegalStateException()
                .isThrownBy(() -> new MunitService(failingMapper))
                .withMessage("Failed to serialize response literal 'GET RESPONSE' as JSON")
                .withCause(failingMapper.failure);
    }

    /** A mapper whose serialization always fails, to drive the service's only error path. */
    private static final class FailingObjectMapper extends ObjectMapper {

        private static final long serialVersionUID = 1L;

        private final JsonMappingException failure =
                new JsonMappingException((Closeable) null, "serialization refused");

        @Override
        public String writeValueAsString(Object value) throws JsonMappingException {
            throw failure;
        }
    }
}
