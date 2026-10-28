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
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * the classpath: the console root, the RAML text and directory, its {@code !include} targets and the console files
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

    /** A top-level {@code baseUri:} line of the RAML, up to its line terminator. */
    private static final Pattern TOP_LEVEL_BASE_URI = Pattern.compile("^baseUri:[^\\r\\n]*", Pattern.MULTILINE);

    /** A top-level {@code title:} line of the RAML, up to its line terminator. */
    private static final Pattern TOP_LEVEL_TITLE = Pattern.compile("^title:[^\\r\\n]*", Pattern.MULTILINE);

    /** The first line of the RAML, up to its line terminator. */
    private static final Pattern FIRST_LINE = Pattern.compile("\\A[^\\r\\n]*");

    /** Shortest {@code application.yml} line whose presence in a response counts as a leak of that file. */
    private static final int LEAK_LINE_MIN_LENGTH = 10;

    /** Methods that send {@link #JSON_BODY}, as a client writing a resource would. */
    private static final Set<String> METHODS_WITH_BODY = Set.of("POST", "PUT", "PATCH");

    /** A small JSON request body. */
    private static final String JSON_BODY = "{ \"id\": \"ABC\" }";

    /** The {@code Origin} of a cross-origin request. */
    private static final String ORIGIN = "https://example.invalid";

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

    /**
     * {@code ConsoleHandler} redirects the console path without its trailing slash to the absolute URL of the
     * console root, built from the scheme and the {@code Host} header, with an empty body and no
     * {@code Access-Control-Allow-Origin}, since it returned from the redirect before setting that header; the
     * console root it redirects to, a successful console response, carries it.
     */
    @Test
    void consoleWithoutTrailingSlashRedirects() {
        HttpTestClient.Response response = client.get(consoleRoot);

        assertEquals(301, response.status(), response::toString);
        assertEquals("Moved Permanently", response.reason(), response::toString);
        assertEquals("http://localhost:" + port + consoleRoot + "/", response.header("Location"),
                response::toString);
        assertEquals(List.of(), response.headers("Access-Control-Allow-Origin"), response::toString);
        assertEquals("0", response.header("Content-Length"), response::toString);
        assertEquals(0, response.body().length, response::toString);

        HttpTestClient.Response page = client.get(consoleRoot + "/");
        assertEquals(200, page.status(), page::toString);
        assertEquals(List.of("*"), page.headers("Access-Control-Allow-Origin"), page::toString);
    }

    /**
     * A {@code Host} header with a legal name keeps its port, or its lack of one, where APIkit copied the header:
     * in the redirect's {@code Location}, its query string kept, and in the {@code baseUri} of the API root's
     * RAML. The console's own RAML keeps the listener's local port, as {@code getApikitRamlConsole} did.
     */
    @ParameterizedTest(name = "Host: {0}")
    @CsvSource({
            "example.com:9999,  example.com:9999,  example.com",
            "localhost:18080,   localhost:18080,   localhost",
            "example.com,       example.com,       example.com",
            "example.com:,      example.com,       example.com",
            "[::1]:9999,        [::1]:9999,        [::1]",
            "[::1],             [::1],             [::1]"
    })
    void hostHeaderAuthorityIsKept(String hostHeader, String authority, String hostLiteral) {
        Map<String, String> host = Map.of("Host", hostHeader);

        HttpTestClient.Response redirect = client.get(consoleRoot + "?foo=bar", host);
        assertEquals(301, redirect.status(), redirect::toString);
        assertEquals("http://" + authority + consoleRoot + "/?foo=bar", redirect.header("Location"),
                redirect::toString);

        HttpTestClient.Response routerRaml = client.get(basePath + "/", Map.of("Host", hostHeader,
                "Accept", RAML_MEDIA_TYPE));
        assertEquals(200, routerRaml.status(), routerRaml::toString);
        assertEquals("http://" + authority + basePath, baseUri(routerRaml), routerRaml::toString);

        HttpTestClient.Response consoleRaml = client.get(consoleRoot + "/?raml", host);
        assertEquals(200, consoleRaml.status(), consoleRaml::toString);
        assertEquals("http://" + hostLiteral + ":" + port + basePath, baseUri(consoleRaml), consoleRaml::toString);
    }

    /**
     * A {@code Host} header whose port is no port number up to 65535 keeps its legal name with the listener's
     * local port, and one with an illegal name gives the listener socket's loopback address and local port, so
     * the rejected bytes reach neither the redirect's {@code Location} nor the API root's RAML.
     */
    @ParameterizedTest(name = "Host: {0}")
    @CsvSource({
            "example.com:99999,  example.com, 99999",
            "example.com:65536,  example.com, 65536",
            "example.com:abc,    example.com, abc",
            "example.com:80:80,  example.com, :80:80",
            "'bad!host:9999',    ,            bad!host",
            "'[abc:9999',        ,            abc"
    })
    void illegalHostHeaderFallsBackToListenerSocket(String hostHeader, String keptName, String rejected)
            throws UnknownHostException {
        HttpTestClient.Response redirect = client.get(consoleRoot + "?foo=bar", Map.of("Host", hostHeader));
        assertEquals(301, redirect.status(), redirect::toString);
        String location = redirect.header("Location");
        assertNotNull(location, redirect::toString);
        assertListenerUrl(location, keptName, rejected, consoleRoot + "/?foo=bar");

        HttpTestClient.Response routerRaml = client.get(basePath + "/", Map.of("Host", hostHeader,
                "Accept", RAML_MEDIA_TYPE));
        assertEquals(200, routerRaml.status(), routerRaml::toString);
        assertListenerUrl(baseUri(routerRaml), keptName, rejected, basePath);
    }

    /**
     * {@code RamlDescriptorHandler} served the API root's RAML to GET alone, so a HEAD asking for it falls through
     * to APIkit's 404.
     */
    @Test
    void headOfApiRootIsResourceNotFound() {
        HttpTestClient.Response response = client.send("HEAD", basePath + "/", Map.of("Accept", RAML_MEDIA_TYPE),
                null);

        assertEquals(404, response.status(), response::toString);
        assertEquals("Not Found", response.reason(), response::toString);
        assertContentType(response, MediaType.APPLICATION_JSON_VALUE);
        assertFalse(response.header("Content-Type").contains(RAML_MEDIA_TYPE), response::toString);
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
     * Every file the RAML {@code !include}s is served relative to the API base path too, with the bytes the RAML's
     * directory holds, and a HEAD gets the GET's status without its body. A RAML without includes has nothing to
     * serve there.
     */
    @ParameterizedTest(name = "{0} <base>/<include>")
    @ValueSource(strings = {"GET", "HEAD"})
    void everyRamlIncludeIsServedRelativeToApiBase(String method) throws IOException {
        for (String include : ramlIncludes()) {
            String path = basePath + "/" + include;
            byte[] expected = "HEAD".equals(method) ? new byte[0] : classpathBytes(ramlDir + include);

            HttpTestClient.Response response = client.send(method, path, null, null);

            assertEquals(200, response.status(), () -> method + " " + path + "\n" + response);
            assertEquals("OK", response.reason(), () -> method + " " + path + "\n" + response);
            assertArrayEquals(expected, response.body(), () -> method + " " + path + "\n" + response);
        }
    }

    /**
     * Every method but GET and HEAD on each file the RAML {@code !include}s, relative to the API base path, is
     * APIkit's unknown resource: its 404 mapping, body byte for byte, rather than Spring's own 200 with
     * {@code Allow} for OPTIONS or a 405 for the others, a non-standard method and the dispatched TRACE included.
     * A RAML without includes has nothing to probe.
     */
    @ParameterizedTest(name = "{0} <base>/<include>")
    @ValueSource(strings = {"OPTIONS", "POST", "PUT", "DELETE", "PATCH", "PROPFIND", "TRACE"})
    void otherMethodOnRamlIncludeRelativeToApiBaseIsResourceNotFound(String method) throws IOException {
        byte[] body = METHODS_WITH_BODY.contains(method) ? JSON_BODY.getBytes(StandardCharsets.UTF_8) : null;
        Map<String, String> headers = body == null ? null : Map.of("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        for (String include : ramlIncludes()) {
            String path = basePath + "/" + include;

            HttpTestClient.Response response = client.send(method, path, headers, body);

            assertIncludeNotFound(method + " " + path, response);
        }
    }

    /**
     * A CORS pre-flight to each file the RAML {@code !include}s, relative to the API base path, is answered as the
     * plain OPTIONS request it is, whichever method it asks for: APIkit's 404, with no CORS header. A RAML without
     * includes has nothing to probe.
     */
    @ParameterizedTest(name = "pre-flight for {0} on <base>/<include>")
    @ValueSource(strings = {"GET", "POST"})
    void preflightToRamlIncludeRelativeToApiBaseIsResourceNotFound(String requestedMethod) throws IOException {
        Map<String, String> headers = Map.of("Origin", ORIGIN, "Access-Control-Request-Method", requestedMethod);
        for (String include : ramlIncludes()) {
            String path = basePath + "/" + include;

            HttpTestClient.Response response = client.send("OPTIONS", path, headers, null);

            assertIncludeNotFound("pre-flight for " + requestedMethod + " on " + path, response);
        }
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
     * The RAML descriptor: 200 as {@code application/raml+yaml} for any origin, carrying the whole classpath
     * RAML, every resource, method, schema and include line and every line terminator, with only its
     * {@code baseUri} line set to this listener's URL.
     */
    private void assertRamlDescriptor(HttpTestClient.Response response) throws IOException {
        assertEquals(200, response.status(), response::toString);
        assertEquals("OK", response.reason(), response::toString);
        assertContentType(response, RAML_MEDIA_TYPE);
        assertEquals("*", response.header("Access-Control-Allow-Origin"), response::toString);
        String expected = expectedRaml(ramlText(), "baseUri: http://localhost:" + port + basePath);
        assertEquals(expected, response.bodyAsString(),
                () -> "the descriptor is not the classpath RAML with only its baseUri line set\n" + response);
    }

    /**
     * {@code raml} as the descriptor must carry it, derived from the RAML text alone: its first top-level
     * {@code baseUri:} line replaced by {@code baseUriLine}; without one, {@code baseUriLine} and the anchor
     * line's own terminator inserted after the first top-level {@code title:} line, or after the first line when
     * there is no title, and appended after a {@code \n} when that line ends the text unterminated. Every other
     * character, line terminators and trailing blanks included, is kept.
     */
    private static String expectedRaml(String raml, String baseUriLine) {
        Matcher baseUri = TOP_LEVEL_BASE_URI.matcher(raml);
        if (baseUri.find()) {
            return raml.substring(0, baseUri.start()) + baseUriLine + raml.substring(baseUri.end());
        }
        Matcher anchor = TOP_LEVEL_TITLE.matcher(raml);
        if (!anchor.find()) {
            anchor = FIRST_LINE.matcher(raml);
            assertTrue(anchor.find(), "the RAML has no first line");
        }
        int lineEnd = anchor.end();
        if (lineEnd == raml.length()) {
            return raml + "\n" + baseUriLine;
        }
        String terminator = raml.startsWith("\r\n", lineEnd) ? "\r\n" : raml.substring(lineEnd, lineEnd + 1);
        int insertAt = lineEnd + terminator.length();
        return raml.substring(0, insertAt) + baseUriLine + terminator + raml.substring(insertAt);
    }

    /** The value of the RAML's first {@code baseUri:} line. */
    private static String baseUri(HttpTestClient.Response response) {
        String prefix = "baseUri: ";
        String line = LINE_BREAK.splitAsStream(response.bodyAsString()).filter(text -> text.startsWith(prefix))
                .findFirst().orElse(null);
        assertNotNull(line, () -> "no baseUri line in the RAML\n" + response);
        return line.substring(prefix.length());
    }

    /**
     * {@code url} is this listener's {@code http} URL ending in {@code pathAndQuery}, with the local port, the
     * host {@code keptName} or, when that is {@code null}, a loopback address, and without {@code rejected}.
     */
    private void assertListenerUrl(String url, String keptName, String rejected, String pathAndQuery)
            throws UnknownHostException {
        assertFalse(url.contains(rejected), () -> url + " contains the rejected " + rejected);
        assertTrue(url.endsWith(":" + port + pathAndQuery), () -> url + " does not end with :" + port + pathAndQuery);
        URI uri = URI.create(url);
        assertEquals("http", uri.getScheme(), url);
        assertEquals(port, uri.getPort(), url);
        if (keptName != null) {
            assertEquals(keptName, uri.getHost(), url);
        } else {
            assertTrue(InetAddress.getByName(uri.getHost()).isLoopbackAddress(), () -> url + " names no loopback");
        }
    }

    private static void assertContentType(HttpTestClient.Response response, String expected) {
        String actual = response.header("Content-Type");
        assertNotNull(actual, () -> "no Content-Type\n" + response);
        assertTrue(MediaType.parseMediaType(actual).equalsTypeAndSubtype(MediaType.parseMediaType(expected)),
                () -> "Content-Type " + actual + " is not " + expected + "\n" + response);
    }

    /**
     * {@code response} to {@code request} is APIkit's 404 mapping as {@code application/json}, body byte for byte,
     * without the {@code Allow}, {@code Access-Control-*} or {@code Vary} headers of Spring's own OPTIONS and CORS
     * handling.
     */
    private static void assertIncludeNotFound(String request, HttpTestClient.Response response) {
        assertEquals(404, response.status(), () -> request + "\n" + response);
        assertEquals("Not Found", response.reason(), () -> request + "\n" + response);
        assertEquals(MediaType.APPLICATION_JSON_VALUE, response.header("Content-Type"),
                () -> request + "\n" + response);
        assertEquals(NOT_FOUND, response.bodyAsString(), () -> request + "\n" + response);
        assertTrue(response.headers().stream().map(Map.Entry::getKey).noneMatch(name ->
                        name.equalsIgnoreCase("Allow") || name.equalsIgnoreCase("Vary")
                                || name.regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length())),
                () -> request + " carries an Allow, Access-Control-* or Vary header\n" + response);
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

    /** Every distinct target the RAML {@code !include}s, in encounter order; comment lines declare none. */
    private List<String> ramlIncludes() throws IOException {
        Set<String> includes = new LinkedHashSet<>();
        for (String line : LINE_BREAK.split(ramlText())) {
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            Matcher matcher = INCLUDE.matcher(line);
            while (matcher.find()) {
                includes.add(matcher.group(1));
            }
        }
        return List.copyOf(includes);
    }
}
