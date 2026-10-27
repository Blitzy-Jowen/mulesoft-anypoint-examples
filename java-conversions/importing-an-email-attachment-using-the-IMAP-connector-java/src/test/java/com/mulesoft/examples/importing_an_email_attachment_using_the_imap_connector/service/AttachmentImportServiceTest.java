/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import jakarta.activation.DataHandler;
import jakarta.mail.util.ByteArrayDataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.mapper.DwCsvReader;
import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.mapper.DwXmlWriter;
import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.mapper.OrdersXmlMapper;

/**
 * Unit test of {@link AttachmentImportService}, which runs what flow {@code imap-to-csvFlow1} of the original
 * {@code imap-to-xml.xml} does after its IMAPS inbound endpoint: the attachments become the payload as a list
 * (lines 11–13), the first one's content is taken ({@code #[payload[0].getContent()]}, line 15), the
 * {@code Map CSV to XML} script turns it into the {@code orders} document (lines 16–30), the document is logged at
 * INFO as {@code Received: <document>} (line 31) and written to {@code orders.xml} (line 32).
 *
 * <p>The real {@link DwCsvReader}, {@link OrdersXmlMapper} and {@link DwXmlWriter} run unmocked, so the written
 * bytes are the DataWeave 1.0 XML writer's: the single-quoted declaration, tab indentation, LF line ends and no line
 * feed after {@code </orders>}. Only the write-failure case mocks {@link FileOutboundWriter}. No Spring context is
 * started.
 */
class AttachmentImportServiceTest {

    private static final String OUTPUT_FILE_NAME = "orders.xml";

    /** The attachment the original test mailed: the carried main resource {@code input.csv}, read as bytes. */
    private static final byte[] INPUT_CSV = readMainResource("/input.csv");

    private static final String INPUT_CSV_TEXT = "orderId,name,units,pricePerUnit\n1,aaa,2.0,10\n2,bbb,4.15,5";

    /** The orders document of {@code input.csv}: 289 UTF-8 bytes ending at {@code </orders>}. */
    private static final String EXPECTED_XML = "<?xml version='1.0' encoding='UTF-8'?>\n"
            + "<orders>\n"
            + "\t<order>\n"
            + "\t\t<orderId>1</orderId>\n"
            + "\t\t<name>aaa</name>\n"
            + "\t\t<units>2.0</units>\n"
            + "\t\t<pricePerUnit>10</pricePerUnit>\n"
            + "\t</order>\n"
            + "\t<order>\n"
            + "\t\t<orderId>2</orderId>\n"
            + "\t\t<name>bbb</name>\n"
            + "\t\t<units>4.15</units>\n"
            + "\t\t<pricePerUnit>5</pricePerUnit>\n"
            + "\t</order>\n"
            + "</orders>";

    private static final int EXPECTED_XML_BYTES = 289;

    private static final String EXPECTED_SHA256 = "c5d9c000e8caa4640d880c08c0f9b75ab2010991b8cf3a93840760af55a906ee";

    private static final String ORIGINAL_PART_NAME = "report";

    private static final String ORIGINAL_PART_CONTENT_TYPE = "application/octet-stream";

    @TempDir
    Path outputDir;

    private Logger serviceLogger;
    private Level savedLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureServiceLog() {
        assertThat(INPUT_CSV).as("main resource /input.csv").hasSize(57);
        assertThat(new String(INPUT_CSV, UTF_8)).isEqualTo(INPUT_CSV_TEXT);

        serviceLogger = (Logger) LoggerFactory.getLogger(AttachmentImportService.class);
        savedLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void releaseServiceLog() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(savedLevel);
    }

    @Test
    void writesTheOrdersDocumentOfTheOriginalAttachment() throws Exception {
        Path written = service(new FileOutboundWriter()).importAttachments(originalAttachment());

        byte[] content = Files.readAllBytes(outputDir.resolve(OUTPUT_FILE_NAME));
        assertThat(content).isEqualTo(EXPECTED_XML.getBytes(UTF_8));
        assertThat(content).hasSize(EXPECTED_XML_BYTES);
        assertThat(sha256(content)).isEqualTo(EXPECTED_SHA256);
        assertThat(written.toAbsolutePath().normalize())
                .isEqualTo(outputDir.resolve(OUTPUT_FILE_NAME).toAbsolutePath().normalize());
        assertOutputIsOnlyOrdersXml();
    }

    @Test
    void logsTheDocumentOnceAtInfo() throws Exception {
        service(new FileOutboundWriter()).importAttachments(originalAttachment());

        assertThat(infoMessages()).containsExactly("Received: " + EXPECTED_XML);
    }

    /**
     * {@link DataHandler#getContent()} yields text when a content handler decodes the part ({@code text/plain}), a
     * stream for a type without one (the original test's {@code application/octet-stream} part), and the object
     * itself for a handler built from bytes; each must yield the same document.
     */
    @ParameterizedTest(name = "{0} content")
    @MethodSource("contentShapes")
    void readsEveryContentShapeAlike(Class<?> contentType, DataHandler attachment) throws Exception {
        assertThat(attachment.getContent()).as("content shape exercised").isInstanceOf(contentType);

        Path written = service(new FileOutboundWriter()).importAttachments(Map.of(ORIGINAL_PART_NAME, attachment));

        byte[] content = Files.readAllBytes(outputDir.resolve(OUTPUT_FILE_NAME));
        assertThat(content).isEqualTo(EXPECTED_XML.getBytes(UTF_8)).hasSize(EXPECTED_XML_BYTES);
        assertThat(written.toAbsolutePath().normalize())
                .isEqualTo(outputDir.resolve(OUTPUT_FILE_NAME).toAbsolutePath().normalize());
        assertOutputIsOnlyOrdersXml();
    }

    static Stream<Arguments> contentShapes() {
        return Stream.of(
                Arguments.of(Named.of("String", String.class),
                        new DataHandler(new String(INPUT_CSV, UTF_8), "text/plain")),
                Arguments.of(Named.of("InputStream", InputStream.class),
                        new DataHandler(new ByteArrayDataSource(INPUT_CSV, ORIGINAL_PART_CONTENT_TYPE))),
                Arguments.of(Named.of("byte[]", byte[].class),
                        new DataHandler(INPUT_CSV, ORIGINAL_PART_CONTENT_TYPE)));
    }

    /**
     * Mule's {@code payload[0]} is the first attachment of the list; the service takes the value the map iterates
     * first, so the expected one is read from the map's own iteration, never from the insertion order.
     */
    @Test
    void importsOnlyTheFirstIteratedAttachment() throws Exception {
        Map<String, String> expectedXmlByKey = Map.of(
                "report", singleOrderXml("7", "x", "1", "1"),
                "alpha", singleOrderXml("8", "y", "2", "3"),
                "zeta-2", singleOrderXml("9", "z", "4.5", "6"));
        Map<String, DataHandler> attachments = new HashMap<>();
        attachments.put("report", csvAttachment("orderId,name,units,pricePerUnit\n7,x,1,1"));
        attachments.put("alpha", csvAttachment("orderId,name,units,pricePerUnit\n8,y,2,3"));
        attachments.put("zeta-2", csvAttachment("orderId,name,units,pricePerUnit\n9,z,4.5,6"));
        String firstKey = attachments.entrySet().iterator().next().getKey();

        Path written = service(new FileOutboundWriter()).importAttachments(attachments);

        assertThat(Files.readString(outputDir.resolve(OUTPUT_FILE_NAME), UTF_8))
                .isEqualTo(expectedXmlByKey.get(firstKey));
        assertThat(written.toAbsolutePath().normalize())
                .isEqualTo(outputDir.resolve(OUTPUT_FILE_NAME).toAbsolutePath().normalize());
        assertOutputIsOnlyOrdersXml();
        assertThat(infoMessages()).containsExactly("Received: " + expectedXmlByKey.get(firstKey));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noAttachments")
    void rejectsAMessageWithoutAttachments(Map<String, DataHandler> attachments) throws IOException {
        assertThatThrownBy(() -> service(new FileOutboundWriter()).importAttachments(attachments))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The received message has no attachments");

        assertOutputIsEmpty();
        assertThat(appender.list).isEmpty();
    }

    static Stream<Arguments> noAttachments() {
        return Stream.of(
                Arguments.of(Named.of("empty HashMap", new HashMap<String, DataHandler>())),
                Arguments.of(Named.of("null map", null)));
    }

    @Test
    void propagatesTheWriteFailureUnchangedAfterLogging() throws Exception {
        FileOutboundWriter writer = mock(FileOutboundWriter.class);
        IOException boom = new IOException("disk full");
        when(writer.write(anyString(), anyString(), anyString())).thenThrow(boom);

        assertThatThrownBy(() -> service(writer).importAttachments(originalAttachment())).isSameAs(boom);

        verify(writer).write(outputDir.toString(), OUTPUT_FILE_NAME, EXPECTED_XML);
        verifyNoMoreInteractions(writer);
        assertThat(infoMessages()).containsExactly("Received: " + EXPECTED_XML);
    }

    /** Content that is neither text, a stream nor bytes is refused before anything is logged or written. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedContents")
    void rejectsUnsupportedContent(DataHandler attachment, String expectedMessage) throws IOException {
        assertThatThrownBy(() -> service(new FileOutboundWriter())
                .importAttachments(Map.of(ORIGINAL_PART_NAME, attachment)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(expectedMessage);

        assertOutputIsEmpty();
        assertThat(appender.list).isEmpty();
    }

    static Stream<Arguments> unsupportedContents() throws IOException {
        DataHandler nullContent = mock(DataHandler.class);
        when(nullContent.getContent()).thenReturn(null);
        return Stream.of(
                Arguments.of(Named.of("Integer content", new DataHandler(42, "application/x-java-object")),
                        "Unsupported attachment content: java.lang.Integer"),
                Arguments.of(Named.of("null content", nullContent), "Unsupported attachment content: null"));
    }

    private AttachmentImportService service(FileOutboundWriter writer) {
        return new AttachmentImportService(new DwCsvReader(), new OrdersXmlMapper(new DwXmlWriter()), writer,
                outputDir.toString(), OUTPUT_FILE_NAME);
    }

    /** The part the original test mailed: {@code input.csv} named {@code report}, {@code application/octet-stream}. */
    private static Map<String, DataHandler> originalAttachment() {
        return Map.of(ORIGINAL_PART_NAME,
                new DataHandler(new ByteArrayDataSource(INPUT_CSV, ORIGINAL_PART_CONTENT_TYPE)));
    }

    private static DataHandler csvAttachment(String csv) {
        return new DataHandler(new ByteArrayDataSource(csv.getBytes(UTF_8), ORIGINAL_PART_CONTENT_TYPE));
    }

    /** The orders document of a one-row CSV, in the DataWeave 1.0 XML layout. */
    private static String singleOrderXml(String orderId, String name, String units, String pricePerUnit) {
        return "<?xml version='1.0' encoding='UTF-8'?>\n"
                + "<orders>\n"
                + "\t<order>\n"
                + "\t\t<orderId>" + orderId + "</orderId>\n"
                + "\t\t<name>" + name + "</name>\n"
                + "\t\t<units>" + units + "</units>\n"
                + "\t\t<pricePerUnit>" + pricePerUnit + "</pricePerUnit>\n"
                + "\t</order>\n"
                + "</orders>";
    }

    private List<String> infoMessages() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** The original test's {@code listFiles().length == 1}, naming the file and proving no temporary file is left. */
    private void assertOutputIsOnlyOrdersXml() throws IOException {
        try (Stream<Path> entries = Files.list(outputDir)) {
            assertThat(entries).containsExactly(outputDir.resolve(OUTPUT_FILE_NAME));
        }
    }

    private void assertOutputIsEmpty() throws IOException {
        try (Stream<Path> entries = Files.list(outputDir)) {
            assertThat(entries).isEmpty();
        }
    }

    private static String sha256(byte[] content) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static byte[] readMainResource(String name) {
        try (InputStream in = Objects.requireNonNull(AttachmentImportServiceTest.class.getResourceAsStream(name),
                () -> "classpath resource " + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
