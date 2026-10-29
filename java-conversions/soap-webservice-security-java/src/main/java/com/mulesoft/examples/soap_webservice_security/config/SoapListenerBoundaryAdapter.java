package com.mulesoft.examples.soap_webservice_security.config;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.Assert;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.ws.InvalidXmlException;
import org.springframework.ws.WebServiceMessageFactory;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.SoapMessageCreationException;
import org.springframework.ws.soap.SoapMessageFactory;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.transport.TransportConstants;
import org.springframework.ws.transport.WebServiceMessageReceiver;
import org.springframework.ws.transport.http.HttpServletConnection;
import org.springframework.ws.transport.http.WebServiceMessageReceiverHandlerAdapter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Answers what the original SOAP listener answered before any endpoint was resolved, in place of Spring WS's bare
 * status codes.
 *
 * <ul>
 *   <li>A non-POST request to a path matching one of the POST-only patterns (the method {@code POST} in any letter
 *       case is a POST, as Mule 3.8's {@code MethodRequestMatcher} compared methods): status 405, Content-Type
 *       {@code text/plain} with no charset parameter, body {@code Method not allowed for endpoint: <path>} in UTF-8,
 *       and no {@code Allow} header, the same bytes as every other listener's method 405. The body is written
 *       through the output stream, never the writer, so the container appends no charset.</li>
 *   <li>Any other request, whatever its method, is read as a SOAP message. A listener that accepts every method
 *       therefore reads a {@code GET} without a metadata query as a message; metadata queries are answered before
 *       the request reaches this adapter.</li>
 *   <li>A body that cannot be read as a SOAP 1.1 envelope (malformed XML, an empty body, a body nested deeper than 100
 *       elements as CXF's default {@code maxElementDepth} allowed, or a root other than
 *       {@code {http://schemas.xmlsoap.org/soap/envelope/}Envelope}) is answered with HTTP 500 and a SOAP 1.1 fault,
 *       {@code faultcode} {@code soap:Client} and {@code faultstring} the parser's message, written through the same
 *       connection type and envelope style as every dispatched fault. The same fault answers an
 *       {@link InvalidXmlException} or {@link SoapMessageCreationException} raised while the request message is
 *       created, for example for a Content-Type the message factory rejects.</li>
 *   <li>Every other envelope is handed to the message receiver unchanged; the response it leaves, fault or not, is
 *       restyled by {@link SoapEnvelopeStyleInterceptor#restyle(SoapMessage)} before it is written.</li>
 *   <li>No response or fault this adapter sends carries {@code Accept} or {@code SOAPAction}, which CXF never wrote
 *       on a response: SAAJ keeps those request headers in the MIME headers of every message it creates or writes,
 *       and Spring WS copies every MIME header onto the response. Every other header, {@code Content-Type} and
 *       {@code Content-Length} among them, passes unchanged.</li>
 * </ul>
 *
 * <p>The path a pattern is matched against is the request URI without the context path ({@code /} when nothing is
 * left), independent of the servlet mapping, matched by {@link AntPathMatcher}.
 *
 * <p>The consuming web-service configuration creates this adapter as the bean {@code messageReceiverHandlerAdapter},
 * which {@code MessageDispatcherServlet} looks up and uses for every SOAP request; this class is deliberately not a
 * Spring component. The servlet replaces the message factory given here with its own and then calls
 * {@link #afterPropertiesSet()}, so the factory is always read through {@link #getMessageFactory()} at request time.
 * The patterns are copied once and the one {@link AntPathMatcher} is safe for concurrent use, so a single instance
 * serves all requests concurrently. Each boundary answer is logged at DEBUG with its kind only, never the caller's
 * path or body text.
 */
public class SoapListenerBoundaryAdapter extends WebServiceMessageReceiverHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(SoapListenerBoundaryAdapter.class);

    private static final String METHOD_NOT_ALLOWED_PREFIX = "Method not allowed for endpoint: ";
    private static final String TEXT_CONTENT_TYPE = "text/plain";
    private static final String ROOT_PATH = "/";

    private static final String ENVELOPE = "Envelope";
    private static final String ENVELOPE_QNAME = "{" + SoapEnvelopeStyleInterceptor.SOAP11_NS + "}" + ENVELOPE;

    /**
     * The deepest element nesting a request body may reach, the root element counting as depth 1: CXF's default
     * {@code org.apache.cxf.stax.maxElementDepth}, which the original CXF listeners applied to every request. A deeper
     * body fails the parse here, so no endpoint code ever recurses over it.
     */
    private static final int MAX_ELEMENT_DEPTH = 100;

    /** The JDK parser's element-depth limit, set on the factory so that it outranks the same-named system property. */
    private static final String MAX_ELEMENT_DEPTH_PROPERTY = "jdk.xml.maxElementDepth";

    private final List<String> postOnlyPatterns;
    private final SoapEnvelopeStyleInterceptor styler;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * Creates the adapter.
     *
     * @param messageFactory   the SOAP message factory; {@code MessageDispatcherServlet} replaces it with its own
     * @param postOnlyPatterns Ant-style patterns of the request paths, within the application, whose listener accepts
     *                         POST only; {@code null} or empty means every listener accepts every method, and
     *                         {@code null} elements are ignored
     * @param styler           the envelope styler applied to every response and fault this adapter sends
     */
    public SoapListenerBoundaryAdapter(WebServiceMessageFactory messageFactory, Collection<String> postOnlyPatterns,
            SoapEnvelopeStyleInterceptor styler) {
        Assert.notNull(messageFactory, "messageFactory must not be null");
        Assert.notNull(styler, "styler must not be null");
        setMessageFactory(messageFactory);
        this.postOnlyPatterns = postOnlyPatterns == null ? List.of()
                : postOnlyPatterns.stream().filter(Objects::nonNull).toList();
        this.styler = styler;
    }

    /**
     * Also requires a {@link SoapMessageFactory}, because the boundary faults are SOAP messages; the servlet calls
     * this after it has set its own factory.
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        super.afterPropertiesSet();
        Assert.isInstanceOf(SoapMessageFactory.class, getMessageFactory(),
                "The SOAP listener boundary needs a SoapMessageFactory");
    }

    /**
     * Reads a request whose method is {@code POST} in any letter case, as Mule 3.8's {@code MethodRequestMatcher}
     * compared methods, as a SOAP message, and hands every other method to {@link #handleNonPostMethod}.
     */
    @Override
    public ModelAndView handle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (HttpMethod.POST.name().equalsIgnoreCase(request.getMethod())) {
            processAsMessage(request, response, handler);
        }
        else {
            handleNonPostMethod(request, response, handler);
        }
        return null;
    }

    @Override
    protected void handleNonPostMethod(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = pathWithinApplication(request);
        String pattern = matchingPostOnlyPattern(path);
        if (pattern == null) {
            processAsMessage(request, response, handler);
            return;
        }
        log.debug("{} request to the POST-only SOAP listener {}; answering 405", request.getMethod(), pattern);
        writeMethodNotAllowed(response, path);
    }

    /**
     * Answers the {@link InvalidXmlException} of message creation with the {@code soap:Client} fault. Its
     * {@code faultstring} is the parser's message, the exception's most specific cause, falling back to the
     * exception's own message and then to {@link SoapFaultConfig#DEFAULT_FAULT_STRING}: the listener boundary reports
     * what the parser said, and Spring's own message ("Could not parse XML") would hide it.
     */
    @Override
    protected void handleInvalidXmlException(HttpServletRequest request, HttpServletResponse response, Object handler,
            InvalidXmlException ex) throws Exception {
        log.debug("SOAP request message could not be parsed by the message factory; answering soap:Client");
        sendClientFault(request, response, mostSpecificMessage(ex));
    }

    /**
     * Reads the request as a SOAP message: the body is buffered and checked once, a body that is not a SOAP 1.1
     * envelope is answered with the {@code soap:Client} fault, and any other body is replayed to the receiver.
     */
    private void processAsMessage(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        String problem = envelopeProblem(body);
        if (problem != null) {
            log.debug("{} request body of {} byte(s) is not a SOAP 1.1 envelope; answering soap:Client",
                    request.getMethod(), body.length);
            sendClientFault(request, response, problem);
            return;
        }
        HttpServletRequest replay = new BufferedBodyRequest(request, body);
        WebServiceMessageReceiver receiver = new RestylingReceiver((WebServiceMessageReceiver) handler, styler);
        try {
            handleConnection(new BoundaryConnection(replay, response), receiver);
        }
        catch (InvalidXmlException ex) {
            handleInvalidXmlException(replay, response, handler, ex);
        }
        catch (SoapMessageCreationException ex) {
            log.debug("SOAP request message could not be created by the message factory; answering soap:Client");
            sendClientFault(replay, response, mostSpecificMessage(ex));
        }
    }

    /**
     * Writes a SOAP 1.1 {@code soap:Client} fault carrying {@code faultString}: HTTP 500, and the Content-Type and
     * envelope style of every dispatched fault.
     */
    private void sendClientFault(HttpServletRequest request, HttpServletResponse response, String faultString)
            throws IOException {
        SoapMessage fault = (SoapMessage) getMessageFactory().createWebServiceMessage();
        fault.getSoapBody().addClientOrSenderFault(faultString, null);
        styler.restyle(fault);
        HttpServletConnection connection = new BoundaryConnection(request, response);
        try {
            connection.setFaultCode(SoapVersion.SOAP_11.getClientOrSenderFaultName());
            connection.send(fault);
        }
        finally {
            connection.close();
        }
    }

    /**
     * Writes the listener's 405 for a method it does not accept, and completes the response body. The UTF-8 bytes go
     * through the output stream, never the writer, so the Content-Type stays {@code text/plain} without a charset.
     */
    private static void writeMethodNotAllowed(HttpServletResponse response, String path) throws IOException {
        byte[] body = (METHOD_NOT_ALLOWED_PREFIX + path).getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        response.setContentType(TEXT_CONTENT_TYPE);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }

    /** The first POST-only pattern matching {@code path}, or {@code null} when the listener accepts every method. */
    private String matchingPostOnlyPattern(String path) {
        for (String pattern : postOnlyPatterns) {
            if (pathMatcher.match(pattern, path)) {
                return pattern;
            }
        }
        return null;
    }

    /**
     * Why {@code body} cannot be read as a SOAP 1.1 envelope, or {@code null} when it can: the parser's message for
     * a body that is not well-formed XML or nests elements deeper than {@link #MAX_ELEMENT_DEPTH}, otherwise a fixed
     * text naming the root element.
     */
    private static String envelopeProblem(byte[] body) {
        Document document;
        try {
            document = newSecureBuilder().parse(new ByteArrayInputStream(body));
        }
        catch (SAXException | IOException ex) {
            return describe(ex);
        }
        Element root = document.getDocumentElement();
        if (SoapEnvelopeStyleInterceptor.SOAP11_NS.equals(root.getNamespaceURI())
                && ENVELOPE.equals(root.getLocalName())) {
            return null;
        }
        return "Message root element " + qualifiedName(root) + " is not " + ENVELOPE_QNAME;
    }

    /**
     * A namespace-aware parser with DOCTYPE declarations, external entities, external DTDs and XInclude disabled, and
     * element nesting limited to {@link #MAX_ELEMENT_DEPTH}. The JDK's built-in implementation is used, so the parser
     * messages a caller receives do not depend on which XML library the classpath carries; a fresh factory per request
     * keeps concurrent requests apart.
     */
    private static DocumentBuilder newSecureBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(MAX_ELEMENT_DEPTH_PROPERTY, String.valueOf(MAX_ELEMENT_DEPTH));
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(ThrowingErrorHandler.INSTANCE);
            return builder;
        }
        catch (ParserConfigurationException ex) {
            throw new IllegalStateException("Cannot configure a secure XML parser", ex);
        }
    }

    /** {@code {namespace}local}, or {@code local} for an element in no namespace. */
    private static String qualifiedName(Element element) {
        String localName = element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
        String namespace = element.getNamespaceURI();
        return StringUtils.hasLength(namespace) ? "{" + namespace + "}" + localName : localName;
    }

    /**
     * The exception's message, or {@link SoapFaultConfig#DEFAULT_FAULT_STRING}, CXF's text for a fault without one,
     * when it carries none: a fault string is never empty and never names an exception type or carries a stack trace.
     */
    private static String describe(Throwable ex) {
        return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : SoapFaultConfig.DEFAULT_FAULT_STRING;
    }

    /**
     * The message of the most specific cause, falling back to the exception's own message and then to
     * {@link SoapFaultConfig#DEFAULT_FAULT_STRING}; no exception type or stack trace reaches the fault.
     */
    private static String mostSpecificMessage(Throwable ex) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
        return StringUtils.hasText(cause.getMessage()) ? cause.getMessage() : describe(ex);
    }

    /** The request URI without the context path, {@code /} when nothing is left. */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            uri = "";
        }
        String contextPath = request.getContextPath();
        if (StringUtils.hasLength(contextPath) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri.isEmpty() ? ROOT_PATH : uri;
    }

    /**
     * Runs the delegate and restyles the response it leaves, so every response and fault the dispatcher produces,
     * those of its exception resolvers included, is written in the listener's envelope style.
     */
    private static final class RestylingReceiver implements WebServiceMessageReceiver {

        private final WebServiceMessageReceiver delegate;
        private final SoapEnvelopeStyleInterceptor styler;

        RestylingReceiver(WebServiceMessageReceiver delegate, SoapEnvelopeStyleInterceptor styler) {
            this.delegate = delegate;
            this.styler = styler;
        }

        @Override
        public void receive(MessageContext messageContext) throws Exception {
            delegate.receive(messageContext);
            if (messageContext.hasResponse() && messageContext.getResponse() instanceof SoapMessage response) {
                styler.restyle(response);
            }
        }
    }

    /**
     * {@link HttpServletConnection}'s constructor is protected; this subclass makes it available here. It also keeps
     * the request headers SAAJ holds in every message's MIME headers off the response: {@code Accept}, which SAAJ
     * sets on every message it creates, and {@code SOAPAction}, which it sets after every write. CXF wrote neither on
     * a response.
     */
    private static final class BoundaryConnection extends HttpServletConnection {

        private static final List<String> REQUEST_ONLY_HEADERS =
                List.of(TransportConstants.HEADER_ACCEPT, TransportConstants.HEADER_SOAP_ACTION);

        BoundaryConnection(HttpServletRequest request, HttpServletResponse response) {
            super(request, response);
        }

        @Override
        public void addResponseHeader(String name, String value) throws IOException {
            for (String requestOnly : REQUEST_ONLY_HEADERS) {
                if (requestOnly.equalsIgnoreCase(name)) {
                    return;
                }
            }
            super.addResponseHeader(name, value);
        }
    }

    /** The request with its already consumed body replayed from the buffered bytes, as often as it is read. */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ByteArrayServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            InputStreamReader reader = encoding == null
                    ? new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8)
                    : new InputStreamReader(new ByteArrayInputStream(body), encoding);
            return new BufferedReader(reader);
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    /** A blocking servlet input stream over bytes held in memory; it is always ready and never needs a listener. */
    private static final class ByteArrayServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream in;

        ByteArrayServletInputStream(byte[] bytes) {
            this.in = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() {
            return in.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return in.read(buffer, offset, length);
        }

        @Override
        public int available() {
            return in.available();
        }

        @Override
        public boolean isFinished() {
            return in.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("The buffered SOAP request body is read synchronously only");
        }
    }

    /**
     * Turns every parse error into the exception that becomes the fault string, so the parser prints nothing to
     * standard error. Warnings leave the document well-formed and are dropped.
     */
    private static final class ThrowingErrorHandler implements ErrorHandler {

        static final ThrowingErrorHandler INSTANCE = new ThrowingErrorHandler();

        @Override
        public void warning(SAXParseException exception) {
            // A warning does not make the body unreadable; the message factory accepts the same document.
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }
}
