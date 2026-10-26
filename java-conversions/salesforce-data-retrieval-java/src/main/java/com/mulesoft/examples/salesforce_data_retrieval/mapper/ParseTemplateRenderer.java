/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.mapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Renders a classpath template as Mule 3.8's {@code parse-template} element did (shared component SC-14).
 *
 * <p>A template is read once as UTF-8 and cached; its text is never modified, so line ends, tabs and a
 * missing trailing newline reach the caller unchanged. Each original {@code #[...]} expression is replaced by
 * the value supplied under that expression's literal text, for example {@code "#[payload.username]"}. MEL and
 * Groovy are never parsed or evaluated: the caller computes every value, and nested brackets are part of the
 * key. Where two keys start at one offset the longer wins, a {@code null} value renders as {@code null}, and
 * substituted text is never rescanned.
 *
 * <p>Exceptions: {@link IllegalStateException} {@code Template not found: <location>} for a missing resource,
 * an empty location or a directory; {@link UncheckedIOException} when the resource cannot be read;
 * {@link IllegalArgumentException} for a null or empty key, or a key the template does not contain, raised
 * before any substitution; {@link IllegalStateException} {@code Unresolved expression at offset <n> in
 * template <location>} for a {@code #[} no supplied key covers, {@code n} being its 0-based character offset.
 * A failed load is not cached, so a later call retries it.
 *
 * <p>The class depends on the JDK and Spring alone. Its consumers ({@code login-form-using-the-http-connector},
 * {@code http-multipart-request}, {@code salesforce-data-retrieval}, {@code get-customer-list-from-netsuite},
 * {@code processing-orders-with-dataweave-and-APIkit}) copy it, with its {@code ParseTemplateRendererTest},
 * changing only the {@code package} line.
 */
@Component
public class ParseTemplateRenderer {

    /** Opening of a Mule expression; one that no supplied key covers is an error. */
    static final String EXPRESSION_START = "#[";

    private final ClassLoader classLoader;
    private final ConcurrentHashMap<String, String> templates = new ConcurrentHashMap<>();

    /** Reads templates through the class loader that loaded this class. */
    public ParseTemplateRenderer() {
        this(ParseTemplateRenderer.class.getClassLoader());
    }

    /** Reads templates through {@code classLoader}. */
    public ParseTemplateRenderer(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    /**
     * Renders a template that holds no expression, returning the cached text itself.
     *
     * @param location classpath location such as {@code login/index.html}; one leading {@code /} is ignored
     */
    public String render(String location) {
        return render(location, Map.of());
    }

    /**
     * Renders a template, replacing each supplied expression key with {@code String.valueOf} of its value.
     *
     * @param location         classpath location; one leading {@code /} is ignored
     * @param expressionValues value per literal expression text, such as {@code #[payload.username]}
     */
    public String render(String location, Map<String, ?> expressionValues) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(expressionValues, "expressionValues");
        String normalized = location.startsWith("/") ? location.substring(1) : location;
        String template = templates.computeIfAbsent(normalized, key -> read(key, location));
        for (String key : expressionValues.keySet()) {
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException(
                        "Expression key must be a non-empty string (template " + location + ")");
            }
            if (!template.contains(key)) {
                throw new IllegalArgumentException("Expression " + key + " does not occur in template " + location);
            }
        }
        if (expressionValues.isEmpty() && template.indexOf(EXPRESSION_START) < 0) {
            return template;
        }
        List<String> keys = new ArrayList<>(expressionValues.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder out = new StringBuilder(template.length() + 64);
        int i = 0;
        while (i < template.length()) {
            String key = keyAt(keys, template, i);
            if (key != null) {
                Object value = expressionValues.get(key);
                out.append(String.valueOf(value));
                i += key.length();
            } else if (template.startsWith(EXPRESSION_START, i)) {
                throw new IllegalStateException("Unresolved expression at offset " + i + " in template " + location);
            } else {
                out.append(template.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static String keyAt(List<String> keysLongestFirst, String template, int offset) {
        for (String key : keysLongestFirst) {
            if (template.startsWith(key, offset)) {
                return key;
            }
        }
        return null;
    }

    private String read(String normalized, String location) {
        // A class loader may answer "" or a directory with a listing stream; neither is a template.
        if (normalized.isEmpty() || normalized.endsWith("/")) {
            throw new IllegalStateException("Template not found: " + location);
        }
        InputStream stream = classLoader.getResourceAsStream(normalized);
        if (stream == null) {
            throw new IllegalStateException("Template not found: " + location);
        }
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Template could not be read: " + location, e);
        }
    }
}
