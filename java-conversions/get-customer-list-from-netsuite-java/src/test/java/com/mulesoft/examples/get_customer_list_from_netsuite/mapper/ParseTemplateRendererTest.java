/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reference test of shared component SC-14, {@link ParseTemplateRenderer}, the replacement for Mule's
 * {@code parse-template}.
 *
 * <p>Every consumer of SC-14 copies this class with only its {@code package} line changed, so it uses nothing
 * but the JDK, JUnit 5, AssertJ and the class under test: no Spring context and no project resource. Templates
 * are written to a temporary directory and read through a class loader whose parent is {@code null}, so no
 * resource on the test classpath can satisfy a lookup.
 */
class ParseTemplateRendererTest {

    /** The NetSuite customer-list expression, holding a literal backslash-n and nested brackets. */
    private static final String GROOVY_KEY = "#[groovy: payload.tokenize('\\n')[1..-1].join('\\n')]";

    /** A real prefix of {@link #GROOVY_KEY} that ends at its first {@code ]}. */
    private static final String GROOVY_PREFIX_KEY = "#[groovy: payload.tokenize('\\n')[1..-1]";

    @TempDir
    Path root;

    URLClassLoader loader;

    ParseTemplateRenderer renderer;

    @BeforeEach
    void createRenderer() throws IOException {
        loader = new URLClassLoader(new URL[] { root.toUri().toURL() }, null);
        renderer = new ParseTemplateRenderer(loader);
    }

    @AfterEach
    void closeLoader() throws IOException {
        loader.close();
    }

    @Test
    void rendersTemplateWithoutExpressionsByteForByte() throws IOException {
        // Latin, punctuation, symbol, CJK and one supplementary character: two to four UTF-8 bytes each.
        String content = "<!--\r\n"
                + "\r\n"
                + "    MuleSoft Examples\r\n"
                + "    Copyright 2014 MuleSoft, Inc.\r\n"
                + "\r\n"
                + "-->\r\n"
                + "\r\n"
                + "<html>\r\n"
                + "\t<body>\n"
                + "\t\t<p>caf\u00e9 \u2013 na\u00efve \u2713 \u65e5\u672c \uD83D\uDE00</p>\r\n"
                + "\t\t<a href=\"#top\">[top]</a> # [\r\n"
                + "\t</body>\r\n"
                + "</html>";
        assertThat(content).doesNotContain("#[");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        write("templates/fidelity.html", bytes);

        String rendered = renderer.render("templates/fidelity.html");

        assertThat(rendered.getBytes(StandardCharsets.UTF_8)).containsExactly(bytes);
        assertThat(renderer.render("templates/fidelity.html", Map.of())).isEqualTo(rendered);
    }

    @Test
    void replacesEveryOccurrenceOfAKey() throws IOException {
        write("templates/every.html",
                "\t\tUser #[payload.username] has been logged successfully!\n#[payload.username]|#[payload.username]");

        String rendered = renderer.render("templates/every.html", Map.of("#[payload.username]", "mule"));

        assertThat(rendered).isEqualTo("\t\tUser mule has been logged successfully!\nmule|mule");
    }

    @Test
    void replacesSeveralKeysInOnePass() throws IOException {
        write("templates/several.html",
                "<p>#[payload.username]</p>\r\n<select>#[message.payload]</select>\r\n<p>#[payload.username]</p>");
        Map<String, String> values = Map.of(
                "#[payload.username]", "mule",
                "#[message.payload]", "<option value=\"Account\">Account</option>");

        String rendered = renderer.render("templates/several.html", values);

        assertThat(rendered).isEqualTo("<p>mule</p>\r\n"
                + "<select><option value=\"Account\">Account</option></select>\r\n"
                + "<p>mule</p>");
    }

    @Test
    void rendersNullValueAsTheTextNull() throws IOException {
        write("templates/null.html", "<p>#[message.payload]</p><p>#[payload.count]</p>");
        Map<String, Object> values = new HashMap<>();
        values.put("#[message.payload]", null);
        values.put("#[payload.count]", 42);

        String rendered = renderer.render("templates/null.html", values);

        assertThat(rendered).isEqualTo("<p>null</p><p>42</p>");
    }

    @Test
    void replacesNestedBracketKeyWhole() throws IOException {
        write("templates/customer.html", "<tbody>" + GROOVY_KEY + "</tbody>");

        String rendered = renderer.render("templates/customer.html", Map.of(GROOVY_KEY, "<tr><td>Doe</td></tr>"));

        assertThat(rendered).isEqualTo("<tbody><tr><td>Doe</td></tr></tbody>");
    }

    @Test
    void longestKeyWinsWhenKeysOverlap() throws IOException {
        write("templates/overlap.html", "A=" + GROOVY_KEY + ";B=" + GROOVY_PREFIX_KEY + ";");
        // The shorter key is inserted first, so iteration order cannot be what selects the longer one.
        Map<String, String> values = new LinkedHashMap<>();
        values.put(GROOVY_PREFIX_KEY, "SHORT");
        values.put(GROOVY_KEY, "LONG");

        String rendered = renderer.render("templates/overlap.html", values);

        assertThat(rendered).isEqualTo("A=LONG;B=SHORT;");
    }

    @Test
    void doesNotRescanInsertedValues() throws IOException {
        write("templates/rescan.html", "[#[a]]");

        String rendered = renderer.render("templates/rescan.html", Map.of("#[a]", "#[x]"));

        assertThat(rendered).isEqualTo("[#[x]]");
    }

    @Test
    void unsuppliedExpressionFailsWithIllegalState() throws IOException {
        write("templates/unsupplied.html", "Hello #[payload.username]");
        write("templates/partial.html", "#[other] and #[payload.username]");

        assertThatIllegalStateException()
                .isThrownBy(() -> renderer.render("templates/unsupplied.html"))
                .withMessageContaining("templates/unsupplied.html");
        assertThatIllegalStateException()
                .isThrownBy(() -> renderer.render("templates/partial.html", Map.of("#[other]", "x")))
                .withMessageContaining("templates/partial.html");
    }

    @Test
    void suppliedKeyMissingFromTemplateFailsWithIllegalArgument() throws IOException {
        write("templates/absent-key.html", "Hi #[a]");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> renderer.render("templates/absent-key.html", Map.of("#[a]", "1", "#[b]", "2")));
    }

    @Test
    void missingTemplateFailsWithTemplateNotFound() {
        assertThatIllegalStateException()
                .isThrownBy(() -> renderer.render("templates/absent.html"))
                .withMessage("Template not found: templates/absent.html");
        assertThatIllegalStateException()
                .isThrownBy(() -> renderer.render("templates/absent.html", Map.of("#[a]", "x")))
                .withMessage("Template not found: templates/absent.html");
    }

    @Test
    void defaultConstructorReportsMissingTemplate() {
        String location = "parse-template-renderer-test/absent-" + UUID.randomUUID() + ".txt";

        assertThatIllegalStateException()
                .isThrownBy(() -> new ParseTemplateRenderer().render(location))
                .withMessage("Template not found: " + location);
    }

    @Test
    void leadingSlashResolvesTheSameTemplate() throws IOException {
        String content = "<p>slash</p>\r\n";
        write("templates/slash.html", content);

        // The slashed form is rendered first, so its result cannot come from the unslashed form's cache entry.
        String slashed = renderer.render("/templates/slash.html");

        assertThat(slashed).isEqualTo(content);
        assertThat(renderer.render("templates/slash.html")).isEqualTo(slashed);
    }

    @Test
    void secondRenderUsesCachedTemplateAfterFileChanges() throws IOException {
        String location = "templates/cached.html";
        Map<String, String> values = Map.of("#[v]", "1");
        write(location, "first #[v]");
        assertThat(renderer.render(location, values)).isEqualTo("first 1");

        write(location, "second #[v]");

        assertThat(renderer.render(location, values)).isEqualTo("first 1");
        assertThat(new ParseTemplateRenderer(loader).render(location, values)).isEqualTo("second 1");
        assertThatIllegalStateException()
                .isThrownBy(() -> renderer.render(location))
                .withMessageContaining(location);
    }

    private void write(String relativePath, String content) throws IOException {
        write(relativePath, content.getBytes(StandardCharsets.UTF_8));
    }

    private void write(String relativePath, byte[] content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }
}
