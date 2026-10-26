/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.mapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The two {@code mulexml:xslt-transformer} steps of the Ajax {@code orderRequest} flow, applying the copied
 * stylesheets unchanged from the classpath root.
 *
 * <ul>
 * <li>{@link #toEnvelope(String)} applies {@value #ENVELOPE_STYLESHEET}: the {@code order} document is wrapped
 * in {@code soapenv:Envelope/soapenv:Body/ord:processOrder} for the POST to the 1080 {@code /orders} service.</li>
 * <li>{@link #toBodyChild(String)} applies {@value #BODY_CHILD_STYLESHEET}: the {@code summary} of the
 * {@code processOrderResponse} is extracted from the SOAP response. The match is on namespace URIs, so the
 * {@code soap:}/{@code ns2:} prefixes the endpoint writes match the stylesheet's {@code soapenv:}/{@code ord:}.
 * The stylesheet has no root template, so the XSLT built-in rules also copy every text node outside the
 * match; for a SOAP fault the result is the fault's bare text, which the following XML-to-JSON step then
 * fails to parse, as the Mule flow did. The result is returned as produced, never repaired.</li>
 * </ul>
 *
 * <p>Each stylesheet is compiled once into thread-safe {@link Templates}; every call creates its own
 * {@link Transformer}. Input is parsed namespace-aware with secure processing and DOCTYPEs rejected. A null,
 * empty or malformed input, or a failed transformation, raises {@link IllegalStateException} carrying the
 * parser or transformer cause; a missing or uncompilable stylesheet does so from the constructor.
 */
@Component
public class SoapEnvelopeTransformer {

    /** Classpath-root stylesheet wrapping an {@code order} document in a {@code processOrder} SOAP envelope. */
    public static final String ENVELOPE_STYLESHEET = "XmlToSoapEnvelope.xsl";

    /** Classpath-root stylesheet extracting {@code summary} from a {@code processOrderResponse} envelope. */
    public static final String BODY_CHILD_STYLESHEET = "SoapEnvelopeToBodyChild.xslt";

    private static final Logger LOGGER = LoggerFactory.getLogger(SoapEnvelopeTransformer.class);

    private static final ErrorListener ERROR_LISTENER = new XsltErrorListener();

    // DefaultHandler throws on fatal errors and ignores the rest: the JAXP default without its printing to stderr.
    private static final ErrorHandler PARSE_ERROR_HANDLER = new DefaultHandler();

    private final Templates envelopeTemplates;

    private final Templates bodyChildTemplates;

    /** Compiles both stylesheets from the root of the class loader that loaded this class. */
    public SoapEnvelopeTransformer() {
        this(SoapEnvelopeTransformer.class.getClassLoader());
    }

    /** Compiles both stylesheets from the root of {@code classLoader}, which tests use to supply other files. */
    SoapEnvelopeTransformer(ClassLoader classLoader) {
        // newDefaultInstance() pins the JDK's XSLTC, so a TransformerFactory service a later dependency registers
        // (Saxon, Xalan) cannot replace it. XSLTC is an XSLT 1.0 processor: it reads the stylesheets'
        // version="2.0" in forwards-compatible mode, which is sound because they use XSLT 1.0 constructs only.
        TransformerFactory factory = TransformerFactory.newDefaultInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            factory.setErrorListener(ERROR_LISTENER);
            this.envelopeTemplates = compile(factory, classLoader, ENVELOPE_STYLESHEET);
            this.bodyChildTemplates = compile(factory, classLoader, BODY_CHILD_STYLESHEET);
        } catch (TransformerConfigurationException | IOException e) {
            throw new IllegalStateException("XSLT stylesheet cannot be compiled: " + e.getMessage(), e);
        }
    }

    /**
     * Wraps an order document in the {@code processOrder} SOAP envelope.
     *
     * @param orderXml XML whose root element is the unqualified {@code order}
     * @return the stylesheet's output, serialized as its {@code xsl:output} declares
     */
    public String toEnvelope(String orderXml) {
        return transform(envelopeTemplates, ENVELOPE_STYLESHEET, orderXml);
    }

    /**
     * Extracts the order summary from a {@code processOrder} SOAP response.
     *
     * @param envelopeXml the SOAP 1.1 response envelope of the order-processing service
     * @return the stylesheet's output: the copied {@code summary} element, plus any text the built-in rules copy
     */
    public String toBodyChild(String envelopeXml) {
        return transform(bodyChildTemplates, BODY_CHILD_STYLESHEET, envelopeXml);
    }

    private static String transform(Templates templates, String stylesheet, String xml) {
        Document document = parse(stylesheet, xml);
        try {
            Transformer transformer = templates.newTransformer();
            transformer.setErrorListener(ERROR_LISTENER);
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toString();
        } catch (TransformerException e) {
            throw new IllegalStateException("XSLT " + stylesheet + " failed: " + e.getMessageAndLocation(), e);
        }
    }

    // A null input is parsed as an empty document, so it fails with the parser's cause like an empty one.
    private static Document parse(String stylesheet, String xml) {
        try {
            DocumentBuilder builder = newDocumentBuilder();
            builder.setErrorHandler(PARSE_ERROR_HANDLER);
            return builder.parse(new InputSource(new StringReader(xml == null ? "" : xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("XSLT " + stylesheet + " input cannot be parsed: " + e.getMessage(), e);
        }
    }

    // DocumentBuilderFactory is not guaranteed thread-safe, so each call configures its own.
    private static DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private static Templates compile(TransformerFactory factory, ClassLoader classLoader, String name)
            throws TransformerConfigurationException, IOException {
        URL resource = classLoader.getResource(name);
        if (resource == null) {
            throw new IllegalStateException("Stylesheet not found: " + name);
        }
        try (InputStream stylesheet = resource.openStream()) {
            return factory.newTemplates(new StreamSource(stylesheet, resource.toExternalForm()));
        }
    }

    /** Logs XSLT warnings at DEBUG and turns errors into failures instead of XSLTC's printing to stderr. */
    private static final class XsltErrorListener implements ErrorListener {

        @Override
        public void warning(TransformerException exception) {
            LOGGER.debug("XSLT warning: {}", exception.getMessageAndLocation());
        }

        @Override
        public void error(TransformerException exception) throws TransformerException {
            throw exception;
        }

        @Override
        public void fatalError(TransformerException exception) throws TransformerException {
            throw exception;
        }
    }
}
