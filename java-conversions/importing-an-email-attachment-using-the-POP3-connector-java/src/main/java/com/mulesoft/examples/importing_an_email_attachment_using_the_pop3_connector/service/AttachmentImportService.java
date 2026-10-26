/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
 *   <li>line 10, {@code set-payload #[payload[0].getContent()]} as {@code application/csv}: the first attachment's
 *       content is read as CSV;</li>
 *   <li>line 13, the {@code %dw 1.0} "Map CSV to XML" script, owned by {@link OrdersXmlMapper};</li>
 *   <li>line 27, the INFO logger {@code Received: <payload as string>};</li>
 *   <li>line 28, {@code file:outbound-endpoint} writing the document to {@code orders.output-dir} as
 *       {@code orders.output-file-name}.</li>
 * </ol>
 * The trigger binds the attachment bytes and calls {@link #process(List)}; this class knows nothing about mail. The
 * flow declares no exception strategy, so every failure reaches the caller unchanged, which applies Mule's default
 * one. Stateless and thread-safe.
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
     * to the output file, replacing any earlier one. Attachments after the first are ignored, as
     * {@code payload[0]} ignored them.
     *
     * @param attachments the contents of the message's attachments, in message order
     * @return the written file
     * @throws IllegalArgumentException when the message has no attachment; nothing is read, logged or written
     * @throws IOException              when the output file cannot be written
     */
    public Path process(List<byte[]> attachments) throws IOException {
        if (attachments == null || attachments.isEmpty()) {
            throw new IllegalArgumentException("Message has no attachment");
        }
        byte[] content = attachments.get(0);
        List<Map<String, String>> rows = dwCsvReader.read(content);
        String xml = ordersXmlMapper.toXml(rows);
        LOG.info("Received: {}", xml);
        return fileOutboundWriter.write(outputDir, fileName, xml);
    }
}
