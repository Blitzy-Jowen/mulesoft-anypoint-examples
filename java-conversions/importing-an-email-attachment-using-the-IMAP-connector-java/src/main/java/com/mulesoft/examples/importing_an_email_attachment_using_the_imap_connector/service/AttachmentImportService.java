/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.activation.DataHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.mapper.DwCsvReader;
import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.mapper.OrdersXmlMapper;

/**
 * Runs the original flow {@code imap-to-csvFlow1} of {@code imap-to-xml.xml} for one received mail, after the
 * IMAPS inbound endpoint the poller replaces, in the flow's processor order:
 * <ol>
 *   <li>the attachments become the payload as a list, and the first one's content is taken as
 *       {@code application/csv} ({@code #[payload[0].getContent()]});</li>
 *   <li>the {@code Map CSV to XML} script turns the CSV rows into the {@code orders} document
 *       ({@link OrdersXmlMapper});</li>
 *   <li>the document is logged at INFO as {@code Received: <document>};</li>
 *   <li>the document is written to {@code orders.output-dir}/{@code orders.output-file-name}, replacing an earlier
 *       file of that name, as the file outbound endpoint wrote it ({@link FileOutboundWriter}).</li>
 * </ol>
 *
 * <p>Nothing is caught: every failure reaches the poller unchanged, which logs it at ERROR as Mule's default
 * exception strategy did. A failure before the log step therefore leaves no INFO line and no file.
 *
 * <p>Stateless apart from its configuration, so one instance serves every poll.
 */
@Service
public class AttachmentImportService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentImportService.class);

    private final DwCsvReader csvReader;
    private final OrdersXmlMapper ordersXmlMapper;
    private final FileOutboundWriter fileOutboundWriter;
    private final String outputDir;
    private final String outputFileName;

    /**
     * @param csvReader          the DataWeave 1.0 CSV reader the attachment content is parsed with
     * @param ordersXmlMapper    the owner of the flow's {@code Map CSV to XML} script
     * @param fileOutboundWriter the writer replacing the flow's file outbound endpoint
     * @param outputDir          the original endpoint's {@code path}, from {@code orders.output-dir}
     * @param outputFileName     the original endpoint's {@code outputPattern}, from {@code orders.output-file-name}
     */
    public AttachmentImportService(DwCsvReader csvReader, OrdersXmlMapper ordersXmlMapper,
            FileOutboundWriter fileOutboundWriter, @Value("${orders.output-dir}") String outputDir,
            @Value("${orders.output-file-name}") String outputFileName) {
        this.csvReader = csvReader;
        this.ordersXmlMapper = ordersXmlMapper;
        this.fileOutboundWriter = fileOutboundWriter;
        this.outputDir = outputDir;
        this.outputFileName = outputFileName;
    }

    /**
     * Imports the first attachment of one received mail as the {@code orders} XML file.
     *
     * @param attachments the mail's attachments in Mule's attachment-list order, which must be the map's iteration
     *                    order (an insertion-ordered map); only the first one is read
     * @return the written file
     * @throws IllegalStateException when there is no attachment, or the first one's content is neither text, a
     *                               stream nor bytes; nothing is logged or written then
     * @throws Exception             any failure of reading the content, mapping it or writing the file, unchanged
     */
    public Path importAttachments(Map<String, DataHandler> attachments) throws Exception {
        if (attachments == null || attachments.isEmpty()) {
            throw new IllegalStateException("The received message has no attachments");
        }
        DataHandler first = new ArrayList<>(attachments.values()).get(0);

        List<Map<String, String>> rows = readCsv(first.getContent());
        String xml = ordersXmlMapper.toXml(rows);
        log.info("Received: {}", xml);
        return fileOutboundWriter.write(outputDir, outputFileName, xml);
    }

    /**
     * Reads the attachment content in the forms {@link DataHandler#getContent()} returns it: text where a content
     * handler decodes the part (such as {@code text/plain}), a stream for a content type without one (such as the
     * original test's {@code application/octet-stream}), or bytes. Streams and bytes are read as UTF-8.
     */
    private List<Map<String, String>> readCsv(Object content) throws IOException {
        if (content instanceof String text) {
            return csvReader.read(text);
        }
        if (content instanceof InputStream stream) {
            byte[] bytes;
            try (stream) {
                bytes = stream.readAllBytes();
            }
            return csvReader.read(bytes);
        }
        if (content instanceof byte[] bytes) {
            return csvReader.read(bytes);
        }
        throw new IllegalStateException("Unsupported attachment content: "
                + (content == null ? "null" : content.getClass().getName()));
    }
}
