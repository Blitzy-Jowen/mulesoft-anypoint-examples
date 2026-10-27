/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_multipart_request.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.http_multipart_request.support.HttpTestClient;

/**
 * Wire-level test of {@link UploadController}, the two {@code /uploadFile} listeners of
 * {@code http-multipart-request.xml} ({@code allowedMethods="GET"}, xml:6-11, and {@code allowedMethods="POST"},
 * xml:13-29), with the whole application on Undertow at a random port and requests sent through ST-1
 * {@link HttpTestClient}, so status line, headers and body bytes are asserted as the listener writes them.
 *
 * <p>The cases cover what the listeners decide before and around the flows: methods admitted ignoring letter case, as
 * Mule 3.8's {@code MethodRequestMatcher} compared them; every other method answered with the listeners' 405; the
 * last of several parts named {@code file} uploaded, as Mule 3.8's {@code HashMap} of parts by name kept it; a
 * request without a {@code file} part answered with the 500 of the failing file-name expression; a non-ASCII file name
 * stored with each header byte read as one ISO-8859-1 character, as Mule 3.8's JavaMail parser read it; a file name
 * joined to the upload directory as Mule's file connector joined them, a leading {@code /} included and missing
 * subdirectories created; a second upload under a stored name replacing the file; a failed write answered with the
 * 500 of Mule's streaming failure, which names no path; an unserved path answered with the listeners' 404; and the
 * upload's reason phrase confined to its own exchange. Bodies A and C of {@code fixtures/SCENARIOS.md} are sent byte
 * for byte, so the scenarios {@code post-upload-file} and {@code post-upload-file-missing-part} run with the requests
 * their capture sends. Uploads are stored in a {@link TempDir}, never in the {@code /tmp} the original fixed, and the
 * directory is emptied before each case.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadControllerTest {

    private static final String PATH = "/uploadFile";

    private static final String BOUNDARY = "upload-controller-boundary-0001";

    private static final String CRLF = "\r\n";

    private static final String MULTIPART_CONTENT_TYPE = "multipart/form-data; boundary=" + BOUNDARY;

    /** Boundary of body A of {@code SCENARIOS.md}, sent by the scenario {@code post-upload-file}. */
    private static final String BODY_A_BOUNDARY = "parity-boundary-0001";

    /** Boundary of body C of {@code SCENARIOS.md}, sent by the scenario {@code post-upload-file-missing-part}. */
    private static final String BODY_C_BOUNDARY = "parity-boundary-0003";

    /** {@code reasonPhrase} of the upload listener's {@code http:response-builder} (xml:16). */
    private static final String REASON = "File was uploaded.";

    /** {@code Content-Length} of the carried {@code uploadFile.html}, byte-equal to the original's template. */
    private static final int FORM_LENGTH = 348;

    private static final byte[] METHOD_NOT_ALLOWED_BODY =
            "Method not allowed for endpoint: /uploadFile".getBytes(StandardCharsets.UTF_8);

    private static final byte[] NO_LISTENER_BODY =
            "No listener for endpoint: /unknown".getBytes(StandardCharsets.UTF_8);

    private static final byte[] MISSING_PART_BODY =
            "{ \"message\": \"Multipart part 'file' is not present in the request\" }".getBytes(StandardCharsets.UTF_8);

    /** The 500 body of a failed write: Mule 3.8.0's {@code CoreMessages.streamingFailedNoStream()} message. */
    private static final byte[] STREAMING_FAILED_BODY =
            "{ \"message\": \"Streaming failed. Could not get output stream\" }".getBytes(StandardCharsets.UTF_8);

    /** Stands, in a file name, for a temporary directory's absolute path less its leading {@code /}. */
    private static final String OUTSIDE_PLACEHOLDER = "<outside>";

    @TempDir
    static Path uploadDir;

    @LocalServerPort
    private int port;

    @Value("${upload.directory}")
    private String uploadDirectory;

    /** Points the upload flow's {@code file:outbound-endpoint} directory at the class's temporary directory. */
    @DynamicPropertySource
    static void uploadDirectory(DynamicPropertyRegistry registry) {
        registry.add("upload.directory", () -> uploadDir.toString());
    }

    @BeforeEach
    void emptyUploadDirectory() throws IOException {
        try (Stream<Path> entries = Files.walk(uploadDir)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                if (!entry.equals(uploadDir)) {
                    Files.delete(entry);
                }
            }
        }
    }

    @Test
    void uploadsAreStoredInTheTemporaryDirectory() {
        assertEquals(uploadDir.toString(), uploadDirectory);
    }

    /**
     * Bodies A and C have the lengths {@code SCENARIOS.md} gives them, and the carried {@code test.txt} and
     * {@code uploadFile.html} are the originals' files, whose MD5 digests these are.
     */
    @Test
    void scenarioBodiesAndCarriedResourcesMatchTheOriginals() throws IOException {
        assertEquals(159, bodyA().length);
        assertEquals(118, bodyC().length);
        byte[] testFile = classpathBytes("/test.txt");
        assertArrayEquals(bytes("file uploaded"), testFile);
        assertEquals("78c06ace828c8448f0878755ccad2d04", md5(testFile));
        byte[] form = classpathBytes("/uploadFile.html");
        assertEquals(FORM_LENGTH, form.length);
        assertEquals("7e90d7849338a2574566425c2ef43f4b", md5(form));
    }

    @ParameterizedTest(name = "{0} /uploadFile")
    @ValueSource(strings = {"GET", "get", "Get", "gEt"})
    void getInAnyLetterCaseRendersTheUploadForm(String method) throws IOException {
        byte[] form = classpathBytes("/uploadFile.html");

        HttpTestClient.Response response = client().send(method, PATH, null, null);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertEquals(String.valueOf(FORM_LENGTH), response.header("Content-Length"), response::toString);
        assertEquals(FORM_LENGTH, form.length);
        assertArrayEquals(form, response.body(), response::toString);
        assertEquals(List.of(), storedFiles());
    }

    @ParameterizedTest(name = "{0} /uploadFile")
    @ValueSource(strings = {"POST", "post", "Post", "pOsT"})
    void postInAnyLetterCaseStoresAndEchoesTheFilePart(String method) throws IOException {
        String fileName = "upload-" + method + ".txt";
        byte[] content = ("uploaded with " + method).getBytes(StandardCharsets.UTF_8);

        HttpTestClient.Response response = client().send(method, PATH, multipartHeaders(),
                multipart(new BodyPart("file", fileName, "text/plain", content)));

        assertEquals("HTTP/1.1 200 File was uploaded.", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertArrayEquals(content, response.body(), response::toString);
        assertEquals(List.of(fileName), storedFiles());
        assertArrayEquals(content, Files.readAllBytes(uploadDir.resolve(fileName)));
    }

    /**
     * Scenario {@code post-upload-file}: body A, the original IT's {@code test.txt} as the part {@code file}, is
     * stored as {@code test.txt} and echoed under the response builder's status line.
     */
    @Test
    void bodyAStoresTestTxtAndEchoesItWithTheUploadReasonPhrase() throws IOException {
        byte[] content = classpathBytes("/test.txt");

        HttpTestClient.Response response = client().send("POST", PATH, multipartHeaders(BODY_A_BOUNDARY), bodyA());

        assertEquals("HTTP/1.1 200 " + REASON, response.statusLine(), response::toString);
        assertEquals(200, response.status(), response::toString);
        assertEquals(REASON, response.reason(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertArrayEquals(content, response.body(), response::toString);
        assertEquals(List.of("test.txt"), storedFiles());
        assertArrayEquals(content, Files.readAllBytes(uploadDir.resolve("test.txt")));
    }

    /**
     * A second upload under a stored name replaces the file, as the {@code file:outbound-endpoint} overwrote it: the
     * shorter second content leaves nothing of the first behind.
     */
    @Test
    void uploadUnderAStoredNameReplacesTheFile() throws IOException {
        HttpTestClient.Response first = client().send("POST", PATH, multipartHeaders(),
                multipart(new BodyPart("file", "overwrite.txt", "text/plain", bytes("first version"))));
        assertEquals("HTTP/1.1 200 " + REASON, first.statusLine(), first::toString);
        assertArrayEquals(bytes("first version"), Files.readAllBytes(uploadDir.resolve("overwrite.txt")));

        HttpTestClient.Response second = client().send("POST", PATH, multipartHeaders(),
                multipart(new BodyPart("file", "overwrite.txt", "text/plain", bytes("second"))));

        assertEquals("HTTP/1.1 200 " + REASON, second.statusLine(), second::toString);
        assertNull(second.header("Content-Type"), second::toString);
        assertArrayEquals(bytes("second"), second.body(), second::toString);
        assertEquals(List.of("overwrite.txt"), storedFiles());
        assertArrayEquals(bytes("second"), Files.readAllBytes(uploadDir.resolve("overwrite.txt")));
    }

    /**
     * Non-ASCII file names: a label, the multipart {@code Content-Type}, the raw bytes sent as the part's
     * {@code filename} and the name the file is stored under. Mule 3.8 parsed the parts with JavaMail's
     * {@code MimeMultipart}, whose {@code InternetHeaders} read each header byte as one ISO-8859-1 character whatever
     * charset the request named: the windows-1252 bytes a browser sends for {@code é} and {@code ü} become those
     * characters, and each byte of a UTF-8 name becomes a character of its own. The last two cases name
     * {@code charset=UTF-8} on the multipart {@code Content-Type}, which that parser did not read either.
     */
    private static Stream<Arguments> nonAsciiFileNames() {
        String utf8ContentType = MULTIPART_CONTENT_TYPE + "; charset=UTF-8";
        return Stream.of(
                Arguments.of("caf<E9>-latin1.txt", MULTIPART_CONTENT_TYPE,
                        "caf\u00e9-latin1.txt".getBytes(StandardCharsets.ISO_8859_1), "caf\u00e9-latin1.txt"),
                Arguments.of("my file <FC>.txt", MULTIPART_CONTENT_TYPE,
                        "my file \u00fc.txt".getBytes(StandardCharsets.ISO_8859_1), "my file \u00fc.txt"),
                Arguments.of("na<C3 AF>ve.txt", MULTIPART_CONTENT_TYPE,
                        "na\u00efve.txt".getBytes(StandardCharsets.UTF_8), "na\u00c3\u00afve.txt"),
                Arguments.of("na<C3 AF>ve.txt", utf8ContentType,
                        "na\u00efve.txt".getBytes(StandardCharsets.UTF_8), "na\u00c3\u00afve.txt"),
                Arguments.of("caf<E9>-latin1.txt", utf8ContentType,
                        "caf\u00e9-latin1.txt".getBytes(StandardCharsets.ISO_8859_1), "caf\u00e9-latin1.txt"));
    }

    @ParameterizedTest(name = "file name bytes {0} with Content-Type {1}")
    @MethodSource("nonAsciiFileNames")
    void nonAsciiFileNameIsDecodedAsIso88591(String label, String contentType, byte[] fileName, String storedName)
            throws IOException {
        // Latin-1 and UTF-8 bytes in the content too, which is stored and echoed as sent, never decoded.
        byte[] content = {'c', 'a', 'f', (byte) 0xE9, ' ', (byte) 0xC3, (byte) 0xAF, ' ', (byte) 0xFC, '\r', '\n'};
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);

        HttpTestClient.Response response = client().send("POST", PATH, headers,
                multipartWithFileName(fileName, content));

        assertEquals("HTTP/1.1 200 File was uploaded.", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertArrayEquals(content, response.body(), response::toString);
        assertEquals(List.of(storedName), storedFiles());
        assertArrayEquals(content, Files.readAllBytes(uploadDir.resolve(storedName)));
    }

    /**
     * Methods neither listener accepts; each request carries a {@code file} part, so a flow that ran would store it.
     * {@code HEAD} in any letter case is covered by the two body-less cases below.
     */
    @ParameterizedTest(name = "{0} /uploadFile")
    @ValueSource(strings = {"PUT", "put", "DELETE", "Delete", "PATCH", "patch", "TRACE", "trace", "OPTIONS",
            "options", "Options"})
    void otherMethodsGetTheListenerMethodNotAllowed(String method) throws IOException {
        HttpTestClient.Response response = client().send(method, PATH, multipartHeaders(),
                multipart(new BodyPart("file", "rejected.txt", "text/plain", bytes("rejected"))));

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertEquals("text/plain", response.header("Content-Type"), response::toString);
        assertArrayEquals(METHOD_NOT_ALLOWED_BODY, response.body(), response::toString);
        assertEquals(List.of(), storedFiles());
    }

    @Test
    void headGetsTheListenerMethodNotAllowedWithoutABody() throws IOException {
        HttpTestClient.Response response = client().send("HEAD", PATH, null, null);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertEquals(0, response.body().length, response::toString);
        assertEquals(List.of(), storedFiles());
    }

    /**
     * {@code HEAD} in other letter cases reaches the controller, which rejects it as it rejects {@code PUT}, while
     * Undertow, comparing method tokens ignoring case, sends that 405 as the response to a {@code HEAD}: the head with
     * the {@code Content-Type} and the {@code Content-Length} of the omitted body, and no body. ST-1 omits the body
     * for the exact token {@code HEAD} only, so the exchange is read here to the end of the stream.
     */
    @ParameterizedTest(name = "{0} /uploadFile")
    @ValueSource(strings = {"head", "Head"})
    void headInOtherLetterCasesGetsTheListenerMethodNotAllowedWithoutABody(String method) throws IOException {
        String raw = new String(sendWithoutRequestBody(method), StandardCharsets.ISO_8859_1);

        int headEnd = raw.indexOf(CRLF + CRLF);
        assertTrue(headEnd > 0, raw);
        assertEquals(headEnd + 2 * CRLF.length(), raw.length(), raw);
        List<String> lines = List.of(raw.substring(0, headEnd).split(CRLF));
        assertEquals("HTTP/1.1 405 Method Not Allowed", lines.get(0), raw);
        assertTrue(lines.stream().anyMatch(line -> line.equalsIgnoreCase("Content-Type: text/plain")), raw);
        assertTrue(lines.stream().anyMatch(line -> line.equalsIgnoreCase(
                "Content-Length: " + METHOD_NOT_ALLOWED_BODY.length)), raw);
        assertEquals(List.of(), storedFiles());
    }

    /**
     * Requests carrying several parts named {@code file}, the last one being the expected upload, with the method in
     * two letter cases.
     */
    private static Stream<Arguments> repeatedFileParts() {
        return Stream.of(
                Arguments.of("POST", List.of(
                        new BodyPart("file", "first.txt", "text/plain", bytes("AAA")),
                        new BodyPart("file", "second.txt", "text/plain", bytes("BBB")))),
                Arguments.of("post", List.of(
                        new BodyPart("file", "first.txt", "text/plain", bytes("AAA")),
                        new BodyPart("description", null, null, bytes("between the files")),
                        new BodyPart("file", "third.txt", "application/octet-stream", bytes("CCC")))),
                Arguments.of("Post", List.of(
                        new BodyPart("file", "one.txt", "text/plain", bytes("111")),
                        new BodyPart("file", "two.txt", "text/plain", bytes("222")),
                        new BodyPart("file", "three.txt", "text/plain", bytes("333")),
                        new BodyPart("description", null, null, bytes("after the files")))));
    }

    @ParameterizedTest(name = "{0} /uploadFile with parts {1}")
    @MethodSource("repeatedFileParts")
    void lastOfSeveralFilePartsIsStoredAndEchoed(String method, List<BodyPart> parts) throws IOException {
        BodyPart last = parts.stream().filter(part -> part.name().equals("file")).reduce((first, second) -> second)
                .orElseThrow();

        HttpTestClient.Response response = client().send(method, PATH, multipartHeaders(),
                multipart(parts.toArray(BodyPart[]::new)));

        assertEquals("HTTP/1.1 200 File was uploaded.", response.statusLine(), response::toString);
        assertNull(response.header("Content-Type"), response::toString);
        assertArrayEquals(last.content(), response.body(), response::toString);
        assertEquals(List.of(last.fileName()), storedFiles());
        assertArrayEquals(last.content(), Files.readAllBytes(uploadDir.resolve(last.fileName())));
    }

    /**
     * Requests without a {@code file} part: body C of the scenario {@code post-upload-file-missing-part}, other
     * multipart bodies of a text field only, and plain text bodies, which Spring resolves to no part at all.
     */
    private static Stream<Arguments> requestsWithoutFilePart() {
        byte[] descriptionOnly = multipart(new BodyPart("description", null, null, bytes("no file here")));
        return Stream.of(
                Arguments.of("POST", "multipart/form-data; boundary=" + BODY_C_BOUNDARY, bodyC()),
                Arguments.of("POST", MULTIPART_CONTENT_TYPE, descriptionOnly),
                Arguments.of("post", MULTIPART_CONTENT_TYPE, descriptionOnly),
                Arguments.of("POST", "text/plain", bytes("x")),
                Arguments.of("Post", "text/plain", bytes("file uploaded")));
    }

    @ParameterizedTest(name = "{0} /uploadFile with Content-Type {1}")
    @MethodSource("requestsWithoutFilePart")
    void uploadWithoutFilePartGetsTheUnhandledExceptionResponse(String method, String contentType, byte[] body)
            throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);

        HttpTestClient.Response response = client().send(method, PATH, headers, body);

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        String responseType = response.header("Content-Type");
        assertNotNull(responseType, response::toString);
        assertTrue(responseType.startsWith("application/json"), response::toString);
        assertArrayEquals(MISSING_PART_BODY, response.body(), response::toString);
        assertEquals(List.of(), storedFiles());
    }

    /**
     * File names that lead into a directory that does not exist yet, {@code <outside>} standing for the absolute
     * path, less its leading {@code /}, of a temporary directory outside the upload directory: an absolute
     * path naming that directory, the same after two slashes, and a nested name whose parent is missing.
     *
     * @return description, file name with {@code <outside>} not yet replaced
     */
    private static Stream<Arguments> fileNamesJoinedToTheUploadDirectory() {
        return Stream.of(
                Arguments.of("absolute path of another directory", "/" + OUTSIDE_PLACEHOLDER + "/qa-absolute.txt"),
                Arguments.of("two leading slashes", "//" + OUTSIDE_PLACEHOLDER + "/qa-absolute.txt"),
                Arguments.of("nested name whose parent is missing", "qa-sub/qa-nested.txt"));
    }

    /**
     * The file name is joined to the upload directory as Mule's file connector joined them,
     * {@code address + "/" + filename} normalised by {@link java.io.File}, which {@link Path#of(String, String...)}
     * reproduces: a leading {@code /} does not take the file out of the upload directory, whose missing
     * subdirectories are created, and the directory the name spells stays empty.
     *
     * @param description      the case, for the test name
     * @param fileNameTemplate the file name, {@code <outside>} standing for {@code outside} less its leading
     *                         {@code /}
     * @param outside          a temporary directory outside the upload directory
     * @throws IOException when the request or a file operation of the test fails
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fileNamesJoinedToTheUploadDirectory")
    void fileNameIsStoredUnderTheUploadDirectory(String description, String fileNameTemplate, @TempDir Path outside)
            throws IOException {
        String fileName = fileNameTemplate.replace(OUTSIDE_PLACEHOLDER, outside.toString().substring(1));
        byte[] content = bytes("QA_SECURITY_CONTENT");

        HttpTestClient.Response response = client().send("POST", PATH, multipartHeaders(),
                multipart(new BodyPart("file", fileName, "text/plain", content)));

        assertEquals("HTTP/1.1 200 File was uploaded.", response.statusLine(), response::toString);
        assertArrayEquals(content, response.body(), response::toString);
        Path stored = Path.of(uploadDir + "/" + fileName);
        assertTrue(stored.startsWith(uploadDir), stored::toString);
        assertArrayEquals(content, Files.readAllBytes(stored));
        assertEquals(List.of(uploadDir.relativize(stored).getName(0).toString()), storedFiles());
        try (Stream<Path> entries = Files.list(outside)) {
            assertEquals(List.of(), entries.toList());
        }
    }

    /**
     * File names whose write fails with a file-system message naming the upload directory or a hidden temporary
     * file: {@code .}, {@code ..} and the empty name, which resolve to directories, and names longer than the file
     * system allows, whose rename is refused once the temporary file is written.
     *
     * @return description, file name
     */
    private static Stream<Arguments> fileNamesWhoseWriteFails() {
        return Stream.of(
                Arguments.of("the name .", "."),
                Arguments.of("the name ..", ".."),
                Arguments.of("the empty name", ""),
                Arguments.of("256 characters", "a".repeat(256)),
                Arguments.of("1,000 characters", "a".repeat(1000)));
    }

    /**
     * A failed write gets the unhandled-exception 500 with Mule's streaming-failure message, which names neither the
     * upload directory nor a temporary file, and leaves nothing in the upload directory.
     *
     * @param description the case, for the test name
     * @param fileName    the file name whose write fails
     * @throws IOException when the request or a file operation of the test fails
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fileNamesWhoseWriteFails")
    void writeFailureGetsTheStreamingFailureResponseNamingNoPath(String description, String fileName)
            throws IOException {
        HttpTestClient.Response response = client().send("POST", PATH, multipartHeaders(),
                multipart(new BodyPart("file", fileName, "text/plain", bytes("QA_SECURITY_CONTENT"))));

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        String responseType = response.header("Content-Type");
        assertNotNull(responseType, response::toString);
        assertTrue(responseType.startsWith("application/json"), response::toString);
        assertArrayEquals(STREAMING_FAILED_BODY, response.body(), response::toString);
        String body = new String(response.body(), StandardCharsets.UTF_8);
        assertFalse(body.contains(uploadDir.toString()), body);
        assertFalse(body.contains(".tmp"), body);
        assertEquals(List.of(), storedFiles());
    }

    /** Scenario {@code get-unknown-path}: no listener serves the path, so the listeners' 404 answers it. */
    @Test
    void unknownPathGetsTheNoListenerNotFound() throws IOException {
        HttpTestClient.Response response = client().get("/unknown");

        assertEquals("HTTP/1.1 404 Not Found", response.statusLine(), response::toString);
        assertEquals("text/plain", response.header("Content-Type"), response::toString);
        assertArrayEquals(NO_LISTENER_BODY, response.body(), response::toString);
        assertEquals(List.of(), storedFiles());
    }

    /**
     * The upload's reason phrase belongs to its own exchange: the form request sent right after it gets the
     * standard {@code OK}, whichever Undertow worker serves it.
     */
    @Test
    void uploadReasonPhraseDoesNotReachTheNextResponse() throws IOException {
        HttpTestClient.Response upload = client().send("POST", PATH, multipartHeaders(BODY_A_BOUNDARY), bodyA());
        assertEquals("HTTP/1.1 200 " + REASON, upload.statusLine(), upload::toString);

        HttpTestClient.Response form = client().get(PATH);

        assertEquals("HTTP/1.1 200 OK", form.statusLine(), form::toString);
        assertArrayEquals(classpathBytes("/uploadFile.html"), form.body(), form::toString);
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    /**
     * Sends {@code <method> /uploadFile HTTP/1.1} with {@code Host} and {@code Connection: close} and no body over a
     * new socket, and returns every byte the server writes before it closes the connection.
     */
    private byte[] sendWithoutRequestBody(String method) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), HttpTestClient.TIMEOUT_MILLIS);
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(bytes(method + " " + PATH + " HTTP/1.1" + CRLF
                    + "Host: localhost:" + port + CRLF
                    + "Connection: close" + CRLF
                    + CRLF));
            out.flush();
            return socket.getInputStream().readAllBytes();
        }
    }

    private static Map<String, String> multipartHeaders() {
        return multipartHeaders(BOUNDARY);
    }

    /** The request header {@code Content-Type: multipart/form-data; boundary=<boundary>}; ST-1 adds the rest. */
    private static Map<String, String> multipartHeaders(String boundary) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        return headers;
    }

    /** Body A of {@code SCENARIOS.md}: the 13 bytes of {@code test.txt} as the part {@code file}, 159 bytes. */
    private static byte[] bodyA() throws IOException {
        return filePart(BODY_A_BOUNDARY, "test.txt", "text/plain", classpathBytes("/test.txt"));
    }

    /** Body C of {@code SCENARIOS.md}: the text field {@code description} and no {@code file} part, 118 bytes. */
    private static byte[] bodyC() {
        return bytes("--" + BODY_C_BOUNDARY + CRLF
                + "Content-Disposition: form-data; name=\"description\"" + CRLF
                + CRLF
                + "no file part" + CRLF
                + "--" + BODY_C_BOUNDARY + "--" + CRLF);
    }

    /**
     * A multipart body of the one part {@code file} laid out as the bodies of {@code SCENARIOS.md}: the delimiter
     * line, {@code Content-Disposition} with {@code filename}, {@code Content-Type}, a blank line, the content and the
     * closing delimiter line, every line ended by CRLF.
     */
    private static byte[] filePart(String boundary, String fileName, String partContentType, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(bytes("--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"" + CRLF
                + "Content-Type: " + partContentType + CRLF
                + CRLF));
        out.writeBytes(content);
        out.writeBytes(bytes(CRLF + "--" + boundary + "--" + CRLF));
        return out.toByteArray();
    }

    /**
     * A {@code multipart/form-data} body with delimiter {@link #BOUNDARY}: each part's {@code Content-Disposition}
     * (with {@code filename} when the part has one), its {@code Content-Type} when it has one, a blank line and its
     * content, every line ended by CRLF, then the closing delimiter line.
     */
    private static byte[] multipart(BodyPart... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (BodyPart part : parts) {
            StringBuilder head = new StringBuilder()
                    .append("--").append(BOUNDARY).append(CRLF)
                    .append("Content-Disposition: form-data; name=\"").append(part.name()).append('"');
            if (part.fileName() != null) {
                head.append("; filename=\"").append(part.fileName()).append('"');
            }
            head.append(CRLF);
            if (part.contentType() != null) {
                head.append("Content-Type: ").append(part.contentType()).append(CRLF);
            }
            head.append(CRLF);
            out.writeBytes(bytes(head.toString()));
            out.writeBytes(part.content());
            out.writeBytes(bytes(CRLF));
        }
        out.writeBytes(bytes("--" + BOUNDARY + "--" + CRLF));
        return out.toByteArray();
    }

    /**
     * A {@code multipart/form-data} body with delimiter {@link #BOUNDARY} and one {@code text/plain} part {@code file}
     * whose {@code filename} is written as {@code fileName} byte for byte, so a name in any encoding reaches the
     * server unchanged; the lines are those of {@link #multipart}.
     */
    private static byte[] multipartWithFileName(byte[] fileName, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(bytes("--" + BOUNDARY + CRLF + "Content-Disposition: form-data; name=\"file\"; filename=\""));
        out.writeBytes(fileName);
        out.writeBytes(bytes("\"" + CRLF + "Content-Type: text/plain" + CRLF + CRLF));
        out.writeBytes(content);
        out.writeBytes(bytes(CRLF + "--" + BOUNDARY + "--" + CRLF));
        return out.toByteArray();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] classpathBytes(String resource) throws IOException {
        try (InputStream in = UploadControllerTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return in.readAllBytes();
        }
    }

    /** The MD5 digest of {@code bytes} as lower-case hexadecimal. */
    private static String md5(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5, which every Java platform provides, is unavailable", e);
        }
    }

    /** Names of the entries of the upload directory, sorted. */
    private static List<String> storedFiles() throws IOException {
        try (Stream<Path> entries = Files.list(uploadDir)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * One part of a multipart body.
     *
     * @param name        the part name of its {@code Content-Disposition}
     * @param fileName    its {@code filename}, or {@code null} for a text field
     * @param contentType its {@code Content-Type}, or {@code null} for none
     * @param content     its content bytes
     */
    record BodyPart(String name, String fileName, String contentType, byte[] content) {

        /** The part as {@code name=fileName} for test names. */
        @Override
        public String toString() {
            return fileName == null ? name : name + "=" + fileName;
        }
    }
}
