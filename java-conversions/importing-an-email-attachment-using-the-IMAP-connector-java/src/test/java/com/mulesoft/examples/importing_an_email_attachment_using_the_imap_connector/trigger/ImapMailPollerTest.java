/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.trigger;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import jakarta.activation.DataHandler;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.iap.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.DummySSLServerSocketFactory;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;

import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.ImportingAnEmailAttachmentUsingTheIMAPConnectorApplication;
import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.service.AttachmentImportService;

/**
 * Trigger test of {@link ImapMailPoller}, the replacement of the original {@code imaps:inbound-endpoint} of
 * {@code imap-to-xml.xml}, which stands in for an endpoint test because the example has no HTTP listener. The poller
 * is constructed directly, with a {@link MockEnvironment} for {@code imap.properties} and a Mockito
 * {@link AttachmentImportService}, or, where its {@code @Value} defaults are under test, by a plain Spring context
 * without scheduling. Only {@link AsWiredByTheTestProfile} starts the Spring Boot application, under the
 * {@code test} profile, to poll as the application wires the poller. No test relies on the scheduled poll: each calls
 * {@link ImapMailPoller#pollOnce()}.
 *
 * <p>Mailbox behaviour: one GreenMail SMTP + IMAPS server on dynamically allocated ports serves the whole class and
 * is emptied before each test that uses it. Mail is sent over SMTP as the original integration test sent it. The
 * cases cover the original mail (a CSV attachment named {@code report}), messages already flagged {@code \Seen} or
 * {@code \Deleted}, a mail without attachments, an import failure, an unreachable port, a rejected password and the
 * attachment naming of Mule 3.8's {@code MailUtils}.
 *
 * <p>Unconfigured mailbox: a null or blank {@code imap.host}, {@code imap.user} or {@code imap.password} skips every
 * poll without a connection to a local listener the poller points at, and logs one WARN naming the missing keys and
 * their environment variables, then a DEBUG line per poll, never a configured value.
 *
 * <p>Socket deadlines: the connect, read and write deadlines default to the original endpoint's 10 s
 * {@code responseTimeout}, follow their dedicated keys and positive {@code imap.properties} entries, and refuse at
 * construction every value Angus Mail would apply as no deadline. Against local peers that accept and then stall
 * (no TLS handshake, a withheld LOGIN reply, a withheld FETCH reply) every poll ends at the read deadline without
 * throwing, and the next poll is not blocked. A GreenMail IMAPS mailbox is polled within the default deadlines.
 *
 * <p>Poll frequency: a zero, negative or unreadable {@code imap.check-frequency} is refused at construction, directly
 * and as Spring creates the poller, with a message naming the key and the value.
 *
 * <p>Log safety: every diagnostic the poller writes (connect, message listing, message processing, folder close,
 * store close) is provoked with an exception whose message and nested causes quote a sentinel user, a sentinel
 * password and sentinel message content. Every event captured on the poller's logger and on the root logger must
 * name the injected exception types and must carry neither a sentinel nor a throwable.
 */
class ImapMailPollerTest {

    private static final String HOST = "imap.test.invalid";
    private static final int PORT = 993;
    private static final String FOLDER = "INBOX";
    private static final String DEFAULT_DEADLINE = "10000";
    /** The original {@code checkFrequency}, passed as {@code imap.check-frequency} to every directly built poller. */
    private static final String DEFAULT_FREQUENCY = "100";

    /** The deadline the stall tests configure, short enough to keep them fast. */
    private static final int STALL_DEADLINE_MS = 500;
    /** How long one stalled poll may take: generous for a loaded host, yet far below the 10 s default deadline. */
    private static final Duration STALLED_POLL_BOUND = Duration.ofSeconds(5);
    private static final String STALL_USER = "stall-user";
    private static final String STALL_PASSWORD = "stall-test-pw";

    // Test-only sentinel values: no log event may contain any of them.
    private static final String SENTINEL_USER = "sentinel-user-7f3a@test.invalid";
    private static final String SENTINEL_PASSWORD = "sentinel-pw-41c9";
    private static final String SENTINEL_CONTENT = "sentinel-content-b58e";

    // The GreenMail users of application-test.yml (imap.* and smtp.*): test-only literals, never real accounts.
    private static final String RECEIVER = "receiver@localhost";
    private static final String RECEIVER_PASSWORD = "receiver-test-pw";
    private static final String SENDER = "sender@localhost";
    private static final String SENDER_PASSWORD = "sender-test-pw";
    /** The subject of the original integration test's mail. */
    private static final String SUBJECT = "This is the IMAP Subject Line!";
    /** GreenMail's 2 s default start-up wait is too short for the IMAPS TLS context on shared, loaded build hosts. */
    private static final long SERVER_STARTUP_TIMEOUT_MS = 30_000;

    /**
     * The class-wide mailbox. Its ports are allocated at start, never fixed, because concurrent builds share the host.
     * Started and stopped by plain class lifecycle methods rather than GreenMailExtension, whose before-all callback
     * would run again for each nested class and replace the running server.
     */
    private static GreenMail mailbox;

    private final AttachmentImportService service = mock(AttachmentImportService.class);
    private final Session session = mock(Session.class);
    private final Store store = mock(Store.class);
    private final Folder folder = mock(Folder.class);

    private final Logger pollerLogger = (Logger) LoggerFactory.getLogger(ImapMailPoller.class);
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private Level savedPollerLevel;
    private ListAppender<ILoggingEvent> pollerEvents;
    private ListAppender<ILoggingEvent> allEvents;

    @BeforeAll
    static void startMailbox() {
        mailbox = new GreenMail(smtpAndImaps());
        mailbox.start();
        mailbox.setUser(RECEIVER, RECEIVER, RECEIVER_PASSWORD);
        mailbox.setUser(SENDER, SENDER, SENDER_PASSWORD);
    }

    @AfterAll
    static void stopMailbox() {
        mailbox.stop();
    }

    @BeforeEach
    void captureLogs() {
        savedPollerLevel = pollerLogger.getLevel();
        pollerLogger.setLevel(Level.DEBUG);
        pollerEvents = new ListAppender<>();
        pollerEvents.start();
        pollerLogger.addAppender(pollerEvents);
        allEvents = new ListAppender<>();
        allEvents.start();
        rootLogger.addAppender(allEvents);
    }

    @AfterEach
    void releaseLogs() {
        pollerLogger.detachAppender(pollerEvents);
        pollerEvents.stop();
        rootLogger.detachAppender(allEvents);
        allEvents.stop();
        pollerLogger.setLevel(savedPollerLevel);
    }

    /**
     * The five diagnostics of {@link ImapMailPoller}, each with the mailbox behaviour that provokes it and the one
     * event it must produce: the fixed operation text, its non-sensitive context and the injected exception types.
     */
    enum DiagnosticSite {

        CONNECT(Level.ERROR, 0, "Failed to connect to IMAP folder INBOX on imap.test.invalid:993 "
                + "(jakarta.mail.AuthenticationFailedException <- java.io.IOException "
                + "<- java.net.SocketTimeoutException)") {
            @Override
            void arrange(ImapMailPollerTest test) throws Exception {
                doThrow(new AuthenticationFailedException("LOGIN rejected for " + SENTINEL_USER + " with "
                        + SENTINEL_PASSWORD, new IOException("server said " + SENTINEL_CONTENT,
                                new SocketTimeoutException("after sending " + SENTINEL_PASSWORD))))
                        .when(test.store).connect(anyString(), anyInt(), anyString(), anyString());
            }
        },

        READ_MESSAGES(Level.ERROR, 0, "Failed to read the messages of IMAP folder INBOX on imap.test.invalid:993 "
                + "(jakarta.mail.MessagingException <- java.lang.IllegalStateException)") {
            @Override
            void arrange(ImapMailPollerTest test) throws Exception {
                when(test.folder.getMessages()).thenThrow(new MessagingException("cannot list " + SENTINEL_CONTENT,
                        new IllegalStateException("session of " + SENTINEL_USER + "/" + SENTINEL_PASSWORD)));
            }
        },

        PROCESS_MESSAGE(Level.ERROR, 1, "Error processing IMAP message 7 "
                + "(java.lang.IllegalStateException <- java.io.IOException)") {
            @Override
            void arrange(ImapMailPollerTest test) throws Exception {
                when(test.folder.getMessages()).thenReturn(new Message[] {numberedMessage(7)});
                when(test.service.importAttachments(any())).thenThrow(new IllegalStateException(
                        "cannot import row 1," + SENTINEL_CONTENT + ",2.0,10",
                        new IOException("output share login " + SENTINEL_USER + " " + SENTINEL_PASSWORD)));
            }
        },

        CLOSE_FOLDER(Level.WARN, 0, "Failed to close IMAP folder INBOX on imap.test.invalid:993 "
                + "(jakarta.mail.MessagingException <- java.io.IOException)") {
            @Override
            void arrange(ImapMailPollerTest test) throws Exception {
                when(test.folder.getMessages()).thenReturn(new Message[0]);
                when(test.folder.isOpen()).thenReturn(true);
                doThrow(new MessagingException("EXPUNGE of " + SENTINEL_CONTENT + " failed",
                        new IOException("as " + SENTINEL_USER + " " + SENTINEL_PASSWORD)))
                        .when(test.folder).close(true);
            }
        },

        CLOSE_STORE(Level.WARN, 0, "Failed to close the IMAP store for imap.test.invalid:993 "
                + "(jakarta.mail.MessagingException <- org.eclipse.angus.mail.iap.ProtocolException "
                + "<- java.net.SocketTimeoutException)") {
            @Override
            void arrange(ImapMailPollerTest test) throws Exception {
                when(test.folder.getMessages()).thenReturn(new Message[0]);
                when(test.store.isConnected()).thenReturn(true);
                // Angus Mail's IMAP layer keeps the I/O failure of a lost connection in the synthetic BYE response
                // of the protocol exception, whose message then quotes that failure's message.
                doThrow(new MessagingException("LOGOUT of " + SENTINEL_USER, new ProtocolException(
                        Response.byeResponse(new SocketTimeoutException(SENTINEL_PASSWORD + " " + SENTINEL_CONTENT)))))
                        .when(test.store).close();
            }
        };

        final Level level;
        final int processed;
        final String message;

        DiagnosticSite(Level level, int processed, String message) {
            this.level = level;
            this.processed = processed;
            this.message = message;
        }

        /** Makes the mocked mailbox fail at this site and nowhere else. */
        abstract void arrange(ImapMailPollerTest test) throws Exception;
    }

    @ParameterizedTest
    @EnumSource(DiagnosticSite.class)
    void diagnosticNamesOnlyTheOperationContextAndExceptionTypes(DiagnosticSite site) throws Exception {
        connectableMailbox();
        site.arrange(this);
        ImapMailPoller poller = poller(new MockEnvironment(), SENTINEL_USER, SENTINEL_PASSWORD);

        assertThat(pollWithMockedSession(poller)).isEqualTo(site.processed);
        assertThat(pollerEvents.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(site.level);
            assertThat(event.getFormattedMessage()).isEqualTo(site.message);
        });
        assertNoEventLeaks();
    }

    static Stream<Arguments> causeChains() {
        MessagingException cyclic = new MessagingException("outer " + SENTINEL_PASSWORD);
        cyclic.setNextException(new IOException("inner " + SENTINEL_CONTENT, cyclic));

        Exception deep = new SocketTimeoutException(SENTINEL_PASSWORD);
        for (int level = 0; level < 14; level++) {
            deep = new IOException("level " + level + " " + SENTINEL_CONTENT, deep);
        }
        String nineIoExceptions = String.join(" <- ", Collections.nCopies(9, "java.io.IOException"));

        return Stream.of(
                Arguments.of(Named.of("cycle ends at the first repeated throwable", cyclic),
                        "(jakarta.mail.MessagingException <- java.io.IOException)"),
                Arguments.of(Named.of("chain beyond ten types is cut off", new MessagingException("deep", deep)),
                        "(jakarta.mail.MessagingException <- " + nineIoExceptions + " <- ...)"),
                Arguments.of(Named.of("protocol exception without a synthetic response",
                        new MessagingException("NO " + SENTINEL_USER, new ProtocolException(SENTINEL_PASSWORD))),
                        "(jakarta.mail.MessagingException <- org.eclipse.angus.mail.iap.ProtocolException)"),
                Arguments.of(Named.of("runtime failure without a cause",
                        new IllegalStateException(SENTINEL_CONTENT)), "(java.lang.IllegalStateException)"));
    }

    @ParameterizedTest
    @MethodSource("causeChains")
    void exceptionTypesWalkTheCauseChainSafely(Throwable failure, String expectedTypes) throws Exception {
        connectableMailbox();
        doThrow(failure).when(store).connect(anyString(), anyInt(), anyString(), anyString());
        ImapMailPoller poller = poller(new MockEnvironment(), SENTINEL_USER, SENTINEL_PASSWORD);

        assertThat(pollWithMockedSession(poller)).isZero();
        assertThat(pollerEvents.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("Failed to connect to IMAP folder INBOX on imap.test.invalid:993 " + expectedTypes);
        assertNoEventLeaks();
    }

    /**
     * Each mailbox setting null, empty or blank while the other two hold a loopback host or sentinels, and all three
     * empty at once, with the WARN and the DEBUG line each case must log.
     */
    static Stream<Arguments> unconfiguredMailboxes() {
        String loopback = "127.0.0.1";
        List<Arguments> cases = new ArrayList<>();
        for (String blank : Arrays.asList(null, "", "   ")) {
            String shown = blank == null ? "null" : "\"" + blank + "\"";
            cases.add(Arguments.of(Named.of("imap.host " + shown, blank), SENTINEL_USER, SENTINEL_PASSWORD,
                    "imap.host (IMAP_HOST) is not configured; IMAPS polling is skipped",
                    "imap.host is not configured; IMAPS poll skipped"));
            cases.add(Arguments.of(Named.of("imap.user " + shown, loopback), blank, SENTINEL_PASSWORD,
                    "imap.user (IMAP_USER) is not configured; IMAPS polling is skipped",
                    "imap.user is not configured; IMAPS poll skipped"));
            cases.add(Arguments.of(Named.of("imap.password " + shown, loopback), SENTINEL_USER, blank,
                    "imap.password (IMAP_PASSWORD) is not configured; IMAPS polling is skipped",
                    "imap.password is not configured; IMAPS poll skipped"));
        }
        cases.add(Arguments.of(Named.of("all three empty, as application.yml ships them", ""), "", "",
                "imap.host (IMAP_HOST), imap.user (IMAP_USER), imap.password (IMAP_PASSWORD) are not configured; "
                        + "IMAPS polling is skipped",
                "imap.host, imap.user, imap.password are not configured; IMAPS poll skipped"));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unconfiguredMailboxes")
    @DisplayName("a null or blank imap.host, imap.user or imap.password skips every poll without connecting; "
            + "one WARN names the missing keys")
    void unconfiguredMailboxIsNeverConnected(String host, String user, String password, String warning, String debug)
            throws Exception {
        // Listens on every local address and never accepts: a connection from any poll, a blank host resolving to
        // the loopback address included, would complete in its backlog. The short deadlines bound a poll that did
        // connect, which would then stall in the TLS handshake.
        try (ServerSocket listener = new ServerSocket(0)) {
            String deadline = String.valueOf(STALL_DEADLINE_MS);
            ImapMailPoller poller = new ImapMailPoller(service, trustingEnvironment(), host, listener.getLocalPort(),
                    user, password, FOLDER, deadline, deadline, deadline, DEFAULT_FREQUENCY);

            for (int poll = 0; poll < 3; poll++) {
                assertThat(assertDoesNotThrow(poller::pollOnce)).isZero();
            }

            listener.setSoTimeout(200);
            assertThatExceptionOfType(SocketTimeoutException.class).as("no poll connected to the listener")
                    .isThrownBy(listener::accept);
        }
        verifyNoInteractions(service);
        // The WARN once, at the first skipped poll; a DEBUG line at every skipped poll; nothing else, so no value.
        assertThat(pollerEvents.list).extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(tuple(Level.WARN, warning), tuple(Level.DEBUG, debug), tuple(Level.DEBUG, debug),
                        tuple(Level.DEBUG, debug));
        assertNoEventLeaks();
    }

    @Test
    void deadlinesDefaultToTenSecondsWhenNothingIsConfigured() {
        Properties properties = pollerFromContext(new MockEnvironment()).sessionProperties();

        assertThat(deadlinesOf(properties)).containsExactly("10000", "10000", "10000");
    }

    @Test
    void applicationYmlDeclaresTenSecondDeadlines() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        assertThat(Stream.of("imap.connect-timeout", "imap.read-timeout", "imap.write-timeout")
                .map(environment::getProperty)).containsExactly("10000", "10000", "10000");
        assertThat(deadlinesOf(pollerFromContext(environment).sessionProperties()))
                .containsExactly("10000", "10000", "10000");
    }

    @Test
    void dedicatedKeysSetTheDeadlines() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("imap.host", HOST)
                .withProperty("imap.connect-timeout", "1500")
                .withProperty("imap.read-timeout", "2500")
                .withProperty("imap.write-timeout", "3500");

        Properties properties = pollerFromContext(environment).sessionProperties();

        assertThat(deadlinesOf(properties)).containsExactly("1500", "2500", "3500");
        assertThat(properties).containsEntry("mail.store.protocol", "imaps").containsEntry("mail.imaps.host", HOST)
                .containsEntry("mail.imaps.port", "993");
    }

    @Test
    void positiveImapPropertiesEntriesReplaceTheDeadlines() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("imap.read-timeout", "2500")
                .withProperty("imap.properties[mail.imaps.connectiontimeout]", "750")
                .withProperty("imap.properties[mail.imaps.timeout]", "1250")
                .withProperty("imap.properties[mail.imaps.writetimeout]", "1750")
                .withProperty("imap.properties[mail.imaps.ssl.trust]", "*");

        Properties properties = pollerFromContext(environment).sessionProperties();

        assertThat(deadlinesOf(properties)).containsExactly("750", "1250", "1750");
        assertThat(properties).containsEntry("mail.imaps.ssl.trust", "*");
    }

    static Stream<Arguments> refusedDeadlines() {
        List<String> keys = List.of("imap.connect-timeout", "imap.read-timeout", "imap.write-timeout",
                "imap.properties[mail.imaps.connectiontimeout]", "imap.properties[mail.imaps.timeout]",
                "imap.properties[mail.imaps.writetimeout]");
        List<String> values = List.of("0", "-1", "", " ", "ten", "0x2710", "+500", "1.5", "2147483648");
        return keys.stream().flatMap(key -> values.stream().map(value -> Arguments.of(key, value)));
    }

    @ParameterizedTest(name = "{0} = \"{1}\"")
    @MethodSource("refusedDeadlines")
    void deadlineThatIsNotAPositiveDecimalIntIsRefusedAtConstruction(String key, String value) {
        assertThatThrownBy(() -> pollerWithDeadline(key, value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(key + " must be a positive number of milliseconds (a decimal whole number from 1 to "
                        + "2147483647)");
    }

    @ParameterizedTest(name = "imap.check-frequency = \"{0}\"")
    @ValueSource(strings = {"0", "-1", "PT0S", "PT-1S", "abc", ""})
    void frequencyThatIsNotPositiveIsRefusedAtConstructionNamingKeyAndValue(String frequency) {
        assertThatThrownBy(() -> pollerWithFrequency(frequency))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(frequencyRefusal(frequency));
    }

    @ParameterizedTest(name = "imap.check-frequency = \"{0}\"")
    @ValueSource(strings = {"1", "100", "PT10S", "pt0.5s"})
    void positiveFrequencyInMillisecondsOrIso8601Constructs(String frequency) {
        assertThatCode(() -> pollerWithFrequency(frequency)).doesNotThrowAnyException();
    }

    @Test
    void nonPositiveFrequencyFromTheEnvironmentStopsSpringFromCreatingThePoller() {
        MockEnvironment environment = new MockEnvironment().withProperty("imap.check-frequency", "-5");

        assertThatThrownBy(() -> pollerFromContext(environment))
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(frequencyRefusal("-5"));
    }

    @Test
    void pollsAGreenMailMailboxWithinTheDefaultDeadlines() throws Exception {
        GreenMail greenMail = new GreenMail(smtpAndImaps());
        greenMail.start();
        try {
            greenMail.setUser("poller@localhost", "poller@localhost", "poller-test-pw");
            // CRLF line ends, so the bytes survive the line-end canonicalization of the SMTP transfer unchanged.
            byte[] csv = "orderId,name,units,pricePerUnit\r\n1,aaa,2.0,10\r\n".getBytes(US_ASCII);
            GreenMailUtil.sendAttachmentEmail("poller@localhost", "sender@localhost", "orders", "see attachment",
                    csv, "application/octet-stream", "input.csv", "input.csv", greenMail.getSmtp().getServerSetup());
            assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
            AtomicReference<Map<String, DataHandler>> received = new AtomicReference<>();
            when(service.importAttachments(any())).thenAnswer(invocation -> {
                received.set(invocation.getArgument(0));
                return null;
            });
            ImapMailPoller poller = new ImapMailPoller(service, trustingEnvironment(), "127.0.0.1",
                    greenMail.getImaps().getPort(), "poller@localhost", "poller-test-pw", FOLDER, DEFAULT_DEADLINE,
                    DEFAULT_DEADLINE, DEFAULT_DEADLINE, DEFAULT_FREQUENCY);

            assertThat(poller.pollOnce()).isEqualTo(1);

            assertThat(received.get()).containsKey("input.csv");
            try (InputStream content = received.get().get("input.csv").getInputStream()) {
                assertThat(content.readAllBytes()).isEqualTo(csv);
            }
            assertThat(greenMail.getReceivedMessages()).as("expunged after the poll").isEmpty();
            assertThat(pollerEvents.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        } finally {
            greenMail.stop();
        }
    }

    @Test
    void stalledTlsHandshakeEndsEachPollAtTheReadDeadline() throws Exception {
        try (ScriptedPeer peer = ScriptedPeer.silent()) {
            pollTwiceAgainstStall(stallPoller(peer.port()));

            assertThat(errorMessages()).hasSize(2).allSatisfy(message -> assertThat(message)
                    .startsWith("Failed to connect to IMAP folder INBOX on 127.0.0.1:" + peer.port() + " (")
                    .contains("java.net.SocketTimeoutException"));
        }
    }

    @Test
    void withheldLoginReplyEndsEachPollAtTheReadDeadline() throws Exception {
        try (ScriptedPeer peer = ScriptedPeer.withholding("LOGIN")) {
            List<Long> returnedAt = pollTwiceAgainstStall(stallPoller(peer.port()));

            String expected = "Failed to connect to IMAP folder INBOX on 127.0.0.1:" + peer.port()
                    + " (jakarta.mail.MessagingException <- org.eclipse.angus.mail.iap.ConnectionException"
                    + " <- java.net.SocketTimeoutException)";
            assertThat(errorMessages()).containsExactly(expected, expected);
            assertWaitedForTheReadDeadline(peer.withheldAt(), returnedAt);
        }
    }

    @Test
    void withheldFetchReplyEndsEachPollAtTheReadDeadline() throws Exception {
        try (ScriptedPeer peer = ScriptedPeer.withholding("FETCH")) {
            List<Long> returnedAt = pollTwiceAgainstStall(stallPoller(peer.port()));

            // Angus Mail reports a connection lost during a message FETCH as a FolderClosedException that carries
            // no cause; the timeout is proved by the withheld FETCH and the wait for the read deadline.
            String expected = "Error processing IMAP message 1 (jakarta.mail.FolderClosedException)";
            assertThat(errorMessages()).containsExactly(expected, expected);
            assertThat(peer.commands()).contains("SELECT");
            assertWaitedForTheReadDeadline(peer.withheldAt(), returnedAt);
        }
    }

    /**
     * Polls twice against a stalling peer. Each poll must return 0 without throwing, within
     * {@link #STALLED_POLL_BOUND}; the second one also proves the first released the synchronized poll and that the
     * next poll connects anew. The service is never reached.
     *
     * @return the {@link System#nanoTime()} at which each poll returned
     */
    private List<Long> pollTwiceAgainstStall(ImapMailPoller poller) {
        List<Long> returnedAt = new ArrayList<>();
        for (int poll = 0; poll < 2; poll++) {
            assertThat(assertTimeoutPreemptively(STALLED_POLL_BOUND, poller::pollOnce)).isZero();
            returnedAt.add(System.nanoTime());
        }
        verifyNoInteractions(service);
        return returnedAt;
    }

    /** Each poll returned only after the read deadline had passed since its command was withheld. */
    private static void assertWaitedForTheReadDeadline(List<Long> withheldAt, List<Long> returnedAt) {
        assertThat(withheldAt).hasSameSizeAs(returnedAt);
        for (int poll = 0; poll < returnedAt.size(); poll++) {
            assertThat(TimeUnit.NANOSECONDS.toMillis(returnedAt.get(poll) - withheldAt.get(poll)))
                    .as("ms between withholding the reply and the end of poll %d", poll + 1)
                    .isGreaterThanOrEqualTo(STALL_DEADLINE_MS * 8L / 10);
        }
    }

    private List<String> errorMessages() {
        return pollerEvents.list.stream().filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private ImapMailPoller stallPoller(int port) {
        String deadline = String.valueOf(STALL_DEADLINE_MS);
        return new ImapMailPoller(service, trustingEnvironment(), "127.0.0.1", port, STALL_USER, STALL_PASSWORD,
                FOLDER, deadline, deadline, deadline, DEFAULT_FREQUENCY);
    }

    /**
     * A socket bound to a free port on 127.0.0.1 that never listens or connects: a connection to its port is refused
     * while it is open, and with SO_REUSEADDR off no other socket can bind that port meanwhile.
     */
    private static Socket reserveRefusingPort() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
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

    /**
     * GreenMail's SMTP and IMAPS test setups on dynamic ports, each allowed {@link #SERVER_STARTUP_TIMEOUT_MS} to
     * start. {@code dynamicPort} returns copies, so the shared {@link ServerSetupTest} presets stay unchanged.
     */
    private static ServerSetup[] smtpAndImaps() {
        ServerSetup[] setups =
                ServerSetup.dynamicPort(new ServerSetup[] {ServerSetupTest.SMTP, ServerSetupTest.IMAPS});
        for (ServerSetup setup : setups) {
            setup.setServerStartupTimeout(SERVER_STARTUP_TIMEOUT_MS);
        }
        return setups;
    }

    /** {@code imap.properties} trusting the self-signed certificate GreenMail's TLS socket factory presents. */
    private static MockEnvironment trustingEnvironment() {
        return new MockEnvironment()
                .withProperty("imap.properties[mail.imaps.ssl.trust]", "*")
                .withProperty("imap.properties[mail.imaps.ssl.checkserveridentity]", "false");
    }

    /**
     * Builds the poller as Spring does, so its {@code @Value} defaults apply; a plain context without scheduling,
     * which never polls.
     */
    private ImapMailPoller pollerFromContext(MockEnvironment environment) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.registerBean(AttachmentImportService.class, () -> service);
            context.register(ImapMailPoller.class);
            context.refresh();
            return context.getBean(ImapMailPoller.class);
        }
    }

    /** A poller whose deadlines are the defaults except {@code key}, a dedicated key or an imap.properties entry. */
    private ImapMailPoller pollerWithDeadline(String key, String value) {
        MockEnvironment environment = new MockEnvironment();
        String connect = DEFAULT_DEADLINE;
        String read = DEFAULT_DEADLINE;
        String write = DEFAULT_DEADLINE;
        switch (key) {
            case "imap.connect-timeout" -> connect = value;
            case "imap.read-timeout" -> read = value;
            case "imap.write-timeout" -> write = value;
            default -> environment.setProperty(key, value);
        }
        return new ImapMailPoller(service, environment, HOST, PORT, STALL_USER, STALL_PASSWORD, FOLDER, connect,
                read, write, DEFAULT_FREQUENCY);
    }

    /** A poller with the default deadlines and {@code frequency} as its {@code imap.check-frequency}. */
    private ImapMailPoller pollerWithFrequency(String frequency) {
        return new ImapMailPoller(service, new MockEnvironment(), HOST, PORT, STALL_USER, STALL_PASSWORD, FOLDER,
                DEFAULT_DEADLINE, DEFAULT_DEADLINE, DEFAULT_DEADLINE, frequency);
    }

    /** The refusal of {@code frequency} as {@code imap.check-frequency}, naming the key and the quoted value. */
    private static String frequencyRefusal(String frequency) {
        return "Invalid imap.check-frequency value \"" + frequency + "\": the polling frequency must be a positive "
                + "number of milliseconds or a positive ISO-8601 duration";
    }

    private static List<String> deadlinesOf(Properties properties) {
        return Stream.of("mail.imaps.connectiontimeout", "mail.imaps.timeout", "mail.imaps.writetimeout")
                .map(properties::getProperty).toList();
    }

    /** A mocked session whose store connects and opens the folder, until a site's arrangement says otherwise. */
    private void connectableMailbox() throws MessagingException {
        when(session.getStore("imaps")).thenReturn(store);
        when(store.getFolder(FOLDER)).thenReturn(folder);
    }

    /**
     * Runs one poll, which must not throw, with {@link Session#getInstance(Properties)} answering the mocked
     * session. Every other static method of {@link Session} stays real, among them the default instance through
     * which a detached {@link MimeMessage} copy streams its bytes.
     */
    private int pollWithMockedSession(ImapMailPoller poller) {
        try (MockedStatic<Session> sessions = mockStatic(Session.class, invocation ->
                "getInstance".equals(invocation.getMethod().getName()) ? session : invocation.callRealMethod())) {
            int processed = assertDoesNotThrow(poller::pollOnce);
            sessions.verify(() -> Session.getInstance(any(Properties.class)));
            return processed;
        }
    }

    private ImapMailPoller poller(MockEnvironment environment, String user, String password) {
        return new ImapMailPoller(service, environment, HOST, PORT, user, password, FOLDER, DEFAULT_DEADLINE,
                DEFAULT_DEADLINE, DEFAULT_DEADLINE, DEFAULT_FREQUENCY);
    }

    /** No captured event, on any logger, quotes a sentinel or carries a throwable. */
    private void assertNoEventLeaks() {
        assertThat(allEvents.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(SENTINEL_USER, SENTINEL_PASSWORD, SENTINEL_CONTENT);
            assertThat(event.getThrowableProxy()).isNull();
            Object[] arguments = event.getArgumentArray();
            assertThat(arguments == null ? List.of() : Arrays.asList(arguments))
                    .noneMatch(Throwable.class::isInstance);
        });
    }

    /**
     * A parsed message with the folder number {@code number} whose CSV attachment quotes the sentinel content; it
     * is already saved, so copying it writes its parsed bytes unchanged.
     */
    private static MimeMessage numberedMessage(int number) throws MessagingException {
        String mime = "Subject: orders\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"part\"\r\n"
                + "\r\n"
                + "--part\r\n"
                + "Content-Type: application/octet-stream; name=input.csv\r\n"
                + "Content-Disposition: attachment; filename=input.csv\r\n"
                + "\r\n"
                + "orderId,name,units,pricePerUnit\r\n"
                + "1," + SENTINEL_CONTENT + ",2.0,10\r\n"
                + "--part--\r\n";
        return new MimeMessage(null, new ByteArrayInputStream(mime.getBytes(US_ASCII))) {
            {
                setMessageNumber(number);
            }
        };
    }

    @Test
    @DisplayName("poll() is scheduled with the original checkFrequency of 100 ms; "
            + "pollOnce() is a public synchronized int")
    void pollIsScheduledWithOriginalCheckFrequency() throws NoSuchMethodException {
        // The class literal, not a bean, so no proxy hides the annotation.
        Scheduled scheduled = ImapMailPoller.class.getDeclaredMethod("poll").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${imap.check-frequency:100}");
        assertThat(scheduled.initialDelayString()).isEqualTo("${imap.initial-delay:0}");
        Method pollOnce = ImapMailPoller.class.getDeclaredMethod("pollOnce");
        assertThat(Modifier.isPublic(pollOnce.getModifiers())).isTrue();
        assertThat(Modifier.isSynchronized(pollOnce.getModifiers())).isTrue();
        assertThat(pollOnce.getReturnType()).isEqualTo(int.class);
    }

    /** Cases against the class-wide GreenMail mailbox, each with a poller constructed for it. */
    @Nested
    @DisplayName("polling a GreenMail IMAPS mailbox")
    class PollingAGreenMailMailbox {

        @BeforeEach
        void emptyMailbox() throws FolderException {
            mailbox.purgeEmailFromAllMailboxes();
        }

        @Test
        @DisplayName("a message already flagged \\Seen is not handed to the service and stays in the mailbox")
        void seenMessageIsSkipped() throws Exception {
            send(reportMail());
            setFlag(0, Flags.Flag.SEEN);

            assertThat(mailboxPoller(RECEIVER_PASSWORD).pollOnce()).isZero();

            verifyNoInteractions(service);
            assertThat(inboxCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a message already flagged \\Deleted is not handed to the service")
        void deletedMessageIsSkipped() throws Exception {
            send(reportMail());
            setFlag(0, Flags.Flag.DELETED);

            // The poll's closing expunge may remove the message, so the mailbox is not inspected afterwards.
            assertThat(mailboxPoller(RECEIVER_PASSWORD).pollOnce()).isZero();

            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("a mail without attachments reaches the service with an empty HashMap and is expunged")
        void plainTextMailReachesServiceWithEmptyMap() throws Exception {
            ImportCalls calls = new ImportCalls();
            doAnswer(calls).when(service).importAttachments(any());
            MimeMessage plain = newMessage();
            plain.setText("plain body");
            send(plain);

            // Rejecting the empty map is AttachmentImportService's behaviour, tested there; the service is mocked here.
            assertThat(mailboxPoller(RECEIVER_PASSWORD).pollOnce()).isEqualTo(1);

            verify(service).importAttachments(any());
            assertThat(calls.map(0)).isExactlyInstanceOf(HashMap.class).isEmpty();
            assertThat(inboxCount()).isZero();
        }

        @Test
        @DisplayName("an import failure is logged at ERROR, the next message is still processed and both are expunged")
        void serviceFailureIsLoggedAndPollContinues() throws Exception {
            ImportCalls calls = new ImportCalls();
            doThrow(new IllegalStateException("boom")).doAnswer(calls).when(service).importAttachments(any());
            send(reportMail(), reportMail());
            ImapMailPoller poller = mailboxPoller(RECEIVER_PASSWORD);
            int[] processed = new int[1];

            assertThatCode(() -> processed[0] = poller.pollOnce()).doesNotThrowAnyException();

            // pollOnce counts the messages handed to the service, whether or not the import succeeded.
            assertThat(processed[0]).isEqualTo(2);
            verify(service, times(2)).importAttachments(any());
            assertThat(calls.bytes(0, "report")).isEqualTo(deliveredOverSmtp(inputCsv()));
            // The poller's diagnostics name the failure by its exception types and never attach the throwable, whose
            // message could quote mail content (the log-safety rule of ImapMailPoller).
            assertThat(errorMessages())
                    .containsExactly("Error processing IMAP message 1 (java.lang.IllegalStateException)");
            assertThat(pollerEvents.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            // Both messages were flagged \Deleted before the service ran, so the closing expunge removed both.
            assertThat(inboxCount()).isZero();
        }

        @Test
        @DisplayName("an unreachable IMAPS port is logged at ERROR and the poll returns 0 without throwing")
        void unreachablePortIsLoggedAndPollReturnsZero() throws Exception {
            // A bound socket that never listens refuses connections and keeps the port from being taken meanwhile.
            try (Socket reserved = reserveRefusingPort()) {
                int closedPort = reserved.getLocalPort();
                ImapMailPoller poller = new ImapMailPoller(service, trustingEnvironment(), "127.0.0.1", closedPort,
                        RECEIVER, RECEIVER_PASSWORD, FOLDER, DEFAULT_DEADLINE, DEFAULT_DEADLINE, DEFAULT_DEADLINE,
                        DEFAULT_FREQUENCY);

                assertThat(assertDoesNotThrow(poller::pollOnce)).isZero();

                verifyNoInteractions(service);
                assertThat(errorMessages()).singleElement().asString()
                        .startsWith("Failed to connect to IMAP folder INBOX on 127.0.0.1:" + closedPort + " (")
                        .contains("java.net.ConnectException");
            }
        }

        @Test
        @DisplayName("a rejected password is logged at ERROR, the poll returns 0 and the mail stays in the mailbox")
        void wrongPasswordIsLoggedAndNothingIsConsumed() throws Exception {
            send(reportMail());
            ImapMailPoller poller = mailboxPoller("wrong-password");

            assertThat(assertDoesNotThrow(poller::pollOnce)).isZero();

            verifyNoInteractions(service);
            assertThat(errorMessages()).containsExactly("Failed to connect to IMAP folder INBOX on 127.0.0.1:"
                    + mailbox.getImaps().getPort() + " (jakarta.mail.AuthenticationFailedException)");
            assertThat(inboxCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("attachment names follow Mule 3.8 MailUtils: description, file name, disposition, index; "
                + "multipart/mixed flattened; a taken name gets the first free n_ prefix")
        void attachmentKeysFollowMule38MailUtils() throws Exception {
            MimeBodyPart described = textPart("p1");
            described.setDescription("described");
            described.setFileName("ignored.csv");
            MimeBodyPart named = textPart("p2");
            named.setFileName("named.csv");
            MimeBodyPart inline = textPart("p3");
            inline.setDisposition(Part.INLINE);
            MimeBodyPart nestedNamed = textPart("n1");
            nestedNamed.setFileName("nested.csv");
            MimeBodyPart nested = new MimeBodyPart();
            nested.setContent(new MimeMultipart("mixed", nestedNamed, textPart("n2")));
            MimeBodyPart duplicate = textPart("p6");
            duplicate.setFileName("dup.csv");
            MimeBodyPart secondDuplicate = textPart("p7");
            secondDuplicate.setFileName("dup.csv");
            MimeMessage message = newMessage();
            message.setContent(new MimeMultipart(described, named, inline, textPart("p4"), nested, duplicate,
                    secondDuplicate));
            ImportCalls calls = new ImportCalls();
            doAnswer(calls).when(service).importAttachments(any());
            send(message);

            assertThat(mailboxPoller(RECEIVER_PASSWORD).pollOnce()).isEqualTo(1);

            ArgumentCaptor<Map<String, DataHandler>> captor = attachmentMapCaptor();
            verify(service).importAttachments(captor.capture());
            assertThat(captor.getValue()).isExactlyInstanceOf(HashMap.class).isSameAs(calls.map(0));
            // Expected from Mule 3.8.0 MailUtils.getAttachments and getAttachmentName: the index of unnamed parts is
            // local to each multipart level ("0" at the top, "0" again inside the nested multipart/mixed), and a name
            // already taken becomes the first free "<n>_<name>" with n counted from 0 ("0_0", "0_dup.csv").
            assertThat(calls.texts(0)).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "described", "p1",
                    "named.csv", "p2",
                    "inline", "p3",
                    "0", "p4",
                    "nested.csv", "n1",
                    "0_0", "n2",
                    "dup.csv", "p6",
                    "0_dup.csv", "p7"));
        }

        private ImapMailPoller mailboxPoller(String password) {
            return new ImapMailPoller(service, trustingEnvironment(), "127.0.0.1", mailbox.getImaps().getPort(),
                    RECEIVER, password, FOLDER, DEFAULT_DEADLINE, DEFAULT_DEADLINE, DEFAULT_DEADLINE,
                    DEFAULT_FREQUENCY);
        }
    }

    /**
     * The happy path through the poller the application itself wires: the {@code test} profile's
     * {@code application-test.yml} supplies host, user, password, the GreenMail trust settings and the held-back
     * scheduled poll, and only the port GreenMail bound is registered here.
     */
    @Nested
    @SpringBootTest(classes = ImportingAnEmailAttachmentUsingTheIMAPConnectorApplication.class)
    @ActiveProfiles("test")
    @DisplayName("as wired by the application under the test profile")
    class AsWiredByTheTestProfile {

        @MockBean
        AttachmentImportService importService;

        @Autowired
        ImapMailPoller poller;

        @DynamicPropertySource
        static void mailboxPort(DynamicPropertyRegistry registry) {
            registry.add("imap.port", () -> mailbox.getImaps().getPort());
        }

        @BeforeEach
        void emptyMailbox() throws FolderException {
            mailbox.purgeEmailFromAllMailboxes();
        }

        @Test
        @DisplayName("the original test's mail hands its CSV attachment to the service, is expunged and polled once")
        void pollOnceHandsCsvAttachmentToServiceAndExpunges() throws Exception {
            byte[] input = inputCsv();
            assertThat(input).as("main resource /input.csv").hasSize(57);
            ImportCalls calls = new ImportCalls();
            doAnswer(calls).when(importService).importAttachments(any());
            send(reportMail());

            assertThat(poller.pollOnce()).isEqualTo(1);

            verify(importService).importAttachments(any());
            assertThat(calls.map(0)).isExactlyInstanceOf(HashMap.class).containsOnlyKeys("report");
            assertThat(calls.bytes(0, "report")).isEqualTo(deliveredOverSmtp(input));
            assertThat(inboxCount()).as("flagged \\Deleted and expunged").isZero();
            assertThat(poller.pollOnce()).isZero();
            verifyNoMoreInteractions(importService);
            assertThat(errorMessages()).isEmpty();
        }
    }

    /** A new mail from the sender to the receiver with the original test's subject. */
    private static MimeMessage newMessage() throws MessagingException {
        MimeMessage message = new MimeMessage(GreenMailUtil.getSession(mailbox.getSmtp().getServerSetup()));
        message.setFrom(new InternetAddress(SENDER));
        message.addRecipient(Message.RecipientType.TO, new InternetAddress(RECEIVER));
        message.setSubject(SUBJECT);
        return message;
    }

    /**
     * The original integration test's mail: a single-part multipart holding {@code input.csv} as
     * {@code application/octet-stream} under the file name {@code report}.
     */
    private static MimeMessage reportMail() throws MessagingException, IOException {
        MimeBodyPart report = new MimeBodyPart();
        report.setDataHandler(new DataHandler(new ByteArrayDataSource(inputCsv(), "application/octet-stream")));
        report.setFileName("report");
        MimeMessage message = newMessage();
        message.setContent(new MimeMultipart(report));
        return message;
    }

    private static MimeBodyPart textPart(String text) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setText(text);
        return part;
    }

    /** The main resource {@code input.csv}, the original test's attachment. */
    private static byte[] inputCsv() throws IOException {
        try (InputStream csv = ImapMailPollerTest.class.getResourceAsStream("/input.csv")) {
            assertThat(csv).as("main resource /input.csv").isNotNull();
            return csv.readAllBytes();
        }
    }

    /**
     * The bytes the mailbox holds for an attachment sent as the original test sent it. Jakarta Mail gives all-ASCII
     * {@code application/octet-stream} content the 7bit transfer encoding, and SMTP carries 7bit content with CRLF
     * line ends, so each LF of {@code input.csv} arrives as CRLF.
     */
    private static byte[] deliveredOverSmtp(byte[] sent) {
        return new String(sent, US_ASCII).replace("\n", "\r\n").getBytes(US_ASCII);
    }

    /** Sends each mail over GreenMail's SMTP, authenticated as the sender like the original test, and awaits them. */
    private static void send(MimeMessage... messages) throws MessagingException {
        for (MimeMessage message : messages) {
            Transport.send(message, SENDER, SENDER_PASSWORD);
        }
        assertThat(mailbox.waitForIncomingEmail(5000, messages.length)).as("mail delivered").isTrue();
    }

    private static int inboxCount() throws MessagingException {
        Store store = mailbox.getImaps().createStore();
        store.connect(RECEIVER, RECEIVER_PASSWORD);
        try {
            Folder inbox = store.getFolder(FOLDER);
            inbox.open(Folder.READ_ONLY);
            try {
                return inbox.getMessageCount();
            } finally {
                inbox.close(false);
            }
        } finally {
            store.close();
        }
    }

    /** Sets {@code flag} on the inbox message at 0-based {@code index}, closing without expunge. */
    private static void setFlag(int index, Flags.Flag flag) throws MessagingException {
        Store store = mailbox.getImaps().createStore();
        store.connect(RECEIVER, RECEIVER_PASSWORD);
        try {
            Folder inbox = store.getFolder(FOLDER);
            inbox.open(Folder.READ_WRITE);
            try {
                inbox.getMessage(index + 1).setFlag(flag, true);
            } finally {
                inbox.close(false);
            }
        } finally {
            store.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, DataHandler>> attachmentMapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    /**
     * Records every attachment map handed to the service and reads each attachment's bytes during the call, while the
     * poll that produced it is still running.
     */
    private static final class ImportCalls implements Answer<Path> {

        private final List<Map<String, DataHandler>> maps = new CopyOnWriteArrayList<>();
        private final List<Map<String, byte[]>> contents = new CopyOnWriteArrayList<>();

        @Override
        public Path answer(InvocationOnMock invocation) throws IOException {
            Map<String, DataHandler> attachments = invocation.getArgument(0);
            Map<String, byte[]> read = new TreeMap<>();
            for (Map.Entry<String, DataHandler> attachment : attachments.entrySet()) {
                try (InputStream content = attachment.getValue().getInputStream()) {
                    read.put(attachment.getKey(), content.readAllBytes());
                }
            }
            maps.add(attachments);
            contents.add(read);
            return Path.of("orders.xml");
        }

        /** The map exactly as the service received it in answered call {@code call}. */
        Map<String, DataHandler> map(int call) {
            assertThat(maps).as("answered service calls").hasSizeGreaterThan(call);
            return maps.get(call);
        }

        byte[] bytes(int call, String name) {
            assertThat(contents).as("answered service calls").hasSizeGreaterThan(call);
            assertThat(contents.get(call)).containsKey(name);
            return contents.get(call).get(name);
        }

        /** Every attachment of answered call {@code call} as UTF-8 text, by name. */
        Map<String, String> texts(int call) {
            assertThat(contents).as("answered service calls").hasSizeGreaterThan(call);
            Map<String, String> texts = new TreeMap<>();
            contents.get(call).forEach((name, content) -> texts.put(name, new String(content, UTF_8)));
            return texts;
        }
    }

    /**
     * A local peer on an ephemeral loopback port that never answers in time. A silent peer accepts TCP connections
     * and never writes a byte, so the client's TLS handshake stalls; a withholding peer plays a minimal IMAP4rev1
     * server over GreenMail's TLS server socket factory and never replies to one command. Every connection is served
     * alike, because Angus Mail may open another one after a timeout, for example to check the store with NOOP.
     */
    private static final class ScriptedPeer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final String withheldCommand;
        private final ExecutorService threads = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "scripted-imaps-peer");
            thread.setDaemon(true);
            return thread;
        });
        private final List<Socket> connections = new CopyOnWriteArrayList<>();
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private final List<Long> withheldAt = new CopyOnWriteArrayList<>();

        private ScriptedPeer(ServerSocket serverSocket, String withheldCommand) {
            this.serverSocket = serverSocket;
            this.withheldCommand = withheldCommand;
            threads.submit(this::acceptConnections);
        }

        /** A plain TCP peer that accepts every connection and never writes to it. */
        static ScriptedPeer silent() throws IOException {
            return new ScriptedPeer(new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")), null);
        }

        /** A TLS IMAP peer that answers every command except {@code command}, whose reply it withholds. */
        static ScriptedPeer withholding(String command) throws IOException {
            return new ScriptedPeer(new DummySSLServerSocketFactory().createServerSocket(0, 50,
                    InetAddress.getByName("127.0.0.1")), command);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        /** The command names received so far, over every connection, in arrival order. */
        List<String> commands() {
            return List.copyOf(commands);
        }

        /** The {@link System#nanoTime()} at which each withheld command arrived. */
        List<Long> withheldAt() {
            return List.copyOf(withheldAt);
        }

        /** Accepts until the server socket is closed, whose exception then ends this task. */
        private Void acceptConnections() throws IOException {
            while (true) {
                Socket connection = serverSocket.accept();
                connections.add(connection);
                threads.submit(() -> serve(connection));
            }
        }

        /** Serves one connection until the client closes it or the peer is closed. */
        private Void serve(Socket connection) throws IOException {
            try (connection) {
                InputStream in = connection.getInputStream();
                if (withheldCommand == null) {
                    in.transferTo(OutputStream.nullOutputStream());
                    return null;
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, US_ASCII));
                Writer writer = new OutputStreamWriter(connection.getOutputStream(), US_ASCII);
                reply(writer, "* OK [CAPABILITY IMAP4rev1] scripted peer ready");
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    String[] words = line.split(" ");
                    String tag = words[0];
                    int commandIndex = words.length > 2 && "UID".equalsIgnoreCase(words[1]) ? 2 : 1;
                    String command = words.length > commandIndex ? words[commandIndex].toUpperCase(Locale.ROOT) : "";
                    commands.add(command);
                    if (command.equals(withheldCommand)) {
                        withheldAt.add(System.nanoTime());
                        continue;
                    }
                    switch (command) {
                        case "CAPABILITY" -> reply(writer, "* CAPABILITY IMAP4rev1", tag + " OK CAPABILITY done");
                        case "LIST" -> reply(writer, "* LIST () \"/\" INBOX", tag + " OK LIST done");
                        case "SELECT", "EXAMINE" -> reply(writer, "* FLAGS (\\Seen \\Deleted)", "* 1 EXISTS",
                                "* 0 RECENT", "* OK [UIDVALIDITY 1] UIDs valid", tag + " OK [READ-WRITE] SELECT done");
                        case "LOGOUT" -> {
                            reply(writer, "* BYE logging out", tag + " OK LOGOUT done");
                            return null;
                        }
                        default -> reply(writer, tag + " OK " + command + " done");
                    }
                }
                return null;
            }
        }

        private static void reply(Writer writer, String... lines) throws IOException {
            for (String line : lines) {
                writer.write(line + "\r\n");
            }
            writer.flush();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            for (Socket connection : connections) {
                connection.close();
            }
            threads.shutdownNow();
        }
    }
}
