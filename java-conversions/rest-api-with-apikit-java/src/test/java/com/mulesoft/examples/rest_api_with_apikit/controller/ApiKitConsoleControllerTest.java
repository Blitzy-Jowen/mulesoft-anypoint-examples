/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mulesoft.examples.rest_api_with_apikit.support.HttpTestClient;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;

/**
 * Wire-level test of {@link ApiKitConsoleController}, shared component SC-17: the APIkit 3.8.0 console and RAML
 * descriptor the original {@code apikit:config consoleEnabled="true" consolePath="console"} served, reproducing
 * {@code ConsoleHandler} and {@code RamlDescriptorHandler} of {@code mule-module-apikit} 3.8.0.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which writes the request target unnormalized
 * and reads the raw status line, so status code, reason phrase, headers and body bytes are asserted as Undertow
 * writes them, and the traversal probes reach the server as sent.
 *
 * <p>The class is copied with SC-17 into every RAML-backed project and differs between copies in its
 * {@code package} line and the {@link HttpTestClient} import alone. Everything project-specific is derived at run
 * time from the {@code apikit.base-path}, {@code apikit.console-path} and {@code apikit.raml} properties and from
 * the classpath: the console root, the RAML text and directory, its first {@code !include} and the console files
 * under {@code apikit-console/}. It only reads, so it shares the cached application context of the project's
 * other {@code @SpringBootTest} classes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKitConsoleControllerTest {

    /** Body of APIkit's 404 mapping, spacing included. */
    private static final String NOT_FOUND = "{ \"message\": \"Resource not found\" }";

    private static final String RAML_MEDIA_TYPE = "application/raml+yaml";

    /** Classpath directory of the console tree copied from the {@code mule-module-apikit} 3.8.0 jar. */
    private static final String CONSOLE_DIR = "apikit-console/";

    private static final String CONSOLE_PAGE = "index.html";

    /** The console page's marker {@code ConsoleHandler} replaced with its options. */
    private static final String ATTRIBUTES_PLACEHOLDER = "console-attributes-placeholder";

    /** The options {@code ConsoleHandler} wrote in place of {@link #ATTRIBUTES_PLACEHOLDER}. */
    private static final String CONSOLE_ATTRIBUTES =
            "disable-raml-client-generator=\"\" disable-theme-switcher=\"\"";

    /** The descriptor the rendered page loads, relative to the console root (SC-17: {@code ?raml}). */
    private static final String RAML_SOURCE = "./?raml";

    private static final Pattern RAML_CONSOLE_SRC = Pattern.compile("<raml-console\\s+src=\"([^\"]*)\"");

    private static final Pattern INCLUDE = Pattern.compile("!include\\s+(\\S+)");

    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    /** Shortest {@code application.yml} line whose presence in a response counts as a leak of that file. */
    private static final int LEAK_LINE_MIN_LENGTH = 10;

    @Value("${apikit.base-path}")
    private String basePath;

    @Value("${apikit.console-path}")
    private String consolePath;

    @Value("${apikit.raml}")
    private String ramlLocation;

    @LocalServerPort
    private int port;

    private HttpTestClient client;

    /** {@code <base path>/<console path>}, without a trailing slash. */
    private String consoleRoot;

    /** The classpath directory of the RAML, with its trailing {@code /}, or {@code ""} at the classpath root. */
    private String ramlDir;

    @BeforeEach
    void setUp() {
        client = HttpTestClient.localhost(port);
        consoleRoot = basePath + "/" + consolePath;
        ramlDir = ramlLocation.substring(0, ramlLocation.lastIndexOf('/') + 1);
    }

    /** {@code ConsoleHandler} redirects the console path without its trailing slash to the console root. */
    @Test
    void consoleWithoutTrailingSlashRedirects() {
        HttpTestClient.Response response = client.get(consoleRoot);

        assertEquals(301, response.status(), response::toString);
        assertEquals("Moved Permanently", response.reason(), response::toString);
        String location = response.header("Location");
        assertNotNull(location, response::toString);
        assertTrue(location.endsWith(consoleRoot + "/"),
                () -> "Location " + location + " does not end with " + consoleRoot + "/\n" + response);
    }

    /**
     * The console root serves the jar's console page with its RAML source pointed at the descriptor and its
     * attribute placeholder replaced by {@code ConsoleHandler}'s options; nothing else of the page changes.
     */
    @Test
    void consoleIndexIsRendered() throws IOException {
        HttpTestClient.Response response = client.get(consoleRoot + "/");

        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertContentType(response, "text/html");
        assertEquals("-1", response.header("Expires"), response::toString);
        assertEquals("*", response.header("Access-Control-Allow-Origin"), response::toString);

        String body = response.bodyAsString();
        Matcher source = RAML_CONSOLE_SRC.matcher(body);
        assertTrue(source.find(), () -> "no <raml-console src=...> in the page\n" + response);
        // SC-17 points the page at the descriptor's "?raml" URL; APIkit 3.8.0 wrote "./?" (Tier 2A point).
        assertEquals(RAML_SOURCE, source.group(1), response::toString);
        assertTrue(body.contains(CONSOLE_ATTRIBUTES), response::toString);
        assertFalse(body.contains(ATTRIBUTES_PLACEHOLDER), response::toString);

        String page = new String(classpathBytes(CONSOLE_DIR + CONSOLE_PAGE), StandardCharsets.UTF_8);
        String expected = RAML_CONSOLE_SRC.matcher(page)
                .replaceFirst(Matcher.quoteReplacement("<raml-console src=\"" + RAML_SOURCE + "\""))
                .replace(ATTRIBUTES_PLACEHOLDER, CONSOLE_ATTRIBUTES);
        assertEquals(expected, body);
    }

    /**
     * Every other console file is served byte for byte with {@code ConsoleHandler}'s content type for its
     * extension, unknown extensions such as {@code .woff2} as {@code application/octet-stream}. The smallest
     * file of each directory keeps the test fast: the vendor script bundle is about 3 MB.
     */
    @ParameterizedTest(name = "{0}/{1} is served as {2}")
    @CsvSource({
            "scripts, *.js,  application/x-javascript",
            "styles,  *.css, text/css",
            "fonts,   *,     application/octet-stream"
    })
    void consoleAssetsUseApiKitContentTypes(String directory, String fileGlob, String contentType)
            throws IOException {
        Resource file = smallest("classpath:" + CONSOLE_DIR + directory + "/" + fileGlob);
        String path = consoleRoot + "/" + directory + "/" + file.getFilename();

        HttpTestClient.Response response = client.get(path);

        assertEquals(200, response.status(), () -> path + "\n" + response);
        assertEquals("OK", response.reason(), () -> path + "\n" + response);
        assertContentType(response, contentType);
        byte[] expected;
        try (InputStream in = file.getInputStream()) {
            expected = in.readAllBytes();
        }
        assertArrayEquals(expected, response.body(), () -> "body of " + path + " differs from " + file);
    }

    /** {@code RamlDescriptorHandler} answers the console root's {@code ?raml} query with the RAML. */
    @Test
    void ramlDescriptorIsServedForRamlQuery() throws IOException {
        assertRamlDescriptor(client.get(consoleRoot + "/?raml"));
    }

    /** {@code RamlDescriptorHandler} answers a GET of the API root that accepts the RAML media type with it. */
    @Test
    void ramlDescriptorIsServedForRamlAcceptHeader() throws IOException {
        assertRamlDescriptor(client.get(basePath + "/", Map.of("Accept", RAML_MEDIA_TYPE)));
    }

    /**
     * The first file the RAML {@code !include}s is served relative to the console root, where the console page
     * resolves it, with the bytes the RAML's directory holds. Comment lines declare no include.
     */
    @Test
    void firstRamlIncludeIsServedRelativeToConsole() throws IOException {
        String include = null;
        for (String line : LINE_BREAK.split(ramlText())) {
            Matcher matcher = INCLUDE.matcher(line);
            if (!line.stripLeading().startsWith("#") && matcher.find()) {
                include = matcher.group(1);
                break;
            }
        }
        Assumptions.assumeTrue(include != null, "RAML declares no !include");
        String path = consoleRoot + "/" + include;

        HttpTestClient.Response response = client.get(path);

        assertEquals(200, response.status(), () -> path + "\n" + response);
        assertEquals("OK", response.reason(), () -> path + "\n" + response);
        assertArrayEquals(classpathBytes(ramlDir + include), response.body(), () -> path + "\n" + response);
    }

    /** A console file that does not exist is APIkit's unknown resource: its 404 mapping, body byte for byte. */
    @Test
    void unknownConsoleFileIsResourceNotFound() {
        HttpTestClient.Response response = client.get(consoleRoot + "/no-such-file.js");

        assertEquals(404, response.status(), response::toString);
        assertEquals("Not Found", response.reason(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertEquals(NOT_FOUND, response.bodyAsString(), response::toString);
    }

    /**
     * A {@code ..} path below the console root, sent raw, reaches no file outside the console tree. Undertow or
     * the dispatcher may answer with APIkit's 404 or with SC-1's no-listener 404, so the status is asserted and
     * the body only for the absence of every trace of the classpath {@code application.yml}.
     */
    @ParameterizedTest(name = "<console>{0}")
    @ValueSource(strings = {"/../application.yml", "/../../application.yml"})
    void pathTraversalOutOfConsoleIsNotServed(String traversal) throws IOException {
        String path = consoleRoot + traversal;

        HttpTestClient.Response response = client.get(path);

        assertEquals(404, response.status(), () -> path + "\n" + response);
        String body = response.bodyAsString();
        String configuration = new String(classpathBytes("application.yml"), StandardCharsets.UTF_8);
        assertFalse(body.contains(configuration.strip()), () -> path + " served application.yml\n" + response);
        for (String marker : List.of("${http.port", "add-mappings")) {
            assertFalse(body.contains(marker), () -> path + " leaked " + marker + "\n" + response);
        }
        for (String line : LINE_BREAK.split(configuration)) {
            String trimmed = line.strip();
            if (trimmed.length() >= LEAK_LINE_MIN_LENGTH && !trimmed.startsWith("#")) {
                assertFalse(body.contains(trimmed),
                        () -> path + " leaked application.yml line '" + trimmed + "'\n" + response);
            }
        }
    }

    /**
     * The RAML descriptor: 200 as {@code application/raml+yaml} for any origin, the classpath RAML from its
     * version line on, with its {@code baseUri} set to this listener's URL.
     */
    private void assertRamlDescriptor(HttpTestClient.Response response) throws IOException {
        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertContentType(response, RAML_MEDIA_TYPE);
        assertEquals("*", response.header("Access-Control-Allow-Origin"), response::toString);
        String body = response.bodyAsString();
        String baseUri = "baseUri: http://localhost:" + port + basePath;
        assertTrue(LINE_BREAK.splitAsStream(body).anyMatch(baseUri::equals),
                () -> "no line '" + baseUri + "' in the RAML\n" + response);
        assertEquals(firstLine(ramlText()), firstLine(body), response::toString);
    }

    private static void assertContentType(HttpTestClient.Response response, String expected) {
        String actual = response.header("Content-Type");
        assertNotNull(actual, () -> "no Content-Type\n" + response);
        assertTrue(MediaType.parseMediaType(actual).equalsTypeAndSubtype(MediaType.parseMediaType(expected)),
                () -> "Content-Type " + actual + " is not " + expected + "\n" + response);
    }

    private static byte[] classpathBytes(String location) throws IOException {
        ClassPathResource resource = new ClassPathResource(location);
        assertTrue(resource.isReadable(), () -> "no readable classpath resource " + location);
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }

    /**
     * The readable file matching {@code pattern} with the fewest bytes, ties broken by file name. The console
     * tree is copied with SC-17 into every consumer, so a pattern matching nothing fails rather than skips.
     */
    private static Resource smallest(String pattern) throws IOException {
        Resource smallest = null;
        long smallestLength = Long.MAX_VALUE;
        for (Resource candidate : new PathMatchingResourcePatternResolver().getResources(pattern)) {
            if (!candidate.isReadable()) {
                continue;
            }
            long length = candidate.contentLength();
            if (smallest == null || length < smallestLength
                    || (length == smallestLength && candidate.getFilename().compareTo(smallest.getFilename()) < 0)) {
                smallest = candidate;
                smallestLength = length;
            }
        }
        assertNotNull(smallest, () -> "no console file matches " + pattern);
        return smallest;
    }

    private String ramlText() throws IOException {
        return new String(classpathBytes(ramlLocation), StandardCharsets.UTF_8);
    }

    private static String firstLine(String text) {
        return LINE_BREAK.split(text, 2)[0];
    }
}
