/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_expression_language_basics.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;

import com.mulesoft.examples.mule_expression_language_basics.support.HttpTestClient;

/**
 * Wire-level test of the listener boundary of {@link GreetingController}, the HTTP trigger of the six flows of
 * mule-expression-language-basics/src/main/app/greeting.xml:4-83, on the application's own Undertow listener.
 *
 * <p>The listeners declare no {@code allowedMethods} and every flow ends in an untyped {@code set-payload}, so every
 * method runs the flow and is answered 200 with the reply's {@code Content-Length} and no {@code Content-Type}; a
 * {@code TRACE} gets the reply rather than an echo of the request. A form-urlencoded body is never parsed before
 * dispatch, so it fails no method of the query-driven flows; {@code greet5} and {@code greet6} bind it as the
 * parameter map the Mule listener made of it, which their flows cannot read, and answer every method with the same
 * JSON 500 and no file. Every other body reaches them as bytes, whatever its {@code Content-Type}.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are
 * asserted as the listener writes them; {@code greeting.output-dir} lies under a JUnit temporary directory, whose
 * file count each case checks, the file names being random.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GreetingListenerBoundaryTest {

    /** The query of the original test's {@code greet1} to {@code greet4} requests. */
    private static final String QUERY = "?username=Mule&age=1";

    private static final byte[] REPLY = "Hello Mule".getBytes(StandardCharsets.UTF_8);

    private static final String DIR_NAME = "Path_of_your_choice";

    private static final Map<String, String> FORM =
            Map.of("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE);

    private static final Map<String, String> FORM_WITH_CHARSET =
            Map.of("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE + "; charset=UTF-8");

    /** A form body whose second value holds a malformed percent escape. */
    private static final Named<byte[]> MALFORMED_FORM =
            Named.of("a=1&b=%ZZ", "a=1&b=%ZZ".getBytes(StandardCharsets.UTF_8));

    private static final Named<byte[]> MESSAGE_XML = Named.of("message.xml", resource("/message.xml"));

    private static final Named<byte[]> MESSAGE_JSON = Named.of("message.json", resource("/message.json"));

    private static final Named<byte[]> NO_BODY = Named.of("no body", null);

    @TempDir
    static Path tempDir;

    @LocalServerPort
    private int port;

    /**
     * Binds the flows' output directory to {@link #outputDir()}. The supplier is read when the application context
     * refreshes, after JUnit has injected the static {@link #tempDir}.
     */
    @DynamicPropertySource
    static void registerOutputDir(DynamicPropertyRegistry registry) {
        registry.add("greeting.output-dir", () -> outputDir().toString());
    }

    /** Leaves no file of one case for the count of the next. */
    @AfterEach
    void clean() throws IOException {
        FileSystemUtils.deleteRecursively(outputDir());
    }

    /** Request target, then the body the flow reads, then the number of files one call writes. */
    private static Stream<Arguments> listeners() {
        return Stream.of(
                Arguments.of("/greet1" + QUERY, NO_BODY, 0),
                Arguments.of("/greet2" + QUERY, NO_BODY, 0),
                Arguments.of("/greet3" + QUERY, NO_BODY, 1),
                Arguments.of("/greet4" + QUERY, NO_BODY, 1),
                Arguments.of("/greet5", MESSAGE_XML, 1),
                Arguments.of("/greet6", MESSAGE_JSON, 1));
    }

    /**
     * Every method on every listener, without a {@code Content-Type}, and a {@code TRACE} carrying a credential
     * that an echo of the request would repeat: method, target, request headers, body, files written.
     */
    private static Stream<Arguments> everyMethod() {
        return listeners().flatMap(listener -> Stream.concat(
                Stream.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE")
                        .map(method -> Arguments.of(method, listener.get()[0], Map.of(), listener.get()[1],
                                listener.get()[2])),
                Stream.of(Arguments.of("TRACE", listener.get()[0], Map.of("Authorization", "Bearer SENTINEL"),
                        listener.get()[1], listener.get()[2]))));
    }

    /**
     * A form body, the malformed one included, on the query-driven {@code greet1} and {@code greet3}, which read no
     * body: method, target, request headers, body, files written.
     */
    private static Stream<Arguments> formBodiesOnQueryListeners() {
        return Stream.of("/greet1" + QUERY, "/greet3" + QUERY).flatMap(target ->
                Stream.of("PUT", "PATCH", "DELETE", "POST").map(method ->
                        Arguments.of(method, target, FORM, MALFORMED_FORM, target.startsWith("/greet3") ? 1 : 0)));
    }

    /**
     * The documents of {@code greet5} and {@code greet6} declared as text, as their own media type or by a
     * {@code Content-Type} that cannot be parsed: method, target, request headers, body, files written.
     */
    private static Stream<Arguments> nonFormBodies() {
        return Stream.of(
                        Arguments.of("/greet5", MESSAGE_XML, MediaType.APPLICATION_XML_VALUE),
                        Arguments.of("/greet6", MESSAGE_JSON, MediaType.APPLICATION_JSON_VALUE))
                .flatMap(document -> Stream.of(MediaType.TEXT_PLAIN_VALUE, (String) document.get()[2],
                                "not-a-media-type")
                        .flatMap(contentType -> Stream.of("POST", "PUT").map(method -> Arguments.of(method,
                                document.get()[0], Map.of("Content-Type", contentType), document.get()[1], 1))));
    }

    @ParameterizedTest(name = "{0} {1} headers {2} body {3}")
    @MethodSource({"everyMethod", "formBodiesOnQueryListeners", "nonFormBodies"})
    void requestRunsTheFlowAndGetsTheReplyWithoutContentType(String method, String target,
                                                             Map<String, String> headers, byte[] body,
                                                             int filesWritten) throws IOException {
        HttpTestClient.Response response = client().send(method, target, headers, body);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(List.of(String.valueOf(REPLY.length)), response.headers("Content-Length"), response::toString);
        assertArrayEquals(REPLY, response.body(), response::toString);
        assertEquals(filesWritten, fileCount(), response::toString);
    }

    @ParameterizedTest(name = "HEAD {0} body {1}")
    @MethodSource("listeners")
    void headRunsTheFlowAndGetsTheHeadersWithoutBody(String target, byte[] body, int filesWritten)
            throws IOException {
        HttpTestClient.Response response = client().send("HEAD", target, Map.of(), body);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(List.of("10"), response.headers("Content-Length"), response::toString);
        assertArrayEquals(new byte[0], response.body(), response::toString);
        assertEquals(filesWritten, fileCount(), response::toString);
    }

    /**
     * Form bodies to {@code greet5} and {@code greet6}, a {@code charset} parameter included, with the original
     * test's documents and with the malformed form: method, target, request headers, body, expected 500 body.
     */
    private static Stream<Arguments> formBodiesOnBodyListeners() {
        String xmlFailure = "{ \"message\": \"greet5 requires an XML body\" }";
        String jsonFailure = "{ \"message\": \"greet6 requires a JSON body\" }";
        return Stream.of(
                        Arguments.of("/greet5", FORM, MESSAGE_XML, xmlFailure),
                        Arguments.of("/greet5", FORM_WITH_CHARSET, MESSAGE_XML, xmlFailure),
                        Arguments.of("/greet5", FORM, MALFORMED_FORM, xmlFailure),
                        Arguments.of("/greet6", FORM, MESSAGE_JSON, jsonFailure),
                        Arguments.of("/greet6", FORM_WITH_CHARSET, MESSAGE_JSON, jsonFailure),
                        Arguments.of("/greet6", FORM, MALFORMED_FORM, jsonFailure))
                .flatMap(request -> Stream.of("POST", "PUT", "PATCH", "DELETE").map(method -> Arguments.of(method,
                        request.get()[0], request.get()[1], request.get()[2], request.get()[3])));
    }

    @ParameterizedTest(name = "{0} {1} headers {2} body {3}")
    @MethodSource("formBodiesOnBodyListeners")
    void formBodyFailsTheBodyFlowAlikeForEveryMethod(String method, String target, Map<String, String> headers,
                                                     byte[] body, String expectedBody) throws IOException {
        HttpTestClient.Response response = client().send(method, target, headers, body);

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.APPLICATION_JSON_VALUE), response.headers("Content-Type"),
                response::toString);
        assertArrayEquals(expectedBody.getBytes(StandardCharsets.UTF_8), response.body(), response::toString);
        assertEquals(0, fileCount(), response::toString);
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    /** The number of entries in the output directory; the file names are random, so only the count is checked. */
    private static long fileCount() throws IOException {
        Path dir = outputDir();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> listing = Files.list(dir)) {
            return listing.count();
        }
    }

    private static Path outputDir() {
        return tempDir.resolve(DIR_NAME);
    }

    private static byte[] resource(String name) {
        try (InputStream in = GreetingListenerBoundaryTest.class.getResourceAsStream(name)) {
            assertNotNull(in, () -> "missing test resource " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read test resource " + name, e);
        }
    }
}
