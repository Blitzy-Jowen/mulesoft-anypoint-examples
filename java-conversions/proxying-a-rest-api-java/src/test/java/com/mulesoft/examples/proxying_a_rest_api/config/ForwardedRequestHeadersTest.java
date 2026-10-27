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

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests {@link ForwardedRequestHeaders} without a server: the substitution rule (a sent value replaces the parsed one
 * only when it normalises to it as Undertow 2.3.17 normalises), the normalisation itself, and the headers built from
 * a request and the fields recorded for its head, which keep every name and value position of the request. The
 * recorded fields come from {@link RequestFramingValidator}, as on the listener.
 */
class ForwardedRequestHeadersTest {

    /** Value Undertow parsed, value as sent ({@code NONE} for no recorded field), value forwarded. */
    // The name leaves out the values, whose tabs the test reports cannot carry.
    @ParameterizedTest(name = "[{index}]")
    @CsvSource(delimiter = '|', nullValues = "NONE", value = {
            "a b | a\tb | a\tb",
            "a b | a\t\tb | a\t\tb",
            "a b | a \tb | a \tb",
            "a b | a\t b | a\t b",
            "a b | 'a    b' | 'a    b'",
            "'Digest username=\"John Smith\", realm=\"r\"' | 'Digest username=\"John  Smith\", realm=\"r\"'"
                    + " | 'Digest username=\"John  Smith\", realm=\"r\"'",
            "'Digest username=\"John Smith\", realm=\"r\"' | 'Digest username=\"John\tSmith\", realm=\"r\"'"
                    + " | 'Digest username=\"John\tSmith\", realm=\"r\"'",
            "Bearer t1 | 'Bearer   t1' | 'Bearer   t1'",
            "a b | a b | a b",
            // Undertow joined two Cookie lines: the first line's value does not describe the joined one.
            "a=1; b=2 | a=1 | a=1; b=2",
            // A value that does not normalise to the parsed one is never forwarded in its place.
            "ab | a b | ab",
            "a b | a\tc | a b",
            "a b | NONE | a b"})
    void sentValueReplacesTheParsedOneOnlyWhenItNormalisesToIt(String parsed, String sent, String forwarded) {
        assertThat(ForwardedRequestHeaders.forwardedValue(parsed, sent)).isEqualTo(forwarded);
    }

    /** Value, then the value Undertow 2.3.17 stores for it. */
    @ParameterizedTest(name = "[{index}] -> {1}")
    @CsvSource(delimiter = '|', value = {
            "'' | ''",
            "' \t ' | ''",
            "a | a",
            "' \ta  \t b\t ' | a b",
            "a\tb\t\tc | a b c",
            "'\u00e9  \u00ff' | \u00e9 \u00ff"})
    void normaliseDropsOuterWhitespaceAndJoinsEveryInnerRunWithOneSpace(String value, String normalised) {
        assertThat(ForwardedRequestHeaders.normalise(value)).isEqualTo(normalised);
    }

    @Test
    void recordedValuesReplaceParsedOnesPositionByPositionAndNoHeaderIsAddedOrDropped() {
        SentHeaderFields sent = sentHead("GET / HTTP/1.1\r\n"
                + "authorization: Digest username=\"John  Smith\"\r\n"
                + "X-Multi: p\tq\r\n"
                + "Cookie: a=1\r\n"
                + "X-Multi: r  s\r\n"
                + "Cookie: b=2\r\n"
                + "X-Drift: x\ty\r\n\r\n");
        MockHttpServletRequest request = new MockHttpServletRequest();
        // The values as Undertow parses the head above, plus one value and one header it does not hold.
        request.addHeader("Authorization", "Digest username=\"John Smith\"");
        request.addHeader("X-Multi", "p q");
        request.addHeader("X-Multi", "r s");
        request.addHeader("X-Multi", "t u");
        request.addHeader("Cookie", "a=1; b=2");
        request.addHeader("X-Drift", "x z");
        request.addHeader("X-Added", "v  w");

        HttpHeaders headers = ForwardedRequestHeaders.from(request, sent);

        assertThat(headers.keySet()).containsExactlyElementsOf(Collections.list(request.getHeaderNames()));
        assertThat(headers.get("Authorization")).containsExactly("Digest username=\"John  Smith\"");
        assertThat(headers.get("X-Multi")).containsExactly("p\tq", "r  s", "t u");
        assertThat(headers.get("Cookie")).containsExactly("a=1; b=2");
        assertThat(headers.get("X-Drift")).containsExactly("x z");
        assertThat(headers.get("X-Added")).containsExactly("v  w");
    }

    @Test
    void requestWithoutRecordedFieldsIsCopiedAsTheContainerReturnsIt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer t1");
        request.addHeader("X-Multi", "p q");
        request.addHeader("X-Multi", "r s");

        // MockMvc and any container other than Undertow carry no recorded fields.
        HttpHeaders headers = ForwardedRequestHeaders.from(request);

        assertThat(headers).isEqualTo(ForwardedRequestHeaders.from(request, null));
        assertThat(headers.keySet()).containsExactly("Authorization", "X-Multi");
        assertThat(headers.get("Authorization")).containsExactly("Bearer t1");
        assertThat(headers.get("X-Multi")).containsExactly("p q", "r s");
    }

    @Test
    void headWithoutFieldsLeavesEveryValueAsParsed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-A", "a b");

        HttpHeaders headers = ForwardedRequestHeaders.from(request, sentHead("GET / HTTP/1.1\r\n\r\n"));

        assertThat(headers.get("X-A")).isEqualTo(List.of("a b"));
    }

    /** The fields {@link RequestFramingValidator} records for {@code head}, a complete request head. */
    private static SentHeaderFields sentHead(String head) {
        RequestFramingValidator validator = new RequestFramingValidator(8192);
        byte[] bytes = head.getBytes(ISO_8859_1);
        validator.consume(bytes, 0, bytes.length);
        SentHeaderFields sent = validator.pollReleasedHead();
        assertThat(sent).as("recorded head").isNotNull();
        return sent;
    }
}
