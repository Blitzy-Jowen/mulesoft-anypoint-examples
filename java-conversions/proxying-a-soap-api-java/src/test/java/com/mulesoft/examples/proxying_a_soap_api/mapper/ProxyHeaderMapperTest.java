/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_soap_api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

/**
 * Unit test of shared component SC-13: header copying between caller and upstream, reproducing the Mule
 * {@code copy-headers} sub-flow ({@code copy-properties *}, then removal of {@code Host},
 * {@code Content-Length}, {@code http.*}, {@code MULE_*}, {@code Connection} and
 * {@code transfer-encoding}), which the original flow ran before its outbound request and again on the
 * response. Copied unchanged, apart from the package line, into every SC-13 consumer.
 *
 * <p>Header names are compared through {@link HttpHeaders#containsKey} and {@link HttpHeaders#get}, which
 * ignore case, so neither the casing of the produced names nor the order between headers is asserted; only
 * the order of the values within one header is part of the contract.
 */
class ProxyHeaderMapperTest {

    private final ProxyHeaderMapper mapper = new ProxyHeaderMapper();

    /** Both copy directions, so each direction-independent rule is asserted once per direction. */
    static Stream<Arguments> directions() {
        BiFunction<ProxyHeaderMapper, HttpHeaders, HttpHeaders> toUpstream = ProxyHeaderMapper::toUpstream;
        BiFunction<ProxyHeaderMapper, HttpHeaders, HttpHeaders> toCaller = ProxyHeaderMapper::toCaller;
        return Stream.of(
                Arguments.of(Named.of("toUpstream", toUpstream)),
                Arguments.of(Named.of("toCaller", toCaller)));
    }

    @Test
    void removedNamesAndPrefixesAreExactlyThoseOfTheOriginalSubFlow() {
        assertThat(ProxyHeaderMapper.REMOVED_HEADERS)
                .containsExactlyInAnyOrder("Host", "Content-Length", "Connection", "Transfer-Encoding");
        assertThat(ProxyHeaderMapper.REMOVED_PREFIXES).containsExactlyInAnyOrder("http.", "MULE_");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Host", "host", "HOST", "Content-Length", "content-length", "Connection",
            "connection", "Transfer-Encoding", "transfer-encoding"})
    void isRemovedMatchesExactNamesIgnoringCase(String name) {
        assertThat(mapper.isRemoved(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http.method", "http.request.path", "http.query.params", "MULE_CORRELATION_ID",
            "mule_session"})
    void isRemovedMatchesPrefixesIgnoringCase(String name) {
        assertThat(mapper.isRemoved(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "Accept", "Cookie", "X-Custom", "Content-Type", "X-Forwarded-Host",
            "Set-Cookie"})
    void isRemovedKeepsEveryOtherName(String name) {
        assertThat(mapper.isRemoved(name)).isFalse();
    }

    @Test
    void isRemovedRejectsANullName() {
        assertThat(mapper.isRemoved(null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"host", "HOST", "content-length", "Connection", "transfer-encoding",
            "Transfer-Encoding", "http.method", "http.request.path", "MULE_CORRELATION_ID", "mule_session"})
    void eachRemovedNameIsDroppedInBothDirections(String name) {
        HttpHeaders input = new HttpHeaders();
        input.add(name, "v");
        input.add("Accept", "application/json");

        HttpHeaders upstream = mapper.toUpstream(input);
        assertThat(upstream.containsKey(name)).isFalse();
        assertThat(upstream.get("Accept")).containsExactly("application/json");

        HttpHeaders caller = mapper.toCaller(input);
        assertThat(caller.containsKey(name)).isFalse();
        assertThat(caller.get("Accept")).containsExactly("application/json");
    }

    @Test
    void toUpstreamKeepsCallerHeadersAndDropsTransportAndMuleProperties() {
        HttpHeaders input = mixedRequestHeaders();

        HttpHeaders result = mapper.toUpstream(input);

        assertThat(result).isNotNull().isNotSameAs(input);
        // The caller's identity reaches the upstream exactly as received.
        assertThat(result.get("Authorization")).isEqualTo(List.of("Bearer abc"));
        assertThat(result.get("Accept")).containsExactly("application/json");
        assertThat(result.get("Cookie")).containsExactly("session=1");
        assertThat(result.get("X-Custom")).containsExactly("a", "b", "c");
        for (String removed : List.of("Host", "Content-Length", "Connection", "Transfer-Encoding", "http.method",
                "http.request.path", "MULE_CORRELATION_ID")) {
            assertThat(result.containsKey(removed)).as("header %s", removed).isFalse();
        }
        assertThat(result.keySet()).noneMatch(mapper::isRemoved);
        assertThat(result.size()).isEqualTo(4);
    }

    @Test
    void toCallerKeepsUpstreamHeadersAndDropsTransportAndMuleProperties() {
        HttpHeaders input = mixedResponseHeaders();

        HttpHeaders result = mapper.toCaller(input);

        assertThat(result).isNotNull().isNotSameAs(input);
        assertThat(result.get("Content-Type")).containsExactly("text/xml");
        assertThat(result.get("Set-Cookie")).containsExactly("a=1", "b=2");
        assertThat(result.get("Location")).containsExactly("http://localhost/x");
        assertThat(result.get("X-Custom")).containsExactly("a", "b", "c");
        for (String removed : List.of("Content-Length", "Transfer-Encoding", "Connection", "http.status",
                "MULE_ENCODING")) {
            assertThat(result.containsKey(removed)).as("header %s", removed).isFalse();
        }
        assertThat(result.keySet()).noneMatch(mapper::isRemoved);
        assertThat(result.size()).isEqualTo(4);
    }

    @ParameterizedTest
    @MethodSource("directions")
    void inputIsNeverModifiedAndResultIsIndependent(
            BiFunction<ProxyHeaderMapper, HttpHeaders, HttpHeaders> direction) {
        HttpHeaders input = mixedRequestHeaders();
        HttpHeaders snapshot = deepCopy(input);

        HttpHeaders result = direction.apply(mapper, input);

        assertThat(input).isEqualTo(snapshot);
        assertThat(input.get("Host")).containsExactly("localhost:8081");
        assertThat(input.get("X-Custom")).containsExactly("a", "b", "c");

        result.add("X-Added", "1");
        result.add("X-Custom", "d");
        assertThat(input.containsKey("X-Added")).isFalse();
        assertThat(input.get("X-Custom")).containsExactly("a", "b", "c");
    }

    @ParameterizedTest
    @MethodSource("directions")
    void readOnlyInputIsAcceptedAndCopiedLikeAWritableOne(
            BiFunction<ProxyHeaderMapper, HttpHeaders, HttpHeaders> direction) {
        HttpHeaders input = mixedRequestHeaders();

        HttpHeaders fromReadOnly = direction.apply(mapper, HttpHeaders.readOnlyHttpHeaders(input));

        assertThat(fromReadOnly).isEqualTo(direction.apply(mapper, input));
        // The result is the mapper's own writable copy, not a view of the read-only argument.
        fromReadOnly.add("X-Added", "1");
        assertThat(fromReadOnly.get("X-Added")).containsExactly("1");
        assertThat(input).isEqualTo(mixedRequestHeaders());
    }

    @ParameterizedTest
    @MethodSource("directions")
    void nullValueListIsSkippedAndEmptyValueListIsKept(
            BiFunction<ProxyHeaderMapper, HttpHeaders, HttpHeaders> direction) {
        HttpHeaders input = new HttpHeaders();
        input.put("X-Null", null);
        input.put("X-Empty", new ArrayList<>());
        input.add("Accept", "application/json");

        HttpHeaders result = direction.apply(mapper, input);

        assertThat(result.containsKey("X-Null")).isFalse();
        assertThat(result.containsKey("X-Empty")).isTrue();
        assertThat(result.get("X-Empty")).isEmpty();
        assertThat(result.get("Accept")).containsExactly("application/json");
    }

    @ParameterizedTest
    @MethodSource("directions")
    void nullOrEmptyInputYieldsNewEmptyHeaders(
            BiFunction<ProxyHeaderMapper, HttpHeaders, HttpHeaders> direction) {
        assertThat(direction.apply(mapper, null)).isNotNull().isEmpty();

        HttpHeaders empty = new HttpHeaders();
        assertThat(direction.apply(mapper, empty)).isNotNull().isNotSameAs(empty).isEmpty();
    }

    /** A caller request mixing kept headers with every kind of removed name. */
    private static HttpHeaders mixedRequestHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Host", "localhost:8081");
        headers.add("Content-Length", "42");
        headers.add("Connection", "keep-alive");
        headers.add("Transfer-Encoding", "chunked");
        headers.add("http.method", "GET");
        headers.add("http.request.path", "/x");
        headers.add("MULE_CORRELATION_ID", "1");
        headers.add("Authorization", "Bearer abc");
        headers.add("Accept", "application/json");
        headers.add("Cookie", "session=1");
        headers.addAll("X-Custom", List.of("a", "b", "c"));
        return headers;
    }

    /** An upstream response mixing kept headers with every kind of removed name. */
    private static HttpHeaders mixedResponseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "text/xml");
        headers.add("Content-Length", "10");
        headers.add("Transfer-Encoding", "chunked");
        headers.add("Connection", "close");
        headers.addAll("Set-Cookie", List.of("a=1", "b=2"));
        headers.add("Location", "http://localhost/x");
        headers.add("http.status", "200");
        headers.add("MULE_ENCODING", "UTF-8");
        headers.addAll("X-Custom", List.of("a", "b", "c"));
        return headers;
    }

    /**
     * Copies every name and value list. {@code new HttpHeaders(MultiValueMap)} is not used: it wraps the
     * given map as a live view and would not detect a modification.
     */
    private static HttpHeaders deepCopy(HttpHeaders source) {
        HttpHeaders copy = new HttpHeaders();
        source.forEach((name, values) -> copy.put(name, new ArrayList<>(values)));
        return copy;
    }
}
