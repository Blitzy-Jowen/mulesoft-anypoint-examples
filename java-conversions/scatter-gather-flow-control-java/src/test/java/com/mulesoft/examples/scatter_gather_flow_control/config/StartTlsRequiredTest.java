/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.config;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import jakarta.mail.MessagingException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.mulesoft.examples.scatter_gather_flow_control.client.AggregationReportMailer;

/**
 * Tests that the SMTP submission of {@code application.yml} requires STARTTLS. The original's
 * {@code smtp:gmail-connector} (scatter-gather.xml:3) turned STARTTLS on, and Angus Mail then upgrades the connection
 * whenever the server offers STARTTLS; {@code mail.smtp.starttls.required} (and {@code mail.smtps.starttls.required}
 * for the {@code smtps} protocol) makes a server, or an interceptor, that does not offer it fail the send before the
 * account or any mail data leaves.
 *
 * <p>Two layers:
 * <ul>
 *   <li>The settings bound from the classpath {@code application.yml} and {@code application-test.yml} exactly as
 *       Spring Boot binds {@code spring.mail.properties}: required in the main configuration and turned off only in
 *       the test profile, whose GreenMail SMTP server offers no STARTTLS.</li>
 *   <li>The wire: {@link AggregationReportMailer}, with the {@code JavaMailSender} Spring Boot's mail
 *       auto-configuration builds from the main {@code application.yml} and no profile, mails the report to a
 *       loopback SMTP peer that advertises {@code AUTH PLAIN LOGIN} but no STARTTLS and records every line it
 *       receives. The send fails with Angus Mail's {@code STARTTLS is required but host does not support STARTTLS},
 *       and the peer receives no {@code AUTH}, {@code MAIL}, {@code RCPT} or {@code DATA} command. The control turns
 *       the requirement off, as the original connector had it, and proves the peer sees the downgrade: the account,
 *       the envelope and the report then reach it in clear text.</li>
 * </ul>
 *
 * <p>The account, password, addresses and the report's contact are dummy values: none is a credential and none
 * identifies anyone.
 */
class StartTlsRequiredTest {

    /** Angus Mail's message when STARTTLS is required and the server does not advertise it. */
    private static final String STARTTLS_REQUIRED_MESSAGE = "STARTTLS is required but host does not support STARTTLS";

    private static final String REQUIRED_KEY = "spring.mail.properties[mail.smtp.starttls.required]";

    private static final String SMTP_USER = "sentinel.smtp.account@localhost";

    private static final String SMTP_PASSWORD = "sentinel_smtp_password";

    private static final String MAIL_FROM = "sentinel.sender@localhost";

    private static final String MAIL_TO = "sentinel.recipient@localhost";

    /** A report row, as {@code ContactsCsvMapper} writes merged contacts: blank {@code Id}, name and e-mail address. */
    private static final String REPORT_ROW = ",sentinel,sentinel.contact@localhost";

    private static final String REPORT = "Id,Name,Email\n" + REPORT_ROW + "\n";

    /** Bound on every wait for the peer, well above the 10000 ms timeouts of {@code application.yml}. */
    private static final int PEER_TIMEOUT_MS = 20_000;

    /**
     * {@code spring.mail.properties[<key>]} holds {@code value} in {@code file}, bound as Spring Boot binds it:
     * STARTTLS is required by the main configuration for {@code smtp} and {@code smtps}, and only the test profile
     * turns it off for GreenMail.
     */
    @ParameterizedTest(name = "{0}: {1} = {2}")
    @CsvSource({
            "application.yml, mail.smtp.starttls.enable, true",
            "application.yml, mail.smtp.starttls.required, true",
            "application.yml, mail.smtps.starttls.enable, true",
            "application.yml, mail.smtps.starttls.required, true",
            "application-test.yml, mail.smtp.starttls.enable, false",
            "application-test.yml, mail.smtp.starttls.required, false",
            "application-test.yml, mail.smtps.starttls.enable, false",
            "application-test.yml, mail.smtps.starttls.required, false"})
    void starttlsIsRequiredByApplicationYmlAndTurnedOffOnlyByTheTestProfile(String file, String key, String value)
            throws IOException {
        assertThat(mailProperties(file)).as("spring.mail.properties in " + file).containsEntry(key, value);
    }

    /**
     * With the main configuration, a peer that offers no STARTTLS ends the send after its EHLO reply: the mailer
     * fails with Angus Mail's STARTTLS message, and neither the account nor any envelope or report data reaches the
     * peer.
     */
    @Test
    void peerOfferingNoStartTlsReceivesNeitherCredentialsNorMailData() throws Exception {
        try (NoStartTlsSmtpPeer peer = new NoStartTlsSmtpPeer()) {
            contextRunner(peer).run(context -> {
                assertThat(context.getEnvironment().getActiveProfiles()).as("active profiles").isEmpty();
                assertThat(context.getBean(JavaMailSenderImpl.class).getJavaMailProperties())
                        .containsEntry("mail.smtp.starttls.required", "true");
                AggregationReportMailer mailer = context.getBean(AggregationReportMailer.class);

                assertThatThrownBy(() -> mailer.send(REPORT))
                        .isInstanceOf(MailSendException.class)
                        .satisfies(failure -> assertThat(causeChain(failure))
                                .anyMatch(cause -> cause instanceof MessagingException
                                        && STARTTLS_REQUIRED_MESSAGE.equals(cause.getMessage())));
            });

            List<String> lines = peer.receivedLines();
            assertThat(verbs(lines)).as("commands the peer received").startsWith("EHLO")
                    .doesNotContain("AUTH", "MAIL", "RCPT", "DATA");
            assertThat(lines).as("lines the peer received").noneMatch(line -> line.contains(base64(SMTP_USER))
                    || line.contains(base64(SMTP_PASSWORD)) || line.contains(MAIL_FROM) || line.contains(MAIL_TO)
                    || line.contains(AggregationReportMailer.BODY_TEXT) || line.contains(REPORT_ROW));
        }
    }

    /**
     * Control: with STARTTLS enabled but not required, as the original connector had it, the same peer receives the
     * account, the envelope and the report in clear text, so the test above observes a refused downgrade.
     */
    @Test
    void withoutTheRequirementTheSamePeerReceivesTheWholeMailInClearText() throws Exception {
        try (NoStartTlsSmtpPeer peer = new NoStartTlsSmtpPeer()) {
            contextRunner(peer).withPropertyValues(REQUIRED_KEY + "=false")
                    .run(context -> assertThat(context.getBean(AggregationReportMailer.class).send(REPORT))
                            .isEqualTo(AggregationReportMailer.BODY_TEXT));

            List<String> lines = peer.receivedLines();
            assertThat(verbs(lines)).as("commands the peer received")
                    .containsSubsequence("EHLO", "AUTH", "MAIL", "RCPT", "DATA");
            assertThat(lines).as("lines the peer received")
                    .contains(base64(SMTP_USER), base64(SMTP_PASSWORD), AggregationReportMailer.BODY_TEXT, REPORT_ROW)
                    .anyMatch(line -> line.contains(MAIL_FROM))
                    .anyMatch(line -> line.contains(MAIL_TO));
        }
    }

    /**
     * The application's mail sender as Spring Boot builds it from the classpath {@code application.yml}, without a
     * profile, aimed at the peer with dummy account and addresses.
     */
    private static ApplicationContextRunner contextRunner(NoStartTlsSmtpPeer peer) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(
                        PropertyPlaceholderAutoConfiguration.class, MailSenderAutoConfiguration.class))
                .withUserConfiguration(AggregationReportMailer.class)
                .withPropertyValues(
                        "smtp.host=" + peer.host(), "smtp.port=" + peer.port(),
                        "smtp.user=" + SMTP_USER, "smtp.password=" + SMTP_PASSWORD,
                        "mail.from=" + MAIL_FROM, "mail.to=" + MAIL_TO);
    }

    /** The {@code spring.mail.properties} map of a classpath YAML file, bound as Spring Boot binds it. */
    private static Map<String, String> mailProperties(String file) throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        return new Binder(ConfigurationPropertySources.from(documents))
                .bind("spring.mail.properties", Bindable.mapOf(String.class, String.class))
                .orElseGet(Map::of);
    }

    /** The upper-cased first word of each line, the SMTP command it carries. */
    private static List<String> verbs(List<String> lines) {
        return lines.stream().map(line -> line.split(" ", 2)[0].toUpperCase(Locale.ROOT)).toList();
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(US_ASCII));
    }

    private static Stream<Throwable> causeChain(Throwable failure) {
        return Stream.iterate(failure, Objects::nonNull, Throwable::getCause);
    }

    /**
     * A one-session SMTP server on a loopback port the system picks. It advertises {@code AUTH PLAIN LOGIN} but no
     * STARTTLS, as a server or an interceptor that suppresses STARTTLS does, accepts every login, envelope and message,
     * and records every line the client sends, the lines of the message data included.
     */
    private static final class NoStartTlsSmtpPeer implements AutoCloseable {

        private static final String LOGIN_USERNAME_PROMPT = "334 VXNlcm5hbWU6";

        private static final String LOGIN_PASSWORD_PROMPT = "334 UGFzc3dvcmQ6";

        private static final String AUTHENTICATED = "235 2.7.0 Authentication successful";

        private final ServerSocket server;

        private final List<String> received = new CopyOnWriteArrayList<>();

        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private final Thread session;

        NoStartTlsSmtpPeer() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            server.setSoTimeout(PEER_TIMEOUT_MS);
            session = new Thread(this::serve, "no-starttls-smtp-peer");
            session.setDaemon(true);
            session.start();
        }

        String host() {
            return server.getInetAddress().getHostAddress();
        }

        int port() {
            return server.getLocalPort();
        }

        /** Waits until the client has ended the session and returns every line it sent, in order. */
        List<String> receivedLines() throws InterruptedException {
            session.join(PEER_TIMEOUT_MS);
            assertThat(session.isAlive()).as("the peer's session ended").isFalse();
            assertThat(failure.get()).as("the peer's own failure").isNull();
            return new ArrayList<>(received);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }

        private void serve() {
            try (Socket client = server.accept()) {
                client.setSoTimeout(PEER_TIMEOUT_MS);
                BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), US_ASCII));
                Writer out = new OutputStreamWriter(client.getOutputStream(), US_ASCII);
                reply(out, "220 peer.localhost ESMTP");
                boolean inData = false;
                int pendingAuthLines = 0;
                String line;
                while ((line = in.readLine()) != null) {
                    received.add(line);
                    if (inData) {
                        if (".".equals(line)) {
                            inData = false;
                            reply(out, "250 2.0.0 Message accepted");
                        }
                    } else if (pendingAuthLines > 0) {
                        pendingAuthLines--;
                        reply(out, pendingAuthLines > 0 ? LOGIN_PASSWORD_PROMPT : AUTHENTICATED);
                    } else {
                        String[] words = line.split(" ");
                        switch (words[0].toUpperCase(Locale.ROOT)) {
                            case "EHLO" -> reply(out, "250-peer.localhost", "250-AUTH PLAIN LOGIN", "250 8BITMIME");
                            case "HELO" -> reply(out, "250 peer.localhost");
                            case "AUTH" -> pendingAuthLines = authenticate(words, out);
                            case "MAIL", "RCPT", "RSET", "NOOP" -> reply(out, "250 2.0.0 OK");
                            case "DATA" -> {
                                inData = true;
                                reply(out, "354 End data with <CR><LF>.<CR><LF>");
                            }
                            case "QUIT" -> {
                                reply(out, "221 2.0.0 Bye");
                                return;
                            }
                            default -> reply(out, "502 5.5.2 Command not implemented");
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                failure.set(e);
            }
        }

        /**
         * Answers an {@code AUTH} command and returns how many more client lines the exchange takes: {@code LOGIN}
         * prompts for the user name, unless the command carries it, and then for the password; {@code PLAIN} takes
         * its credentials on the command line or after an empty prompt. Every login is accepted.
         */
        private static int authenticate(String[] words, Writer out) throws IOException {
            String mechanism = words.length > 1 ? words[1].toUpperCase(Locale.ROOT) : "";
            boolean initialResponse = words.length > 2;
            switch (mechanism) {
                case "LOGIN" -> {
                    reply(out, initialResponse ? LOGIN_PASSWORD_PROMPT : LOGIN_USERNAME_PROMPT);
                    return initialResponse ? 1 : 2;
                }
                case "PLAIN" -> {
                    reply(out, initialResponse ? AUTHENTICATED : "334 ");
                    return initialResponse ? 0 : 1;
                }
                default -> {
                    reply(out, "504 5.5.4 Mechanism not supported");
                    return 0;
                }
            }
        }

        private static void reply(Writer out, String... lines) throws IOException {
            for (String line : lines) {
                out.write(line);
                out.write("\r\n");
            }
            out.flush();
        }
    }
}
