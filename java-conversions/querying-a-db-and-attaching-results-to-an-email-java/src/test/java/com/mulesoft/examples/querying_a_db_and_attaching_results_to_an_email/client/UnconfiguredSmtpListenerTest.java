/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.support.HttpTestClient;
import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.support.ImportOrdersSeeder;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Wire-level test of the whole application started from the main {@code application.yml} alone, with every SMTP
 * and address setting empty, as the unset TODO keys leave them, and a working database: an in-memory H2 database in
 * MySQL mode seeded from {@code import_orders.sql}. The original test's {@code message.xml} request therefore runs
 * the lookups and the CSV transform and reaches {@link ReportMailSender}, which refuses the send before any message is
 * built or any connection opened. The SC-1 500 {@code application/json} {@code { "message": ... }} carries the text
 * naming every missing key with its environment variable.
 *
 * <p>No profile is active, so the GreenMail account of profile {@code test} does not apply; the SMTP and address
 * keys are also set empty here, so variables such as {@code SMTP_HOST} in the host environment cannot change the
 * outcome. The application's {@link JavaMailSender} is a spy that must never be asked to build or send a message.
 * Requests go through the project's ST-1 {@link HttpTestClient}; the listener declares no {@code allowedMethods}, so
 * {@code POST} and {@code GET} with the same body are answered alike.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jdbc.url=jdbc:h2:mem:unconfigured-smtp;MODE=MySQL;DATABASE_TO_LOWER=TRUE;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1",
        "smtp.host=", "smtp.user=", "smtp.password=", "mail.from=", "mail.to="})
class UnconfiguredSmtpListenerTest {

    private static final String NOT_CONFIGURED_MESSAGE = "SMTP mail settings are not configured: "
            + "smtp.host (SMTP_HOST), smtp.user (SMTP_USER), smtp.password (SMTP_PASSWORD), "
            + "mail.from (MAIL_FROM), mail.to (MAIL_TO)";

    private static final Map<String, String> XML = Map.of("Content-Type", "application/xml");

    /** The original test's request body: Chava Puckett, Quentin Puckett and Mona Sosa. */
    private static final byte[] MESSAGE = classpathBytes("message.xml");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private JavaMailSender mailSender;

    @SpyBean
    private ReportMailSender reportMailSender;

    @BeforeEach
    void seedDatabase() {
        ImportOrdersSeeder.seed(jdbcTemplate);
    }

    /** The shipped configuration still yields Boot's sender, with the empty host the check reads. */
    @Test
    void contextStartsWithBootMailSenderAndEmptyHost() {
        JavaMailSenderImpl sender = assertInstanceOf(JavaMailSenderImpl.class, mailSender);
        assertEquals("", sender.getHost());
    }

    /**
     * @param method the request method, sent with {@code message.xml} as its body
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "GET"})
    void reportRequestFailsWithJson500NamingTheMissingSmtpKeys(String method) {
        HttpTestClient.Response response = HttpTestClient.localhost(port).send(method, "/", XML, MESSAGE);

        assertEquals("HTTP/1.1 500 Internal Server Error", response.statusLine(), response::toString);
        String contentType = response.header("Content-Type");
        assertNotNull(contentType, response::toString);
        assertTrue(contentType.startsWith(MediaType.APPLICATION_JSON_VALUE), response::toString);
        assertEquals("{ \"message\": \"" + NOT_CONFIGURED_MESSAGE + "\" }", response.bodyAsString(),
                response::toString);

        verify(reportMailSender, times(1)).send(anyString());
        verify(mailSender, never()).createMimeMessage();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    private static byte[] classpathBytes(String location) {
        try {
            return new ClassPathResource(location).getContentAsByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath resource " + location, e);
        }
    }
}
