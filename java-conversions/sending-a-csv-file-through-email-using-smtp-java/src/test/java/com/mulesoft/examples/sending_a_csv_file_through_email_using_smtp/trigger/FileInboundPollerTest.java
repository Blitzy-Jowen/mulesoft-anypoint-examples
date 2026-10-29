package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.trigger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * Tests shared component SC-5, {@link FileInboundPoller}: Mule 3.8 {@code file:inbound-endpoint} semantics on the
 * default file connector. Each poll hands every regular file of the input directory to the handler once, and a
 * handed file is consumed whether the handler returned or threw: moved to the move directory under its own name,
 * or deleted without one. A file that cannot be read stays in place, unmarked, for the next poll.
 *
 * <p>This is the SC-5 reference test of the conversion plan (AAP 0.4.4). Its first consumer is
 * {@code processing-orders-with-dataweave-and-APIkit}; {@code import-contacts-into-salesforce},
 * {@code import-leads-into-salesforce}, {@code dataweave-with-flowreflookup}, {@code import-contacts-into-ms-dynamics},
 * {@code importing-a-CSV-file-into-Mongo-DB}, {@code importing-a-csv-file-into-ms-sharepoint} and
 * {@code sending-a-csv-file-through-email-using-smtp} copy it verbatim with only the {@code package} line changed.
 * It therefore depends on nothing but the JDK, JUnit 5, AssertJ, Mockito and {@link FileInboundPoller}: every file
 * content is inline, every directory and every file outside the input and move directories lives under
 * {@link TempDir}, no order across several files is asserted other than the platform's own directory-listing order,
 * and an unreadable file is simulated by overriding {@link FileInboundPoller#readContent(Path, SeekableByteChannel)},
 * never through file permissions, which a root build ignores; only a file too large to read is real, a sparse file
 * that takes no disk space.
 *
 * <p>Links and substitutions are real and point at owned files outside the input directory: a symbolic link to a
 * file or a directory outside it, a dangling link and a link to a file beside it; an entry swapped for a link or
 * another file after the listing, while the file is read and after the read; a link standing in the move directory;
 * and an input or move directory redirected through a link after the first poll. Each such case runs in both of the
 * poller's modes, selected through {@link FileInboundPoller#openDirectory(Path)}: relative to the open directories
 * through the platform's {@link SecureDirectoryStream}, skipped by assumption where the platform provides none, and
 * by path from the pinned locations. Three cases run in the secure mode alone: the copy-and-delete fallback, reached
 * by refusing the rename in a decorated secure stream, a directory swapped for a link while it is opened, which
 * only an open directory's identity reveals, and a move directory nested in the input directory swapped for a link
 * once both are verified, which the open directory keeps confined. The path-based mode instead refuses input and
 * move directories nested one in the other before that swap could be reached, which a case of its own asserts.
 */
class FileInboundPollerTest {

    private static final long LATCH_TIMEOUT_SECONDS = 10;

    /** Content of the file outside the input and move directories that links point at. */
    private static final String SECRET = "Name,Email\nOutside,outside@example.com\n";

    private static final String OTHER_CONTENT = "id\nother\n";

    @TempDir
    Path tempDir;

    /** Numbers the entries staged for a swap, so each swap renames a fresh one into place. */
    private final AtomicInteger staged = new AtomicInteger();

    @Test
    void movesFileToMoveDirectoryUnderItsOwnNameAfterSuccess() throws IOException {
        Path in = tempDir.resolve("in");
        Path out = tempDir.resolve("out");
        Path file = write(in, "contacts.csv", "name,surname\nJohn,Doe\n");
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        FileInboundPoller poller = new FileInboundPoller(in, out, recordingInto(received));

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(received).hasSize(1);
        FileInboundPoller.InboundFile inbound = received.get(0);
        assertThat(inbound.name()).isEqualTo("contacts.csv");
        assertThat(inbound.content()).isEqualTo(bytes("name,surname\nJohn,Doe\n"));
        // The documented path is the configured input directory resolved with the file name.
        assertThat(inbound.path()).isEqualTo(file);
        assertThat(inbound.path().getParent()).isEqualTo(in);
        assertThat(file).doesNotExist();
        assertThat(out.resolve("contacts.csv")).hasBinaryContent(bytes("name,surname\nJohn,Doe\n"));
        assertThat(poller.moveToDirectory()).isEqualTo(Optional.of(out));
        assertThat(poller.inputDirectory()).isEqualTo(in);
    }

    @Test
    void deletesFileWhenNoMoveDirectoryAfterSuccess() throws IOException {
        Path in = tempDir.resolve("in");
        Path file = write(in, "orders.xml", "<orders/>");
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        FileInboundPoller poller = new FileInboundPoller(in, null, recordingInto(received));

        assertThat(poller.moveToDirectory()).isEmpty();
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(received).extracting(FileInboundPoller.InboundFile::name).containsExactly("orders.xml");
        assertThat(file).doesNotExist();
        assertThat(tree(tempDir)).containsExactlyInAnyOrder(tempDir, in);
    }

    @Test
    void consumesFileAfterHandlerFailureWithMoveDirectory() throws Exception {
        Path in = tempDir.resolve("in");
        Path out = tempDir.resolve("out");
        Path file = write(in, "leads.csv", "Email\nlead@example.com\n");
        FileInboundPoller.FileHandler handler = mock(FileInboundPoller.FileHandler.class);
        doThrow(new Exception("boom")).when(handler).handle(any());
        FileInboundPoller poller = new FileInboundPoller(in, out, handler);
        AtomicInteger handed = new AtomicInteger(-1);

        assertThatCode(() -> handed.set(poller.pollOnce())).doesNotThrowAnyException();

        // pollOnce() documents that a file whose handler threw is counted: it was handed to the handler.
        assertThat(handed).hasValue(1);
        verify(handler).handle(any());
        assertThat(file).doesNotExist();
        assertThat(out.resolve("leads.csv")).hasBinaryContent(bytes("Email\nlead@example.com\n"));
    }

    @Test
    void consumesFileAfterHandlerFailureWithoutMoveDirectory() throws IOException {
        Path in = tempDir.resolve("in");
        Path file = write(in, "companies.csv", "company_name,state\nAcme,GA\n");
        AtomicInteger calls = new AtomicInteger();
        FileInboundPoller poller = new FileInboundPoller(in, null, inbound -> {
            calls.incrementAndGet();
            throw new IllegalStateException("vendor rejected the record");
        });
        AtomicInteger handed = new AtomicInteger(-1);

        assertThatCode(() -> handed.set(poller.pollOnce())).doesNotThrowAnyException();

        assertThat(handed).hasValue(1);
        assertThat(calls).hasValue(1);
        assertThat(file).doesNotExist();
        // Nothing is retried: the next poll finds no file.
        assertThat(poller.pollOnce()).isZero();
        assertThat(calls).hasValue(1);
    }

    @Test
    void consumesFileAndKeepsInterruptStatusWhenHandlerIsInterrupted() throws IOException {
        Path in = tempDir.resolve("in");
        Path file = write(in, "input.csv", "orderId,units\n1,2\n");
        // Whichever file comes second is read on a thread whose interrupt status the first handler set.
        Path second = write(in, "second.csv", "orderId,units\n2,3\n");
        FileInboundPoller poller = new FileInboundPoller(in, null, inbound -> {
            throw new InterruptedException("stopped");
        });

        int handed;
        boolean interrupted;
        try {
            handed = poller.pollOnce();
        } finally {
            // Read and clear the flag, so no later test running on this thread starts interrupted.
            interrupted = Thread.interrupted();
        }

        assertThat(handed).isEqualTo(2);
        assertThat(interrupted).isTrue();
        assertThat(file).doesNotExist();
        assertThat(second).doesNotExist();
    }

    @Test
    void reentrantPollDoesNotRedeliverFileInProcess() throws IOException {
        Path in = tempDir.resolve("in");
        Path out = tempDir.resolve("out");
        Path file = write(in, "orders.xml", "<orders><order/></orders>");
        AtomicReference<FileInboundPoller> ref = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger innerHanded = new AtomicInteger(-1);
        ref.set(new FileInboundPoller(in, out, inbound -> {
            if (calls.incrementAndGet() == 1) {
                innerHanded.set(ref.get().pollOnce());
            }
        }));

        assertThat(ref.get().pollOnce()).isEqualTo(1);

        assertThat(calls).hasValue(1);
        assertThat(innerHanded).hasValue(0);
        assertThat(file).doesNotExist();
        assertThat(tree(out)).containsExactlyInAnyOrder(out, out.resolve("orders.xml"));
        assertThat(out.resolve("orders.xml")).hasBinaryContent(bytes("<orders><order/></orders>"));
    }

    @Test
    void concurrentPollSkipsFileHandledByAnotherThread() throws Exception {
        Path in = tempDir.resolve("in");
        Path file = write(in, "contacts.csv", "name\nJane\n");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        FileInboundPoller poller = new FileInboundPoller(in, null, inbound -> {
            calls.incrementAndGet();
            entered.countDown();
            if (!release.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("handler was not released");
            }
        });
        AtomicInteger firstHanded = new AtomicInteger(-1);
        Thread first = new Thread(() -> firstHanded.set(poller.pollOnce()), "file-inbound-poller-test-first-poll");

        first.start();
        try {
            assertThat(entered.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            // The first poll holds the file in its handler: a concurrent poll sees it but must not hand it over.
            assertThat(poller.pollOnce()).isZero();
            assertThat(file).exists();
        } finally {
            release.countDown();
            first.join(TimeUnit.SECONDS.toMillis(LATCH_TIMEOUT_SECONDS));
        }

        assertThat(first.isAlive()).isFalse();
        assertThat(firstHanded).hasValue(1);
        assertThat(calls).hasValue(1);
        assertThat(file).doesNotExist();
    }

    static Stream<Arguments> readFailures() {
        return Stream.of(
                Arguments.of("IOException", (Supplier<Throwable>) () -> new IOException("locked")),
                Arguments.of("SecurityException", (Supplier<Throwable>) () -> new SecurityException("locked")),
                Arguments.of("OutOfMemoryError", (Supplier<Throwable>) () -> new OutOfMemoryError("Java heap space")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("readFailures")
    void unopenableFileIsLeftInPlaceUnmarkedAndRetriedLater(String label, Supplier<Throwable> failure)
            throws IOException {
        Path in = tempDir.resolve("in");
        Path locked = write(in, "locked.csv", "id\n1\n");
        Path ok = write(in, "ok.csv", "id\n2\n");
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        AtomicBoolean failRead = new AtomicBoolean(true);
        FileInboundPoller poller = new FileInboundPoller(in, null, recordingInto(received)) {
            @Override
            protected byte[] readContent(Path p, SeekableByteChannel channel) throws IOException {
                if (failRead.get() && p.getFileName().toString().equals("locked.csv")) {
                    Throwable thrown = failure.get();
                    if (thrown instanceof IOException ioFailure) {
                        throw ioFailure;
                    }
                    if (thrown instanceof Error error) {
                        throw error;
                    }
                    throw (RuntimeException) thrown;
                }
                return super.readContent(p, channel);
            }
        };
        AtomicInteger handed = new AtomicInteger(-1);

        assertThatCode(() -> handed.set(poller.pollOnce())).doesNotThrowAnyException();

        assertThat(handed).hasValue(1);
        assertThat(received).extracting(FileInboundPoller.InboundFile::name).containsExactly("ok.csv");
        assertThat(locked).hasBinaryContent(bytes("id\n1\n"));
        assertThat(ok).doesNotExist();

        failRead.set(false);
        received.clear();

        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).name()).isEqualTo("locked.csv");
        assertThat(received.get(0).content()).isEqualTo(bytes("id\n1\n"));
        assertThat(locked).doesNotExist();
    }

    /**
     * Runs with a file one byte over the read limit, and with a 3 GiB file, past {@link Integer#MAX_VALUE}, for which
     * {@link Files#readAllBytes(Path)} throws {@link OutOfMemoryError}.
     */
    @ParameterizedTest
    @ValueSource(longs = {FileInboundPoller.MAX_CONTENT_LENGTH + 1, 3L << 30})
    void fileTooLargeToReadIsLeftInPlaceWhileOtherFilesAreConsumed(long length) throws IOException {
        Path in = tempDir.resolve("in");
        Path ok = write(in, "ok.csv", "id\n2\n");
        Path huge = in.resolve("huge.csv");
        // Extending an empty file writes no data, so the file is sparse and takes no disk space.
        try (RandomAccessFile file = new RandomAccessFile(huge.toFile(), "rw")) {
            file.setLength(length);
        }
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        FileInboundPoller poller = new FileInboundPoller(in, null, recordingInto(received));
        AtomicInteger handed = new AtomicInteger(-1);

        assertThatCode(() -> handed.set(poller.pollOnce())).doesNotThrowAnyException();

        assertThat(handed).hasValue(1);
        assertThat(received).extracting(FileInboundPoller.InboundFile::name).containsExactly("ok.csv");
        assertThat(ok).doesNotExist();
        assertThat(Files.size(huge)).isEqualTo(length);
        // The read reports the file's size, taken from the open file, and the limit, never its path, because the
        // reason is logged.
        try (SeekableByteChannel channel = Files.newByteChannel(huge)) {
            assertThatThrownBy(() -> poller.readContent(huge, channel))
                    .isInstanceOfSatisfying(FileSystemException.class, e -> {
                        assertThat(e.getFile()).isEqualTo(huge.toString());
                        assertThat(e.getReason()).isEqualTo("file size " + length + " bytes exceeds the read limit of "
                                + FileInboundPoller.MAX_CONTENT_LENGTH + " bytes");
                    });
        }

        received.clear();

        assertThat(poller.pollOnce()).isZero();
        assertThat(received).isEmpty();
        assertThat(Files.size(huge)).isEqualTo(length);
    }

    /**
     * Runs with sizes the open file reports below, at and above its length, as when the file grows or shrinks after
     * its size was taken, including sizes that make the read grow its buffer past the first 8192 bytes.
     */
    @ParameterizedTest
    @ValueSource(longs = {0, 3, 9000, 20000, 20005})
    void readContentReadsToTheEndWhateverSizeTheOpenFileReported(long reportedSize) throws IOException {
        byte[] content = new byte[20000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }
        Path in = Files.createDirectories(tempDir.resolve("in"));
        Path file = Files.write(in.resolve("growing.csv"), content);
        FileInboundPoller poller = new FileInboundPoller(in, null, inbound -> { });

        try (SeekableByteChannel channel = new ReportedSizeChannel(Files.newByteChannel(file), reportedSize)) {
            assertThat(poller.readContent(file, channel)).isEqualTo(content);
        }
    }

    @Test
    void createsMissingInputDirectory() {
        Path in = tempDir.resolve("missing").resolve("in");
        AtomicInteger calls = new AtomicInteger();
        FileInboundPoller poller = new FileInboundPoller(in, null, inbound -> calls.incrementAndGet());
        AtomicInteger handed = new AtomicInteger(-1);

        assertThatCode(() -> handed.set(poller.pollOnce())).doesNotThrowAnyException();

        assertThat(handed).hasValue(0);
        assertThat(calls).hasValue(0);
        assertThat(in).isDirectory();
    }

    @Test
    void createsMissingMoveDirectory() throws IOException {
        Path in = tempDir.resolve("in");
        Path out = tempDir.resolve("not").resolve("yet").resolve("out");
        Path file = write(in, "input.csv", "name,units\naaa,2\n");
        FileInboundPoller poller = new FileInboundPoller(in, out, inbound -> { });

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(out).isDirectory();
        assertThat(out.resolve("input.csv")).hasBinaryContent(bytes("name,units\naaa,2\n"));
        assertThat(file).doesNotExist();
    }

    @Test
    void ignoresSubdirectoriesOfInputDirectory() throws IOException {
        Path in = tempDir.resolve("in");
        Path sub = in.resolve("sub");
        Path nested = write(sub, "nested.csv", "nested\n");
        Path top = write(in, "top.csv", "top\n");
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        FileInboundPoller poller = new FileInboundPoller(in, null, recordingInto(received));

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(received).extracting(FileInboundPoller.InboundFile::name).containsExactly("top.csv");
        assertThat(top).doesNotExist();
        assertThat(sub).isDirectory();
        assertThat(nested).hasBinaryContent(bytes("nested\n"));
    }

    @Test
    void moveReplacesExistingFileOfSameName() throws IOException {
        Path in = tempDir.resolve("in");
        Path out = tempDir.resolve("out");
        Path previous = write(out, "data.csv", "old");
        Path file = write(in, "data.csv", "new");
        FileInboundPoller poller = new FileInboundPoller(in, out, inbound -> { });

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(previous).hasBinaryContent(bytes("new"));
        assertThat(file).doesNotExist();
    }

    @Test
    void returnsZeroForEmptyDirectoryAndCountsHandledFiles() throws Exception {
        Path in = tempDir.resolve("in");
        Files.createDirectories(in);
        FileInboundPoller.FileHandler handler = mock(FileInboundPoller.FileHandler.class);
        FileInboundPoller poller = new FileInboundPoller(in, null, handler);

        assertThat(poller.pollOnce()).isZero();
        verifyNoInteractions(handler);

        write(in, "first.csv", "1\n");
        write(in, "second.csv", "2\n");
        ArgumentCaptor<FileInboundPoller.InboundFile> captor =
                ArgumentCaptor.forClass(FileInboundPoller.InboundFile.class);

        assertThat(poller.pollOnce()).isEqualTo(2);

        verify(handler, times(2)).handle(captor.capture());
        assertThat(captor.getAllValues()).extracting(FileInboundPoller.InboundFile::name)
                .containsExactlyInAnyOrder("first.csv", "second.csv");
        assertThat(poller.pollOnce()).isZero();
        verifyNoMoreInteractions(handler);
    }

    @ParameterizedTest(name = "{0}, move directory {1}")
    @MethodSource("modesAndFlags")
    void ordinaryFilesKeepTheirBytesNamesAndDisposition(Mode mode, boolean withMoveDirectory) throws IOException {
        Path in = tempDir.resolve("in");
        Path out = withMoveDirectory ? tempDir.resolve("out") : null;
        Map<String, String> contents = Map.of("first.csv", "id\n1\n", "with space.csv", "id\n2\n", "empty.csv", "");
        for (Map.Entry<String, String> entry : contents.entrySet()) {
            write(in, entry.getKey(), entry.getValue());
        }
        if (out != null) {
            write(out, "first.csv", "old");
        }
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        FileInboundPoller poller = poller(mode, in, out, recordingInto(received));

        assertThat(poller.pollOnce()).isEqualTo(3);

        assertThat(received).extracting(FileInboundPoller.InboundFile::name)
                .containsExactlyInAnyOrderElementsOf(contents.keySet());
        for (FileInboundPoller.InboundFile inbound : received) {
            assertThat(inbound.path()).isEqualTo(in.resolve(inbound.name()));
            assertThat(inbound.content()).isEqualTo(bytes(contents.get(inbound.name())));
        }
        assertThat(tree(in)).containsExactly(in);
        if (out != null) {
            assertThat(tree(out)).hasSize(contents.size() + 1);
            for (Map.Entry<String, String> entry : contents.entrySet()) {
                assertThat(out.resolve(entry.getKey())).hasBinaryContent(bytes(entry.getValue()));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void handsFilesOverInDirectoryListingOrder(Mode mode) throws IOException {
        Path in = tempDir.resolve("in");
        for (int i = 0; i < 20; i++) {
            write(in, "file-" + i + ".csv", "id\n" + i + "\n");
        }
        File[] listed = in.toFile().listFiles();
        assertThat(listed).isNotNull();
        List<String> listingOrder = Arrays.stream(listed).map(File::getName).toList();
        List<String> handed = new CopyOnWriteArrayList<>();
        FileInboundPoller poller = poller(mode, in, null, inbound -> handed.add(inbound.name()));

        assertThat(poller.pollOnce()).isEqualTo(20);

        // The platform's own listing order, unsorted, as File.listFiles reports it.
        assertThat(handed).containsExactlyElementsOf(listingOrder);
    }

    static Stream<Arguments> linksInInputDirectory() {
        List<Arguments> cases = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            for (LinkKind kind : LinkKind.values()) {
                cases.add(Arguments.of(mode, kind, true));
                cases.add(Arguments.of(mode, kind, false));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, link to {1}, move directory {2}")
    @MethodSource("linksInInputDirectory")
    void symbolicLinkIsNeverFollowedHandedOverOrDisposed(Mode mode, LinkKind kind, boolean withMoveDirectory)
            throws IOException {
        Path in = tempDir.resolve("in");
        Path out = withMoveDirectory ? tempDir.resolve("out") : null;
        Path secret = outsideFile();
        Path ok = write(in, "ok.csv", "id\n1\n");
        Path contained = kind == LinkKind.CONTAINED ? write(in, "target.csv", "id\n2\n") : null;
        Path linkTarget = switch (kind) {
            case OUTSIDE_FILE -> secret;
            case OUTSIDE_DIRECTORY -> secret.getParent();
            case DANGLING -> tempDir.resolve("missing.csv");
            case CONTAINED -> contained;
        };
        Path link = Files.createSymbolicLink(in.resolve("link.csv"), linkTarget);
        List<Path> regularFiles = contained == null ? List.of(ok) : List.of(ok, contained);
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        FileInboundPoller poller = poller(mode, in, out, recordingInto(received));

        assertThat(poller.pollOnce()).isEqualTo(regularFiles.size());

        // A contained link's target is handed over once, as itself; a link never is.
        assertThat(received).extracting(FileInboundPoller.InboundFile::path)
                .containsExactlyInAnyOrderElementsOf(regularFiles);
        for (FileInboundPoller.InboundFile inbound : received) {
            assertThat(inbound.content()).isEqualTo(bytes(inbound.name().equals("ok.csv") ? "id\n1\n" : "id\n2\n"));
        }
        assertLinkLeftInPlace(link, linkTarget);
        assertOutsideUntouched(secret);
        assertThat(ok).doesNotExist();
        if (out != null) {
            List<Path> moved = new ArrayList<>(List.of(out));
            regularFiles.forEach(file -> moved.add(out.resolve(file.getFileName())));
            assertThat(tree(out)).containsExactlyInAnyOrderElementsOf(moved);
            assertThat(out.resolve("ok.csv")).hasBinaryContent(bytes("id\n1\n"));
        }

        received.clear();
        assertThat(poller.pollOnce()).isZero();
        assertThat(received).isEmpty();
        assertLinkLeftInPlace(link, linkTarget);
        assertOutsideUntouched(secret);
    }

    @ParameterizedTest(name = "{0}, move directory {1}")
    @MethodSource("modesAndFlags")
    void entrySwappedForLinkAfterTheListingIsSkipped(Mode mode, boolean withMoveDirectory) throws IOException {
        Path in = tempDir.resolve("in");
        Path out = withMoveDirectory ? tempDir.resolve("out") : null;
        Path secret = outsideFile();
        Path first = write(in, "a.csv", "id\na\n");
        Path second = write(in, "b.csv", "id\nb\n");
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        FileInboundPoller poller = poller(mode, in, out, inbound -> {
            received.add(inbound);
            if (received.size() == 1) {
                // Both names were listed before this handler ran; the other one now stands for a link.
                replaceWithLink(inbound.path().equals(first) ? second : first, secret);
            }
        });

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(received).hasSize(1);
        Path handled = received.get(0).path();
        Path swapped = handled.equals(first) ? second : first;
        assertThat(handled).doesNotExist();
        assertLinkLeftInPlace(swapped, secret);
        assertOutsideUntouched(secret);
        if (out != null) {
            assertThat(tree(out)).containsExactlyInAnyOrder(out, out.resolve(handled.getFileName()));
        }
    }

    static Stream<Arguments> replacementsWhileRead() {
        return Stream.of(Mode.values())
                .flatMap(mode -> Stream.of(Substitute.values()).map(substitute -> Arguments.of(mode, substitute)));
    }

    @ParameterizedTest(name = "{0}, replaced by {1}")
    @MethodSource("replacementsWhileRead")
    void fileReplacedWhileBeingReadIsLeftInPlaceAndNotHandedOver(Mode mode, Substitute substitute)
            throws IOException {
        Path in = tempDir.resolve("in");
        Path out = tempDir.resolve("out");
        Path secret = outsideFile();
        Path file = write(in, "data.csv", "id\n1\n");
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        AtomicBoolean replaced = new AtomicBoolean();
        // The hook runs while the file is open, so the bytes read are those of the file it replaces.
        TestPoller poller = poller(mode, in, out, recordingInto(received)).beforeRead(listed -> {
            if (replaced.compareAndSet(false, true)) {
                replace(listed, substitute, secret);
            }
        });

        assertThat(poller.pollOnce()).isZero();

        assertThat(received).isEmpty();
        assertSubstituteInPlace(file, substitute, secret);
        assertOutsideUntouched(secret);
        assertThat(tree(out)).containsExactly(out);

        // The file was left unmarked, so a regular file standing at its name is taken by the next poll.
        if (substitute == Substitute.OTHER_FILE) {
            assertThat(poller.pollOnce()).isEqualTo(1);
            assertThat(received).singleElement()
                    .satisfies(inbound -> assertThat(inbound.content()).isEqualTo(bytes(OTHER_CONTENT)));
            assertThat(file).doesNotExist();
            assertThat(out.resolve("data.csv")).hasBinaryContent(bytes(OTHER_CONTENT));
        } else {
            assertThat(poller.pollOnce()).isZero();
            assertThat(received).isEmpty();
            assertSubstituteInPlace(file, substitute, secret);
            assertThat(tree(out)).containsExactly(out);
        }
        assertOutsideUntouched(secret);
    }

    static Stream<Arguments> replacementsAfterRead() {
        List<Arguments> cases = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            for (Substitute substitute : Substitute.values()) {
                cases.add(Arguments.of(mode, substitute, true));
                cases.add(Arguments.of(mode, substitute, false));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}, replaced by {1}, move directory {2}")
    @MethodSource("replacementsAfterRead")
    void fileReplacedAfterItWasReadIsNeitherMovedNorDeleted(Mode mode, Substitute substitute,
                                                             boolean withMoveDirectory) throws IOException {
        Path in = tempDir.resolve("in");
        Path out = withMoveDirectory ? tempDir.resolve("out") : null;
        Path secret = outsideFile();
        Path file = write(in, "data.csv", "id\n1\n");
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        FileInboundPoller poller = poller(mode, in, out, inbound -> {
            received.add(inbound);
            replace(inbound.path(), substitute, secret);
        });

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(received).singleElement()
                .satisfies(inbound -> assertThat(inbound.content()).isEqualTo(bytes("id\n1\n")));
        assertSubstituteInPlace(file, substitute, secret);
        assertOutsideUntouched(secret);
        if (out != null) {
            assertThat(tree(out)).containsExactly(out);
        }
    }

    @ParameterizedTest(name = "{0}, link to a directory {1}")
    @MethodSource("modesAndFlags")
    void linkInMoveDirectoryIsReplacedWithoutWritingItsTarget(Mode mode, boolean linkToDirectory)
            throws IOException {
        Path in = tempDir.resolve("in");
        Path out = Files.createDirectories(tempDir.resolve("out"));
        Path secret = outsideFile();
        Path standing =
                Files.createSymbolicLink(out.resolve("data.csv"), linkToDirectory ? secret.getParent() : secret);
        Path file = write(in, "data.csv", "new");
        FileInboundPoller poller = poller(mode, in, out, inbound -> { });

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(file).doesNotExist();
        assertThat(Files.isSymbolicLink(standing)).isFalse();
        assertThat(standing).hasBinaryContent(bytes("new"));
        assertOutsideUntouched(secret);
    }

    @ParameterizedTest(name = "{0}, input directory redirected {1}")
    @MethodSource("modesAndFlags")
    void directoryRedirectedThroughLinkAfterTheFirstPollIsRefused(Mode mode, boolean redirectInput)
            throws IOException {
        Path in = tempDir.resolve("in");
        Path out = tempDir.resolve("out");
        Path secret = outsideFile();
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        FileInboundPoller poller = poller(mode, in, out, recordingInto(received));
        assertThat(poller.pollOnce()).isZero();
        Path file = redirectInput ? null : write(in, "data.csv", "id\n1\n");
        Path redirected = redirectInput ? in : out;
        Path original = tempDir.resolve("original");
        Files.move(redirected, original);
        Files.createSymbolicLink(redirected, secret.getParent());

        assertThat(poller.pollOnce()).isZero();

        assertThat(received).isEmpty();
        assertOutsideUntouched(secret);
        if (file != null) {
            assertThat(file).hasBinaryContent(bytes("id\n1\n"));
        }

        // The directory restored at its pinned location is polled again.
        Files.delete(redirected);
        Files.move(original, redirected);
        Path later = write(in, "later.csv", "id\n2\n");
        assertThat(poller.pollOnce()).isEqualTo(file == null ? 1 : 2);
        assertThat(later).doesNotExist();
        assertThat(out.resolve("later.csv")).hasBinaryContent(bytes("id\n2\n"));
        assertOutsideUntouched(secret);
    }

    @Test
    void directorySwappedWhileItIsOpenedIsRefused() throws IOException {
        Path in = tempDir.resolve("in");
        Path aside = tempDir.resolve("aside");
        Path secret = outsideFile();
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        AtomicBoolean restored = new AtomicBoolean();
        TestPoller poller = poller(Mode.SECURE, in, null, recordingInto(received));
        assertThat(poller.pollOnce()).isZero();
        // The directory opened is the outside one, while the location resolves to the original again once it is open.
        poller.aroundNextOpen(directory -> {
            Files.move(directory, aside);
            Files.createSymbolicLink(directory, secret.getParent());
        }, directory -> {
            Files.delete(directory);
            Files.move(aside, directory);
            restored.set(true);
        });

        assertThat(poller.pollOnce()).isZero();

        assertThat(restored).isTrue();
        assertThat(received).isEmpty();
        assertOutsideUntouched(secret);
        assertThat(Files.isDirectory(in, LinkOption.NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    void secureModeKeepsAMoveDirectoryNestedInTheInputDirectoryConfinedWhenItIsSwappedForALink()
            throws IOException {
        Path in = tempDir.resolve("in");
        Path out = in.resolve("done");
        Path aside = tempDir.resolve("aside");
        Path secret = outsideFile();
        Path first = write(in, "first.csv", "id\n1\n");
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        AtomicBoolean armed = new AtomicBoolean();
        AtomicBoolean swapped = new AtomicBoolean();
        // Once both directories are verified and a file is open, a writer of the input directory swaps the move
        // directory for a link to the outside one.
        TestPoller poller = poller(Mode.SECURE, in, out, recordingInto(received)).beforeRead(listed -> {
            if (armed.get() && swapped.compareAndSet(false, true)) {
                Files.move(out, aside);
                Files.createSymbolicLink(out, secret.getParent());
            }
        });

        // A move directory inside the input directory is accepted, and the listing skips it as a subdirectory.
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(first).doesNotExist();
        assertThat(out.resolve("first.csv")).hasBinaryContent(bytes("id\n1\n"));

        Path second = write(in, "second.csv", "id\n2\n");
        armed.set(true);

        assertThat(poller.pollOnce()).isEqualTo(1);

        // The file went into the directory opened as the move directory, now standing aside, never the link's target.
        assertThat(swapped).isTrue();
        assertThat(received).extracting(FileInboundPoller.InboundFile::name)
                .containsExactly("first.csv", "second.csv");
        assertThat(received.get(1).content()).isEqualTo(bytes("id\n2\n"));
        assertThat(second).doesNotExist();
        assertThat(tree(aside))
                .containsExactlyInAnyOrder(aside, aside.resolve("first.csv"), aside.resolve("second.csv"));
        assertThat(aside.resolve("second.csv")).hasBinaryContent(bytes("id\n2\n"));
        assertLinkLeftInPlace(out, secret.getParent());
        assertOutsideUntouched(secret);

        // The next poll finds the move directory redirected and is refused.
        Path third = write(in, "third.csv", "id\n3\n");
        assertThat(poller.pollOnce()).isZero();
        assertThat(received).hasSize(2);
        assertThat(third).hasBinaryContent(bytes("id\n3\n"));
        assertOutsideUntouched(secret);
    }

    @ParameterizedTest(name = "move directory inside the input directory {0}")
    @ValueSource(booleans = {true, false})
    void pathBasedModeRefusesDirectoriesNestedOneInTheOther(boolean moveInsideInput) throws IOException {
        Path in = moveInsideInput ? tempDir.resolve("in") : tempDir.resolve("out").resolve("in");
        Path out = moveInsideInput ? in.resolve("done") : tempDir.resolve("out");
        Path inner = moveInsideInput ? out : in;
        Path secret = outsideFile();
        Path file = write(in, "data.csv", "id\n1\n");
        Files.createDirectories(out);
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        AtomicBoolean swapped = new AtomicBoolean();
        // The same swap of the inner directory for a link to the outside one, armed for the first file operation
        // after both directories are verified: by path, a later rename or delete would resolve through the link.
        TestPoller poller = poller(Mode.PATH_BASED, in, out, recordingInto(received)).beforeRead(listed -> {
            if (swapped.compareAndSet(false, true)) {
                Files.move(inner, tempDir.resolve("aside"));
                Files.createSymbolicLink(inner, secret.getParent());
            }
        });

        // Every poll is refused before any file is examined, so the swap is never reached.
        assertThat(poller.pollOnce()).isZero();
        assertThat(poller.pollOnce()).isZero();

        assertThat(swapped).isFalse();
        assertThat(received).isEmpty();
        assertThat(Files.isDirectory(inner, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(file).hasBinaryContent(bytes("id\n1\n"));
        assertThat(out.resolve("data.csv")).doesNotExist();
        assertOutsideUntouched(secret);
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void directoryRecreatedAtItsLocationKeepsBeingPolled(Mode mode) throws IOException {
        Path in = tempDir.resolve("in");
        Path out = tempDir.resolve("out");
        FileInboundPoller poller = poller(mode, in, out, inbound -> { });
        assertThat(poller.pollOnce()).isZero();
        Files.delete(in);
        Files.delete(out);
        // A new input directory is created at the same location by the file's writer, the move directory by the poll.
        Path file = write(in, "data.csv", "id\n1\n");

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(file).doesNotExist();
        assertThat(out.resolve("data.csv")).hasBinaryContent(bytes("id\n1\n"));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void inputDirectoryConfiguredThroughLinkIsPolledAtItsTargetUntilRedirected(Mode mode) throws IOException {
        Path real = tempDir.resolve("real-in");
        Path configured = tempDir.resolve("in");
        Path secret = outsideFile();
        Path file = write(real, "data.csv", "id\n1\n");
        Files.createSymbolicLink(configured, real);
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        FileInboundPoller poller = poller(mode, configured, null, recordingInto(received));

        assertThat(poller.pollOnce()).isEqualTo(1);

        // The handed path keeps the configured form.
        assertThat(received).extracting(FileInboundPoller.InboundFile::path)
                .containsExactly(configured.resolve("data.csv"));
        assertThat(file).doesNotExist();

        Files.delete(configured);
        Files.createSymbolicLink(configured, secret.getParent());
        received.clear();

        assertThat(poller.pollOnce()).isZero();

        assertThat(received).isEmpty();
        assertOutsideUntouched(secret);
    }

    @ParameterizedTest
    @EnumSource(MoveTarget.class)
    void copyFallbackMovesTheFileWhenTheRenameFails(MoveTarget standing) throws IOException {
        Path in = tempDir.resolve("in");
        Path out = Files.createDirectories(tempDir.resolve("out"));
        Path secret = outsideFile();
        Path file = write(in, "data.csv", "new");
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
        if (posix) {
            Files.setPosixFilePermissions(file, permissions);
        }
        Path target = out.resolve("data.csv");
        switch (standing) {
            case REGULAR_FILE -> Files.write(target, bytes("old"));
            case LINK_TO_OUTSIDE_FILE -> Files.createSymbolicLink(target, secret);
            case NOTHING -> { }
        }
        TestPoller poller = poller(Mode.SECURE, in, out, inbound -> { }).refusingRenames(source -> { });

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(file).doesNotExist();
        assertThat(Files.isSymbolicLink(target)).isFalse();
        assertThat(target).hasBinaryContent(bytes("new"));
        if (posix) {
            assertThat(Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS)).isEqualTo(permissions);
        }
        assertOutsideUntouched(secret);
    }

    @Test
    void copyFallbackNeverCopiesTheTargetOfALinkSwappedInBeforeTheCopy() throws IOException {
        Path in = tempDir.resolve("in");
        Path out = tempDir.resolve("out");
        Path secret = outsideFile();
        Path file = write(in, "data.csv", "id\n1\n");
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        TestPoller poller = poller(Mode.SECURE, in, out, recordingInto(received))
                .refusingRenames(source -> replaceWithLink(source, secret));

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(received).hasSize(1);
        assertLinkLeftInPlace(file, secret);
        assertOutsideUntouched(secret);
        assertThat(tree(out)).containsExactly(out);
    }

    /** How the poller under test reaches its directories. */
    enum Mode {

        /** Relative to the open directories, through the platform's {@link SecureDirectoryStream}. */
        SECURE,

        /** By path from the pinned locations, as on a platform without a secure directory stream. */
        PATH_BASED
    }

    /** Where a symbolic link in the input directory points. */
    enum LinkKind {
        OUTSIDE_FILE, OUTSIDE_DIRECTORY, DANGLING, CONTAINED
    }

    /** What the test puts at a file's name in place of the file. */
    enum Substitute {
        LINK_TO_OUTSIDE_FILE, OTHER_FILE, DIRECTORY
    }

    /** What stands in the move directory under the file's name before the copy fallback runs. */
    enum MoveTarget {
        NOTHING, REGULAR_FILE, LINK_TO_OUTSIDE_FILE
    }

    /** What a hook does with a file's path. */
    @FunctionalInterface
    interface FileAction {

        void apply(Path file) throws IOException;
    }

    static Stream<Arguments> modesAndFlags() {
        return Stream.of(Mode.values()).flatMap(mode -> Stream.of(Arguments.of(mode, true), Arguments.of(mode, false)));
    }

    /** Creates the poller under test, skipping the test by assumption when the platform lacks the mode. */
    private TestPoller poller(Mode mode, Path in, Path out, FileInboundPoller.FileHandler handler)
            throws IOException {
        if (mode == Mode.SECURE) {
            try (DirectoryStream<Path> probe = Files.newDirectoryStream(tempDir)) {
                assumeTrue(probe instanceof SecureDirectoryStream, "the platform provides no SecureDirectoryStream");
            }
        }
        return new TestPoller(in, out, handler, mode);
    }

    /** The file outside the input and move directories that no link may expose. */
    private Path outsideFile() throws IOException {
        return write(tempDir.resolve("outside"), "secret.csv", SECRET);
    }

    /** Asserts that the outside file is unchanged and still alone in its directory, so nothing was copied there. */
    private static void assertOutsideUntouched(Path secret) throws IOException {
        assertThat(secret).hasBinaryContent(bytes(SECRET));
        assertThat(tree(secret.getParent())).containsExactlyInAnyOrder(secret.getParent(), secret);
    }

    private static void assertLinkLeftInPlace(Path link, Path target) throws IOException {
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(Files.readSymbolicLink(link)).isEqualTo(target);
    }

    private void replace(Path file, Substitute substitute, Path secret) throws IOException {
        switch (substitute) {
            case LINK_TO_OUTSIDE_FILE -> replaceWithLink(file, secret);
            case OTHER_FILE -> replaceWithFile(file, OTHER_CONTENT);
            case DIRECTORY -> {
                Files.delete(file);
                Files.createDirectories(file.resolve("child"));
            }
        }
    }

    private static void assertSubstituteInPlace(Path file, Substitute substitute, Path secret) throws IOException {
        switch (substitute) {
            case LINK_TO_OUTSIDE_FILE -> assertLinkLeftInPlace(file, secret);
            case OTHER_FILE -> {
                assertThat(Files.isSymbolicLink(file)).isFalse();
                assertThat(file).hasBinaryContent(bytes(OTHER_CONTENT));
            }
            case DIRECTORY -> assertThat(file.resolve("child")).isDirectory();
        }
    }

    /** Replaces {@code file} with a link to {@code target} in one rename, as a hostile writer swaps an entry. */
    private void replaceWithLink(Path file, Path target) throws IOException {
        Files.move(Files.createSymbolicLink(stagingPath(), target), file, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Replaces {@code file} with another regular file in one rename; the replacement exists before the replaced file
     * is released, so it can never take over its identity.
     */
    private void replaceWithFile(Path file, String content) throws IOException {
        Files.move(Files.write(stagingPath(), bytes(content)), file, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path stagingPath() throws IOException {
        return Files.createDirectories(tempDir.resolve("staging")).resolve("staged-" + staged.incrementAndGet());
    }

    /** A poller in a chosen mode, with a hook inside the read and, on request, renames that fail. */
    private static final class TestPoller extends FileInboundPoller {

        private final Mode mode;

        private FileAction beforeRead = file -> { };

        private FileAction beforeRefusedRename;

        private FileAction beforeNextOpen = directory -> { };

        private FileAction afterNextOpen = directory -> { };

        TestPoller(Path in, Path out, FileHandler handler, Mode mode) {
            super(in, out, handler);
            this.mode = mode;
        }

        /** Runs {@code before} and {@code after} with the pinned location around the next opening of a directory. */
        TestPoller aroundNextOpen(FileAction before, FileAction after) {
            this.beforeNextOpen = before;
            this.afterNextOpen = after;
            return this;
        }

        /** Runs {@code action} with the listed file's path while the file is open and before its bytes are read. */
        TestPoller beforeRead(FileAction action) {
            this.beforeRead = action;
            return this;
        }

        /** Makes every rename fail after running {@code action} with the source's path, so the copy fallback runs. */
        TestPoller refusingRenames(FileAction action) {
            this.beforeRefusedRename = action;
            return this;
        }

        @Override
        DirectoryStream<Path> openDirectory(Path directory) throws IOException {
            FileAction before = beforeNextOpen;
            FileAction after = afterNextOpen;
            beforeNextOpen = unused -> { };
            afterNextOpen = unused -> { };
            before.apply(directory);
            DirectoryStream<Path> opened = super.openDirectory(directory);
            after.apply(directory);
            if (mode == Mode.PATH_BASED) {
                return new ListingOnlyStream(opened);
            }
            if (beforeRefusedRename != null && opened instanceof SecureDirectoryStream<Path> secure) {
                return new RenameRefusingStream(secure, directory, beforeRefusedRename);
            }
            return opened;
        }

        @Override
        protected byte[] readContent(Path file, SeekableByteChannel channel) throws IOException {
            beforeRead.apply(file);
            return super.readContent(file, channel);
        }
    }

    /** A channel reporting a fixed size, as a file that grew or shrank after its size was taken. */
    private static final class ReportedSizeChannel implements SeekableByteChannel {

        private final SeekableByteChannel delegate;

        private final long reportedSize;

        ReportedSizeChannel(SeekableByteChannel delegate, long reportedSize) {
            this.delegate = delegate;
            this.reportedSize = reportedSize;
        }

        @Override
        public long size() {
            return reportedSize;
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            return delegate.read(destination);
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            return delegate.write(source);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /** A plain directory stream over the platform's, so that the poller takes its path-based operations. */
    private static final class ListingOnlyStream implements DirectoryStream<Path> {

        private final DirectoryStream<Path> delegate;

        ListingOnlyStream(DirectoryStream<Path> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Iterator<Path> iterator() {
            return delegate.iterator();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /** A secure directory stream whose renames fail, as across file stores, after a hook ran with the source. */
    private static final class RenameRefusingStream implements SecureDirectoryStream<Path> {

        private final SecureDirectoryStream<Path> delegate;

        private final Path directory;

        private final FileAction beforeRefusal;

        RenameRefusingStream(SecureDirectoryStream<Path> delegate, Path directory, FileAction beforeRefusal) {
            this.delegate = delegate;
            this.directory = directory;
            this.beforeRefusal = beforeRefusal;
        }

        @Override
        public void move(Path source, SecureDirectoryStream<Path> targetDirectory, Path target) throws IOException {
            beforeRefusal.apply(directory.resolve(source));
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "rename refused");
        }

        @Override
        public SecureDirectoryStream<Path> newDirectoryStream(Path path, LinkOption... options) throws IOException {
            return delegate.newDirectoryStream(path, options);
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                                  FileAttribute<?>... attributes) throws IOException {
            return delegate.newByteChannel(path, options, attributes);
        }

        @Override
        public void deleteFile(Path path) throws IOException {
            delegate.deleteFile(path);
        }

        @Override
        public void deleteDirectory(Path path) throws IOException {
            delegate.deleteDirectory(path);
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Class<V> type) {
            return delegate.getFileAttributeView(type);
        }

        @Override
        public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
            return delegate.getFileAttributeView(path, type, options);
        }

        @Override
        public Iterator<Path> iterator() {
            return delegate.iterator();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /** A handler recording a copy of every file it receives, so later assertions see the bytes as handed over. */
    private static FileInboundPoller.FileHandler recordingInto(List<FileInboundPoller.InboundFile> received) {
        return inbound -> received.add(
                new FileInboundPoller.InboundFile(inbound.path(), inbound.name(), inbound.content().clone()));
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        return Files.write(dir.resolve(name), bytes(content));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(UTF_8);
    }

    /** Every path under {@code root}, {@code root} included. */
    private static List<Path> tree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.toList();
        }
    }
}
