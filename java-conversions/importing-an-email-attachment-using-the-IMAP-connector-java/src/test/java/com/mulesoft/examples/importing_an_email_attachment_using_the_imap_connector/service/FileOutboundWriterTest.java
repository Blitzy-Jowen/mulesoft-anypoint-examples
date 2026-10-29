/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test of {@link FileOutboundWriter} (shared component SC-6): the Mule 3.8 {@code file:outbound-endpoint}
 * write to {@code <dir>/<name>}, creating the directory and the missing parents of a nested name, joining a name
 * that starts with separators under the directory as Mule's {@code address + "/" + filename} did, overwriting an
 * existing file, writing bytes exactly and text as UTF-8, resolving a relative directory against the working
 * directory, reporting every failed write as an {@link IOException} with Mule's message
 * {@value #STREAMING_FAILED}, which names no path, and the failure as its cause, and the default
 * {@code <uuid>.dat} name.
 *
 * <p>The overwrite is a whole-file replacement. Simultaneous writers of one name, each with its own instance, never
 * interleave and never expose an empty or partial file to a reader; a write through a symbolic link that lies inside
 * the directory and leads to an entry inside it, at the target or in place of a directory, follows it, replacing the
 * linked file and keeping the link; a link that leads out of the directory, or one met once the name's own {@code ..}
 * has left it, is refused, and nothing outside the directory is created, written, re-moded or removed, also when the
 * destination, its directory, the directory a missing directory is created in or the temporary file is swapped for a
 * link while the write runs; a directory the process may search but not read fails the write where the walk must
 * hold it open, and a missing directory that cannot be created relative to its open directory is never created by
 * its path instead; an overwrite keeps the file's POSIX permissions, and the temporary file replacing an existing
 * file is owner-only while it receives the content; a read-only file is refused unless the process may write it; in
 * a directory that refuses new entries an existing file is still overwritten, in place, and a new one is refused
 * unless the process may create it; an existing file in a sticky directory is overwritten; a named pipe is written
 * in place; a failed write leaves the existing content and no temporary file behind. The cases that concern how
 * directories are reached run with each {@link FileOutboundWriter.DirectoryAccess}: the directory-relative walk the
 * platform offers, and the path-name walk used where it offers none.
 *
 * <p>Every project that carries {@code FileOutboundWriter} carries this test with only the {@code package} line
 * changed, so it depends on the writer, JUnit 5, AssertJ and the JDK alone: no Spring context, no mock, no project
 * type and no resource file. Every file it writes lands under its {@link TempDir}.
 */
class FileOutboundWriterTest {

    /**
     * The message of every failed write: Mule 3.8.0's {@code CoreMessages.streamingFailedNoStream()}, core message
     * 205, with which its file connector wrapped the {@link IOException} of an outbound file write.
     */
    private static final String STREAMING_FAILED = "Streaming failed. Could not get output stream";

    /** The reason of the failure refusing a symbolic link that is not confined to the target directory. */
    private static final String LINK_NOT_CONFINED = "Symbolic link is not confined to the target directory";

    /** The reason of the failure ending a write whose temporary file was swapped for another entry. */
    private static final String TEMPORARY_FILE_REPLACED = "Temporary file was replaced while it was being written";

    /**
     * The reason of the failure ending a write whose missing directory cannot be created relative to the open
     * directory that is to hold it.
     */
    private static final String NOT_CREATED_RELATIVE = "Directory cannot be created relative to the open directory";

    private static final Pattern DEFAULT_NAME =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.dat");

    /** The name of a temporary file: hidden, a random version-4 UUID and {@code .tmp}. */
    private static final Pattern TEMPORARY_NAME =
            Pattern.compile("\\.[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.tmp");

    /** Simultaneous writers of one file name. */
    private static final int WRITERS = 8;

    /** Writes each simultaneous writer makes. */
    private static final int ROUNDS = 4;

    /** One MiB and seven bytes: many times an 8 KiB write buffer, and not a multiple of it. */
    private static final int LARGE_SIZE = 1024 * 1024 + 7;

    /** Fill byte of the file before the simultaneous writers start; the writers use 1 to {@link #WRITERS}. */
    private static final byte INITIAL_FILL = 0x40;

    /** Upper bound, in seconds, of every wait on another thread. */
    private static final long WAIT_SECONDS = 60;

    /** Readable and writable by the owner only. */
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    /** A directory mode that lets everyone list and search the directory and no one but a superuser add to it. */
    private static final Set<PosixFilePermission> READ_AND_SEARCH_ONLY = PosixFilePermissions.fromString("r-xr-xr-x");

    /** The four {@code write} overloads, each called with its own directory and content types. */
    private enum Overload {
        PATH_BYTES, PATH_STRING, STRING_BYTES, STRING_STRING;

        Path write(FileOutboundWriter writer, Path dir, String name, String content) throws IOException {
            byte[] bytes = content == null ? null : content.getBytes(StandardCharsets.UTF_8);
            String configuredDir = dir == null ? null : dir.toString();
            return switch (this) {
                case PATH_BYTES -> writer.write(dir, name, bytes);
                case PATH_STRING -> writer.write(dir, name, content);
                case STRING_BYTES -> writer.write(configuredDir, name, bytes);
                case STRING_STRING -> writer.write(configuredDir, name, content);
            };
        }
    }

    @TempDir
    Path tempDir;

    private final FileOutboundWriter writer = new FileOutboundWriter();

    @ParameterizedTest
    @EnumSource(Overload.class)
    @DisplayName("a missing directory and its missing parents are created; dir/name is written and returned")
    void createsMissingDirectoriesAndReturnsTheWrittenFile(Overload overload) throws IOException {
        Path dir = tempDir.resolve("output").resolve("2016").resolve("orders");

        Path written = overload.write(writer, dir, "orders.json", "{}");

        assertThat(dir).isDirectory();
        assertThat(written).isEqualTo(dir.resolve("orders.json")).isRegularFile();
        assertThat(Files.readAllBytes(written)).isEqualTo(new byte[] {'{', '}'});
        try (Stream<Path> listing = Files.list(dir)) {
            assertThat(listing).containsExactly(written);
        }
    }

    @ParameterizedTest
    @EnumSource(Overload.class)
    @DisplayName("an existing file of the same name is replaced and truncated; other files in the directory stay")
    void overwritesAndTruncatesAnExistingFile(Overload overload) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("output"));
        Path existing = Files.writeString(dir.resolve("report.csv"), "an older and much longer report\n");
        Path sibling = Files.writeString(dir.resolve("orders.json"), "untouched");

        Path written = overload.write(writer, dir, "report.csv", "new\n");

        assertThat(written).isEqualTo(existing);
        assertThat(Files.readAllBytes(existing)).isEqualTo(new byte[] {'n', 'e', 'w', '\n'});
        assertThat(Files.readString(sibling)).isEqualTo("untouched");
    }

    static Stream<Arguments> byteContents() {
        byte[] everyByteValue = new byte[256];
        for (int i = 0; i < everyByteValue.length; i++) {
            everyByteValue[i] = (byte) i;
        }
        byte[] crLfWithoutTrailingLineEnd = {'a', '\r', '\n', 'b'};
        return Stream.of(
                Arguments.of("every byte value 0x00 to 0xFF, Path directory", true, everyByteValue),
                Arguments.of("every byte value 0x00 to 0xFF, String directory", false, everyByteValue),
                Arguments.of("CR LF kept and no line end appended, Path directory", true, crLfWithoutTrailingLineEnd),
                Arguments.of("CR LF kept and no line end appended, String directory", false,
                        crLfWithoutTrailingLineEnd),
                Arguments.of("empty content -> empty file, Path directory", true, new byte[0]),
                Arguments.of("empty content -> empty file, String directory", false, new byte[0]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("byteContents")
    @DisplayName("byte content is written exactly as given: no encoding, no line-end conversion, nothing appended")
    void writesBytesExactly(String description, boolean pathDirectory, byte[] content) throws IOException {
        Path dir = tempDir.resolve("output");

        Path written = pathDirectory
                ? writer.write(dir, "payload.dat", content)
                : writer.write(dir.toString(), "payload.dat", content);

        assertThat(Files.readAllBytes(written)).isEqualTo(content);
    }

    @ParameterizedTest
    @EnumSource(value = Overload.class, names = {"PATH_STRING", "STRING_STRING"})
    @DisplayName("String content is encoded as UTF-8 with no byte order mark, whatever the platform charset")
    void encodesStringContentAsUtf8(Overload overload) throws IOException {
        String text = "Jos" + cp(0xE9) + " " + cp(0x20AC) + " " + cp(0x1F600) + "\r\n";

        Path written = overload.write(writer, tempDir.resolve("output"), "text.txt", text);

        assertThat(Files.readAllBytes(written)).isEqualTo(bytes(
                'J', 'o', 's', 0xC3, 0xA9, ' ', 0xE2, 0x82, 0xAC, ' ', 0xF0, 0x9F, 0x98, 0x80, '\r', '\n'));
    }

    @ParameterizedTest
    @EnumSource(value = Overload.class, names = {"STRING_BYTES", "STRING_STRING"})
    @DisplayName("a relative directory string resolves against the working directory, and the relative file is returned")
    void resolvesARelativeDirectoryAgainstTheWorkingDirectory(Overload overload) throws IOException {
        // Real paths on both sides, so the ".." steps walk the physical parents the operating system follows.
        Path workingDirectory = Path.of("").toAbsolutePath().toRealPath();
        Path target = tempDir.toRealPath().resolve("relative-output");
        Path relative = workingDirectory.relativize(target);

        Path written = overload.write(writer, relative, "orders.json", "{}");

        assertThat(relative.isAbsolute()).isFalse();
        assertThat(written).isEqualTo(relative.resolve("orders.json"));
        assertThat(Files.readString(target.resolve("orders.json"))).isEqualTo("{}");
    }

    /**
     * A directory path that is an existing regular file, and a file name that is an existing directory: each write
     * fails with Mule's streaming failure, caused by the file-system exception, and nothing changes.
     *
     * @param overload the {@code write} overload called
     * @throws IOException when a file operation of the test fails
     */
    @ParameterizedTest
    @EnumSource(Overload.class)
    @DisplayName("an IOException from creating the directory or writing the file is the cause of the streaming"
            + " failure the caller receives, nothing changes")
    void reportsEveryIOExceptionAsTheStreamingFailure(Overload overload) throws IOException {
        Path regularFile = Files.writeString(tempDir.resolve("output"), "a regular file");
        Path existingDirectory = Files.createDirectory(tempDir.resolve("existing-directory"));
        Path inside = Files.writeString(existingDirectory.resolve("inside.txt"), "kept");

        assertThatThrownBy(() -> overload.write(writer, regularFile, "orders.json", "payload"))
                .as("directory path is an existing regular file")
                .isExactlyInstanceOf(IOException.class)
                .hasMessage(STREAMING_FAILED)
                .cause()
                .isInstanceOfSatisfying(FileAlreadyExistsException.class, failure ->
                        assertThat(failure.getFile()).isEqualTo(regularFile.toString()));
        assertThatThrownBy(() -> overload.write(writer, tempDir, "existing-directory", "payload"))
                .as("file name is an existing directory")
                .isExactlyInstanceOf(IOException.class)
                .hasMessage(STREAMING_FAILED)
                .cause()
                .isInstanceOfSatisfying(FileSystemException.class, failure ->
                        assertThat(failure.getReason()).isEqualTo("Is a directory"));

        assertThat(Files.readString(regularFile)).isEqualTo("a regular file");
        assertThat(existingDirectory).isDirectory();
        try (Stream<Path> listing = Files.list(existingDirectory)) {
            assertThat(listing).containsExactly(inside);
        }
        assertThat(Files.readString(inside)).isEqualTo("kept");
    }

    /**
     * Names that start with separators, as an uploaded file's name can. Each continues with the absolute path, less
     * its leading {@code /}, of a directory beside the target directory, so a write that took the name for an
     * absolute path would land there. The directory is given as an absolute {@link Path} and as a string relative to
     * the working directory.
     *
     * @return description, the separators the name starts with, the rest of the name after the outside directory's
     *         path, whether the directory is given relative to the working directory
     */
    static Stream<Arguments> namesStartingWithSeparators() {
        return Stream.of(
                Arguments.of("one slash: an absolute path", "/", "qa-absolute.txt", false),
                Arguments.of("two slashes", "//", "qa-absolute.txt", false),
                Arguments.of("three slashes before a nested name", "///", "etc/qa.txt", false),
                Arguments.of("one slash, directory relative to the working directory", "/", "qa-absolute.txt", true));
    }

    /**
     * A name's leading separators do not take the file out of the directory: Mule joined the two as
     * {@code address + "/" + filename}, which {@link java.io.File} normalised by collapsing the separators, so the
     * expected file is {@link Path#of(String, String...)} of that joined text, which collapses them the same way.
     * The file holds the exact bytes, its missing parents are created, the directory the name spells is left empty
     * and nothing is written beside the target directory.
     *
     * @param description       the case, for the test name
     * @param separators        the separators the name starts with
     * @param rest              the name after the outside directory's path
     * @param relativeDirectory whether the directory is given as a string relative to the working directory
     * @throws IOException when a file operation of the test fails
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("namesStartingWithSeparators")
    void leadingSeparatorsOfANameKeepTheFileUnderTheDirectory(String description, String separators, String rest,
            boolean relativeDirectory) throws IOException {
        // Real paths, so the relative directory's ".." steps walk the physical parents the operating system follows.
        Path root = tempDir.toRealPath();
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path absoluteDir = root.resolve("out");
        Path dir = relativeDirectory
                ? Path.of("").toAbsolutePath().toRealPath().relativize(absoluteDir)
                : absoluteDir;
        String outsideWithoutRoot = outside.toString().substring(1);
        String name = separators + outsideWithoutRoot + "/" + rest;
        byte[] content = "QA_SECURITY_CONTENT".getBytes(StandardCharsets.UTF_8);

        Path written = relativeDirectory
                ? writer.write(dir.toString(), name, content)
                : writer.write(dir, name, content);

        assertThat(written).isEqualTo(Path.of(dir + "/" + name));
        assertThat(written.isAbsolute()).isEqualTo(!relativeDirectory);
        assertThat(Files.readAllBytes(absoluteDir.resolve(outsideWithoutRoot).resolve(rest))).isEqualTo(content);
        assertThat(outside).isEmptyDirectory();
        assertThat(names(root)).containsExactly("out", "outside");
    }

    /**
     * Nested names whose parent directories do not exist yet, in each directory access: one missing parent, three, a
     * name that climbs out of the directory with {@code ..} into a missing sibling, which is kept as given, as Mule
     * kept it, and one that climbs out and back in again.
     *
     * @return access, description, the name
     */
    static Stream<Arguments> nestedNamesWithMissingParents() {
        return withEachAccess(Stream.of(
                Arguments.of("one missing parent", "qa-sub/qa-nested.txt"),
                Arguments.of("three missing parents", "a/b/c/deep.txt"),
                Arguments.of(".. into a missing sibling of the directory", "../sibling/up.txt"),
                Arguments.of(".. out of the directory and back into a missing parent", "../inner/back/in.txt")));
    }

    /**
     * The missing parent directories of a nested name are created, as Mule's {@code FileUtils.createFile} created
     * them, and the file holds the exact bytes; a second write of the name replaces it as a whole and leaves no
     * temporary file beside it.
     *
     * @param access      how the writer reaches the directories
     * @param description the case, for the test name
     * @param name        the nested name
     * @throws Exception when a file operation of the test fails or the lock map cannot be read
     */
    @ParameterizedTest(name = "{1} ({0})")
    @MethodSource("nestedNamesWithMissingParents")
    void nestedNameGetsItsMissingParentDirectoriesCreated(FileOutboundWriter.DirectoryAccess access,
            String description, String name) throws Exception {
        FileOutboundWriter writer = new FileOutboundWriter(access);
        Path dir = tempDir.resolve("out").resolve("inner");
        byte[] first = bytes(0x00, 'Q', 'A', 0xFF, '\r', '\n');
        byte[] second = "replaced".getBytes(StandardCharsets.UTF_8);

        Path written = writer.write(dir, name, first);

        assertThat(written).isEqualTo(Path.of(dir + "/" + name));
        assertThat(Files.readAllBytes(written)).isEqualTo(first);

        Path rewritten = writer.write(dir, name, second);

        assertThat(rewritten).isEqualTo(written);
        assertThat(Files.readAllBytes(written)).isEqualTo(second);
        assertThat(names(written.getParent())).containsExactly(written.getFileName().toString());
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * Names whose write fails in a way whose own exception text names the directory or a temporary file, in each
     * directory access: names longer than the file system allows, at the first refused length and far beyond it,
     * whose lookup is refused; {@code .}, {@code ..} and the empty name, which resolve to directories; a name holding
     * a NUL character, which is no path; and a nested name whose parent is an existing regular file.
     *
     * @return access, description, the name, the type of the failure the streaming failure is caused by
     */
    static Stream<Arguments> namesWhoseWriteFails() {
        return withEachAccess(Stream.of(
                Arguments.of("256-character name", "x".repeat(256), FileSystemException.class),
                Arguments.of("1,000-character name", "a".repeat(1000), FileSystemException.class),
                Arguments.of("the name .", ".", FileSystemException.class),
                Arguments.of("the name ..", "..", FileSystemException.class),
                Arguments.of("the empty name", "", FileSystemException.class),
                Arguments.of("a name holding NUL", "a" + cp(0) + ".txt", InvalidPathException.class),
                Arguments.of("a missing parent blocked by a regular file", "blocker/qa-nested.txt",
                        FileAlreadyExistsException.class)));
    }

    /**
     * A failed write is reported with Mule's message alone, which names neither the directory nor a temporary file,
     * and the failure as its cause; every file and directory is left exactly as it was.
     *
     * @param access      how the writer reaches the directories
     * @param description the case, for the test name
     * @param name        the name whose write fails
     * @param cause       the type of the failure the streaming failure is caused by
     * @throws Exception when a file operation of the test fails or the lock map cannot be read
     */
    @ParameterizedTest(name = "{1} ({0})")
    @MethodSource("namesWhoseWriteFails")
    void failedWriteIsReportedWithAMessageNamingNoPath(FileOutboundWriter.DirectoryAccess access,
            String description, String name, Class<? extends Throwable> cause) throws Exception {
        FileOutboundWriter writer = new FileOutboundWriter(access);
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Files.writeString(dir.resolve("blocker"), "a regular file");
        Map<String, String> before = tree(tempDir);

        assertThatThrownBy(() -> writer.write(dir, name, "payload"))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage(STREAMING_FAILED)
                .hasMessageNotContaining(tempDir.toString())
                .hasMessageNotContaining(".tmp")
                .hasCauseExactlyInstanceOf(cause);

        assertThat(tree(tempDir)).isEqualTo(before);
        assertThat(lockedDestinations()).isEmpty();
    }

    static Stream<Arguments> nullArguments() {
        return Stream.of(Overload.values()).flatMap(overload -> Stream.of(
                Arguments.of(overload, "dir"),
                Arguments.of(overload, "name"),
                Arguments.of(overload, "content")));
    }

    @ParameterizedTest(name = "{0}: null {1}")
    @MethodSource("nullArguments")
    @DisplayName("a null dir, name or content -> NullPointerException naming it, before anything is created")
    void rejectsNullArguments(Overload overload, String nullArgument) {
        Path dir = "dir".equals(nullArgument) ? null : tempDir.resolve("output");
        String name = "name".equals(nullArgument) ? null : "orders.json";
        String content = "content".equals(nullArgument) ? null : "{}";

        assertThatThrownBy(() -> overload.write(writer, dir, name, content))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage(nullArgument);
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    @DisplayName("defaultFileName: a fresh random UUID followed by .dat on every call, usable as the written name")
    void defaultFileNameIsAFreshRandomUuidWithDatExtension() throws IOException {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String name = FileOutboundWriter.defaultFileName();
            UUID uuid = UUID.fromString(name.substring(0, name.length() - ".dat".length()));

            assertThat(name).matches(DEFAULT_NAME).isEqualTo(uuid + ".dat");
            assertThat(uuid.version()).isEqualTo(4);
            assertThat(uuid.variant()).isEqualTo(2);
            names.add(name);
        }
        assertThat(names).hasSize(100);

        String name = FileOutboundWriter.defaultFileName();
        Path written = writer.write(tempDir, name, new byte[] {1});

        assertThat(written.getFileName()).hasToString(name);
        assertThat(Files.readAllBytes(written)).isEqualTo(new byte[] {1});
    }

    /**
     * Eight writers, each with its own instance and released together, write the same name several times with
     * content far larger than one write buffer, while a reader reads the file over and over. Every snapshot the
     * reader takes, and the final file, is one writer's content whole, and no temporary file is left behind.
     *
     * @throws Exception when a file operation or a wait on another thread fails, or the lock map cannot be read
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void simultaneousSameNameWritesNeverInterleaveOrExposePartialContent() throws Exception {
        Path dir = tempDir.resolve("out");
        String name = "orders.json";
        Path target = writer.write(dir, name, filled(INITIAL_FILL));
        CountDownLatch ready = new CountDownLatch(WRITERS + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean writing = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS + 1);
        try {
            Future<Integer> reader = pool.submit(() -> {
                awaitRelease(ready, start);
                int snapshots = 0;
                do {
                    assertWhole(Files.readAllBytes(target));
                    snapshots++;
                } while (writing.get());
                return snapshots;
            });
            List<Future<Path>> writers = new ArrayList<>();
            for (int fill = 1; fill <= WRITERS; fill++) {
                byte[] content = filled((byte) fill);
                writers.add(pool.submit(() -> {
                    FileOutboundWriter own = new FileOutboundWriter();
                    awaitRelease(ready, start);
                    Path written = null;
                    for (int round = 0; round < ROUNDS; round++) {
                        written = own.write(dir, name, content);
                    }
                    return written;
                }));
            }
            assertThat(ready.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            try {
                for (Future<Path> each : writers) {
                    assertThat(await(each)).isEqualTo(target);
                }
            } finally {
                writing.set(false);
            }
            assertThat(await(reader)).isPositive();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        byte[] result = Files.readAllBytes(target);
        assertWhole(result);
        assertThat(result[0]).isBetween((byte) 1, (byte) WRITERS);
        assertThat(names(dir)).containsExactly(name);
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * Symbolic links at the target that lie inside the target directory and lead to an entry inside it, in each
     * directory access: relative and absolute link text, a link into a subdirectory, a chain of links, and links
     * whose file does not exist yet.
     *
     * @return access, description, absolute link text, linked file in a subdirectory, chain length, linked file
     *         exists
     */
    static Stream<Arguments> symbolicLinks() {
        return withEachAccess(Stream.of(
                Arguments.of("relative link to an existing file", false, false, 1, true),
                Arguments.of("absolute link to an existing file", true, false, 1, true),
                Arguments.of("relative link into a subdirectory", false, true, 1, true),
                Arguments.of("chain of two links to an existing file", false, false, 2, true),
                Arguments.of("relative dangling link", false, false, 1, false),
                Arguments.of("absolute dangling link into a subdirectory", true, true, 1, false)));
    }

    /**
     * Writing through a symbolic link confined to the target directory replaces, or for a dangling link creates, the
     * file the link chain ends at, keeps every link unchanged and leaves no temporary file in either directory.
     *
     * @param access           how the writer reaches the directories
     * @param description      the case, for the test name
     * @param absolute         whether the link text is an absolute path rather than one relative to the link
     * @param subdirectory     whether the linked file lives in a subdirectory of the target directory
     * @param links            the number of links in the chain
     * @param linkedFileExists whether the linked file exists before the write
     * @throws Exception when a file operation of the test fails or the lock map cannot be read
     */
    @ParameterizedTest(name = "{1} ({0})")
    @MethodSource("symbolicLinks")
    void writeThroughSymbolicLinkReplacesTheLinkedFileAndKeepsTheLink(FileOutboundWriter.DirectoryAccess access,
            String description, boolean absolute, boolean subdirectory, int links, boolean linkedFileExists)
            throws Exception {
        FileOutboundWriter writer = new FileOutboundWriter(access);
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Path linkedDir = subdirectory ? Files.createDirectories(dir.resolve("sub")) : dir;
        Path linked = linkedDir.resolve("linked.json");
        if (linkedFileExists) {
            Files.writeString(linked, "old content");
        }
        Map<Path, Path> linkTexts = new TreeMap<>();
        Path next = linked;
        for (int i = 1; i <= links; i++) {
            Path link = dir.resolve("link" + i + ".json");
            Path text = absolute ? next.toAbsolutePath() : dir.relativize(next);
            Files.createSymbolicLink(link, text);
            linkTexts.put(link, text);
            next = link;
        }

        Path written = writer.write(dir, next.getFileName().toString(), "new");

        assertThat(written).isEqualTo(next);
        for (Map.Entry<Path, Path> link : linkTexts.entrySet()) {
            assertThat(Files.isSymbolicLink(link.getKey())).isTrue();
            assertThat(Files.readSymbolicLink(link.getKey())).isEqualTo(link.getValue());
        }
        assertThat(Files.isRegularFile(linked, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.readString(linked)).isEqualTo("new");
        List<String> expectedNames = new ArrayList<>();
        linkTexts.keySet().forEach(link -> expectedNames.add(link.getFileName().toString()));
        if (subdirectory) {
            assertThat(names(linkedDir)).containsExactly("linked.json");
            expectedNames.add("sub");
        } else {
            expectedNames.add("linked.json");
        }
        assertThat(names(dir)).containsExactlyInAnyOrderElementsOf(expectedNames);
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * Symbolic links that are not confined to the target directory {@code out}, beside which the directory
     * {@code outside} holds the owned fixture file {@code secret.txt}, in each directory access. Each link is written
     * {@code name -> text}, with <code>{outside}</code> and <code>{out}</code> standing for the absolute paths of the
     * two directories; {@code out} also holds the empty directory {@code real}. At the target: relative and absolute
     * text to the fixture, dangling text into the outside directory, a chain whose last link escapes, and text that
     * climbs above the target directory with {@code ..}, through a subdirectory, after the directory's own absolute
     * path, or from a link in a subdirectory. In place of a directory: relative and absolute text to the outside
     * directory, a link in a subdirectory climbing out, and an escaping link with a missing directory below it.
     *
     * @return access, description, the name written, the link the failure names, relative to {@code out}, the links
     */
    static Stream<Arguments> linksNotConfinedToTheDirectory() {
        return withEachAccess(Stream.of(
                Arguments.of("relative link to a file outside", "victim.txt", "victim.txt",
                        new String[] {"victim.txt -> ../outside/secret.txt"}),
                Arguments.of("absolute link to a file outside", "victim.txt", "victim.txt",
                        new String[] {"victim.txt -> {outside}/secret.txt"}),
                Arguments.of("absolute dangling link into the outside directory", "victim.txt", "victim.txt",
                        new String[] {"victim.txt -> {outside}/new.txt"}),
                Arguments.of("relative dangling link into the outside directory", "victim.txt", "victim.txt",
                        new String[] {"victim.txt -> ../outside/new.txt"}),
                Arguments.of("chain whose last link escapes", "victim.txt", "hop.txt",
                        new String[] {"victim.txt -> hop.txt", "hop.txt -> ../outside/secret.txt"}),
                Arguments.of("relative text climbing out through a subdirectory", "victim.txt", "victim.txt",
                        new String[] {"victim.txt -> real/../../outside/secret.txt"}),
                Arguments.of("absolute text under the directory climbing out", "victim.txt", "victim.txt",
                        new String[] {"victim.txt -> {out}/../outside/secret.txt"}),
                Arguments.of("link in a subdirectory climbing out", "real/victim.txt", "real/victim.txt",
                        new String[] {"real/victim.txt -> ../../outside/secret.txt"}),
                Arguments.of("relative link to the outside directory on the way", "sub/report.csv", "sub",
                        new String[] {"sub -> ../outside"}),
                Arguments.of("absolute link to the outside directory on the way", "sub/report.csv", "sub",
                        new String[] {"sub -> {outside}"}),
                Arguments.of("link in a subdirectory climbing out on the way", "real/sub/report.csv", "real/sub",
                        new String[] {"real/sub -> ../../outside"}),
                Arguments.of("missing directory below an escaping link", "sub/new/report.csv", "sub",
                        new String[] {"sub -> ../outside"})));
    }

    /**
     * A symbolic link that leads out of the target directory is refused: the write fails with Mule's streaming
     * failure, caused by a {@link FileSystemException} naming the refused link with the confinement reason. The
     * fixture file outside keeps its bytes and mode, the outside directory gains no entry, every link and entry of the
     * target directory is left as it was, no temporary file remains and the lock entry is released.
     *
     * @param access      how the writer reaches the directories
     * @param description the case, for the test name
     * @param name        the name written
     * @param refused     the link the failure names, relative to the target directory
     * @param links       the links to create, as {@code name -> text}
     * @throws Exception when a file operation of the test fails or the lock map cannot be read
     */
    @ParameterizedTest(name = "{1} ({0})")
    @MethodSource("linksNotConfinedToTheDirectory")
    void linkNotConfinedToTheDirectoryIsRefusedAndNothingOutsideChanges(FileOutboundWriter.DirectoryAccess access,
            String description, String name, String refused, String[] links) throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Files.createDirectories(dir.resolve("real"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.txt"), "secret");
        Set<PosixFilePermission> secretMode = PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(secret, secretMode);
        createLinks(dir, outside, links);
        Map<String, String> outsideBefore = tree(outside);
        Map<String, String> dirBefore = tree(dir);

        assertThatThrownBy(() -> new FileOutboundWriter(access).write(dir, name, "QA_SECURITY_CONTENT"))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage(STREAMING_FAILED)
                .cause()
                .isExactlyInstanceOf(FileSystemException.class)
                .isInstanceOfSatisfying(FileSystemException.class, failure -> {
                    assertThat(failure.getFile()).isEqualTo(dir.resolve(refused).toString());
                    assertThat(failure.getReason()).isEqualTo(LINK_NOT_CONFINED);
                });

        assertThat(Files.readString(secret)).isEqualTo("secret");
        assertThat(Files.getPosixFilePermissions(secret, LinkOption.NOFOLLOW_LINKS)).isEqualTo(secretMode);
        assertThat(tree(outside)).isEqualTo(outsideBefore);
        assertThat(tree(dir)).isEqualTo(dirBefore);
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * Symbolic links in place of a directory the name leads through that lie inside the target directory {@code out}
     * and lead to a directory inside it, in each directory access, written as in
     * {@link #linksNotConfinedToTheDirectory()}, with <code>{real}</code> standing for the real path of {@code out};
     * {@code out} holds the empty directories {@code real} and {@code other}: relative text, absolute text by the
     * directory's path and by its real path, a link in a subdirectory climbing back into the directory, a missing
     * directory below a link, and links to the directory itself.
     *
     * @return access, description, the name written, the file written to, relative to {@code out}, the links
     */
    static Stream<Arguments> linksConfinedToTheDirectoryOnTheWay() {
        return withEachAccess(Stream.of(
                Arguments.of("relative link to a subdirectory", "sub-link/report.csv", "real/report.csv",
                        new String[] {"sub-link -> real"}),
                Arguments.of("absolute link to a subdirectory", "sub-link/report.csv", "real/report.csv",
                        new String[] {"sub-link -> {out}/real"}),
                Arguments.of("absolute link by the directory's real path", "sub-link/report.csv", "real/report.csv",
                        new String[] {"sub-link -> {real}/real"}),
                Arguments.of("link in a subdirectory climbing back into the directory", "real/up/report.csv",
                        "other/report.csv", new String[] {"real/up -> ../other"}),
                Arguments.of("missing directory below a link", "sub-link/new/report.csv", "real/new/report.csv",
                        new String[] {"sub-link -> real"}),
                Arguments.of("absolute link to the directory itself", "self/report.csv", "report.csv",
                        new String[] {"self -> {out}"}),
                Arguments.of("relative link to the directory itself", "self/report.csv", "report.csv",
                        new String[] {"self -> ."})));
    }

    /**
     * A symbolic link in place of a directory is followed when it stays inside the target directory: the file lands
     * where the link leads, holding the exact bytes, with any missing directory below the link created there; the
     * written path is the one the caller named; the link and every other entry are kept, no temporary file remains
     * and the lock entry is released.
     *
     * @param access      how the writer reaches the directories
     * @param description the case, for the test name
     * @param name        the name written
     * @param expected    the file the content must land in, relative to the target directory
     * @param links       the links to create, as {@code name -> text}
     * @throws Exception when a file operation of the test fails or the lock map cannot be read
     */
    @ParameterizedTest(name = "{1} ({0})")
    @MethodSource("linksConfinedToTheDirectoryOnTheWay")
    void linkConfinedToTheDirectoryOnTheWayIsFollowed(FileOutboundWriter.DirectoryAccess access, String description,
            String name, String expected, String[] links) throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Files.createDirectories(dir.resolve("real"));
        Files.createDirectories(dir.resolve("other"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        createLinks(dir, outside, links);
        Map<String, String> before = tree(dir);

        Path written = new FileOutboundWriter(access).write(dir, name, "QA_CONFINED_CONTENT");

        assertThat(written).isEqualTo(dir.resolve(name));
        assertThat(Files.readString(dir.resolve(expected))).isEqualTo("QA_CONFINED_CONTENT");
        Set<String> expectedEntries = new TreeSet<>(before.keySet());
        for (Path entry = Path.of(expected); entry != null; entry = entry.getParent()) {
            expectedEntries.add(entry.toString());
        }
        Map<String, String> after = tree(dir);
        assertThat(after.keySet()).containsExactlyInAnyOrderElementsOf(expectedEntries);
        before.forEach((entry, kind) -> assertThat(after.get(entry)).isEqualTo(kind));
        assertThat(outside).isEmptyDirectory();
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * Symbolic links met once the name's own {@code ..} took the walk above the target directory {@code out/inner},
     * in each directory access: each lies in {@code out} and leads back into the target directory, at the target and
     * in place of a directory.
     *
     * @return access, description, the name written, the link the failure names relative to {@code out}, the link
     *         text
     */
    static Stream<Arguments> linksBeyondTheNamesDotDot() {
        return withEachAccess(Stream.of(
                Arguments.of("link at the target", "../back.txt", "back.txt", "inner/target.txt"),
                Arguments.of("link in place of a directory", "../back/report.csv", "back", "inner")));
    }

    /**
     * The name's own {@code ..} is kept as given, as Mule kept it, but once it has taken the walk above the target
     * directory no symbolic link is followed, even one leading back inside: the write fails, caused by a
     * {@link FileSystemException} naming the link with the confinement reason, and nothing is created.
     *
     * @param access      how the writer reaches the directories
     * @param description the case, for the test name
     * @param name        the name written
     * @param link        the link the failure names, relative to the target directory's parent
     * @param text        the link's text
     * @throws Exception when a file operation of the test fails or the lock map cannot be read
     */
    @ParameterizedTest(name = "{1} ({0})")
    @MethodSource("linksBeyondTheNamesDotDot")
    void linkMetAfterTheNamesDotDotLeftTheDirectoryIsRefused(FileOutboundWriter.DirectoryAccess access,
            String description, String name, String link, String text) throws Exception {
        Path parent = Files.createDirectories(tempDir.resolve("out"));
        Path dir = Files.createDirectories(parent.resolve("inner"));
        Files.createSymbolicLink(parent.resolve(link), Path.of(text));
        Map<String, String> before = tree(tempDir);

        assertThatThrownBy(() -> new FileOutboundWriter(access).write(dir, name, "QA_SECURITY_CONTENT"))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage(STREAMING_FAILED)
                .cause()
                .isInstanceOfSatisfying(FileSystemException.class, failure -> {
                    assertThat(failure.getFile()).isEqualTo(dir.resolve("..").resolve(link).toString());
                    assertThat(failure.getReason()).isEqualTo(LINK_NOT_CONFINED);
                });

        assertThat(tree(tempDir)).isEqualTo(before);
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * Once the temporary file holds the content, the observer swaps the existing destination for a symbolic link to
     * an owned file outside the target directory: the link is replaced by the new file, never followed, so the
     * outside file keeps its bytes and mode and the destination is a regular file holding the new content.
     *
     * @param access how the writer reaches the directories
     * @throws Exception when a file operation of the test fails or the lock map cannot be read
     */
    @ParameterizedTest
    @EnumSource(FileOutboundWriter.DirectoryAccess.class)
    void destinationSwappedForALinkDuringTheWriteIsReplacedNotFollowed(FileOutboundWriter.DirectoryAccess access)
            throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Path destination = Files.writeString(dir.resolve("report.csv"), "old");
        Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rw-r-----"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.txt"), "secret");
        Set<PosixFilePermission> secretMode = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(secret, secretMode);
        FileOutboundWriter swapping = new FileOutboundWriter(access, temp -> {
            Files.delete(destination);
            Files.createSymbolicLink(destination, secret);
        });

        Path written = swapping.write(dir, "report.csv", "new");

        assertThat(written).isEqualTo(destination);
        assertThat(Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.readString(destination)).isEqualTo("new");
        assertThat(Files.readString(secret)).isEqualTo("secret");
        assertThat(Files.getPosixFilePermissions(secret)).isEqualTo(secretMode);
        assertThat(names(outside)).containsExactly("secret.txt");
        assertThat(names(dir)).containsExactly("report.csv");
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * Once the temporary file holds the content, the observer moves the destination's directory away and puts a
     * symbolic link to an outside directory in its place. The directory-relative walk keeps acting in the directory
     * it holds, so the file is replaced as a whole, by a new inode, in the moved directory with the replaced file's
     * mode, and nothing reaches the outside directory. Runs only where the platform offers the directory-relative
     * walk.
     *
     * @throws Exception when a file operation of the test fails or the lock map cannot be read
     */
    @Test
    void directorySwappedForALinkDuringTheWriteSendsNothingOutside() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Assumptions.assumeTrue(offersDirectoryRelativeWalk(dir), "no SecureDirectoryStream on this platform");
        Path sub = Files.createDirectories(dir.resolve("sub"));
        Set<PosixFilePermission> mode = PosixFilePermissions.fromString("rw-r-----");
        Path replaced = Files.writeString(sub.resolve("report.csv"), "old");
        Files.setPosixFilePermissions(replaced, mode);
        Object inode = Files.readAttributes(replaced, BasicFileAttributes.class).fileKey();
        Path moved = dir.resolve("moved");
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        FileOutboundWriter swapping = new FileOutboundWriter(FileOutboundWriter.DirectoryAccess.AUTOMATIC, temp -> {
            Files.move(sub, moved);
            Files.createSymbolicLink(sub, outside);
        });

        Path written = swapping.write(dir, "sub/report.csv", "new");

        assertThat(written).isEqualTo(sub.resolve("report.csv"));
        assertThat(outside).isEmptyDirectory();
        assertThat(Files.readSymbolicLink(sub)).isEqualTo(outside);
        assertThat(names(moved)).containsExactly("report.csv");
        assertThat(Files.readString(moved.resolve("report.csv"))).isEqualTo("new");
        assertThat(Files.getPosixFilePermissions(moved.resolve("report.csv"))).isEqualTo(mode);
        assertThat(Files.readAttributes(moved.resolve("report.csv"), BasicFileAttributes.class).fileKey())
                .isNotEqualTo(inode);
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * Once the temporary file holds the content, the observer swaps it for a symbolic link, or a hard link, to an
     * owned file outside the target directory whose mode differs from the destination's: the swapped-in entry is
     * neither given the destination's mode nor renamed into place, but removed, and the write fails, caused by a
     * {@link FileSystemException} naming the temporary file. The outside file keeps its bytes and mode, and the
     * destination its content and mode.
     *
     * @param access   how the writer reaches the directories
     * @param hardLink whether the swapped-in entry is a hard link rather than a symbolic link
     * @throws Exception when a file operation of the test fails or the lock map cannot be read
     */
    @ParameterizedTest(name = "{0}, hard link {1}")
    @MethodSource("eachAccessWithAndWithoutHardLink")
    void temporaryFileSwappedDuringTheWriteIsRemovedAndNothingOutsideChanges(
            FileOutboundWriter.DirectoryAccess access, boolean hardLink) throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Path destination = Files.writeString(dir.resolve("report.csv"), "old");
        Set<PosixFilePermission> destinationMode = PosixFilePermissions.fromString("rw-r--r--");
        Files.setPosixFilePermissions(destination, destinationMode);
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.txt"), "secret");
        Set<PosixFilePermission> secretMode = PosixFilePermissions.fromString("rw-------");
        Files.setPosixFilePermissions(secret, secretMode);
        List<Path> swapped = new ArrayList<>();
        FileOutboundWriter swapping = new FileOutboundWriter(access, temp -> {
            Files.delete(temp);
            if (hardLink) {
                Files.createLink(temp, secret);
            } else {
                Files.createSymbolicLink(temp, secret);
            }
            swapped.add(temp);
        });

        assertThatThrownBy(() -> swapping.write(dir, "report.csv", "new"))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage(STREAMING_FAILED)
                .cause()
                .isInstanceOfSatisfying(FileSystemException.class, failure -> {
                    assertThat(failure.getFile()).isEqualTo(swapped.get(0).toString());
                    assertThat(failure.getReason()).isEqualTo(TEMPORARY_FILE_REPLACED);
                });

        assertThat(Files.readString(secret)).isEqualTo("secret");
        assertThat(Files.getPosixFilePermissions(secret)).isEqualTo(secretMode);
        assertThat(names(outside)).containsExactly("secret.txt");
        assertThat(Files.readString(destination)).isEqualTo("old");
        assertThat(Files.getPosixFilePermissions(destination)).isEqualTo(destinationMode);
        assertThat(names(dir)).containsExactly("report.csv");
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * Each directory access, with the temporary file swapped for a symbolic link and for a hard link.
     *
     * @return access, whether the swapped-in entry is a hard link
     */
    static Stream<Arguments> eachAccessWithAndWithoutHardLink() {
        return withEachAccess(Stream.of(Arguments.of(false), Arguments.of(true)));
    }

    /**
     * Just before a missing directory is created, the observer moves the directory it is to be created in away and
     * puts a symbolic link to an owned outside directory in its place, which is empty or already holds a directory of
     * the missing directory's name. The directory-relative walk creates the missing directory relative to the
     * directory it holds open, never by that directory's path, so the missing directory lands in the moved directory
     * and the outside directory gains nothing: no directory, no file and no temporary file. The temporary file is
     * written in the created directory, named by the path the walk reached that directory by, and is gone afterwards.
     * Where the outside directory holds the name, the file lands in the moved directory too. Where it is empty, the
     * file-store query the write makes on that path, which now leads through the link to nothing, fails the write
     * once the temporary file holds the content, and the temporary file is removed: the streaming failure is caused
     * by a {@link NoSuchFileException} naming the path, and the moved directory holds the created directory alone.
     * Runs only where the platform offers the directory-relative walk; the path-name walk offers no such
     * protection.
     *
     * @param outsideHoldsTheName whether the outside directory already holds a directory of the missing name
     * @throws Exception when a file operation of the test fails or the lock map cannot be read
     */
    @ParameterizedTest(name = "outside directory holds the missing name: {0}")
    @ValueSource(booleans = {false, true})
    void directorySwappedForALinkJustBeforeAMissingDirectoryIsCreatedSendsNothingOutside(boolean outsideHoldsTheName)
            throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Assumptions.assumeTrue(offersDirectoryRelativeWalk(dir), "no SecureDirectoryStream on this platform");
        Path sub = Files.createDirectories(dir.resolve("sub"));
        Path moved = dir.resolve("moved");
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        if (outsideHoldsTheName) {
            Files.createDirectory(outside.resolve("daily"));
        }
        Map<String, String> outsideBefore = tree(outside);
        List<Path> parents = new ArrayList<>();
        List<Path> temporaryFiles = new ArrayList<>();
        FileOutboundWriter swapping = new FileOutboundWriter(FileOutboundWriter.DirectoryAccess.AUTOMATIC,
                temporaryFiles::add, parent -> {
                    parents.add(parent);
                    Files.move(sub, moved);
                    Files.createSymbolicLink(sub, outside);
                });
        Path walked = sub.resolve("daily");

        Map<String, String> movedAfter = new TreeMap<>(Map.of("", "directory", "daily", "directory"));
        if (outsideHoldsTheName) {
            Path written = swapping.write(dir, "sub/daily/report.csv", "new");

            assertThat(written).isEqualTo(walked.resolve("report.csv"));
            movedAfter.put(Path.of("daily", "report.csv").toString(), "file new");
        } else {
            assertThatThrownBy(() -> swapping.write(dir, "sub/daily/report.csv", "new"))
                    .isExactlyInstanceOf(IOException.class)
                    .hasMessage(STREAMING_FAILED)
                    .cause()
                    .isInstanceOfSatisfying(NoSuchFileException.class, failure ->
                            assertThat(failure.getFile()).isEqualTo(walked.toString()));
        }

        assertThat(tree(outside)).isEqualTo(outsideBefore);
        assertThat(tree(moved)).isEqualTo(movedAfter);
        assertThat(temporaryFiles).singleElement().satisfies(temp -> {
            assertThat(temp.getParent()).isEqualTo(walked);
            assertThat(temp.getFileName().toString()).matches(TEMPORARY_NAME);
        });
        assertThat(parents).containsExactly(sub);
        assertThat(Files.readSymbolicLink(sub)).isEqualTo(outside);
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * Listings of open file descriptors in which no entry may be used to create a missing directory relative to the
     * open directory: a missing listing, an empty one, one whose entries lead to another directory, to a regular
     * file and nowhere, and a listing that names the open directory while the directory has no file key to find it
     * by.
     *
     * @return description, whether the listing exists, whether it holds entries leading elsewhere, whether it names
     *         the open directory, whether the open directory's file key is known
     */
    static Stream<Arguments> listingsNamingNoOpenDirectory() {
        return Stream.of(
                Arguments.of("missing listing", false, false, false, true),
                Arguments.of("empty listing", true, false, false, true),
                Arguments.of("entries leading elsewhere", true, true, false, true),
                Arguments.of("no file key", true, true, true, false));
    }

    /**
     * Where no entry of the listing of open file descriptors names the open directory, the missing directory is not
     * created at all, and never by its path instead: the failure is a {@link FileSystemException} naming the directory
     * to create with the reason {@value #NOT_CREATED_RELATIVE}, the failure reading a missing listing as its cause.
     *
     * @param description   the case, for the test name
     * @param listingExists whether the listing exists
     * @param elsewhere     whether the listing holds entries leading to another directory, a file and nowhere
     * @param namingHeld    whether the listing holds an entry leading to the open directory
     * @param fileKeyKnown  whether the open directory's file key is passed
     * @throws IOException when a file operation of the test fails
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("listingsNamingNoOpenDirectory")
    void missingDirectoryIsNotCreatedWhenNoOpenDescriptorNamesItsDirectory(String description,
            boolean listingExists, boolean elsewhere, boolean namingHeld, boolean fileKeyKnown) throws IOException {
        Path held = Files.createDirectories(tempDir.resolve("out"));
        Path created = held.resolve("daily");
        Path descriptors = tempDir.resolve("fd");
        if (listingExists) {
            Files.createDirectory(descriptors);
        }
        if (elsewhere) {
            Files.createSymbolicLink(descriptors.resolve("3"), Files.createDirectory(tempDir.resolve("other")));
            Files.createSymbolicLink(descriptors.resolve("4"), Files.writeString(tempDir.resolve("file"), "x"));
            Files.createSymbolicLink(descriptors.resolve("5"), tempDir.resolve("closed"));
        }
        if (namingHeld) {
            Files.createSymbolicLink(descriptors.resolve("6"), held);
        }
        Object identity = fileKeyKnown ? Files.readAttributes(held, BasicFileAttributes.class).fileKey() : null;

        assertThatThrownBy(() -> FileOutboundWriter.descriptorOf(descriptors, identity, created))
                .isExactlyInstanceOf(FileSystemException.class)
                .isInstanceOfSatisfying(FileSystemException.class, refused -> {
                    assertThat(refused.getFile()).isEqualTo(created.toString());
                    assertThat(refused.getReason()).isEqualTo(NOT_CREATED_RELATIVE);
                    if (listingExists) {
                        assertThat(refused.getCause()).isNull();
                    } else {
                        assertThat(refused.getCause()).isInstanceOf(NoSuchFileException.class);
                    }
                });

        assertThat(created).doesNotExist();
    }

    /**
     * The directories given mode {@code 0300}, write and search without read, in each directory access: the target
     * directory, a directory below it that the name leads through, and that directory with a missing directory to
     * create inside it.
     *
     * @return access, description, the directory given the mode relative to the target directory, the name
     */
    static Stream<Arguments> directoriesWithoutReadPermission() {
        return withEachAccess(Stream.of(
                Arguments.of("target directory", "", "orders.json"),
                Arguments.of("directory on the way", "drop", "drop/orders.json"),
                Arguments.of("missing directory inside it", "drop", "drop/daily/orders.json")));
    }

    /**
     * A directory the process may write and search but not read, mode {@code 0300}, whether the target directory or
     * a directory below it that the name leads through, with or without a missing directory to create inside it.
     * The directory-relative walk must hold every directory open, which needs read permission, so
     * {@link Files#isReadable(Path)} on the directory decides its expectation: when the process may still read it, as
     * a superuser may, the file is written; otherwise the write fails, the streaming failure caused by an
     * {@link AccessDeniedException} naming that directory, and the tree is left exactly as it was. Where the
     * platform offers no directory-relative walk, only the target directory is opened, and the path-name walk needs
     * no read permission: there, and with {@link FileOutboundWriter.DirectoryAccess#PATH_NAMES}, a directory below
     * the target directory is written into. The directory keeps its mode, and a written tree holds the file and the
     * directories leading to it, nothing else.
     *
     * @param access      how the writer reaches the directories
     * @param description what the case covers
     * @param searchOnly  the directory given mode {@code 0300}, relative to the target directory
     * @param name        the file name passed to the writer
     * @throws Exception when a file operation of the test fails or the lock map cannot be read
     */
    @ParameterizedTest(name = "{1} ({0})")
    @MethodSource("directoriesWithoutReadPermission")
    void directoryWithoutReadPermissionIsWrittenIntoOnlyWhereItNeedNotBeHeldOpen(
            FileOutboundWriter.DirectoryAccess access, String description, String searchOnly, String name)
            throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Path unreadable = Files.createDirectories(dir.resolve(searchOnly));
        boolean opened = access == FileOutboundWriter.DirectoryAccess.AUTOMATIC
                && (unreadable.equals(dir) || offersDirectoryRelativeWalk(dir));
        Map<String, String> before = tree(dir);
        int unreadableMode = unixMode(unreadable);
        Files.setAttribute(unreadable, "unix:mode", 0300);
        boolean refused = opened && !Files.isReadable(unreadable);
        try {
            if (refused) {
                assertThatThrownBy(() -> new FileOutboundWriter(access).write(dir, name, "{}"))
                        .isExactlyInstanceOf(IOException.class)
                        .hasMessage(STREAMING_FAILED)
                        .cause()
                        .isInstanceOfSatisfying(AccessDeniedException.class, failure ->
                                assertThat(failure.getFile()).isEqualTo(unreadable.toString()));
            } else {
                Path written = new FileOutboundWriter(access).write(dir, name, "{}");

                assertThat(written).isEqualTo(dir.resolve(name));
                assertThat(Files.readString(written)).isEqualTo("{}");
            }
            assertThat(unixMode(unreadable)).isEqualTo(0300);
        } finally {
            Files.setAttribute(unreadable, "unix:mode", unreadableMode);
        }
        Map<String, String> expected = new TreeMap<>(before);
        if (!refused) {
            expected.put(name, "file {}");
            for (Path parent = Path.of(name).getParent(); parent != null; parent = parent.getParent()) {
                expected.put(parent.toString(), "directory");
            }
        }
        assertThat(tree(dir)).isEqualTo(expected);
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * Two links naming each other fail as the operating system's lookup would, the streaming failure caused by the
     * lookup's {@link FileSystemException}, and nothing is created.
     *
     * @param access how the writer reaches the directories
     * @throws IOException when a file operation of the test fails
     */
    @ParameterizedTest
    @EnumSource(FileOutboundWriter.DirectoryAccess.class)
    void writeThroughSymbolicLinkLoopFails(FileOutboundWriter.DirectoryAccess access) throws IOException {
        FileOutboundWriter writer = new FileOutboundWriter(access);
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Files.createSymbolicLink(dir.resolve("a"), Path.of("b"));
        Files.createSymbolicLink(dir.resolve("b"), Path.of("a"));

        assertThatThrownBy(() -> writer.write(dir, "a", "new"))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage(STREAMING_FAILED)
                .cause()
                .isInstanceOfSatisfying(FileSystemException.class, failure -> {
                    assertThat(failure.getFile()).isEqualTo(dir.resolve("a").toString());
                    assertThat(failure.getReason()).isEqualTo("Too many levels of symbolic links");
                });
        assertThat(names(dir)).containsExactly("a", "b");
    }

    /**
     * A chain of forty links, the most a Linux path lookup follows, is followed to the file it ends at.
     *
     * @param access how the writer reaches the directories
     * @throws IOException when a file operation of the test fails
     */
    @ParameterizedTest
    @EnumSource(FileOutboundWriter.DirectoryAccess.class)
    void chainOfFortySymbolicLinksIsFollowed(FileOutboundWriter.DirectoryAccess access) throws IOException {
        FileOutboundWriter writer = new FileOutboundWriter(access);
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        String name = linkChain(dir, 40, "linked.json");

        writer.write(dir, name, "new");

        assertThat(Files.readString(dir.resolve("linked.json"))).isEqualTo("new");
    }

    /**
     * A chain of forty-one links, one more than a Linux path lookup follows, fails, the streaming failure caused by
     * the lookup's {@link FileSystemException}, and creates nothing.
     *
     * @param access how the writer reaches the directories
     * @throws IOException when a file operation of the test fails
     */
    @ParameterizedTest
    @EnumSource(FileOutboundWriter.DirectoryAccess.class)
    void chainOfFortyOneSymbolicLinksFails(FileOutboundWriter.DirectoryAccess access) throws IOException {
        FileOutboundWriter writer = new FileOutboundWriter(access);
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        String name = linkChain(dir, 41, "linked.json");

        assertThatThrownBy(() -> writer.write(dir, name, "new"))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage(STREAMING_FAILED)
                .cause()
                .isInstanceOfSatisfying(FileSystemException.class, failure ->
                        assertThat(failure.getReason()).isEqualTo("Too many levels of symbolic links"));
        assertThat(dir.resolve("linked.json")).doesNotExist();
    }

    /**
     * Replacing a file keeps its POSIX permissions, as an in-place overwrite did.
     *
     * @param permissions the mode given to the file before it is overwritten, one its owner may write
     * @throws IOException when a file operation of the test fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"rw-------", "rw-r-----", "rw-rw-r--", "rwxr-x--x"})
    void overwriteKeepsTheExistingFilesPosixPermissions(String permissions) throws IOException {
        Path dir = tempDir.resolve("out");
        Path target = writer.write(dir, "report.csv", "first");
        Set<PosixFilePermission> mode = PosixFilePermissions.fromString(permissions);
        Files.setPosixFilePermissions(target, mode);

        writer.write(dir, "report.csv", "second");

        assertThat(Files.getPosixFilePermissions(target)).isEqualTo(mode);
        assertThat(Files.readString(target)).isEqualTo("second");
        assertThat(names(dir)).containsExactly("report.csv");
    }

    /**
     * A new file gets the permissions an ordinary create gives a file, not a temporary file's owner-only mode.
     *
     * @throws IOException when a file operation of the test fails
     */
    @Test
    void newFileGetsThePermissionsOfAnOrdinaryCreate() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Path reference = Files.write(dir.resolve("reference.dat"), new byte[] {1});

        Path written = writer.write(dir, "orders.json", "{}");

        assertThat(Files.getPosixFilePermissions(written)).isEqualTo(Files.getPosixFilePermissions(reference));
    }

    /**
     * The modes of a file being replaced, in each directory access.
     *
     * @return access, the mode
     */
    static Stream<Arguments> replacedFileModes() {
        return withEachAccess(Stream.of(Arguments.of("rw-------"), Arguments.of("rw-r-----"),
                Arguments.of("rw-r--r--")));
    }

    /**
     * The temporary file that replaces an existing file is created empty and readable and writable by its owner
     * only, whatever the replaced file's mode, so no other user can read the new content while it is written.
     *
     * @param access      how the directory is reached
     * @param permissions the mode of the file being replaced
     * @throws IOException when a file operation of the test fails
     */
    @ParameterizedTest(name = "{1} ({0})")
    @MethodSource("replacedFileModes")
    void temporaryFileReplacingAFileIsCreatedEmptyAndOwnerOnly(FileOutboundWriter.DirectoryAccess access,
            String permissions) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Path destination = Files.writeString(dir.resolve("private.dat"), "old");
        Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString(permissions));
        Path temp = dir.resolve(".replacement.tmp");

        try (FileOutboundWriter.DirectoryHandle directory = FileOutboundWriter.openDirectory(dir, access);
                SeekableByteChannel channel = FileOutboundWriter.createTemporaryFile(directory,
                        temp.getFileName(), destination.getFileName())) {
            assertThat(channel.size()).isZero();
            assertThat(Files.size(temp)).isZero();
            assertThat(OWNER_ONLY).containsAll(Files.getPosixFilePermissions(temp));
        }
        assertThat(Files.readString(destination)).isEqualTo("old");
    }

    /**
     * The temporary file of a new file is created empty with the permissions an ordinary create gives a file.
     *
     * @param access how the directory is reached
     * @throws IOException when a file operation of the test fails
     */
    @ParameterizedTest
    @EnumSource(FileOutboundWriter.DirectoryAccess.class)
    void temporaryFileOfANewFileGetsThePermissionsOfAnOrdinaryCreate(FileOutboundWriter.DirectoryAccess access)
            throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Path reference = Files.write(dir.resolve("reference.dat"), new byte[0]);
        Path destination = dir.resolve("orders.json");
        Path temp = dir.resolve(".new.tmp");

        try (FileOutboundWriter.DirectoryHandle directory = FileOutboundWriter.openDirectory(dir, access);
                SeekableByteChannel channel = FileOutboundWriter.createTemporaryFile(directory,
                        temp.getFileName(), destination.getFileName())) {
            assertThat(channel.size()).isZero();
            assertThat(Files.getPosixFilePermissions(temp)).isEqualTo(Files.getPosixFilePermissions(reference));
        }
        assertThat(destination).doesNotExist();
    }

    /**
     * The temporary file is never created through a symbolic link already at its name, here a dangling link into an
     * outside directory: the create fails with a {@link FileAlreadyExistsException} naming the temporary file by
     * its full path, and nothing is created where the link leads.
     *
     * @param access how the directory is reached
     * @throws IOException when a file operation of the test fails
     */
    @ParameterizedTest
    @EnumSource(FileOutboundWriter.DirectoryAccess.class)
    void temporaryFileIsNotCreatedThroughALinkAtItsName(FileOutboundWriter.DirectoryAccess access)
            throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path temp = Files.createSymbolicLink(dir.resolve(".planted.tmp"), outside.resolve("new.txt"));

        try (FileOutboundWriter.DirectoryHandle directory = FileOutboundWriter.openDirectory(dir, access)) {
            assertThatThrownBy(() -> FileOutboundWriter.createTemporaryFile(directory, temp.getFileName(),
                    Path.of("orders.json")))
                    .isInstanceOfSatisfying(FileAlreadyExistsException.class, failure ->
                            assertThat(failure.getFile()).isEqualTo(temp.toString()));
        }
        assertThat(outside).isEmptyDirectory();
        assertThat(Files.readSymbolicLink(temp)).isEqualTo(outside.resolve("new.txt"));
    }

    /**
     * A file of an owner-only or a wider mode is replaced several times, each time with new content far larger than
     * one write buffer, by a writer whose temporary-file observer runs in the writing thread once the temporary file
     * holds the whole content, before it takes the replaced file's mode and is renamed into place. Each write must
     * show the observer exactly one temporary file, and at that point the file is a hidden {@code .<uuid>.tmp} beside
     * the target, readable and writable by its owner only, holding the whole new content, while the target still
     * holds the previous content. After each write that temporary file is gone, and the target, alone in its
     * directory, holds the new content with its own mode, which for the wider modes the temporary file did not have.
     *
     * @param access      how the writer reaches the directories
     * @param permissions the mode of the file being replaced
     * @throws IOException when a file operation of the test fails
     */
    @ParameterizedTest(name = "{1} ({0})")
    @MethodSource("replacedFileModes")
    void temporaryFileIsOwnerOnlyOnceItHoldsTheContentOfEachReplacement(FileOutboundWriter.DirectoryAccess access,
            String permissions) throws IOException {
        Path dir = tempDir.resolve("out");
        Path target = writer.write(dir, "private.dat", "old");
        Set<PosixFilePermission> mode = PosixFilePermissions.fromString(permissions);
        Files.setPosixFilePermissions(target, mode);
        byte[] previous = Files.readAllBytes(target);
        for (int round = 1; round <= ROUNDS; round++) {
            byte[] content = filled((byte) round);
            byte[] replaced = previous;
            List<Path> observed = new ArrayList<>();
            FileOutboundWriter observing = new FileOutboundWriter(access, temp -> {
                assertThat(temp.getParent()).isEqualTo(dir);
                assertThat(temp.getFileName().toString()).matches(TEMPORARY_NAME);
                assertThat(OWNER_ONLY).containsAll(Files.getPosixFilePermissions(temp, LinkOption.NOFOLLOW_LINKS));
                assertThat(Arrays.mismatch(Files.readAllBytes(temp), content)).isEqualTo(-1);
                assertThat(Arrays.mismatch(Files.readAllBytes(target), replaced)).isEqualTo(-1);
                observed.add(temp);
            });

            observing.write(dir, "private.dat", content);

            assertThat(observed).hasSize(1);
            assertThat(observed.get(0)).doesNotExist();
            assertThat(Files.getPosixFilePermissions(target)).isEqualTo(mode);
            assertThat(Arrays.mismatch(Files.readAllBytes(target), content)).isEqualTo(-1);
            assertThat(names(dir)).containsExactly("private.dat");
            previous = content;
        }
    }

    /**
     * A read-only file is refused as an in-place write refused it, although a rename needs write permission on
     * the directory only. {@link Files#isWritable(Path)} decides the expectation: when the process may not write
     * the file, the write fails, the streaming failure caused by an {@link AccessDeniedException} naming it, and the
     * directory is left exactly as it was; when it may, as a superuser may, the content is replaced. The file stays
     * read-only either way.
     *
     * @param access how the writer reaches the directories
     * @throws Exception when a file operation fails or the lock map cannot be read
     */
    @ParameterizedTest
    @EnumSource(FileOutboundWriter.DirectoryAccess.class)
    void readOnlyFileIsRefusedUnlessTheProcessMayWriteIt(FileOutboundWriter.DirectoryAccess access)
            throws Exception {
        FileOutboundWriter writer = new FileOutboundWriter(access);
        Path dir = tempDir.resolve("out");
        Path target = writer.write(dir, "report.csv", "first");
        Set<PosixFilePermission> readOnly = PosixFilePermissions.fromString("r--r--r--");
        Files.setPosixFilePermissions(target, readOnly);
        Map<String, String> before = tree(dir);

        if (Files.isWritable(target)) {
            writer.write(dir, "report.csv", "second");

            assertThat(Files.readString(target)).isEqualTo("second");
            assertThat(names(dir)).containsExactly("report.csv");
        } else {
            assertThatThrownBy(() -> writer.write(dir, "report.csv", "second"))
                    .isExactlyInstanceOf(IOException.class)
                    .hasMessage(STREAMING_FAILED)
                    .cause()
                    .isInstanceOfSatisfying(AccessDeniedException.class, failure ->
                            assertThat(failure.getFile()).isEqualTo(target.toString()));

            assertThat(tree(dir)).isEqualTo(before);
        }
        assertThat(Files.getPosixFilePermissions(target)).isEqualTo(readOnly);
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * An existing file the process may write is overwritten as Mule's in-place write overwrote it, in a directory
     * whose mode can refuse a replacement: read-and-search only ({@code 0555}), which refuses an unprivileged
     * process the temporary file, and world-writable with the sticky bit ({@code 1777}, the mode of {@code /tmp}),
     * which refuses the rename over a file another user owns. The mode is set and read back as {@code unix:mode},
     * since {@link PosixFilePermission} has no sticky bit.
     * {@link Files#isWritable(Path)} on the directory tells which path ran: when the process may add to it, as a
     * superuser may and as the owner of the file in the sticky directory may, the file is replaced as a whole by a
     * new inode; otherwise it is written in place and keeps its inode. Either way it holds the new content and keeps
     * its mode, no temporary file is left behind and the lock entry is released.
     *
     * @param access        how the writer reaches the directories
     * @param directoryMode the octal mode the directory has during the write
     * @throws Exception when a file operation fails or the lock map cannot be read
     */
    @ParameterizedTest(name = "directory mode {1} ({0})")
    @MethodSource("restrictiveDirectoryModes")
    void existingFileInARestrictiveDirectoryIsOverwritten(FileOutboundWriter.DirectoryAccess access,
            String directoryMode) throws Exception {
        FileOutboundWriter writer = new FileOutboundWriter(access);
        Path dir = tempDir.resolve("out");
        Path target = writer.write(dir, "report.csv", "first");
        Set<PosixFilePermission> mode = PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(target, mode);
        Object inode = Files.readAttributes(target, BasicFileAttributes.class).fileKey();
        int restrictive = Integer.parseInt(directoryMode, 8);
        int dirMode = unixMode(dir);
        Files.setAttribute(dir, "unix:mode", restrictive);
        try {
            assertThat(unixMode(dir)).isEqualTo(restrictive);
            boolean replaced = Files.isWritable(dir);

            Path written = writer.write(dir, "report.csv", "second");

            assertThat(written).isEqualTo(target);
            assertThat(Files.readString(target)).isEqualTo("second");
            assertThat(Files.getPosixFilePermissions(target)).isEqualTo(mode);
            assertThat(names(dir)).containsExactly("report.csv");
            Object inodeAfter = Files.readAttributes(target, BasicFileAttributes.class).fileKey();
            if (replaced) {
                assertThat(inodeAfter).isNotEqualTo(inode);
            } else {
                assertThat(inodeAfter).isEqualTo(inode);
            }
        } finally {
            Files.setAttribute(dir, "unix:mode", dirMode);
        }
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * The directory modes that can refuse a replacement, in each directory access.
     *
     * @return access, the octal mode
     */
    static Stream<Arguments> restrictiveDirectoryModes() {
        return withEachAccess(Stream.of(Arguments.of("0555"), Arguments.of("1777")));
    }

    /**
     * A new file in a directory made read-and-search only: {@link Files#isWritable(Path)} on the directory decides
     * the expectation. When the process may still add to it, as a superuser may, the file is created with the
     * permissions an ordinary create gives it; otherwise the write fails, the streaming failure caused by an
     * {@link AccessDeniedException} naming the file, as its in-place create failed, and the directory is left
     * exactly as it was. The lock entry is released either way.
     *
     * @param access how the writer reaches the directories
     * @throws Exception when a file operation fails or the lock map cannot be read
     */
    @ParameterizedTest
    @EnumSource(FileOutboundWriter.DirectoryAccess.class)
    void newFileInAReadOnlyDirectoryIsRefusedUnlessTheProcessMayCreateIt(FileOutboundWriter.DirectoryAccess access)
            throws Exception {
        FileOutboundWriter writer = new FileOutboundWriter(access);
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Path reference = Files.write(dir.resolve("reference.dat"), new byte[] {1});
        Path target = dir.resolve("orders.json");
        Map<String, String> before = tree(dir);
        Set<PosixFilePermission> dirMode = Files.getPosixFilePermissions(dir);
        Files.setPosixFilePermissions(dir, READ_AND_SEARCH_ONLY);
        try {
            if (Files.isWritable(dir)) {
                writer.write(dir, "orders.json", "{}");

                assertThat(Files.readString(target)).isEqualTo("{}");
                assertThat(Files.getPosixFilePermissions(target))
                        .isEqualTo(Files.getPosixFilePermissions(reference));
                assertThat(names(dir)).containsExactly("orders.json", "reference.dat");
            } else {
                assertThatThrownBy(() -> writer.write(dir, "orders.json", "{}"))
                        .isExactlyInstanceOf(IOException.class)
                        .hasMessage(STREAMING_FAILED)
                        .cause()
                        .isInstanceOfSatisfying(AccessDeniedException.class, failure ->
                                assertThat(failure.getFile()).isEqualTo(target.toString()));

                assertThat(tree(dir)).isEqualTo(before);
            }
        } finally {
            Files.setPosixFilePermissions(dir, dirMode);
        }
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * A named pipe at the target is written in place, as Mule's in-place write did, not replaced by a regular
     * file: its reader receives every byte of content far larger than the pipe's buffer, the pipe stays a pipe and
     * no temporary file appears. Writer and reader run on daemon threads waited on boundedly, because opening a
     * pipe blocks until its other end is opened.
     *
     * @param access how the writer reaches the directories
     * @throws Exception when a file operation, the {@code mkfifo} command, a wait on another thread fails, or the
     *                   lock map cannot be read
     */
    @ParameterizedTest
    @EnumSource(FileOutboundWriter.DirectoryAccess.class)
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void namedPipeIsWrittenInPlace(FileOutboundWriter.DirectoryAccess access) throws Exception {
        FileOutboundWriter writer = new FileOutboundWriter(access);
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Path pipe = dir.resolve("orders.pipe");
        Process mkfifo = new ProcessBuilder("mkfifo", pipe.toString()).redirectErrorStream(true).start();
        assertThat(mkfifo.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(mkfifo.exitValue()).isZero();
        byte[] content = filled((byte) 1);
        ExecutorService pool = Executors.newFixedThreadPool(2, FileOutboundWriterTest::daemon);
        try {
            Future<byte[]> reader = pool.submit(() -> Files.readAllBytes(pipe));
            Future<Path> written = pool.submit(() -> writer.write(dir, "orders.pipe", content));

            assertThat(await(written)).isEqualTo(pipe);
            assertThat(Arrays.mismatch(await(reader), content)).isEqualTo(-1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(Files.exists(pipe, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.isRegularFile(pipe, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.isDirectory(pipe, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.readAttributes(pipe, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther())
                .isTrue();
        assertThat(names(dir)).containsExactly("orders.pipe");
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * Names that resolve to a directory, in each directory access: the empty name, a directory, a link to it, the
     * same link written with a trailing separator, and a nested name ending in {@code ..}.
     *
     * @return access, the name
     */
    static Stream<Arguments> namesOfDirectories() {
        return withEachAccess(Stream.of(Arguments.of(""), Arguments.of("sub"), Arguments.of("sub-link"),
                Arguments.of("sub-link/"), Arguments.of("sub/..")));
    }

    /**
     * A name that resolves to a directory, directly, as the empty name or through a link, fails, the streaming
     * failure caused by a {@link FileSystemException} with the reason {@code Is a directory}; every file and
     * directory is left exactly as it was and no temporary file appears.
     *
     * @param access how the writer reaches the directories
     * @param name   the file name passed to the writer
     * @throws Exception when a file operation fails or the lock map cannot be read
     */
    @ParameterizedTest(name = "name \"{1}\" ({0})")
    @MethodSource("namesOfDirectories")
    void writeToADirectoryFailsAndLeavesEverythingAsItWas(FileOutboundWriter.DirectoryAccess access, String name)
            throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("out"));
        Path sub = Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("kept.txt"), "kept");
        Files.writeString(sub.resolve("inner.txt"), "inner");
        Files.createSymbolicLink(dir.resolve("sub-link"), Path.of("sub"));
        Map<String, String> before = tree(dir);

        assertThatThrownBy(() -> new FileOutboundWriter(access).write(dir, name, "new"))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage(STREAMING_FAILED)
                .cause()
                .isInstanceOfSatisfying(FileSystemException.class, failure ->
                        assertThat(failure.getReason()).isEqualTo("Is a directory"));

        assertThat(tree(dir)).isEqualTo(before);
        assertThat(lockedDestinations()).isEmpty();
    }

    /**
     * A write whose rename is refused after its temporary file was written, here because the observer puts a
     * non-empty directory where the new file was to go, removes the temporary file and leaves every other entry as
     * it was; with no regular file left to overwrite in place, the refused rename is the failure reported.
     *
     * @param access how the writer reaches the directories
     * @throws Exception when a file operation fails or the lock map cannot be read
     */
    @ParameterizedTest
    @EnumSource(FileOutboundWriter.DirectoryAccess.class)
    void failedRenameLeavesTheExistingContentAndNoTemporaryFile(FileOutboundWriter.DirectoryAccess access)
            throws Exception {
        Path dir = tempDir.resolve("out");
        Path existing = writer.write(dir, "orders.json", "first");
        Path blocker = dir.resolve("report.csv");
        FileOutboundWriter blocked = new FileOutboundWriter(access, temp -> {
            Files.createDirectory(blocker);
            Files.writeString(blocker.resolve("inner.txt"), "inner");
        });

        assertThatThrownBy(() -> blocked.write(dir, "report.csv", "second"))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage(STREAMING_FAILED)
                .hasCauseInstanceOf(FileSystemException.class);

        assertThat(names(dir)).containsExactly("orders.json", "report.csv");
        assertThat(Files.readString(existing)).isEqualTo("first");
        assertThat(names(blocker)).containsExactly("inner.txt");
        assertThat(lockedDestinations()).isEmpty();
    }

    private static String cp(int codePoint) {
        return Character.toString(codePoint);
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    /**
     * Returns content of {@link #LARGE_SIZE} bytes, every one {@code fill}.
     *
     * @param fill the byte value
     * @return the content
     */
    private static byte[] filled(byte fill) {
        byte[] content = new byte[LARGE_SIZE];
        Arrays.fill(content, fill);
        return content;
    }

    /**
     * Creates a chain of relative symbolic links {@code link1} to {@code link<links>} in {@code dir}, where
     * {@code link1} names {@code linked} and each further link names the one before it.
     *
     * @param dir    the directory holding the chain
     * @param links  the number of links
     * @param linked the name the chain ends at, which is not created
     * @return the name at the head of the chain, {@code link<links>}, to write through
     * @throws IOException when a link cannot be created
     */
    private static String linkChain(Path dir, int links, String linked) throws IOException {
        String next = linked;
        for (int i = 1; i <= links; i++) {
            Files.createSymbolicLink(dir.resolve("link" + i), Path.of(next));
            next = "link" + i;
        }
        return next;
    }

    /**
     * Creates symbolic links under {@code dir}, each written {@code name -> text}, the name relative to {@code dir};
     * in the text, <code>{outside}</code> stands for the absolute path of {@code outside}, <code>{out}</code> for the
     * absolute path of {@code dir} and <code>{real}</code> for its real path.
     *
     * @param dir     the target directory
     * @param outside the directory beside it
     * @param links   the links
     * @throws IOException when a link cannot be created or the real path cannot be read
     */
    private static void createLinks(Path dir, Path outside, String... links) throws IOException {
        String real = dir.toRealPath().toString();
        for (String link : links) {
            String[] nameAndText = link.split(" -> ", 2);
            String text = nameAndText[1]
                    .replace("{outside}", outside.toAbsolutePath().toString())
                    .replace("{out}", dir.toAbsolutePath().toString())
                    .replace("{real}", real);
            Files.createSymbolicLink(dir.resolve(nameAndText[0]), Path.of(text));
        }
    }

    /**
     * Returns every case once for each {@link FileOutboundWriter.DirectoryAccess}, the access as its first argument.
     *
     * @param cases the cases
     * @return the cases for the first access, then for the next
     */
    private static Stream<Arguments> withEachAccess(Stream<Arguments> cases) {
        List<Arguments> each = cases.toList();
        return Stream.of(FileOutboundWriter.DirectoryAccess.values()).flatMap(access -> each.stream().map(one -> {
            Object[] values = one.get();
            Object[] withAccess = new Object[values.length + 1];
            withAccess[0] = access;
            System.arraycopy(values, 0, withAccess, 1, values.length);
            return Arguments.of(withAccess);
        }));
    }

    /**
     * Tells whether the platform offers the directory-relative walk for {@code dir}, as
     * {@link FileOutboundWriter.DirectoryAccess#AUTOMATIC} uses it.
     *
     * @param dir an existing directory
     * @return {@code true} when {@link Files#newDirectoryStream(Path)} returns a {@link SecureDirectoryStream}
     * @throws IOException when the directory cannot be opened
     */
    private static boolean offersDirectoryRelativeWalk(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return stream instanceof SecureDirectoryStream;
        }
    }

    /**
     * Fails unless {@code snapshot} is one whole content of the simultaneous-write test: {@link #LARGE_SIZE}
     * bytes of the initial fill or of one writer's fill, and nothing else.
     *
     * @param snapshot the bytes read from the file
     */
    private static void assertWhole(byte[] snapshot) {
        if (snapshot.length != LARGE_SIZE) {
            throw new AssertionError("read " + snapshot.length + " bytes, expected " + LARGE_SIZE);
        }
        byte fill = snapshot[0];
        if (fill != INITIAL_FILL && (fill < 1 || fill > WRITERS)) {
            throw new AssertionError("read fill byte " + fill + ", which no writer wrote");
        }
        for (int offset = 1; offset < snapshot.length; offset++) {
            if (snapshot[offset] != fill) {
                throw new AssertionError("read byte " + snapshot[offset] + " at offset " + offset
                        + " in content of fill byte " + fill);
            }
        }
    }

    /**
     * Signals that the calling thread is ready, then waits, boundedly, for the release of every simultaneous thread.
     *
     * @param ready counted down once by each thread
     * @param start opened once every thread is ready
     * @throws InterruptedException when the wait is interrupted
     */
    private static void awaitRelease(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("threads were not released within " + WAIT_SECONDS + " s");
        }
    }

    /**
     * Waits, boundedly, for a task and returns its result, rethrowing the task's own failure.
     *
     * @param future the task
     * @param <T>    the result type
     * @return the task's result
     * @throws Exception the task's failure, or a timeout or interruption of the wait
     */
    private static <T> T await(Future<T> future) throws Exception {
        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            if (failed.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw failed;
        }
    }

    /**
     * Creates a daemon thread for {@code task}, so a thread left blocked opening a named pipe by a failed test
     * never keeps the JVM alive.
     *
     * @param task the work of the thread
     * @return the unstarted daemon thread
     */
    private static Thread daemon(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Reads the permission bits of {@code file}, including the set-user-ID, set-group-ID and sticky bits that
     * {@link PosixFilePermission} cannot express.
     *
     * @param file the file
     * @return its {@code unix:mode} without the file-type bits
     * @throws IOException when the mode cannot be read
     */
    private static int unixMode(Path file) throws IOException {
        return (Integer) Files.getAttribute(file, "unix:mode") & 07777;
    }

    /**
     * Returns the names of the entries directly inside {@code dir}, sorted.
     *
     * @param dir the directory
     * @return the entry names, including hidden ones
     * @throws IOException when the directory cannot be listed
     */
    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * Describes every entry under {@code dir}, without following links: each directory, each link with its text,
     * and each regular file with its content.
     *
     * @param dir the root of the tree
     * @return the description of each entry, by path relative to {@code dir}
     * @throws IOException when the tree cannot be read
     */
    private static Map<String, String> tree(Path dir) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path entry : walk.toList()) {
                String description;
                if (Files.isSymbolicLink(entry)) {
                    description = "link " + Files.readSymbolicLink(entry);
                } else if (Files.isDirectory(entry)) {
                    description = "directory";
                } else {
                    description = "file " + Files.readString(entry);
                }
                entries.put(dir.relativize(entry).toString(), description);
            }
        }
        return entries;
    }

    /**
     * Returns the keys of the destinations whose lock entry is currently held or awaited, read from the writer's
     * private lock map: the map must lose every entry once its last writer finishes, so it never grows with each new
     * name. The tests of this class run one at a time, so the map is empty whenever none of them is writing.
     *
     * @return a copy of the lock map's keys
     * @throws ReflectiveOperationException when the lock map cannot be read
     */
    private static Set<Object> lockedDestinations() throws ReflectiveOperationException {
        Field locks = FileOutboundWriter.class.getDeclaredField("LOCKS");
        locks.setAccessible(true);
        return Set.copyOf(((Map<?, ?>) locks.get(null)).keySet());
    }
}
