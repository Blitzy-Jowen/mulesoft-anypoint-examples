/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_expression_language_basics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;

import com.mulesoft.examples.mule_expression_language_basics.support.HttpTestClient;

/**
 * Port of the original example's {@code MuleExpressionLanguageBasicsIT} functional test, keeping its six requests
 * and its assertions with their comparison semantics: exact equality of every reply, exactly one entry in the
 * output directory after each file-writing call, and equality of the trimmed expected line with the trimmed file
 * content. Every call is a {@code POST}, as the original's {@code http.method} property made it.
 *
 * <p>The original wrote into {@code Path_of_your_choice} under the working directory; here that directory lives
 * under a JUnit temporary directory and is bound to {@code greeting.output-dir}, so no run leaves files in the
 * project.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MuleExpressionLanguageBasicsTest {

    private static final String MESSAGE = "username=Mule&age=1";
    private static final String REPLY = "Hello Mule";
    private static final String REPLY_2 = "No username provided";
    private static final String DIR_NAME = "Path_of_your_choice";
    private static final String REPLY_1 = "Mule, 1, false";

    /** The empty String payload the original sent to {@code greet1} to {@code greet4}. */
    private static final byte[] EMPTY = new byte[0];

    /** The {@code Content-Type} a Mule client String payload carried. */
    private static final Map<String, String> TEXT_PLAIN = Map.of("Content-Type", "text/plain");

    @TempDir
    static Path tempDir;

    @LocalServerPort
    private int port;

    private byte[] messageXml;
    private byte[] messageJson;

    /**
     * Binds the flows' output directory to {@link #outputDir()}. The supplier is read when the application context
     * refreshes, after JUnit has injected the static {@link #tempDir}.
     */
    @DynamicPropertySource
    static void registerOutputDir(DynamicPropertyRegistry registry) {
        registry.add("greeting.output-dir", () -> outputDir().toString());
    }

    @BeforeEach
    void init() throws IOException {
        messageXml = resource("/message.xml");
        messageJson = resource("/message.json");
    }

    /** Replaces the original's {@code @AfterClass} clean-up, so a failed test never leaves a file for the next. */
    @AfterEach
    void clean() throws IOException {
        FileSystemUtils.deleteRecursively(outputDir());
    }

    @Test
    void melFlow1Test() throws Exception {
        testResponse("greet1?" + MESSAGE, REPLY, EMPTY);
    }

    @Test
    void melFlow2Test() throws Exception {
        testResponse("greet2?" + MESSAGE, REPLY, EMPTY);
        testResponse("greet2", REPLY_2, EMPTY);
    }

    @Test
    void melFlow3Test() throws Exception {
        testFileAndClean("greet3?" + MESSAGE, REPLY_1, EMPTY);
    }

    @Test
    void melFlow4Test() throws Exception {
        testFileAndClean("greet4?" + MESSAGE, "Mule,1,false", EMPTY);
    }

    @Test
    void melFlow5Test() throws Exception {
        testFileAndClean("greet5", REPLY_1, messageXml);
    }

    @Test
    void melFlow6Test() throws Exception {
        testFileAndClean("greet6", REPLY_1, messageJson);
    }

    private void testFileAndClean(String param, String reply, byte[] body) throws Exception {
        testResponse(param, REPLY, body);
        Path dir = outputDir();
        assertTrue(Files.isDirectory(dir));
        List<Path> entries;
        try (Stream<Path> listing = Files.list(dir)) {
            entries = listing.toList();
        }
        // The original counted every entry of the directory (File.list()), not only regular files.
        assertEquals(1, entries.size());
        String content = new String(Files.readAllBytes(entries.get(0)), StandardCharsets.UTF_8);
        assertEquals(reply.trim(), content.trim());
        FileSystemUtils.deleteRecursively(dir);
    }

    private void testResponse(String param, String reply, byte[] body) {
        HttpTestClient.Response response = HttpTestClient.localhost(port).send("POST", "/" + param, TEXT_PLAIN, body);
        assertNotNull(response);
        // A Mule NullPayload result corresponds to an empty HTTP body.
        assertFalse(response.body().length == 0);
        assertEquals(reply, response.bodyAsString());
    }

    private static Path outputDir() {
        return tempDir.resolve(DIR_NAME);
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            assertNotNull(in, () -> "missing test resource " + name);
            return in.readAllBytes();
        }
    }
}
