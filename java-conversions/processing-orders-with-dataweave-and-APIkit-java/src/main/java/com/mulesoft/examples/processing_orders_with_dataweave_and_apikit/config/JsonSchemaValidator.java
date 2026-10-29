/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.fge.jackson.JsonLoader;
import com.github.fge.jsonschema.SchemaVersion;
import com.github.fge.jsonschema.cfg.ValidationConfiguration;
import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import com.github.fge.jsonschema.core.report.LogLevel;
import com.github.fge.jsonschema.core.report.ProcessingMessage;
import com.github.fge.jsonschema.core.report.ProcessingReport;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Validates request bodies against the JSON schemas the RAML includes ({@code schema: !include ...}),
 * with JSON Schema draft-03 semantics, as the APIkit 3.8 router did before it invoked a flow.
 *
 * <p>Schema paths are resolved relative to the classpath directory of the RAML named by
 * {@code apikit.raml}, the way APIkit resolved {@code !include}. Each schema is read and compiled
 * once, on first use, and kept for the life of the application. The RAML itself is never read.
 *
 * <p>Outcomes, and the response each one becomes:
 * <ul>
 *   <li>a blank body, a body that is not one JSON document, or a body the schema rejects throws
 *       {@link SchemaValidationException}, which {@code ApiKitExceptionHandler} answers with APIkit's
 *       400 {@code { "message": "Bad request" }};</li>
 *   <li>a missing, unreadable or invalid schema is a defect of the application, not of the request,
 *       and throws {@link IllegalStateException}, which reaches the generic 500 handler.</li>
 * </ul>
 *
 * <p>Shared component SC-3: it depends only on the JDK, Spring Framework, Jackson and
 * {@code json-schema-validator}, and is copied unchanged, apart from its {@code package} line, into
 * every RAML-backed project. The class holds no per-request state and is safe for concurrent use.
 */
@Component
public class JsonSchemaValidator {

    private static final String CLASSPATH_PREFIX = "classpath:";

    private static final String INLINE_SCHEMA_DESCRIPTION = "(inline schema)";

    private final ResourceLoader resourceLoader;

    private final String baseDirectory;

    private final JsonSchemaFactory factory;

    private final ConcurrentHashMap<String, JsonSchema> schemasByPath = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, JsonSchema> inlineSchemas = new ConcurrentHashMap<>();

    /**
     * Creates the validator for the RAML configured as {@code apikit.raml}.
     *
     * @param ramlPath classpath location of the RAML, optionally prefixed with {@code classpath:};
     *                 the schemas it includes are looked up in the same directory
     */
    @Autowired
    public JsonSchemaValidator(@Value("${apikit.raml:leagues.raml}") String ramlPath) {
        this(ramlPath, new DefaultResourceLoader(JsonSchemaValidator.class.getClassLoader()));
    }

    JsonSchemaValidator(String ramlPath, ResourceLoader resourceLoader) {
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader");
        this.baseDirectory = directoryOf(normaliseRamlPath(Objects.requireNonNull(ramlPath, "ramlPath")));
        // APIkit 3.8 built its factory the same way: draft-03 applies to every schema whose $schema names
        // no other draft. The RAML schemas use draft-03's boolean "required", a syntax error in draft-04.
        ValidationConfiguration configuration = ValidationConfiguration.newBuilder()
                .setDefaultVersion(SchemaVersion.DRAFTV3)
                .freeze();
        this.factory = JsonSchemaFactory.newBuilder()
                .setValidationConfiguration(configuration)
                .freeze();
    }

    /**
     * Validates {@code body} against a schema file the RAML includes.
     *
     * @param schemaPath the schema's path as the RAML's {@code !include} names it, relative to the
     *                   RAML's directory, for example {@code schemas/teams-schema-output.json}
     * @param body       the raw request body; {@code null} or blank is rejected
     * @throws SchemaValidationException when the body is blank, is not one JSON document, or does not
     *                                   satisfy the schema
     * @throws IllegalStateException     when the schema is missing, unreadable or not a valid schema
     */
    public void validate(String schemaPath, String body) {
        Objects.requireNonNull(schemaPath, "schemaPath");
        String relativePath = stripLeadingSeparators(schemaPath.trim());
        if (relativePath.isEmpty() || relativePath.endsWith("/")) {
            throw new IllegalStateException("JSON schema path names no file: '" + schemaPath + "'");
        }
        String resolvedPath = StringUtils.cleanPath(baseDirectory + relativePath);
        JsonSchema schema = schemasByPath.computeIfAbsent(resolvedPath, this::loadSchemaFromClasspath);
        validateBody(schema, body, resolvedPath);
    }

    /**
     * Validates {@code body} against a schema given as JSON text, for a RAML that declares the schema
     * inline instead of including a file.
     *
     * @param schemaJson the schema document
     * @param body       the raw request body; {@code null} or blank is rejected
     * @throws SchemaValidationException when the body is blank, is not one JSON document, or does not
     *                                   satisfy the schema
     * @throws IllegalStateException     when {@code schemaJson} is not JSON or not a valid schema
     */
    public void validateAgainst(String schemaJson, String body) {
        Objects.requireNonNull(schemaJson, "schemaJson");
        JsonSchema schema = inlineSchemas.computeIfAbsent(schemaJson, this::compileInlineSchema);
        validateBody(schema, body, INLINE_SCHEMA_DESCRIPTION);
    }

    private JsonSchema loadSchemaFromClasspath(String resolvedPath) {
        Resource resource = resourceLoader.getResource(CLASSPATH_PREFIX + resolvedPath);
        if (!resource.exists()) {
            throw new IllegalStateException("JSON schema not found on classpath: " + resolvedPath);
        }
        JsonNode schemaNode;
        try (InputStream in = resource.getInputStream()) {
            schemaNode = JsonLoader.fromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "JSON schema " + resolvedPath + " could not be read: " + describe(e), e);
        }
        return buildSchema(schemaNode, resolvedPath);
    }

    private JsonSchema compileInlineSchema(String schemaJson) {
        JsonNode schemaNode;
        try {
            schemaNode = JsonLoader.fromString(schemaJson);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "JSON schema " + INLINE_SCHEMA_DESCRIPTION + " is not valid JSON: " + describe(e), e);
        }
        return buildSchema(schemaNode, INLINE_SCHEMA_DESCRIPTION);
    }

    private JsonSchema buildSchema(JsonNode schemaNode, String description) {
        // getJsonSchema accepts a syntactically invalid schema and fails only once an instance is
        // validated; checking here rejects it before it is cached, with the syntax errors as the message.
        ProcessingReport syntaxReport = factory.getSyntaxValidator().validateSchema(schemaNode);
        if (!syntaxReport.isSuccess()) {
            throw new IllegalStateException(
                    "Invalid JSON schema " + description + ": " + errorMessages(syntaxReport));
        }
        try {
            return factory.getJsonSchema(schemaNode);
        } catch (ProcessingException e) {
            throw new IllegalStateException("Invalid JSON schema " + description + ": " + e.getMessage(), e);
        }
    }

    private void validateBody(JsonSchema schema, String body, String description) {
        // Draft-03 gives a root-level "required" no meaning, so a missing body is rejected here rather
        // than left to the schema.
        if (body == null || body.isBlank()) {
            throw new SchemaValidationException(
                    "Request body is empty; schema " + description + " requires a JSON document");
        }
        JsonNode instance;
        try {
            instance = JsonLoader.fromString(body);
        } catch (IOException e) {
            throw new SchemaValidationException("Request body is not valid JSON: " + describe(e), e);
        }
        ProcessingReport report;
        try {
            report = schema.validate(instance);
        } catch (ProcessingException e) {
            throw new IllegalStateException(
                    "JSON schema " + description + " could not be applied: " + e.getMessage(), e);
        }
        if (!report.isSuccess()) {
            throw new SchemaValidationException(
                    "Request body does not match schema " + description + ": " + errorMessages(report));
        }
    }

    private static String errorMessages(ProcessingReport report) {
        StringJoiner joined = new StringJoiner("; ");
        joined.setEmptyValue("no error detail reported");
        for (ProcessingMessage message : report) {
            LogLevel level = message.getLogLevel();
            if (level == LogLevel.ERROR || level == LogLevel.FATAL) {
                joined.add(message.getMessage());
            }
        }
        return joined.toString();
    }

    /** The parser's message without Jackson's source-location suffix. */
    private static String describe(IOException e) {
        String message = e instanceof JsonProcessingException jsonError
                ? jsonError.getOriginalMessage()
                : e.getMessage();
        return message != null ? message : e.getClass().getSimpleName();
    }

    private static String normaliseRamlPath(String ramlPath) {
        String path = ramlPath.trim();
        if (path.startsWith(CLASSPATH_PREFIX)) {
            path = path.substring(CLASSPATH_PREFIX.length());
        }
        return stripLeadingSeparators(path);
    }

    private static String directoryOf(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? "" : path.substring(0, lastSlash + 1);
    }

    private static String stripLeadingSeparators(String path) {
        String stripped = path;
        while (true) {
            if (stripped.startsWith("/")) {
                stripped = stripped.substring(1);
            } else if (stripped.startsWith("./")) {
                stripped = stripped.substring(2);
            } else {
                return stripped;
            }
        }
    }

    /**
     * The request body is blank, is not one JSON document, or does not satisfy its schema: APIkit's
     * {@code BadRequestException}. The message is diagnostic only; the HTTP response is always APIkit's
     * fixed 400 body.
     */
    public static class SchemaValidationException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SchemaValidationException(String message) {
            super(message);
        }

        public SchemaValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
