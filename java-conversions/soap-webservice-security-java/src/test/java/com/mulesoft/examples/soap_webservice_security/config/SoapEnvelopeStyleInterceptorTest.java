package com.mulesoft.examples.soap_webservice_security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;

import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ws.context.DefaultMessageContext;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.TransportInputStream;

/**
 * Asserts the CXF wire form SC-11 promises for SOAP 1.1 responses and faults. The expected strings are that contract
 * byte for byte (no XML declaration, {@code soap:} envelope prefix, no empty Header, untouched payload), so they are
 * compared exactly, never as similar XML. Every SC-11 consumer carries this class unchanged apart from its package
 * line, which is why the fixtures name no project type or namespace.
 */
class SoapEnvelopeStyleInterceptorTest {

    private static final SaajSoapMessageFactory FACTORY = soap11Factory();

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String TEXT_XML_UTF8 = "text/xml; charset=UTF-8";

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

    private static SaajSoapMessageFactory soap11Factory() {
        try {
            return new SaajSoapMessageFactory(MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL));
        }
        catch (SOAPException ex) {
            throw new IllegalStateException("SAAJ SOAP 1.1 message factory unavailable", ex);
        }
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
}
