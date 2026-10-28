package com.mulesoft.examples.soap_webservice_security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.xml.namespace.QName;

import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPElement;
import jakarta.xml.soap.SOAPException;
import jakarta.xml.soap.SOAPMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ws.context.DefaultMessageContext;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.TransportInputStream;
import org.springframework.ws.transport.TransportOutputStream;

/**
 * Asserts the CXF wire form SC-11 promises for SOAP 1.1 responses and faults. The expected strings are that contract
 * byte for byte (no XML declaration, {@code soap:} envelope prefix, no empty Header, untouched payload), so they are
 * compared exactly, never as similar XML. The messages of {@code CxfStyleMessageFactory} are compared with SAAJ's own
 * bytes and headers, from which they differ only in writing characters outside the Basic Multilingual Plane as raw
 * UTF-8. Every SC-11 consumer carries this class unchanged apart from its package line, which is why the fixtures name
 * no project type or namespace.
 */
class SoapEnvelopeStyleInterceptorTest {

    private static final SaajSoapMessageFactory FACTORY = soap11Factory();

    private static final SaajSoapMessageFactory CXF_FACTORY = cxfStyleSoap11Factory();

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_LENGTH = "Content-Length";
    private static final String SOAP_ACTION = "SOAPAction";
    private static final String TEXT_XML_UTF8 = "text/xml; charset=UTF-8";

    /** U+1F600, outside the Basic Multilingual Plane: two UTF-16 chars. */
    private static final String GRINNING = "\uD83D\uDE00";

    /** U+1F600 in UTF-8, as CXF wrote it. */
    private static final byte[] GRINNING_UTF8 = { (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80 };

    /** U+1F600 as the numeric character reference SAAJ writes. */
    private static final String GRINNING_REFERENCE = "&#128512;";

    /** A response as Spring WS writes it by default. */
    private static final String SPRING_STYLE_RESPONSE =
            "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\"><SOAP-ENV:Header/>"
                    + "<SOAP-ENV:Body><ns2:echoResponse xmlns:ns2=\"urn:example:echo\"><text>hi</text></ns2:echoResponse>"
                    + "</SOAP-ENV:Body></SOAP-ENV:Envelope>";

    private static final String CXF_STYLE_RESPONSE =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                    + "<ns2:echoResponse xmlns:ns2=\"urn:example:echo\"><text>hi</text></ns2:echoResponse>"
                    + "</soap:Body></soap:Envelope>";

    /** A server fault as Spring WS writes it by default. */
    private static final String SPRING_STYLE_SERVER_FAULT =
            "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\"><SOAP-ENV:Header/>"
                    + "<SOAP-ENV:Body><SOAP-ENV:Fault><faultcode>SOAP-ENV:Server</faultcode>"
                    + "<faultstring xml:lang=\"en\">boom</faultstring></SOAP-ENV:Fault></SOAP-ENV:Body></SOAP-ENV:Envelope>";

    private static final String CXF_STYLE_SERVER_FAULT =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><soap:Fault>"
                    + "<faultcode>soap:Server</faultcode><faultstring>boom</faultstring>"
                    + "</soap:Fault></soap:Body></soap:Envelope>";

    private static final String CXF_STYLE_CLIENT_FAULT =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><soap:Fault>"
                    + "<faultcode>soap:Client</faultcode><faultstring>bad</faultstring>"
                    + "</soap:Fault></soap:Body></soap:Envelope>";

    private final SoapEnvelopeStyleInterceptor interceptor = new SoapEnvelopeStyleInterceptor();

    static Stream<Arguments> responses() {
        return Stream.of(
                Arguments.of("Spring WS default style", SPRING_STYLE_RESPONSE, CXF_STYLE_RESPONSE),
                Arguments.of("payload with its own prefix",
                        "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                                + "<SOAP-ENV:Header/><SOAP-ENV:Body>"
                                + "<e:echoResponse xmlns:e=\"urn:example:echo\"><text>hi</text></e:echoResponse>"
                                + "</SOAP-ENV:Body></SOAP-ENV:Envelope>",
                        "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                                + "<e:echoResponse xmlns:e=\"urn:example:echo\"><text>hi</text></e:echoResponse>"
                                + "</soap:Body></soap:Envelope>"),
                Arguments.of("whitespace between structural elements",
                        "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
                                + "  <SOAP-ENV:Header/>\n"
                                + "  <SOAP-ENV:Body>\n"
                                + "    <ns2:echoResponse xmlns:ns2=\"urn:example:echo\">\n"
                                + "      <text>hi</text>\n"
                                + "    </ns2:echoResponse>\n"
                                + "  </SOAP-ENV:Body>\n"
                                + "</SOAP-ENV:Envelope>",
                        "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                                + "<ns2:echoResponse xmlns:ns2=\"urn:example:echo\">\n"
                                + "      <text>hi</text>\n"
                                + "    </ns2:echoResponse>"
                                + "</soap:Body></soap:Envelope>"),
                Arguments.of("non-empty Header kept",
                        "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                                + "<SOAP-ENV:Header><h:trace xmlns:h=\"urn:example:echo\">1</h:trace></SOAP-ENV:Header>"
                                + "<SOAP-ENV:Body><ns2:echoResponse xmlns:ns2=\"urn:example:echo\"><text>hi</text>"
                                + "</ns2:echoResponse></SOAP-ENV:Body></SOAP-ENV:Envelope>",
                        "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                                + "<soap:Header><h:trace xmlns:h=\"urn:example:echo\">1</h:trace></soap:Header>"
                                + "<soap:Body><ns2:echoResponse xmlns:ns2=\"urn:example:echo\"><text>hi</text>"
                                + "</ns2:echoResponse></soap:Body></soap:Envelope>"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("responses")
    void restylesResponseIntoCxfBytes(String description, String input, String expected) {
        SoapMessage message = parse(input);

        interceptor.restyle(message);

        assertThat(bytes(message)).isEqualTo(expected);
    }

    @Test
    void restyledResponseMeetsEachCxfRule() {
        SoapMessage message = parse(SPRING_STYLE_RESPONSE);

        interceptor.restyle(message);

        String restyled = bytes(message);
        assertThat(restyled).as("no XML declaration").doesNotStartWith("<?xml");
        assertThat(restyled).as("envelope prefix soap, not SOAP-ENV").doesNotContain("SOAP-ENV");
        assertThat(restyled).as("empty Header dropped").doesNotContain("Header");
        assertThat(restyled).as("payload and its declaration unchanged")
                .contains("ns2:echoResponse xmlns:ns2=\"urn:example:echo\"");
    }

    static Stream<Arguments> faults() {
        return Stream.of(
                Arguments.of("parsed Spring WS server fault",
                        (Supplier<SoapMessage>) () -> parse(SPRING_STYLE_SERVER_FAULT), CXF_STYLE_SERVER_FAULT),
                Arguments.of("server fault built through the API",
                        (Supplier<SoapMessage>) () -> {
                            SoapMessage message = FACTORY.createWebServiceMessage();
                            message.getSoapBody().addServerOrReceiverFault("boom", Locale.ENGLISH);
                            return message;
                        }, CXF_STYLE_SERVER_FAULT),
                Arguments.of("client fault built through the API",
                        (Supplier<SoapMessage>) () -> {
                            SoapMessage message = FACTORY.createWebServiceMessage();
                            message.getSoapBody().addClientOrSenderFault("bad", Locale.ENGLISH);
                            return message;
                        }, CXF_STYLE_CLIENT_FAULT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("faults")
    void restylesFaultIntoCxfBytes(String description, Supplier<SoapMessage> fault, String expected) {
        SoapMessage message = fault.get();

        interceptor.restyle(message);

        String restyled = bytes(message);
        assertThat(restyled).isEqualTo(expected);
        assertThat(restyled).as("faultstring without xml:lang").doesNotContain("xml:lang");
    }

    static Stream<Arguments> restyleInputs() {
        return Stream.of(
                Arguments.of("response", SPRING_STYLE_RESPONSE),
                Arguments.of("fault", SPRING_STYLE_SERVER_FAULT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("restyleInputs")
    void restyleIsIdempotent(String description, String input) {
        SoapMessage message = parse(input);

        interceptor.restyle(message);
        String once = bytes(message);
        interceptor.restyle(message);

        assertThat(bytes(message)).isEqualTo(once);
    }

    @Test
    void handleResponseAndHandleFaultRestyleTheResponseAndContinueTheChain() {
        MessageContext responseContext = new DefaultMessageContext(parse(SPRING_STYLE_RESPONSE), FACTORY);
        responseContext.setResponse(parse(SPRING_STYLE_RESPONSE));
        MessageContext faultContext = new DefaultMessageContext(parse(SPRING_STYLE_RESPONSE), FACTORY);
        faultContext.setResponse(parse(SPRING_STYLE_SERVER_FAULT));

        assertThat(interceptor.handleResponse(responseContext, new Object())).isTrue();
        assertThat(interceptor.handleFault(faultContext, new Object())).isTrue();

        assertThat(bytes((SoapMessage) responseContext.getResponse())).isEqualTo(CXF_STYLE_RESPONSE);
        assertThat(bytes((SoapMessage) faultContext.getResponse())).isEqualTo(CXF_STYLE_SERVER_FAULT);
    }

    @Test
    void requestIsLeftUntouchedAndNoResponseIsCreated() {
        SoapMessage request = parse(SPRING_STYLE_RESPONSE);
        String before = bytes(request);
        MessageContext context = new DefaultMessageContext(request, FACTORY);

        assertThat(interceptor.handleRequest(context, new Object())).isTrue();
        assertThat(interceptor.handleResponse(context, new Object())).isTrue();
        assertThat(interceptor.handleFault(context, new Object())).isTrue();
        interceptor.afterCompletion(context, new Object(), null);

        assertThat(context.hasResponse()).as("no response created").isFalse();
        assertThat(bytes(request)).isEqualTo(before);
    }

    @Test
    void nullMessageIsIgnored() {
        assertThatCode(() -> interceptor.restyle(null)).doesNotThrowAnyException();
    }

    @Test
    void soap12EnvelopeIsLeftUnchanged() throws SOAPException {
        SaajSoapMessageFactory soap12Factory =
                new SaajSoapMessageFactory(MessageFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL));
        SoapMessage message = soap12Factory.createWebServiceMessage();
        String before = bytes(message);

        interceptor.restyle(message);

        assertThat(bytes(message)).isEqualTo(before);
    }

    static Stream<Arguments> payloadTexts() {
        return Stream.of(
                Arguments.of("U+1F600 between ASCII letters", "A" + GRINNING + "B"),
                Arguments.of("BMP characters only", "Zo\u00eb-\u674e"),
                Arguments.of("BMP characters and U+1F600", "Zo\u00eb-\u674e-" + GRINNING));
    }

    /**
     * SAAJ writes U+1F600 in text and attribute values as {@code &#128512;}, CXF as its four UTF-8 bytes; nothing
     * else differs, so a message without such a character is written byte for byte as SAAJ writes it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("payloadTexts")
    void cxfStyleMessageWritesSaajBytesWithSupplementaryCharactersRaw(String description, String text) {
        byte[] saaj = restyledBytes(FACTORY, text);
        byte[] cxf = restyledBytes(CXF_FACTORY, text);

        assertThat(new String(saaj, StandardCharsets.UTF_8).contains(GRINNING_REFERENCE))
                .as("SAAJ writes a character reference").isEqualTo(text.contains(GRINNING));
        assertThat(cxf).isEqualTo(
                new String(saaj, StandardCharsets.UTF_8).replace(GRINNING_REFERENCE, GRINNING)
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(cxf).containsSequence(text.getBytes(StandardCharsets.UTF_8));
        assertThat(new String(cxf, StandardCharsets.UTF_8)).doesNotContain("&#")
                .contains("note=\"" + text + "\"", "<text>" + text + "</text>");
    }

    @Test
    void cxfStyleMessageHandsTheTransportSaajsHeadersWithTheLengthOfTheBytesWritten() throws IOException {
        SaajSoapMessage saaj = echoResponse(FACTORY, "A" + GRINNING + "B");
        SaajSoapMessage cxf = echoResponse(CXF_FACTORY, "A" + GRINNING + "B");
        interceptor.restyle(saaj);
        interceptor.restyle(cxf);
        RecordingTransportOutputStream saajTransport = new RecordingTransportOutputStream();
        RecordingTransportOutputStream cxfTransport = new RecordingTransportOutputStream();

        saaj.writeTo(saajTransport);
        cxf.writeTo(cxfTransport);

        byte[] written = cxfTransport.body.toByteArray();
        assertThat(written).containsSequence(GRINNING_UTF8);
        assertThat(cxfTransport.values(CONTENT_LENGTH)).containsExactly(Integer.toString(written.length));
        assertThat(saajTransport.values(CONTENT_LENGTH))
                .containsExactly(Integer.toString(saajTransport.body.size()));
        assertThat(cxfTransport.values(CONTENT_TYPE)).isEqualTo(saajTransport.values(CONTENT_TYPE))
                .singleElement().asString().startsWith("text/xml");
        assertThat(cxfTransport.headersWithout(CONTENT_LENGTH)).isEqualTo(saajTransport.headersWithout(CONTENT_LENGTH));
        assertThat(cxf.getSaajMessage().getMimeHeaders().getHeader(SOAP_ACTION))
                .as("SOAPAction SAAJ sets after a write").containsExactly("\"\"")
                .isEqualTo(saaj.getSaajMessage().getMimeHeaders().getHeader(SOAP_ACTION));
    }

    @Test
    void cxfStyleMessageWritesSaajsXmlDeclarationWhenAskedTo() throws SOAPException {
        SaajSoapMessage saaj = echoResponse(FACTORY, "A" + GRINNING + "B");
        SaajSoapMessage cxf = echoResponse(CXF_FACTORY, "A" + GRINNING + "B");
        saaj.getSaajMessage().setProperty(SOAPMessage.WRITE_XML_DECLARATION, "true");
        cxf.getSaajMessage().setProperty(SOAPMessage.WRITE_XML_DECLARATION, "true");

        byte[] saajBytes = writtenBytes(saaj);
        byte[] cxfBytes = writtenBytes(cxf);

        assertThat(new String(cxfBytes, StandardCharsets.UTF_8))
                .startsWith("<?xml version=\"1.0\" encoding=\"utf-8\" ?><")
                .contains("<text>A" + GRINNING + "B</text>");
        assertThat(cxfBytes).isEqualTo(new String(saajBytes, StandardCharsets.UTF_8)
                .replace(GRINNING_REFERENCE, GRINNING).getBytes(StandardCharsets.UTF_8));
    }

    static Stream<Arguments> messagesSaajWrites() {
        return Stream.of(
                Arguments.of("unpaired low surrogate beside U+1F600", "A" + GRINNING + "B\uDE00", null),
                Arguments.of("character set ISO-8859-1", "A" + GRINNING + "B", "ISO-8859-1"));
    }

    /** A message UTF-8 cannot carry raw is written as SAAJ writes it, U+1F600 as its character reference included. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("messagesSaajWrites")
    void cxfStyleMessageFallsBackToSaajsBytes(String description, String text, String characterSetEncoding)
            throws SOAPException {
        SaajSoapMessage saaj = echoResponse(FACTORY, text);
        SaajSoapMessage cxf = echoResponse(CXF_FACTORY, text);
        if (characterSetEncoding != null) {
            saaj.getSaajMessage().setProperty(SOAPMessage.CHARACTER_SET_ENCODING, characterSetEncoding);
            cxf.getSaajMessage().setProperty(SOAPMessage.CHARACTER_SET_ENCODING, characterSetEncoding);
        }
        interceptor.restyle(saaj);
        interceptor.restyle(cxf);

        byte[] saajBytes = writtenBytes(saaj);

        assertThat(writtenBytes(cxf)).isEqualTo(saajBytes);
        assertThat(new String(saajBytes, StandardCharsets.ISO_8859_1)).contains(GRINNING_REFERENCE);
    }

    static Stream<Arguments> faultStringLangSettings() {
        return Stream.of(
                Arguments.of("Spring's default", null, true),
                Arguments.of("set to true", true, true),
                Arguments.of("set to false", false, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("faultStringLangSettings")
    void cxfStyleFactoryAppliesTheFaultStringLangSettingAsSpringsDoes(String description, Boolean setting,
            boolean expectLang) {
        SaajSoapMessageFactory spring = soap11Factory();
        SoapEnvelopeStyleInterceptor.CxfStyleMessageFactory cxf = cxfStyleSoap11Factory();
        if (setting != null) {
            spring.setLangAttributeOnSoap11FaultString(setting);
            cxf.setLangAttributeOnSoap11FaultString(setting);
        }
        SoapMessage springFault = spring.createWebServiceMessage();
        SoapMessage cxfFault = cxf.createWebServiceMessage();
        springFault.getSoapBody().addServerOrReceiverFault("boom", Locale.ENGLISH);
        cxfFault.getSoapBody().addServerOrReceiverFault("boom", Locale.ENGLISH);

        String written = bytes(cxfFault);

        assertThat(written).isEqualTo(bytes(springFault));
        assertThat(written.contains("xml:lang=\"en\"")).as("faultstring xml:lang").isEqualTo(expectLang);
    }

    private static SaajSoapMessageFactory soap11Factory() {
        try {
            return new SaajSoapMessageFactory(MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL));
        }
        catch (SOAPException ex) {
            throw new IllegalStateException("SAAJ SOAP 1.1 message factory unavailable", ex);
        }
    }

    private static SoapEnvelopeStyleInterceptor.CxfStyleMessageFactory cxfStyleSoap11Factory() {
        try {
            return new SoapEnvelopeStyleInterceptor.CxfStyleMessageFactory(
                    MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL));
        }
        catch (SOAPException ex) {
            throw new IllegalStateException("SAAJ SOAP 1.1 message factory unavailable", ex);
        }
    }

    /**
     * A new message of {@code factory} whose Body holds {@code <ns2:echoResponse note="text"><text>text</text>}. The
     * text goes in through the DOM, which accepts any string, an unpaired surrogate included.
     */
    private static SaajSoapMessage echoResponse(SaajSoapMessageFactory factory, String text) {
        SaajSoapMessage message = factory.createWebServiceMessage();
        try {
            SOAPElement response = message.getSaajMessage().getSOAPBody()
                    .addChildElement(new QName("urn:example:echo", "echoResponse", "ns2"));
            response.setAttributeNS(null, "note", text);
            response.addChildElement("text").setTextContent(text);
        }
        catch (SOAPException ex) {
            throw new IllegalStateException("Cannot build the echo response", ex);
        }
        return message;
    }

    private byte[] restyledBytes(SaajSoapMessageFactory factory, String text) {
        SaajSoapMessage message = echoResponse(factory, text);
        interceptor.restyle(message);
        return writtenBytes(message);
    }

    static byte[] writtenBytes(SoapMessage message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            message.writeTo(out);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    /** Reads {@code xml} as a received SOAP 1.1 message; saaj-impl refuses a message without Content-Type. */
    static SoapMessage parse(String xml) {
        try {
            return FACTORY.createWebServiceMessage(new XmlTransportInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static String bytes(SoapMessage message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            message.writeTo(out);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static final class XmlTransportInputStream extends TransportInputStream {

        private final byte[] content;

        XmlTransportInputStream(byte[] content) {
            this.content = content;
        }

        @Override
        protected InputStream createInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public Iterator<String> getHeaderNames() {
            return List.of(CONTENT_TYPE).iterator();
        }

        @Override
        public Iterator<String> getHeaders(String name) {
            return CONTENT_TYPE.equalsIgnoreCase(name)
                    ? List.of(TEXT_XML_UTF8).iterator()
                    : Collections.emptyIterator();
        }
    }

    /** A transport stream recording, in order, the headers handed to it, and the bytes written to it. */
    private static final class RecordingTransportOutputStream extends TransportOutputStream {

        private final List<Map.Entry<String, String>> headers = new ArrayList<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        @Override
        public void addHeader(String name, String value) {
            headers.add(Map.entry(name, value));
        }

        @Override
        protected OutputStream createOutputStream() {
            return body;
        }

        List<String> values(String name) {
            return headers.stream().filter(header -> header.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue).toList();
        }

        List<Map.Entry<String, String>> headersWithout(String name) {
            return headers.stream().filter(header -> !header.getKey().equalsIgnoreCase(name)).toList();
        }
    }
}
