package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.trigger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * content is inline, every directory lives under {@link TempDir}, no order across several files is asserted because
 * directory listing is unsorted, and an unreadable file is simulated by overriding
 * {@link FileInboundPoller#readContent(Path)}, never through file permissions, which a root build ignores.
 */
class FileInboundPollerTest {

    private static final long LATCH_TIMEOUT_SECONDS = 10;

    @TempDir
    Path tempDir;

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

        assertThat(handed).isEqualTo(1);
        assertThat(interrupted).isTrue();
        assertThat(file).doesNotExist();
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

    @Test
    void unopenableFileIsLeftInPlaceUnmarkedAndRetriedLater() throws IOException {
        Path in = tempDir.resolve("in");
        Path locked = write(in, "locked.csv", "id\n1\n");
        Path ok = write(in, "ok.csv", "id\n2\n");
        List<FileInboundPoller.InboundFile> received = new CopyOnWriteArrayList<>();
        AtomicBoolean failRead = new AtomicBoolean(true);
        FileInboundPoller poller = new FileInboundPoller(in, null, recordingInto(received)) {
            @Override
            protected byte[] readContent(Path p) throws IOException {
                if (failRead.get() && p.getFileName().toString().equals("locked.csv")) {
                    throw new IOException("locked");
                }
                return super.readContent(p);
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
