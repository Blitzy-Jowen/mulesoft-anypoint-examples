/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.trigger.CsvFileTrigger;
import jakarta.mail.internet.MimeMessage;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Port of the original {@code SendingACsvFileThroughEmailUsingSmtpIT}: the classpath {@code input.csv}, dropped into
 * the input directory as {@code input.csv}, must arrive as one mail whose body, trimmed, equals the committed
 * {@code email.txt}.
 *
 * <p>The original ran the flow against a Gmail account and read the mail back over IMAPS. Here the whole application
 * context runs against an in-process GreenMail SMTP server on a dynamic port, so the test needs no credential and no
 * external host:
 * <ul>
 *   <li>{@code MuleClient.dispatch} to the file endpoint becomes a file moved atomically into this class's input
 *       directory followed by {@link CsvFileTrigger#pollOnce()};</li>
 *   <li>{@code Thread.sleep(12000)} becomes a wait bounded at the same 12 seconds;</li>
 *   <li>reading the newest INBOX message over IMAPS becomes GreenMail's received messages, and the
 *       {@code tearDown} that flagged it deleted becomes a purge of every mailbox.</li>
 * </ul>
 * The SMTP account, sender, recipient and subject come from profile {@code test} ({@code application-test.yml}); the
 * GreenMail user registered below must match its {@code smtp.user} and {@code smtp.password}. The subject, the
 * {@code text/plain} content type, the single send and the deletion of the polled file are checked as well.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class SendingACsvFileThroughEmailUsingSmtpTest {

    /** The {@code outputPattern} the original test dispatched the file with. */
    private static final String INPUT_FILE_NAME = "input.csv";

    private static final String SUBJECT = "Mule flow completed!";

    /** The original test's 12000 ms sleep, now an upper bound. */
    private static final Duration MAIL_WAIT_LIMIT = Duration.ofSeconds(12);

    private static final Duration DISPOSAL_WAIT_LIMIT = Duration.ofSeconds(5);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    /**
     * Per-run directory under the build directory rather than in the shared system temp directory: the cached context
     * keeps polling, and recreating, the input directory after this class ends, so only {@code mvn clean} removes it.
     * Its {@code input} subdirectory is this class's {@code csv-email.input-dir}, so neither
     * {@code src/main/resources/input} nor the surefire default {@code target/test-csv-email/input} is polled here.
     */
    private static final Path WORK_DIR;

    private static final Path INPUT_DIR;

    static {
        try {
            Path buildDir = Files.createDirectories(Path.of("target").toAbsolutePath());
            WORK_DIR = Files.createTempDirectory(buildDir, "csv-email-smtp-test-");
            INPUT_DIR = Files.createDirectories(WORK_DIR.resolve("input"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the test input directory under target", e);
        }
    }

    // Class-scoped lifecycle: the server starts in beforeAll, before the Spring extension loads the context, so the
    // lazy smtp.port supplier below reads the port GreenMail actually bound.
    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("test@localhost", "secret"))
            .withPerMethodLifecycle(false);

    @Autowired
    CsvFileTrigger trigger;

    /**
     * Points the mail sender at GreenMail and the file trigger at this class's input directory. Both outrank the
     * profile YAML and the surefire system property {@code csv-email.input-dir}.
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("smtp.port", () -> GREEN_MAIL.getSmtp().getPort());
        registry.add("csv-email.input-dir", INPUT_DIR::toString);
    }

    @AfterEach
    void tearDown() throws Exception {
        GREEN_MAIL.purgeEmailFromAllMailboxes();
        Files.deleteIfExists(INPUT_DIR.resolve(INPUT_FILE_NAME));
    }

    @Test
    void sendCSVFile() throws Exception {
        String testEmailBody = classpathText("/email.txt");

        placeInputFile(classpathBytes("/" + INPUT_FILE_NAME));

        // Not asserted: the scheduled poll may take the file first, and the poller's in-process mark keeps either run
        // from mailing it twice.
        trigger.pollOnce();

        Awaitility.await().atMost(MAIL_WAIT_LIMIT).pollInterval(POLL_INTERVAL)
                .until(() -> GREEN_MAIL.getReceivedMessages().length >= 1);
        // The poller deletes the file only after the send returned, so once it is gone no send is still in flight.
        Path polledFile = INPUT_DIR.resolve(INPUT_FILE_NAME);
        Awaitility.await().atMost(DISPOSAL_WAIT_LIMIT).pollInterval(POLL_INTERVAL)
                .until(() -> Files.notExists(polledFile));

        MimeMessage[] messages = GREEN_MAIL.getReceivedMessages();
        assertEquals(1, messages.length, "The polled file should be mailed exactly once");
        assertEquals(testEmailBody, messages[0].getContent().toString().trim());
        assertEquals(SUBJECT, messages[0].getSubject());
        assertTrue(messages[0].isMimeType("text/plain"), messages[0].getContentType());
    }

    /**
     * Stages the bytes beside the input directory, on the same file store, and renames them in atomically: the
     * scheduled poll runs on its own thread and must never list a half-written file.
     */
    private static void placeInputFile(byte[] content) throws IOException {
        Path staged = Files.write(Files.createTempFile(WORK_DIR, "staged-", ".csv"), content);
        Files.move(staged, INPUT_DIR.resolve(INPUT_FILE_NAME), StandardCopyOption.ATOMIC_MOVE);
    }

    private static byte[] classpathBytes(String resource) throws IOException {
        try (InputStream stream = SendingACsvFileThroughEmailUsingSmtpTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "Missing classpath resource " + resource);
            return stream.readAllBytes();
        }
    }

    /** The resource as UTF-8 text, untrimmed, as the original read {@code email.txt}. */
    private static String classpathText(String resource) throws IOException {
        return new String(classpathBytes(resource), StandardCharsets.UTF_8);
    }
}
