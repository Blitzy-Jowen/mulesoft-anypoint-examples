/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.client.CurrencyClient;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.OrderMapper;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.OrderReportMapper;

/**
 * Runs the processors of flow {@code OrderFlow} of
 * {@code processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml} (l.7-43) in their original order, for
 * one orders file handed over by {@code trigger/OrderFileTrigger}, which replaces the flow's
 * {@code file:inbound-endpoint} (l.8):
 * <ol>
 *   <li>l.9-11, {@code <enricher target="flowVars.currencies">} around {@code http:request GET /currencies}:
 *       {@link CurrencyClient#getCurrencies()}, called before the payload is read, as the enricher ran before the
 *       transform.</li>
 *   <li>l.12-38, the single {@code dw:transform-message "Process Orders and Create Report"}: the payload is parsed
 *       once and both of its scripts read that same document, {@code set-payload} (l.14-29) through
 *       {@link OrderMapper#toJson(Document, JsonNode)} and {@code set-variable report} (l.30-37) through
 *       {@link OrderReportMapper#toCsv(Document)}. Both results are computed before anything is written, so a
 *       failure of either script writes no file, as a failed transform ended the flow before its outbound
 *       endpoints.</li>
 *   <li>l.39, {@code file:outbound-endpoint outputPattern="orders.json" path="src/main/resources/output"}:
 *       the JSON is written to {@code orders.output-dir}.</li>
 *   <li>l.41-42, {@code set-payload #[report]} and {@code file:outbound-endpoint outputPattern="report.csv"} on
 *       the same path: the CSV is written next, and only once the JSON write has succeeded.</li>
 * </ol>
 *
 * <p>The flow declares no {@code logger} and no exception strategy, so nothing is logged or caught here: every
 * failure (the currency call's {@code RestClientException} or {@code UncheckedIOException}, the parser's
 * {@link SAXException}, a mapper's {@code NumberFormatException}, {@code IllegalArgumentException} or
 * {@code ArithmeticException}, a write's {@link IOException}) reaches the caller unchanged. SC-5
 * {@code FileInboundPoller} then logs it at ERROR and still deletes the input file, as Mule's default exception
 * strategy did.
 *
 * <p>The class is stateless and thread-safe; each parse uses its own parser.
 */
@Service
public class OrderFlowService {

    /** The {@code outputPattern} of the first {@code file:outbound-endpoint} (books.xml l.39). */
    static final String ORDERS_FILE = "orders.json";

    /** The {@code outputPattern} of the second {@code file:outbound-endpoint} (books.xml l.42). */
    static final String REPORT_FILE = "report.csv";

    /**
     * Fails the parse on every recoverable and fatal error and ignores warnings. It replaces the JDK default
     * handler, which prints {@code [Fatal Error]} lines to standard error before the parse fails.
     */
    private static final ErrorHandler FAIL_ON_ERROR = new ErrorHandler() {

        @Override
        public void warning(SAXParseException exception) {
            // A warning never failed the DataWeave XML reader either, and the caller sees no stderr output.
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };

    private final CurrencyClient currencyClient;

    private final OrderMapper orderMapper;

    private final OrderReportMapper orderReportMapper;

    private final FileOutboundWriter fileOutboundWriter;

    private final String outputDir;

    /**
     * Creates the service.
     *
     * @param currencyClient     the client of the application's own Currency API, the enricher's {@code http:request}
     * @param orderMapper        the owner of the transform's {@code set-payload} script
     * @param orderReportMapper  the owner of the transform's {@code set-variable report} script
     * @param fileOutboundWriter the SC-6 writer of both {@code file:outbound-endpoint}s
     * @param outputDir          the endpoints' {@code path}, {@code orders.output-dir}; a relative path resolves
     *                           against the working directory, as Mule's did
     */
    public OrderFlowService(CurrencyClient currencyClient, OrderMapper orderMapper,
            OrderReportMapper orderReportMapper, FileOutboundWriter fileOutboundWriter,
            @Value("${orders.output-dir}") String outputDir) {
        this.currencyClient = Objects.requireNonNull(currencyClient, "currencyClient");
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper");
        this.orderReportMapper = Objects.requireNonNull(orderReportMapper, "orderReportMapper");
        this.fileOutboundWriter = Objects.requireNonNull(fileOutboundWriter, "fileOutboundWriter");
        this.outputDir = Objects.requireNonNull(outputDir, "outputDir");
    }

    /**
     * Processes one orders file: fetches the currencies, applies both scripts of the transform to the parsed
     * payload, then writes {@value #ORDERS_FILE} and {@value #REPORT_FILE} to the output directory, each replacing
     * a file of the same name.
     *
     * @param orderXml the bytes of the inbound file, the flow's payload
     * @throws org.springframework.web.client.RestClientException when the Currency API call fails; nothing is
     *         written
     * @throws java.io.UncheckedIOException when the Currency API response is not JSON; nothing is written
     * @throws SAXException when the payload is not well-formed XML or exceeds the JDK's entity-expansion limits;
     *         nothing is written
     * @throws ParserConfigurationException when the platform parser rejects the hardened configuration
     * @throws IOException when an output file cannot be written; after a failed {@value #ORDERS_FILE} write,
     *         {@value #REPORT_FILE} is not attempted
     * @throws Exception any other failure of the two scripts, unchanged; nothing is written
     */
    public void process(byte[] orderXml) throws Exception {
        Objects.requireNonNull(orderXml, "orderXml");
        JsonNode currencies = currencyClient.getCurrencies();
        Document orders = parse(orderXml);
        String json = orderMapper.toJson(orders, currencies);
        String csv = orderReportMapper.toCsv(orders);
        fileOutboundWriter.write(outputDir, ORDERS_FILE, json);
        fileOutboundWriter.write(outputDir, REPORT_FILE, csv);
    }

    /**
     * Parses the payload into a namespace-aware DOM with the JDK's built-in parser. The encoding is taken from the
     * bytes and the XML declaration, as the DataWeave reader took it. The parser resolves nothing outside the
     * document: external general and parameter entities, external DTDs and schemas, and XInclude are disabled.
     * An inline DTD stays legal, and the internal entities it declares are expanded into the text and elements
     * they stand for, as DataWeave's reader expanded them. Secure processing caps that expansion, so a document
     * over the JDK's entity-expansion limits fails the parse.
     *
     * @param xml the document bytes
     * @return the parsed document
     * @throws ParserConfigurationException when the parser does not support a hardening feature
     * @throws SAXException when the bytes are not a well-formed XML document, an empty array included, or when
     *         the document exceeds the JDK's entity-expansion limits
     * @throws IOException when the bytes cannot be read
     */
    Document parse(byte[] xml) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(FAIL_ON_ERROR);
        return builder.parse(new ByteArrayInputStream(xml));
    }
}
