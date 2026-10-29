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
import static org.mockito.Mockito.doThrow;
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
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.commons.net.MalformedServerReplyException;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPConnectionClosedException;
import org.apache.commons.net.io.CopyStreamException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockftpserver.core.command.CommandNames;
import org.mockftpserver.core.command.StaticReplyCommandHandler;
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
 *
 * <p>Failure messages reach the anonymous 500 body, so they are asserted exactly: the failed operation with the
 * server's reply code or a category of the I/O failure, while a marker in the server's reply text or the I/O
 * exception's own message, and the configured host, port, directory, file name and login, stay out of the message
 * and the exception's string form.
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

    /** Login of a second account whose names no message may quote; {@code demo} is too common to prove absence. */
    private static final String MARKER_USER = "marker-svc-user";
    private static final String MARKER_PASSWORD = "MARKER-PW-7f3a9c";

    /** Text a hostile server or a failing socket puts into its reply or exception message. */
    private static final String MARKER_TEXT = "MARKER-REPLY-SECRET";

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
            FtpProperties properties = props(refusedPort, UPLOAD_PATH, PASSWORD);

            Throwable thrown = catchUploadFailure(clientWith(properties, ftp));

            assertThat(thrown).isInstanceOf(UncheckedIOException.class)
                    .hasMessage("FTP connect failed: connection refused or timed out");
            assertNoUploadDetail(thrown, properties);
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

    /**
     * The server answers one step of the session with {@code replyCode} and a reply text that quotes the marker, the
     * password, the target, the directory and the file name. A negative reply is reported by its code alone; a 421,
     * on which commons-net closes the session with an exception, as the connection the server closed. The error log
     * names the failure by its class and throw-site frame only, so that frame must lie in {@code upload} itself,
     * where each step throws from its own line, and never in a helper shared by every step.
     */
    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("serverReplyFailures")
    void serverReplyFailureNamesOnlyTheOperationAndOutcome(String command, int replyCode,
            Class<? extends RuntimeException> type, Class<? extends IOException> cause, String message)
            throws Exception {
        FtpProperties properties = markerProps();
        server.setCommandHandler(command, new StaticReplyCommandHandler(replyCode, markerReply(properties)));

        Throwable thrown = catchUploadFailure(new FtpUploadClient(properties));

        assertThat(thrown).isInstanceOf(type).hasMessage(message);
        if (cause == null) {
            assertThat(thrown).hasNoCause();
        } else {
            assertThat(thrown).hasCauseInstanceOf(cause);
        }
        assertThat(thrown.getStackTrace()).isNotEmpty();
        assertThat(thrown.getStackTrace()[0])
                .returns(FtpUploadClient.class.getName(), StackTraceElement::getClassName)
                .returns("upload", StackTraceElement::getMethodName);
        assertNoUploadDetail(thrown, properties);
        assertThat(fileSystem.exists(UPLOADED_FILE)).isFalse();
    }

    static Stream<Arguments> serverReplyFailures() {
        return Stream.of(
                Arguments.of(CommandNames.CONNECT, 554, IllegalStateException.class, null,
                        "FTP connect failed: server replied 554"),
                Arguments.of(CommandNames.CONNECT, 421, UncheckedIOException.class,
                        FTPConnectionClosedException.class, "FTP connect failed: connection closed by the server"),
                Arguments.of(CommandNames.PASS, 530, IllegalStateException.class, null,
                        "FTP login failed: server replied 530"),
                Arguments.of(CommandNames.TYPE, 504, IllegalStateException.class, null,
                        "FTP set binary file type failed: server replied 504"),
                Arguments.of(CommandNames.CWD, 550, IllegalStateException.class, null,
                        "FTP change working directory failed: server replied 550"),
                Arguments.of(CommandNames.CWD, 421, UncheckedIOException.class,
                        FTPConnectionClosedException.class,
                        "FTP change working directory failed: connection closed by the server"),
                Arguments.of(CommandNames.STOR, 553, IllegalStateException.class, null,
                        "FTP store failed: server replied 553"));
    }

    /**
     * Each I/O failure, its own message quoting the marker and the target, is reported by the category of its type,
     * or of the first cause with one, and kept as the cause.
     */
    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("connectFailures")
    void connectIoFailureNamesOnlyTheCategoryOfItsType(IOException failure, String category) throws Exception {
        FtpProperties properties = markerProps();
        FTPClient ftp = spy(new FTPClient());
        doThrow(failure).when(ftp).connect(HOST, port);

        Throwable thrown = catchUploadFailure(clientWith(properties, ftp));

        assertThat(thrown).isInstanceOf(UncheckedIOException.class)
                .hasMessage("FTP connect failed: " + category);
        assertThat(thrown.getCause()).isSameAs(failure);
        assertNoUploadDetail(thrown, properties);
        verify(ftp, never()).login(anyString(), anyString());
    }

    static Stream<Arguments> connectFailures() {
        String text = MARKER_TEXT + " " + HOST + UPLOAD_PATH + " " + MARKER_PASSWORD;
        return Stream.of(
                Arguments.of(new FTPConnectionClosedException(text), "connection closed by the server"),
                Arguments.of(new MalformedServerReplyException(text), "malformed server reply"),
                Arguments.of(new SocketTimeoutException(text), "timed out"),
                Arguments.of(new UnknownHostException(text), "unknown host"),
                Arguments.of(new ConnectException(text), "connection refused or timed out"),
                Arguments.of(new NoRouteToHostException(text), "host unreachable"),
                Arguments.of(new SocketException(text), "connection error"),
                Arguments.of(new IOException(text), "I/O error"),
                Arguments.of(new IOException(text, new SocketTimeoutException(text)), "timed out"),
                Arguments.of(circularFailure(text), "I/O error"));
    }

    /** Two plain I/O failures, each the other's cause, so a walk of the cause chain never reaches its end. */
    private static IOException circularFailure(String text) {
        IOException outer = new IOException(text);
        outer.initCause(new IOException(text, outer));
        return outer;
    }

    /**
     * A data transfer that fails inside {@code storeFile}, which commons-net reports as a {@link CopyStreamException}
     * around the socket's failure, is reported as the store operation with the category of that inner failure, and
     * the session is still logged out and closed.
     */
    @Test
    void storeTransferFailureNamesTheStoreOperation() throws Exception {
        FtpProperties properties = markerProps();
        FTPClient ftp = spy(new FTPClient());
        String text = MARKER_TEXT + " " + UPLOAD_PATH + FtpUploadClient.OUTPUT_PATTERN;
        CopyStreamException failure = new CopyStreamException(text, 0, new SocketTimeoutException(text));
        doThrow(failure).when(ftp).storeFile(eq(FtpUploadClient.OUTPUT_PATTERN), any(InputStream.class));

        Throwable thrown = catchUploadFailure(clientWith(properties, ftp));

        assertThat(thrown).isInstanceOf(UncheckedIOException.class).hasMessage("FTP store failed: timed out");
        assertThat(thrown.getCause()).isSameAs(failure);
        assertNoUploadDetail(thrown, properties);
        verify(ftp, times(1)).storeFile(eq(FtpUploadClient.OUTPUT_PATTERN), any(InputStream.class));
        verify(ftp, times(1)).logout();
        verify(ftp, atLeastOnce()).disconnect();
        assertThat(ftp.isConnected()).isFalse();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unconfiguredSettings")
    void unconfiguredSettingFailsBeforeConnecting(String key, Consumer<FtpProperties> unset) throws Exception {
        FtpProperties properties = props(port, UPLOAD_PATH, PASSWORD);
        unset.accept(properties);
        FTPClient ftp = spy(new FTPClient());

        assertThatThrownBy(() -> clientWith(properties, ftp).upload(FtpUploadClient.OUTPUT_PATTERN, utf8(XML)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(key)
                .satisfies(thrown -> assertNoUploadDetail(thrown, properties));

        verifyNoInteractions(ftp);
        assertThat(fileSystem.exists(UPLOADED_FILE)).isFalse();
    }

    /**
     * Host and user must not be blank; only an absent or empty password counts as missing; the port must be one the
     * JDK accepts, whose own rejection would quote it.
     */
    static Stream<Arguments> unconfiguredSettings() {
        return Stream.of(
                Arguments.of("ftp.host", (Consumer<FtpProperties>) p -> p.setHost(null)),
                Arguments.of("ftp.host", (Consumer<FtpProperties>) p -> p.setHost(" ")),
                Arguments.of("ftp.user", (Consumer<FtpProperties>) p -> p.setUser(null)),
                Arguments.of("ftp.user", (Consumer<FtpProperties>) p -> p.setUser("")),
                Arguments.of("ftp.password", (Consumer<FtpProperties>) p -> p.setPassword(null)),
                Arguments.of("ftp.password", (Consumer<FtpProperties>) p -> p.setPassword("")),
                Arguments.of("ftp.port", (Consumer<FtpProperties>) p -> p.setPort(-1)),
                Arguments.of("ftp.port", (Consumer<FtpProperties>) p -> p.setPort(65_536)));
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

    /** Settings of the fake server with the marker login, which this method adds to the server. */
    private FtpProperties markerProps() {
        server.addUserAccount(new UserAccount(MARKER_USER, MARKER_PASSWORD, "/"));
        FtpProperties properties = props(port, UPLOAD_PATH, MARKER_PASSWORD);
        properties.setUser(MARKER_USER);
        return properties;
    }

    /** A one-line reply text quoting the marker and every detail of {@code properties} that a message must omit. */
    private static String markerReply(FtpProperties properties) {
        return MARKER_TEXT + " pw=" + properties.getPassword() + " user=" + properties.getUser() + " at "
                + properties.getHost() + ":" + properties.getPort() + " in " + properties.getPath()
                + FtpUploadClient.OUTPUT_PATTERN;
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
     * The failure's message and string form, the text the 500 body and the error log can carry, quote neither the
     * marker nor any configured detail: host, port, directory, file name, user or password. Its causes keep their
     * own text for diagnosis and are not checked.
     */
    private static void assertNoUploadDetail(Throwable thrown, FtpProperties properties) {
        List<String> details = new ArrayList<>(List.of(MARKER_TEXT, FtpUploadClient.OUTPUT_PATTERN,
                String.valueOf(properties.getPort())));
        Stream.of(properties.getHost(), properties.getPath(), properties.getUser(), properties.getPassword())
                .filter(value -> value != null && !value.isBlank())
                .forEach(details::add);
        String[] forbidden = details.toArray(new String[0]);
        assertThat(String.valueOf(thrown.getMessage())).doesNotContain(forbidden);
        assertThat(thrown.toString()).doesNotContain(forbidden);
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
