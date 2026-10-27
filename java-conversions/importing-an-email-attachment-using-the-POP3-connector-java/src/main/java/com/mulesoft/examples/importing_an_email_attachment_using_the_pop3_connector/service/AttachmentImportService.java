/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import jakarta.activation.DataHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.mapper.DwCsvReader;
import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.mapper.OrdersXmlMapper;

/**
 * The processing of flow {@code pop-to-xmlFlow1}
 * ({@code importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml}) after its POP3S
 * inbound endpoint, in the flow's processor order:
 * <ol>
 *   <li>line 10, {@code set-payload #[payload[0].getContent()]} as {@code application/csv}: the content of the first
 *       attachment, and of no other, is read through {@link DataHandler#getContent()} and parsed as CSV;</li>
 *   <li>line 13, the {@code %dw 1.0} "Map CSV to XML" script, owned by {@link OrdersXmlMapper};</li>
 *   <li>line 27, the INFO logger {@code Received: <payload as string>};</li>
 *   <li>line 28, {@code file:outbound-endpoint} writing the document to {@code orders.output-dir} as
 *       {@code orders.output-file-name}.</li>
 * </ol>
 * The trigger binds the attachment list of lines 7-9 in Mule's attachment-list order and calls {@link #process(List)};
 * this class knows nothing about mail. The flow declares no exception strategy, so every failure reaches the caller
 * unchanged, which applies Mule's default one. Stateless and thread-safe.
 */
@Service
public class AttachmentImportService {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentImportService.class);

    private final DwCsvReader dwCsvReader;
    private final OrdersXmlMapper ordersXmlMapper;
    private final FileOutboundWriter fileOutboundWriter;
    private final String outputDir;
    private final String fileName;

    /**
     * Creates the service.
     *
     * @param dwCsvReader        the DataWeave CSV reader for the attachment content
     * @param ordersXmlMapper    the owner of the "Map CSV to XML" script
     * @param fileOutboundWriter the writer behind the flow's file outbound endpoint
     * @param outputDir          the outbound endpoint's {@code path}
     * @param fileName           the outbound endpoint's {@code outputPattern}
     */
    public AttachmentImportService(DwCsvReader dwCsvReader, OrdersXmlMapper ordersXmlMapper,
            FileOutboundWriter fileOutboundWriter, @Value("${orders.output-dir}") String outputDir,
            @Value("${orders.output-file-name}") String fileName) {
        this.dwCsvReader = dwCsvReader;
        this.ordersXmlMapper = ordersXmlMapper;
        this.fileOutboundWriter = fileOutboundWriter;
        this.outputDir = outputDir;
        this.fileName = fileName;
    }

    /**
     * Converts the first attachment of one polled email to the {@code orders} XML document, logs it and writes it
     * to the output file, replacing any earlier one. Only the first attachment's content is read; the others are
     * ignored and never decoded, as {@code payload[0]} ignored them.
     *
     * @param attachments the message's attachments in Mule's attachment-list order
     * @return the written file
     * @throws IllegalArgumentException when the message has no attachment; nothing is read, logged or written
     * @throws IllegalStateException    when the first attachment's content is neither text, a stream nor bytes;
     *                                  nothing is logged or written
     * @throws IOException              when the first attachment's content cannot be read or decoded, or the output
     *                                  file cannot be written
     */
    public Path process(List<DataHandler> attachments) throws IOException {
        if (attachments == null || attachments.isEmpty()) {
            throw new IllegalArgumentException("Message has no attachment");
        }
        List<Map<String, String>> rows = readCsv(attachments.get(0).getContent());
        String xml = ordersXmlMapper.toXml(rows);
        LOG.info("Received: {}", xml);
        return fileOutboundWriter.write(outputDir, fileName, xml);
    }

    /**
     * Reads the attachment content in the forms {@link DataHandler#getContent()} returns it: text where a content
     * handler decodes the part with its declared charset (such as {@code text/plain}), a stream for a content type
     * without one (such as the original test's {@code application/octet-stream}), or bytes. Streams and bytes are
     * read as UTF-8.
     */
    private List<Map<String, String>> readCsv(Object content) throws IOException {
        if (content instanceof String text) {
            return dwCsvReader.read(text);
        }
        if (content instanceof InputStream stream) {
            byte[] bytes;
            try (stream) {
                bytes = stream.readAllBytes();
            }
            return dwCsvReader.read(bytes);
        }
        if (content instanceof byte[] bytes) {
            return dwCsvReader.read(bytes);
        }
        throw new IllegalStateException("Unsupported attachment content: "
                + (content == null ? "null" : content.getClass().getName()));
    }
}
