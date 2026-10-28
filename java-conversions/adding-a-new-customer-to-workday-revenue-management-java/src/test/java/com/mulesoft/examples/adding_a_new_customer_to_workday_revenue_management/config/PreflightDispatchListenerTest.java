/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.service.WorkdayCustomerService;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.support.HttpTestClient;

/**
 * Wire-level test of {@link PreflightDispatchConfig} on the listener {@code path="/" allowedMethods="POST"}
 * (adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml:8): a CORS pre-flight is
 * answered exactly as a plain {@code OPTIONS} request to its path is, with the listener's 405 {@code text/plain}
 * {@code Method not allowed for endpoint: /} and no {@code Access-Control-*} or {@code Vary} header, and the flow
 * never runs, whatever origin the pre-flight comes from and whatever method it asks for, because the original
 * listener applies no CORS policy.
 *
 * <p>The whole application runs on Undertow on a random port under profile {@code test}, set up as
 * {@code controller/WorkdayCustomerControllerTest} sets it up: an in-process WireMock server stands in for the
 * Workday Revenue_Management v25.0 endpoint. {@link WorkdayCustomerService} is a Mockito spy, so it keeps its real
 * behaviour while any flow step a request reaches is recorded; WireMock's journal shows whether Workday was called.
 * Requests go through ST-1 {@link HttpTestClient}, so the status line, headers and body bytes are asserted as the
 * listener writes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PreflightDispatchListenerTest {

    private static final String ORIGIN = "https://example.invalid";

    /** Revenue_Management v25.0 path of the fake tenant {@code test_pt1} of profile {@code test}. */
    private static final String WORKDAY_PATH = "/ccx/service/test_pt1/Revenue_Management/v25.0";

    /**
     * Socket read budget of the test's own HTTP client: covers the lazy creation of the Revenue_Management JAXB
     * context on the first Workday call.
     */
    private static final int CLIENT_READ_TIMEOUT_MILLIS = 30_000;

    private static final String PUT_CUSTOMER_RESPONSE =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                    + "<SOAP-ENV:Body>"
                    + "<wd:Put_Customer_Response xmlns:wd=\"urn:com.workday/bsvc\" wd:version=\"v25.0\">"
                    + "<wd:Customer_Reference wd:Descriptor=\"NAME\">"
                    + "<wd:ID wd:type=\"Customer_ID\">NAME</wd:ID>"
                    + "</wd:Customer_Reference>"
                    + "</wd:Put_Customer_Response>"
                    + "</SOAP-ENV:Body>"
                    + "</SOAP-ENV:Envelope>";

    /** Started before the context, so the lazy {@code wday.endpoint} supplier finds its base URL. */
    private static final WireMockServer WIREMOCK =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @SpyBean
    private WorkdayCustomerService service;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void workdayProperties(DynamicPropertyRegistry registry) {
        registry.add("wday.endpoint", () -> WIREMOCK.baseUrl() + WORKDAY_PATH);
        registry.add("wday.read-timeout", () -> "5s");
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void resetWireMock() {
        WIREMOCK.resetAll();
    }

    /**
     * Origin of the pre-flight, its requested method, then its {@code Access-Control-Request-Headers}, or
     * {@code null}; every case is sent from each of two origins.
     */
    private static Stream<Arguments> preflights() {
        return Stream.of(ORIGIN, "http://a.example").flatMap(origin -> Stream.of(
                Arguments.of(origin, "POST", null),
                Arguments.of(origin, "GET", null),
                Arguments.of(origin, "PUT", null),
                Arguments.of(origin, "DELETE", null),
                Arguments.of(origin, "PATCH", null),
                Arguments.of(origin, "OPTIONS", null),
                Arguments.of(origin, "post", null),
                Arguments.of(origin, "POST", "content-type")));
    }

    @ParameterizedTest(name = "Origin {0}, Access-Control-Request-Method {1}, Access-Control-Request-Headers {2}")
    @MethodSource("preflights")
    void preflightGetsTheListenerMethod405LikeAPlainOptionsRequest(String origin, String requestedMethod,
                                                                   String requestedHeaders) {
        HttpTestClient.Response plain = client().send("OPTIONS", "/", null, null);

        HttpTestClient.Response preflight = client().send("OPTIONS", "/",
                preflightHeaders(origin, requestedMethod, requestedHeaders), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 405 Method Not Allowed", preflight.statusLine(), preflight::toString);
        assertEquals(List.of("text/plain"), preflight.headers("Content-Type"), preflight::toString);
        assertArrayEquals("Method not allowed for endpoint: /".getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(service);
        WIREMOCK.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void preflightToAnUnservedPathGetsTheNoListener404() {
        HttpTestClient.Response plain = client().send("OPTIONS", "/other", null, null);

        HttpTestClient.Response preflight = client().send("OPTIONS", "/other",
                preflightHeaders(ORIGIN, "POST", null), null);

        assertSameResponse(plain, preflight);
        assertEquals("HTTP/1.1 404 Not Found", preflight.statusLine(), preflight::toString);
        assertEquals(List.of("text/plain"), preflight.headers("Content-Type"), preflight::toString);
        assertArrayEquals("No listener for endpoint: /other".getBytes(StandardCharsets.UTF_8), preflight.body(),
                preflight::toString);
        assertNoCorsHeaders(preflight);
        verifyNoInteractions(service);
        WIREMOCK.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void crossOriginPostStillRunsTheFlowWithoutCorsHeaders() throws Exception {
        WIREMOCK.stubFor(post(urlEqualTo(WORKDAY_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/xml;charset=UTF-8")
                .withBody(PUT_CUSTOMER_RESPONSE)));
        byte[] customer = customerXml();
        Map<String, String> crossOriginHeaders = new LinkedHashMap<>();
        crossOriginHeaders.put("Origin", ORIGIN);
        crossOriginHeaders.put("Content-Type", "application/xml");

        HttpTestClient.Response sameOrigin = client().send("POST", "/", Map.of("Content-Type", "application/xml"),
                customer);
        HttpTestClient.Response crossOrigin = client().send("POST", "/", crossOriginHeaders, customer);

        assertSameResponse(sameOrigin, crossOrigin);
        assertEquals("HTTP/1.1 200 OK", crossOrigin.statusLine(), crossOrigin::toString);
        assertArrayEquals("Added customer: NAME".getBytes(StandardCharsets.UTF_8), crossOrigin.body(),
                crossOrigin::toString);
        assertNoCorsHeaders(crossOrigin);
        verify(service, times(2)).addCustomer(any());
        verifyNoMoreInteractions(service);
        WIREMOCK.verify(2, postRequestedFor(urlEqualTo(WORKDAY_PATH)));
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port).withReadTimeout(CLIENT_READ_TIMEOUT_MILLIS);
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

    /** The original example's test resource {@code src/test/resources/customer.xml}, as its file bytes. */
    private static byte[] customerXml() {
        try (InputStream in = PreflightDispatchListenerTest.class.getResourceAsStream("/customer.xml")) {
            if (in == null) {
                throw new IllegalStateException("customer.xml is not on the test classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read customer.xml", e);
        }
    }
}
