/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.get_customer_list_from_netsuite.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.mulesoft.examples.get_customer_list_from_netsuite.client.NetsuiteTokenProvider;
import com.mulesoft.examples.get_customer_list_from_netsuite.service.NetsuiteCustomerService;
import com.mulesoft.examples.get_customer_list_from_netsuite.support.HttpTestClient;

/**
 * Wire-level endpoint test of the {@code /customers} listener of {@code get-customer-list-from-netsuiteFlow}
 * ({@code get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml}).
 *
 * <p>The whole application runs on a random port and every request goes through ST-1 {@link HttpTestClient}, so the
 * raw status line (reason phrase included), the headers and the body bytes are asserted as they leave Undertow.
 * Nothing is mocked inside the application: controller, service, mapper, template, REST client, token provider,
 * SC-9 executor and exception handlers all run, against an in-process WireMock NetSuite whose base URL and signing
 * key are registered per run, so no credential and no host other than localhost is ever used.
 *
 * <p>Covered: the original's branches (customers found, none found, the expression filter's rejection), every
 * method on the listener, the page reply's {@code Cache-Control}, {@code X-Content-Type-Options} and
 * {@code X-Frame-Options} headers and their absence from the filter rejection, the SuiteQL literal escaping of a
 * quote, the no-listener 404 ({@code /customers} with {@code ;} segment parameters included, which never reaches
 * NetSuite), and the approved connector
 * failure modes with their exact upstream call counts (one re-authentication on 401, no retry on 429 or a timeout)
 * next to a failure that keeps the platform default 500. Every expected page is written by hand from the template
 * and the fixture rows, never computed by the code under test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NetsuiteCustomerControllerTest {

    private static final String SUITEQL_PATH = "/services/rest/query/v1/suiteql";

    private static final String TOKEN_PATH = "/services/rest/auth/oauth2/v1/token";

    private static final String ACCESS_TOKEN = "test-access-token";

    private static final String TEXT_HTML_UTF8 = "text/html;charset=UTF-8";

    /** The headers a page reply carries besides Content-Type, each with its exact value; no other reply sends them. */
    private static final Map<String, String> PAGE_HEADERS = Map.of(
            "Cache-Control", "no-store",
            "X-Content-Type-Options", "nosniff",
            "X-Frame-Options", "DENY");

    /** The SuiteQL page size {@code NetsuiteRestClient} sends as {@code limit} on every page. */
    private static final String PAGE_LIMIT = "1000";

    /** The heading the original {@code GetCustomersListFromNetsuiteIT} looks for. */
    private static final String HEADING = "<h1>Netsuite Customer List</h1>";

    /** The original test's request: {@code LAST_NAME_VALUE = "a"}. */
    private static final String CUSTOMERS_A = "/customers?lastName=a";

    private static final String TEMPLATE = "customer/index.html";

    /**
     * The rows of {@code netsuite/suiteql-customers.json} as the DataWeave 1.0 XML writer lays them out (tab
     * indentation, LF line ends), without the XML declaration line the template expression drops and without a line
     * feed after the last line. Cells are last name, first name, email, in the fixture's row order.
     */
    private static final String EXPECTED_TBODY =
              "<div>\n"
            + "\t<line>\n\t\t<tr>\n"
            + "\t\t\t<td>Abbott</td>\n\t\t\t<td>Byron</td>\n\t\t\t<td>ab@example.com</td>\n"
            + "\t\t</tr>\n\t</line>\n"
            + "\t<line>\n\t\t<tr>\n"
            + "\t\t\t<td>Norman</td>\n\t\t\t<td>Gustaffson</td>\n\t\t\t<td>gustaff@example.com</td>\n"
            + "\t\t</tr>\n\t</line>\n"
            + "\t<line>\n\t\t<tr>\n"
            + "\t\t\t<td>Analytics</td>\n\t\t\t<td>Angels</td>\n\t\t\t<td>aa@example.com</td>\n"
            + "\t\t</tr>\n\t</line>\n"
            + "\t<line>\n\t\t<tr>\n"
            + "\t\t\t<td>McGill</td>\n\t\t\t<td>Stuart</td>\n\t\t\t<td>mcgill@example.com</td>\n"
            + "\t\t</tr>\n\t</line>\n"
            + "</div>";

    /** The DataWeave {@code otherwise} branch: the single row of an empty result, laid out as above. */
    private static final String NO_CUSTOMERS_TBODY =
              "<div>\n"
            + "\t<line>\n\t\t<tr>\n\t\t\t<td>No customers found</td>\n\t\t</tr>\n\t</line>\n"
            + "</div>";

    private static final String UNAUTHORIZED_BODY =
            "{\"type\":\"https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.2\",\"title\":\"Unauthorized\","
            + "\"status\":401,\"o:errorDetails\":[{\"detail\":\"Invalid login attempt.\","
            + "\"o:errorCode\":\"INVALID_LOGIN\"}]}";

    private static final String RATE_LIMITED_BODY =
            "{\"type\":\"https://www.rfc-editor.org/rfc/rfc6585.html#section-4\",\"title\":\"Too Many Requests\","
            + "\"status\":429,\"o:errorDetails\":[{\"detail\":\"Request limit exceeded.\","
            + "\"o:errorCode\":\"SSS_REQUEST_LIMIT_EXCEEDED\"}]}";

    private static final String BAD_REQUEST_BODY =
            "{\"type\":\"https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.1\",\"title\":\"Bad Request\","
            + "\"status\":400,\"o:errorDetails\":[{\"detail\":\"Invalid search query.\","
            + "\"o:errorCode\":\"INVALID_PARAMETER\"}]}";

    private static final String SERVER_ERROR_BODY =
            "{\"type\":\"https://www.rfc-editor.org/rfc/rfc9110.html#section-15.6.1\","
            + "\"title\":\"Internal Server Error\","
            + "\"status\":500,\"o:errorDetails\":[{\"detail\":\"An unexpected error occurred.\","
            + "\"o:errorCode\":\"UNEXPECTED_ERROR\"}]}";

    private static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    /** A throwaway RSA key the token provider signs its client assertions with; WireMock never checks them. */
    private static final Path PRIVATE_KEY;

    // Static, so NetSuite and the key exist before the Spring context resolves the dynamic properties.
    static {
        PRIVATE_KEY = writePrivateKey();
        WIRE_MOCK.start();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private NetsuiteTokenProvider tokenProvider;

    @DynamicPropertySource
    static void netsuiteProperties(DynamicPropertyRegistry registry) {
        registry.add("netsuite.base-url", WIRE_MOCK::baseUrl);
        registry.add("netsuite.oauth.private-key-path", () -> PRIVATE_KEY.toAbsolutePath().toString());
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    /**
     * Restores a NetSuite that only issues tokens and drops the token the previous test cached, so each test's
     * token and SuiteQL counts start from zero.
     */
    @BeforeEach
    void resetNetsuite() {
        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(jsonResponse(200,
                "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"token_type\":\"bearer\",\"expires_in\":\"3600\"}")));
        tokenProvider.evict();
    }

    @Test
    void getWithLastNameRendersTheMatchingCustomersIntoTheTemplate() throws IOException {
        stubSuiteQl(okJson("netsuite/suiteql-customers.json"));

        HttpTestClient.Response response = client().get(CUSTOMERS_A);

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.reason()).isEqualTo("OK");
        assertThat(response.header("Content-Type")).isEqualTo(TEXT_HTML_UTF8);
        assertPageHeaders(response);
        String expected = expectedPage(EXPECTED_TBODY);
        assertThat(response.bodyAsString()).isEqualTo(expected).contains(HEADING);
        assertThat(response.body()).isEqualTo(expected.getBytes(UTF_8));
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL_PATH)));
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL_PATH))
                .withQueryParam("limit", equalTo(PAGE_LIMIT))
                .withQueryParam("offset", equalTo("0"))
                .withHeader("Prefer", equalTo("transient"))
                .withHeader("Authorization", equalTo("Bearer " + ACCESS_TOKEN))
                .withRequestBody(matchingJsonPath("$.q", equalTo("SELECT email, firstname, lastname FROM customer"
                        + " WHERE LOWER(lastname) LIKE LOWER('a%') ORDER BY lastname ASC"))));
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    /** The listener declares no {@code allowedMethods}, so every method runs the flow and answers as GET does. */
    @ParameterizedTest(name = "{0} /customers?lastName=a answers as GET does")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void everyMethodWithTheSameQueryAnswersAsGetDoes(String method) throws IOException {
        stubSuiteQl(okJson("netsuite/suiteql-customers.json"));

        HttpTestClient.Response get = client().get(CUSTOMERS_A);
        HttpTestClient.Response other = client().send(method, CUSTOMERS_A, Map.of(), new byte[0]);

        assertThat(get.status()).as(get.toString()).isEqualTo(200);
        assertThat(get.bodyAsString()).isEqualTo(expectedPage(EXPECTED_TBODY));
        assertThat(other.status()).as(other.toString()).isEqualTo(get.status());
        assertThat(other.reason()).isEqualTo(get.reason());
        assertThat(other.header("Content-Type")).isEqualTo(get.header("Content-Type")).isEqualTo(TEXT_HTML_UTF8);
        assertPageHeaders(get);
        assertPageHeaders(other);
        assertThat(other.body()).isEqualTo(get.body());
        WIRE_MOCK.verify(2, postRequestedFor(urlPathEqualTo(SUITEQL_PATH))
                .withRequestBody(matchingJsonPath("$.q", containing("LIKE LOWER('a%')"))));
    }

    static Stream<Arguments> requestsTheFilterRejects() {
        return Stream.of(
                Arguments.of("GET", "/customers", null, null),
                Arguments.of("GET", "/customers?lastName", null, null),
                // A form field is not a query parameter: Mule's http.query.params held the URL query only.
                Arguments.of("POST", "/customers", "application/x-www-form-urlencoded", "lastName=a"));
    }

    /** The expression filter, without {@code throwOnUnaccepted}, ends the flow with no payload: 200, empty body. */
    @ParameterizedTest(name = "{0} {1}, body: {3}")
    @MethodSource("requestsTheFilterRejects")
    void requestWithoutALastNameQueryValueIsAnEmpty200AndNeverReachesNetsuite(String method, String path,
            String contentType, String body) {
        Map<String, String> headers = contentType == null ? Map.of() : Map.of("Content-Type", contentType);

        HttpTestClient.Response response =
                client().send(method, path, headers, body == null ? null : body.getBytes(UTF_8));

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.reason()).isEqualTo("OK");
        assertThat(response.header("Content-Type")).isNull();
        assertNoPageHeaders(response);
        assertThat(response.body()).isEmpty();
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo(SUITEQL_PATH)));
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    /** An empty value passes the filter ({@code != null}) and, as the original's {@code like '%'}, matches all. */
    @Test
    void emptyLastNamePassesTheFilterAndQueriesEveryCustomer() throws IOException {
        stubSuiteQl(okJson("netsuite/suiteql-customers.json"));

        HttpTestClient.Response response = client().get("/customers?lastName=");

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.reason()).isEqualTo("OK");
        assertThat(response.header("Content-Type")).isEqualTo(TEXT_HTML_UTF8);
        assertThat(response.bodyAsString()).isEqualTo(expectedPage(EXPECTED_TBODY));
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL_PATH))
                .withRequestBody(matchingJsonPath("$.q", containing("LIKE LOWER('%')"))));
    }

    @Test
    void noMatchingCustomerRendersTheNoCustomersRow() throws IOException {
        stubSuiteQl(okJson("netsuite/suiteql-empty.json"));

        HttpTestClient.Response response = client().get("/customers?lastName=zzz");

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.reason()).isEqualTo("OK");
        assertThat(response.header("Content-Type")).isEqualTo(TEXT_HTML_UTF8);
        assertThat(response.bodyAsString())
                .isEqualTo(expectedPage(NO_CUSTOMERS_TBODY))
                .contains("<td>No customers found</td>", HEADING)
                .doesNotContain("Abbott", "McGill");
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL_PATH))
                .withRequestBody(matchingJsonPath("$.q", containing("LIKE LOWER('zzz%')"))));
    }

    /** A quote in the value is doubled, so it stays inside the SuiteQL literal instead of ending it. */
    @Test
    void quoteInTheLastNameIsEscapedInsideTheSuiteQlLiteral() throws IOException {
        stubSuiteQl(okJson("netsuite/suiteql-empty.json"));

        HttpTestClient.Response response = client().get("/customers?lastName=O%27Brien");

        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        assertThat(response.reason()).isEqualTo("OK");
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL_PATH))
                .withRequestBody(matchingJsonPath("$.q", containing("LIKE LOWER('O''Brien%')"))));
    }

    @Test
    void unservedPathIsTheNoListener404() {
        HttpTestClient.Response response = client().get("/unknown");

        assertThat(response.status()).as(response.toString()).isEqualTo(404);
        assertThat(response.reason()).isEqualTo("Not Found");
        assertMediaType(response, MediaType.TEXT_PLAIN);
        assertNoPageHeaders(response);
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: /unknown");
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo(SUITEQL_PATH)));
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    /** Request method, then {@code /customers} followed by {@code ;} segment parameters. */
    static Stream<Arguments> pathParameterRequests() {
        return Stream.of("GET", "POST", "HEAD", "OPTIONS").flatMap(method ->
                Stream.of("/customers;x=1", "/customers;jsessionid=ABC", "/customers;")
                        .map(path -> Arguments.of(method, path)));
    }

    /**
     * A path carrying {@code ;} segment parameters is not the listener's path: the Mule listener compared the raw
     * path, query removed, literally, so every method gets the no-listener 404 naming the path with its parameters,
     * and the flow never runs ({@code config/ListenerPathConfig}). NetSuite, stubbed to answer the search, receives
     * no request at all, not even for a token. A {@code HEAD} reply carries the headers without the body.
     *
     * @param method the request method; {@code POST} carries a {@code text/plain} body
     * @param path   {@code /customers} followed by its segment parameters, requested with {@code ?lastName=a}
     */
    @ParameterizedTest(name = "{0} {1}?lastName=a")
    @MethodSource("pathParameterRequests")
    void pathWithSegmentParametersIsTheNoListener404AndNeverReachesNetsuite(String method, String path)
            throws IOException {
        stubSuiteQl(okJson("netsuite/suiteql-customers.json"));
        boolean withBody = "POST".equals(method);

        HttpTestClient.Response response = client().send(method, path + "?lastName=a",
                withBody ? Map.of("Content-Type", "text/plain") : Map.of(),
                withBody ? "ping".getBytes(UTF_8) : null);

        byte[] body = ("No listener for endpoint: " + path).getBytes(UTF_8);
        assertThat(response.statusLine()).as(response.toString()).isEqualTo("HTTP/1.1 404 Not Found");
        assertThat(response.headers("Content-Type")).as(response.toString()).containsExactly("text/plain");
        assertNoPageHeaders(response);
        if ("HEAD".equals(method)) {
            assertThat(response.headers("Content-Length")).as(response.toString())
                    .containsExactly(String.valueOf(body.length));
            assertThat(response.body()).as(response.toString()).isEmpty();
        } else {
            assertThat(response.body()).as(response.toString()).isEqualTo(body);
        }
        WIRE_MOCK.verify(0, anyRequestedFor(anyUrl()));
    }

    /** A 401 evicts the token and retries once with a new one; rejected again, the attempt ends in a 502. */
    @Test
    void tokenRejectedTwiceIsReauthenticatedOnceAndAnswered502() {
        stubSuiteQl(jsonResponse(401, UNAUTHORIZED_BODY));

        HttpTestClient.Response response = client().get(CUSTOMERS_A);

        assertThat(response.status()).as(response.toString()).isEqualTo(502);
        assertThat(response.reason()).isEqualTo("Bad Gateway");
        assertMediaType(response, MediaType.APPLICATION_JSON);
        assertNoPageHeaders(response);
        assertThat(response.bodyAsString())
                .isEqualTo("{ \"message\": \"Invalid login attempt.\", \"upstream\": \"NetSuite\" }");
        WIRE_MOCK.verify(2, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        WIRE_MOCK.verify(2, postRequestedFor(urlPathEqualTo(SUITEQL_PATH)));
    }

    @Test
    void rateLimitIsNotRetriedAndCopiesRetryAfter() {
        stubSuiteQl(jsonResponse(429, RATE_LIMITED_BODY).withHeader("Retry-After", "30"));

        HttpTestClient.Response response = client().get(CUSTOMERS_A);

        assertThat(response.status()).as(response.toString()).isEqualTo(429);
        assertThat(response.reason()).isEqualTo("Too Many Requests");
        assertThat(response.header("Retry-After")).isEqualTo("30");
        assertMediaType(response, MediaType.APPLICATION_JSON);
        assertNoPageHeaders(response);
        assertThat(response.bodyAsString())
                .isEqualTo("{ \"message\": \"Request limit exceeded.\", \"upstream\": \"NetSuite\" }");
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL_PATH)));
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    /** The answer arrives after the test profile's 2 s {@code netsuite.read-timeout}; a timeout is never retried. */
    @Test
    void upstreamTimeoutIsNeverRetriedAndAnswered503() throws IOException {
        stubSuiteQl(okJson("netsuite/suiteql-customers.json").withFixedDelay(3000));

        HttpTestClient.Response response = client().get(CUSTOMERS_A);

        assertThat(response.status()).as(response.toString()).isEqualTo(503);
        assertThat(response.reason()).isEqualTo("Service Unavailable");
        assertMediaType(response, MediaType.APPLICATION_JSON);
        assertNoPageHeaders(response);
        assertThat(response.bodyAsString()).startsWith("{ \"message\": \"").contains("\"upstream\": \"NetSuite\"");
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL_PATH)));
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    /**
     * NetSuite failures no approved mode covers: an invalid query's 400 and a 500 with NetSuite's REST error body,
     * and a 503 without a body, which is still not a timeout or connectivity failure. Each comes with the message
     * of the platform default 500 it gets: the status code and reason phrase alone.
     */
    static Stream<Arguments> unclassifiedUpstreamFailures() {
        return Stream.of(
                Arguments.of(jsonResponse(400, BAD_REQUEST_BODY), "400 Bad Request"),
                Arguments.of(jsonResponse(500, SERVER_ERROR_BODY), "500 Internal Server Error"),
                Arguments.of(aResponse().withStatus(503), "503 Service Unavailable"));
    }

    /**
     * Any other NetSuite failure is rethrown unchanged: the flow declares no exception strategy, so it gets the
     * platform default 500, whose message never names NetSuite's address or the request path.
     */
    @ParameterizedTest(name = "NetSuite answers {1}")
    @MethodSource("unclassifiedUpstreamFailures")
    void otherUpstreamFailureIsNotRetriedAndKeepsThePlatformDefault500(ResponseDefinitionBuilder answer,
            String message) {
        stubSuiteQl(answer);

        HttpTestClient.Response response = client().get(CUSTOMERS_A);

        assertThat(response.status()).as(response.toString()).isEqualTo(500);
        assertThat(response.reason()).isEqualTo("Internal Server Error");
        assertMediaType(response, MediaType.APPLICATION_JSON);
        assertNoPageHeaders(response);
        assertThat(response.bodyAsString()).isEqualTo("{ \"message\": \"" + message + "\" }")
                .doesNotContain(WIRE_MOCK.baseUrl(), "/services/rest");
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(SUITEQL_PATH)));
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    /** Stubs the SuiteQL first page exactly as {@code NetsuiteRestClient} requests it. */
    private static void stubSuiteQl(ResponseDefinitionBuilder response) {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(SUITEQL_PATH))
                .withQueryParam("limit", equalTo(PAGE_LIMIT))
                .withQueryParam("offset", equalTo("0"))
                .withHeader("Prefer", equalTo("transient"))
                .withRequestBody(matchingJsonPath("$.q"))
                .willReturn(response));
    }

    /** A 200 {@code application/json} answer whose body is the given classpath resource. */
    private static ResponseDefinitionBuilder okJson(String classpathResource) throws IOException {
        return jsonResponse(200, new ClassPathResource(classpathResource).getContentAsString(UTF_8));
    }

    private static ResponseDefinitionBuilder jsonResponse(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private HttpTestClient client() {
        return HttpTestClient.localhost(port);
    }

    /** The carried template with its one expression replaced by {@code tbody}, as {@code parse-template} renders it. */
    private static String expectedPage(String tbody) throws IOException {
        String template = new ClassPathResource(TEMPLATE).getContentAsString(UTF_8);
        String expression = NetsuiteCustomerService.TEMPLATE_EXPRESSION;
        assertThat(template.indexOf(expression)).as("occurrences of %s in %s", expression, TEMPLATE)
                .isNotNegative()
                .isEqualTo(template.lastIndexOf(expression));
        return template.replace(expression, tbody);
    }

    /** Asserts that every one of {@link #PAGE_HEADERS} is sent exactly once, with its exact value. */
    private static void assertPageHeaders(HttpTestClient.Response response) {
        PAGE_HEADERS.forEach((name, value) ->
                assertThat(response.headers(name)).as("%s of %s", name, response).containsExactly(value));
    }

    /** Asserts that none of {@link #PAGE_HEADERS} is sent. */
    private static void assertNoPageHeaders(HttpTestClient.Response response) {
        PAGE_HEADERS.keySet().forEach(name ->
                assertThat(response.headers(name)).as("%s of %s", name, response).isEmpty());
    }

    /** Compares type and subtype only, since the server may add a charset parameter. */
    private static void assertMediaType(HttpTestClient.Response response, MediaType expected) {
        String contentType = response.header("Content-Type");
        assertThat(contentType).as("Content-Type of %s", response.statusLine()).isNotNull();
        MediaType actual = MediaType.parseMediaType(contentType);
        assertThat(actual.getType() + "/" + actual.getSubtype())
                .isEqualTo(expected.getType() + "/" + expected.getSubtype());
    }

    private static Path writePrivateKey() {
        KeyPair keyPair;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keyPair = generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(US_ASCII)).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        try {
            Path directory = Files.createTempDirectory("netsuite-controller-test");
            Path file = directory.resolve("private-key.pem");
            Files.writeString(file, pem, US_ASCII);
            // deleteOnExit runs in reverse registration order: the file first, then its directory.
            directory.toFile().deleteOnExit();
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
