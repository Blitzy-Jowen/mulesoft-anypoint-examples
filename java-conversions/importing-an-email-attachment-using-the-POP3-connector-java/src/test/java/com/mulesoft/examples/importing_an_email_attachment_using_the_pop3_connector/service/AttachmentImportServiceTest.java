/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.service;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.mapper.DwCsvReader;
import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.mapper.DwXmlWriter;
import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.mapper.OrdersXmlMapper;

import jakarta.activation.DataHandler;
import jakarta.mail.util.ByteArrayDataSource;

/**
 * Unit test of {@link AttachmentImportService}, the processing of flow {@code pop-to-xmlFlow1}
 * ({@code importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml}) after its POP3S
 * inbound endpoint: the first attachment read as CSV (line 10), the "Map CSV to XML" script (line 13), the INFO
 * logger {@code Received: <payload>} (line 27) and the file outbound endpoint writing {@code orders.xml} (line 28).
 *
 * <p>The service runs over the real SC-4 {@link DwCsvReader} and {@link DwXmlWriter}, the real
 * {@link OrdersXmlMapper} and a Mockito spy of the real SC-6 {@link FileOutboundWriter}, so every file it writes holds
 * the bytes the converted flow writes. Attachments are real {@link DataHandler}s over byte data sources, so
 * {@link DataHandler#getContent()} answers as for a received mail part: a stream for
 * {@code application/octet-stream}, text decoded with its declared charset for {@code text/plain}. The only doubles
 * are the spy's stubbed write failure, a mocked reader for the failure the real, lenient reader never raises, and
 * mocked data handlers for the content forms no mail part type yields here. The original test only counted the files
 * in its output directory; these tests compare the written bytes with fixed literals instead, and when the service
 * output differs from a literal the service is wrong, not the literal.
 *
 * <p>Plain JUnit 5 without a Spring context: {@link OutputCaptureExtension} only captures the console, where the
 * logging system's default console appender writes the INFO line. Every file lands under the {@link TempDir}.
 */
@ExtendWith(OutputCaptureExtension.class)
class AttachmentImportServiceTest {

    /**
     * The document the script produced for the original {@code input.csv}: 289 bytes in UTF-8, spelled out with
     * explicit {@code \n} and {@code \t} because the DataWeave 1.0 writer indents with tabs and writes no line feed
     * after the root end tag. The same literal governs {@code OrdersXmlMapperTest}.
     */
    private static final String EXPECTED = "<?xml version='1.0' encoding='UTF-8'?>\n"
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

    /** SHA-256 of the UTF-8 bytes of {@link #EXPECTED}, the digest the written file must match. */
    private static final String EXPECTED_SHA256 = "c5d9c000e8caa4640d880c08c0f9b75ab2010991b8cf3a93840760af55a906ee";

    /** A one-record attachment that replaces the first document in the overwrite case. */
    private static final String ONE_ORDER_CSV = "orderId,name,units,pricePerUnit\n3,ccc,1,7";

    /** The document the script produced for {@link #ONE_ORDER_CSV}: 170 bytes in UTF-8. */
    private static final String EXPECTED_ONE_ORDER = "<?xml version='1.0' encoding='UTF-8'?>\n"
            + "<orders>\n"
            + "\t<order>\n"
            + "\t\t<orderId>3</orderId>\n"
            + "\t\t<name>ccc</name>\n"
            + "\t\t<units>1</units>\n"
            + "\t\t<pricePerUnit>7</pricePerUnit>\n"
            + "\t</order>\n"
            + "</orders>";

    /** SHA-256 of the UTF-8 bytes of {@link #EXPECTED_ONE_ORDER}, the digest the written file must match. */
    private static final String EXPECTED_ONE_ORDER_SHA256 =
            "10d31ef6be36de02b482fe984307df98710c22d4b92d58fcfd92385818ac86f8";

    /** A one-record attachment with non-ASCII letters, sent as ISO-8859-1 text in the charset case. */
    private static final String LATIN_1_CSV = "orderId,name,units,pricePerUnit\n1,Zo\u00eb M\u00fcller,2,10\n";

    /** The document the script produced for {@link #LATIN_1_CSV}, the name decoded and written as UTF-8. */
    private static final String EXPECTED_LATIN_1 = "<?xml version='1.0' encoding='UTF-8'?>\n"
            + "<orders>\n"
            + "\t<order>\n"
            + "\t\t<orderId>1</orderId>\n"
            + "\t\t<name>Zo\u00eb M\u00fcller</name>\n"
            + "\t\t<units>2</units>\n"
            + "\t\t<pricePerUnit>10</pricePerUnit>\n"
            + "\t</order>\n"
            + "</orders>";

    /** The outbound endpoint's {@code outputPattern}. */
    private static final String FILE_NAME = "orders.xml";

    /** The source file of the service, which is where its own calls on the writer are recorded as made. */
    private static final String SERVICE_SOURCE_FILE = AttachmentImportService.class.getSimpleName() + ".java";

    @TempDir
    Path tempDir;

    private final DwCsvReader reader = new DwCsvReader();

    private final OrdersXmlMapper mapper = new OrdersXmlMapper(new DwXmlWriter());

    private final FileOutboundWriter writer = Mockito.spy(new FileOutboundWriter());

    private AttachmentImportService service;

    private byte[] inputCsv;

    @BeforeEach
    void setUp() throws IOException {
        service = new AttachmentImportService(reader, mapper, writer, tempDir.toString(), FILE_NAME);
        try (InputStream in = getClass().getResourceAsStream("/input.csv")) {
            assertThat(in).as("main classpath resource /input.csv").isNotNull();
            inputCsv = in.readAllBytes();
        }
    }

    @Test
    void writesOrdersXmlForFirstAttachment(CapturedOutput output) throws Exception {
        assertThat(inputCsv).as("carried input.csv drifted from the original's 57 bytes").hasSize(57);
        assertThat(new String(inputCsv, UTF_8))
                .isEqualTo("orderId,name,units,pricePerUnit\n1,aaa,2.0,10\n2,bbb,4.15,5")
                .doesNotEndWith("\n");

        Path result = service.process(List.of(octetStream(inputCsv)));

        assertThat(result).isEqualTo(tempDir.resolve(FILE_NAME));
        byte[] written = Files.readAllBytes(result);
        assertThat(written).isEqualTo(EXPECTED.getBytes(UTF_8)).hasSize(289);
        assertThat(sha256(written)).isEqualTo(EXPECTED_SHA256);

        // The original logger printed the whole payload, so the whole document follows the prefix, at INFO.
        String out = output.getOut();
        assertThat(out).contains("Received: <?xml version='1.0' encoding='UTF-8'?>").contains("Received: " + EXPECTED);
        assertThat(out.lines().filter(line -> line.contains("Received: "))).singleElement().asString().contains("INFO");

        verify(writer, times(1)).write(eq(tempDir.toString()), eq(FILE_NAME), eq(EXPECTED));
        assertWroteOnceFromService(tempDir.toString(), EXPECTED);
    }

    @Test
    void ignoresAttachmentsAfterTheFirst() throws Exception {
        // Only payload[0].getContent() was evaluated: a later attachment is never read, so it cannot fail the flow.
        DataHandler later = mock(DataHandler.class);

        Path result = service.process(List.of(octetStream(inputCsv), later));

        assertThat(Files.readAllBytes(result)).isEqualTo(EXPECTED.getBytes(UTF_8));
        verify(writer, times(1)).write(eq(tempDir.toString()), eq(FILE_NAME), eq(EXPECTED));
        assertWroteOnceFromService(tempDir.toString(), EXPECTED);
        verifyNoInteractions(later);
    }

    @Test
    void decodesTextPlainWithItsDeclaredCharset() throws Exception {
        byte[] latin1 = LATIN_1_CSV.getBytes(ISO_8859_1);
        assertThat(new String(latin1, UTF_8)).as("the bytes are not valid UTF-8").contains("\ufffd");
        DataHandler attachment = new DataHandler(new ByteArrayDataSource(latin1, "text/plain; charset=ISO-8859-1"));

        Path result = service.process(List.of(attachment));

        byte[] written = Files.readAllBytes(result);
        assertThat(written).isEqualTo(EXPECTED_LATIN_1.getBytes(UTF_8));
        assertThat(new String(written, UTF_8)).contains("<name>Zo\u00eb M\u00fcller</name>");
        assertWroteOnceFromService(tempDir.toString(), EXPECTED_LATIN_1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contentForms")
    void readsEveryContentFormTheDataHandlerReturns(String form, Object content) throws Exception {
        DataHandler attachment = mock(DataHandler.class);
        when(attachment.getContent()).thenReturn(content);

        Path result = service.process(List.of(attachment));

        assertThat(Files.readAllBytes(result)).isEqualTo(EXPECTED_ONE_ORDER.getBytes(UTF_8));
        assertWroteOnceFromService(tempDir.toString(), EXPECTED_ONE_ORDER);
    }

    static Stream<Arguments> contentForms() {
        return Stream.of(
                Arguments.of("text", ONE_ORDER_CSV),
                Arguments.of("bytes", ONE_ORDER_CSV.getBytes(UTF_8)));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("unsupportedContents")
    void rejectsUnsupportedContent(Object content, String message, CapturedOutput output) throws Exception {
        DataHandler attachment = mock(DataHandler.class);
        when(attachment.getContent()).thenReturn(content);

        assertThatThrownBy(() -> service.process(List.of(attachment)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(message);

        assertDirectoryEmpty();
        assertThat(output.getOut()).doesNotContain("Received:");
        verifyNoInteractions(writer);
    }

    static Stream<Arguments> unsupportedContents() {
        return Stream.of(
                Arguments.of(null, "Unsupported attachment content: null"),
                Arguments.of(42, "Unsupported attachment content: java.lang.Integer"));
    }

    @Test
    void undecodableContentPropagates(CapturedOutput output) throws Exception {
        DataHandler attachment = new DataHandler(
                new ByteArrayDataSource("orderId\n1".getBytes(UTF_8), "text/plain; charset=x-unknown"));

        assertThatThrownBy(() -> service.process(List.of(attachment)))
                .isInstanceOf(UnsupportedEncodingException.class);

        assertDirectoryEmpty();
        assertThat(output.getOut()).doesNotContain("Received:");
        verifyNoInteractions(writer);
    }

    @Test
    void overwritesExistingOrdersXml() throws Exception {
        service.process(List.of(octetStream(inputCsv)));
        Path result = service.process(List.of(octetStream(ONE_ORDER_CSV.getBytes(UTF_8))));

        byte[] written = Files.readAllBytes(result);
        assertThat(written).isEqualTo(EXPECTED_ONE_ORDER.getBytes(UTF_8)).hasSize(170);
        assertThat(sha256(written)).isEqualTo(EXPECTED_ONE_ORDER_SHA256);
        assertThat(new String(written, UTF_8)).doesNotContain("aaa");
        try (Stream<Path> entries = Files.list(tempDir)) {
            assertThat(entries.map(entry -> entry.getFileName().toString())).containsExactly(FILE_NAME);
        }
        verify(writer).write(eq(tempDir.toString()), eq(FILE_NAME), eq(EXPECTED));
        verify(writer).write(eq(tempDir.toString()), eq(FILE_NAME), eq(EXPECTED_ONE_ORDER));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsMessageWithoutAttachment(List<DataHandler> attachments, CapturedOutput output) throws IOException {
        assertThatThrownBy(() -> service.process(attachments))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("Message has no attachment");

        assertDirectoryEmpty();
        assertThat(output.getOut()).doesNotContain("Received:");
        verifyNoInteractions(writer);
    }

    @Test
    void malformedCsvPropagates(CapturedOutput output) {
        // The real reader is lenient by design and rejects no input (an unterminated quote runs to the end of the
        // input), so the failure a read could raise is stubbed on a mocked reader and must surface unwrapped.
        DwCsvReader mockReader = mock(DwCsvReader.class);
        IllegalArgumentException boom = new IllegalArgumentException("malformed CSV");
        when(mockReader.read(any(byte[].class))).thenThrow(boom);
        AttachmentImportService failing =
                new AttachmentImportService(mockReader, mapper, writer, tempDir.toString(), FILE_NAME);

        assertThatThrownBy(() -> failing.process(List.of(octetStream(inputCsv)))).isSameAs(boom);

        assertThat(tempDir.resolve(FILE_NAME)).doesNotExist();
        assertThat(output.getOut()).doesNotContain("Received:");
        verify(mockReader).read(inputCsv);
        verifyNoInteractions(writer);
    }

    @Test
    void characterXmlForbidsFailsTheImportBeforeAnythingIsLoggedOrWritten(CapturedOutput output) throws IOException {
        DataHandler attachment =
                octetStream("orderId,name,units,pricePerUnit\n1,bad\u0001char\u000b,1,1\n".getBytes(UTF_8));

        assertThatThrownBy(() -> service.process(List.of(attachment)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid XML 1.0 character U+0001 in the text of element <name>")
                .hasNoCause();

        // The XML writer refuses the document, as DataWeave's StAX writer did, so no ill-formed orders.xml exists.
        assertDirectoryEmpty();
        assertThat(output.getOut()).doesNotContain("Received:");
        verifyNoInteractions(writer);
    }

    @Test
    void writeFailureFromUnwritableDirectoryPropagates() throws Exception {
        Path blocker = Files.createFile(tempDir.resolve("blocker"));
        AttachmentImportService blocked =
                new AttachmentImportService(reader, mapper, writer, blocker.toString(), FILE_NAME);

        assertThatThrownBy(() -> blocked.process(List.of(octetStream(inputCsv))))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage("Streaming failed. Could not get output stream")
                .hasCauseExactlyInstanceOf(FileAlreadyExistsException.class);

        assertThat(blocker).isRegularFile().isEmptyFile();
        try (Stream<Path> entries = Files.list(tempDir)) {
            assertThat(entries).containsExactly(blocker);
        }
        verify(writer).write(eq(blocker.toString()), eq(FILE_NAME), eq(EXPECTED));
    }

    @Test
    void writeFailurePropagatesIOExceptionAfterLogging(CapturedOutput output) throws Exception {
        IOException ioe = new IOException("disk full");
        doThrow(ioe).when(writer).write(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.process(List.of(octetStream(inputCsv)))).isSameAs(ioe);

        // The flow logs (line 27) before it writes (line 28), so a failed write still leaves the log line.
        assertThat(output.getOut()).contains("Received: <?xml");
        assertThat(tempDir.resolve(FILE_NAME)).doesNotExist();
        verify(writer).write(eq(tempDir.toString()), eq(FILE_NAME), eq(EXPECTED));
    }

    /**
     * Asserts that the service made exactly one call on the writer, {@code write(String, String, String)} with the
     * given directory, {@link #FILE_NAME} and content. A spy also records the calls {@link FileOutboundWriter} makes
     * on itself while one public overload delegates to the next, so {@code verifyNoMoreInteractions} would count the
     * writer's own delegation as service calls; the calls are counted here by the caller the spy recorded instead.
     */
    private void assertWroteOnceFromService(String dir, String content) throws IOException {
        List<Invocation> fromService = mockingDetails(writer).getInvocations().stream()
                .filter(invocation -> SERVICE_SOURCE_FILE.equals(invocation.getLocation().getSourceFile()))
                .toList();
        assertThat(fromService).as("writer calls made by the service").hasSize(1);
        Invocation call = fromService.get(0);
        assertThat(call.getMethod().getName()).isEqualTo("write");
        assertThat(call.getMethod().getParameterTypes()).containsExactly(String.class, String.class, String.class);
        assertThat(call.getArguments()).containsExactly(dir, FILE_NAME, content);
        verify(writer, never()).write(anyString(), anyString(), any(byte[].class));
    }

    /**
     * An attachment as the original test sent it, {@code application/octet-stream}, for which no content handler
     * exists, so {@link DataHandler#getContent()} returns a stream of the bytes.
     */
    private static DataHandler octetStream(byte[] content) {
        return new DataHandler(new ByteArrayDataSource(content, "application/octet-stream"));
    }

    private void assertDirectoryEmpty() throws IOException {
        try (Stream<Path> entries = Files.list(tempDir)) {
            assertThat(entries).isEmpty();
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
