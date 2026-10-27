/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.FileEntry;
import org.mockftpserver.fake.filesystem.FileSystem;
import org.mockftpserver.fake.filesystem.FileSystemEntry;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.UploadToFtpAfterConvertingJsonToXmlApplication;
import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.config.FtpProperties;
import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.support.HttpTestClient;

/**
 * Wire-level test of {@link EmployeeUploadController}, the HTTP trigger of flow {@code main}
 * (upload-to-ftp-after-converting-json-to-xml/src/main/app/upload-to-ftp.xml:4-29), on the application's own Undertow
 * listener with every bean real: the JSON-to-XML mapper, the FTP client and the SC-1 handler.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the socket,
 * so the reason phrase, the {@code Content-Type} header and the body bytes are asserted as the listener writes them.
 * The FTP side is an in-process MockFtpServer {@link FakeFtpServer} on an ephemeral loopback port: the {@code test}
 * profile supplies host {@code localhost}, the original demo login and the remote directory, and
 * {@link #ftpPort(DynamicPropertyRegistry)} supplies only the server's port, so nothing beyond this JVM is contacted.
 * The uploaded file is read back from the fake server's file system.
 *
 * <p>The happy path covers every method, because the original listener declares no {@code allowedMethods}. The error
 * cases are the failures the flow does not handle, each answered with the platform-default 500 of SC-1 (malformed
 * JSON, no body, a rejected FTP login, a missing remote directory), and the no-listener 404 for a path the flow does
 * not serve. Every test starts from the same seeded FTP server, so the order of the tests does not matter.
 *
 * <p>The original {@code UploadToFtpAfterConvertingJsonToXmlIT} assertion is ported separately in
 * {@code UploadToFtpAfterConvertingJsonToXmlTest}.
 */
@SpringBootTest(classes = UploadToFtpAfterConvertingJsonToXmlApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EmployeeUploadControllerTest {

    /*
     * The fake server's own account and layout. They must equal the test profile's ftp.user, ftp.password and
     * ftp.path (src/test/resources/application-test.yml), which testProfilePointsFtpAtLocalFakeServer checks.
     */
    private static final String FTP_USER = "demo";
    private static final String FTP_PASSWORD = "demo";
    private static final String FTP_HOME = "/";
    private static final String UPLOAD_DIR = "/Upload";
    private static final String UPLOADED_FILE_NAME = "muleExample.xml";
    private static final String UPLOADED_FILE = UPLOAD_DIR + "/" + UPLOADED_FILE_NAME;

    private static final long START_TIMEOUT_MILLIS = 10_000;
    private static final long START_POLL_MILLIS = 10;

    private static final String ROOT = "/";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String ERROR_BODY_PREFIX = "{ \"message\": ";

    /**
     * The XML the original flow returns and uploads for {@code message.json}: the DataWeave XML writer's single-quoted
     * declaration, one tab per level, LF line ends and no trailing line feed, with the street values keeping their
     * spaces. The committed {@code reply.xml} lacks those spaces and ends with a line feed, so it matches only with
     * whitespace removed, as the original test compared it. Built from explicit escapes rather than a text block, whose
     * indentation stripping would make the bytes depend on the source layout.
     */
    private static final String EXPECTED_XML = String.join("\n",
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<employees>",
            "\t<employee>",
            "\t\t<name>John</name>",
            "\t\t<lastName>Doe</lastName>",
            "\t\t<addresses>",
            "\t\t\t<address>",
            "\t\t\t\t<street>123 Main Street</street>",
            "\t\t\t\t<zipCode>111</zipCode>",
            "\t\t\t</address>",
            "\t\t\t<address>",
            "\t\t\t\t<street>987 Cypress Avenue</street>",
            "\t\t\t\t<zipCode>222</zipCode>",
            "\t\t\t</address>",
            "\t\t</addresses>",
            "\t</employee>",
            "\t<employee>",
            "\t\t<name>Jane</name>",
            "\t\t<lastName>Doe</lastName>",
            "\t\t<addresses>",
            "\t\t\t<address>",
            "\t\t\t\t<street>345 Main Street</street>",
            "\t\t\t\t<zipCode>111</zipCode>",
            "\t\t\t</address>",
            "\t\t\t<address>",
            "\t\t\t\t<street>654 Sunset Boulevard</street>",
            "\t\t\t\t<zipCode>333</zipCode>",
            "\t\t\t</address>",
            "\t\t</addresses>",
            "\t</employee>",
            "</employees>");

    /** Started while the class loads, so it listens before the application context reads {@code ftp.port}. */
    private static final FakeFtpServer FTP = startFakeFtpServer();

    @LocalServerPort
    private int port;

    @Autowired
    private FtpProperties ftpProperties;

    private HttpTestClient http;

    @DynamicPropertySource
    static void ftpPort(DynamicPropertyRegistry registry) {
        registry.add("ftp.port", FTP::getServerControlPort);
    }

    @AfterAll
    static void stopFtp() {
        FTP.stop();
    }

    @BeforeEach
    void setUp() {
        resetFtpServer();
        http = HttpTestClient.localhost(port);
    }

    @AfterEach
    void tearDown() {
        resetFtpServer();
    }

    @Test
    void expectedDocumentLiteralIsTheVerified637ByteDocument() throws NoSuchAlgorithmException {
        byte[] bytes = EXPECTED_XML.getBytes(UTF_8);

        assertThat(bytes).hasSize(637);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)))
                .isEqualTo("86159f75689d7a5b40bb14284901d8d6");
    }

    /** Guards that the application uploads only to the in-process fake server, with the account it holds. */
    @Test
    void testProfilePointsFtpAtLocalFakeServer() {
        assertThat(ftpProperties.getHost()).isEqualTo("localhost");
        assertThat(ftpProperties.getPort()).isEqualTo(FTP.getServerControlPort());
        assertThat(ftpProperties.getPath()).isEqualTo(UPLOAD_DIR + "/");
        assertThat(ftpProperties.getUser()).isEqualTo(FTP_USER);
        assertThat(ftpProperties.getPassword()).isEqualTo(FTP_PASSWORD);
    }

    /** Scenario {@code post-root-employees}: the XML is the response and the uploaded file, byte for byte. */
    @Test
    void postEmployeesReturnsXmlAndUploadsTheSameBytes() {
        HttpTestClient.Response response = sendEmployees("POST");

        assertUploadedXml(response);
        assertThat(response.statusLine()).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.bodyAsString().replaceAll("\\s", ""))
                .as("whitespace-stripped comparison of the original test")
                .isEqualTo(new String(classpath("reply.xml"), UTF_8).replaceAll("\\s", ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void otherMethodsAreAcceptedBecauseTheListenerDeclaresNoAllowedMethods(String method) {
        assertUploadedXml(sendEmployees(method));
    }

    /** Scenario {@code post-root-malformed-json}: the transform fails before the upload step is reached. */
    @Test
    void malformedJsonReturns500JsonAndUploadsNothing() {
        HttpTestClient.Response response = http.send("POST", ROOT, Map.of(CONTENT_TYPE, "application/json"),
                "{\"employees\": {\"employee\": [".getBytes(UTF_8));

        assertPlatform500(response);
        assertThat(response.bodyAsString()).as("%s", response).contains("Unexpected end-of-input");
        assertNothingUploaded();
    }

    /** Scenario {@code post-root-ftp-unavailable}, here with the server refusing the configured login. */
    @Test
    void ftpLoginRejectedReturns500() {
        FTP.getUserAccount(FTP_USER).setPassword("not-the-configured-password");

        HttpTestClient.Response response = sendEmployees("POST");

        assertPlatform500(response);
        assertThat(response.bodyAsString()).as("%s", response)
                .contains("FTP login to localhost:" + FTP.getServerControlPort() + " failed");
        assertNothingUploaded();
    }

    /** Like the original endpoint, the client never creates the remote directory, so the upload fails. */
    @Test
    void missingUploadDirectoryReturns500AndCreatesNoDirectory() {
        FTP.setFileSystem(fileSystemWithoutUploadDir());

        HttpTestClient.Response response = sendEmployees("POST");

        assertPlatform500(response);
        assertThat(response.bodyAsString()).as("%s", response)
                .contains("FTP change working directory to " + UPLOAD_DIR + "/");
        assertThat(FTP.getFileSystem().exists(UPLOAD_DIR)).isFalse();
        assertThat(FTP.getFileSystem().exists(FTP_HOME + UPLOADED_FILE_NAME)).isFalse();
        assertThat(uploadedFileExists()).isFalse();
    }

    /** Scenario {@code get-unknown-path}: the listener serves {@code /} only. */
    @Test
    void unknownPathReturnsNoListener404() {
        HttpTestClient.Response response = http.get("/other");

        assertThat(response.status()).as("%s", response).isEqualTo(404);
        assertThat(response.reason()).as("%s", response).isEqualTo("Not Found");
        assertThat(contentType(response).equalsTypeAndSubtype(MediaType.TEXT_PLAIN)).as("%s", response).isTrue();
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: /other");
        assertNothingUploaded();
    }

    /** Scenario {@code get-root-no-body}: GET reaches the flow, whose transform rejects the absent payload. */
    @Test
    void getRootWithoutBodyReturns500() {
        HttpTestClient.Response response = http.get(ROOT);

        assertPlatform500(response);
        assertThat(response.bodyAsString()).isEqualTo("{ \"message\": \"JSON payload is empty\" }");
        assertNothingUploaded();
    }

    private HttpTestClient.Response sendEmployees(String method) {
        return http.send(method, ROOT, Map.of(CONTENT_TYPE, "application/json"), classpath("message.json"));
    }

    /** 200 {@code OK}, XML in UTF-8, the expected document, and the same bytes as the only uploaded file. */
    private static void assertUploadedXml(HttpTestClient.Response response) {
        assertThat(response.httpVersion()).as("%s", response).isEqualTo("HTTP/1.1");
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        assertThat(response.reason()).as("%s", response).isEqualTo("OK");
        MediaType contentType = contentType(response);
        assertThat(contentType.equalsTypeAndSubtype(MediaType.APPLICATION_XML)).as("%s", response).isTrue();
        assertThat(contentType.getCharset()).as("%s", response).isEqualTo(UTF_8);
        assertThat(response.body()).as("body was:%n%s", response.bodyAsString())
                .isEqualTo(EXPECTED_XML.getBytes(UTF_8));
        assertThat(uploadedBytes()).isEqualTo(response.body());
        assertThat(uploadDirectoryNames()).containsExactly(UPLOADED_FILE_NAME);
    }

    /** The unhandled-exception reply of SC-1 (AAP 0.4.7), with APIkit's spacing of the message member. */
    private static void assertPlatform500(HttpTestClient.Response response) {
        assertThat(response.status()).as("%s", response).isEqualTo(500);
        assertThat(response.reason()).as("%s", response).isEqualTo("Internal Server Error");
        assertThat(contentType(response).equalsTypeAndSubtype(MediaType.APPLICATION_JSON)).as("%s", response).isTrue();
        assertThat(response.bodyAsString()).startsWith(ERROR_BODY_PREFIX).endsWith("\" }");
    }

    /** Parses the single {@code Content-Type} header the response carries. */
    private static MediaType contentType(HttpTestClient.Response response) {
        assertThat(response.headers(CONTENT_TYPE)).as("%s", response).hasSize(1);
        return MediaType.parseMediaType(response.header(CONTENT_TYPE));
    }

    private static void assertNothingUploaded() {
        assertThat(uploadedFileExists()).isFalse();
        assertThat(uploadDirectoryNames()).isEmpty();
    }

    private static FakeFtpServer startFakeFtpServer() {
        FakeFtpServer server = new FakeFtpServer();
        server.setServerControlPort(0);
        server.addUserAccount(new UserAccount(FTP_USER, FTP_PASSWORD, FTP_HOME));
        server.setFileSystem(fileSystemWithUploadDir());
        server.start();
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MILLIS;
        while (!server.isStarted() || server.getServerControlPort() <= 0) {
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("FakeFtpServer did not start");
            }
            try {
                Thread.sleep(START_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for FakeFtpServer to start", e);
            }
        }
        return server;
    }

    /*
     * The root is added first: adding /Upload alone would create it implicitly, and adding it afterwards would then
     * fail as an existing path.
     */
    private static FileSystem fileSystemWithUploadDir() {
        UnixFakeFileSystem fileSystem = new UnixFakeFileSystem();
        fileSystem.add(new DirectoryEntry(FTP_HOME));
        fileSystem.add(new DirectoryEntry(UPLOAD_DIR));
        return fileSystem;
    }

    /** Only the home directory, which must exist for the login itself to succeed. */
    private static FileSystem fileSystemWithoutUploadDir() {
        UnixFakeFileSystem fileSystem = new UnixFakeFileSystem();
        fileSystem.add(new DirectoryEntry(FTP_HOME));
        return fileSystem;
    }

    /*
     * Replaces the whole file system rather than deleting what a test stored, because the fake file system refuses
     * to delete a non-empty directory; its command handlers read the server's current file system on every command.
     * Re-adding the account replaces the one keyed by the same user name, which restores a changed password.
     */
    private static void resetFtpServer() {
        FTP.setFileSystem(fileSystemWithUploadDir());
        FTP.addUserAccount(new UserAccount(FTP_USER, FTP_PASSWORD, FTP_HOME));
    }

    private static byte[] uploadedBytes() {
        FileSystemEntry entry = FTP.getFileSystem().getEntry(UPLOADED_FILE);
        assertThat(entry).as("uploaded file %s", UPLOADED_FILE).isInstanceOf(FileEntry.class);
        try (InputStream in = ((FileEntry) entry).createInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean uploadedFileExists() {
        return FTP.getFileSystem().exists(UPLOADED_FILE);
    }

    /** The names of the entries in the remote directory; the fake file system's API returns a raw list. */
    private static List<String> uploadDirectoryNames() {
        List<?> names = FTP.getFileSystem().listNames(UPLOAD_DIR);
        return names.stream().map(String::valueOf).toList();
    }

    private static byte[] classpath(String name) {
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath resource " + name, e);
        }
    }
}
