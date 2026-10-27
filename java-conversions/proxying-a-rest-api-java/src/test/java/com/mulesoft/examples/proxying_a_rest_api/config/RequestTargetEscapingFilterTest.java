/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.proxying_a_rest_api.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.mulesoft.examples.proxying_a_rest_api.config.RequestTargetEscapingFilter.TargetComponent.PATH;
import static com.mulesoft.examples.proxying_a_rest_api.config.RequestTargetEscapingFilter.TargetComponent.QUERY;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.mulesoft.examples.proxying_a_rest_api.ProxyingARestApiApplication;
import com.mulesoft.examples.proxying_a_rest_api.config.RequestTargetEscapingFilter.TargetComponent;
import com.mulesoft.examples.proxying_a_rest_api.support.HttpTestClient;

/**
 * Tests {@link RequestTargetEscapingFilter}: a request target the original {@code /*} listener accepted although
 * RFC 3986 does not allow it reaches the upstream with only the disallowed characters percent-encoded, and a valid
 * target reaches it byte for byte.
 *
 * <p>The escaping cases call the encoding directly for every character class of each component. The filter cases
 * check on a mock request which requests are wrapped and what the wrapper returns. The wire cases boot the
 * application with its own {@code application.yml} on Undertow (random port on IPv4 loopback), send each raw target
 * through ST-1 {@link HttpTestClient}, which writes it unchanged, and compare the URL the WireMock upstream recorded.
 */
@SpringBootTest(
        classes = ProxyingARestApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Pins the listener to the address ST-1 connects to, whatever server.address the project sets.
        properties = "server.address=" + RequestTargetEscapingFilterTest.LOOPBACK)
@ActiveProfiles("test")
class RequestTargetEscapingFilterTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    private static final String UPSTREAM_BODY = "ok";

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().bindAddress(LOOPBACK))
            .build();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("proxy.upstream-url", () -> "http://" + LOOPBACK + ":" + upstream.getPort());
    }

    /** Component, raw text as the container returns it, expected escaped text. */
    private static Stream<Arguments> escapes() {
        return Stream.of(
                // Every character a component keeps, and valid escapes in either letter case, stay as they are.
                Arguments.of(PATH, "", ""),
                Arguments.of(PATH, "/2.0/folders/0", "/2.0/folders/0"),
                Arguments.of(PATH, "/AZaz09-._~!$&'()*+,;=:@/", "/AZaz09-._~!$&'()*+,;=:@/"),
                Arguments.of(PATH, "/a%20b%2Fc%7c%7C%e9", "/a%20b%2Fc%7c%7C%e9"),
                Arguments.of(QUERY, "", ""),
                Arguments.of(QUERY, "AZaz09-._~!$&'()*+,;=:@/?[]", "AZaz09-._~!$&'()*+,;=:@/?[]"),
                Arguments.of(QUERY, "filter[name]=x", "filter[name]=x"),
                Arguments.of(QUERY, "k=%7Cx&x=a%20b+c&y=%7c", "k=%7Cx&x=a%20b+c&y=%7c"),
                // Characters RFC 3986 allows in neither component.
                Arguments.of(PATH, "/a|b", "/a%7Cb"),
                Arguments.of(PATH, "/a{b}", "/a%7Bb%7D"),
                Arguments.of(PATH, "/a^b", "/a%5Eb"),
                Arguments.of(PATH, "/a\"<>`\\b", "/a%22%3C%3E%60%5Cb"),
                Arguments.of(PATH, "/a b", "/a%20b"),
                Arguments.of(PATH, "/a#b", "/a%23b"),
                Arguments.of(PATH, "/a\u0000\t\u001f\u007fb", "/a%00%09%1F%7Fb"),
                Arguments.of(QUERY, "k=v|w", "k=v%7Cw"),
                Arguments.of(QUERY, "k={x}", "k=%7Bx%7D"),
                Arguments.of(QUERY, "k=a^b", "k=a%5Eb"),
                Arguments.of(QUERY, "k=a\"b", "k=a%22b"),
                Arguments.of(QUERY, "k=<x>", "k=%3Cx%3E"),
                Arguments.of(QUERY, "k=a`b", "k=a%60b"),
                Arguments.of(QUERY, "k=a\\b", "k=a%5Cb"),
                Arguments.of(QUERY, "k=a b", "k=a%20b"),
                Arguments.of(QUERY, "k=a#b", "k=a%23b"),
                Arguments.of(QUERY, "k=a\u0000\t\u001f\u007fb", "k=a%00%09%1F%7Fb"),
                // Characters the query keeps and the path does not.
                Arguments.of(PATH, "/a[b]", "/a%5Bb%5D"),
                Arguments.of(PATH, "/a?b", "/a%3Fb"),
                // A % that does not start an escape of two ASCII hexadecimal digits.
                Arguments.of(PATH, "/a%zz", "/a%25zz"),
                Arguments.of(PATH, "/a%", "/a%25"),
                Arguments.of(PATH, "/a%2", "/a%252"),
                Arguments.of(PATH, "/a%%41", "/a%25%41"),
                Arguments.of(PATH, "/a%\uff21\uff21", "/a%25%EF%BC%A1%EF%BC%A1"),
                Arguments.of(QUERY, "k=%zz", "k=%25zz"),
                Arguments.of(QUERY, "k=%", "k=%25"),
                Arguments.of(QUERY, "k=a%2", "k=a%252"),
                // Non-ASCII: up to U+00FF one byte each (the container's ISO-8859-1 reading of the caller's bytes),
                // above it UTF-8.
                Arguments.of(PATH, "/caf\u00c3\u00a9", "/caf%C3%A9"),
                Arguments.of(PATH, "/\u0080\u00ff", "/%80%FF"),
                Arguments.of(QUERY, "k=caf\u00c3\u00a9", "k=caf%C3%A9"),
                Arguments.of(PATH, "/\u20ac", "/%E2%82%AC"),
                Arguments.of(PATH, "/\ud83d\ude00", "/%F0%9F%98%80"),
                Arguments.of(QUERY, "k=\ud83d", "k=%3F"));
    }

    // The name leaves out the raw text, which holds control characters the test reports cannot carry.
    @ParameterizedTest(name = "[{index}] {0} -> [{2}]")
    @MethodSource("escapes")
    void escapeEncodesEveryCharacterTheComponentDoesNotKeep(TargetComponent component, String raw,
                                                             String expected) {
        String escaped = RequestTargetEscapingFilter.escape(raw, component);

        assertThat(escaped).isEqualTo(expected);
        if (raw.equals(expected)) {
            assertThat(escaped).as("unchanged text is returned as is").isSameAs(raw);
        }
    }

    @ParameterizedTest(name = "[{0}] ? [{1}]")
    @CsvSource(nullValues = "NULL", value = {
            "/2.0/folders/0, NULL",
            "/2.0/folders/0, ''",
            "/2.0/search, filter[name]=x&q=a%20b+c",
            "/a%2Fb;p=1, k=%7c"
    })
    void validTargetIsPassedOnUnwrapped(String requestUri, String queryString) throws Exception {
        MockHttpServletRequest request = request(requestUri, queryString);
        MockFilterChain chain = new MockFilterChain();

        new RequestTargetEscapingFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @ParameterizedTest(name = "[{0}] ? [{1}] -> [{2}] ? [{3}]")
    @CsvSource(nullValues = "NULL", value = {
            "/a|b, NULL, /a%7Cb, NULL",
            "/a|b, '', /a%7Cb, ''",
            "/qtest, k=v|w, /qtest, k=v%7Cw",
            "/a%zz, k=%, /a%25zz, k=%25"
    })
    void invalidTargetIsPassedOnEscaped(String requestUri, String queryString, String expectedUri,
                                        String expectedQuery) throws Exception {
        MockHttpServletRequest request = request(requestUri, queryString);
        request.addHeader("Authorization", "Bearer token");
        MockFilterChain chain = new MockFilterChain();

        new RequestTargetEscapingFilter().doFilter(request, new MockHttpServletResponse(), chain);

        HttpServletRequest forwarded = (HttpServletRequest) chain.getRequest();
        assertThat(forwarded).isNotSameAs(request);
        assertThat(forwarded.getRequestURI()).isEqualTo(expectedUri);
        assertThat(forwarded.getQueryString()).isEqualTo(expectedQuery);
        assertThat(forwarded.getRequestURL()).hasToString("http://localhost" + expectedUri);
        assertThat(forwarded.getMethod()).isEqualTo("GET");
        assertThat(forwarded.getHeader("Authorization")).isEqualTo("Bearer token");
    }

    /** Raw target the caller sends, URL the upstream must receive. */
    private static Stream<Arguments> wireTargets() {
        return Stream.of(
                Arguments.of("/qtest?k=v|w", "/qtest?k=v%7Cw"),
                Arguments.of("/a|b", "/a%7Cb"),
                Arguments.of("/a{b}", "/a%7Bb%7D"),
                Arguments.of("/a^b", "/a%5Eb"),
                Arguments.of("/a%zz", "/a%25zz"),
                Arguments.of("/a%", "/a%25"),
                Arguments.of("/a%2", "/a%252"),
                // Only the first % starts no valid escape; the second starts %41.
                Arguments.of("/a%%41", "/a%25%41"),
                Arguments.of("/qtest?k={x}", "/qtest?k=%7Bx%7D"),
                Arguments.of("/qtest?k=a\"b", "/qtest?k=a%22b"),
                Arguments.of("/qtest?k=<x>", "/qtest?k=%3Cx%3E"),
                Arguments.of("/qtest?k=a`b", "/qtest?k=a%60b"),
                Arguments.of("/qtest?k=a\\b", "/qtest?k=a%5Cb"),
                Arguments.of("/qtest?k=%zz", "/qtest?k=%25zz"),
                Arguments.of("/qtest?k=a%2", "/qtest?k=a%252"),
                Arguments.of("/a[b]", "/a%5Bb%5D"),
                Arguments.of("/a#b", "/a%23b"),
                Arguments.of("/qtest?k=a#b", "/qtest?k=a%23b"),
                // ST-1 writes the target as UTF-8, so the caller's bytes are C3 A9.
                Arguments.of("/caf\u00e9?k=caf\u00e9", "/caf%C3%A9?k=caf%C3%A9"),
                // Valid targets reach the upstream byte for byte.
                Arguments.of("/qtest?k=%7Cx", "/qtest?k=%7Cx"),
                Arguments.of("/qtest?filter[name]=x", "/qtest?filter[name]=x"),
                Arguments.of("/a%20b?x=a%20b+c", "/a%20b?x=a%20b+c"),
                Arguments.of("/a%2Fb", "/a%2Fb"),
                Arguments.of("/x%7cy?k=%7c", "/x%7cy?k=%7c"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("wireTargets")
    void rawTargetIsForwardedWithOnlyDisallowedCharactersEscaped(String target, String upstreamUrl) {
        upstream.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody(UPSTREAM_BODY)));

        HttpTestClient.Response response = new HttpTestClient(LOOPBACK, port).get(target);

        assertThat(response.statusLine()).as(response::toString).isEqualTo("HTTP/1.1 200 OK");
        assertThat(response.bodyAsString()).as(response::toString).isEqualTo(UPSTREAM_BODY);
        List<ServeEvent> received = upstream.getAllServeEvents();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).getRequest().getUrl()).isEqualTo(upstreamUrl);
    }

    private static MockHttpServletRequest request(String requestUri, String queryString) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
        request.setQueryString(queryString);
        return request;
    }
}
