/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_json_data_to_a_jms_queue.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.mulesoft.examples.sending_json_data_to_a_jms_queue.support.HttpTestClient;

/**
 * Tests {@link ListenerMethodFilter}, which matches {@code POST} on the listener ignoring case, as Mule's
 * {@code MethodRequestMatcher} did for {@code allowedMethods="POST"}
 * (sending-json-data-to-a-jms-queue/src/main/app/json-to-jms.xml:6), in two layers.
 *
 * <ul>
 *   <li>Unit layer: the filter alone over a {@link MockFilterChain}, checking which request the chain receives
 *       and the method it reports.</li>
 *   <li>Wire layer: the whole application on Undertow (random port on IPv4 loopback, embedded Artemis), called
 *       through the project's ST-1 client with the method token exactly as written, so the raw status line, the
 *       {@code Content-Type} header and the body bytes are asserted as the listener writes them, and the queued
 *       message is read back from queue {@code sales}. Each accepted request also checks the success response's
 *       {@code Content-Type} against the one the original listener derived from the request's.</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Pins the listener to the address ST-1 connects to, whatever server.address the project sets.
            "server.address=" + ListenerMethodFilterTest.LOOPBACK,
            // Bounds the reads of queue sales, so a missing message fails the test instead of blocking it.
            "spring.jms.template.receive-timeout=5s"
        })
class ListenerMethodFilterTest {

    /** IPv4 loopback literal: {@code localhost} may resolve to {@code ::1}, where nothing listens. */
    static final String LOOPBACK = "127.0.0.1";

    private static final String SALES = "sales";

    /** The request body of the original finding's reproduction, {@code abc}. */
    private static final byte[] ABC = "abc".getBytes(StandardCharsets.US_ASCII);

    @LocalServerPort
    int port;

    @Autowired
    JmsTemplate jmsTemplate;

    @ParameterizedTest
    @ValueSource(strings = {"post", "Post", "pOsT"})
    void caseVariantOfPostReachesTheChainAsPost(String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/sales");
        request.setContentType("text/plain");
        request.setContent(ABC);
        MockFilterChain chain = new MockFilterChain();

        new ListenerMethodFilter().doFilter(request, new MockHttpServletResponse(), chain);

        ServletRequest passed = chain.getRequest();
        assertNotSame(request, passed);
        HttpServletRequest wrapped = (HttpServletRequest) passed;
        assertEquals("POST", wrapped.getMethod());
        // Everything but the method is the original request's.
        assertEquals("/sales", wrapped.getRequestURI());
        assertEquals("text/plain", wrapped.getContentType());
        assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo(ABC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "GET", "get", "options", "OPTIONS", "trace", "postx", "pos", "po\u017Ft"})
    void otherMethodTokenPassesUnchanged(String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/sales");
        MockFilterChain chain = new MockFilterChain();

        new ListenerMethodFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertSame(request, chain.getRequest());
        assertEquals(method, request.getMethod());
    }

    @Test
    void requestWithoutMethodPassesUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(null);
        MockFilterChain chain = new MockFilterChain();

        new ListenerMethodFilter().doFilter(request, new MockHttpServletResponse(), chain);

        assertSame(request, chain.getRequest());
    }

    /**
     * A {@code POST} in some letter case, the request's {@code Content-Type} ({@code null} for none), its body, the
     * text queue {@code sales} must receive, and the response {@code Content-Type} the original listener wrote
     * ({@code null} for none): the lower-cased {@code type/subtype}, {@code "; charset="} and the decoding charset.
     */
    static Stream<Arguments> postsOnTheWire() {
        String jvmDefault = "; charset=" + Charset.defaultCharset().name();
        byte[] cafeLatin1 = {'C', 'a', 'f', (byte) 0xE9};
        return Stream.of(
                Arguments.of("post", "text/plain", ABC, "abc", "text/plain" + jvmDefault),
                Arguments.of("Post", "text/plain", ABC, "abc", "text/plain" + jvmDefault),
                Arguments.of("pOsT", "text/plain", ABC, "abc", "text/plain" + jvmDefault),
                Arguments.of("post", null, ABC, "abc", null),
                Arguments.of("post", "APPLICATION/JSON; foo=bar", ABC, "abc", "application/json" + jvmDefault),
                Arguments.of("post", "text/plain; charset=ISO-8859-1", cafeLatin1, "Caf\u00e9",
                        "text/plain; charset=ISO-8859-1"),
                Arguments.of("post", "*/*", ABC, "abc", null),
                Arguments.of("post", ";;;", ABC, "abc", null),
                Arguments.of("POST", "application/json; charset=UTF-8", ABC, "abc", "application/json; charset=UTF-8"));
    }

    @ParameterizedTest(name = "{index}: {0} with Content-Type [{1}]")
    @MethodSource("postsOnTheWire")
    void postInAnyLetterCaseRunsTheFlowAndAnswersWithTheOriginalContentType(String method, String contentType,
            byte[] body, String queuedText, String responseContentType) {
        Map<String, String> headers = contentType == null ? Map.of() : Map.of("Content-Type", contentType);

        HttpTestClient.Response response = client().send(method, "/sales", headers, body);

        assertEquals("HTTP/1.1 200 OK", response.statusLine(), response::toString);
        // Exactly one header, or none, with the bytes the listener wrote: the space after ';' included.
        assertEquals(responseContentType == null ? List.of() : List.of(responseContentType),
                response.headers("Content-Type"), response::toString);
        assertThat(response.body()).isEqualTo(body);
        assertEquals(queuedText, jmsTemplate.receiveAndConvert(SALES));
    }

    /**
     * Spellings of methods other than {@code POST}, with headers that would make an {@code OPTIONS} request a
     * CORS preflight: each keeps the listener's 405.
     */
    static Stream<Arguments> otherMethodsOnTheWire() {
        Map<String, String> preflightHeaders = new LinkedHashMap<>();
        preflightHeaders.put("Origin", "http://example.com");
        preflightHeaders.put("Access-Control-Request-Method", "POST");
        return Stream.of(
                Arguments.of("get", Map.of()),
                Arguments.of("options", preflightHeaders),
                Arguments.of("trace", Map.of()),
                Arguments.of("postx", Map.of()));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("otherMethodsOnTheWire")
    void otherMethodSpellingKeepsTheListener405(String method, Map<String, String> headers) {
        HttpTestClient.Response response = client().send(method, "/sales", headers, null);

        assertEquals("HTTP/1.1 405 Method Not Allowed", response.statusLine(), response::toString);
        assertEquals("text/plain", response.header("Content-Type"), response::toString);
        assertEquals("Method not allowed for endpoint: /sales", response.bodyAsString());
    }

    private HttpTestClient client() {
        return new HttpTestClient(LOOPBACK, port);
    }
}
