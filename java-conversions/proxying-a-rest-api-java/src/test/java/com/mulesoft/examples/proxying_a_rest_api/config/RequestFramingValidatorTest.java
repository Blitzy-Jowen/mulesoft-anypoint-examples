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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.mulesoft.examples.proxying_a_rest_api.config.RequestFramingValidator.Outcome;

/**
 * Tests {@link RequestFramingValidator} on byte streams, one parameterized case per shape: well-formed heads are held
 * until their last byte and then released whole; malformed heads are refused with nothing of them released; body
 * framing releases exactly the bytes Undertow reads as the body, so the next head starts where Undertow's does;
 * malformed bodies stop the stream at the offending byte; the head limit; the same result whatever fragments the
 * bytes arrive in; and the header fields recorded as sent for every released head, in stream order, never for a
 * refused head or a trailer. Streams are written as ISO-8859-1 strings, one byte per character.
 */
class RequestFramingValidatorTest {

    /** Head limit for every case except the limit cases. */
    private static final int LIMIT = 8192;

    /** Scenario, then a complete request head without body. */
    private static Stream<Arguments> wellFormedHeads() {
        return Stream.of(
                Arguments.of("request line only", "GET / HTTP/1.1\r\n\r\n"),
                Arguments.of("HTTP/1.0", "GET /x HTTP/1.0\r\nHost: h\r\n\r\n"),
                Arguments.of("token method", "PROPFIND /dav HTTP/1.1\r\nHost: h\r\n\r\n"),
                Arguments.of("asterisk form", "OPTIONS * HTTP/1.1\r\nHost: h\r\n\r\n"),
                Arguments.of("authority form", "CONNECT h:443 HTTP/1.1\r\nHost: h:443\r\n\r\n"),
                Arguments.of("absolute form", "GET http://u:p@h:8080/x?q HTTP/1.1\r\nHost: h\r\n\r\n"),
                Arguments.of("raw target bytes left to the listener",
                        "GET /a|b{c}\"<>`\\%zz\u0000\u00e9 HTTP/1.1\r\nHost: h\r\n\r\n"),
                Arguments.of("HTAB, SP runs and obs-text in a value",
                        "GET / HTTP/1.1\r\nX-A: \ta  b\t\u00e9\u00ff \r\n\r\n"),
                Arguments.of("empty value", "GET / HTTP/1.1\r\nX-Empty:\r\n\r\n"),
                Arguments.of("obs-fold", "GET / HTTP/1.1\r\nX-A: a\r\n \tb\r\n\tc\r\nX-B: d\r\n\r\n"),
                Arguments.of("obs-fold line of whitespace only", "GET / HTTP/1.1\r\nX-A: a\r\n \r\n\r\n"),
                Arguments.of("every token character in a name",
                        "GET / HTTP/1.1\r\n!#$%&'*+-.^_`|~09AZaz: v\r\n\r\n"),
                Arguments.of("Content-Length 0", "POST / HTTP/1.1\r\nContent-Length: 0\r\n\r\n"),
                Arguments.of("Content-Length 0 with surrounding whitespace",
                        "POST / HTTP/1.1\r\nContent-Length: \t 0 \t\r\n\r\n"),
                Arguments.of("framing names inside other names",
                        "POST / HTTP/1.1\r\nX-Content-Length: 5\r\nContent-Lengths: 5\r\n"
                                + "Transfer-Encodings: x\r\n\r\n"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("wellFormedHeads")
    void wellFormedHeadIsHeldUntilItsLastByteAndThenReleasedWhole(String scenario, String head) {
        RequestFramingValidator validator = new RequestFramingValidator(LIMIT);
        byte[] bytes = head.getBytes(ISO_8859_1);

        validator.consume(bytes, 0, bytes.length - 1);

        assertThat(validator.outcome()).as(scenario).isEqualTo(Outcome.ACCEPTING);
        assertThat(validator.releasedBytes()).as(scenario).isZero();

        validator.consume(bytes, bytes.length - 1, 1);

        assertThat(validator.outcome()).as(scenario).isEqualTo(Outcome.ACCEPTING);
        assertThat(validator.releasedBytes()).as(scenario).isEqualTo(bytes.length);
        assertThat(validator.consumedBytes()).as(scenario).isEqualTo(bytes.length);
    }

    /** Scenario, the well-formed messages before the malformed head, then the malformed head and what follows. */
    private static Stream<Arguments> malformedHeads() {
        String ok = "GET /ok HTTP/1.1\r\nHost: h\r\n\r\n";
        return Stream.of(
                Arguments.of("bare CR in a value", "", "GET / HTTP/1.1\r\nX-A: a\rAuthorization: x\r\n\r\n"),
                Arguments.of("bare LF in a value", "", "GET / HTTP/1.1\r\nX-A: a\nX-B: b\r\n\r\n"),
                Arguments.of("NUL in a value", "", "GET / HTTP/1.1\r\nX-A: a\u0000b\r\n\r\n"),
                Arguments.of("VT in a value", "", "GET / HTTP/1.1\r\nX-A: a\u000bb\r\n\r\n"),
                Arguments.of("FF in a value", "", "GET / HTTP/1.1\r\nX-A: a\u000cb\r\n\r\n"),
                Arguments.of("ESC in a value", "", "GET / HTTP/1.1\r\nX-A: a\u001bb\r\n\r\n"),
                Arguments.of("DEL in a value", "", "GET / HTTP/1.1\r\nX-A: a\u007fb\r\n\r\n"),
                Arguments.of("bare CR at the start of a header line", "", "GET / HTTP/1.1\r\n\rX-A: a\r\n\r\n"),
                Arguments.of("CR CR before the final LF", "", "GET / HTTP/1.1\r\nX-A: a\r\n\r\r\n"),
                Arguments.of("LF LF ending the head", "", "GET / HTTP/1.1\r\nX-A: a\r\n\n"),
                Arguments.of("bare LF ending the request line", "", "GET / HTTP/1.1\nX-A: a\r\n\r\n"),
                Arguments.of("bare CR in the request line", "", "GET /a\rb HTTP/1.1\r\n\r\n"),
                Arguments.of("whitespace before a colon", "", "GET / HTTP/1.1\r\nAuthorization : x\r\n\r\n"),
                Arguments.of("HTAB before a colon", "", "GET / HTTP/1.1\r\nAuthorization\t: x\r\n\r\n"),
                Arguments.of("empty header name", "", "GET / HTTP/1.1\r\n: x\r\n\r\n"),
                Arguments.of("header line without a colon", "", "GET / HTTP/1.1\r\nX-A\r\n\r\n"),
                Arguments.of("non-token character in a name", "", "GET / HTTP/1.1\r\nX(A): x\r\n\r\n"),
                Arguments.of("obs-fold on the first header line", "", "GET / HTTP/1.1\r\n X-A: a\r\n\r\n"),
                Arguments.of("empty line before the request line", "", "\r\nGET / HTTP/1.1\r\n\r\n"),
                Arguments.of("empty request line", "", "\r\n\r\n"),
                Arguments.of("non-token character in the method", "", "G(T / HTTP/1.1\r\n\r\n"),
                Arguments.of("two SPs after the method", "", "GET  / HTTP/1.1\r\n\r\n"),
                Arguments.of("HTAB in the target", "", "GET /a\tb HTTP/1.1\r\n\r\n"),
                Arguments.of("two SPs before the version", "", "GET /  HTTP/1.1\r\n\r\n"),
                Arguments.of("no version", "", "GET /\r\n\r\n"),
                Arguments.of("lower-case version", "", "GET / http/1.1\r\n\r\n"),
                Arguments.of("three-digit version", "", "GET / HTTP/1.10\r\n\r\n"),
                Arguments.of("letter in the version", "", "GET / HTTP/1.x\r\n\r\n"),
                Arguments.of("Transfer-Encoding with Content-Length", "",
                        "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\nContent-Length: 3\r\n\r\nabc"),
                Arguments.of("Transfer-Encoding identity with Content-Length", "",
                        "POST / HTTP/1.1\r\nContent-Length: 3\r\nTransfer-Encoding: identity\r\n\r\nabc"),
                Arguments.of("two Content-Length values", "",
                        "POST / HTTP/1.1\r\nContent-Length: 3\r\ncontent-length: 3\r\n\r\nabc"),
                Arguments.of("two Transfer-Encoding values", "",
                        "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\nTRANSFER-ENCODING: chunked\r\n\r\n0\r\n\r\n"),
                Arguments.of("Content-Length list", "", "POST / HTTP/1.1\r\nContent-Length: 3, 3\r\n\r\nabc"),
                Arguments.of("Content-Length with inner space", "", "POST / HTTP/1.1\r\nContent-Length: 1 2\r\n\r\n"),
                Arguments.of("Content-Length folded into two numbers", "",
                        "POST / HTTP/1.1\r\nContent-Length: 1\r\n 2\r\n\r\n"),
                Arguments.of("signed Content-Length", "", "POST / HTTP/1.1\r\nContent-Length: +3\r\n\r\nabc"),
                Arguments.of("negative Content-Length", "", "POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n"),
                Arguments.of("empty Content-Length", "", "POST / HTTP/1.1\r\nContent-Length:\r\n\r\n"),
                Arguments.of("Content-Length beyond long", "",
                        "POST / HTTP/1.1\r\nContent-Length: 9223372036854775808\r\n\r\n"),
                Arguments.of("malformed head after a request without a body", ok,
                        "GET / HTTP/1.1\r\nX-A: a\rB: b\r\n\r\n"),
                Arguments.of("malformed head after a Content-Length body",
                        "POST /ok HTTP/1.1\r\nContent-Length: 4\r\n\r\n\r\r\n\n",
                        "GET / HTTP/1.1\r\nX-A: a\rB: b\r\n\r\n"),
                Arguments.of("malformed head after a chunked body",
                        "POST /ok HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n",
                        "GET / HTTP/1.1\r\nX-A: a\rB: b\r\n\r\n"));
    }

    // The name leaves out the bytes, whose control characters the test reports cannot carry.
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("malformedHeads")
    void malformedHeadIsRefusedWithNothingOfItReleased(String scenario, String before, String malformed) {
        RequestFramingValidator validator = new RequestFramingValidator(LIMIT);

        consume(validator, before + malformed);

        assertThat(validator.outcome()).as(scenario).isEqualTo(Outcome.HEAD_REJECTED);
        assertThat(validator.releasedBytes()).as(scenario).isEqualTo(before.length());
        // Only the well-formed message before it is recorded; the refused head never is.
        assertThat(releasedHeads(validator)).as(scenario).hasSize(before.isEmpty() ? 0 : 1);
    }

    /** Scenario, a complete request head without body, then its header fields as sent: name, value, name, ... */
    private static Stream<Arguments> sentFields() {
        return Stream.of(
                Arguments.of("no header field", "GET / HTTP/1.1\r\n\r\n", List.of()),
                Arguments.of("inner SP and HTAB runs kept", "GET / HTTP/1.1\r\nX-A: a\tb\r\nX-B: a  \t b\r\n"
                        + "Authorization: Digest username=\"John  Smith\", realm=\"r\"\r\n\r\n",
                        List.of("X-A", "a\tb", "X-B", "a  \t b",
                                "Authorization", "Digest username=\"John  Smith\", realm=\"r\"")),
                Arguments.of("leading and trailing SP and HTAB dropped", "GET / HTTP/1.1\r\nX-A: \t a b \t\r\n\r\n",
                        List.of("X-A", "a b")),
                Arguments.of("empty and whitespace-only values", "GET / HTTP/1.1\r\nX-Empty:\r\nX-Ws: \t \r\n\r\n",
                        List.of("X-Empty", "", "X-Ws", "")),
                Arguments.of("obs-fold and the whitespace around it become one SP",
                        "GET / HTTP/1.1\r\nX-A: a \t\r\n \tb\r\n\r\n", List.of("X-A", "a b")),
                Arguments.of("obs-fold keeps the runs inside each line",
                        "GET / HTTP/1.1\r\nX-A: a  b\r\n\tc\t\td\r\n\r\n", List.of("X-A", "a  b c\t\td")),
                Arguments.of("obs-fold after an empty first line", "GET / HTTP/1.1\r\nX-A:\r\n  b\r\n\r\n",
                        List.of("X-A", "b")),
                Arguments.of("whitespace-only line between two obs-folds",
                        "GET / HTTP/1.1\r\nX-A: a\r\n \r\n b\r\n\r\n", List.of("X-A", "a b")),
                Arguments.of("whitespace-only obs-fold line at the end",
                        "GET / HTTP/1.1\r\nX-A: a\r\n \r\nX-B: b\r\n\r\n", List.of("X-A", "a", "X-B", "b")),
                Arguments.of("names as sent, repeated names in order",
                        "GET / HTTP/1.1\r\nauthorization: Bearer t1\r\nCookie: a=1\r\ncookie: b=2\r\n\r\n",
                        List.of("authorization", "Bearer t1", "Cookie", "a=1", "cookie", "b=2")),
                Arguments.of("obs-text kept", "GET / HTTP/1.1\r\nX-A: \u00e9\t\u00ff\r\n\r\n",
                        List.of("X-A", "\u00e9\t\u00ff")),
                Arguments.of("framing fields recorded like any other",
                        "POST / HTTP/1.1\r\nContent-Length:  0 \r\nX-A: a\r\n\r\n",
                        List.of("Content-Length", "0", "X-A", "a")));
    }

    // The name leaves out the bytes, whose tabs and line ends the test reports cannot carry.
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("sentFields")
    void releasedHeadRecordsItsFieldsAsSent(String scenario, String head, List<String> fields) {
        RequestFramingValidator validator = new RequestFramingValidator(LIMIT);
        byte[] bytes = head.getBytes(ISO_8859_1);

        validator.consume(bytes, 0, bytes.length - 1);

        assertThat(validator.pollReleasedHead()).as("%s: recorded before its last byte", scenario).isNull();

        validator.consume(bytes, bytes.length - 1, 1);

        assertThat(validator.outcome()).as(scenario).isEqualTo(Outcome.ACCEPTING);
        assertThat(releasedHeads(validator)).as(scenario).containsExactly(fields);
    }

    /**
     * Fragment size of a stream of three pipelined heads, with a chunked body and its trailer, a {@code Content-Length}
     * body and no body, followed by a refused head.
     */
    @ParameterizedTest(name = "fragments of {0} bytes")
    @ValueSource(ints = {1, 2, 3, 7, 64, 4096})
    void pipelinedHeadsAreRecordedInStreamOrderWithoutTrailersOrTheRefusedHead(int fragmentBytes) {
        String stream = "POST /a HTTP/1.1\r\nTransfer-Encoding: chunked\r\nX-A: a\t1\r\n\r\n"
                + "3\r\nabc\r\n0\r\nX-Trailer: t\r\n\r\n"
                + "PUT /b HTTP/1.1\r\nContent-Length: 3\r\nX-B: b  2\r\n\r\nxyz"
                + "GET /c HTTP/1.1\r\n\r\n"
                + "GET /d HTTP/1.1\r\nX-D: d\rX: x\r\n\r\n";
        RequestFramingValidator validator = new RequestFramingValidator(LIMIT);
        byte[] bytes = stream.getBytes(ISO_8859_1);
        List<List<String>> heads = new ArrayList<>();

        for (int start = 0; start < bytes.length; start += fragmentBytes) {
            validator.consume(bytes, start, Math.min(fragmentBytes, bytes.length - start));
            heads.addAll(releasedHeads(validator));
        }

        assertThat(validator.outcome()).isEqualTo(Outcome.HEAD_REJECTED);
        assertThat(heads).containsExactly(
                List.of("Transfer-Encoding", "chunked", "X-A", "a\t1"),
                List.of("Content-Length", "3", "X-B", "b  2"),
                List.of());
    }

    /** A head of exactly the limit is recorded whole. */
    @Test
    void headOfTheLimitIsRecordedWhole() {
        int limit = 64;
        String start = "GET / HTTP/1.1\r\nX-Pad: ";
        String value = "a\t".repeat((limit - start.length() - 4) / 2);
        String head = start + value + "a".repeat(limit - start.length() - 4 - value.length()) + "\r\n\r\n";
        RequestFramingValidator validator = new RequestFramingValidator(limit);

        consume(validator, head);

        assertThat(head).hasSize(limit);
        assertThat(releasedHeads(validator))
                .containsExactly(List.of("X-Pad", head.substring(start.length(), head.length() - 4)));
    }

    /**
     * Scenario, the bytes released (whole messages and the body bytes read so far), then the bytes held back (the
     * start of the next head).
     */
    private static Stream<Arguments> framedStreams() {
        String next = "GET /next HTTP/1.1\r\nHost";
        return Stream.of(
                Arguments.of("no framing header: no body", "GET / HTTP/1.1\r\n\r\n", next),
                Arguments.of("Content-Length body that looks like a malformed head",
                        "POST / HTTP/1.1\r\nContent-Length: 6\r\n\r\n\r\r\u0000\n\n\r", next),
                Arguments.of("Content-Length with leading zeros", "POST / HTTP/1.1\r\nContent-Length: 003\r\n\r\nabc",
                        next),
                Arguments.of("Content-Length name in another case",
                        "POST / HTTP/1.1\r\ncontent-LENGTH:  3 \r\n\r\nabc", next),
                Arguments.of("Content-Length folded",
                        "POST / HTTP/1.1\r\nContent-Length:\r\n 3\r\n\r\nabc", next),
                Arguments.of("partial Content-Length body", "POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc", ""),
                Arguments.of("chunked",
                        "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n", next),
                Arguments.of("chunked with upper-case hex, extensions and trailers",
                        "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"
                                + "A;name=\"v a\"\r\n0123456789\r\n00b \t;x\r\n\r\n\r\r\n\n\u0000\u00ffabc\r\n"
                                + "0\r\nX-T: a\r\nY: \u00e9\r\n\r\n", next),
                Arguments.of("chunked named in another case", "POST / HTTP/1.1\r\nTransfer-Encoding: Chunked\r\n\r\n"
                        + "1\r\na\r\n0\r\n\r\n", next),
                Arguments.of("any coding but identity is read as chunked",
                        "POST / HTTP/1.1\r\nTransfer-Encoding: gzip, chunked\r\n\r\n1\r\na\r\n0\r\n\r\n", next),
                Arguments.of("empty Transfer-Encoding is read as chunked",
                        "POST / HTTP/1.1\r\nTransfer-Encoding:\r\n\r\n0\r\n\r\n", next),
                Arguments.of("partial chunked body", "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nab",
                        ""),
                Arguments.of("identity: everything to the end of the connection is body",
                        "POST / HTTP/1.1\r\nTransfer-Encoding: IDENTITY \r\n\r\nabc\r\r\u0000\nGET / HTTP/1.1\r\n", ""),
                Arguments.of("pipelined heads", "GET /1 HTTP/1.1\r\n\r\nGET /2 HTTP/1.1\r\nX-A: a\r\n\r\n", next));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("framedStreams")
    void bodyFramingReleasesExactlyTheBodyAndHoldsTheNextHead(String scenario, String released, String held) {
        RequestFramingValidator validator = new RequestFramingValidator(LIMIT);

        consume(validator, released + held);

        assertThat(validator.outcome()).as(scenario).isEqualTo(Outcome.ACCEPTING);
        assertThat(validator.releasedBytes()).as(scenario).isEqualTo(released.length());
        assertThat(validator.consumedBytes()).as(scenario).isEqualTo(released.length() + held.length());
    }

    /** Scenario, the bytes released up to the violation, then the offending byte and what follows. */
    private static Stream<Arguments> malformedBodies() {
        String chunked = "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n";
        return Stream.of(
                Arguments.of("no chunk-size digit", chunked, "\r\n"),
                Arguments.of("non-hex chunk size", chunked + "5", "z\r\nhello\r\n0\r\n\r\n"),
                Arguments.of("bare LF after the chunk size", chunked + "5", "\nhello\r\n0\r\n\r\n"),
                Arguments.of("whitespace after the size without an extension", chunked + "5 ", "\r\nhello\r\n"),
                Arguments.of("control character in an extension", chunked + "5;a", "\u0001\r\nhello\r\n"),
                Arguments.of("CR without LF after an extension", chunked + "5;a\r", "hello\r\n"),
                Arguments.of("chunk data longer than its size", chunked + "3\r\nabc", "d\r\n0\r\n\r\n"),
                Arguments.of("bare LF after chunk data", chunked + "3\r\nabc", "\n0\r\n\r\n"),
                Arguments.of("chunk size beyond 56 bits", chunked + "ffffffffffffff", "f\r\n"),
                Arguments.of("obs-fold in a trailer", chunked + "0\r\nX: a\r\n", " b\r\n\r\n"),
                Arguments.of("trailer without a colon", chunked + "0\r\nX", "\r\n\r\n"),
                Arguments.of("whitespace before a trailer colon", chunked + "0\r\nX", " : a\r\n\r\n"),
                Arguments.of("CR without LF in a trailer", chunked + "0\r\nX: a\r", "Y: b\r\n\r\n"),
                Arguments.of("CR CR ending the trailers", chunked + "0\r\n\r", "\r\n"));
    }

    // The name leaves out the bytes, whose control characters the test reports cannot carry.
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("malformedBodies")
    void malformedBodyStopsTheStreamAtTheOffendingByte(String scenario, String released, String offending) {
        RequestFramingValidator validator = new RequestFramingValidator(LIMIT);

        consume(validator, released + offending);

        assertThat(validator.outcome()).as(scenario).isEqualTo(Outcome.BODY_REJECTED);
        assertThat(validator.releasedBytes()).as(scenario).isEqualTo(released.length());
    }

    @Test
    void largestChunkSizeIsAccepted() {
        RequestFramingValidator validator = new RequestFramingValidator(LIMIT);
        String stream = "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n00ffffffffffffff\r\nabc";

        consume(validator, stream);

        assertThat(Long.parseLong("00ffffffffffffff", 16)).isEqualTo(RequestFramingValidator.MAX_CHUNK_SIZE);
        assertThat(validator.outcome()).isEqualTo(Outcome.ACCEPTING);
        assertThat(validator.releasedBytes()).isEqualTo(stream.length());
    }

    /** A head of exactly the limit passes, one byte more is refused, as Undertow counts its own limit. */
    @ParameterizedTest(name = "head of limit {0}: {1}")
    @CsvSource({"0, ACCEPTING", "1, HEAD_REJECTED"})
    void headLimitCountsEveryByteOfTheHead(int bytesOverLimit, Outcome outcome) {
        int limit = 64;
        String before = "POST / HTTP/1.1\r\nContent-Length: 2\r\n\r\nab";
        String start = "GET / HTTP/1.1\r\nX-Pad: ";
        String head = start + "a".repeat(limit + bytesOverLimit - start.length() - 4) + "\r\n\r\n";
        RequestFramingValidator validator = new RequestFramingValidator(limit);

        consume(validator, before + head);

        assertThat(head).hasSize(limit + bytesOverLimit);
        assertThat(validator.outcome()).isEqualTo(outcome);
        assertThat(validator.releasedBytes())
                .isEqualTo(before.length() + (outcome == Outcome.ACCEPTING ? head.length() : 0));
    }

    /** Stream, then fragment size: the outcome and release point do not depend on how the bytes are split. */
    private static Stream<Arguments> fragmentedStreams() {
        String stream = "POST /a HTTP/1.1\r\nTransfer-Encoding: chunked\r\nX-A: a\r\n b\r\n\r\n"
                + "4;e=1\r\nab\r\n\r\n0\r\nT: t\r\n\r\n"
                + "PUT /b HTTP/1.1\r\nContent-Length: 3\r\n\r\nxyz"
                + "GET /c HTTP/1.1\r\nX-B: b\rX-C: c\r\n\r\n";
        return Stream.of(1, 2, 3, 7, 64).map(size -> Arguments.of(stream, size));
    }

    @ParameterizedTest(name = "fragments of {1} bytes")
    @MethodSource("fragmentedStreams")
    void fragmentsGiveTheSameResultAsTheWholeStream(String stream, int fragmentBytes) {
        RequestFramingValidator whole = new RequestFramingValidator(LIMIT);
        consume(whole, stream);
        RequestFramingValidator fragmented = new RequestFramingValidator(LIMIT);
        byte[] bytes = stream.getBytes(ISO_8859_1);

        for (int start = 0; start < bytes.length; start += fragmentBytes) {
            fragmented.consume(bytes, start, Math.min(fragmentBytes, bytes.length - start));
        }

        assertThat(whole.outcome()).isEqualTo(Outcome.HEAD_REJECTED);
        assertThat(whole.releasedBytes()).isEqualTo(stream.indexOf("GET /c"));
        assertThat(fragmented.outcome()).isEqualTo(whole.outcome());
        assertThat(fragmented.releasedBytes()).isEqualTo(whole.releasedBytes());
    }

    @Test
    void bytesAfterARejectionAreIgnored() {
        RequestFramingValidator validator = new RequestFramingValidator(LIMIT);
        consume(validator, "GET / HTTP/1.1\r\nX: a\rb");
        long consumed = validator.consumedBytes();

        consume(validator, "\r\n\r\nGET / HTTP/1.1\r\n\r\n");

        assertThat(validator.outcome()).isEqualTo(Outcome.HEAD_REJECTED);
        assertThat(validator.consumedBytes()).isEqualTo(consumed);
        assertThat(validator.releasedBytes()).isZero();
    }

    @Test
    void invalidArgumentsAreRefused() {
        RequestFramingValidator validator = new RequestFramingValidator(LIMIT);

        assertThatThrownBy(() -> new RequestFramingValidator(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.consume(new byte[4], 3, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> validator.consume(new byte[4], -1, 1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> validator.consume(new byte[4], 0, -1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    private static void consume(RequestFramingValidator validator, String stream) {
        byte[] bytes = stream.getBytes(ISO_8859_1);
        validator.consume(bytes, 0, bytes.length);
    }

    /** Polls every head recorded so far, each as its fields: name, value, name, value, ... */
    private static List<List<String>> releasedHeads(RequestFramingValidator validator) {
        List<List<String>> heads = new ArrayList<>();
        for (SentHeaderFields head = validator.pollReleasedHead(); head != null; head = validator.pollReleasedHead()) {
            heads.add(fields(head));
        }
        return heads;
    }

    /** @return the fields of {@code head}: name, value, name, value, ... */
    static List<String> fields(SentHeaderFields head) {
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < head.size(); i++) {
            fields.add(head.name(i));
            fields.add(head.value(i));
        }
        return fields;
    }
}
