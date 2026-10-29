/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api.config;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Reads the bytes a client sends on one HTTP/1.1 connection, in the order they arrive, and decides how many of them
 * Undertow's request parser may see. {@link RequestFramingConduit} feeds it every byte it reads from the socket and
 * hands Undertow only the bytes released here.
 *
 * <p>Undertow 2.3.17's parser ends a header line at a bare CR or a bare LF and starts a new header at the next byte,
 * so {@code X-Test: a<CR>Authorization: Bearer x} becomes a second {@code Authorization} header the proxy forwards;
 * it also keeps NUL, VT, FF and DEL in a value. Once parsed, a value no longer shows where the client put a line
 * end, so the check has to run on the raw bytes, before the parser.
 *
 * <p>Request head, held back until it is complete and released whole, or refused:
 * <ul>
 *   <li>request line: a method of token characters, one SP, a non-empty target, one SP, {@code HTTP/} digit
 *       {@code .} digit, CRLF. The target may hold any byte except SP, HTAB, CR and LF; what else it may hold is
 *       left to Undertow;</li>
 *   <li>each header line: one or more token characters, {@code :} with no whitespace before it, then value bytes
 *       (SP, HTAB, {@code 0x21}-{@code 0x7E}, {@code 0x80}-{@code 0xFF}) and CRLF. A line that starts with SP or HTAB
 *       continues the previous value (obs-fold), as Undertow folds it; the first header line cannot;</li>
 *   <li>every CR is followed by LF and every LF follows a CR; the head ends at an empty line, CRLF;</li>
 *   <li>at most the configured number of bytes, request line and final CRLF included, the limit Undertow applies to
 *       a head itself.</li>
 * </ul>
 *
 * <p>Body framing, decided from the released head exactly as Undertow's {@code HttpTransferEncoding.setupRequest}
 * decides it, so both always agree where the next request starts. Header names match ignoring ASCII case, and a
 * value counts as Undertow stores it: without leading and trailing SP and HTAB, every inner run of them (an obs-fold
 * included) one SP. A head with more than one {@code Transfer-Encoding}, more than one {@code Content-Length}, both
 * of them, or a {@code Content-Length} that is not a decimal number within {@code long} range is refused, as
 * Undertow refuses it with 400 after reading the body by its own rule.
 * <ul>
 *   <li>{@code Transfer-Encoding} other than {@code identity}, whatever coding it names: chunked, read strictly. A
 *       chunk size of hexadecimal digits that fits in 56 bits, optionally SP or HTAB and then a {@code ;} extension
 *       of value bytes, CRLF, the chunk data, CRLF; after the last chunk, trailer lines of the header-line form
 *       without obs-fold, then CRLF. Undertow's chunk reader accepts more, but agrees on every body that passes;</li>
 *   <li>{@code Transfer-Encoding: identity} without {@code Content-Length}: everything up to the end of the
 *       connection is body, which Undertow reads the same way;</li>
 *   <li>{@code Content-Length}: that many body bytes;</li>
 *   <li>neither: no body.</li>
 * </ul>
 * Body bytes are released as they are read. A head that breaks a rule is refused with the bytes before it still
 * released; a body that breaks one stops the stream at the offending byte. Either way nothing after it is ever
 * released, and {@link #outcome()} says which happened.
 *
 * <p>Every head released is also recorded as {@link SentHeaderFields}: its header fields in the order sent, each name
 * as sent and each value without leading and trailing SP and HTAB, every obs-fold replaced by one SP and every other
 * byte kept, inner runs of SP and HTAB included. Undertow keeps only one SP of every such run, so these are the
 * values as the client sent them. {@link #pollReleasedHead()} hands the records over in stream order; trailer fields
 * are not recorded. A record holds at most the bytes of its head, which the head limit bounds.
 *
 * <p>The connection is taken to stay HTTP/1.x throughout: the project enables neither HTTP/2 nor TLS on its listener,
 * and nothing in it switches a connection to another protocol. Not thread-safe; one instance per connection.
 */
final class RequestFramingValidator {

    /** What the stream has shown so far. */
    enum Outcome {

        /** Every byte so far follows the rules; more may be released. */
        ACCEPTING,

        /** A request head broke a rule; nothing from its first byte on is released. */
        HEAD_REJECTED,

        /** A body broke its framing; nothing from the offending byte on is released. */
        BODY_REJECTED
    }

    /** Parse position in the stream; the flag tells whether the position lies inside a request head. */
    private enum State {
        METHOD(true),
        TARGET(true),
        VERSION(true),
        REQUEST_LINE_LF(true),
        LINE_START(true),
        FIELD_NAME(true),
        FIELD_VALUE(true),
        FIELD_VALUE_LF(true),
        HEAD_END_LF(true),
        FIXED_BODY(false),
        UNTIL_CLOSE(false),
        CHUNK_SIZE(false),
        CHUNK_SIZE_WHITESPACE(false),
        CHUNK_EXTENSION(false),
        CHUNK_SIZE_LF(false),
        CHUNK_DATA(false),
        CHUNK_DATA_CR(false),
        CHUNK_DATA_LF(false),
        TRAILER_LINE_START(false),
        TRAILER_NAME(false),
        TRAILER_VALUE(false),
        TRAILER_VALUE_LF(false),
        TRAILER_END_LF(false);

        final boolean head;

        State(boolean head) {
            this.head = head;
        }
    }

    /** Kind of the header line being read, for the two that frame the body. */
    private enum FieldKind { OTHER, CONTENT_LENGTH, TRANSFER_ENCODING }

    private static final byte CR = '\r';

    private static final byte LF = '\n';

    private static final byte SP = ' ';

    private static final byte HTAB = '\t';

    /** {@code HTTP/} followed by digit, {@code .}, digit; {@code 0} marks a digit position. */
    private static final byte[] VERSION_PATTERN = {'H', 'T', 'T', 'P', '/', 0, '.', 0};

    private static final String CONTENT_LENGTH = "content-length";

    private static final String TRANSFER_ENCODING = "transfer-encoding";

    private static final String IDENTITY = "identity";

    /** Largest chunk size accepted: 56 bits, well inside what Undertow's chunk reader stores exactly. */
    static final long MAX_CHUNK_SIZE = (1L << 56) - 1;

    /** Initial entries of the bounds array that records a head's fields: room for seven fields. */
    private static final int INITIAL_SENT_BOUNDS = 16;

    /**
     * Characters of field text kept allocated between heads; a larger buffer, grown for one large head, is released
     * once that head is recorded, so an idle connection does not keep it.
     */
    private static final int RETAINED_SENT_CHARS = 1024;

    /** RFC 9110 {@code tchar}: the characters of a method or a header name. */
    private static final boolean[] TOKEN = new boolean[256];

    static {
        String token = "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        for (int i = 0; i < token.length(); i++) {
            TOKEN[token.charAt(i)] = true;
        }
    }

    private final int maxHeadBytes;

    private State state = State.METHOD;

    private Outcome outcome = Outcome.ACCEPTING;

    /** Bytes consumed since the stream began. */
    private long consumed;

    /** Leading bytes of the stream cleared for Undertow. */
    private long released;

    /** Bytes of the current head consumed so far. */
    private int headBytes;

    /** Bytes of the method, target, header name or trailer name being read, or position in the version. */
    private int tokenLength;

    /** Whether a header line has been read whose value an obs-fold line may still continue. */
    private boolean fieldPending;

    private FieldKind fieldKind = FieldKind.OTHER;

    private boolean nameMayBeContentLength;

    private boolean nameMayBeTransferEncoding;

    /** Value of a {@code Content-Length} or {@code Transfer-Encoding} line, normalised as Undertow stores it. */
    private final StringBuilder fieldValue = new StringBuilder();

    /** Whether SP or HTAB was read after value bytes, so that one SP joins them to the next value byte. */
    private boolean spacePending;

    private int contentLengthCount;

    private int transferEncodingCount;

    /** The first {@code Content-Length} value, which Undertow frames the body by. */
    private String firstContentLength;

    /** The last {@code Transfer-Encoding} value, which Undertow frames the body by. */
    private String lastTransferEncoding;

    /** Body bytes left of a {@code Content-Length} body, or data bytes left of the current chunk. */
    private long remaining;

    private long chunkSize;

    private boolean chunkSizeHasDigit;

    /** Names and values of the current head's fields as sent, one after another. */
    private final StringBuilder sentFields = new StringBuilder();

    /** {@code 0}, then where each name and value ends in {@link #sentFields}, as {@link SentHeaderFields} keeps it. */
    private int[] sentBounds = new int[INITIAL_SENT_BOUNDS];

    /** Entries of {@link #sentBounds} in use. */
    private int sentBoundCount = 1;

    /** Where the value of the current field starts in {@link #sentFields}. */
    private int sentValueStart;

    /** Whether SP and HTAB are dropped from the current value: at its start and after an obs-fold. */
    private boolean dropSentWhitespace;

    /** Heads released and not yet handed over by {@link #pollReleasedHead()}, in stream order. */
    private final Deque<SentHeaderFields> releasedHeads = new ArrayDeque<>();

    /**
     * @param maxHeadBytes the most bytes a request head may hold, request line and final CRLF included
     * @throws IllegalArgumentException if {@code maxHeadBytes} is not positive
     */
    RequestFramingValidator(int maxHeadBytes) {
        if (maxHeadBytes <= 0) {
            throw new IllegalArgumentException("maxHeadBytes must be positive: " + maxHeadBytes);
        }
        this.maxHeadBytes = maxHeadBytes;
    }

    /**
     * Consumes {@code length} bytes of the stream from {@code bytes[offset]} on, the bytes that follow the ones
     * consumed before. Once the outcome is no longer {@link Outcome#ACCEPTING}, the rest is ignored.
     *
     * @param bytes  the array holding the bytes
     * @param offset index of the first byte
     * @param length number of bytes
     * @throws IndexOutOfBoundsException if the range lies outside {@code bytes}
     */
    void consume(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException("offset " + offset + ", length " + length + ", array " + bytes.length);
        }
        int index = offset;
        int end = offset + length;
        while (index < end && outcome == Outcome.ACCEPTING) {
            if (state == State.UNTIL_CLOSE) {
                pass(end - index);
                index = end;
            } else if (state == State.FIXED_BODY || state == State.CHUNK_DATA) {
                int count = (int) Math.min(remaining, end - index);
                pass(count);
                index += count;
                remaining -= count;
                if (remaining == 0) {
                    if (state == State.FIXED_BODY) {
                        startHead();
                    } else {
                        state = State.CHUNK_DATA_CR;
                    }
                }
            } else {
                boolean bodyByte = !state.head;
                consumed++;
                step(bytes[index++] & 0xFF);
                if (bodyByte && outcome == Outcome.ACCEPTING) {
                    released = consumed;
                }
            }
        }
    }

    /** @return what the stream has shown so far */
    Outcome outcome() {
        return outcome;
    }

    /** @return how many leading bytes of the stream Undertow may see; it never decreases */
    long releasedBytes() {
        return released;
    }

    /** @return how many bytes of the stream have been consumed */
    long consumedBytes() {
        return consumed;
    }

    /**
     * Hands over the header fields of the next head released, in stream order; each head is handed over once.
     *
     * @return the fields of the oldest released head not handed over yet, or {@code null} when there is none
     */
    SentHeaderFields pollReleasedHead() {
        return releasedHeads.poll();
    }

    /** Releases {@code count} body bytes that need no inspection. */
    private void pass(int count) {
        consumed += count;
        released = consumed;
    }

    private void step(int b) {
        if (state.head && ++headBytes > maxHeadBytes) {
            rejectHead();
            return;
        }
        switch (state) {
            case METHOD -> readMethod(b);
            case TARGET -> readTarget(b);
            case VERSION -> readVersion(b);
            case REQUEST_LINE_LF -> expectLf(b, State.LINE_START);
            case LINE_START -> readLineStart(b);
            case FIELD_NAME -> readFieldName(b);
            case FIELD_VALUE -> readFieldValue(b);
            case FIELD_VALUE_LF -> expectLf(b, State.LINE_START);
            case HEAD_END_LF -> {
                if (b == LF) {
                    completeHead();
                } else {
                    rejectHead();
                }
            }
            case CHUNK_SIZE -> readChunkSize(b);
            case CHUNK_SIZE_WHITESPACE -> {
                if (b == ';') {
                    state = State.CHUNK_EXTENSION;
                } else if (b != SP && b != HTAB) {
                    rejectBody();
                }
            }
            case CHUNK_EXTENSION -> {
                if (b == CR) {
                    state = State.CHUNK_SIZE_LF;
                } else if (!isValueByte(b)) {
                    rejectBody();
                }
            }
            case CHUNK_SIZE_LF -> {
                if (b != LF) {
                    rejectBody();
                } else if (chunkSize == 0) {
                    state = State.TRAILER_LINE_START;
                } else {
                    remaining = chunkSize;
                    state = State.CHUNK_DATA;
                }
            }
            case CHUNK_DATA_CR -> {
                if (b == CR) {
                    state = State.CHUNK_DATA_LF;
                } else {
                    rejectBody();
                }
            }
            case CHUNK_DATA_LF -> {
                if (b == LF) {
                    startChunk();
                } else {
                    rejectBody();
                }
            }
            case TRAILER_LINE_START -> {
                if (b == CR) {
                    state = State.TRAILER_END_LF;
                } else if (TOKEN[b]) {
                    state = State.TRAILER_NAME;
                } else {
                    rejectBody();
                }
            }
            case TRAILER_NAME -> {
                if (b == ':') {
                    state = State.TRAILER_VALUE;
                } else if (!TOKEN[b]) {
                    rejectBody();
                }
            }
            case TRAILER_VALUE -> {
                if (b == CR) {
                    state = State.TRAILER_VALUE_LF;
                } else if (!isValueByte(b)) {
                    rejectBody();
                }
            }
            case TRAILER_VALUE_LF -> {
                if (b == LF) {
                    state = State.TRAILER_LINE_START;
                } else {
                    rejectBody();
                }
            }
            case TRAILER_END_LF -> {
                if (b == LF) {
                    startHead();
                } else {
                    rejectBody();
                }
            }
            default -> throw new IllegalStateException("bulk state stepped byte by byte: " + state);
        }
    }

    private void readMethod(int b) {
        if (TOKEN[b]) {
            tokenLength++;
        } else if (b == SP && tokenLength > 0) {
            tokenLength = 0;
            state = State.TARGET;
        } else {
            rejectHead();
        }
    }

    private void readTarget(int b) {
        if (b == SP && tokenLength > 0) {
            tokenLength = 0;
            state = State.VERSION;
        } else if (b == SP || b == HTAB || b == CR || b == LF) {
            rejectHead();
        } else {
            tokenLength++;
        }
    }

    private void readVersion(int b) {
        if (tokenLength == VERSION_PATTERN.length) {
            if (b == CR) {
                state = State.REQUEST_LINE_LF;
            } else {
                rejectHead();
            }
            return;
        }
        byte expected = VERSION_PATTERN[tokenLength];
        if (expected == 0 ? isDigit(b) : b == expected) {
            tokenLength++;
        } else {
            rejectHead();
        }
    }

    private void readLineStart(int b) {
        if (b == SP || b == HTAB) {
            if (!fieldPending) {
                rejectHead();
                return;
            }
            // obs-fold: the line continues the previous value, joined by one SP as Undertow joins it.
            whitespace();
            foldSentValue();
            state = State.FIELD_VALUE;
            return;
        }
        finishField();
        if (b == CR) {
            state = State.HEAD_END_LF;
        } else if (TOKEN[b]) {
            tokenLength = 0;
            nameMayBeContentLength = true;
            nameMayBeTransferEncoding = true;
            matchName(b);
            sentFields.append((char) b);
            state = State.FIELD_NAME;
        } else {
            rejectHead();
        }
    }

    private void readFieldName(int b) {
        if (TOKEN[b]) {
            matchName(b);
            sentFields.append((char) b);
        } else if (b == ':') {
            addSentBound();
            sentValueStart = sentFields.length();
            dropSentWhitespace = true;
            if (nameMayBeContentLength && tokenLength == CONTENT_LENGTH.length()) {
                fieldKind = FieldKind.CONTENT_LENGTH;
            } else if (nameMayBeTransferEncoding && tokenLength == TRANSFER_ENCODING.length()) {
                fieldKind = FieldKind.TRANSFER_ENCODING;
            } else {
                fieldKind = FieldKind.OTHER;
            }
            fieldValue.setLength(0);
            spacePending = false;
            fieldPending = true;
            state = State.FIELD_VALUE;
        } else {
            rejectHead();
        }
    }

    private void readFieldValue(int b) {
        if (b == SP || b == HTAB) {
            whitespace();
            if (!dropSentWhitespace) {
                sentFields.append((char) b);
            }
        } else if (b == CR) {
            state = State.FIELD_VALUE_LF;
        } else if (isValueByte(b)) {
            if (fieldKind != FieldKind.OTHER) {
                if (spacePending) {
                    fieldValue.append(' ');
                }
                fieldValue.append((char) b);
            }
            spacePending = false;
            sentFields.append((char) b);
            dropSentWhitespace = false;
        } else {
            rejectHead();
        }
    }

    /** SP or HTAB inside a value, or the start of an obs-fold line. */
    private void whitespace() {
        if (fieldValue.length() > 0) {
            spacePending = true;
        }
    }

    /** Compares the next header-name byte, ignoring ASCII case, with the two names that frame the body. */
    private void matchName(int b) {
        int lower = b >= 'A' && b <= 'Z' ? b + ('a' - 'A') : b;
        nameMayBeContentLength &= tokenLength < CONTENT_LENGTH.length()
                && CONTENT_LENGTH.charAt(tokenLength) == lower;
        nameMayBeTransferEncoding &= tokenLength < TRANSFER_ENCODING.length()
                && TRANSFER_ENCODING.charAt(tokenLength) == lower;
        tokenLength++;
    }

    /** Records the header line just ended, now that no obs-fold line can continue it. */
    private void finishField() {
        if (!fieldPending) {
            return;
        }
        fieldPending = false;
        trimSentValue();
        addSentBound();
        if (fieldKind == FieldKind.CONTENT_LENGTH) {
            if (contentLengthCount++ == 0) {
                firstContentLength = fieldValue.toString();
            }
        } else if (fieldKind == FieldKind.TRANSFER_ENCODING) {
            transferEncodingCount++;
            lastTransferEncoding = fieldValue.toString();
        }
    }

    private void completeHead() {
        if (transferEncodingCount > 1 || contentLengthCount > 1
                || (transferEncodingCount > 0 && contentLengthCount > 0)) {
            rejectHead();
            return;
        }
        if (transferEncodingCount == 1) {
            releaseHead();
            if (isIdentity(lastTransferEncoding)) {
                state = State.UNTIL_CLOSE;
            } else {
                startChunk();
            }
            return;
        }
        if (contentLengthCount == 1) {
            long length = parseContentLength(firstContentLength);
            if (length < 0) {
                rejectHead();
                return;
            }
            releaseHead();
            if (length == 0) {
                startHead();
            } else {
                remaining = length;
                state = State.FIXED_BODY;
            }
            return;
        }
        releaseHead();
        startHead();
    }

    /** Releases the head just completed and records its fields as sent for {@link #pollReleasedHead()}. */
    private void releaseHead() {
        released = consumed;
        releasedHeads.add(sentBoundCount == 1
                ? SentHeaderFields.NONE
                : new SentHeaderFields(sentFields.toString(), Arrays.copyOf(sentBounds, sentBoundCount)));
        sentFields.setLength(0);
        if (sentFields.capacity() > RETAINED_SENT_CHARS) {
            sentFields.trimToSize();
        }
        if (sentBounds.length > INITIAL_SENT_BOUNDS) {
            sentBounds = new int[INITIAL_SENT_BOUNDS];
        }
        sentBoundCount = 1;
    }

    /**
     * An obs-fold in the current value: the whitespace before it, the fold and the whitespace after it become one SP,
     * unless nothing but whitespace precedes it in the value, in which case they are leading whitespace and dropped.
     */
    private void foldSentValue() {
        trimSentValue();
        if (sentFields.length() > sentValueStart) {
            sentFields.append(' ');
        }
        dropSentWhitespace = true;
    }

    /** Drops the SP and HTAB that end the current value. */
    private void trimSentValue() {
        int end = sentFields.length();
        while (end > sentValueStart && (sentFields.charAt(end - 1) == SP || sentFields.charAt(end - 1) == HTAB)) {
            end--;
        }
        sentFields.setLength(end);
    }

    /** Records the end of the name or value just read. */
    private void addSentBound() {
        if (sentBoundCount == sentBounds.length) {
            sentBounds = Arrays.copyOf(sentBounds, 2 * sentBounds.length);
        }
        sentBounds[sentBoundCount++] = sentFields.length();
    }

    private void readChunkSize(int b) {
        int digit = Character.digit(b, 16);
        if (digit >= 0 && b < 0x80) {
            if (chunkSize > MAX_CHUNK_SIZE >>> 4) {
                rejectBody();
                return;
            }
            chunkSize = (chunkSize << 4) | digit;
            chunkSizeHasDigit = true;
        } else if (!chunkSizeHasDigit) {
            rejectBody();
        } else if (b == CR) {
            state = State.CHUNK_SIZE_LF;
        } else if (b == ';') {
            state = State.CHUNK_EXTENSION;
        } else if (b == SP || b == HTAB) {
            state = State.CHUNK_SIZE_WHITESPACE;
        } else {
            rejectBody();
        }
    }

    private void expectLf(int b, State next) {
        if (b == LF) {
            state = next;
        } else {
            rejectHead();
        }
    }

    private void startHead() {
        state = State.METHOD;
        headBytes = 0;
        tokenLength = 0;
        fieldPending = false;
        fieldKind = FieldKind.OTHER;
        fieldValue.setLength(0);
        spacePending = false;
        contentLengthCount = 0;
        transferEncodingCount = 0;
        firstContentLength = null;
        lastTransferEncoding = null;
    }

    private void startChunk() {
        state = State.CHUNK_SIZE;
        chunkSize = 0;
        chunkSizeHasDigit = false;
    }

    private void rejectHead() {
        outcome = Outcome.HEAD_REJECTED;
    }

    private void rejectBody() {
        outcome = Outcome.BODY_REJECTED;
    }

    /** SP, HTAB, visible ASCII or obs-text: the bytes a header value, trailer value or chunk extension may hold. */
    private static boolean isValueByte(int b) {
        return b == SP || b == HTAB || (b >= 0x21 && b <= 0x7E) || b >= 0x80;
    }

    private static boolean isDigit(int b) {
        return b >= '0' && b <= '9';
    }

    /** {@code identity} ignoring ASCII case only, as Undertow compares the coding. */
    private static boolean isIdentity(String value) {
        if (value.length() != IDENTITY.length()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            char lower = c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
            if (lower != IDENTITY.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** @return the value of one or more decimal digits, or {@code -1} if it is anything else or exceeds {@code long} */
    private static long parseContentLength(String value) {
        if (value.isEmpty()) {
            return -1;
        }
        long length = 0;
        for (int i = 0; i < value.length(); i++) {
            int digit = value.charAt(i) - '0';
            if (digit < 0 || digit > 9 || length > (Long.MAX_VALUE - digit) / 10) {
                return -1;
            }
            length = length * 10 + digit;
        }
        return length;
    }
}
