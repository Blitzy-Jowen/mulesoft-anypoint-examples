/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.mulesoft.examples.salesforce_data_retrieval.SalesforceDataRetrievalApplication;
import com.mulesoft.examples.salesforce_data_retrieval.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_data_retrieval.service.SalesforceRetrievalService;
import com.mulesoft.examples.salesforce_data_retrieval.support.HttpTestClient;
import com.sforce.soap.partner.DescribeGlobalResult;
import com.sforce.soap.partner.DescribeGlobalSObjectResult;
import com.sforce.soap.partner.DescribeSObjectResult;
import com.sforce.soap.partner.Field;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the two {@code /} listeners of
 * {@code salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml}, {@code showFormFlow}
 * ({@code allowedMethods="GET"}, xml:8) and {@code salesforceDataRetrievalFlow} ({@code allowedMethods="POST"},
 * xml:26): a CORS pre-flight is answered exactly as a plain {@code OPTIONS} request to its path is, with the
 * listener's 405 {@code text/plain} {@code Method not allowed for endpoint: /} and no {@code Access-Control-*} or
 * {@code Vary} header, and no flow runs, whatever origin it comes from and whatever method it asks for, because the
 * original listeners apply no CORS policy.
 *
 * <p>The whole application runs on Undertow on a random port with only the Salesforce Partner client mocked. The
 * service is a Mockito spy, so it keeps its real behaviour while any flow step a request reaches is recorded.
 * Requests go through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the
 * listener writes them.
 */
@SpringBootTest(classes = SalesforceDataRetrievalApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreflightDispatchListenerTest {

    private static final String ORIGIN = "https://example.invalid";

    /** The form the original {@code SalesforceIdRetrievalIT#testGetData} posts. */
    private static final String ORIGINAL_FORM = "object=user&field=email&searchKey=name&searchValue=mule";

    @MockBean
    private SalesforcePartnerClient client;

    @SpyBean
    private SalesforceRetrievalService service;

    @LocalServerPort
    private int port;

    @BeforeEach
    void stubSalesforce() throws Exception {
        DescribeGlobalSObjectResult userObject = new DescribeGlobalSObjectResult();
        userObject.setName("User");
        userObject.setLabel("User");
        DescribeGlobalResult global = new DescribeGlobalResult();
        global.setSobjects(new DescribeGlobalSObjectResult[] {userObject});
        given(client.describeGlobal()).willReturn(global);

        DescribeSObjectResult user = new DescribeSObjectResult();
        user.setName("User");
        user.setFields(new Field[] {field("Id"), field("Name"), field("Email")});
        given(client.describeSObject(argThat(type -> type != null && type.equalsIgnoreCase("user"))))
                .willReturn(user);

        given(client.queryAll(anyString())).willReturn(records());
    }

    /**
     * Origin of the pre-flight, its requested method, then its {@code Access-Control-Request-Headers}, or
     * {@code null}; every case is sent from each of two origins.
     */
    private static Stream<Arguments> preflights() {
        return Stream.of(ORIGIN, "http://a.example").flatMap(origin -> Stream.of(
                Arguments.of(origin, "GET", null),
                Arguments.of(origin, "POST", null),
                Arguments.of(origin, "PUT", null),
                Arguments.of(origin, "DELETE", null),
                Arguments.of(origin, "PATCH", null),
                Arguments.of(origin, "HEAD", null),
                Arguments.of(origin, "OPTIONS", null),
                Arguments.of(origin, "get", null),
                Arguments.of(origin, "POST", "content-type")));
    }

    @ParameterizedTest(name = "Origin {0}, Access-Control-Request-Method {1}, Access-Control-Request-Headers {2}")
    @MethodSource("preflights")
    void preflightGetsTheListenerMethod405LikeAPlainOptionsRequest(String origin, String requestedMethod,
                                                                   String requestedHeaders) {
        HttpTestClient http = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = http.send("OPTIONS", "/", null, null);

        HttpTestClient.Response preflight = http.send("OPTIONS", "/",
                preflightHeaders(origin, requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 405 Method Not Allowed", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("Method not allowed for endpoint: /".getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(service, client);
    }

    @Test
    void preflightToAnUnservedPathGetsTheNoListener404() {
        HttpTestClient http = HttpTestClient.localhost(port);
        HttpTestClient.Response plain = http.send("OPTIONS", "/favicon.ico", null, null);

        HttpTestClient.Response preflight = http.send("OPTIONS", "/favicon.ico",
                preflightHeaders(ORIGIN, "GET", null), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals("text/plain", preflight.header("Content-Type"), preflight::toString);
        assertArrayEquals("No listener for endpoint: /favicon.ico".getBytes(StandardCharsets.UTF_8),
                preflight.body(), preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(service, client);
    }

    @Test
    void crossOriginRequestsStillRunBothFlowsWithoutCorsHeaders() throws Exception {
        HttpTestClient http = HttpTestClient.localhost(port);

        HttpTestClient.Response form = http.get("/", Map.of("Origin", ORIGIN));

        assertEquals("HTTP/1.1 200 OK", form.statusLine(), form::toString);
        assertEquals("text/html;charset=UTF-8", form.header("Content-Type"), form::toString);
        assertTrue(form.bodyAsString().contains("<option value=\"User\">User</option>"), form::toString);
        assertNoCorsHeaders(form);

        HttpTestClient.Response result = http.post("/", "application/x-www-form-urlencoded", ORIGINAL_FORM,
                Map.of("Origin", ORIGIN));

        assertEquals("HTTP/1.1 200 OK", result.statusLine(), result::toString);
        // The record text is List.toString() over the HashMap records the client returns, untyped as the flow's
        // payload.
        byte[] text = records().toString().getBytes(StandardCharsets.UTF_8);
        assertEquals(List.of(), result.headers("Content-Type"), result::toString);
        assertEquals(List.of(String.valueOf(text.length)), result.headers("Content-Length"), result::toString);
        assertArrayEquals(text, result.body(), result::toString);
        assertNoCorsHeaders(result);

        verify(service).showForm();
        verify(service).query(anyMap());
        verifyNoMoreInteractions(service);
    }

    private static Map<String, String> preflightHeaders(String origin, String requestedMethod,
                                                        String requestedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", origin);
        headers.put("Access-Control-Request-Method", requestedMethod);
        if (requestedHeaders != null) {
            headers.put("Access-Control-Request-Headers", requestedHeaders);
        }
        return headers;
    }

    /** Same status line, same body bytes and the same headers but {@code Date}, compared as sorted lines. */
    private static void assertSameResponse(HttpTestClient.Response expected, HttpTestClient.Response actual) {
        assertEquals(expected.statusLine(), actual.statusLine(), actual::toString);
        assertArrayEquals(expected.body(), actual.body(), actual::toString);
        assertEquals(headerLines(expected), headerLines(actual), actual::toString);
    }

    /** Every header but {@code Date} as {@code name: value} with the name lower-cased, sorted, duplicates kept. */
    private static List<String> headerLines(HttpTestClient.Response response) {
        return response.headers().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("Date"))
                .map(header -> header.getKey().toLowerCase(Locale.ROOT) + ": " + header.getValue())
                .sorted()
                .toList();
    }

    private static void assertNoCorsHeaders(HttpTestClient.Response response) {
        assertTrue(response.headers().stream().map(Map.Entry::getKey).noneMatch(name ->
                        name.regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length())
                                || name.equalsIgnoreCase("Vary")),
                response::toString);
    }

    /** One {@code User} record as the {@code HashMap} the client returns. */
    private static List<Map<String, Object>> records() {
        Map<String, Object> record = new HashMap<>();
        record.put("Email", "one@mule.test");
        record.put("Id", "005A");
        record.put("Name", "Mule One");
        return List.of(record);
    }

    private static Field field(String name) {
        Field field = new Field();
        field.setName(name);
        return field;
    }
}
