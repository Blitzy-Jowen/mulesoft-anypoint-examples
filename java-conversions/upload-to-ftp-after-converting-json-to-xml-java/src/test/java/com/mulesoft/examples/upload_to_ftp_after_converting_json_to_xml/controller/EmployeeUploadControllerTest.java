/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.controller;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockftpserver.core.MockFtpServerException;
import org.mockftpserver.core.command.CommandHandler;
import org.mockftpserver.core.command.StaticReplyCommandHandler;
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
 * JSON, a member name that is not an XML element name, no body, a rejected FTP login, a missing remote
 * directory, a hostile FTP reply), and the no-listener 404 for a path the flow does not serve. Every test starts
 * from the same seeded FTP server, so the order of the tests does not matter.
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

    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration START_POLL_INTERVAL = Duration.ofMillis(10);

    private static final String ROOT = "/";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String ERROR_BODY_PREFIX = "{ \"message\": ";

    /** The security review's key, which as a raw element name would inject an {@code <evil/>} element. */
    private static final String INJECTION_KEY = "x><!--</x>--><evil/><!--x--";

    /** Text a hostile FTP server puts into its reply. */
    private static final String HOSTILE_REPLY_MARKER = "MARKER-REPLY-SECRET";

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

    /**
     * Employees whose address item carries the security review's comment-balanced injection key, as a direct member
     * and in an object nested in the item.
     */
    static Stream<String> bodiesWithAnInjectionKey() {
        String employee = "{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"Doe\",\"addresses\":"
                + "{\"address\":[%s]}}]}}";
        return Stream.of(
                String.format(employee, "{\"street\":\"1 A St\",\"" + INJECTION_KEY + "\":\"v\"}"),
                String.format(employee, "{\"street\":\"1 A St\",\"geo\":{\"" + INJECTION_KEY + "\":\"v\"}}"));
    }

    /**
     * A JSON member name that is not an XML element name: the writer refuses it while the transform builds the
     * document, before the upload step, so the 500 carries neither the key nor the markup it would inject, and
     * nothing is stored.
     */
    @ParameterizedTest
    @MethodSource("bodiesWithAnInjectionKey")
    void keyThatIsNotAnElementNameReturns500WithoutTheKeyAndUploadsNothing(String body) {
        HttpTestClient.Response response = http.send("POST", ROOT, Map.of(CONTENT_TYPE, "application/json"),
                body.getBytes(UTF_8));

        assertPlatform500(response);
        assertThat(response.bodyAsString()).as("%s", response)
                .isEqualTo("{ \"message\": \"Invalid XML element name: character U+003E at offset 1 is not allowed\" }")
                .doesNotContain(INJECTION_KEY)
                .doesNotContain("<evil");
        assertNothingUploaded();
    }

    /**
     * Scenario {@code post-root-ftp-unavailable}, here with the server refusing the configured login: the message
     * names the operation and the reply code only.
     */
    @Test
    void ftpLoginRejectedReturns500() {
        FTP.getUserAccount(FTP_USER).setPassword("not-the-configured-password");

        HttpTestClient.Response response = sendEmployees("POST");

        assertPlatform500(response);
        assertThat(response.bodyAsString()).as("%s", response)
                .isEqualTo("{ \"message\": \"FTP login failed: server replied 530\" }");
        assertNoFtpDetail(response);
        assertNothingUploaded();
    }

    /**
     * A hostile server's reply text, quoting a marker, the configured login and the target, never reaches the
     * anonymous 500 body: a negative reply is reported by its code, a 421 as the connection the server closed.
     */
    @ParameterizedTest(name = "[{index}] {0} {1}")
    @CsvSource({
            "CONNECT, 421, FTP connect failed: connection closed by the server",
            "PASS, 530, FTP login failed: server replied 530",
            "STOR, 553, FTP store failed: server replied 553"})
    void hostileFtpReplyTextStaysOutOfThe500Body(String command, int replyCode, String message) {
        CommandHandler original = FTP.getCommandHandler(command);
        FTP.setCommandHandler(command, new StaticReplyCommandHandler(replyCode, HOSTILE_REPLY_MARKER + " pw="
                + FTP_PASSWORD + " user=" + FTP_USER + " at localhost:" + FTP.getServerControlPort() + " in "
                + UPLOAD_DIR + "/" + UPLOADED_FILE_NAME));
        try {
            HttpTestClient.Response response = sendEmployees("POST");

            assertPlatform500(response);
            assertThat(response.bodyAsString()).as("%s", response).isEqualTo("{ \"message\": \"" + message + "\" }");
            assertThat(response.bodyAsString()).doesNotContain(HOSTILE_REPLY_MARKER);
            assertNoFtpDetail(response);
            assertNothingUploaded();
        } finally {
            FTP.setCommandHandler(command, original);
        }
    }

    /**
     * Like the original endpoint, the client never creates the remote directory, so the upload fails; the message
     * names the operation and the reply code, not the directory.
     */
    @Test
    void missingUploadDirectoryReturns500AndCreatesNoDirectory() {
        FTP.setFileSystem(fileSystemWithoutUploadDir());

        HttpTestClient.Response response = sendEmployees("POST");

        assertPlatform500(response);
        assertThat(response.bodyAsString()).as("%s", response)
                .isEqualTo("{ \"message\": \"FTP change working directory failed: server replied 550\" }");
        assertNoFtpDetail(response);
        assertThat(FTP.getFileSystem().exists(UPLOAD_DIR)).isFalse();
        assertThat(FTP.getFileSystem().exists(FTP_HOME + UPLOADED_FILE_NAME)).isFalse();
        assertThat(uploadedFileExists()).isFalse();
    }

    /** Scenario {@code get-unknown-path}: the listener serves {@code /} only. */
    @Test
    void unknownPathReturnsNoListener404() {
        HttpTestClient.Response response = http.get("/other");

        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(response.headers(CONTENT_TYPE)).as("%s", response).containsExactly("text/plain");
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

    /**
     * 200 {@code OK}, exactly one {@code Content-Type} of XML in UTF-8 as the controller spells it, the expected
     * document, and the same bytes as the only uploaded file.
     */
    private static void assertUploadedXml(HttpTestClient.Response response) {
        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.headers(CONTENT_TYPE)).as("%s", response).containsExactly("application/xml;charset=UTF-8");
        assertThat(response.body()).as("body was:%n%s", response.bodyAsString())
                .isEqualTo(EXPECTED_XML.getBytes(UTF_8));
        assertThat(uploadedBytes()).isEqualTo(response.body());
        assertThat(uploadDirectoryNames()).containsExactly(UPLOADED_FILE_NAME);
    }

    /**
     * The unhandled-exception reply of SC-1 (AAP 0.4.7): exactly one bare {@code application/json} header and
     * APIkit's spacing of the message member.
     */
    private static void assertPlatform500(HttpTestClient.Response response) {
        assertThat(response.statusLine()).as("%s", response).isEqualTo("HTTP/1.1 500 Internal Server Error");
        assertThat(response.headers(CONTENT_TYPE)).as("%s", response).containsExactly("application/json");
        assertThat(response.bodyAsString()).startsWith(ERROR_BODY_PREFIX).endsWith("\" }");
    }

    /** The anonymous 500 body names none of the FTP settings: host, port, directory, file name or login. */
    private static void assertNoFtpDetail(HttpTestClient.Response response) {
        assertThat(response.bodyAsString()).as("%s", response).doesNotContain("localhost",
                String.valueOf(FTP.getServerControlPort()), UPLOAD_DIR, UPLOADED_FILE_NAME, FTP_USER, FTP_PASSWORD);
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
        return startAndAwait(server, START_TIMEOUT);
    }

    /**
     * Starts {@code server} and waits at most {@code timeout} for it to listen on its control port.
     *
     * <p>Every failure after {@code start()} is called stops the server before it propagates, because its thread may
     * already run and a failed class initialization keeps no instance anyone could stop: {@code start()}'s own
     * failure, which comes after it launched the server thread, the bounded wait timing out, and any other exception
     * or error. The stop waits for the server thread to end even when interrupted, and a failure of {@code stop()}
     * other than an interrupted wait is attached to the propagating one as suppressed. An exception is reported as an
     * {@link IllegalStateException} naming the timeout; an {@link Error} propagates unchanged.
     *
     * <p>The caller's interrupt status survives: Awaitility waits uninterruptibly and re-asserts an interrupt it
     * received, {@code start()} reports an interrupted wait for its server thread as a {@link MockFtpServerException}
     * caused by the {@link InterruptedException}, whose flag is restored here, and an interrupt received while the
     * stop waits for the server thread is restored once the stop has ended.
     */
    private static FakeFtpServer startAndAwait(FakeFtpServer server, Duration timeout) {
        try {
            server.start();
            // Polled on this thread: the condition is a lambda of this class, which no other thread can run until the
            // static initializer that starts FTP has finished.
            await().atMost(timeout)
                    .pollInterval(START_POLL_INTERVAL)
                    .pollInSameThread()
                    .until(() -> server.isStarted() && server.getServerControlPort() > 0);
            return server;
        } catch (RuntimeException e) {
            IllegalStateException failure =
                    new IllegalStateException("FakeFtpServer did not start within " + timeout.toMillis() + " ms", e);
            throw stopAfterFailedStart(server, failure, e.getCause() instanceof InterruptedException);
        } catch (Error e) {
            throw stopAfterFailedStart(server, e, false);
        }
    }

    /**
     * Stops a server whose start failed and returns {@code failure} for the caller to throw.
     *
     * <p>{@code stop()} closes the control socket and then joins the server thread, and an interrupt ends that join
     * while the thread may still run, so the stop is made uninterruptible: the flag is cleared before the first
     * {@code stop()}, and each time {@code stop()} reports an interrupted join as a {@link MockFtpServerException}
     * caused by the {@link InterruptedException}, the interrupt is noted and {@code stop()} is called again, which
     * repeats the harmless socket close and resumes the join, until it returns normally. That exception is not a
     * failure of the stop and is not attached to {@code failure}. Any other exception or error from {@code stop()}
     * ends the attempts and is attached to {@code failure} as suppressed. Once the attempts end, the flag is restored
     * when an interrupt was pending before the stop, arrived during it, or is reported by {@code interruptConsumed} as
     * one the failed start already cleared.
     */
    private static <T extends Throwable> T stopAfterFailedStart(FakeFtpServer server, T failure,
            boolean interruptConsumed) {
        boolean interrupted = Thread.interrupted() || interruptConsumed;
        try {
            while (true) {
                try {
                    server.stop();
                    return failure;
                } catch (MockFtpServerException e) {
                    if (!(e.getCause() instanceof InterruptedException)) {
                        failure.addSuppressed(e);
                        return failure;
                    }
                    interrupted = true;
                } catch (RuntimeException | Error e) {
                    failure.addSuppressed(e);
                    return failure;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
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
