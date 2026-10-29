/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.http_multipart_request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import jakarta.servlet.http.Part;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.http_multipart_request.mapper.ParseTemplateRenderer;

/**
 * Unit test of {@link UploadService}, the processor logic of the two flows of {@code http-multipart-request.xml},
 * with the real SC-6 {@link FileOutboundWriter} writing under a {@link TempDir} and the real SC-14
 * {@link ParseTemplateRenderer} reading the carried {@code uploadFile.html}; the multipart {@link Part} is a lenient
 * Mockito mock answering the accessors the service calls.
 *
 * <p>It covers {@code httpRenderFlow}'s page byte for byte, and {@code httpMultipartRequestFlow}'s steps in their
 * original order: the file name from the original expression component, applied verbatim to the part's
 * {@code Content-Disposition}; the part's {@code Content-Type} logged at ERROR, {@code null} included; the content
 * stored as {@code <upload.directory>/<file name>}, replacing an existing file, and returned unchanged; and the
 * failures the original declares no strategy for (no part, no or a too short {@code Content-Disposition}, an
 * unreadable part, an unwritable directory), each reaching the caller unchanged. No Spring context is started and
 * nothing is written outside the {@link TempDir}.
 */
class UploadServiceTest {

    /** The header a browser sends for the carried {@code test.txt}, as the ported original test uploads it. */
    private static final String TEST_TXT_DISPOSITION = "form-data; name=\"file\"; filename=\"test.txt\"";

    /**
     * MD5 of {@code http-multipart-request/src/main/resources/uploadFile.html} in the original example, the page
     * its {@code parse-template} served (348 bytes).
     */
    private static final String ORIGINAL_UPLOAD_FORM_MD5 = "7e90d7849338a2574566425c2ef43f4b";

    /** Size in bytes of the original {@code uploadFile.html}. */
    private static final int ORIGINAL_UPLOAD_FORM_SIZE = 348;

    @TempDir
    Path root;

    /** The configured {@code upload.directory}; it does not exist until the first file is stored. */
    Path uploadDirectory;

    UploadService service;

    Logger serviceLogger;

    Level previousLevel;

    ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void createServiceAndCaptureItsLog() {
        uploadDirectory = root.resolve("uploads");
        service = new UploadService(new ParseTemplateRenderer(), new FileOutboundWriter(), uploadDirectory.toString());
        serviceLogger = (Logger) LoggerFactory.getLogger(UploadService.class);
        previousLevel = serviceLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        serviceLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void releaseServiceLog() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(previousLevel);
    }

    /**
     * Header values and the name the original expression component computes from them: {@code trim()}, then
     * {@code substring(indexOf("filename=") + "filename=".length() + 1, length() - 1)}.
     */
    static Stream<Arguments> fileNamesByTheOriginalExpression() {
        return Stream.of(
                Arguments.of("browser header", TEST_TXT_DISPOSITION, "test.txt"),
                Arguments.of("httpmime FileBody header of the original test, file name \"file\"",
                        "form-data; name=\"file\"; filename=\"file\"", "file"),
                Arguments.of("surrounding whitespace is trimmed",
                        "  form-data; name=\"file\"; filename=\"test.txt\"\t ", "test.txt"),
                Arguments.of("spaces inside the name are kept",
                        "form-data; name=\"file\"; filename=\"my report.pdf\"", "my report.pdf"),
                Arguments.of("path segments are not sanitised",
                        "form-data; name=\"file\"; filename=\"../outside.txt\"", "../outside.txt"),
                Arguments.of("empty quoted name",
                        "form-data; name=\"file\"; filename=\"\"", ""),
                Arguments.of("unquoted name loses its first and last character",
                        "form-data; name=\"file\"; filename=test.txt", "est.tx"),
                Arguments.of("filename not the last parameter runs to the end of the header",
                        "form-data; filename=\"a.txt\"; name=\"file\"", "a.txt\"; name=\"file"),
                Arguments.of("first filename= wins",
                        "form-data; name=\"file\"; filename=\"a.txt\"; filename=\"b.txt\"",
                        "a.txt\"; filename=\"b.txt"),
                // The original's quirk, kept on purpose: indexOf answers -1, so the range is [9, length - 1).
                Arguments.of("without filename= the range starts at index 9",
                        "form-data; name=\"file\"", "; name=\"file"),
                Arguments.of("filename* is not read",
                        "form-data; name=\"file\"; filename*=UTF-8''a.txt", "; name=\"file\"; filename*=UTF-8''a.tx"),
                Arguments.of("the parameter name is matched case-sensitively",
                        "form-data; name=\"file\"; FILENAME=\"a.txt\"", "; name=\"file\"; FILENAME=\"a.txt"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fileNamesByTheOriginalExpression")
    void extractFileNameAppliesTheOriginalExpressionVerbatim(String scenario, String contentDisposition,
                                                             String expectedName) {
        assertThat(UploadService.extractFileName(contentDisposition)).isEqualTo(expectedName);
    }

    /** Header values the original expression fails on, with the exception its Java string calls raise. */
    static Stream<Arguments> headersTheOriginalExpressionFailsOn() {
        return Stream.of(
                Arguments.of("absent header", null, NullPointerException.class),
                Arguments.of("empty header", "", StringIndexOutOfBoundsException.class),
                Arguments.of("blank header", "   ", StringIndexOutOfBoundsException.class),
                Arguments.of("nine characters without filename=", "form-data", StringIndexOutOfBoundsException.class),
                Arguments.of("bare filename=", "filename=", StringIndexOutOfBoundsException.class),
                Arguments.of("filename= with an opening quote only", "filename=\"",
                        StringIndexOutOfBoundsException.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("headersTheOriginalExpressionFailsOn")
    void extractFileNameFailsAsTheOriginalExpressionDid(String scenario, String contentDisposition,
                                                        Class<? extends RuntimeException> expected) {
        assertThatThrownBy(() -> UploadService.extractFileName(contentDisposition)).isExactlyInstanceOf(expected);
    }

    /** Uploads that succeed: file name, logged content type, content. */
    static Stream<Arguments> storedUploads() throws IOException {
        byte[] everyByteValue = new byte[256];
        IntStream.range(0, everyByteValue.length).forEach(i -> everyByteValue[i] = (byte) i);
        return Stream.of(
                Arguments.of("carried test.txt as text/plain", TEST_TXT_DISPOSITION, "text/plain",
                        "test.txt", classpath("test.txt")),
                Arguments.of("content type parameters are logged verbatim", TEST_TXT_DISPOSITION,
                        "text/plain; charset=UTF-8", "test.txt", classpath("test.txt")),
                Arguments.of("binary content is stored and returned byte for byte",
                        "form-data; name=\"file\"; filename=\"data.bin\"", "application/octet-stream",
                        "data.bin", everyByteValue),
                Arguments.of("part without Content-Type logs null", TEST_TXT_DISPOSITION, null,
                        "test.txt", classpath("test.txt")),
                Arguments.of("empty part stores an empty file",
                        "form-data; name=\"file\"; filename=\"empty.txt\"", "text/plain", "empty.txt", new byte[0]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("storedUploads")
    void uploadLogsTheContentTypeStoresTheContentAndReturnsIt(String scenario, String contentDisposition,
                                                              String contentType, String fileName, byte[] content)
            throws IOException {
        TrackingInputStream stream = new TrackingInputStream(content);
        Part part = part(contentDisposition, contentType, stream);

        byte[] body = service.upload(part);

        assertThat(body).isEqualTo(content);
        Path stored = uploadDirectory.resolve(fileName);
        assertThat(regularFilesUnder(root)).containsExactly(stored);
        assertThat(Files.readAllBytes(stored)).isEqualTo(content);
        assertThat(stream.closed).isTrue();
        assertThat(appender.list)
                .extracting(ILoggingEvent::getLoggerName, ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(tuple(UploadService.class.getName(), Level.ERROR, String.valueOf(contentType)));
    }

    @Test
    void uploadReplacesAnExistingFileOfTheSameName() throws IOException {
        Files.createDirectories(uploadDirectory);
        Path stored = uploadDirectory.resolve("test.txt");
        Files.writeString(stored, "previous content, longer than the uploaded one", StandardCharsets.UTF_8);
        byte[] content = classpath("test.txt");

        byte[] body = service.upload(part(TEST_TXT_DISPOSITION, "text/plain", new TrackingInputStream(content)));

        assertThat(body).isEqualTo(content);
        assertThat(Files.readAllBytes(stored)).isEqualTo(content);
        assertThat(regularFilesUnder(root)).containsExactly(stored);
    }

    @Test
    void uploadWithoutThePartFailsBeforeLoggingOrWriting() {
        assertThatIllegalStateException()
                .isThrownBy(() -> service.upload(null))
                .withMessage("Multipart part 'file' is not present in the request");

        assertThat(appender.list).isEmpty();
        assertThat(root).isEmptyDirectory();
    }

    /** Content-Disposition values the file-name expression fails on, with the exception it raises. */
    static Stream<Arguments> unusableDispositions() {
        return Stream.of(
                Arguments.of("absent Content-Disposition", null, NullPointerException.class),
                Arguments.of("Content-Disposition too short for the name range", "form-data",
                        StringIndexOutOfBoundsException.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableDispositions")
    void uploadWhoseNameCannotBeExtractedFailsBeforeLoggingOrReading(String scenario, String contentDisposition,
                                                                     Class<? extends RuntimeException> expected)
            throws IOException {
        Part part = part(contentDisposition, "text/plain", new TrackingInputStream(classpath("test.txt")));

        assertThatThrownBy(() -> service.upload(part)).isExactlyInstanceOf(expected);

        verify(part, never()).getInputStream();
        assertThat(appender.list).isEmpty();
        assertThat(root).isEmptyDirectory();
    }

    @Test
    void unreadablePartFailsWithItsOwnIOExceptionAfterLoggingAndWritesNothing() throws IOException {
        IOException readFailure = new IOException("part content could not be read");
        FailingInputStream stream = new FailingInputStream(readFailure);
        Part part = part(TEST_TXT_DISPOSITION, "text/plain", stream);

        Throwable thrown = catchThrowable(() -> service.upload(part));

        assertThat(thrown).isSameAs(readFailure);
        assertThat(stream.closed).isTrue();
        // The logger precedes set-payload in the original flow, so the content type is logged before the read fails.
        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(tuple(Level.ERROR, "text/plain"));
        assertThat(root).isEmptyDirectory();
    }

    @Test
    void partWhoseStreamCannotBeOpenedFailsWithThatIOException() throws IOException {
        IOException openFailure = new IOException("part stream could not be opened");
        Part part = part(TEST_TXT_DISPOSITION, "text/plain", null);
        when(part.getInputStream()).thenThrow(openFailure);

        Throwable thrown = catchThrowable(() -> service.upload(part));

        assertThat(thrown).isSameAs(openFailure);
        assertThat(root).isEmptyDirectory();
    }

    @Test
    void writeFailureReachesTheCallerUnchangedAndLeavesTheFileSystemAsItWas() throws IOException {
        // A regular file where the upload directory should be: the writer cannot create the directory.
        Files.writeString(uploadDirectory, "not a directory", StandardCharsets.UTF_8);
        TrackingInputStream stream = new TrackingInputStream(classpath("test.txt"));

        Throwable thrown = catchThrowable(() -> service.upload(part(TEST_TXT_DISPOSITION, "text/plain", stream)));

        // The writer's exception as it threw it: Mule's streaming failure, which names no path, caused by the
        // file-system exception naming the directory it could not create.
        assertThat(thrown).isExactlyInstanceOf(IOException.class)
                .hasMessage("Streaming failed. Could not get output stream")
                .cause()
                .isInstanceOfSatisfying(FileSystemException.class,
                        failure -> assertThat(failure.getFile()).isEqualTo(uploadDirectory.toString()));
        assertThat(stream.closed).isTrue();
        assertThat(Files.readString(uploadDirectory, StandardCharsets.UTF_8)).isEqualTo("not a directory");
        assertThat(regularFilesUnder(root)).containsExactly(uploadDirectory);
    }

    @Test
    void reasonPhraseIsTheOriginalResponseBuilderPhrase() {
        assertThat(UploadService.REASON_PHRASE).isEqualTo("File was uploaded.");
    }

    @Test
    void uploadFormIsTheOriginalPageByteForByte() throws IOException {
        byte[] page = service.uploadForm().getBytes(StandardCharsets.UTF_8);

        assertThat(page).isEqualTo(classpath("uploadFile.html"));
        assertThat(page).hasSize(ORIGINAL_UPLOAD_FORM_SIZE);
        assertThat(md5(page)).isEqualTo(ORIGINAL_UPLOAD_FORM_MD5);
        assertThat(appender.list).isEmpty();
        assertThat(root).isEmptyDirectory();
    }

    @Test
    void uploadFormFailsWhenTheTemplateIsNotOnTheClasspath() {
        UploadService withoutTemplate = new UploadService(new ParseTemplateRenderer(new ClassLoader(null) { }),
                new FileOutboundWriter(), uploadDirectory.toString());

        assertThatIllegalStateException()
                .isThrownBy(withoutTemplate::uploadForm)
                .withMessage("Template not found: uploadFile.html");
    }

    /**
     * A lenient mock of the multipart part {@code file}, answering the accessors {@link UploadService#upload(Part)}
     * reads; lenient because a failing upload stops before it reads every one.
     */
    private static Part part(String contentDisposition, String contentType, InputStream content) throws IOException {
        Part part = mock(Part.class, withSettings().strictness(Strictness.LENIENT));
        when(part.getHeader("Content-Disposition")).thenReturn(contentDisposition);
        when(part.getHeader("Content-Type")).thenReturn(contentType);
        when(part.getInputStream()).thenReturn(content);
        return part;
    }

    private static byte[] classpath(String location) throws IOException {
        try (InputStream in = UploadServiceTest.class.getClassLoader().getResourceAsStream(location)) {
            assertThat(in).as("classpath resource %s", location).isNotNull();
            return in.readAllBytes();
        }
    }

    private static List<Path> regularFilesUnder(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static String md5(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is a mandatory JDK algorithm", e);
        }
    }

    /** Part content that records whether the service closed it. */
    private static final class TrackingInputStream extends ByteArrayInputStream {

        boolean closed;

        TrackingInputStream(byte[] content) {
            super(content);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    /** Part content whose every read fails with one given exception, recording whether the service closed it. */
    private static final class FailingInputStream extends InputStream {

        private final IOException failure;

        boolean closed;

        FailingInputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public int read() throws IOException {
            throw failure;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            throw failure;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
