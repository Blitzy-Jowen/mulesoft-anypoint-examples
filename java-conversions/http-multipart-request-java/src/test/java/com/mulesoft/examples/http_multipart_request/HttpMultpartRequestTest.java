/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_multipart_request;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.http_multipart_request.support.HttpTestClient;

/**
 * Port of the original example's {@code HttpMultpartRequestIT} functional test, keeping its two requests and their
 * assertions: the GET form page contains {@code uploadFile.html}, and a multipart upload of {@code test.txt} under
 * the file name {@code file} leaves that file in the upload directory. Status line and headers are asserted by
 * {@code controller/UploadControllerTest}.
 *
 * <p>The original wrote to the fixed {@code /tmp/file}; here {@code upload.directory} points at a class-level
 * {@link TempDir}, so the test never touches the shared {@code /tmp}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpMultpartRequestTest {

    private static final String TEST_FILE_PATH = "./src/test/resources/test.txt";

    private static final String HTML_PATH = "./src/main/resources/uploadFile.html";

    /** Name of the uploaded file and of the part, as the original's {@code AttachmentRequest} call passed them. */
    private static final String FILE_NAME = "file";

    private static final String PART_NAME = "file";

    /**
     * Fixed boundary in place of HttpMime's random one, from the same alphabet; with it the request body is
     * byte-identical to body B of {@code fixtures/SCENARIOS.md} ({@code post-upload-file-attachment-request}).
     */
    private static final String BOUNDARY = "parity-boundary-0002";

    private static final String CRLF = "\r\n";

    private static String HTML;

    @TempDir
    static Path uploadDir;

    @LocalServerPort
    private int port;

    /** Replaces the original's fixed {@code /tmp} upload directory; resolved lazily, after the static TempDir exists. */
    @DynamicPropertySource
    static void uploadDirectory(DynamicPropertyRegistry registry) {
        registry.add("upload.directory", () -> uploadDir.toString());
    }

    @BeforeAll
    static void init() throws IOException {
        HTML = Files.readString(Path.of(HTML_PATH), StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(uploadDir.resolve(FILE_NAME));
    }

    @Test
    void testPageRender() {
        // The original's MuleClient GET carried no entity, so no body and no Content-Length are sent.
        HttpTestClient.Response response = HttpTestClient.localhost(port).get("/uploadFile");
        assertNotNull(response);
        assertTrue(response.bodyAsString().contains(HTML));
    }

    @Test
    void testFileUpload() throws Exception {
        executeMultiPartRequest("/uploadFile", Path.of(TEST_FILE_PATH), FILE_NAME);
        assertTrue(Files.exists(uploadDir.resolve(FILE_NAME)));
    }

    /**
     * Sends {@code file} as the multipart part {@code file}, with the bytes Apache HttpMime 4.3.6 wrote for the
     * original {@code AttachmentRequest.executeMultiPartRequest}: {@code MultipartEntityBuilder.create()} (STRICT
     * mode) holding {@code new FileBody(file, ContentType.APPLICATION_OCTET_STREAM, fileName)}. The header carries no
     * charset parameter; HttpTestClient adds the {@code Content-Length}.
     *
     * @param path     request target
     * @param file     file whose bytes form the part content
     * @param fileName {@code filename} parameter of the part's {@code Content-Disposition}
     * @return the complete response
     * @throws IOException if the file cannot be read
     */
    private HttpTestClient.Response executeMultiPartRequest(String path, Path file, String fileName)
            throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeAscii(body, "--" + BOUNDARY + CRLF);
        writeAscii(body, "Content-Disposition: form-data; name=\"" + PART_NAME + "\"; filename=\"" + fileName + "\""
                + CRLF);
        writeAscii(body, "Content-Type: application/octet-stream" + CRLF);
        writeAscii(body, "Content-Transfer-Encoding: binary" + CRLF);
        writeAscii(body, CRLF);
        body.writeBytes(Files.readAllBytes(file));
        writeAscii(body, CRLF);
        writeAscii(body, "--" + BOUNDARY + "--" + CRLF);

        Map<String, String> headers = Map.of("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        return HttpTestClient.localhost(port).send("POST", path, headers, body.toByteArray());
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }
}
