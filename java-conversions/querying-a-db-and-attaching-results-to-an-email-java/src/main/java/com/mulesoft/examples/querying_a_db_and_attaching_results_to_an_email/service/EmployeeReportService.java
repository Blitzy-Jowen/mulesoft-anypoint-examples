/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client.EmployeeRepository;
import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client.ReportMailSender;
import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.mapper.EmployeeCsvMapper;

/**
 * Orchestration of flow {@code attachmentsFlow1} of the original
 * {@code querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml} (lines 10-36), from the
 * request body the HTTP listener received to the mailed CSV report. Statements run in the flow's processor order:
 * <ol>
 *   <li>{@code splitter expression="#[xpath('//employee')]"} (line 10): the body is parsed and split into its
 *       {@code employee} elements, in document order;</li>
 *   <li>{@code db:select} (lines 11-15): each element's text is split on a single space and its first two parts are
 *       looked up as first and last name through {@link EmployeeRepository};</li>
 *   <li>{@code collection-aggregator} (line 16): the row lists are collected in document order;</li>
 *   <li>{@code logger level="INFO" message="#[payload]"} (line 17): the aggregate is logged;</li>
 *   <li>the DataWeave CSV script and {@code object-to-string-transformer} (lines 18-33): {@link EmployeeCsvMapper};</li>
 *   <li>{@code set-attachment} and {@code smtp:outbound-endpoint} (lines 35-36): {@link ReportMailSender}.</li>
 * </ol>
 *
 * <p>The flow declares no exception strategy, so nothing here catches, wraps or retries: every failure reaches the
 * platform-default 500 of the global exception handler unchanged, and a failing step leaves every later step
 * unexecuted.
 *
 * <p>Instances hold only their thread-safe collaborators; the XML parser and XPath objects, which are not
 * thread-safe, are created per call.
 */
@Service
public class EmployeeReportService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeReportService.class);

    /** The warning Mule 3.8's {@code ExpressionSplitter} logs when its expression selects nothing. */
    static final String NO_RESULTS_WARNING =
            "Splitter returned no results. If this is not expected, please check your split expression";

    /** The splitter expression of the original flow (attachments.xml:10). */
    static final String EMPLOYEE_XPATH = "//employee";

    /*
     * Parse errors propagate as the parser reports them. Without a handler the JDK's default one also prints
     * "[Fatal Error] ..." to stderr. Warnings do not change the parsed document, so they are dropped rather than
     * printed. The handler is stateless and therefore shared.
     */
    private static final ErrorHandler RETHROWING_ERROR_HANDLER = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) {
            // Dropped on purpose: see the comment on the constant.
        }

        @Override
        public void error(SAXParseException exception) throws SAXParseException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXParseException {
            throw exception;
        }
    };

    private final EmployeeRepository repository;

    private final EmployeeCsvMapper csvMapper;

    private final ReportMailSender mailSender;

    public EmployeeReportService(EmployeeRepository repository, EmployeeCsvMapper csvMapper,
                                 ReportMailSender mailSender) {
        this.repository = repository;
        this.csvMapper = csvMapper;
        this.mailSender = mailSender;
    }

    /**
     * Runs the flow for one request body: looks up every posted employee, logs the aggregated rows, renders them as
     * CSV and mails the CSV as the {@code employees.csv} attachment.
     *
     * @param xml the raw request body; {@code null} is read as an empty body
     * @return the CSV that was mailed, which the original flow left as its response payload; empty when the body
     *         holds no {@code employee} element, in which case nothing is queried, logged at INFO or mailed, as
     *         Mule's splitter ended the flow there
     * @throws SAXParseException when the body is empty or not well-formed XML
     * @throws ArrayIndexOutOfBoundsException when an employee's text contains no space, as the original's
     *         {@code split(" ")[1]} did
     * @throws org.springframework.dao.DataAccessException when a lookup fails
     * @throws IllegalArgumentException when the CSV mapper cannot read a date value
     * @throws org.springframework.mail.MailException when the report cannot be mailed
     * @throws Exception any other XML parser or XPath configuration failure, unchanged
     */
    public Optional<String> generateAndSend(byte[] xml) throws Exception {
        byte[] body = xml == null ? new byte[0] : xml;

        // No secure-processing or entity features are changed: the original Mule parser used the same defaults.
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(RETHROWING_ERROR_HANDLER);
        Document doc = builder.parse(new ByteArrayInputStream(body));

        // Namespace-aware parsing makes //employee select only no-namespace employee elements, as dom4j's did.
        XPath xpath = XPathFactory.newInstance().newXPath();
        NodeList nodes = (NodeList) xpath.evaluate(EMPLOYEE_XPATH, doc, XPathConstants.NODESET);
        if (nodes.getLength() == 0) {
            log.warn(NO_RESULTS_WARNING);
            return Optional.empty();
        }

        List<List<Map<String, Object>>> aggregate = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            // MEL's payload.getText().split(" ") with Java semantics, unguarded: no trimming, empty parts between
            // consecutive spaces kept, trailing ones dropped, and a text without a space fails on parts[1].
            String[] parts = directText(nodes.item(i)).split(" ");
            aggregate.add(repository.findByFirstNameAndLastName(parts[0], parts[1]));
        }

        log.info("{}", aggregate);

        String csv = csvMapper.toCsv(aggregate);

        mailSender.send(csv);

        return Optional.of(csv);
    }

    /**
     * dom4j's {@code Element.getText()}, which the original's {@code payload.getText()} called: the values of the
     * element's direct text and CDATA children concatenated in order, untrimmed; nested elements and comments
     * contribute nothing.
     */
    private static String directText(Node node) {
        StringBuilder text = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            short type = child.getNodeType();
            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            }
        }
        return text.toString();
    }
}
