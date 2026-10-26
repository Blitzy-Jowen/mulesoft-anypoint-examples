/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract of the SC-3 {@link JsonSchemaValidator}: request-body faults raise
 * {@link JsonSchemaValidator.SchemaValidationException} (APIkit's 400), schema faults raise
 * {@link IllegalStateException} (a 500).
 *
 * <p>Shared with every RAML-backed project and copied with only its {@code package} line changed, so it
 * uses inline schemas written in the RAML schema files' draft-03 style and never names a project RAML,
 * schema file or type.
 */
class JsonSchemaValidatorTest {

    /** Not on any classpath; the constructor only takes its directory, the classpath root. */
    private static final String RAML_PATH = "test-api.raml";

    /**
     * Written as the RAML schemas are: {@code $schema} without a trailing {@code #}, the non-standard
     * {@code name} keyword, and draft-03's boolean {@code required} at root and property level.
     */
    private static final String OBJECT_SCHEMA = """
            {
              "$schema": "http://json-schema.org/draft-03/schema",
              "name": "Thing",
              "type": "object",
              "required": true,
              "properties": {
                "id": {
                  "description": "A three-character code",
                  "type": "string",
                  "minLength": 3,
                  "maxLength": 3,
                  "required": true
                },
                "count": {
                  "type": "integer"
                },
                "label": {
                  "description": "Free text describing the thing",
                  "type": "string"
                }
              }
            }
            """;

    private static final String VALID_BODY = "{\"id\":\"ABC\",\"count\":2,\"label\":\"x\"}";

    private static final String INVALID_BODY = "{\"id\":\"ABCD\",\"count\":2,\"label\":\"x\"}";

    /** Draft-03 {@code type} must be a string or an array. */
    private static final String NUMERIC_TYPE_SCHEMA = """
            {
              "$schema": "http://json-schema.org/draft-03/schema",
              "type": 42
            }
            """;

    /** Draft-03 {@code properties} must be an object. */
    private static final String NON_OBJECT_PROPERTIES_SCHEMA = """
            {
              "$schema": "http://json-schema.org/draft-03/schema",
              "type": "object",
              "properties": "not-an-object"
            }
            """;

    private final JsonSchemaValidator validator = new JsonSchemaValidator(RAML_PATH);

    @Test
    void bodySatisfyingTheSchemaPasses() {
        // Also proves the non-standard "name" and "description" keywords are tolerated, not errors.
        assertThatCode(() -> validator.validateAgainst(OBJECT_SCHEMA, VALID_BODY))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":\"ABC\",\"count\":\"two\"}",
            "{\"id\":\"ABC\",\"count\":1.5}",
            "[{\"id\":\"ABC\"}]"
    })
    void valueOfTheWrongTypeIsRejected(String body) {
        assertThatThrownBy(() -> validator.validateAgainst(OBJECT_SCHEMA, body))
                .isInstanceOf(JsonSchemaValidator.SchemaValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABCD", "AB"})
    void stringOutsideItsLengthBoundsIsRejected(String id) {
        String body = "{\"id\":\"" + id + "\",\"count\":1}";

        assertThatThrownBy(() -> validator.validateAgainst(OBJECT_SCHEMA, body))
                .isInstanceOf(JsonSchemaValidator.SchemaValidationException.class);
    }

    @Test
    void missingRequiredPropertyIsRejectedWithDraft03Semantics() {
        // Only draft-03 reads a property's boolean "required": true as "this property must be present".
        // Draft-04 makes it a syntax error, which would surface as IllegalStateException, and a lax reading
        // would ignore it and pass; a validation failure therefore shows draft-03 was applied.
        assertThatThrownBy(() -> validator.validateAgainst(OBJECT_SCHEMA, "{\"count\":1}"))
                .isInstanceOf(JsonSchemaValidator.SchemaValidationException.class)
                .isNotInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\r\n\t"})
    void absentOrBlankBodyIsRejected(String body) {
        assertThatThrownBy(() -> validator.validateAgainst(OBJECT_SCHEMA, body))
                .isInstanceOf(JsonSchemaValidator.SchemaValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":", "not json", "{\"id\":\"ABC\"} {\"id\":\"DEF\"}"})
    void bodyThatIsNotOneJsonDocumentIsRejected(String body) {
        assertThatThrownBy(() -> validator.validateAgainst(OBJECT_SCHEMA, body))
                .isInstanceOf(JsonSchemaValidator.SchemaValidationException.class);
    }

    @Test
    void unparseableSchemaIsAnApplicationFault() {
        assertThatThrownBy(() -> validator.validateAgainst("{\"type\":", "{}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {NUMERIC_TYPE_SCHEMA, NON_OBJECT_PROPERTIES_SCHEMA})
    void structurallyInvalidSchemaIsAnApplicationFaultEveryTime(String schema) {
        // The second call shows the invalid schema was not cached as a usable one.
        assertThatThrownBy(() -> validator.validateAgainst(schema, "{}"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> validator.validateAgainst(schema, "{}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"no/such/schema.json", "./no/such/schema.json", "no/such/"})
    void missingSchemaFileIsAnApplicationFault(String schemaPath) {
        assertThatThrownBy(() -> validator.validate(schemaPath, "{}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cachedSchemaKeepsNoStateBetweenValidations() {
        for (int round = 0; round < 3; round++) {
            assertThatCode(() -> validator.validateAgainst(OBJECT_SCHEMA, VALID_BODY))
                    .doesNotThrowAnyException();
            if (round < 2) {
                assertThatThrownBy(() -> validator.validateAgainst(OBJECT_SCHEMA, INVALID_BODY))
                        .isInstanceOf(JsonSchemaValidator.SchemaValidationException.class);
            }
        }
    }
}
