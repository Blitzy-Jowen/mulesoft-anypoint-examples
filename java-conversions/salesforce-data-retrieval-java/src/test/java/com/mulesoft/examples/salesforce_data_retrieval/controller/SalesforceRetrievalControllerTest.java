/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_data_retrieval.controller;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;

import com.mulesoft.examples.salesforce_data_retrieval.SalesforceDataRetrievalApplication;
import com.mulesoft.examples.salesforce_data_retrieval.client.SalesforcePartnerClient;
import com.mulesoft.examples.salesforce_data_retrieval.client.UpstreamSystem;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamRateLimitException;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamUnavailableException;
import com.mulesoft.examples.salesforce_data_retrieval.mapper.ParseTemplateRenderer;
import com.mulesoft.examples.salesforce_data_retrieval.mapper.QueryResultFormatter;
import com.mulesoft.examples.salesforce_data_retrieval.mapper.SObjectOptionsMapper;
import com.mulesoft.examples.salesforce_data_retrieval.support.HttpTestClient;
import com.mulesoft.examples.salesforce_data_retrieval.support.HttpTestClient.Response;
import com.sforce.soap.partner.DescribeGlobalResult;
import com.sforce.soap.partner.DescribeGlobalSObjectResult;
import com.sforce.soap.partner.DescribeSObjectResult;
import com.sforce.soap.partner.Field;
import com.sforce.ws.ConnectionException;

/**
 * Wire-level test of {@link SalesforceRetrievalController}, the two {@code /} listeners of
 * {@code salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml}: {@code showFormFlow} (GET) and
 * {@code salesforceDataRetrievalFlow} (POST, with the catch exception strategy answering
 * {@code Invalid Salesforce query.}).
 *
 * <p>The whole application runs on Undertow on a random port with only the Salesforce Partner client mocked, so the
 * controller, service, mappers, template and every advice run for real. Every request goes through ST-1
 * {@link HttpTestClient}, which reads the raw status line, so the status code, the reason phrase, the
 * {@code Content-Type} and the body bytes are asserted exactly as they leave the server.
 *
 * <p>Covered: the form page and the record text, both branches of the original {@code field} ternary, the catch
 * branch for hostile, missing and non-form input (none of which reaches Salesforce), the platform-default 404, 405
 * and 500, {@code GET} and {@code POST} matched ignoring case as the Mule listener matched {@code allowedMethods}
 * while every other spelling keeps the 405, and the approved connector failure modes, which take precedence over
 * both the GET flow's 500 and the POST flow's catch-all. The expected page and record text are computed by the real
 * mapper and renderer beans from the stubbed describe and query results; their own byte layout is fixed by the
 * mapper tests.
 */
@SpringBootTest(classes = SalesforceDataRetrievalApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SalesforceRetrievalControllerTest {

    private static final String FORM_URLENCODED = "application/x-www-form-urlencoded";

    private static final String TEXT_HTML_UTF8 = "text/html;charset=UTF-8";

    private static final String TEXT_PLAIN_UTF8 = "text/plain;charset=UTF-8";

    private static final String TEMPLATE = "retrieval/index.html";

    private static final String PAYLOAD_EXPRESSION = "#[message.payload]";

    private static final String INVALID_QUERY = "Invalid Salesforce query.";

    /** The pattern the original {@code SalesforceIdRetrievalIT#testDisplayData} looks for in the form page. */
    private static final Pattern OPTION_REGEX = Pattern.compile("<option\\svalue=\\\".*\\\">.*<\\/option>");

    /** The form the original {@code SalesforceIdRetrievalIT#testGetData} posts. */
    private static final String ORIGINAL_FORM = "object=user&field=email&searchKey=name&searchValue=mule";

    /** The original MEL concatenation of xml:27 for {@link #ORIGINAL_FORM}. */
    private static final String ORIGINAL_SOQL = "SELECT id, name ,email from user where name like '%mule%'";

    @MockBean
    SalesforcePartnerClient client;

    @Autowired
    SObjectOptionsMapper optionsMapper;

    @Autowired
    ParseTemplateRenderer renderer;

    @Autowired
    QueryResultFormatter formatter;

    @LocalServerPort
    int port;

    private HttpTestClient http;

    @BeforeEach
    void setUp() throws Exception {
        http = HttpTestClient.localhost(port);
        given(client.describeGlobal()).willReturn(describeGlobal());
        given(client.describeSObject(argThat(type -> type != null && type.equalsIgnoreCase("user"))))
                .willReturn(describeUser());
        given(client.queryAll(anyString())).willReturn(records());
    }

    // ---------------------------------------------------------------- GET / (showFormFlow)

    @Test
    void getRendersTheFormWithOneOptionPerDescribedObject() throws Exception {
        Response response = http.get("/");

        assertStatusLine(response, 200, "OK");
        assertThat(response.header("Content-Type")).isEqualTo(TEXT_HTML_UTF8);
        assertThat(response.body()).isEqualTo(expectedFormPage().getBytes(UTF_8));
        String page = response.bodyAsString();
        assertThat(page).contains(
                "<option value=\"User\">User</option>",
                "<option value=\"Account\">Account</option>",
                "<option value=\"Contact\">Contact</option>");
        assertThat(OPTION_REGEX.matcher(page).find()).as("page holds at least one option tag").isTrue();
        assertThat(page).doesNotContain("#[");
        verify(client).describeGlobal();
        verify(client, never()).queryAll(any());
    }

    @Test
    void getIgnoresTheAcceptHeaderAsTheMuleListenerDid() throws Exception {
        Response response = http.get("/", Map.of("Accept", "application/json"));

        assertStatusLine(response, 200, "OK");
        assertThat(response.header("Content-Type")).isEqualTo(TEXT_HTML_UTF8);
        assertThat(response.body()).isEqualTo(expectedFormPage().getBytes(UTF_8));
    }

    @Test
    void getAnswersAnUnhandledDescribeFailureWithThePlatformDefault500() throws Exception {
        given(client.describeGlobal()).willThrow(new ConnectionException("boom"));

        Response response = http.get("/");

        assertStatusLine(response, 500, "Internal Server Error");
        assertContentType(response, "application/json");
        assertThat(response.bodyAsString()).isEqualTo("{ \"message\": \"boom\" }");
        verify(client).describeGlobal();
    }

    // ---------------------------------------------------------------- POST / (salesforceDataRetrievalFlow)

    @Test
    void postRunsTheOriginalQueryAndReturnsTheRecordText() throws Exception {
        Response response = http.post("/", FORM_URLENCODED, ORIGINAL_FORM);

        assertStatusLine(response, 200, "OK");
        String expected = formatter.format(records());
        assertThat(expected).isEqualTo(records().toString());
        assertUntypedReply(response, expected.getBytes(UTF_8));
        // The original IT's assertion: every line of reply.txt occurs in the response.
        String body = response.bodyAsString();
        List<String> replyLines = new ClassPathResource("reply.txt").getContentAsString(UTF_8).lines().toList();
        assertThat(replyLines).hasSize(6);
        replyLines.forEach(line -> assertThat(body).contains(line));
        assertThat(capturedSoql()).isEqualTo(ORIGINAL_SOQL);
    }

    @Test
    void postWithAnEmptyFieldLeavesItOutOfTheSelectList() throws Exception {
        Response response = http.post("/", FORM_URLENCODED, "object=user&field=&searchKey=name&searchValue=mule");

        assertStatusLine(response, 200, "OK");
        assertUntypedReply(response, formatter.format(records()).getBytes(UTF_8));
        // The ternary's empty branch: "SELECT id, name " + "" + " from ...", hence two spaces before "from".
        assertThat(capturedSoql()).isEqualTo("SELECT id, name  from user where name like '%mule%'");
    }

    /**
     * The record text is written as UTF-8 bytes with their own length, and {@code Accept} adds no Content-Type: the
     * flow's payload is untyped, and the Mule listener ignored {@code Accept}.
     */
    @Test
    void postWritesTheRecordTextAsUtf8WithItsByteLengthAndNoContentTypeWhateverTheAccept() throws Exception {
        List<Map<String, Object>> records = List.of(user("m\u00fcller@mule.test", "005B", "M\u00fcller \u20ac"));
        given(client.queryAll(anyString())).willReturn(records);

        Response response = http.post("/", FORM_URLENCODED, ORIGINAL_FORM, Map.of("Accept", "application/json"));

        assertStatusLine(response, 200, "OK");
        byte[] expected = formatter.format(records).getBytes(UTF_8);
        assertThat(expected.length).isGreaterThan(formatter.format(records).length());
        assertUntypedReply(response, expected);
        assertThat(response.bodyAsString()).contains("M\u00fcller \u20ac");
    }

    static Stream<Arguments> rejectedQueries() {
        return Stream.of(
                Arguments.of("object Salesforce does not describe", "/", FORM_URLENCODED,
                        "object=nosuch&field=email&searchKey=name&searchValue=mule"),
                Arguments.of("JSON body, which is not decoded as a form", "/", "application/json",
                        "{\"object\":\"user\",\"field\":\"email\",\"searchKey\":\"name\",\"searchValue\":\"mule\"}"),
                Arguments.of("parameters in the query string only", "/?" + ORIGINAL_FORM, FORM_URLENCODED, ""),
                Arguments.of("form without field", "/", FORM_URLENCODED,
                        "object=user&searchKey=name&searchValue=mule"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedQueries")
    void postAnswersEveryOtherQueryFailureWithTheCatchBranch(String description, String path, String contentType,
            String body) throws Exception {
        Response response = http.post(path, contentType, body);

        assertStatusLine(response, 200, "OK");
        assertThat(response.header("Content-Type")).isEqualTo(TEXT_PLAIN_UTF8);
        assertThat(response.bodyAsString()).isEqualTo(INVALID_QUERY);
        verify(client, never()).queryAll(any());
    }

    // ---------------------------------------------------------------- listener defaults (SC-1)

    @ParameterizedTest(name = "{0} /")
    @ValueSource(strings = {"PUT", "PATCH", "DELETE", "OPTIONS"})
    void methodsNeitherListenerAcceptsGetThe405(String method) {
        Response response = http.send(method, "/", null, new byte[0]);

        assertStatusLine(response, 405, "Method Not Allowed");
        assertContentType(response, "text/plain");
        assertThat(response.bodyAsString()).isEqualTo("Method not allowed for endpoint: /");
        verifyNoInteractions(client);
    }

    // ---------------------------------------------------------------- method letter case (config/ListenerMethodFilter)

    @ParameterizedTest(name = "{0} /")
    @ValueSource(strings = {"get", "Get", "gEt"})
    void getInAnyLetterCaseRendersTheFormAsAnUppercaseGet(String method) throws Exception {
        Response response = http.send(method, "/", null, null);

        assertStatusLine(response, 200, "OK");
        assertThat(response.header("Content-Type")).isEqualTo(TEXT_HTML_UTF8);
        assertThat(response.body()).isEqualTo(expectedFormPage().getBytes(UTF_8));
        assertSameResponse(http.get("/"), response);
        verify(client, times(2)).describeGlobal();
        verify(client, never()).queryAll(any());
    }

    @ParameterizedTest(name = "{0} /")
    @ValueSource(strings = {"post", "Post", "pOsT"})
    void postInAnyLetterCaseRunsTheQueryAsAnUppercasePost(String method) throws Exception {
        Response response = http.send(method, "/", Map.of("Content-Type", FORM_URLENCODED),
                ORIGINAL_FORM.getBytes(UTF_8));

        assertStatusLine(response, 200, "OK");
        assertThat(response.body()).isEqualTo(formatter.format(records()).getBytes(UTF_8));
        assertThat(capturedSoql()).isEqualTo(ORIGINAL_SOQL);
        assertSameResponse(http.post("/", FORM_URLENCODED, ORIGINAL_FORM), response);
    }

    /**
     * Spellings neither listener allows, with the headers sent: each keeps the listener's 405, a lower-case
     * {@code options} carrying the headers of a CORS pre-flight included. {@code head} has its own case below.
     */
    static Stream<Arguments> otherMethodSpellings() {
        Map<String, String> preflightHeaders = new LinkedHashMap<>();
        preflightHeaders.put("Origin", "https://example.invalid");
        preflightHeaders.put("Access-Control-Request-Method", "GET");
        return Stream.of(
                Arguments.of("options", Map.of()),
                Arguments.of("Options", Map.of()),
                Arguments.of("options", preflightHeaders),
                Arguments.of("put", Map.of()),
                Arguments.of("delete", Map.of()),
                Arguments.of("trace", Map.of()),
                Arguments.of("postx", Map.of()));
    }

    @ParameterizedTest(name = "{0} / {1}")
    @MethodSource("otherMethodSpellings")
    void otherMethodSpellingKeepsTheListener405(String method, Map<String, String> headers) {
        Response response = http.send(method, "/", headers, null);

        assertStatusLine(response, 405, "Method Not Allowed");
        assertContentType(response, "text/plain");
        assertThat(response.bodyAsString()).isEqualTo("Method not allowed for endpoint: /");
        assertThat(response.headers()).noneMatch(header ->
                header.getKey().regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length())
                        || header.getKey().equalsIgnoreCase("Vary"));
        verifyNoInteractions(client);
    }

    /**
     * {@code head} keeps the listener's 405 and, like {@code HEAD}, carries no body: Undertow compares the method
     * ignoring case when it decides whether a response has a body, so it writes the head of the 405 alone. ST-1 skips
     * the body for the exact token {@code HEAD} only, so this case reads the raw response up to the server's close.
     */
    @Test
    void lowerCaseHeadKeepsTheListener405AndIsAnsweredAsAnUppercaseHead() throws IOException {
        Response uppercase = http.send("HEAD", "/", null, null);

        String raw = new String(rawExchange("head / HTTP/1.1\r\nHost: localhost:" + port
                + "\r\nConnection: close\r\n\r\n"), ISO_8859_1);

        int headEnd = raw.indexOf("\r\n\r\n");
        assertThat(headEnd).as(raw).isPositive();
        assertThat(raw.substring(headEnd + 4)).as(raw).isEmpty();
        List<String> lines = List.of(raw.substring(0, headEnd).split("\r\n"));
        assertThat(lines.get(0)).as(raw).isEqualTo("HTTP/1.1 405 Method Not Allowed");
        List<String> headers = lines.subList(1, lines.size()).stream()
                .map(line -> line.substring(0, line.indexOf(':')).toLowerCase(Locale.ROOT) + ": "
                        + line.substring(line.indexOf(':') + 1).trim())
                .filter(line -> !line.startsWith("date: "))
                .sorted()
                .toList();
        assertThat(headers).as(raw).contains("content-type: text/plain",
                "content-length: " + "Method not allowed for endpoint: /".length());
        assertThat(lines.get(0)).isEqualTo(uppercase.statusLine());
        assertThat(headers).isEqualTo(headerLines(uppercase));
        verifyNoInteractions(client);
    }

    @Test
    void anUnservedPathGetsTheNoListener404() {
        Response response = http.get("/favicon.ico");

        assertStatusLine(response, 404, "Not Found");
        assertContentType(response, "text/plain");
        assertThat(response.bodyAsString()).isEqualTo("No listener for endpoint: /favicon.ico");
        verifyNoInteractions(client);
    }

    // ---------------------------------------------------------------- approved connector failure modes (SC-9)

    /** The two Salesforce-calling endpoints and the client call each one fails on. */
    enum Endpoint {

        /** {@code GET /}, failing on {@code describeGlobal}. */
        GET_FORM {
            @Override
            void failWith(SalesforcePartnerClient client, RuntimeException failure) throws Exception {
                given(client.describeGlobal()).willThrow(failure);
            }

            @Override
            Response send(HttpTestClient http) {
                return http.get("/");
            }
        },

        /** {@code POST /} with the original form, failing on {@code queryAll} after the identifier checks pass. */
        POST_QUERY {
            @Override
            void failWith(SalesforcePartnerClient client, RuntimeException failure) throws Exception {
                given(client.queryAll(anyString())).willThrow(failure);
            }

            @Override
            Response send(HttpTestClient http) {
                return http.post("/", FORM_URLENCODED, ORIGINAL_FORM);
            }
        };

        abstract void failWith(SalesforcePartnerClient client, RuntimeException failure) throws Exception;

        abstract Response send(HttpTestClient http);
    }

    /** One SC-9 exception with the response {@code UpstreamExceptionHandler} must give it. */
    record UpstreamFailure(String label, RuntimeException exception, int status, String reason, String body) {

        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Arguments> upstreamFailures() {
        return Stream.of(Endpoint.values()).flatMap(endpoint -> Stream.of(
                new UpstreamFailure("unavailable",
                        new UpstreamUnavailableException(UpstreamSystem.SALESFORCE, "down",
                                new SocketTimeoutException("t")),
                        503, "Service Unavailable", "{ \"message\": \"down\", \"upstream\": \"Salesforce\" }"),
                new UpstreamFailure("rate limit",
                        new UpstreamRateLimitException(UpstreamSystem.SALESFORCE, "limit", null,
                                new RuntimeException("x")),
                        429, "Too Many Requests", "{ \"message\": \"limit\", \"upstream\": \"Salesforce\" }"),
                new UpstreamFailure("authentication",
                        new UpstreamAuthenticationException(UpstreamSystem.SALESFORCE, "auth",
                                new RuntimeException("x")),
                        502, "Bad Gateway", "{ \"message\": \"auth\", \"upstream\": \"Salesforce\" }"))
                .map(failure -> Arguments.of(endpoint, failure)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("upstreamFailures")
    void upstreamFailuresTakePrecedenceOverTheFlowsOwnHandling(Endpoint endpoint, UpstreamFailure failure)
            throws Exception {
        endpoint.failWith(client, failure.exception());

        Response response = endpoint.send(http);

        assertStatusLine(response, failure.status(), failure.reason());
        assertContentType(response, "application/json");
        assertThat(response.bodyAsString()).isEqualTo(failure.body()).isNotEqualTo(INVALID_QUERY);
        // Salesforce sends no Retry-After, so none of the three responses carries one, the 429 included.
        assertThat(response.header("Retry-After")).isNull();
    }

    // ---------------------------------------------------------------- fixtures and assertions

    private static DescribeGlobalResult describeGlobal() {
        DescribeGlobalResult result = new DescribeGlobalResult();
        result.setSobjects(new DescribeGlobalSObjectResult[] {
                sobject("User", "User"), sobject("Account", "Account"), sobject("Contact", "Contact")});
        return result;
    }

    private static DescribeGlobalSObjectResult sobject(String name, String label) {
        DescribeGlobalSObjectResult sobject = new DescribeGlobalSObjectResult();
        sobject.setName(name);
        sobject.setLabel(label);
        return sobject;
    }

    private static DescribeSObjectResult describeUser() {
        DescribeSObjectResult result = new DescribeSObjectResult();
        result.setName("User");
        result.setFields(new Field[] {field("Id"), field("Name"), field("Email")});
        return result;
    }

    private static Field field(String name) {
        Field field = new Field();
        field.setName(name);
        return field;
    }

    /** The two {@code reply.txt} users as {@code HashMap}s, the record type the client returns. */
    private static List<Map<String, Object>> records() {
        return List.of(
                user("kicks+a@mulesoft.com", "00520000003LtYCAA0", "Mule orgA"),
                user("kicks+a-integration@mulesoft.com", "00520000003LtvGAAS", "Mule orgA Integration"));
    }

    private static Map<String, Object> user(String email, String id, String name) {
        Map<String, Object> user = new HashMap<>();
        user.put("Email", email);
        user.put("Id", id);
        user.put("Name", name);
        return user;
    }

    private String expectedFormPage() {
        return renderer.render(TEMPLATE, Map.of(PAYLOAD_EXPRESSION, optionsMapper.toOptionsXml(describeGlobal())));
    }

    /** The single SOQL statement the request handed to {@code queryAll}. */
    private String capturedSoql() throws Exception {
        ArgumentCaptor<String> soql = ArgumentCaptor.forClass(String.class);
        verify(client).queryAll(soql.capture());
        return soql.getValue();
    }

    private static void assertStatusLine(Response response, int status, String reason) {
        assertThat(response.status()).as("status of %s", response).isEqualTo(status);
        assertThat(response.reason()).as("reason phrase").isEqualTo(reason);
        assertThat(response.statusLine()).isEqualTo("HTTP/1.1 " + status + " " + reason);
    }

    private static void assertContentType(Response response, String expected) {
        assertThat(response.header("Content-Type")).as("Content-Type of %s", response).isEqualTo(expected);
    }

    /**
     * The reply of an untyped payload, as the Mule 3.8 listener wrote it: {@code body} with its
     * {@code Content-Length} and no {@code Content-Type}.
     */
    private static void assertUntypedReply(Response response, byte[] body) {
        assertThat(response.headers("Content-Type")).as("Content-Type of %s", response).isEmpty();
        assertThat(response.headers("Content-Length")).as("Content-Length of %s", response)
                .containsExactly(String.valueOf(body.length));
        assertThat(response.body()).as("body of %s", response).isEqualTo(body);
    }

    /** Same status line, same body bytes and the same headers but {@code Date}, compared as sorted lines. */
    private static void assertSameResponse(Response expected, Response actual) {
        assertThat(actual.statusLine()).as("status line of %s", actual).isEqualTo(expected.statusLine());
        assertThat(actual.body()).as("body of %s", actual).isEqualTo(expected.body());
        assertThat(headerLines(actual)).as("headers of %s", actual).isEqualTo(headerLines(expected));
    }

    /** Every header but {@code Date} as {@code name: value} with the name lower-cased, sorted, duplicates kept. */
    private static List<String> headerLines(Response response) {
        return response.headers().stream()
                .filter(header -> !header.getKey().equalsIgnoreCase("Date"))
                .map(header -> header.getKey().toLowerCase(Locale.ROOT) + ": " + header.getValue())
                .sorted()
                .toList();
    }

    /**
     * Writes {@code requestHead} on a new connection and returns every byte the server sends until it closes the
     * connection, within ST-1's timeouts.
     */
    private byte[] rawExchange(String requestHead) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), HttpTestClient.TIMEOUT_MILLIS);
            socket.setSoTimeout(HttpTestClient.TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(requestHead.getBytes(US_ASCII));
            out.flush();
            return socket.getInputStream().readAllBytes();
        }
    }
}
