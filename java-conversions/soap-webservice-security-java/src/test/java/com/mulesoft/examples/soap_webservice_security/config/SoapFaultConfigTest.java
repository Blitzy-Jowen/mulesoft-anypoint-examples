package com.mulesoft.examples.soap_webservice_security.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.endpoint.adapter.DefaultMethodEndpointAdapter;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.springframework.ws.server.endpoint.mapping.PayloadRootAnnotationMethodEndpointMapping;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.soap.server.SoapMessageDispatcher;
import org.springframework.ws.transport.http.WebServiceMessageReceiverHandlerAdapter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Asserts the CXF fault contract SC-11 promises for failures inside Spring WS endpoint dispatch: an endpoint exception
 * becomes a SOAP 1.1 {@code soap:Server} fault carrying the exception message (or CXF's default text when it has
 * none), and an unknown operation, thrown by an endpoint or reached through an unmapped body root, becomes a
 * {@code soap:Client} fault with CXF's unknown-part text, each over HTTP 500 in CXF's envelope form. Codes and strings
 * are that contract verbatim, so they are compared exactly. The WARN line logged for each fault names its kind and the
 * exception's type, never the message the fault carries.
 * <p>
 * The context holds only the SC-11 beans and the Spring WS dispatch infrastructure the servlet would use, around an
 * inline echo endpoint, and requests travel through the real servlet handler adapter. Every SC-11 consumer carries
 * this class unchanged apart from its package line, which is why the fixtures name no project type or namespace.
 */
@SpringJUnitConfig(SoapFaultConfigTest.TestConfig.class)
class SoapFaultConfigTest {

    private static final String ECHO_NS = "urn:example:echo";

    private static final String REQUEST_PATH = "/svc/Echo";

    private static final String TEXT_XML_UTF8 = "text/xml; charset=UTF-8";

    /** Caller-supplied text that the fault carries and no log line may repeat. */
    private static final String SENTINEL = "sentinel-c41f9e";

    /** Request text that makes the echo endpoint throw with the rest of the text as the exception message. */
    private static final String FAIL_WITH_PREFIX = "fail-with:";

    private static final String CXF_SERVER_FAULT_BOOM =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><soap:Fault>"
                    + "<faultcode>soap:Server</faultcode><faultstring>boom</faultstring>"
                    + "</soap:Fault></soap:Body></soap:Envelope>";

    @Autowired
    private SoapMessageDispatcher dispatcher;

    @Autowired
    private WebServiceMessageReceiverHandlerAdapter handlerAdapter;

    @Test
    void endpointExceptionBecomesServerFaultInCxfBytes() throws Exception {
        MockHttpServletResponse response = post("<e:echo xmlns:e=\"urn:example:echo\">fail</e:echo>");

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith("text/xml");
        assertThat(body(response)).isEqualTo(CXF_SERVER_FAULT_BOOM);
    }

    static Stream<Arguments> faults() {
        return Stream.of(
                Arguments.of("exception without a message",
                        "<e:echo xmlns:e=\"urn:example:echo\">null</e:echo>",
                        "soap:Server", "Fault occurred while processing."),
                Arguments.of("unknown operation thrown by an endpoint",
                        "<e:echo xmlns:e=\"urn:example:echo\">unknown</e:echo>",
                        "soap:Client",
                        "Message part {urn:example:echo}missing was not recognized.  (Does it exist in service WSDL?)"),
                Arguments.of("unmapped payload root reaches the catch-all mapping",
                        "<e:other xmlns:e=\"urn:example:echo\"/>",
                        "soap:Client",
                        "Message part {urn:example:echo}other was not recognized.  (Does it exist in service WSDL?)"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("faults")
    void answersWithCxfFault(String description, String bodyChildXml, String faultCode, String faultString)
            throws Exception {
        MockHttpServletResponse response = post(bodyChildXml);

        // 500 rather than the bare 404 Spring WS sends when no endpoint is found.
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith("text/xml");
        assertThat(text(response, "faultcode")).isEqualTo(faultCode);
        assertThat(text(response, "faultstring")).isEqualTo(faultString);
        assertThat(body(response)).startsWith("<soap:Envelope").doesNotContain("SOAP-ENV").doesNotContain("xml:lang");
    }

    /**
     * Faults whose message carries caller data: description, body child, fault code, fault string, fault kind in the
     * WARN line, exception type and the start of its throw site.
     */
    static Stream<Arguments> faultsWhoseMessageCarriesCallerData() {
        String unmappedRootNamespace = "urn:example:" + SENTINEL;
        String endpointMessage = "Rejected request " + SENTINEL;
        return Stream.of(
                Arguments.of("unmapped payload root in a caller-chosen namespace",
                        "<x:other xmlns:x=\"" + unmappedRootNamespace + "\"/>",
                        "soap:Client",
                        "Message part {" + unmappedRootNamespace
                                + "}other was not recognized.  (Does it exist in service WSDL?)",
                        "Client", SoapFaultConfig.UnknownOperationException.class,
                        SoapFaultConfig.UnknownOperationEndpointMapping.class.getName()
                                + "$UnknownOperationEndpoint.invoke:"),
                Arguments.of("endpoint exception whose message carries request data",
                        "<e:echo xmlns:e=\"urn:example:echo\">" + FAIL_WITH_PREFIX + endpointMessage + "</e:echo>",
                        "soap:Server", endpointMessage,
                        "Server", IllegalStateException.class, ThrowingEchoEndpoint.class.getName() + ".echo:"));
    }

    /**
     * The fault carries the message with the caller data, while the one WARN line names the fault kind, the exception
     * type and its throw site only, without the message and without the throwable.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("faultsWhoseMessageCarriesCallerData")
    void warnLineNamesFaultKindTypeAndThrowSiteButNotTheMessage(String description, String bodyChildXml,
            String faultCode, String faultString, String faultKind, Class<? extends Exception> exceptionType,
            String throwingMethod) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(SoapFaultConfig.CxfStyleSoapFaultResolver.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MockHttpServletResponse response;
        try {
            response = post(bodyChildXml);
        }
        finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        // The fault still carries the message, caller data included.
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(text(response, "faultcode")).isEqualTo(faultCode);
        assertThat(text(response, "faultstring")).isEqualTo(faultString).contains(SENTINEL);
        // The resolver's DEBUG trace belongs to the framework; the WARN line is this resolver's own.
        List<ILoggingEvent> warnings = appender.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .toList();
        assertThat(warnings).hasSize(1);
        ILoggingEvent warning = warnings.get(0);
        assertThat(warning.getLevel()).isEqualTo(Level.WARN);
        assertThat(warning.getFormattedMessage())
                .contains("SOAP " + faultKind + " fault")
                .contains(exceptionType.getName())
                .contains(" thrown at " + throwingMethod)
                .doesNotContain(SENTINEL);
        assertThat(warning.getThrowableProxy()).isNull();
    }

    @Test
    void dispatchesMappedRequestNormally() throws Exception {
        MockHttpServletResponse response = post("<e:echo xmlns:e=\"urn:example:echo\">ok</e:echo>");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).contains("echoResponse").doesNotContain("Fault");
    }

    private MockHttpServletResponse post(String bodyChildXml) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", REQUEST_PATH);
        request.setContentType(TEXT_XML_UTF8);
        request.setContent(("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>"
                + bodyChildXml + "</soapenv:Body></soapenv:Envelope>").getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        handlerAdapter.handle(request, response, dispatcher);
        return response;
    }

    private static String body(MockHttpServletResponse response) {
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** Returns the text of the response's only element named {@code localName}, in whatever namespace. */
    private static String text(MockHttpServletResponse response, String localName) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(response.getContentAsByteArray()));
        NodeList matches = document.getElementsByTagNameNS("*", localName);
        assertThat(matches.getLength()).as("number of <%s> elements", localName).isEqualTo(1);
        return matches.item(0).getTextContent();
    }

    /**
     * The SC-11 beans plus the dispatch infrastructure Spring Boot's web-services auto-configuration and the project's
     * web-service configuration would contribute. Nothing is component-scanned.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({ SoapFaultConfig.class, SoapEnvelopeStyleInterceptor.class })
    static class TestConfig {

        /**
         * Carries the styler as the project's web-service configuration registers it, so faults raised by mapped
         * endpoints are restyled. Order 0 keeps it ahead of the catch-all mapping, whose order is the lowest.
         */
        @Bean
        PayloadRootAnnotationMethodEndpointMapping payloadRootAnnotationMethodEndpointMapping(
                SoapEnvelopeStyleInterceptor styler) {
            PayloadRootAnnotationMethodEndpointMapping mapping = new PayloadRootAnnotationMethodEndpointMapping();
            mapping.setOrder(0);
            mapping.setInterceptors(new EndpointInterceptor[] { styler });
            return mapping;
        }

        /** Declared because the adapter bean SoapFaultConfig contributes stops the dispatcher's default adapters. */
        @Bean
        DefaultMethodEndpointAdapter defaultMethodEndpointAdapter() {
            return new DefaultMethodEndpointAdapter();
        }

        @Bean
        ThrowingEchoEndpoint throwingEchoEndpoint() {
            return new ThrowingEchoEndpoint();
        }

        @Bean
        SaajSoapMessageFactory messageFactory() {
            SaajSoapMessageFactory messageFactory = new SaajSoapMessageFactory();
            messageFactory.setSoapVersion(SoapVersion.SOAP_11);
            return messageFactory;
        }

        /** Detects the mappings, adapters and exception resolvers of this context by type, as the servlet's does. */
        @Bean
        SoapMessageDispatcher messageDispatcher() {
            return new SoapMessageDispatcher();
        }

        @Bean
        WebServiceMessageReceiverHandlerAdapter webServiceMessageReceiverHandlerAdapter(
                SaajSoapMessageFactory messageFactory) {
            WebServiceMessageReceiverHandlerAdapter adapter = new WebServiceMessageReceiverHandlerAdapter();
            adapter.setMessageFactory(messageFactory);
            return adapter;
        }
    }

    /**
     * Fails as the request's text names: {@code fail}, {@code null} or {@code unknown}, or {@code fail-with:} followed
     * by the message to throw with; echoes otherwise.
     */
    @Endpoint
    static class ThrowingEchoEndpoint {

        @PayloadRoot(namespace = ECHO_NS, localPart = "echo")
        @ResponsePayload
        public Source echo(@RequestPayload Element request) {
            String mode = request.getTextContent().trim();
            if (mode.startsWith(FAIL_WITH_PREFIX)) {
                throw new IllegalStateException(mode.substring(FAIL_WITH_PREFIX.length()));
            }
            switch (mode) {
                case "fail":
                    throw new IllegalStateException("boom");
                case "null":
                    throw new IllegalStateException();
                case "unknown":
                    throw new SoapFaultConfig.UnknownOperationException(new QName(ECHO_NS, "missing"));
                default:
                    return new StreamSource(new StringReader(
                            "<ns2:echoResponse xmlns:ns2=\"urn:example:echo\"><text>ok</text></ns2:echoResponse>"));
            }
        }
    }
}
