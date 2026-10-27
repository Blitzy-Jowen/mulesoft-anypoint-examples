/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.xnio.XnioIoThread;
import org.xnio.XnioWorker;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.conduits.ReadReadyHandler;
import org.xnio.conduits.StreamSourceConduit;

/**
 * Tests {@link RequestFramingConduit} over an in-memory socket conduit that returns one queued segment per read:
 * which bytes Undertow is handed and in which reads, the rejection trigger, the end of stream, the readiness calls
 * that must account for bytes the conduit holds and the socket no longer signals, and the queue of released heads'
 * fields that exchanges take.
 */
class RequestFramingConduitTest {

    private static final int LIMIT = 8192;

    private static final String HEAD = "GET /a HTTP/1.1\r\nHost: h\r\n\r\n";

    private final FakeSocket socket = new FakeSocket();

    private final RequestFramingConduit conduit = new RequestFramingConduit(socket, LIMIT);

    @Test
    void headIsHeldUntilCompleteAndReadinessWaitsOnTheSocket() throws IOException {
        socket.segment("GET /a HTTP/1.1\r\nHo");

        assertThat(conduit.read(ByteBuffer.allocate(1024))).isZero();
        conduit.resumeReads();
        conduit.awaitReadable();
        conduit.awaitReadable(1, TimeUnit.SECONDS);
        assertThat(socket.resumes).isEqualTo(1);
        assertThat(socket.wakeups).isZero();
        assertThat(socket.awaits).isEqualTo(2);

        socket.segment("st: h\r\n\r\n");

        assertThat(read(1024)).isEqualTo(HEAD);
    }

    @Test
    void bufferedBytesWakeTheListenerAndKeepReadsOpen() throws IOException {
        socket.segment(HEAD);
        socket.readShutdown = true;

        assertThat(read(10)).isEqualTo(HEAD.substring(0, 10));
        conduit.resumeReads();
        conduit.awaitReadable();
        conduit.awaitReadable(1, TimeUnit.SECONDS);

        assertThat(socket.wakeups).isEqualTo(1);
        assertThat(socket.resumes).isZero();
        assertThat(socket.awaits).isZero();
        assertThat(conduit.isReadShutdown()).isFalse();
        assertThat(read(1024)).isEqualTo(HEAD.substring(10));
        assertThat(conduit.isReadShutdown()).isTrue();
    }

    @Test
    void refusedHeadIsReplacedByTheTriggerInAReadOfItsOwn() throws IOException {
        socket.segment(HEAD + "GET /b HTTP/1.1\r\nX: a\rAuthorization: x\r\n\r\n");

        assertThat(read(4096)).isEqualTo(HEAD);
        assertThat(conduit.isReadShutdown()).isFalse();
        assertThat(read(4096)).isEqualTo("GET /\r\n");
        assertThat(conduit.read(ByteBuffer.allocate(4096))).isEqualTo(-1);
        assertThat(conduit.isReadShutdown()).isTrue();
        conduit.resumeReads();
        conduit.awaitReadable();
        assertThat(socket.wakeups).isEqualTo(1);
        assertThat(socket.awaits).isZero();
        assertThat(socket.reads).isEqualTo(1);
    }

    @Test
    void scatterReadNeverJoinsTheTriggerToTheBytesBeforeIt() throws IOException {
        socket.segment(HEAD + "BAD\n");
        ByteBuffer[] buffers = {
                ByteBuffer.allocate(4), ByteBuffer.allocate(HEAD.length() - 4), ByteBuffer.allocate(64)};

        assertThat(conduit.read(buffers, 0, 3)).isEqualTo(HEAD.length());
        assertThat(buffers[2].position()).isZero();
        assertThat(conduit.read(buffers, 2, 1)).isEqualTo(7);
        assertThat(text(buffers[2])).isEqualTo("GET /\r\n");
        assertThat(conduit.read(buffers, 2, 1)).isEqualTo(-1);
    }

    @Test
    void scatterReadFillsEveryBuffer() throws IOException {
        socket.segment(HEAD);
        ByteBuffer[] buffers = {ByteBuffer.allocate(0), ByteBuffer.allocate(5), ByteBuffer.allocate(1024)};

        assertThat(conduit.read(buffers, 0, 3)).isEqualTo(HEAD.length());
        assertThat(text(buffers[1]) + text(buffers[2])).isEqualTo(HEAD);
        assertThat(conduit.read(buffers, 2, 1)).isZero();
    }

    @Test
    void bodyViolationEndsTheStreamAfterTheBytesBeforeIt() throws IOException {
        String valid = "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5";
        socket.segment(valid + "z\r\nhello\r\n0\r\n\r\n" + HEAD);

        assertThat(read(4096)).isEqualTo(valid);
        assertThat(conduit.read(ByteBuffer.allocate(4096))).isEqualTo(-1);
        assertThat(conduit.isReadShutdown()).isTrue();
    }

    @Test
    void endOfStreamInsideAHeadHandsNothingOfItOver() throws IOException {
        socket.segment(HEAD + "GET /b HT");
        socket.endOfStream = true;

        assertThat(read(4096)).isEqualTo(HEAD);
        assertThat(conduit.read(ByteBuffer.allocate(4096))).isEqualTo(-1);
        assertThat(conduit.isReadShutdown()).isTrue();
    }

    @Test
    void bodyBytesPassAsTheyArriveAndPipelinedBytesWaitTheirTurn() throws IOException {
        String head = "POST / HTTP/1.1\r\nContent-Length: 6\r\n\r\n";
        socket.segment(head + "abc");
        socket.segment("def" + HEAD + "GET /c");

        assertThat(read(4096)).isEqualTo(head + "abc");
        assertThat(read(4096)).isEqualTo("def" + HEAD);
        assertThat(conduit.read(ByteBuffer.allocate(4096))).isZero();
    }

    /** A head larger than one socket read grows the buffer; one byte past the limit is refused. */
    @ParameterizedTest(name = "head of {0} bytes, limit {1}")
    @CsvSource({"16484, 32768, true", "8193, 8192, false"})
    void headLongerThanOneSocketReadIsGatheredOrRefusedAtTheLimit(int headBytes, int limit, boolean accepted)
            throws IOException {
        RequestFramingConduit guarded = new RequestFramingConduit(socket, limit);
        String start = "GET / HTTP/1.1\r\nX-Pad: ";
        String head = start + "a".repeat(headBytes - start.length() - 4) + "\r\n\r\n";
        for (int i = 0; i < head.length(); i += 1000) {
            socket.segment(head.substring(i, Math.min(head.length(), i + 1000)));
        }
        ByteBuffer dst = ByteBuffer.allocate(head.length() + 16);

        int read = guarded.read(dst);

        assertThat(head.length()).isGreaterThan(RequestFramingConduit.READ_BYTES / 2);
        if (accepted) {
            assertThat(read).isEqualTo(head.length());
            assertThat(text(dst)).isEqualTo(head);
        } else {
            assertThat(text(dst)).isEqualTo("GET /\r\n");
            assertThat(guarded.read(ByteBuffer.allocate(16))).isEqualTo(-1);
        }
    }

    @Test
    void transferToAFileGoesThroughTheGuard(@TempDir Path directory) throws IOException {
        socket.segment(HEAD + "GET /b HTTP/1.1\r\nX: a\u0000\r\n\r\n");
        Path file = directory.resolve("transfer");

        try (FileChannel target = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            assertThat(conduit.transferTo(0, 4096, target)).isEqualTo(HEAD.length() + 7);
        }

        // The refused head never reaches the target; the trigger in its place does.
        assertThat(Files.readString(file, ISO_8859_1)).isEqualTo(HEAD + "GET /\r\n");
    }

    @Test
    void terminateReadsEndsTheStreamAndShutsTheSocketDown() throws IOException {
        socket.segment(HEAD);
        assertThat(read(4)).isEqualTo("GET ");

        conduit.terminateReads();

        assertThat(socket.terminated).isTrue();
        assertThat(conduit.read(ByteBuffer.allocate(64))).isEqualTo(-1);
        assertThat(conduit.isReadShutdown()).isTrue();
    }

    @Test
    void releasedHeadsAreQueuedInOrderAndEachIsTakenOnce() throws IOException {
        socket.segment(HEAD + "GET /b HTTP/1.1\r\nX-B: b\t2\r\n\r\n" + "GET /c HTTP/1.1\r\nX: a\rb\r\n\r\n");

        assertThat(conduit.nextReleasedHead()).isNull();
        assertThat(read(4096)).isEqualTo(HEAD + "GET /b HTTP/1.1\r\nX-B: b\t2\r\n\r\n");

        assertThat(RequestFramingValidatorTest.fields(conduit.nextReleasedHead())).containsExactly("Host", "h");
        assertThat(RequestFramingValidatorTest.fields(conduit.nextReleasedHead())).containsExactly("X-B", "b\t2");
        // The refused head is replaced by the trigger and never queued.
        assertThat(conduit.nextReleasedHead()).isNull();
    }

    @Test
    void emptyDestinationReadsNothing() throws IOException {
        socket.segment(HEAD);

        assertThat(conduit.read(ByteBuffer.allocate(0))).isZero();
        assertThat(socket.reads).isZero();
    }

    private String read(int capacity) throws IOException {
        ByteBuffer dst = ByteBuffer.allocate(capacity);
        int count = conduit.read(dst);
        assertThat(count).isPositive();
        return text(dst);
    }

    private static String text(ByteBuffer buffer) {
        return new String(buffer.array(), 0, buffer.position(), ISO_8859_1);
    }

    /**
     * A socket conduit in memory: each read returns the next queued segment, as much of it as fits, then 0, or
     * end of stream once {@link #endOfStream} is set. It counts the readiness calls the guard makes.
     */
    private static final class FakeSocket implements StreamSourceConduit {

        private final Deque<ByteBuffer> segments = new ArrayDeque<>();

        boolean endOfStream;

        boolean readShutdown;

        boolean terminated;

        int reads;

        int resumes;

        int wakeups;

        int awaits;

        int suspends;

        void segment(String bytes) {
            segments.add(ByteBuffer.wrap(bytes.getBytes(ISO_8859_1)));
        }

        @Override
        public int read(ByteBuffer dst) {
            reads++;
            ByteBuffer segment = segments.peek();
            if (segment == null) {
                return endOfStream ? -1 : 0;
            }
            int count = Math.min(segment.remaining(), dst.remaining());
            ByteBuffer slice = segment.slice();
            slice.limit(count);
            dst.put(slice);
            segment.position(segment.position() + count);
            if (!segment.hasRemaining()) {
                segments.poll();
            }
            return count;
        }

        @Override
        public long read(ByteBuffer[] dsts, int offs, int len) {
            throw new AssertionError("the guard reads the socket one buffer at a time");
        }

        @Override
        public long transferTo(long position, long count, FileChannel target) {
            throw new AssertionError("the guard never transfers straight from the socket");
        }

        @Override
        public long transferTo(long count, ByteBuffer throughBuffer, StreamSinkChannel target) {
            throw new AssertionError("the guard never transfers straight from the socket");
        }

        @Override
        public void terminateReads() {
            terminated = true;
            readShutdown = true;
        }

        @Override
        public boolean isReadShutdown() {
            return readShutdown;
        }

        @Override
        public void resumeReads() {
            resumes++;
        }

        @Override
        public void suspendReads() {
            suspends++;
        }

        @Override
        public void wakeupReads() {
            wakeups++;
        }

        @Override
        public boolean isReadResumed() {
            return resumes > suspends;
        }

        @Override
        public void awaitReadable() {
            awaits++;
        }

        @Override
        public void awaitReadable(long time, TimeUnit timeUnit) {
            awaits++;
        }

        @Override
        public XnioIoThread getReadThread() {
            throw new AssertionError("not used by the guard");
        }

        @Override
        public void setReadReadyHandler(ReadReadyHandler handler) {
            throw new AssertionError("not used by the guard");
        }

        @Override
        public XnioWorker getWorker() {
            throw new AssertionError("not used by the guard");
        }
    }
}
