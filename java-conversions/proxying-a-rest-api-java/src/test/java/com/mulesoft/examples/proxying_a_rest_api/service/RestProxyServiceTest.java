/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import com.mulesoft.examples.proxying_a_rest_api.client.BoxProxyClient;
import com.mulesoft.examples.proxying_a_rest_api.mapper.ProxyHeaderMapper;

/**
 * Unit test of {@link RestProxyService}, the original flow {@code rest-api-proxy}: SC-13 header copying on the
 * caller's headers, one upstream call with the caller's method, raw path, raw query and the body the Mule
 * requester sent for that method, the requester's default success-status validator, and SC-13 header copying on
 * the upstream response. The client is a Mockito mock and the header mapper the real SC-13 component, so the
 * headers each leg carries are the ones the running proxy produces.
 */
class RestProxyServiceTest {

    private static final String PATH = "/2.0/folders/0";

    private static final byte[] REQUEST_BODY = "{\"name\":\"Résumé ✓\",\"parent\":{\"id\":\"0\"}}".getBytes(UTF_8);

    private static final byte[] UPSTREAM_BODY = "{\"type\":\"folder\",\"id\":\"0\",\"name\":\"Tous les fichiers\"}"
            .getBytes(UTF_8);

    private final BoxProxyClient client = mock(BoxProxyClient.class);

    private final ProxyHeaderMapper headerMapper = new ProxyHeaderMapper();

    private final RestProxyService service = new RestProxyService(headerMapper, client);

    /**
     * Caller method, the body the caller sent, and the body the client must receive: none for GET, HEAD and
     * OPTIONS ({@code sendBodyMode} AUTO) and for TRACE, CONNECT and PRI (no payload from the requester's Grizzly
     * client) whatever was sent; for every other method, an extension method or a lower-case {@code trace} token
     * included, the bytes as sent, an empty or absent body as empty bytes.
     */
    static Stream<Arguments> requestBodies() {
        Stream<Arguments> bodiless = Stream.of("GET", "HEAD", "OPTIONS", "TRACE", "CONNECT", "PRI")
                .flatMap(method -> Stream.of(
                        Arguments.of(method, Named.of("request bytes", REQUEST_BODY), null),
                        Arguments.of(method, Named.of("empty bytes", new byte[0]), null),
                        Arguments.of(method, Named.of("no body", null), null)));
        Stream<Arguments> bodied = Stream.of("POST", "PUT", "PATCH", "DELETE", "PROPFIND", "trace")
                .flatMap(method -> Stream.of(
                        Arguments.of(method, Named.of("request bytes", REQUEST_BODY), REQUEST_BODY),
                        Arguments.of(method, Named.of("empty bytes", new byte[0]), new byte[0]),
                        Arguments.of(method, Named.of("no body", null), new byte[0])));
        return Stream.concat(bodiless, bodied);
    }

    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("requestBodies")
    void requestBodyReachesTheClientAsTheMuleRequesterSentIt(String methodName, byte[] sent, byte[] expected) {
        HttpMethod method = HttpMethod.valueOf(methodName);
        stubUpstream(ResponseEntity.ok(UPSTREAM_BODY));

        service.forward(method, PATH, null, new HttpHeaders(), sent);

        ArgumentCaptor<byte[]> forwarded = ArgumentCaptor.forClass(byte[].class);
        verify(client, times(1)).exchange(eq(method), eq(PATH), isNull(), any(HttpHeaders.class),
                forwarded.capture());
        verifyNoMoreInteractions(client);
        if (expected == null) {
            assertThat(forwarded.getValue()).isNull();
        } else {
            assertThat(forwarded.getValue()).isNotNull().isEqualTo(expected).hasSize(expected.length);
        }
    }

    @ParameterizedTest(name = "path {0}, query {1}")
    @CsvSource({
            "/2.0/search, query=a%20b&type=file",
            "/2.0/folders/0, ",
            "/2.0/files/a%2Fb/content, q=a+b&fields=id%2Cname",
            "/2.0/folders/0/items, ''"
    })
    void rawPathAndQueryReachTheClientUntouched(String rawPath, String rawQuery) {
        stubUpstream(ResponseEntity.ok(UPSTREAM_BODY));

        service.forward(HttpMethod.GET, rawPath, rawQuery, new HttpHeaders(), null);

        verify(client, times(1)).exchange(eq(HttpMethod.GET), eq(rawPath), eq(rawQuery), any(HttpHeaders.class),
                isNull());
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest(name = "upstream {0}")
    @ValueSource(ints = {200, 201, 204, 302, 399})
    void everyUpstreamStatusBelow400IsAnswered200WithTheUnparsedBody(int upstreamStatus) {
        stubUpstream(ResponseEntity.status(upstreamStatus).body(UPSTREAM_BODY));

        ResponseEntity<byte[]> result = service.forward(HttpMethod.GET, PATH, null, new HttpHeaders(), null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(UPSTREAM_BODY);
        verify(client, times(1)).exchange(any(), any(), any(), any(), any());
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest(name = "upstream {0}")
    @ValueSource(ints = {400, 401, 404, 500, 503})
    void everyUpstreamStatusFrom400FailsWithTheDefaultValidatorMessage(int upstreamStatus) {
        stubUpstream(ResponseEntity.status(upstreamStatus).body(UPSTREAM_BODY));

        assertThatThrownBy(() -> service.forward(HttpMethod.GET, PATH, null, new HttpHeaders(), null))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Response code " + upstreamStatus
                        + " mapped as failure. Message payload is of type: BufferInputStream")
                .hasMessage(String.format(RestProxyService.VALIDATOR_FAILURE_MESSAGE, upstreamStatus))
                .hasNoCause();
        verify(client, times(1)).exchange(any(), any(), any(), any(), any());
        verifyNoMoreInteractions(client);
    }

    @Test
    void headersAreCopiedBothWaysWithoutTransportAndMuleProperties() {
        HttpHeaders inbound = new HttpHeaders();
        inbound.add("MULE_X", "1");
        inbound.add("Authorization", "Bearer abc");
        inbound.add("Host", "localhost:8081");
        inbound.add("X-Custom-In", "keep");
        HttpHeaders inboundSnapshot = deepCopy(inbound);
        HttpHeaders upstreamHeaders = new HttpHeaders();
        upstreamHeaders.add("Transfer-Encoding", "chunked");
        upstreamHeaders.add("X-Box-Request-Id", "abc123");
        stubUpstream(ResponseEntity.status(200).headers(upstreamHeaders).body(UPSTREAM_BODY));

        ResponseEntity<byte[]> result = service.forward(HttpMethod.GET, PATH, null, inbound, null);

        ArgumentCaptor<HttpHeaders> sentHeaders = ArgumentCaptor.forClass(HttpHeaders.class);
        verify(client, times(1)).exchange(eq(HttpMethod.GET), eq(PATH), isNull(), sentHeaders.capture(), isNull());
        verifyNoMoreInteractions(client);
        HttpHeaders upstream = sentHeaders.getValue();
        assertThat(upstream).isNotSameAs(inbound);
        assertThat(upstream.containsKey("MULE_X")).isFalse();
        assertThat(upstream.containsKey("Host")).isFalse();
        // The caller's identity reaches the upstream exactly as received.
        assertThat(upstream.get("Authorization")).isEqualTo(List.of("Bearer abc"));
        assertThat(upstream.get("X-Custom-In")).containsExactly("keep");
        assertThat(upstream.size()).isEqualTo(2);
        assertThat(inbound).isEqualTo(inboundSnapshot);

        HttpHeaders returned = result.getHeaders();
        assertThat(returned.containsKey("Transfer-Encoding")).isFalse();
        assertThat(returned.get("X-Box-Request-Id")).isEqualTo(List.of("abc123"));
        assertThat(returned.size()).isEqualTo(1);
    }

    @Test
    void anUpstreamResponseWithoutABodyIsAnsweredWithEmptyBytes() {
        stubUpstream(ResponseEntity.status(200).build());

        ResponseEntity<byte[]> result = service.forward(HttpMethod.GET, PATH, null, new HttpHeaders(), null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull().isEmpty();
        assertThat(result.getHeaders()).isEmpty();
    }

    @Test
    void aClientFailurePropagatesUnchangedAndIsNeverRetried() {
        ResourceAccessException timeout = new ResourceAccessException("timeout");
        when(client.exchange(any(), any(), any(), any(), any())).thenThrow(timeout);

        assertThatThrownBy(() -> service.forward(HttpMethod.POST, PATH, null, new HttpHeaders(), REQUEST_BODY))
                .isSameAs(timeout);
        verify(client, times(1)).exchange(eq(HttpMethod.POST), eq(PATH), isNull(), any(HttpHeaders.class),
                eq(REQUEST_BODY));
        verifyNoMoreInteractions(client);
    }

    private void stubUpstream(ResponseEntity<byte[]> upstream) {
        when(client.exchange(any(), any(), any(), any(), any())).thenReturn(upstream);
    }

    /**
     * Copies every name and value list. {@code new HttpHeaders(MultiValueMap)} is not used: it wraps the given map
     * as a live view and would not detect a modification.
     */
    private static HttpHeaders deepCopy(HttpHeaders source) {
        HttpHeaders copy = new HttpHeaders();
        source.forEach((name, values) -> copy.put(name, new ArrayList<>(values)));
        return copy;
    }
}
