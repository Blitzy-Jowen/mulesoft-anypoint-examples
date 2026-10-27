/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.FileEntry;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;

import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.config.FtpProperties;

/**
 * Unit test of {@link FtpUploadClient}, the replacement for the original flow's {@code ftp:outbound-endpoint}
 * ({@code upload-to-ftp.xml:28}: path {@code /Upload/}, {@code outputPattern="muleExample.xml"},
 * {@code responseTimeout="10000"}).
 *
 * <p>Every test talks to its own in-process MockFtpServer {@link FakeFtpServer} on a free loopback port, holding
 * the directory {@code /Upload} and the original demo login {@code demo}/{@code demo}, which exists only here.
 * The upload is asserted on the server's file system (bytes, overwrite, nothing written on failure) and, through a
 * Mockito spy on the commons-net {@link FTPClient} the client creates, on the protocol calls: one connection, no
 * retry, binary type, passive mode, the 10 s timeouts and a closed connection on every outcome. The original test
 * made no FTP-side assertion, so this coverage is new; no Spring context is started.
 */
class FtpUploadClientTest {

    private static final String HOST = "localhost";
    private static final String USER = "demo";
    private static final String PASSWORD = "demo";
    private static final String WRONG_PASSWORD = "wrong-pw";
    private static final String UPLOAD_PATH = "/Upload/";
    private static final String MISSING_PATH = "/Missing/";
    private static final String UPLOADED_FILE = "/Upload/muleExample.xml";
    private static final int TIMEOUT_MILLIS = 10_000;

    /**
     * DataWeave-style XML with LF line ends, tab indentation and multi-byte UTF-8 characters, written with unicode
     * escapes so the source encoding cannot change the bytes. An ASCII-mode transfer would turn each LF into CRLF.
     * Kept as immutable text that {@link #utf8(String)} encodes into a new array at every use, so the array a test
     * hands the client is never the array it expects, and a client that alters its input can change neither the
     * expectation nor a later test.
     */
    private static final String XML = "<?xml version='1.0' encoding='UTF-8'?>\n"
            + "<employees>\n"
            + "\t<employee>\n"
            + "\t\t<name>Zo\u00eb</name>\n"
            + "\t\t<lastName>\u65e5\u672c</lastName>\n"
            + "\t</employee>\n"
            + "</employees>\n";

    private UnixFakeFileSystem fileSystem;
    private FakeFtpServer server;
    private int port;

    @BeforeEach
    void startServer() {
        fileSystem = new UnixFakeFileSystem();
        // The parent "/" is created automatically; adding it as well would fail with pathAlreadyExists.
        fileSystem.add(new DirectoryEntry("/Upload"));

        server = new FakeFtpServer();
        server.setServerControlPort(0);
        server.setFileSystem(fileSystem);
        server.addUserAccount(new UserAccount(USER, PASSWORD, "/"));
        server.start();
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(10))
                .until(() -> server.isStarted() && server.getServerControlPort() > 0);
        port = server.getServerControlPort();
        assertThat(port).isPositive();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void outputPatternIsMuleExampleXml() throws Exception {
        assertThat(FtpUploadClient.OUTPUT_PATTERN).isEqualTo("muleExample.xml");
    }

    @Test
    void uploadWritesFileByteForByte() throws Exception {
        byte[] content = utf8(XML);

        new FtpUploadClient(props(port, UPLOAD_PATH, PASSWORD)).upload(FtpUploadClient.OUTPUT_PATTERN, content);

        assertThat(fileSystem.isFile(UPLOADED_FILE)).isTrue();
        byte[] stored = read(UPLOADED_FILE);
        assertThat(stored).containsExactly(utf8(XML));
        // The fake server stores the bytes it receives unchanged, so no CR proves the transfer ran in BINARY mode.
        assertThat(stored).doesNotContain((byte) '\r');
        // The client only reads the caller's array; it must hand it back unchanged.
        assertThat(content).containsExactly(utf8(XML));
    }

    @Test
    void uploadOverwritesExistingFile() throws Exception {
        fileSystem.add(new FileEntry(UPLOADED_FILE, "previous content that is clearly longer than the replacement"));
        // Non-empty on purpose: the fake server leaves the old bytes in place when it receives no data.
        String replacement = "<employees/>\n";
        byte[] content = utf8(replacement);

        new FtpUploadClient(props(port, UPLOAD_PATH, PASSWORD)).upload(FtpUploadClient.OUTPUT_PATTERN, content);

        assertThat(read(UPLOADED_FILE)).containsExactly(utf8(replacement));
        assertThat(content).containsExactly(utf8(replacement));
    }

    @Test
    void wrongPasswordFailsWithoutWritingOrLeakingPassword() throws Exception {
        FtpUploadClient client = new FtpUploadClient(props(port, UPLOAD_PATH, WRONG_PASSWORD));

        Throwable thrown = catchUploadFailure(client);

        assertSecretAbsent(thrown, WRONG_PASSWORD);
        assertThat(fileSystem.exists(UPLOADED_FILE)).isFalse();
    }

    @Test
    void missingDirectoryFailsAndIsNotCreated() throws Exception {
        FtpUploadClient client = new FtpUploadClient(props(port, MISSING_PATH, PASSWORD));

        catchUploadFailure(client);

        assertThat(fileSystem.exists("/Missing")).isFalse();
        assertThat(fileSystem.exists("/Missing/muleExample.xml")).isFalse();
        // The file must not fall back to the login's home directory or to the original endpoint's directory.
        assertThat(fileSystem.exists("/muleExample.xml")).isFalse();
        assertThat(fileSystem.exists(UPLOADED_FILE)).isFalse();
    }

    @Test
    void connectionRefusedFails() throws Exception {
        try (Socket reserved = reserveRefusingPort()) {
            int refusedPort = reserved.getLocalPort();
            FTPClient ftp = spy(new FTPClient());

            Throwable thrown = catchUploadFailure(clientWith(props(refusedPort, UPLOAD_PATH, PASSWORD), ftp));

            assertThat(thrown).isInstanceOf(UncheckedIOException.class);
            assertThat(Stream.iterate(thrown, Objects::nonNull, Throwable::getCause))
                    .anyMatch(ConnectException.class::isInstance);
            // connect(String, int) delegates to this overload, which every String-host connect reaches exactly once.
            verify(ftp, times(1)).connect(eq(HOST), eq(refusedPort), isNull(), eq(-1));
            verify(ftp, never()).login(anyString(), anyString());
        }
    }

    @Test
    void disconnectsAndDoesNotRetryAfterLoginFailure() throws Exception {
        FTPClient ftp = spy(new FTPClient());

        catchUploadFailure(clientWith(props(port, UPLOAD_PATH, WRONG_PASSWORD), ftp));

        verify(ftp, times(1)).connect(eq(HOST), eq(port), isNull(), eq(-1));
        verify(ftp, times(1)).login(USER, WRONG_PASSWORD);
        verify(ftp, never()).storeFile(anyString(), any(InputStream.class));
        // FTPClient.disconnect() calls its superclass's disconnect(), which a spy records as a second call.
        verify(ftp, atLeastOnce()).disconnect();
        assertThat(ftp.isConnected()).isFalse();
    }

    @Test
    void disconnectsAfterMissingDirectory() throws Exception {
        FTPClient ftp = spy(new FTPClient());

        catchUploadFailure(clientWith(props(port, MISSING_PATH, PASSWORD), ftp));

        verify(ftp, times(1)).changeWorkingDirectory(MISSING_PATH);
        verify(ftp, never()).makeDirectory(anyString());
        verify(ftp, never()).storeFile(anyString(), any(InputStream.class));
        verify(ftp, times(1)).logout();
        verify(ftp, atLeastOnce()).disconnect();
        assertThat(ftp.isConnected()).isFalse();
    }

    @Test
    void successfulUploadUsesOneConnectionAndDisconnects() throws Exception {
        FTPClient ftp = spy(new FTPClient());
        byte[] content = utf8(XML);

        clientWith(props(port, UPLOAD_PATH, PASSWORD), ftp).upload(FtpUploadClient.OUTPUT_PATTERN, content);

        verify(ftp, times(1)).connect(eq(HOST), eq(port), isNull(), eq(-1));
        verify(ftp, times(1)).login(USER, PASSWORD);
        verify(ftp, times(1)).setFileType(FTP.BINARY_FILE_TYPE);
        verify(ftp, atLeastOnce()).enterLocalPassiveMode();
        verify(ftp, times(1)).changeWorkingDirectory(UPLOAD_PATH);
        verify(ftp, never()).makeDirectory(anyString());
        verify(ftp, times(1)).storeFile(eq(FtpUploadClient.OUTPUT_PATTERN), any(InputStream.class));
        verify(ftp, times(1)).logout();
        verify(ftp, atLeastOnce()).disconnect();
        assertThat(ftp.isConnected()).isFalse();

        // The original endpoint's responseTimeout="10000", applied to connect, control replies and data transfer.
        assertThat(ftp.getConnectTimeout()).isEqualTo(TIMEOUT_MILLIS);
        verify(ftp).setDefaultTimeout(TIMEOUT_MILLIS);
        verify(ftp).setSoTimeout(TIMEOUT_MILLIS);
        verify(ftp).setDataTimeout(Duration.ofMillis(TIMEOUT_MILLIS));

        assertThat(read(UPLOADED_FILE)).containsExactly(utf8(XML));
        assertThat(content).containsExactly(utf8(XML));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unconfiguredSettings")
    void unconfiguredSettingFailsBeforeConnecting(String key, Consumer<FtpProperties> unset) throws Exception {
        FtpProperties properties = props(port, UPLOAD_PATH, PASSWORD);
        unset.accept(properties);
        FTPClient ftp = spy(new FTPClient());

        assertThatThrownBy(() -> clientWith(properties, ftp).upload(FtpUploadClient.OUTPUT_PATTERN, utf8(XML)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(key);

        verifyNoInteractions(ftp);
        assertThat(fileSystem.exists(UPLOADED_FILE)).isFalse();
    }

    /** Host and user must not be blank; only an absent or empty password counts as missing. */
    static Stream<Arguments> unconfiguredSettings() {
        return Stream.of(
                Arguments.of("ftp.host", (Consumer<FtpProperties>) p -> p.setHost(null)),
                Arguments.of("ftp.host", (Consumer<FtpProperties>) p -> p.setHost(" ")),
                Arguments.of("ftp.user", (Consumer<FtpProperties>) p -> p.setUser(null)),
                Arguments.of("ftp.user", (Consumer<FtpProperties>) p -> p.setUser("")),
                Arguments.of("ftp.password", (Consumer<FtpProperties>) p -> p.setPassword(null)),
                Arguments.of("ftp.password", (Consumer<FtpProperties>) p -> p.setPassword("")));
    }

    /** Settings of the fake server on {@code localhost} with the original demo user and the given values. */
    private static FtpProperties props(int port, String path, String password) {
        FtpProperties properties = new FtpProperties();
        properties.setHost(HOST);
        properties.setPort(port);
        properties.setPath(path);
        properties.setUser(USER);
        properties.setPassword(password);
        return properties;
    }

    /** A client whose single upload runs on {@code ftp}, so the test can observe the protocol calls. */
    private static FtpUploadClient clientWith(FtpProperties properties, FTPClient ftp) {
        return new FtpUploadClient(properties) {
            @Override
            protected FTPClient createClient() {
                return ftp;
            }
        };
    }

    /**
     * Uploads a fresh encoding of {@link #XML} and returns the failure, which must be one of the two types the client
     * declares.
     */
    private static Throwable catchUploadFailure(FtpUploadClient client) {
        Throwable thrown = catchThrowable(() -> client.upload(FtpUploadClient.OUTPUT_PATTERN, utf8(XML)));
        assertThat(thrown).isInstanceOfAny(IllegalStateException.class, UncheckedIOException.class);
        return thrown;
    }

    /** The UTF-8 bytes of {@code text} in a new array on every call, so no two uses ever share one. */
    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] read(String path) throws IOException {
        try (InputStream in = ((FileEntry) fileSystem.getEntry(path)).createInputStream()) {
            return in.readAllBytes();
        }
    }

    /** Neither the failure nor any of its causes may quote {@code secret}, in its message or its string form. */
    private static void assertSecretAbsent(Throwable thrown, String secret) {
        for (Throwable c = thrown; c != null; c = c.getCause()) {
            assertThat(String.valueOf(c.getMessage())).doesNotContain(secret);
            assertThat(c.toString()).doesNotContain(secret);
        }
    }

    /**
     * A socket bound to a free port on every local address that never listens or connects: a connection to its port
     * is refused while it is open, and with SO_REUSEADDR off no other socket can bind that port meanwhile.
     */
    private static Socket reserveRefusingPort() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(0));
            return socket;
        } catch (IOException | RuntimeException ex) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }
}
