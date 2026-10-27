/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.client.FtpUploadClient;
import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.mapper.DwXmlWriter;
import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.mapper.EmployeesXmlMapper;

/**
 * Unit test of {@link EmployeeUploadService}, the orchestration of flow {@code main} in the original
 * {@code upload-to-ftp.xml}: the "JSON to XML" transform (line 9), then the "Upload to FTP" outbound endpoint with
 * {@code outputPattern="muleExample.xml"} (line 28), then the XML as the response payload.
 *
 * <p>The real {@link EmployeesXmlMapper} over a real {@link DwXmlWriter} runs, wrapped in a Mockito spy so the call
 * order can be verified; {@link FtpUploadClient} is a mock, so no FTP server, network or file is touched. The flow
 * declares no exception strategy, so every failure must reach the caller unchanged, after at most one upload
 * attempt, and a mapping failure must stop the flow before the upload. No Spring context is started.
 */
class EmployeeUploadServiceTest {

    /** Size of {@code src/test/resources/message.json}, a byte copy of the original example's input. */
    private static final int MESSAGE_JSON_LENGTH = 352;

    /**
     * The script's output for {@code message.json}, as {@link DwXmlWriter} writes it: tab indentation, LF line ends,
     * nothing after {@code </employees>}. It is the original {@code reply.xml} with the street values' inner spaces
     * restored from the input and without that hand-edited file's final line feed; the original test compared it
     * with all whitespace removed, so {@code reply.xml} is not read for this exact comparison.
     */
    private static final String EXPECTED_XML = String.join("\n",
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<employees>",
            "\t<employee>",
            "\t\t<name>John</name>",
            "\t\t<lastName>Doe</lastName>",
            "\t\t<addresses>",
            "\t\t\t<address>",
            "\t\t\t\t<street>123 Main Street</street>",
            "\t\t\t\t<zipCode>111</zipCode>",
            "\t\t\t</address>",
            "\t\t\t<address>",
            "\t\t\t\t<street>987 Cypress Avenue</street>",
            "\t\t\t\t<zipCode>222</zipCode>",
            "\t\t\t</address>",
            "\t\t</addresses>",
            "\t</employee>",
            "\t<employee>",
            "\t\t<name>Jane</name>",
            "\t\t<lastName>Doe</lastName>",
            "\t\t<addresses>",
            "\t\t\t<address>",
            "\t\t\t\t<street>345 Main Street</street>",
            "\t\t\t\t<zipCode>111</zipCode>",
            "\t\t\t</address>",
            "\t\t\t<address>",
            "\t\t\t\t<street>654 Sunset Boulevard</street>",
            "\t\t\t\t<zipCode>333</zipCode>",
            "\t\t\t</address>",
            "\t\t</addresses>",
            "\t</employee>",
            "</employees>");

    private static final int EXPECTED_XML_LENGTH = 637;

    private static final String EXPECTED_XML_MD5 = "86159f75689d7a5b40bb14284901d8d6";

    /** An employee whose names are not ASCII; sent as ISO-8859-1, each of {@code é} and {@code ü} is one byte. */
    private static final String LATIN1_JSON =
            "{\"employees\":{\"employee\":[{\"name\":\"Jos\u00e9\",\"lastName\":\"M\u00fcller\","
            + "\"addresses\":{\"address\":[]}}]}}";

    /** The script's output for {@link #LATIN1_JSON}, whatever the charset the JSON arrived in. */
    private static final String LATIN1_XML = String.join("\n",
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<employees>",
            "\t<employee>",
            "\t\t<name>Jos\u00e9</name>",
            "\t\t<lastName>M\u00fcller</lastName>",
            "\t\t<addresses/>",
            "\t</employee>",
            "</employees>");

    private FtpUploadClient client;

    private EmployeesXmlMapper mapper;

    private EmployeeUploadService service;

    @BeforeEach
    void setUp() {
        // The inline mock maker creates the concrete client without running its FtpProperties constructor.
        client = mock(FtpUploadClient.class);
        // A spy, never stubbed: the real transformation runs, and only its invocation is recorded.
        mapper = spy(new EmployeesXmlMapper(new DwXmlWriter(), new ObjectMapper()));
        service = new EmployeeUploadService(mapper, client);
    }

    @Test
    void transformRunsBeforeUpload() throws IOException {
        byte[] json = messageJson();

        service.process(json, null);

        InOrder inOrder = inOrder(mapper, client);
        inOrder.verify(mapper).toXmlBytes(aryEq(json), isNull());
        inOrder.verify(client).upload(anyString(), any(byte[].class));
        verifyNoMoreInteractions(client);
    }

    @Test
    void uploadsUnderOriginalOutputPattern() throws IOException {
        ArgumentCaptor<String> fileName = ArgumentCaptor.forClass(String.class);

        service.process(messageJson(), null);

        verify(client).upload(fileName.capture(), any(byte[].class));
        assertEquals("muleExample.xml", FtpUploadClient.OUTPUT_PATTERN);
        assertEquals("muleExample.xml", fileName.getValue());
        assertEquals(FtpUploadClient.OUTPUT_PATTERN, fileName.getValue());
    }

    @Test
    void uploadedBytesEqualReturnedBytesAndExactDwDocument() throws IOException, NoSuchAlgorithmException {
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);

        byte[] result = service.process(messageJson(), null);

        verify(client).upload(eq(FtpUploadClient.OUTPUT_PATTERN), bytes.capture());
        assertArrayEquals(result, bytes.getValue());
        assertEquals(EXPECTED_XML_LENGTH, result.length);
        assertEquals(EXPECTED_XML_MD5, md5Hex(result));
        assertEquals(EXPECTED_XML, new String(result, StandardCharsets.UTF_8));
        assertArrayEquals(EXPECTED_XML.getBytes(StandardCharsets.UTF_8), result);
    }

    /** The two failure types {@link FtpUploadClient#upload(String, byte[])} raises. */
    static Stream<RuntimeException> uploadFailures() {
        return Stream.of(
                new IllegalStateException("FTP upload failed"),
                new UncheckedIOException(new IOException("boom")));
    }

    @ParameterizedTest
    @MethodSource("uploadFailures")
    void uploadFailurePropagatesUnchangedWithoutRetry(RuntimeException failure) throws IOException {
        doThrow(failure).when(client).upload(anyString(), any(byte[].class));
        byte[] json = messageJson();

        RuntimeException thrown = assertThrows(failure.getClass(), () -> service.process(json, null));

        assertSame(failure, thrown);
        verify(client, times(1)).upload(anyString(), any(byte[].class));
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"employees\": ", "not json", "", "[]"})
    void malformedJsonFailsBeforeAnyUpload(String payload) {
        byte[] json = payload.getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> service.process(json, null));

        verify(mapper).toXmlBytes(aryEq(json), isNull());
        verifyNoInteractions(client);
    }

    @Test
    void declaredCharsetReachesTheTransformAndTheUploadIsUtf8() {
        byte[] json = LATIN1_JSON.getBytes(StandardCharsets.ISO_8859_1);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);

        byte[] result = service.process(json, StandardCharsets.ISO_8859_1);

        InOrder inOrder = inOrder(mapper, client);
        inOrder.verify(mapper).toXmlBytes(aryEq(json), eq(StandardCharsets.ISO_8859_1));
        inOrder.verify(client).upload(eq(FtpUploadClient.OUTPUT_PATTERN), bytes.capture());
        verifyNoMoreInteractions(client);
        assertArrayEquals(result, bytes.getValue());
        assertArrayEquals(LATIN1_XML.getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    void payloadUndecodableInItsDeclaredCharsetFailsBeforeAnyUpload() {
        byte[] json = LATIN1_JSON.getBytes(StandardCharsets.ISO_8859_1);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.process(json, StandardCharsets.UTF_8));

        assertEquals("Malformed UTF-8 input at byte offset 38 of the JSON payload", thrown.getMessage());
        verify(mapper).toXmlBytes(aryEq(json), eq(StandardCharsets.UTF_8));
        verifyNoInteractions(client);
    }

    private static byte[] messageJson() throws IOException {
        try (InputStream in = EmployeeUploadServiceTest.class.getResourceAsStream("/message.json")) {
            assertNotNull(in, "message.json is missing from the test classpath");
            byte[] json = in.readAllBytes();
            assertEquals(MESSAGE_JSON_LENGTH, json.length);
            return json;
        }
    }

    private static String md5Hex(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }
}
