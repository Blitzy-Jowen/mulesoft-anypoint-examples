package com.mulesoft.examples.xml_only_soap_webservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.context.ApplicationContext;
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
 * none, as a {@code NullPointerException} the JVM raised counts), an unknown operation, thrown by an endpoint or
 * reached through an unmapped body root, becomes a {@code soap:Client} fault with CXF's unknown-part text, and a
 * {@code mustUnderstand} header block nothing understands becomes a {@code soap:MustUnderstand} fault with CXF's
 * text before any endpoint runs, each over HTTP 500 in CXF's envelope form, while the receiver built for a proxy
 * service lets such a block through to the endpoint. Codes and strings are that contract
 * verbatim, so they are compared exactly. The WARN line logged for each fault names its kind and the exception's type
 * or the number of header blocks, never the caller data the fault carries.
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

    /** The message of the {@code NullPointerException} the echo endpoint constructs itself. */
    private static final String EXPLICIT_NULL_POINTER_MESSAGE = "Echo text is required";

    private static final String CXF_SERVER_FAULT_BOOM =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><soap:Fault>"
                    + "<faultcode>soap:Server</faultcode><faultstring>boom</faultstring>"
                    + "</soap:Fault></soap:Body></soap:Envelope>";

    /** A body the echo endpoint answers normally. */
    private static final String ECHO_OK = "<e:echo xmlns:e=\"urn:example:echo\">ok</e:echo>";

    /** A header block in namespace {@code urn:x} that the request requires its receiver to understand. */
    private static final String MUST_UNDERSTAND_SEC = "<x:Sec xmlns:x=\"urn:x\" soapenv:mustUnderstand=\"1\"/>";

    private static final String CXF_MUST_UNDERSTAND_FAULT_SEC =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><soap:Fault>"
                    + "<faultcode>soap:MustUnderstand</faultcode>"
                    + "<faultstring>MustUnderstand headers: [{urn:x}Sec] are not understood.</faultstring>"
                    + "</soap:Fault></soap:Body></soap:Envelope>";

    @Autowired
    private SoapMessageDispatcher dispatcher;

    @Autowired
    private WebServiceMessageReceiverHandlerAdapter handlerAdapter;

    @Autowired
    private ThrowingEchoEndpoint endpoint;

    @Autowired
    private SoapEnvelopeStyleInterceptor styler;

    @Autowired
    private ApplicationContext context;

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
                Arguments.of("NullPointerException the JVM raised, whose computed message names internal members",
                        "<e:echo xmlns:e=\"urn:example:echo\">jvm-null</e:echo>",
                        "soap:Server", "Fault occurred while processing."),
                Arguments.of("NullPointerException constructed with a message of its own",
                        "<e:echo xmlns:e=\"urn:example:echo\">explicit-null</e:echo>",
                        "soap:Server", EXPLICIT_NULL_POINTER_MESSAGE),
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
     * Precondition of the JVM-raised case of {@link #faults()}: on this JDK the dereference the echo endpoint performs
     * raises a {@code NullPointerException} whose message the JVM computed, so the default fault string that case
     * expects proves the computed text was withheld.
     */
    @Test
    void jvmRaisedNullPointerExceptionCarriesAComputedMessage() {
        assertThatThrownBy(() -> ThrowingEchoEndpoint.lengthOf(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessageStartingWith("Cannot invoke \"String.length()\"");
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
        MockHttpServletResponse response = post(ECHO_OK);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).contains("echoResponse").doesNotContain("Fault");
    }

    /** CXF's MustUnderstand fault, byte for byte, written before the mapped endpoint is invoked. */
    @Test
    void mustUnderstandHeaderNothingUnderstandsBecomesMustUnderstandFaultInCxfBytes() throws Exception {
        int invocationsBefore = endpoint.invocations();

        MockHttpServletResponse response = post(MUST_UNDERSTAND_SEC, ECHO_OK);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith("text/xml");
        assertThat(body(response)).isEqualTo(CXF_MUST_UNDERSTAND_FAULT_SEC);
        assertThat(endpoint.invocations()).isEqualTo(invocationsBefore);
    }

    /**
     * Header blocks not understood, and the {@code faultstring} CXF wrote for them: the names collected in a
     * {@code HashSet} and rendered by its {@code toString}, which lists a repeated block once and orders the names by
     * hash (the same on the original's Java 8 runtime), not as the request lists them.
     */
    static Stream<Arguments> headersNotUnderstood() {
        return Stream.of(
                Arguments.of("unqualified block",
                        "<Sec soapenv:mustUnderstand=\"1\"/>",
                        "MustUnderstand headers: [Sec] are not understood."),
                Arguments.of("the same block twice",
                        MUST_UNDERSTAND_SEC + MUST_UNDERSTAND_SEC,
                        "MustUnderstand headers: [{urn:x}Sec] are not understood."),
                Arguments.of("two blocks, listed in hash order",
                        MUST_UNDERSTAND_SEC + "<y:Trace xmlns:y=\"urn:y\" soapenv:mustUnderstand=\"1\"/>",
                        "MustUnderstand headers: [{urn:y}Trace, {urn:x}Sec] are not understood."),
                Arguments.of("block addressed to the next actor",
                        "<x:Sec xmlns:x=\"urn:x\" soapenv:mustUnderstand=\"1\""
                                + " soapenv:actor=\"http://schemas.xmlsoap.org/soap/actor/next\"/>",
                        "MustUnderstand headers: [{urn:x}Sec] are not understood."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("headersNotUnderstood")
    void mustUnderstandFaultNamesTheBlocksAsCxfDid(String description, String headerXml, String faultString)
            throws Exception {
        int invocationsBefore = endpoint.invocations();

        MockHttpServletResponse response = post(headerXml, ECHO_OK);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(text(response, "faultcode")).isEqualTo("soap:MustUnderstand");
        assertThat(text(response, "faultstring")).isEqualTo(faultString);
        assertThat(body(response)).startsWith("<soap:Envelope").doesNotContain("<soap:Header")
                .doesNotContain("faultactor").doesNotContain("xml:lang");
        assertThat(endpoint.invocations()).isEqualTo(invocationsBefore);
    }

    /** Header blocks this receiver is not required to understand: description and header content. */
    static Stream<Arguments> headersNotRequiringUnderstanding() {
        return Stream.of(
                Arguments.of("mustUnderstand=\"0\"", "<x:Sec xmlns:x=\"urn:x\" soapenv:mustUnderstand=\"0\"/>"),
                Arguments.of("no mustUnderstand attribute", "<x:Sec xmlns:x=\"urn:x\"/>"),
                Arguments.of("block addressed to another actor",
                        "<x:Sec xmlns:x=\"urn:x\" soapenv:mustUnderstand=\"1\" soapenv:actor=\"urn:other\"/>"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("headersNotRequiringUnderstanding")
    void headerBlockNotRequiringUnderstandingIsDispatchedNormally(String description, String headerXml)
            throws Exception {
        int invocationsBefore = endpoint.invocations();

        MockHttpServletResponse response = post(headerXml, ECHO_OK);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).contains("echoResponse").doesNotContain("Fault");
        assertThat(endpoint.invocations()).isEqualTo(invocationsBefore + 1);
    }

    /**
     * The fault names the caller's block, namespace included, while the one WARN line logged anywhere carries only
     * the number of blocks, without the caller's namespace and without a throwable.
     */
    @Test
    void mustUnderstandWarnLineCarriesTheCountButNotTheCallersNamespace() throws Exception {
        String callerNamespace = "urn:example:" + SENTINEL;
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        MockHttpServletResponse response;
        try {
            response = post("<c:Sec xmlns:c=\"" + callerNamespace + "\" soapenv:mustUnderstand=\"1\"/>", ECHO_OK);
        }
        finally {
            root.detachAppender(appender);
            appender.stop();
        }

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(text(response, "faultstring"))
                .isEqualTo("MustUnderstand headers: [{" + callerNamespace + "}Sec] are not understood.");
        List<ILoggingEvent> warnings = appender.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .toList();
        assertThat(warnings).hasSize(1);
        ILoggingEvent warning = warnings.get(0);
        assertThat(warning.getLevel()).isEqualTo(Level.WARN);
        assertThat(warning.getLoggerName()).isEqualTo(SoapFaultConfig.CxfStyleSoapMessageDispatcher.class.getName());
        assertThat(warning.getFormattedMessage())
                .contains("SOAP MustUnderstand fault")
                .contains(": 1 mandatory header block")
                .doesNotContain(SENTINEL);
        assertThat(warning.getThrowableProxy()).isNull();
    }

    /**
     * The unchecked receiver of a proxy service examines no header block: the block the checked receiver answers with
     * the MustUnderstand fault reaches the mapped endpoint, which answers normally.
     */
    @Test
    void proxyServiceReceiverDispatchesMustUnderstandHeaderNothingUnderstandsNormally() throws Exception {
        SoapMessageDispatcher proxyServiceReceiver = new SoapFaultConfig.CxfStyleSoapMessageDispatcher(styler, false);
        // Collects this context's mappings, adapters and exception resolvers, as the registered receiver does.
        proxyServiceReceiver.setApplicationContext(context);
        int invocationsBefore = endpoint.invocations();

        MockHttpServletResponse response = post(proxyServiceReceiver, MUST_UNDERSTAND_SEC, ECHO_OK);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response)).contains("echoResponse").doesNotContain("Fault").doesNotContain("MustUnderstand");
        assertThat(endpoint.invocations()).isEqualTo(invocationsBefore + 1);
    }

    @Test
    void dispatcherRejectsAMissingStyler() {
        assertThatThrownBy(() -> new SoapFaultConfig.CxfStyleSoapMessageDispatcher(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MockHttpServletResponse post(String bodyChildXml) throws Exception {
        return post(null, bodyChildXml);
    }

    private MockHttpServletResponse post(String headerXml, String bodyChildXml) throws Exception {
        return post(dispatcher, headerXml, bodyChildXml);
    }

    /**
     * Posts a SOAP 1.1 envelope through the servlet handler adapter to {@code receiver}; {@code headerXml}, when not
     * {@code null}, is the content of its {@code Header}, whose prefix {@code soapenv} the envelope declares.
     */
    private MockHttpServletResponse post(SoapMessageDispatcher receiver, String headerXml, String bodyChildXml)
            throws Exception {
        String header = headerXml == null ? "" : "<soapenv:Header>" + headerXml + "</soapenv:Header>";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", REQUEST_PATH);
        request.setContentType(TEXT_XML_UTF8);
        request.setContent(("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">" + header
                + "<soapenv:Body>" + bodyChildXml + "</soapenv:Body></soapenv:Envelope>")
                .getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        handlerAdapter.handle(request, response, receiver);
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

        /**
         * The receiver the project's web-service configuration registers; it detects the mappings, adapters and
         * exception resolvers of this context by type, as the servlet's does.
         */
        @Bean
        SoapMessageDispatcher messageDispatcher(SoapEnvelopeStyleInterceptor styler) {
            return new SoapFaultConfig.CxfStyleSoapMessageDispatcher(styler);
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
     * Fails as the request's text names: {@code fail}, {@code null}, {@code jvm-null}, {@code explicit-null} or
     * {@code unknown}, or {@code fail-with:} followed by the message to throw with; echoes otherwise.
     */
    @Endpoint
    static class ThrowingEchoEndpoint {

        private final AtomicInteger invocations = new AtomicInteger();

        @PayloadRoot(namespace = ECHO_NS, localPart = "echo")
        @ResponsePayload
        public Source echo(@RequestPayload Element request) {
            invocations.incrementAndGet();
            String mode = request.getTextContent().trim();
            if (mode.startsWith(FAIL_WITH_PREFIX)) {
                throw new IllegalStateException(mode.substring(FAIL_WITH_PREFIX.length()));
            }
            switch (mode) {
                case "fail":
                    throw new IllegalStateException("boom");
                case "null":
                    throw new IllegalStateException();
                case "jvm-null":
                    // lengthOf(null) dereferences null, so the JVM raises the exception before any response exists.
                    return new StreamSource(new StringReader(String.valueOf(lengthOf(null))));
                case "explicit-null":
                    throw new NullPointerException(EXPLICIT_NULL_POINTER_MESSAGE);
                case "unknown":
                    throw new SoapFaultConfig.UnknownOperationException(new QName(ECHO_NS, "missing"));
                default:
                    return new StreamSource(new StringReader(
                            "<ns2:echoResponse xmlns:ns2=\"urn:example:echo\"><text>ok</text></ns2:echoResponse>"));
            }
        }

        /** Dereferences {@code text}, so a {@code null} argument makes the JVM raise a NullPointerException. */
        static int lengthOf(String text) {
            return text.length();
        }

        /** @return how often {@link #echo} has been invoked since the context started */
        int invocations() {
            return invocations.get();
        }
    }
}
