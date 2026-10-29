/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.support.HttpTestClient;

/**
 * Port of the original example's {@code UploadToFtpAfterConvertingJsonToXmlIT} functional test: it posts
 * {@code message.json} to the {@code /} listener and compares the returned XML with {@code reply.xml} after removing
 * every whitespace character from both, the original's comparison. {@code reply.xml} itself lacks the spaces inside
 * the street names, so only that comparison matches.
 *
 * <p>The original uploaded to the FTP server named in {@code mule.test.properties}. Here an in-process MockFtpServer
 * {@link FakeFtpServer} on a free loopback port takes its place, holding the directory {@code /Upload} and the demo
 * account {@code demo}/{@code demo}; the {@code test} profile supplies the client side of that login
 * ({@code ftp.host}, {@code ftp.user}, {@code ftp.password}, {@code ftp.path}) and {@link #ftpProperties} the server's
 * port as {@code ftp.port}. The original's {@code ftp.home}, a local directory for an external server, has no
 * counterpart. No host other than localhost is contacted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UploadToFtpAfterConvertingJsonToXmlTest {

    /** Server side of the demo login that {@code application-test.yml} configures for the client. */
    private static final String FTP_USER = "demo";
    private static final String FTP_PASSWORD = "demo";
    private static final String FTP_HOME = "/";

    /** The original endpoint's {@code path="/Upload/"}; the client fails the upload when it does not exist. */
    private static final String UPLOAD_DIRECTORY = "/Upload";

    /** {@link FakeFtpServer#start()} binds its socket on a background thread. */
    private static final Duration FTP_START_TIMEOUT = Duration.ofSeconds(5);

    private static final FakeFtpServer FTP_SERVER = new FakeFtpServer();

    private static String MESSAGE;
    private static String REPLY;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void ftpProperties(DynamicPropertyRegistry registry) {
        int ftpPort = startFtpServer();
        registry.add("ftp.port", () -> ftpPort);
    }

    @BeforeAll
    static void init() throws IOException {
        MESSAGE = readResource("/message.json");
        REPLY = readResource("/reply.xml");
    }

    @AfterAll
    static void stopFtpServer() {
        FTP_SERVER.stop();
    }

    @Test
    void testDataWeave() {
        HttpTestClient.Response result = HttpTestClient.localhost(port).post("/", "application/json", MESSAGE);
        assertEquals(200, result.status(), result::toString);
        assertEquals(REPLY.replaceAll("\\s", ""), result.bodyAsString().replaceAll("\\s", ""));
    }

    /**
     * Starts the FTP server once, before the application context that needs its port is built, and returns that
     * port.
     */
    private static synchronized int startFtpServer() {
        if (!FTP_SERVER.isStarted()) {
            UnixFakeFileSystem fileSystem = new UnixFakeFileSystem();
            // The root "/" exists already; adding it as well would fail.
            fileSystem.add(new DirectoryEntry(UPLOAD_DIRECTORY));
            FTP_SERVER.setFileSystem(fileSystem);
            FTP_SERVER.addUserAccount(new UserAccount(FTP_USER, FTP_PASSWORD, FTP_HOME));
            FTP_SERVER.setServerControlPort(0);
            FTP_SERVER.start();
            await().atMost(FTP_START_TIMEOUT)
                    .pollInterval(Duration.ofMillis(10))
                    .until(() -> FTP_SERVER.isStarted() && FTP_SERVER.getServerControlPort() > 0);
        }
        return FTP_SERVER.getServerControlPort();
    }

    private static String readResource(String name) throws IOException {
        try (InputStream in = UploadToFtpAfterConvertingJsonToXmlTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Test resource not found on the classpath: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
