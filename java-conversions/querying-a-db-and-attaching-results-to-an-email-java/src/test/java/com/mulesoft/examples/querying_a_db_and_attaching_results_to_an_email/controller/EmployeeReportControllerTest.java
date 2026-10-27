/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
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
 * strategy: the JSON 500 for a one-word employee name, malformed XML and a failed SMTP send, and the no-listener
 * 404 for any other path.
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
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void unknownPathReturnsNoListener404() {
        HttpTestClient.Response response = client.get("/other");

        assertStatus(response, 404, "Not Found");
        assertContentTypeStartsWith(response, "text/plain");
        assertThat(response.bodyAsString()).as(response::toString).isEqualTo("No listener for endpoint: /other");
    }

    /** The send runs last and is never retried, so a failing SMTP server answers the request with the JSON 500. */
    @Test
    void smtpFailureFailsWithJson500() {
        doThrow(new MailSendException("SMTP unavailable")).when(reportMailSender).send(anyString());

        HttpTestClient.Response response = client.send("POST", "/", XML, MESSAGE);

        assertStatus(response, 500, "Internal Server Error");
        assertContentTypeStartsWith(response, "application/json");
        assertThat(response.bodyAsString()).as(response::toString).isEqualTo("{ \"message\": \"SMTP unavailable\" }");
        verify(reportMailSender).send(CSV);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    /**
     * Asserts the 200 {@code multipart/form-data} reply whose single part is the {@code employees.csv} attachment of
     * the original flow (attachments.xml:35), typed {@code text/plain} and holding exactly {@link #CSV}.
     */
    private static void assertCsvReport(HttpTestClient.Response response) throws MessagingException, IOException {
        assertStatus(response, 200, "OK");
        assertContentTypeStartsWith(response, "multipart/form-data");

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

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] classpathBytes(String location) {
        try {
            return new ClassPathResource(location).getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath resource " + location, e);
        }
    }
}
