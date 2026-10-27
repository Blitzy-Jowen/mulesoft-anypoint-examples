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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;

import com.mulesoft.examples.mule_expression_language_basics.support.HttpTestClient;

/**
 * Wire-level controller test of {@link GreetingController}, the HTTP trigger of the six flows of
 * mule-expression-language-basics/src/main/app/greeting.xml:4-83, on the application's own Undertow listener.
 *
 * <p>Each endpoint gets its happy path for {@code GET}, {@code POST} and {@code OPTIONS} with the inputs of the
 * original {@code MuleExpressionLanguageBasicsIT}, asserting the reply and, for {@code greet3} to {@code greet6}, the
 * content of the single file the call writes; then its error cases: the flows' own failures (an age MEL cannot
 * compare with 18, a body {@code xpath()} or {@code json-to-object-transformer} cannot read) answered by the SC-1
 * JSON 500, the no-listener 404 of every path next to a listener, and the 400 a query that cannot be decoded gets
 * before any flow runs. The {@code greet2} branch and the {@code http.query.params} semantics the flows greet with
 * are covered as well. The dispatch of every other method, {@code HEAD} and form bodies is the subject of
 * {@code GreetingListenerBoundaryTest}.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, so status line, headers and body bytes are
 * asserted exactly as the listener writes them. {@code greeting.output-dir} lies under a JUnit temporary directory,
 * emptied around every case; file names are random, so only the count and the content of the files are checked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GreetingControllerTest {

    /** The query of the original test's {@code greet1} to {@code greet4} requests. */
    private static final String QUERY = "?username=Mule&age=1";

    private static final String REPLY = "Hello Mule";

    private static final String NO_USERNAME = "No username provided";

    /** The trimmed file content the original test expects from {@code greet3}, {@code greet5} and {@code greet6}. */
    private static final String LINE = "Mule, 1, false";

    /** The trimmed file content the original test expects from {@code greet4}'s header-less DataWeave CSV. */
    private static final String CSV_LINE = "Mule,1,false";

    private static final String DIR_NAME = "Path_of_your_choice";

    /** The empty String payload the original test sent to the query-driven flows. */
    private static final byte[] EMPTY = new byte[0];

    /** The {@code Content-Type} a Mule client String payload carried. */
    private static final Map<String, String> TEXT_PLAIN = Map.of("Content-Type", MediaType.TEXT_PLAIN_VALUE);

    private static final Map<String, String> NO_HEADERS = Map.of();

    /** The directory the flows' relative {@code path="Path_of_your_choice"} named, under the working directory. */
    private static final Path WORKING_DIR_OUTPUT = Path.of(DIR_NAME);

    private static final Named<byte[]> MESSAGE_XML = Named.of("message.xml", resource("/message.xml"));

    private static final Named<byte[]> MESSAGE_JSON = Named.of("message.json", resource("/message.json"));

    @TempDir
    static Path tempDir;

    private static boolean workingDirOutputExisted;

    @LocalServerPort
    private int port;

    @Value("${greeting.output-dir}")
    private String configuredOutputDir;

    /**
     * Binds the flows' output directory to {@link #outputDir()}, outranking the surefire system property. The
     * supplier is read when the application context refreshes, after JUnit has injected the static {@link #tempDir}.
     */
    @DynamicPropertySource
    static void registerOutputDir(DynamicPropertyRegistry registry) {
        registry.add("greeting.output-dir", () -> outputDir().toString());
    }

    @BeforeAll
    static void recordWorkingDirOutput() {
        workingDirOutputExisted = Files.exists(WORKING_DIR_OUTPUT);
    }

    /**
     * No call of this class may write where the original wrote, in the working directory. A directory left there
     * by an earlier run outside this class is not this class's doing, so the check applies only when none existed.
     */
    @AfterAll
    static void workingDirOutputWasNotCreated() {
        if (!workingDirOutputExisted) {
            assertFalse(Files.exists(WORKING_DIR_OUTPUT),
                    () -> WORKING_DIR_OUTPUT.toAbsolutePath() + " was created by a greeting call");
        }
    }

    @BeforeEach
    @AfterEach
    void cleanOutputDir() throws IOException {
        FileSystemUtils.deleteRecursively(outputDir());
    }

    @Test
    void outputDirectoryIsTheTemporaryDirectory() {
        assertEquals(outputDir().toString(), configuredOutputDir);
    }

    /** Method, then target of the flows that write no file, sent without a body for GET, empty otherwise. */
    private static Stream<Arguments> replyOnlyHappyPaths() {
        return Stream.of("GET", "POST", "OPTIONS").flatMap(method ->
                Stream.of("/greet1" + QUERY, "/greet2" + QUERY).map(target -> Arguments.of(method, target)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("replyOnlyHappyPaths")
    void queryFlowGreetsTheUsernameWithoutWritingAFile(String method, String target) throws IOException {
        HttpTestClient.Response response = client().send(method, target, NO_HEADERS, bodyFor(method));

        assertOkReply(response, REPLY);
        assertEquals(List.of(), outputEntries(), response::toString);
    }

    /** Method, target, request headers, body and the trimmed line of the single file the call writes. */
    private static Stream<Arguments> fileWritingHappyPaths() {
        return Stream.of("GET", "POST", "OPTIONS").flatMap(method -> Stream.of(
                Arguments.of(method, "/greet3" + QUERY, NO_HEADERS, namedBodyFor(method), LINE),
                Arguments.of(method, "/greet4" + QUERY, NO_HEADERS, namedBodyFor(method), CSV_LINE),
                Arguments.of(method, "/greet5", TEXT_PLAIN, MESSAGE_XML, LINE),
                Arguments.of(method, "/greet6", TEXT_PLAIN, MESSAGE_JSON, LINE)));
    }

    /**
     * {@code OPTIONS} runs the flow like every other method, as the controller documents: the same reply and the
     * same file.
     */
    @ParameterizedTest(name = "{0} {1} body {3}")
    @MethodSource("fileWritingHappyPaths")
    void fileFlowGreetsTheUsernameAndWritesOneLine(String method, String target, Map<String, String> headers,
                                                  byte[] body, String expectedLine) throws IOException {
        HttpTestClient.Response response = client().send(method, target, headers, body);

        assertOkReply(response, REPLY);
        assertExactlyOneFile(expectedLine, response);
    }

    /**
     * The {@code empty} branch of {@code greetingFlow2}: a {@code username} that is absent, has an empty value or
     * comes without {@code =} (a {@code null} value) is empty; the original test's bare {@code greet2} call is the
     * first case.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("emptyUsernames")
    void greet2WithoutUsernameRepliesNoUsernameProvided(String method, String target) throws IOException {
        HttpTestClient.Response response = client().send(method, target, NO_HEADERS, bodyFor(method));

        assertOkReply(response, NO_USERNAME);
        assertEquals(List.of(), outputEntries(), response::toString);
    }

    private static Stream<Arguments> emptyUsernames() {
        return Stream.of("GET", "POST").flatMap(method ->
                Stream.of("/greet2", "/greet2?username=", "/greet2?username", "/greet2?age=1")
                        .map(target -> Arguments.of(method, target)));
    }

    /**
     * {@code http.query.params} as the Mule listener built it from the raw query: the last of repeated pairs wins,
     * names and values are URL-decoded as UTF-8 ({@code +} included), and a pair without {@code =} holds
     * {@code null}, which a MEL template renders as {@code null}.
     */
    @ParameterizedTest(name = "GET {0} replies {1}")
    @MethodSource("querySemantics")
    void greet1GreetsTheUsernameAsTheListenerDecodedIt(String target, String expectedReply) {
        assertOkReply(client().get(target), expectedReply);
    }

    private static Stream<Arguments> querySemantics() {
        return Stream.of(
                Arguments.of("/greet1?username=a&username=b", "Hello b"),
                Arguments.of("/greet1?username=M%C3%BCle", "Hello M\u00fcle"),
                Arguments.of("/greet1?username=Mule+Soft", "Hello Mule Soft"),
                Arguments.of("/greet1?username", "Hello null"),
                Arguments.of("/greet1", "Hello null"));
    }

    /** Paths next to every listener, which match exactly, and a path no listener serves. */
    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"/greet1/x", "/greet2/x", "/greet3/x", "/greet4/x", "/greet5/x", "/greet6/x",
            "/greet1/", "/greet7"})
    void pathNoListenerServesReturnsNoListener404(String path) throws IOException {
        HttpTestClient.Response response = client().get(path);

        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.TEXT_PLAIN_VALUE), response.headers("Content-Type"), response::toString);
        assertEquals("No listener for endpoint: " + path, response.bodyAsString(), response::toString);
        assertEquals(List.of(), outputEntries(), response::toString);
    }

    /**
     * A query holding a malformed percent escape cannot be decoded into {@code http.query.params}; Undertow rejects
     * it with its own empty 400 before dispatch, as {@link GreetingController} documents, so no flow runs.
     */
    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"/greet1?username=%ZZ", "/greet2?username=%ZZ", "/greet3?username=Mule&age=%ZZ",
            "/greet4?username=%ZZ&age=1"})
    void malformedQueryIsRejectedBeforeAnyFlowRuns(String target) throws IOException {
        HttpTestClient.Response response = client().get(target);

        assertEquals("HTTP/1.1 400 Bad Request", response.statusLine(), response::toString);
        assertArrayEquals(EMPTY, response.body(), response::toString);
        assertEquals(List.of(), outputEntries(), response::toString);
    }

    /**
     * MEL cannot compare a query String that is not a plain number with 18, so {@code greet3} fails before its file
     * is written and the platform-default 500 carries the failure's message.
     */
    @Test
    void greet3WithNonNumericAgeFailsWithJson500AndWritesNoFile() throws IOException {
        HttpTestClient.Response response = client().get("/greet3?username=Mule&age=abc");

        assertJson500(response, "{ \"message\": \"uncomparable values <<abc>> and <<18>>\" }");
    }

    @Test
    void greet3WithEmptyAgeFailsWithJson500AndWritesNoFile() throws IOException {
        HttpTestClient.Response response = client().get("/greet3?username=Mule&age=");

        assertJson500Prefix(response);
    }

    /** Target, then a body the flow cannot read, then the exact 500 body naming the failure. */
    private static Stream<Arguments> unreadableBodies() {
        return Stream.of(
                Arguments.of("/greet5", Named.of("empty body", EMPTY),
                        "{ \"message\": \"greet5 requires an XML body\" }"),
                Arguments.of("/greet5", utf8("<user><username>Mule</username></user>"),
                        "{ \"message\": \"xpath('/user/age') selected 0 nodes\" }"),
                Arguments.of("/greet6", Named.of("empty body", EMPTY),
                        "{ \"message\": \"greet6 requires a JSON body\" }"),
                Arguments.of("/greet6", utf8("[1,2]"),
                        "{ \"message\": \"greet6 requires a JSON object\" }"));
    }

    @ParameterizedTest(name = "POST {0} body {1}")
    @MethodSource("unreadableBodies")
    void bodyTheFlowCannotReadFailsWithJson500AndWritesNoFile(String target, byte[] body, String expectedBody)
            throws IOException {
        HttpTestClient.Response response = client().send("POST", target, TEXT_PLAIN, body);

        assertJson500(response, expectedBody);
    }

    /** Target, then a body its parser rejects; the parser's own message text is not part of the contract. */
    private static Stream<Arguments> unparseableBodies() {
        return Stream.of(
                Arguments.of("/greet5", utf8("<user><username>Mule")),
                Arguments.of("/greet6", utf8("{\"username\":")));
    }

    @ParameterizedTest(name = "POST {0} body {1}")
    @MethodSource("unparseableBodies")
    void unparseableBodyFailsWithJson500AndWritesNoFile(String target, byte[] body) throws IOException {
        HttpTestClient.Response response = client().send("POST", target, TEXT_PLAIN, body);

        assertJson500Prefix(response);
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    /**
     * The original test sent its query-driven requests with an empty String payload; a {@code GET} is sent as
     * {@link HttpTestClient#get(String)} sends it, without a body.
     */
    private static byte[] bodyFor(String method) {
        return "GET".equals(method) ? null : EMPTY;
    }

    private static Named<byte[]> namedBodyFor(String method) {
        byte[] body = bodyFor(method);
        return Named.of(body == null ? "none" : "empty", body);
    }

    /**
     * Asserts the 200 every flow answers with. The flows end in a {@code set-payload} that declares no MIME type,
     * so the listener sends the reply's {@code Content-Length} and no {@code Content-Type}, as
     * {@link GreetingController} documents; the conversion plan's {@code text/plain;charset=UTF-8} expectation is
     * not asserted because the original sent no such header.
     */
    private static void assertOkReply(HttpTestClient.Response response, String expectedReply) {
        byte[] expected = expectedReply.getBytes(StandardCharsets.UTF_8);
        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(List.of(String.valueOf(expected.length)), response.headers("Content-Length"),
                response::toString);
        assertArrayEquals(expected, response.body(), response::toString);
    }

    /** Asserts the SC-1 500 with its exact {@code { "message": ... }} body, and that no file was written. */
    private static void assertJson500(HttpTestClient.Response response, String expectedBody) throws IOException {
        assertJson500Head(response);
        assertEquals(expectedBody, response.bodyAsString(), response::toString);
        assertEquals(List.of(), outputEntries(), response::toString);
    }

    /** Asserts the SC-1 500 whose message text is not pinned, and that no file was written. */
    private static void assertJson500Prefix(HttpTestClient.Response response) throws IOException {
        assertJson500Head(response);
        String body = response.bodyAsString();
        assertTrue(body.startsWith("{ \"message\": ") && body.endsWith(" }"), response::toString);
        assertEquals(List.of(), outputEntries(), response::toString);
    }

    private static void assertJson500Head(HttpTestClient.Response response) {
        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        assertEquals(List.of(MediaType.APPLICATION_JSON_VALUE), response.headers("Content-Type"),
                response::toString);
    }

    /**
     * Asserts that the call wrote exactly one regular file whose trimmed UTF-8 content is {@code expectedLine},
     * the original test's comparison, then empties the directory so a later call counts from zero.
     */
    private static void assertExactlyOneFile(String expectedLine, HttpTestClient.Response response)
            throws IOException {
        List<Path> entries = outputEntries();
        assertEquals(1, entries.size(), () -> entries + " after " + response);
        Path file = entries.get(0);
        assertTrue(Files.isRegularFile(file), () -> file + " is not a regular file");
        assertEquals(expectedLine, Files.readString(file, StandardCharsets.UTF_8).trim(), response::toString);
        FileSystemUtils.deleteRecursively(outputDir());
    }

    /**
     * Every entry of the output directory, or none when it does not exist. Every entry is counted, as the original
     * test counted them, so a stray temporary file or directory fails the count too.
     */
    private static List<Path> outputEntries() throws IOException {
        Path dir = outputDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> listing = Files.list(dir)) {
            return listing.toList();
        }
    }

    private static Path outputDir() {
        return tempDir.resolve(DIR_NAME);
    }

    private static Named<byte[]> utf8(String body) {
        return Named.of(body, body.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] resource(String name) {
        try (InputStream in = GreetingControllerTest.class.getResourceAsStream(name)) {
            assertNotNull(in, () -> "missing test resource " + name);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read test resource " + name, e);
        }
    }
}
