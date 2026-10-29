/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.trigger.CsvFileTrigger;

/**
 * Port of the original {@code ImportingCsvFileIntoMSSharepointIT}: the local {@code contacts.csv} dropped into the
 * input directory must reach SharePoint unchanged as {@code /Shared Documents/<csvImportFolder>/contacts.csv}.
 *
 * <p>The original ran against a live SharePoint Online tenant. Here WireMock, bound to loopback on a dynamic port,
 * stands in for both the site and the security token service, so the test needs no credential and no external host:
 * <ul>
 *   <li>{@code client.dispatch} to the input endpoint and {@code Thread.sleep(20000)} become a file placed in the
 *       input directory, {@link CsvFileTrigger#pollOnce()} and a bounded wait;</li>
 *   <li>{@code getCsvFromSharepointFlow} ({@code file-get-content} of the uploaded file and
 *       {@code byte-array-to-string-transformer}) becomes the body of the recorded {@code Files/add} request, read as
 *       UTF-8, which is compared with the local file under the original assertion;</li>
 *   <li>{@code deleteFolderInSharepointFlow} becomes {@link WireMockExtension#resetAll()}, which discards the stubbed
 *       site's state.</li>
 * </ul>
 * The file endpoint's {@code moveToDirectory} disposition and the folder creation of the flow are checked as well.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ImportingCsvFileIntoMSSharepointTest {

    private static final String TEST_FOLDER = "" + System.currentTimeMillis();
    private static final String FILE_NAME = "contacts.csv";

    private static final String STS_PATH = "/extSTS.srf";
    private static final String WSIGNIN_URL = "/_forms/default.aspx?wa=wsignin1.0";
    private static final String CONTEXTINFO_URL = "/_api/contextinfo";
    private static final String FOLDERS_URL = "/_api/web/folders";
    private static final String FILES_ADD_PATH_PATTERN = ".*/Files/add\\(.*";
    /** Where the original read the file back from: {@code /Shared Documents/${csvImportFolder}/contacts.csv}. */
    private static final String FILES_ADD_URL = "/_api/web/GetFolderByServerRelativeUrl('/Shared%20Documents/"
            + TEST_FOLDER + "')/Files/add(url='" + FILE_NAME + "',overwrite=true)";

    private static final String SESSION_COOKIE = "rtFa=RTFA123; FedAuth=FEDAUTH456";
    private static final String FORM_DIGEST = "0xDIGEST,01 Jan 2024";
    private static final String ODATA_VERBOSE = "application/json;odata=verbose";
    private static final String STS_RESPONSE = "<S:Envelope xmlns:S=\"http://www.w3.org/2003/05/soap-envelope\""
            + " xmlns:wst=\"http://schemas.xmlsoap.org/ws/2005/02/trust\""
            + " xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">"
            + "<S:Body><wst:RequestSecurityTokenResponse><wst:RequestedSecurityToken>"
            + "<wsse:BinarySecurityToken Id=\"Compact0\">t=FAKE-TOKEN-123</wsse:BinarySecurityToken>"
            + "</wst:RequestedSecurityToken></wst:RequestSecurityTokenResponse></S:Body></S:Envelope>";

    private static final Duration AWAIT_LIMIT = Duration.ofSeconds(10);

    /**
     * Per-run directories under the build directory, created before the context binds them. They are not a
     * {@code @TempDir}: the cached context keeps polling the input directory after this class ends.
     */
    private static final Path WORK_DIR =
            Path.of("target", "test-csv-import", "ported-" + TEST_FOLDER).toAbsolutePath();
    private static final Path INPUT_DIR = WORK_DIR.resolve("input");
    private static final Path OUTPUT_DIR = WORK_DIR.resolve("output");
    /** Outside the input directory and on the same file store, so a staged file can be moved in atomically. */
    private static final Path STAGING_DIR = WORK_DIR.resolve("staging");

    static {
        try {
            Files.createDirectories(INPUT_DIR);
            Files.createDirectories(OUTPUT_DIR);
            Files.createDirectories(STAGING_DIR);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the test directories under " + WORK_DIR, e);
        }
    }

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"))
            .build();

    @Autowired
    CsvFileTrigger trigger;

    /**
     * Points the SharePoint connection and the file endpoint at this class's stub and directories. The suppliers are
     * read while the context starts, after WireMock is running; the stub is addressed by the loopback address it is
     * bound to. The polling frequency of one hour leaves the scheduled startup poll as the only one.
     */
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("Sharepoint.SiteUrl", ImportingCsvFileIntoMSSharepointTest::stubBaseUrl);
        r.add("sharepoint-online.sts-url", () -> stubBaseUrl() + STS_PATH);
        r.add("csvImportFolder", () -> TEST_FOLDER);
        r.add("csv-import.input-dir", INPUT_DIR::toString);
        r.add("csv-import.move-to-dir", OUTPUT_DIR::toString);
        r.add("csv-import.polling-frequency", () -> "3600000");
    }

    private static String stubBaseUrl() {
        return "http://127.0.0.1:" + wm.getPort();
    }

    @BeforeEach
    void stubSharepoint() {
        wm.stubFor(post(urlEqualTo(STS_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/soap+xml; charset=utf-8")
                .withBody(STS_RESPONSE)));
        wm.stubFor(post(urlEqualTo(WSIGNIN_URL)).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Set-Cookie", "rtFa=RTFA123; path=/", "FedAuth=FEDAUTH456; path=/")
                .withHeader("Location", "/_layouts/15/landing.aspx")));
        wm.stubFor(post(urlEqualTo(CONTEXTINFO_URL)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", ODATA_VERBOSE)
                .withBody("{\"d\":{\"GetContextWebInformation\":{\"FormDigestValue\":\"" + FORM_DIGEST + "\"}}}")));
        wm.stubFor(post(urlEqualTo(FOLDERS_URL)).willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", ODATA_VERBOSE)
                .withBody("{\"d\":{\"ServerRelativeUrl\":\"/Shared Documents/" + TEST_FOLDER + "\"}}")));
        wm.stubFor(post(urlPathMatching(FILES_ADD_PATH_PATTERN)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", ODATA_VERBOSE)
                .withBody("{\"d\":{\"Name\":\"" + FILE_NAME + "\"}}")));
    }

    @AfterEach
    void tearDown() throws IOException {
        wm.resetAll();
        Files.deleteIfExists(OUTPUT_DIR.resolve(FILE_NAME));
        Files.deleteIfExists(INPUT_DIR.resolve(FILE_NAME));
        Files.deleteIfExists(STAGING_DIR.resolve(FILE_NAME));
    }

    @Test
    void testImport() throws Exception {
        byte[] payload = new ClassPathResource(FILE_NAME).getContentAsByteArray();
        // Staged and moved in atomically: the scheduled startup poll runs on its own thread and must never list a
        // half-written file.
        Path staged = Files.write(STAGING_DIR.resolve(FILE_NAME), payload);
        Files.move(staged, INPUT_DIR.resolve(FILE_NAME), StandardCopyOption.ATOMIC_MOVE);

        // Not asserted: the scheduled startup poll may take the file first, and the poller's in-process mark keeps
        // either run from importing it twice.
        trigger.pollOnce();

        await().atMost(AWAIT_LIMIT).until(() -> uploads().size() == 1);
        LoggedRequest upload = uploads().get(0);
        String sharepointFileContent = new String(upload.getBody(), StandardCharsets.UTF_8);

        String localFileContent = new ClassPathResource(FILE_NAME).getContentAsString(StandardCharsets.UTF_8);

        assertEquals(localFileContent, sharepointFileContent, "Local file and Sharepoint file should be same");

        // The flow's moveToDirectory: the polled file ends up, unchanged, in the output directory.
        Path moved = OUTPUT_DIR.resolve(FILE_NAME);
        await().atMost(AWAIT_LIMIT).until(() -> Files.exists(moved) && !hasRegularFiles(INPUT_DIR));
        assertArrayEquals(payload, Files.readAllBytes(moved), "The polled file should be moved unchanged");

        wm.verify(1, postRequestedFor(urlEqualTo(FOLDERS_URL))
                .withHeader("Cookie", equalTo(SESSION_COOKIE))
                .withHeader("X-RequestDigest", equalTo(FORM_DIGEST))
                .withRequestBody(matchingJsonPath("$.ServerRelativeUrl", equalTo("/Shared Documents/" + TEST_FOLDER))));
        wm.verify(1, postRequestedFor(urlPathMatching(FILES_ADD_PATH_PATTERN)));
        wm.verify(1, postRequestedFor(urlEqualTo(FILES_ADD_URL))
                .withHeader("Cookie", equalTo(SESSION_COOKIE))
                .withHeader("X-RequestDigest", equalTo(FORM_DIGEST)));
    }

    private static List<LoggedRequest> uploads() {
        return wm.findAll(postRequestedFor(urlPathMatching(FILES_ADD_PATH_PATTERN)));
    }

    private static boolean hasRegularFiles(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.anyMatch(Files::isRegularFile);
        }
    }
}
