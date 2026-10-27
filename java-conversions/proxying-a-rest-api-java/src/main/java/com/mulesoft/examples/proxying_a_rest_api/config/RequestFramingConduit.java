/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.xnio.IoUtils;
import org.xnio.channels.StreamSinkChannel;
import org.xnio.conduits.AbstractStreamSourceConduit;
import org.xnio.conduits.ConduitReadableByteChannel;
import org.xnio.conduits.StreamSourceConduit;

/**
 * The source conduit of an accepted listener connection, directly above the socket and below every conduit Undertow
 * adds: it reads the client's bytes into its own buffer, runs them through a {@link RequestFramingValidator} and
 * hands Undertow only the bytes the validator released.
 *
 * <ul>
 *   <li>A request head is held back until it is complete and valid, then handed over whole, so Undertow's parser
 *       always starts a request at a message boundary and never sees part of a head it would split differently.
 *       Body bytes are handed over as they are validated; bytes of a pipelined request stay buffered until their
 *       turn.</li>
 *   <li>A refused head is dropped together with everything after it, and Undertow receives the 7 bytes
 *       {@code GET /<CR><LF>} in its place. Its parser refuses a CR inside a request target, so Undertow answers with
 *       its own {@code HTTP/1.1 400 Bad Request}, an empty body and {@code Connection: close}, as for any request it
 *       cannot parse. Reads then return end of stream. Those 7 bytes come in a read of their own, after every byte
 *       before the refused head has been read: Undertow then answers the requests before it first, whereas a parse
 *       failure among pipelined bytes it already holds would close the connection with their responses still
 *       buffered and unsent.</li>
 *   <li>A body that breaks its framing, and the end of the stream inside a head, end the stream at that point:
 *       reads return end of stream once the bytes before it are handed over, so Undertow never parses anything
 *       after the violation, and a partial head is never handed over.</li>
 * </ul>
 *
 * <p>Because a head is handed over only once complete, Undertow's no-request timeout (60 s by default) runs until the
 * whole head has arrived rather than until its first byte.
 *
 * <p>The validator also records each released head's header fields as the client sent them, which Undertow's parser
 * does not keep: it turns every run of SP and HTAB in a value into one SP. The records wait here, in release order,
 * until {@link #nextReleasedHead()} hands each to the exchange Undertow creates from that head; the handler
 * {@link RequestFramingGuardConfig} registers takes one per exchange. Waiting records belong to heads already handed
 * over or still buffered, so the bytes Undertow and this conduit hold bound them.
 *
 * <p>Bytes held here are not visible to the socket's readiness, so every readiness call takes them into account:
 * {@link #resumeReads()} wakes the read listener up when bytes are ready, the {@code awaitReadable} methods return at
 * once, and {@link #isReadShutdown()} stays {@code false} until they are handed over. The scatter read and both
 * {@code transferTo} methods go through {@link #read(ByteBuffer)}, never straight to the socket. The buffer holds at
 * most one head and one read of the socket, and is released whenever it is empty, so an idle connection keeps none.
 *
 * <p>The guard reads plain HTTP/1.x: installed below a TLS layer it would see encrypted bytes, which is why
 * {@link RequestFramingGuardConfig} refuses to start with TLS or HTTP/2 enabled. One reader at a time, as XNIO
 * conduits are used; the readiness methods and {@link #nextReleasedHead()} may be called from other threads.
 */
final class RequestFramingConduit extends AbstractStreamSourceConduit<StreamSourceConduit> {

    /** What Undertow receives in place of a refused head: its parser answers a CR in the target with 400. */
    static final byte[] REJECTION_TRIGGER = {'G', 'E', 'T', ' ', '/', '\r', '\n'};

    /** Bytes requested from the socket per read, and the buffer's initial capacity. */
    static final int READ_BYTES = 16 * 1024;

    private static final byte[] NO_BYTES = new byte[0];

    private final RequestFramingValidator validator;

    private final int maxHeadBytes;

    private byte[] buffer = NO_BYTES;

    /** Index of the first buffered byte not yet handed over. */
    private int start;

    /** Index after the last buffered byte. */
    private int end;

    /** Stream bytes handed over so far. */
    private long handedOver;

    /** Buffered bytes from {@link #start} on that may be handed over, the rejection trigger included. */
    private volatile int deliverable;

    /** Bytes of the rejection trigger at the end of the deliverable ones, not yet handed over. */
    private int triggerBytes;

    /** Whether no byte beyond the buffered deliverable ones will ever be handed over. */
    private volatile boolean finished;

    /**
     * Header fields of the heads released to Undertow, as sent, not yet taken by an exchange. Filled by the reading
     * thread and taken by the thread that runs each exchange's handlers, which may be another one.
     */
    private final Queue<SentHeaderFields> releasedHeads = new ConcurrentLinkedQueue<>();

    /**
     * @param next         the conduit of the socket
     * @param maxHeadBytes the most bytes a request head may hold, as {@link RequestFramingValidator} counts them
     */
    RequestFramingConduit(StreamSourceConduit next, int maxHeadBytes) {
        super(next);
        this.validator = new RequestFramingValidator(maxHeadBytes);
        this.maxHeadBytes = maxHeadBytes;
    }

    /**
     * Hands over deliverable bytes, reading and validating more from the socket when none are buffered.
     *
     * @return the number of bytes copied into {@code dst}, {@code 0} when none is deliverable yet, or {@code -1} once
     *         the stream has ended for Undertow
     */
    @Override
    public int read(ByteBuffer dst) throws IOException {
        if (!dst.hasRemaining()) {
            return 0;
        }
        if (deliverable == 0 && !finished) {
            fill();
        }
        int available = deliverable;
        if (available == 0) {
            return finished ? -1 : 0;
        }
        int streamBytes = available - triggerBytes;
        int count = Math.min(streamBytes > 0 ? streamBytes : available, dst.remaining());
        dst.put(buffer, start, count);
        start += count;
        if (streamBytes > 0) {
            handedOver += count;
        } else {
            triggerBytes -= count;
        }
        if (start == end) {
            buffer = NO_BYTES;
            start = 0;
            end = 0;
        }
        deliverable = available - count;
        return count;
    }

    /** Reads into each buffer in turn; the rejection trigger never shares a call with the bytes before it. */
    @Override
    public long read(ByteBuffer[] dsts, int offs, int len) throws IOException {
        long total = 0;
        for (int i = offs; i < offs + len; i++) {
            ByteBuffer dst = dsts[i];
            if (!dst.hasRemaining()) {
                continue;
            }
            if (total > 0 && triggerBytes > 0 && deliverable == triggerBytes) {
                break;
            }
            int count = read(dst);
            if (count == -1) {
                return total == 0 ? -1 : total;
            }
            total += count;
            if (dst.hasRemaining()) {
                break;
            }
        }
        return total;
    }

    @Override
    public long transferTo(long position, long count, FileChannel target) throws IOException {
        return target.transferFrom(new ConduitReadableByteChannel(this), position, count);
    }

    @Override
    public long transferTo(long count, ByteBuffer throughBuffer, StreamSinkChannel target) throws IOException {
        return IoUtils.transfer(new ConduitReadableByteChannel(this), count, throughBuffer, target);
    }

    /** Wakes the read listener up at once when bytes are ready to hand over, which the socket cannot signal. */
    @Override
    public void resumeReads() {
        if (readable()) {
            next.wakeupReads();
        } else {
            next.resumeReads();
        }
    }

    @Override
    public void awaitReadable() throws IOException {
        if (!readable()) {
            next.awaitReadable();
        }
    }

    @Override
    public void awaitReadable(long time, TimeUnit timeUnit) throws IOException {
        if (!readable()) {
            next.awaitReadable(time, timeUnit);
        }
    }

    /** @return {@code true} once nothing more will be handed over, never while deliverable bytes are buffered */
    @Override
    public boolean isReadShutdown() {
        return deliverable == 0 && (finished || next.isReadShutdown());
    }

    /** Drops the buffered bytes, so every later read returns end of stream, and shuts the socket's reads down. */
    @Override
    public void terminateReads() throws IOException {
        finished = true;
        deliverable = 0;
        triggerBytes = 0;
        next.terminateReads();
    }

    /**
     * Takes the header fields of the oldest released head no exchange has taken yet. Undertow parses the heads in the
     * order they were released and runs one exchange per head, one after another, so the exchange created from a
     * head takes that head's fields when it calls this once, before any later exchange of the connection does.
     *
     * @return the fields, or {@code null} when every released head has been taken
     */
    SentHeaderFields nextReleasedHead() {
        return releasedHeads.poll();
    }

    /** Whether a read would return at once: bytes are ready, or the stream has ended. */
    private boolean readable() {
        return deliverable > 0 || finished;
    }

    /**
     * Reads from the socket until bytes become deliverable, the socket has nothing more for now, or the stream ends.
     * Called only when nothing deliverable is buffered, so the buffer then holds at most an incomplete head.
     */
    private void fill() throws IOException {
        while (deliverable == 0 && !finished) {
            reserve(READ_BYTES);
            int read = next.read(ByteBuffer.wrap(buffer, end, buffer.length - end));
            if (read == 0) {
                return;
            }
            if (read < 0) {
                // A partial head is never handed over; nothing else is buffered, since nothing is deliverable.
                buffer = NO_BYTES;
                start = 0;
                end = 0;
                finished = true;
                return;
            }
            int from = end;
            end += read;
            validator.consume(buffer, from, read);
            for (SentHeaderFields head = validator.pollReleasedHead(); head != null;
                    head = validator.pollReleasedHead()) {
                releasedHeads.add(head);
            }
            int released = (int) (validator.releasedBytes() - handedOver);
            switch (validator.outcome()) {
                case HEAD_REJECTED -> {
                    end = start + released;
                    reserve(REJECTION_TRIGGER.length);
                    System.arraycopy(REJECTION_TRIGGER, 0, buffer, end, REJECTION_TRIGGER.length);
                    end += REJECTION_TRIGGER.length;
                    triggerBytes = REJECTION_TRIGGER.length;
                    deliverable = released + REJECTION_TRIGGER.length;
                    finished = true;
                }
                case BODY_REJECTED -> {
                    end = start + released;
                    deliverable = released;
                    finished = true;
                }
                case ACCEPTING -> deliverable = released;
                default -> throw new IllegalStateException("unknown outcome " + validator.outcome());
            }
        }
    }

    /**
     * Makes room for {@code bytes} more after {@link #end}: moves the buffered bytes to the front, and grows the
     * buffer when that is not enough, at most to one head and one read beyond it.
     */
    private void reserve(int bytes) {
        if (buffer.length - end >= bytes) {
            return;
        }
        int buffered = end - start;
        if (start > 0 && buffer.length - buffered >= bytes) {
            System.arraycopy(buffer, start, buffer, 0, buffered);
        } else {
            long doubled = Math.min(2L * buffer.length, (long) maxHeadBytes + READ_BYTES);
            byte[] grown = new byte[Math.toIntExact(Math.max((long) buffered + bytes, doubled))];
            System.arraycopy(buffer, start, grown, 0, buffered);
            buffer = grown;
        }
        start = 0;
        end = buffered;
    }
}
