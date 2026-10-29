/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.support.HttpTestClient;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.support.HttpTestClient.Response;

/**
 * Wire-level test of {@link WorkdayCustomerController}, the HTTP trigger of flow {@code add-customer-flow}
 * ({@code adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml}): the listener
 * {@code path="/" allowedMethods="POST"} of line 8, followed by the {@code Put_Customer_Request} script, the
 * {@code wd-connector:invoke Revenue_Management||Put_Customer} call, the {@code Customer_Reference/@Descriptor}
 * extraction and {@code set-payload "Added customer: #[payload]"}.
 *
 * <p>The whole application runs on Undertow on a random port under profile {@code test}, whose fake
 * {@code wday.*} values make the client send the UsernameToken {@code testuser@test_pt1}. Nothing of the project is
 * mocked: an in-process WireMock server stands in for the Workday Revenue_Management v25.0 endpoint, so the
 * controller, service, both mappers, the SOAP client with its WS-Security interceptor, the SC-9 executor with the
 * Workday classifier, and every advice run for real. Every request goes through ST-1 {@link HttpTestClient}, which
 * reads the raw status line, so status code, reason phrase, headers and body bytes are asserted as they leave the
 * server.
 *
 * <p>Covered for {@code POST /}: the happy path with the outgoing SOAP request, for {@code POST} in any letter case,
 * which the Mule listener compared ignoring case ({@code config/ListenerMethodFilter}), the listener defaults (405
 * for every spelling of the methods {@code allowedMethods} excludes, 404 for an unserved path in any letter case of
 * {@code POST}), the unhandled-failure 500 for an unparseable
 * body and for a Workday validation fault (classified {@code OTHER} and rethrown, as the flow declares no exception
 * strategy), and the three approved connector failure modes: one re-authentication and retry before the 502, a 429
 * with Workday's {@code Retry-After} and no retry, and a 503 with no retry after a read timeout. Standard reason
 * phrases are asserted exactly as Undertow writes them; as platform defaults they remain Tier 2A verification points,
 * where a captured fixture would govern both the handler and this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WorkdayCustomerControllerTest {

    /** Revenue_Management v25.0 path of the fake tenant {@code test_pt1} of profile {@code test}. */
    private static final String WORKDAY_PATH = "/ccx/service/test_pt1/Revenue_Management/v25.0";

    private static final String SOAP_ENV_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String TEXT_XML_UTF8 = "text/xml;charset=UTF-8";

    /**
     * {@code wday.read-timeout} of the client under test, deliberately far above WireMock's answer time: this one
     * context serves every case, and on a loaded host WireMock's answer can take seconds, which a tighter budget would
     * turn into a spurious 503 in the cases that expect an answer.
     */
    private static final String WORKDAY_READ_TIMEOUT = "5s";

    /** Delay of the slow stub: longer than {@link #WORKDAY_READ_TIMEOUT}, so the client's read times out. */
    private static final int SLOW_RESPONSE_DELAY_MILLIS = 8_000;

    /**
     * Socket read budget of the test's own HTTP client: covers the server's whole handling of one request, including
     * the Workday read timeout and the lazy creation of the Revenue_Management JAXB context on the first call.
     */
    private static final int CLIENT_READ_TIMEOUT_MILLIS = 30_000;

    private static final String PUT_CUSTOMER_RESPONSE =
            "<wd:Put_Customer_Response xmlns:wd=\"urn:com.workday/bsvc\" wd:version=\"v25.0\">"
                    + "<wd:Customer_Reference wd:Descriptor=\"NAME\">"
                    + "<wd:ID wd:type=\"WID\">0f2b4c6d8e</wd:ID>"
                    + "<wd:ID wd:type=\"Customer_ID\">NAME</wd:ID>"
                    + "</wd:Customer_Reference>"
                    + "</wd:Put_Customer_Response>";

    /** Holds no quote, backslash or control character, so the 500 body carries it unescaped. */
    private static final String VALIDATION_FAULT_STRING = "Validation error occurred. Invalid Customer Category";

    private static final String VALIDATION_FAULT =
            "<SOAP-ENV:Fault xmlns:wd=\"urn:com.workday/bsvc\">"
                    + "<faultcode>wd:Validation_Error</faultcode>"
                    + "<faultstring>" + VALIDATION_FAULT_STRING + "</faultstring>"
                    + "</SOAP-ENV:Fault>";

    /** Started before the context, so the lazy {@code wday.endpoint} supplier finds its base URL. */
    private static final WireMockServer WIREMOCK =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @LocalServerPort
    int port;

    private HttpTestClient http;

    @DynamicPropertySource
    static void workdayProperties(DynamicPropertyRegistry registry) {
        registry.add("wday.endpoint", () -> WIREMOCK.baseUrl() + WORKDAY_PATH);
        registry.add("wday.read-timeout", () -> WORKDAY_READ_TIMEOUT);
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void resetWireMock() {
        WIREMOCK.resetAll();
        http = HttpTestClient.localhost(port).withReadTimeout(CLIENT_READ_TIMEOUT_MILLIS);
    }

    // POST / (add-customer-flow)

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"POST, /", "post, /", "Post, /", "pOsT, /", "post, /?source=test"})
    void postRootAddsCustomer(String method, String target) throws IOException {
        stubWorkday(soapResponse(200, PUT_CUSTOMER_RESPONSE));

        Response response = send(method, target, customerXml());

        assertStatusLine(response, 200, "OK");
        // The flow's template set-payload declares no MIME type, so the Mule listener sent no Content-Type.
        assertThat(response.headers("Content-Type")).as("Content-Type of %s", response).isEmpty();
        assertThat(response.headers("Content-Length")).as("Content-Length of %s", response)
                .containsExactly(String.valueOf("Added customer: NAME".getBytes(UTF_8).length));
        assertThat(response.headers("Transfer-Encoding")).as("Transfer-Encoding of %s", response).isEmpty();
        assertThat(new String(response.body(), UTF_8)).isEqualTo("Added customer: NAME");
        // One Put_Customer carrying the mapped customer.xml values and the PasswordText UsernameToken user@tenant;
        // local names and values only, because the namespace prefixes are the marshaller's choice.
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(WORKDAY_PATH))
                .withHeader("Content-Type", containing("text/xml"))
                .withRequestBody(containing("Put_Customer_Request"))
                .withRequestBody(containing("Customer_Category_ID"))
                .withRequestBody(containing("CUSTOMER_CATEGORY-5"))
                .withRequestBody(containing("Business_Entity_Status_Value_ID"))
                .withRequestBody(containing("ACTIVE"))
                .withRequestBody(containing("testuser@test_pt1"))
                .withRequestBody(containing("PasswordText")));
    }

    // listener defaults (SC-1)

    /**
     * Spellings of methods other than {@code POST}, each with the headers it is sent with: a lower-case
     * {@code options} also carries the headers that would make an {@code OPTIONS} request a CORS pre-flight.
     */
    private static Stream<Arguments> nonPostMethods() {
        Map<String, String> preflightHeaders = new LinkedHashMap<>();
        preflightHeaders.put("Origin", "https://example.invalid");
        preflightHeaders.put("Access-Control-Request-Method", "POST");
        return Stream.of(
                Arguments.of("GET", Map.of()),
                Arguments.of("OPTIONS", Map.of()),
                Arguments.of("get", Map.of()),
                Arguments.of("Get", Map.of()),
                Arguments.of("options", Map.of()),
                Arguments.of("options", preflightHeaders),
                Arguments.of("put", Map.of()),
                Arguments.of("postx", Map.of()),
                Arguments.of("pos", Map.of()));
    }

    @ParameterizedTest(name = "{0} / with headers {1}")
    @MethodSource("nonPostMethods")
    void nonPostMethodsAreRejected(String method, Map<String, String> headers) {
        Response response = http.send(method, "/", headers, new byte[0]);

        assertStatusLine(response, 405, "Method Not Allowed");
        assertMediaType(response, "text/plain");
        assertThat(response.bodyAsString()).isEqualTo("Method not allowed for endpoint: /");
        assertThat(response.headers()).as("headers of %s", response)
                .noneMatch(header -> header.getKey()
                        .regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length()));
        assertNoUpstreamCall();
    }

    @ParameterizedTest(name = "{0} /other")
    @ValueSource(strings = {"POST", "post"})
    void unknownPathIsNotFound(String method) throws IOException {
        Response response = send(method, "/other", customerXml());

        assertStatusLine(response, 404, "Not Found");
        assertMediaType(response, "text/plain");
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: /other");
        assertNoUpstreamCall();
    }

    @Test
    void malformedXmlIsServerError() {
        Response response = send("POST", "/", "<root><Account><CustomerName>NAME</CustomerName>".getBytes(UTF_8));

        assertStatusLine(response, 500, "Internal Server Error");
        assertMediaType(response, "application/json");
        // The message is the JDK parser's own text, so only the APIkit-spaced shape around it is fixed.
        assertThat(response.bodyAsString()).startsWith("{ \"message\": \"").endsWith("\" }");
        assertNoUpstreamCall();
    }

    // Workday failures

    @Test
    void workdaySoapFaultIsServerError() throws IOException {
        stubWorkday(soapResponse(500, VALIDATION_FAULT));

        Response response = send("POST", "/", customerXml());

        assertStatusLine(response, 500, "Internal Server Error");
        assertMediaType(response, "application/json");
        assertThat(response.bodyAsString()).isEqualTo("{ \"message\": \"" + VALIDATION_FAULT_STRING + "\" }");
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(WORKDAY_PATH)));
    }

    @Test
    void authenticationFailureRetriesOnceThenBadGateway() throws IOException {
        // No WWW-Authenticate header, so HttpURLConnection attempts no authentication of its own.
        stubWorkday(aResponse().withStatus(401).withHeader("Content-Type", "text/plain").withBody("Unauthorized"));

        Response response = send("POST", "/", customerXml());

        assertStatusLine(response, 502, "Bad Gateway");
        assertMediaType(response, "application/json");
        // Spring WS's connection error message "<reason> [<status>]" for the retry's "401 Unauthorized" from WireMock.
        assertUpstreamBody(response, "{ \"message\": \"Unauthorized [401]\", \"upstream\": \"Workday\" }");
        assertThat(response.header("Retry-After")).isNull();
        // The rejected attempt and exactly one retry after re-authentication.
        WIREMOCK.verify(2, postRequestedFor(urlEqualTo(WORKDAY_PATH)));
    }

    @Test
    void rateLimitIsReturnedWithRetryAfter() throws IOException {
        stubWorkday(aResponse().withStatus(429)
                .withHeader("Retry-After", "30")
                .withHeader("Content-Type", "text/plain")
                .withBody("Too Many Requests"));

        Response response = send("POST", "/", customerXml());

        assertStatusLine(response, 429, "Too Many Requests");
        assertMediaType(response, "application/json");
        assertThat(response.header("Retry-After")).isEqualTo("30");
        // Spring WS's connection error message "<reason> [<status>]" for WireMock's "429 Too Many Requests".
        assertUpstreamBody(response, "{ \"message\": \"Too Many Requests [429]\", \"upstream\": \"Workday\" }");
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(WORKDAY_PATH)));
    }

    @Test
    void timeoutIsServiceUnavailableWithoutRetry() throws IOException {
        stubWorkday(soapResponse(200, PUT_CUSTOMER_RESPONSE).withFixedDelay(SLOW_RESPONSE_DELAY_MILLIS));

        Response response = send("POST", "/", customerXml());

        assertStatusLine(response, 503, "Service Unavailable");
        assertMediaType(response, "application/json");
        // The first message of the cause chain: Spring WS's WebServiceTemplate prefixes "I/O error: " to the message
        // of the JDK's SocketTimeoutException, "Read timed out". The classifier's neutral "Workday did not respond in
        // time" applies only to a chain that carries no message, which the client tests' upstreamMessage cases cover.
        assertUpstreamBody(response, "{ \"message\": \"I/O error: Read timed out\", \"upstream\": \"Workday\" }");
        assertThat(response.header("Retry-After")).isNull();
        // WireMock journals the request before it starts the delay, and a retry would have been sent before the 503.
        WIREMOCK.verify(1, postRequestedFor(urlEqualTo(WORKDAY_PATH)));
    }

    // helpers

    /** The original test resource {@code customer.xml}: {@code root/Account} with {@code CustomerName} {@code NAME}. */
    private byte[] customerXml() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/customer.xml")) {
            assertThat(in).as("classpath resource /customer.xml").isNotNull();
            return in.readAllBytes();
        }
    }

    /** Sends {@code body} as {@code application/xml}, or an empty body without {@code Content-Type}. */
    private Response send(String method, String path, byte[] body) {
        Map<String, String> headers = body.length == 0 ? Map.of() : Map.of("Content-Type", "application/xml");
        return http.send(method, path, headers, body);
    }

    private static void stubWorkday(ResponseDefinitionBuilder response) {
        WIREMOCK.stubFor(post(urlEqualTo(WORKDAY_PATH)).willReturn(response));
    }

    private static ResponseDefinitionBuilder soapResponse(int status, String bodyContent) {
        return aResponse().withStatus(status)
                .withHeader("Content-Type", TEXT_XML_UTF8)
                .withBody(soapEnvelope(bodyContent));
    }

    /** A SOAP 1.1 envelope whose {@code SOAP-ENV:Body} holds {@code bodyContent}. */
    private static String soapEnvelope(String bodyContent) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"" + SOAP_ENV_NS + "\">"
                + "<SOAP-ENV:Body>" + bodyContent + "</SOAP-ENV:Body>"
                + "</SOAP-ENV:Envelope>";
    }

    private static MediaType contentType(Response response) {
        String header = response.header("Content-Type");
        assertThat(header).as("Content-Type of %s", response).isNotNull();
        return MediaType.parseMediaType(header);
    }

    /** Compares type and subtype only, so the charset parameter the converter may add is ignored. */
    private static void assertMediaType(Response response, String typeSubtype) {
        MediaType mediaType = contentType(response);
        assertThat(mediaType.getType() + "/" + mediaType.getSubtype())
                .as("media type of %s", response)
                .isEqualTo(typeSubtype);
    }

    /** Compares the raw status line, {@code HTTP/1.1 <status> <reason>}, exactly as Undertow wrote it. */
    private static void assertStatusLine(Response response, int status, String reason) {
        assertThat(response.statusLine()).as("status line of %s", response)
                .isEqualTo("HTTP/1.1 " + status + " " + reason);
    }

    /** Compares the SC-9 body byte for byte with {@code expected} encoded as UTF-8. */
    private static void assertUpstreamBody(Response response, String expected) {
        assertThat(response.body()).as("body of %s", response).isEqualTo(expected.getBytes(UTF_8));
    }

    private static void assertNoUpstreamCall() {
        WIREMOCK.verify(0, anyRequestedFor(anyUrl()));
    }
}
