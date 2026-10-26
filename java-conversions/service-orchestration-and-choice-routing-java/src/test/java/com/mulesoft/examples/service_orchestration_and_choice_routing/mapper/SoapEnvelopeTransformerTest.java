/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.SAXException;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;
import org.xmlunit.xpath.JAXPXPathEngine;

/**
 * The two XSLT steps of the Ajax {@code orderRequest} flow, run through {@link SoapEnvelopeTransformer} over the
 * stylesheets copied unchanged from the original example.
 *
 * <p>The inputs are the original test's {@code message.xml}, whose {@code order} element is what the flow's
 * JSON-to-XML step hands to {@code XmlToSoapEnvelope.xsl}, and {@code reply.xml}, the 1080 {@code /orders}
 * response that {@code SoapEnvelopeToBodyChild.xslt} reduces to its {@code summary}. Results are compared as XML
 * (similar, whitespace ignored) because both stylesheets declare {@code indent="yes"} and the serializer, not the
 * flow, decides the layout. {@code xsl:copy-of} carries the response's in-scope {@code xmlns:soap} and
 * {@code xmlns:ns2} declarations onto the copied {@code summary}, so the summary comparison filters namespace
 * declarations.
 *
 * <p>The failure tests build the transformer over a parent-less class loader, so the real stylesheets on the
 * test classpath cannot be found and only the files a test supplies are compiled.
 */
class SoapEnvelopeTransformerTest {

    private static final String SOAPENV_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    private static final String ORD_NS = "http://orders.se.mulesoft.com/";

    /** The declaration {@code AjaxOrderXmlMapper} writes ahead of the order document, as DataWeave did. */
    private static final String DATAWEAVE_DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>\n";

    private static final String REPLY = resource("/reply.xml");

    /** Order 12 for John Doe with one Samsung {@code s-1} ({@code AX02}) item of quantity 1. */
    private static final String ORDER = element(resource("/message.xml"), "order");

    private static final String SUMMARY = element(REPLY, "summary");

    private static final String EXPECTED_ENVELOPE = "<soapenv:Envelope xmlns:soapenv=\"" + SOAPENV_NS
            + "\" xmlns:ord=\"" + ORD_NS + "\"><soapenv:Body><ord:processOrder>" + ORDER
            + "</ord:processOrder></soapenv:Body></soapenv:Envelope>";

    private final SoapEnvelopeTransformer transformer = new SoapEnvelopeTransformer();

    @Test
    void wrapsOrderInProcessOrderEnvelope() {
        String envelope = transformer.toEnvelope(ORDER);

        assertSimilar(DiffBuilder.compare(Input.fromString(EXPECTED_ENVELOPE))
                .withTest(Input.fromString(envelope))
                .ignoreWhitespace()
                .checkForSimilar()
                .build());
        assertThat(xpath("count(/soapenv:Envelope/soapenv:Body/ord:processOrder/order)", envelope)).isEqualTo("1");
        assertThat(xpath("/soapenv:Envelope/soapenv:Body/ord:processOrder/order/orderId", envelope)).isEqualTo("12");
    }

    @Test
    void acceptsTheDataWeaveDeclaration() {
        String envelope = transformer.toEnvelope(DATAWEAVE_DECLARATION + ORDER);

        assertSimilar(DiffBuilder.compare(Input.fromString(EXPECTED_ENVELOPE))
                .withTest(Input.fromString(envelope))
                .ignoreWhitespace()
                .checkForSimilar()
                .build());
    }

    @Test
    void extractsSummaryFromReply() {
        String body = transformer.toBodyChild(REPLY);

        assertSimilar(DiffBuilder.compare(Input.fromString(SUMMARY))
                .withTest(Input.fromString(body))
                .withAttributeFilter(attr -> !XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI()))
                .ignoreWhitespace()
                .checkForSimilar()
                .build());
        assertThat(xpath("local-name(/*)", body)).isEqualTo("summary");
        assertThat(xpath("namespace-uri(/*)", body)).isEmpty();
        assertThat(xpath("count(//*[local-name() = 'Envelope' or local-name() = 'processOrderResponse'])", body))
                .isEqualTo("0");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "<order><orderId>12</order>")
    void toEnvelopeRejectsMalformedXml(String orderXml) {
        assertThatThrownBy(() -> transformer.toEnvelope(orderXml))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SoapEnvelopeTransformer.ENVELOPE_STYLESHEET)
                .hasCauseInstanceOf(SAXException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "<soap:Envelope")
    void toBodyChildRejectsMalformedXml(String envelopeXml) {
        assertThatThrownBy(() -> transformer.toBodyChild(envelopeXml))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SoapEnvelopeTransformer.BODY_CHILD_STYLESHEET)
                .hasCauseInstanceOf(SAXException.class);
    }

    @Test
    void rejectsDocumentTypeDeclarations() {
        String externalEntity = "<!DOCTYPE order [<!ENTITY id SYSTEM \"file:///etc/hostname\">]>"
                + "<order><orderId>&id;</orderId></order>";

        assertThatThrownBy(() -> transformer.toEnvelope(externalEntity))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(SAXException.class);
    }

    @Test
    void constructorRejectsMissingStylesheet() {
        ClassLoader withoutStylesheets = new ClassLoader(null) {
        };

        assertThatThrownBy(() -> new SoapEnvelopeTransformer(withoutStylesheets))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stylesheet not found: " + SoapEnvelopeTransformer.ENVELOPE_STYLESHEET);
    }

    @Test
    void constructorRejectsUncompilableStylesheet(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve(SoapEnvelopeTransformer.ENVELOPE_STYLESHEET), "<xsl:stylesheet");

        try (URLClassLoader loader = isolatedLoader(directory)) {
            assertThatThrownBy(() -> new SoapEnvelopeTransformer(loader))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("XSLT stylesheet cannot be compiled")
                    .hasCauseInstanceOf(TransformerConfigurationException.class);
        }
    }

    @Test
    void toEnvelopeReportsFailedTransformation(@TempDir Path directory) throws IOException {
        String terminating = "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" version=\"1.0\">"
                + "<xsl:template match=\"/\"><xsl:message terminate=\"yes\">stop</xsl:message></xsl:template>"
                + "</xsl:stylesheet>";
        Files.writeString(directory.resolve(SoapEnvelopeTransformer.ENVELOPE_STYLESHEET), terminating);
        Files.writeString(directory.resolve(SoapEnvelopeTransformer.BODY_CHILD_STYLESHEET), terminating);

        try (URLClassLoader loader = isolatedLoader(directory)) {
            SoapEnvelopeTransformer failing = new SoapEnvelopeTransformer(loader);

            assertThatThrownBy(() -> failing.toEnvelope(ORDER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("XSLT " + SoapEnvelopeTransformer.ENVELOPE_STYLESHEET + " failed")
                    .hasCauseInstanceOf(TransformerException.class);
        }
    }

    private static void assertSimilar(Diff diff) {
        assertThat(diff.hasDifferences()).as(diff::toString).isFalse();
    }

    private static String xpath(String expression, String xml) {
        JAXPXPathEngine engine = new JAXPXPathEngine();
        engine.setNamespaceContext(Map.of("soapenv", SOAPENV_NS, "ord", ORD_NS));
        return engine.evaluate(expression, Input.fromString(xml).build());
    }

    private static URLClassLoader isolatedLoader(Path directory) throws IOException {
        return new URLClassLoader(new URL[] {directory.toUri().toURL()}, null);
    }

    private static String resource(String name) {
        try (InputStream in = SoapEnvelopeTransformerTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Test resource not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The text of {@code document} from {@code <name>} through the first {@code </name>} after it, inclusive. */
    private static String element(String document, String name) {
        String start = "<" + name + ">";
        String end = "</" + name + ">";
        int from = document.indexOf(start);
        int to = from < 0 ? -1 : document.indexOf(end, from);
        if (to < 0) {
            throw new IllegalStateException("No <" + name + "> element in the test resource");
        }
        return document.substring(from, to + end.length());
    }
}
