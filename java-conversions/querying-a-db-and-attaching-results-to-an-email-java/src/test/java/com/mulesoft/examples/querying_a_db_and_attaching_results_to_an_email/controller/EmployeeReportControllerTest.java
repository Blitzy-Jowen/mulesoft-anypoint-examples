/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client.ReportMailSender;
import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.support.HttpTestClient;
import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.support.ImportOrdersSeeder;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Wire-level test of {@link EmployeeReportController}, the HTTP trigger of flow {@code attachmentsFlow1}
 * (querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml:6-36), on the application's own
 * Undertow listener over the H2 database (MySQL mode) of profile {@code test}, seeded before every test from the
 * unchanged {@code import_orders.sql}, and an in-process GreenMail SMTP server standing in for Gmail.
 *
 * <p>Requests go through the project's ST-1 {@link HttpTestClient}, which reads the raw status line from the
 * socket, so status code, reason phrase, {@code Content-Type} and body bytes are asserted as the listener writes
 * them. The happy path posts the original test's {@code message.xml} and expects the complete CSV report, as the
 * {@code employees.csv} part of the reply and as one mail; GET and PUT are answered alike because the listener
 * declares no {@code allowedMethods}. A body without {@code employee} elements ends the flow at the splitter with an
 * empty 200 and no mail. The error cases are the platform defaults of SC-1, since the flow declares no exception
 * strategy: the JSON 500 for a one-word employee name, malformed XML and a failed SMTP send (a refused connection and
 * a rejected account each with a fixed message that names no class, host or port), and the no-listener 404 for any
 * other path. Every report reply carries exactly {@code Cache-Control: no-store} and
 * {@code X-Content-Type-Options: nosniff}, because it holds employee records; the empty reply and the error replies
 * carry neither.
 *
 * <p>{@code TRACE}, {@code OPTIONS} and CORS pre-flights run the flow as well and get exactly the {@code POST} reply,
 * complete with its {@code Content-Length}: no {@code message/http} echo of the request headers, its
 * {@code Authorization} value included, no {@code Allow} header and no {@code Access-Control-*} or {@code Vary}
 * header, because the listener applied no CORS policy.
 *
 * <p>The mail sender is a spy over the real bean, so every request sends through GreenMail unless a test stubs the
 * send to fail; the spy's default reset restores the real behaviour after each test. The single GreenMail server
 * serves the whole class, so no {@code @Nested} class is used: a nested class would start a second server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EmployeeReportControllerTest {

    /** The GreenMail account of profile {@code test} ({@code smtp.user}, {@code smtp.password}). */
    private static final String SMTP_USER = "test@localhost";

    private static final String SMTP_PASSWORD = "secret";

    /** {@code mail.subject} of profile {@code test}. */
    private static final String SUBJECT = "Mule flow completed!";

    private static final long DELIVERY_TIMEOUT_MS = 5000;

    /**
     * The report for {@code message.xml}: the DataWeave CSV writer's header row and one row per posted employee in
     * document order, dates as {@code yyyy-MM-dd}, LF line ends with a trailing LF. The original test asserted the
     * Chava and Mona rows; Quentin's hire date is the script's {@code '08-09-15'} read as 2008-09-15.
     */
    private static final String CSV = "first_name,last_name,gender,dob,hire_date\n"
            + "Chava,Puckett,F,1985-09-02,2008-10-12\n"
            + "Quentin,Puckett,F,1971-10-21,2008-09-15\n"
            + "Mona,Sosa,M,1950-09-26,2007-11-27\n";

    private static final Map<String, String> XML = Map.of("Content-Type", "application/xml");

    /** The original test's request body: Chava Puckett, Quentin Puckett and Mona Sosa. */
    private static final byte[] MESSAGE = classpathBytes("message.xml");

    /** A body whose {@code //employee} selects nothing. */
    private static final String NO_EMPLOYEES = "<root><employees/></root>";

    /** The reply's Content-Type: the form converter's generated boundary and no other parameter. */
    private static final String MULTIPART_CONTENT_TYPE = "multipart/form-data;boundary=[-_0-9A-Za-z]+";

    /** A marker in the shape of a bearer credential, which the servlet's TRACE echo would repeat; not a credential. */
    private static final String AUTHORIZATION = "Bearer employee-report-trace-sentinel";

    private static final String ORIGIN = "https://example.invalid";

    // A dynamic port instead of GreenMail's fixed 3025, so concurrent builds and the project's other GreenMail
    // tests never contend for one port. The server starts before the Spring context and serves every test.
    @RegisterExtension
    static final GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser(SMTP_USER, SMTP_PASSWORD))
            .withPerMethodLifecycle(false);

    @DynamicPropertySource
    static void greenMailPort(DynamicPropertyRegistry registry) {
        // Suppliers are read when the context is built, after GreenMail's beforeAll has bound the port.
        registry.add("smtp.port", () -> greenMail.getSmtp().getPort());
        registry.add("spring.mail.port", () -> greenMail.getSmtp().getPort());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private ReportMailSender reportMailSender;

    private HttpTestClient client;

    @BeforeEach
    void setUp() throws FolderException {
        greenMail.purgeEmailFromAllMailboxes();
        ImportOrdersSeeder.seed(jdbcTemplate);
        client = HttpTestClient.localhost(port);
    }

    @Test
    void postMessageXmlRepliesWithEmployeesCsvPartAndMailsTheReport() throws Exception {
        assertCsvReport(client.send("POST", "/", XML, MESSAGE));

        assertThat(greenMail.waitForIncomingEmail(DELIVERY_TIMEOUT_MS, 1)).isTrue();
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    /** The listener declares no {@code allowedMethods}, so every method runs the flow and mails the report. */
    @Test
    void getAndPutWithTheSameBodyAreAnsweredLikePost() throws Exception {
        for (String method : List.of("GET", "PUT")) {
            assertCsvReport(client.send(method, "/", XML, MESSAGE));
        }

        assertThat(greenMail.waitForIncomingEmail(DELIVERY_TIMEOUT_MS, 2)).isTrue();
        assertThat(greenMail.getReceivedMessages()).hasSize(2);
    }

    /** Mule's splitter ended the flow when {@code //employee} selected nothing: no query, no mail, empty reply. */
    @Test
    void bodyWithoutEmployeesRepliesEmpty200AndSendsNoMail() {
        HttpTestClient.Response response = client.send("POST", "/", XML, utf8("<root><employees/></root>"));

        assertStatus(response, 200, "OK");
        assertThat(response.body()).as(response::toString).isEmpty();
        assertNoCacheProtectionHeaders(response);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    /** The original's unguarded {@code payload.getText().split(" ")[1]} fails for a name without a space. */
    @Test
    void singleWordEmployeeNameFailsWithJson500AndSendsNoMail() {
        HttpTestClient.Response response = client.send("POST", "/", XML,
                utf8("<root><employees><employee>Chava</employee></employees></root>"));

        assertStatus(response, 500, "Internal Server Error");
        assertContentTypeStartsWith(response, "application/json");
        assertThat(response.bodyAsString()).as(response::toString)
                .isEqualTo("{ \"message\": \"Index 1 out of bounds for length 1\" }");
        assertNoCacheProtectionHeaders(response);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    /** The parser's message is the JDK's {@code SAXParseException} text, so only the body's shape is pinned. */
    @Test
    void malformedXmlFailsWithJson500AndSendsNoMail() {
        HttpTestClient.Response response = client.send("POST", "/", XML, utf8("<root>"));

        assertStatus(response, 500, "Internal Server Error");
        assertContentTypeStartsWith(response, "application/json");
        assertThat(response.bodyAsString()).as(response::toString)
                .startsWith("{ \"message\": ")
                .endsWith(" }");
        assertNoCacheProtectionHeaders(response);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void unknownPathReturnsNoListener404() {
        HttpTestClient.Response response = client.get("/other");

        assertStatus(response, 404, "Not Found");
        assertContentTypeStartsWith(response, "text/plain");
        assertThat(response.bodyAsString()).as(response::toString).isEqualTo("No listener for endpoint: /other");
        assertNoCacheProtectionHeaders(response);
    }

    /** The send runs last and is never retried, so a failing SMTP server answers the request with the JSON 500. */
    @Test
    void smtpFailureFailsWithJson500() {
        doThrow(new MailSendException("SMTP unavailable")).when(reportMailSender).send(anyString());

        HttpTestClient.Response response = client.send("POST", "/", XML, MESSAGE);

        assertStatus(response, 500, "Internal Server Error");
        assertContentTypeStartsWith(response, "application/json");
        assertThat(response.bodyAsString()).as(response::toString).isEqualTo("{ \"message\": \"SMTP unavailable\" }");
        assertNoCacheProtectionHeaders(response);
        verify(reportMailSender).send(CSV);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    /**
     * SMTP failures of a real send, whether its sender is aimed at a port that refuses connections, and the password
     * it presents to GreenMail, with the message the JSON 500 carries for each.
     */
    private static Stream<Arguments> realSmtpFailures() {
        return Stream.of(
                Arguments.of("connection refused", true, SMTP_PASSWORD, "Could not send the employees report mail"),
                Arguments.of("authentication rejected", false, "not-" + SMTP_PASSWORD, "Authentication failed"));
    }

    /**
     * The mail sender's real failures reach the caller as the JSON 500 with a fixed message: no mail or network
     * class, SMTP host or port, which Spring's own message for a refused connection names.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("realSmtpFailures")
    void realSmtpFailureAnswersJson500WithoutTransportDetails(String scenario, boolean refuseConnection,
                                                              String password, String message) throws Exception {
        try (Socket refusing = refuseConnection ? reserveRefusingPort() : null) {
            int smtpPort = refusing != null ? refusing.getLocalPort() : greenMail.getSmtp().getPort();
            ReportMailSender failingSender =
                    new ReportMailSender(mailSenderOn(smtpPort, password), SMTP_USER, SMTP_USER, SUBJECT);
            doAnswer(invocation -> {
                failingSender.send(invocation.getArgument(0));
                return null;
            }).when(reportMailSender).send(anyString());

            HttpTestClient.Response response = client.send("POST", "/", XML, MESSAGE);

            assertStatus(response, 500, "Internal Server Error");
            assertContentTypeStartsWith(response, "application/json");
            assertThat(response.bodyAsString()).as(response::toString)
                    .isEqualTo("{ \"message\": \"" + message + "\" }")
                    .doesNotContain("org.", "java.", Integer.toString(smtpPort), "localhost", "127.0.0.1");
            verify(reportMailSender).send(CSV);
            assertThat(greenMail.getReceivedMessages()).isEmpty();
        }
    }

    /**
     * Method and headers of requests the listener answers with the flow's own reply: {@code POST}, {@code TRACE} and
     * {@code OPTIONS} carrying an {@code Authorization} value the servlet's {@code TRACE} echo would repeat, and CORS
     * pre-flights asking for each method, which run the flow like any {@code OPTIONS} request.
     */
    private static Stream<Arguments> flowRequests() {
        Stream<Arguments> plain = Stream.of("POST", "TRACE", "OPTIONS").map(method -> Arguments.of(method,
                headers("Content-Type", "application/xml", "Authorization", AUTHORIZATION)));
        Stream<Arguments> preflights = Stream.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "TRACE")
                .map(requestedMethod -> Arguments.of("OPTIONS", headers("Origin", ORIGIN,
                        "Access-Control-Request-Method", requestedMethod, "Content-Type", "application/xml")));
        Stream<Arguments> preflightWithHeaders = Stream.of(Arguments.of("OPTIONS", headers("Origin", ORIGIN,
                "Access-Control-Request-Method", "POST",
                "Access-Control-Request-Headers", "authorization, content-type",
                "Content-Type", "application/xml", "Authorization", AUTHORIZATION)));
        return Stream.of(plain, preflights, preflightWithHeaders).flatMap(arguments -> arguments);
    }

    /**
     * The report reply is complete when the handler returns, so {@code FrameworkServlet} appends no {@code TRACE}
     * echo and adds no {@code Allow} header, and a pre-flight gets no CORS processing.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("flowRequests")
    void messageXmlGetsTheCompleteCsvReportForEveryMethod(String method, Map<String, String> headers)
            throws Exception {
        HttpTestClient.Response response = client.send(method, "/", headers, MESSAGE);

        assertCsvReport(response);
        assertThat(response.header("Content-Type")).as(response::toString).matches(MULTIPART_CONTENT_TYPE);
        assertThat(response.headers("Transfer-Encoding")).as(response::toString).isEmpty();
        assertThat(response.header("Content-Length")).as(response::toString)
                .isEqualTo(Integer.toString(response.body().length));
        assertNoServletOrCorsAdditions(response);
        assertThat(greenMail.waitForIncomingEmail(DELIVERY_TIMEOUT_MS, 1)).isTrue();
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    /** The empty reply is completed with {@code Content-Length: 0} as well, so nothing replaces or extends it. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("flowRequests")
    void bodyWithoutEmployeesGetsTheCompleteEmpty200ForEveryMethod(String method, Map<String, String> headers) {
        HttpTestClient.Response response = client.send(method, "/", headers, utf8(NO_EMPLOYEES));

        assertStatus(response, 200, "OK");
        assertThat(response.body()).as(response::toString).isEmpty();
        assertThat(response.header("Content-Length")).as(response::toString).isEqualTo("0");
        assertThat(response.header("Content-Type")).as(response::toString).isNull();
        assertNoCacheProtectionHeaders(response);
        assertNoServletOrCorsAdditions(response);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    /** A pre-flight to a path no listener serves gets the no-listener 404 like any other request. */
    @Test
    void preflightToUnknownPathReturnsNoListener404() {
        HttpTestClient.Response response = client.send("OPTIONS", "/other",
                headers("Origin", ORIGIN, "Access-Control-Request-Method", "POST"), null);

        assertStatus(response, 404, "Not Found");
        assertContentTypeStartsWith(response, "text/plain");
        assertThat(response.bodyAsString()).as(response::toString).isEqualTo("No listener for endpoint: /other");
        assertNoServletOrCorsAdditions(response);
    }

    /** {@code HEAD} runs the flow too; its reply declares the length of the report entity it does not carry. */
    @Test
    void headWithMessageXmlDeclaresTheReportWithoutBodyAndMailsTheReport() {
        HttpTestClient.Response response = client.send("HEAD", "/", XML, MESSAGE);

        assertStatus(response, 200, "OK");
        assertThat(response.header("Content-Type")).as(response::toString).matches(MULTIPART_CONTENT_TYPE);
        assertCacheProtectionHeaders(response);
        assertThat(Long.parseLong(response.header("Content-Length"))).as(response::toString)
                .isGreaterThan(CSV.length());
        assertThat(response.body()).as(response::toString).isEmpty();
        assertThat(greenMail.waitForIncomingEmail(DELIVERY_TIMEOUT_MS, 1)).isTrue();
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    /**
     * Asserts the 200 {@code multipart/form-data} reply whose single part is the {@code employees.csv} attachment of
     * the original flow (attachments.xml:35), typed {@code text/plain} and holding exactly {@link #CSV}, with the
     * cache and sniffing protection of a reply that holds employee records.
     */
    private static void assertCsvReport(HttpTestClient.Response response) throws MessagingException, IOException {
        assertStatus(response, 200, "OK");
        assertContentTypeStartsWith(response, "multipart/form-data");
        assertCacheProtectionHeaders(response);

        MimeMultipart multipart =
                new MimeMultipart(new ByteArrayDataSource(response.body(), response.header("Content-Type")));
        assertThat(multipart.getCount()).as(response::toString).isEqualTo(1);

        MimeBodyPart part = (MimeBodyPart) multipart.getBodyPart(0);
        assertThat(part.getHeader("Content-Disposition", null)).as(response::toString)
                .contains("name=\"employees.csv\"");
        // The raw header rather than getContentType(), which falls back to text/plain when a part has none.
        assertThat(part.getHeader("Content-Type", null)).as(response::toString).startsWith("text/plain");
        assertThat(new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .as(response::toString)
                .isEqualTo(CSV);
    }

    private static void assertStatus(HttpTestClient.Response response, int status, String reason) {
        assertThat(response.status()).as(response::toString).isEqualTo(status);
        assertThat(response.reason()).as(response::toString).isEqualTo(reason);
    }

    private static void assertContentTypeStartsWith(HttpTestClient.Response response, String mediaType) {
        assertThat(response.header("Content-Type")).as(response::toString).isNotNull().startsWith(mediaType);
    }

    /** Exactly one {@code Cache-Control: no-store} and one {@code X-Content-Type-Options: nosniff}. */
    private static void assertCacheProtectionHeaders(HttpTestClient.Response response) {
        assertThat(response.headers("Cache-Control")).as(response::toString).containsExactly("no-store");
        assertThat(response.headers("X-Content-Type-Options")).as(response::toString).containsExactly("nosniff");
    }

    /** Neither header: the empty no-employee reply and the SC-1 error replies hold no employee record. */
    private static void assertNoCacheProtectionHeaders(HttpTestClient.Response response) {
        assertThat(response.headers("Cache-Control")).as(response::toString).isEmpty();
        assertThat(response.headers("X-Content-Type-Options")).as(response::toString).isEmpty();
    }

    /**
     * Asserts that the reply carries nothing the servlet's own {@code TRACE} or {@code OPTIONS} handling or Spring's
     * CORS processing would add: no {@code message/http} Content-Type, no echoed request line, no
     * {@link #AUTHORIZATION} value anywhere, no {@code Allow}, {@code Access-Control-*} or {@code Vary} header.
     */
    private static void assertNoServletOrCorsAdditions(HttpTestClient.Response response) {
        assertThat(String.valueOf(response.header("Content-Type"))).as(response::toString)
                .doesNotStartWith("message/http");
        assertThat(response.bodyAsString()).as(response::toString)
                .doesNotContain("TRACE / HTTP/1.1")
                .doesNotContain(AUTHORIZATION);
        assertThat(response.headers()).as(response::toString)
                .noneMatch(header -> header.getValue().contains(AUTHORIZATION))
                .noneMatch(header -> header.getKey().equalsIgnoreCase("Allow")
                        || header.getKey().equalsIgnoreCase("Vary")
                        || header.getKey().regionMatches(true, 0, "Access-Control-", 0, "Access-Control-".length()));
    }

    /** Request headers from alternating names and values, sent in the order given. */
    private static Map<String, String> headers(String... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("every header name needs a value");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return headers;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A sender set up as profile {@code test} sets up the application's, aimed at the given port and presenting the
     * given password, with 2000 ms timeouts so that a failing send cannot hold the request.
     */
    private static JavaMailSenderImpl mailSenderOn(int smtpPort, String password) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("localhost");
        mailSender.setPort(smtpPort);
        mailSender.setUsername(SMTP_USER);
        mailSender.setPassword(password);
        mailSender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.starttls.enable", "false");
        properties.setProperty("mail.smtp.connectiontimeout", "2000");
        properties.setProperty("mail.smtp.timeout", "2000");
        properties.setProperty("mail.smtp.writetimeout", "2000");
        mailSender.setJavaMailProperties(properties);
        return mailSender;
    }

    /**
     * A socket bound to a free port on every local address that never listens or connects: a connection to its port
     * is refused while it is open, and with SO_REUSEADDR off no other socket can bind that port meanwhile.
     */
    private static Socket reserveRefusingPort() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(0));
            return socket;
        } catch (IOException | RuntimeException ex) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    private static byte[] classpathBytes(String location) {
        try {
            return new ClassPathResource(location).getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath resource " + location, e);
        }
    }
}
