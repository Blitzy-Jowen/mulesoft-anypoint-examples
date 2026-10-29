package com.mulesoft.examples.service_orchestration_and_choice_routing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ws.InvalidXmlException;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.SoapMessageCreationException;
import org.springframework.ws.soap.SoapMessageFactory;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.WebServiceMessageReceiver;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Asserts what the SOAP listener boundary answers before any endpoint is resolved: the listener's 405 for a method a
 * POST-only listener does not accept, the {@code soap:Client} fault for a body that is not a SOAP 1.1 envelope, and
 * the CXF envelope style of every response a receiver leaves. The 405 body and the fault code are the listener
 * contract and are compared exactly; the fault string is the parser's message, so its origin is asserted rather than
 * a fixed parser text, and CXF's default text only for a failure that carries no message. Every SC-11 consumer
 * carries this class unchanged apart from its package line, which is why it uses its own POST-only pattern and
 * fixtures that name no project type, path or namespace.
 */
class SoapListenerBoundaryAdapterTest {

    private static final String SOAP11_NS = SoapEnvelopeStyleInterceptor.SOAP11_NS;

    private static final String POST_ONLY_PATTERN = "/post-only/**";
    private static final String POST_ONLY_PATH = "/post-only/Echo";
    private static final String ALL_METHODS_PATH = "/svc/Echo";

    private static final String VALID_ENVELOPE =
            "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Header/>"
                    + "<soapenv:Body><e:echo xmlns:e=\"urn:example:echo\"><text>hi</text></e:echo></soapenv:Body>"
                    + "</soapenv:Envelope>";

    private static final String MALFORMED_ENVELOPE =
            "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body>";

    private static final String NON_ENVELOPE_ROOT = "<e:echo xmlns:e=\"urn:example:echo\"><text>hi</text></e:echo>";

    private static final String ECHO_RESPONSE_PAYLOAD =
            "<ns2:echoResponse xmlns:ns2=\"urn:example:echo\"><text>hi</text></ns2:echoResponse>";

    private static final String CXF_STYLE_ECHO_RESPONSE =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
                    + ECHO_RESPONSE_PAYLOAD + "</soap:Body></soap:Envelope>";

    private static final String CLIENT_FAULT_PREFIX =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><soap:Fault>"
                    + "<faultcode>soap:Client</faultcode><faultstring>";

    private static final String CLIENT_FAULT_SUFFIX = "</faultstring></soap:Fault></soap:Body></soap:Envelope>";

    /**
     * The fault string CXF wrote when it had no more specific message. The boundary uses it only for a failure that
     * carries no message, so no fault whose failure has a message may carry it.
     */
    private static final String GENERIC_CXF_FAULT_STRING = "Fault occurred while processing.";

    /**
     * CXF's default {@code org.apache.cxf.stax.maxElementDepth}, the root element counting as depth 1: the deepest
     * nesting the original listeners read.
     */
    private static final int CXF_MAX_ELEMENT_DEPTH = 100;

    private SoapListenerBoundaryAdapter adapter;
    private AtomicInteger receiverCalls;
    private WebServiceMessageReceiver echo;

    @BeforeEach
    void setUp() throws Exception {
        SaajSoapMessageFactory factory = new SaajSoapMessageFactory();
        factory.setSoapVersion(SoapVersion.SOAP_11);
        factory.afterPropertiesSet();
        SoapEnvelopeStyleInterceptor styler = new SoapEnvelopeStyleInterceptor();
        adapter = new SoapListenerBoundaryAdapter(factory, List.of(POST_ONLY_PATTERN), styler);
        adapter.afterPropertiesSet();

        receiverCalls = new AtomicInteger();
        echo = context -> {
            receiverCalls.incrementAndGet();
            Transformer identity = TransformerFactory.newInstance().newTransformer();
            identity.transform(new StreamSource(new StringReader(ECHO_RESPONSE_PAYLOAD)),
                    context.getResponse().getPayloadResult());
        };
    }

    static Stream<Arguments> methodsAPostOnlyListenerRejects() {
        return Stream.of(
                Arguments.of("GET", null),
                Arguments.of("PUT", VALID_ENVELOPE),
                Arguments.of("DELETE", VALID_ENVELOPE));
    }

    @ParameterizedTest(name = "{0} on a POST-only listener")
    @MethodSource("methodsAPostOnlyListenerRejects")
    void nonPostRequestToPostOnlyListenerIsAnsweredWith405(String method, String body) throws Exception {
        MockHttpServletResponse response = handle(request(method, POST_ONLY_PATH, body));

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getContentType()).isEqualTo("text/plain");
        assertThat(response.getHeader("Content-Type")).isEqualTo("text/plain").doesNotContainIgnoringCase("charset");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("Method not allowed for endpoint: " + POST_ONLY_PATH);
        assertThat(response.getHeader("Allow")).isNull();
        assertThat(receiverCalls).hasValue(0);
    }

    static Stream<Arguments> bodiesThatAreNotSoapEnvelopes() {
        return Stream.of(
                Arguments.of("malformed XML", "POST", MALFORMED_ENVELOPE),
                Arguments.of("empty body", "POST", ""),
                Arguments.of("root other than soap:Envelope", "POST", NON_ENVELOPE_ROOT),
                Arguments.of("GET without a metadata query on a listener accepting every method", "GET", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodiesThatAreNotSoapEnvelopes")
    void bodyThatIsNotSoapEnvelopeIsAnsweredWithClientFault(String description, String method, String body)
            throws Exception {
        MockHttpServletResponse response = handle(request(method, ALL_METHODS_PATH, body));

        assertClientFault(response);
        assertThat(receiverCalls).hasValue(0);
    }

    static Stream<Arguments> bodiesThatAreNotWellFormed() {
        return Stream.of(
                Arguments.of("malformed XML", "POST", MALFORMED_ENVELOPE),
                Arguments.of("empty body", "POST", ""),
                Arguments.of("GET without a body", "GET", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodiesThatAreNotWellFormed")
    void faultStringOfBodyThatIsNotWellFormedIsTheParserMessage(String description, String method, String body)
            throws Exception {
        MockHttpServletResponse response = handle(request(method, ALL_METHODS_PATH, body));

        assertThat(fault(response, "faultstring")).isEqualTo(parserMessage(body == null ? "" : body));
    }

    /**
     * A well-formed envelope nested deeper than CXF's limit is refused before the receiver runs, with the message the
     * JDK's parser reports under that limit; the parser without a limit reads the same bytes, so the depth alone is
     * what the fault answers.
     */
    @ParameterizedTest(name = "elements nested {0} deep")
    @ValueSource(ints = { CXF_MAX_ELEMENT_DEPTH + 1, 3_000, 10_000 })
    void envelopeNestedDeeperThanCxfLimitIsAnsweredWithClientFault(int depth) throws Exception {
        String body = envelopeNestedTo(depth);
        newParser().parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));

        MockHttpServletResponse response = handle(request("POST", ALL_METHODS_PATH, body));

        assertClientFault(response);
        assertThat(fault(response, "faultstring")).isEqualTo(parserMessage(newDepthLimitedParser(), body));
        assertThat(receiverCalls).hasValue(0);
    }

    @Test
    void faultStringOfNonEnvelopeRootNamesTheRootElement() throws Exception {
        MockHttpServletResponse response = handle(request("POST", ALL_METHODS_PATH, NON_ENVELOPE_ROOT));

        assertThat(fault(response, "faultstring")).contains("{urn:example:echo}echo").contains("Envelope");
    }

    @Test
    void contentTypeTheMessageFactoryRejectsIsAnsweredWithClientFault() throws Exception {
        MockHttpServletRequest request = request("POST", ALL_METHODS_PATH, VALID_ENVELOPE);
        request.setContentType("application/json");

        MockHttpServletResponse response = handle(request);

        assertClientFault(response);
        assertThat(fault(response, "faultstring")).as("the message factory's reason").contains("application/json");
        assertThat(receiverCalls).hasValue(0);
    }

    /** A message-less failure for each path by which request message creation reaches the boundary fault. */
    static Stream<Arguments> messageLessFailuresOfRequestMessageCreation() {
        return Stream.of(
                Arguments.of("SoapMessageCreationException", new SoapMessageCreationException(null,
                        new IllegalStateException())),
                Arguments.of("InvalidXmlException", new InvalidXmlException(null, null)));
    }

    /**
     * A failure without a message is answered with CXF's default fault text, never with the exception's type, so the
     * fault discloses nothing about the implementation.
     */
    @ParameterizedTest(name = "{0} without a message")
    @MethodSource("messageLessFailuresOfRequestMessageCreation")
    void messageLessFailureWhileRequestMessageIsCreatedIsAnsweredWithDefaultFaultString(String description,
            RuntimeException failure) throws Exception {
        SaajSoapMessageFactory saaj = new SaajSoapMessageFactory();
        SoapMessageFactory factory = new RequestRejectingMessageFactory(saaj, failure);
        factory.setSoapVersion(SoapVersion.SOAP_11);
        saaj.afterPropertiesSet();
        SoapListenerBoundaryAdapter rejecting = new SoapListenerBoundaryAdapter(factory, List.of(POST_ONLY_PATTERN),
                new SoapEnvelopeStyleInterceptor());
        rejecting.afterPropertiesSet();
        MockHttpServletResponse response = new MockHttpServletResponse();

        rejecting.handle(request("POST", ALL_METHODS_PATH, VALID_ENVELOPE), response, echo);

        assertClientFaultEnvelope(response);
        assertThat(fault(response, "faultstring")).isEqualTo(SoapFaultConfig.DEFAULT_FAULT_STRING)
                .isEqualTo(GENERIC_CXF_FAULT_STRING);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("Exception")
                .doesNotContain(failure.getClass().getName());
        assertThat(receiverCalls).hasValue(0);
    }

    static Stream<Arguments> envelopesTheListenerDispatches() {
        return Stream.of(
                Arguments.of("POST " + ALL_METHODS_PATH, "POST", ALL_METHODS_PATH, VALID_ENVELOPE),
                Arguments.of("POST " + POST_ONLY_PATH, "POST", POST_ONLY_PATH, VALID_ENVELOPE),
                Arguments.of("post " + POST_ONLY_PATH, "post", POST_ONLY_PATH, VALID_ENVELOPE),
                Arguments.of("Post " + POST_ONLY_PATH, "Post", POST_ONLY_PATH, VALID_ENVELOPE),
                Arguments.of("elements nested exactly CXF's limit deep", "POST", ALL_METHODS_PATH,
                        envelopeNestedTo(CXF_MAX_ELEMENT_DEPTH)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("envelopesTheListenerDispatches")
    void envelopeIsDispatchedToReceiverAndItsResponseRestyled(String description, String method, String path,
            String body) throws Exception {
        MockHttpServletResponse response = handle(request(method, path, body));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(receiverCalls).hasValue(1);
        assertThat(response.getContentType()).startsWith("text/xml");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(CXF_STYLE_ECHO_RESPONSE);
    }

    static Stream<Arguments> answersSentThroughTheBoundary() {
        return Stream.of(
                Arguments.of("dispatched response", VALID_ENVELOPE, false, 200),
                Arguments.of("dispatched response whose receiver set a SOAPAction", VALID_ENVELOPE, true, 200),
                Arguments.of("boundary soap:Client fault", MALFORMED_ENVELOPE, false, 500));
    }

    /**
     * SAAJ keeps {@code Accept}, and {@code SOAPAction} once set or written, in every message's MIME headers, and
     * Spring WS copies each MIME header onto the response; CXF wrote neither on a response.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("answersSentThroughTheBoundary")
    void answerCarriesNoRequestHeaderButItsEntityHeaders(String description, String body,
            boolean receiverSetsSoapAction, int status) throws Exception {
        WebServiceMessageReceiver receiver = receiverSetsSoapAction
                ? context -> {
                    echo.receive(context);
                    ((SoapMessage) context.getResponse()).setSoapAction("urn:example:echo");
                }
                : echo;
        MockHttpServletResponse response = new MockHttpServletResponse();

        adapter.handle(request("POST", ALL_METHODS_PATH, body), response, receiver);

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getHeaderNames()).as("response headers")
                .noneMatch(name -> "Accept".equalsIgnoreCase(name) || "SOAPAction".equalsIgnoreCase(name));
        assertThat(response.getContentType()).startsWith("text/xml");
        assertThat(response.getHeader("Content-Length"))
                .isEqualTo(Integer.toString(response.getContentAsByteArray().length));
    }

    private MockHttpServletResponse handle(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        adapter.handle(request, response, echo);
        return response;
    }

    /**
     * A request whose path within the application is {@code path} both as the request URI without the context path
     * and as servlet path plus path info.
     */
    private static MockHttpServletRequest request(String method, String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setContextPath("");
        request.setServletPath("");
        request.setPathInfo(path);
        request.setServerName("localhost");
        request.setServerPort(8080);
        if (body != null) {
            request.setContentType("text/xml; charset=UTF-8");
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return request;
    }

    /**
     * A SOAP 1.1 envelope whose elements nest {@code depth} levels deep, the {@code Envelope} counting as level 1 and
     * its {@code Body} as level 2; {@code depth} is at least 4.
     */
    private static String envelopeNestedTo(int depth) {
        int textLevels = depth - 3;
        return "<soapenv:Envelope xmlns:soapenv=\"" + SOAP11_NS + "\"><soapenv:Body>"
                + "<e:echo xmlns:e=\"urn:example:echo\">" + "<text>".repeat(textLevels) + "hi"
                + "</text>".repeat(textLevels) + "</e:echo></soapenv:Body></soapenv:Envelope>";
    }

    private static void assertClientFault(MockHttpServletResponse response) throws Exception {
        assertClientFaultEnvelope(response);
        assertThat(fault(response, "faultstring")).isNotBlank().isNotEqualTo(GENERIC_CXF_FAULT_STRING);
    }

    /** HTTP 500 and a {@code soap:Client} fault in CXF's envelope form, whatever its fault string. */
    private static void assertClientFaultEnvelope(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith("text/xml");
        assertThat(body).startsWith(CLIENT_FAULT_PREFIX).endsWith(CLIENT_FAULT_SUFFIX)
                .doesNotContain("SOAP-ENV").doesNotContain("<?xml").doesNotContain("Header")
                .doesNotContain("xml:lang");
        assertThat(fault(response, "faultcode")).isEqualTo("soap:Client");
    }

    /** The text of the one unqualified {@code localName} child of the response's {@code soap:Body/soap:Fault}. */
    private static String fault(MockHttpServletResponse response, String localName) throws Exception {
        Document document = newParser().parse(new ByteArrayInputStream(response.getContentAsByteArray()));
        Element envelope = document.getDocumentElement();
        assertThat(envelope.getNamespaceURI()).isEqualTo(SOAP11_NS);
        assertThat(envelope.getLocalName()).isEqualTo("Envelope");
        Element body = onlyChild(envelope, SOAP11_NS, "Body");
        Element fault = onlyChild(body, SOAP11_NS, "Fault");
        return onlyChild(fault, null, localName).getTextContent();
    }

    private static Element onlyChild(Element parent, String namespace, String localName) {
        List<Element> matches = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            boolean sameNamespace = namespace == null
                    ? child.getNamespaceURI() == null
                    : namespace.equals(child.getNamespaceURI());
            if (child.getNodeType() == Node.ELEMENT_NODE && localName.equals(child.getLocalName()) && sameNamespace) {
                matches.add((Element) child);
            }
        }
        assertThat(matches).as("%s children of %s", localName, parent.getNodeName()).hasSize(1);
        return matches.get(0);
    }

    /** What the JDK's built-in namespace-aware parser reports for {@code body}, which is not well-formed XML. */
    private static String parserMessage(String body) throws Exception {
        return parserMessage(newParser(), body);
    }

    /** What {@code parser} reports for {@code body}, which it refuses to read. */
    private static String parserMessage(DocumentBuilder parser, String body) {
        SAXParseException failure = catchThrowableOfType(
                () -> parser.parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))),
                SAXParseException.class);
        assertThat(failure).as("parse failure of the fixture body").isNotNull();
        assertThat(failure.getMessage()).isNotBlank();
        return failure.getMessage();
    }

    /** The JDK's built-in parser, namespace-aware, reporting parse errors only by throwing them. */
    private static DocumentBuilder newParser() throws Exception {
        return newParser(DocumentBuilderFactory.newDefaultInstance());
    }

    /** The parser of {@link #newParser()}, refusing elements nested deeper than {@link #CXF_MAX_ELEMENT_DEPTH}. */
    private static DocumentBuilder newDepthLimitedParser() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setAttribute("jdk.xml.maxElementDepth", String.valueOf(CXF_MAX_ELEMENT_DEPTH));
        return newParser(factory);
    }

    /** A parser from {@code factory}, namespace-aware, reporting parse errors only by throwing them. */
    private static DocumentBuilder newParser(DocumentBuilderFactory factory) throws Exception {
        factory.setNamespaceAware(true);
        DocumentBuilder parser = factory.newDocumentBuilder();
        parser.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) {
                // Warnings leave the document well-formed; the assertions concern errors only.
            }

            @Override
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }
        });
        return parser;
    }

    /**
     * A SOAP message factory that creates outgoing messages through SAAJ but rejects every request message with the
     * given failure, as a factory that cannot say why it rejects a request would.
     */
    private static final class RequestRejectingMessageFactory implements SoapMessageFactory {

        private final SaajSoapMessageFactory delegate;
        private final RuntimeException failure;

        RequestRejectingMessageFactory(SaajSoapMessageFactory delegate, RuntimeException failure) {
            this.delegate = delegate;
            this.failure = failure;
        }

        @Override
        public void setSoapVersion(SoapVersion version) {
            delegate.setSoapVersion(version);
        }

        @Override
        public SoapMessage createWebServiceMessage() {
            return delegate.createWebServiceMessage();
        }

        @Override
        public SoapMessage createWebServiceMessage(InputStream inputStream) {
            throw failure;
        }
    }
}
