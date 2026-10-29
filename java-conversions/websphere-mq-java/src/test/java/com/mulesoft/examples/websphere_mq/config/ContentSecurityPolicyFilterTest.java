/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.websphere_mq.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit test of {@link ContentSecurityPolicyFilter}.
 *
 * <ul>
 *   <li>The policy matches the served page: the inline code of {@code static/services/wmqExample/index.html}, read
 *       from the classpath (every inline script with text and every {@code on*} event-handler attribute), hashed as
 *       a browser hashes it, gives exactly the policy's hashes, each under the constant that names it; beside them
 *       {@code script-src} allows only {@code 'self'}, {@code 'unsafe-eval'} and {@code 'unsafe-hashes'}, the only
 *       other directives are {@code object-src 'none'} and {@code base-uri 'none'}, and {@code 'unsafe-inline'}
 *       appears nowhere.</li>
 *   <li>The response wrapper, with mock servlet objects: each way an HTML Content-Type can be set yields the policy
 *       header exactly once, with the body passed through unchanged; every other Content-Type, and none, yields no
 *       policy header.</li>
 * </ul>
 */
class ContentSecurityPolicyFilterTest {

    /** Classpath location of the copied docroot page the policy protects. */
    private static final String PAGE = "static/services/wmqExample/index.html";

    /** Body the filter chain writes, which must reach the response unchanged. */
    private static final byte[] BODY = "<html><body>page</body></html>".getBytes(UTF_8);

    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** A script element; group 1 is its text, empty for the elements that load a {@code src}. */
    private static final Pattern SCRIPT_ELEMENT =
            Pattern.compile("<script\\b[^>]*>(.*?)</script\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern START_TAG = Pattern.compile("<[a-zA-Z][^>]*>");

    /** The name of an event-handler attribute inside a start tag, whatever form its value takes. */
    private static final Pattern HANDLER_NAME = Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);

    /** An event-handler attribute with a quoted value: group 1 is its name, group 2 or 3 its value. */
    private static final Pattern HANDLER =
            Pattern.compile("\\s(on[a-z]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);

    private static final String HASH_PREFIX = "'sha256-";

    @Test
    void policyAllowsExactlyThePagesInlineCodeAndTheSourcesTheDojoClientNeeds() throws IOException {
        String page = COMMENT.matcher(new String(classpathBytes(PAGE), UTF_8)).replaceAll("");
        List<Map.Entry<String, String>> inlineCodeHashes = new ArrayList<>();
        Matcher scripts = SCRIPT_ELEMENT.matcher(page);
        while (scripts.find()) {
            if (!scripts.group(1).isEmpty()) {
                inlineCodeHashes.add(entry("script", hash(scripts.group(1))));
            }
        }
        long handlerNames = 0;
        long quotedHandlers = 0;
        Matcher tags = START_TAG.matcher(SCRIPT_ELEMENT.matcher(page).replaceAll(""));
        while (tags.find()) {
            handlerNames += HANDLER_NAME.matcher(tags.group()).results().count();
            Matcher handlers = HANDLER.matcher(tags.group());
            while (handlers.find()) {
                quotedHandlers++;
                String code = handlers.group(2) != null ? handlers.group(2) : handlers.group(3);
                inlineCodeHashes.add(entry(handlers.group(1).toLowerCase(Locale.ROOT), hash(code)));
            }
        }
        assertThat(quotedHandlers).as("event-handler attributes of %s read with their values", PAGE)
                .isEqualTo(handlerNames);
        assertThat(inlineCodeHashes).as("hashed inline code of %s", PAGE)
                .containsExactlyInAnyOrder(
                        entry("script", ContentSecurityPolicyFilter.INLINE_SCRIPT_HASH),
                        entry("onload", ContentSecurityPolicyFilter.ONLOAD_HANDLER_HASH),
                        entry("onunload", ContentSecurityPolicyFilter.ONUNLOAD_HANDLER_HASH),
                        entry("onclick", ContentSecurityPolicyFilter.ONCLICK_HANDLER_HASH));

        String[] directives = ContentSecurityPolicyFilter.POLICY.split(";", -1);
        Map<String, List<String>> sources = new LinkedHashMap<>();
        for (String directive : directives) {
            List<String> tokens = List.of(directive.trim().split(" +"));
            sources.put(tokens.get(0), tokens.subList(1, tokens.size()));
        }
        assertThat(sources).as(ContentSecurityPolicyFilter.POLICY)
                .hasSize(directives.length)
                .containsOnlyKeys("script-src", "object-src", "base-uri");
        assertThat(sources.get("script-src")).filteredOn(source -> source.startsWith(HASH_PREFIX))
                .containsExactlyInAnyOrderElementsOf(inlineCodeHashes.stream().map(Map.Entry::getValue).toList());
        assertThat(sources.get("script-src")).filteredOn(source -> !source.startsWith(HASH_PREFIX))
                .containsExactlyInAnyOrder("'self'", "'unsafe-eval'", "'unsafe-hashes'");
        assertThat(sources.get("object-src")).containsExactly("'none'");
        assertThat(sources.get("base-uri")).containsExactly("'none'");
        assertThat(ContentSecurityPolicyFilter.POLICY).doesNotContain("'unsafe-inline'");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("htmlContentTypes")
    void htmlContentTypeSetsThePolicyOnceAndPassesTheBodyThrough(String how, ResponseAction setContentType)
            throws ServletException, IOException {
        MockHttpServletResponse response = filter(wrapped -> {
            setContentType.applyTo(wrapped);
            wrapped.getOutputStream().write(BODY);
        });

        assertThat(response.getHeaders(ContentSecurityPolicyFilter.HEADER_NAME))
                .containsExactly(ContentSecurityPolicyFilter.POLICY);
        assertThat(response.getContentAsByteArray()).isEqualTo(BODY);
    }

    static Stream<Arguments> htmlContentTypes() {
        return Stream.of(
                Arguments.of("setContentType text/html",
                        (ResponseAction) response -> response.setContentType("text/html")),
                Arguments.of("setContentType text/html;charset=UTF-8",
                        (ResponseAction) response -> response.setContentType("text/html;charset=UTF-8")),
                Arguments.of("setContentType TEXT/HTML",
                        (ResponseAction) response -> response.setContentType("TEXT/HTML")),
                Arguments.of("setHeader content-type",
                        (ResponseAction) response -> response.setHeader("content-type", "text/html")),
                Arguments.of("addHeader content-type",
                        (ResponseAction) response -> response.addHeader("content-type", "text/html")),
                Arguments.of("set twice", (ResponseAction) response -> {
                    response.setContentType("text/html");
                    response.setHeader("Content-Type", "text/html;charset=UTF-8");
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherContentTypes")
    void otherContentTypeSetsNoPolicy(String how, ResponseAction setContentType) throws ServletException, IOException {
        MockHttpServletResponse response = filter(setContentType);

        assertThat(response.getHeaders(ContentSecurityPolicyFilter.HEADER_NAME)).isEmpty();
    }

    static Stream<Arguments> otherContentTypes() {
        return Stream.of(
                Arguments.of("text/javascript",
                        (ResponseAction) response -> response.setContentType("text/javascript")),
                Arguments.of("text/plain", (ResponseAction) response -> response.setContentType("text/plain")),
                Arguments.of("text/json;charset=UTF-8",
                        (ResponseAction) response -> response.setContentType("text/json;charset=UTF-8")),
                Arguments.of("application/json",
                        (ResponseAction) response -> response.setContentType("application/json")),
                Arguments.of("null", (ResponseAction) response -> response.setContentType(null)),
                Arguments.of("no content type",
                        (ResponseAction) response -> response.setStatus(HttpServletResponse.SC_OK)),
                Arguments.of("text/html in another header",
                        (ResponseAction) response -> response.setHeader("X-Content-Kind", "text/html")));
    }

    /** Runs the filter on a GET of the page with a chain that applies {@code action} to the response it receives. */
    private static MockHttpServletResponse filter(ResponseAction action) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (chainRequest, chainResponse) -> action.applyTo((HttpServletResponse) chainResponse);
        new ContentSecurityPolicyFilter()
                .doFilter(new MockHttpServletRequest("GET", "/services/wmqExample/index.html"), response, chain);
        return response;
    }

    /** The CSP source expression of {@code code}: its UTF-8 bytes hashed with SHA-256, Base64-encoded. */
    private static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(UTF_8));
            return HASH_PREFIX + Base64.getEncoder().encodeToString(digest) + "'";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }

    private static byte[] classpathBytes(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        }
    }

    /** What the filter chain does to the response it is handed. */
    @FunctionalInterface
    interface ResponseAction {

        void applyTo(HttpServletResponse response) throws IOException;
    }
}
