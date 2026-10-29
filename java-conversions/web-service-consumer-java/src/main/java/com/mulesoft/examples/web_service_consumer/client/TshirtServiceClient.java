/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;

import org.springframework.stereotype.Component;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.client.WebServiceFaultException;
import org.springframework.ws.client.core.FaultMessageResolver;
import org.springframework.ws.client.core.WebServiceMessageCallback;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.WebServiceConnection;
import org.springframework.ws.transport.http.HttpUrlConnection;
import org.springframework.ws.transport.http.HttpUrlConnectionMessageSender;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.mulesoft.examples.web_service_consumer.config.TshirtServiceProperties;

/**
 * SOAP 1.1 client of {@code TshirtService}/{@code TshirtServicePort} ({@code tshirt.wsdl}), the one outbound
 * system of the example.
 *
 * <p>Replaces, from {@code web-service-consumer/src/main/app/tshirt-service-consumer.xml}:
 * <ul>
 *   <li>line 3, {@code <ws:consumer-config serviceAddress="http://tshirt-service.cloudhub.io"/>}: the
 *       endpoint is {@code tshirt.service-address} and both timeouts are {@code tshirt.connect-timeout} and
 *       {@code tshirt.read-timeout} (10 s, the Mule default response timeout);</li>
 *   <li>lines 17-23, the {@code soap.header} DataWeave script
 *       {@code ns0#AuthenticationHeader : { apiKey: ... }}: the header is added to every
 *       {@code OrderTshirt} request with the key configured as {@code tshirt.api-key}. The key has no default,
 *       so construction accepts a missing or blank key, and {@code OrderTshirt} then fails with an
 *       {@link IllegalStateException} naming the key before anything is sent; {@code ListInventory} sends
 *       no header and never needs the key;</li>
 *   <li>line 25, {@code <ws:consumer operation="OrderTshirt"/>}, and line 38,
 *       {@code <ws:consumer operation="ListInventory"/>}.</li>
 * </ul>
 *
 * <p>Payloads are DOM documents: the body of a request is built by the request mapper (or, for
 * {@code ListInventory} without a body, here), and each method returns the element child of the response
 * SOAP Body as its own {@link Document}, or {@code null} when the service answers with no response or an
 * empty Body. Response SOAP headers ({@code APIUsageInformation}) are not returned: the original flows read
 * only the body payload after the consumer.
 *
 * <p>Each request is sent as SAAJ serializes it, except that supplementary characters are written as UTF-8
 * bytes rather than character references, as the original's CXF stack wrote them
 * ({@code encodeSupplementaryCharacterReferences}).
 *
 * <p>Failures reach the caller as the Spring WS runtime exceptions the template raises, unwrapped and never
 * retried: {@code SoapFaultClientException} for a SOAP fault, whose message is the fault string;
 * {@code WebServiceFaultException} with the fixed message
 * {@code The T-shirt service returned a SOAP fault without a readable fault string} for a SOAP fault whose fault
 * string is missing or cannot be read ({@code FaultStringCheckingResolver});
 * {@code WebServiceTransportException} for an HTTP error without a fault; {@code WebServiceIOException} for a
 * timeout or connection failure. The original flows declare no exception strategy, so each one becomes the
 * platform-default 500.
 *
 * <p>Thread-safe: every field is final and configured once, the template is safe for concurrent use, and
 * the non-thread-safe JAXP builders and transformers are created per call.
 */
@Component
public class TshirtServiceClient {

    /** Target namespace of {@code tshirt.wsdl}; its schema has no {@code elementFormDefault}, so local elements are unqualified. */
    public static final String NAMESPACE = "http://mulesoft.org/tshirt-service";

    /** {@code soapAction} of the {@code OrderTshirt} binding operation ({@code tshirt.wsdl} line 177). */
    public static final String ORDER_TSHIRT_ACTION = "http://mulesoft.org/tshirt-service/order-tshirt";

    /** {@code soapAction} of the {@code ListInventory} binding operation ({@code tshirt.wsdl} line 195). */
    public static final String LIST_INVENTORY_ACTION = "http://mulesoft.org/tshirt-service/list-inventory";

    /** Prefix the original DataWeave scripts declared ({@code %namespace ns0 http://mulesoft.org/tshirt-service}). */
    private static final String PREFIX = "ns0";

    private static final String PREFIX_DECLARATION = XMLConstants.XMLNS_ATTRIBUTE + ":" + PREFIX;

    /** Mule's default response timeout, used when a timeout is not configured. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Shortest usable timeout: {@link HttpURLConnection} takes whole milliseconds and waits forever on 0, which a
     * zero or sub-millisecond {@link Duration} truncates to.
     */
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(1);

    /**
     * Shortest timeout too long to use: its whole milliseconds no longer fit the {@code int}
     * {@link HttpURLConnection} takes.
     */
    private static final Duration OVERFLOWING_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE + 1L);

    /** Message of the failure {@link FaultStringCheckingResolver} raises for a fault without a readable string. */
    private static final String INCOMPLETE_FAULT_MESSAGE =
            "The T-shirt service returned a SOAP fault without a readable fault string";

    /**
     * Opening and closing delimiters, in pairs, of the markup whose content the serializer writes verbatim:
     * comments, CDATA sections and processing instructions ({@link #encodeSupplementaryCharacterReferences}).
     */
    private static final byte[][] VERBATIM_DELIMITERS = {
            ascii("<!--"), ascii("-->"),
            ascii("<![CDATA["), ascii("]]>"),
            ascii("<?"), ascii("?>"),
    };

    private final WebServiceTemplate template;

    private final String apiKey;

    private final DocumentBuilderFactory documentBuilderFactory;

    private final TransformerFactory transformerFactory;

    /**
     * Builds the SOAP 1.1 template for the configured service address, timeouts and API key.
     *
     * @param properties the {@code tshirt.*} settings; a timeout that is not configured is 10 s
     * @throws IllegalArgumentException when {@code tshirt.connect-timeout} or {@code tshirt.read-timeout} is not
     *         between 1 ms and {@link Integer#MAX_VALUE} ms in whole milliseconds; the message names the key and
     *         the value, and nothing is built
     */
    public TshirtServiceClient(TshirtServiceProperties properties) {
        Duration connectTimeout = usableTimeout("tshirt.connect-timeout", properties.getConnectTimeout());
        Duration readTimeout = usableTimeout("tshirt.read-timeout", properties.getReadTimeout());

        SaajSoapMessageFactory messageFactory = new SaajSoapMessageFactory();
        messageFactory.setSoapVersion(SoapVersion.SOAP_11);
        messageFactory.afterPropertiesSet();

        HttpUrlConnectionMessageSender messageSender = new SingleAttemptMessageSender();
        messageSender.setConnectionTimeout(connectTimeout);
        messageSender.setReadTimeout(readTimeout);
        // Requests do not advertise gzip: Spring WS decides whether a response exists from the raw, still
        // compressed bytes, so a gzip-encoded empty body would be read as an envelope and fail to parse
        // instead of yielding no response.
        messageSender.setAcceptGzipEncoding(false);

        WebServiceTemplate webServiceTemplate = new WebServiceTemplate(messageFactory);
        webServiceTemplate.setMessageSender(messageSender);
        webServiceTemplate.setDefaultUri(properties.getServiceAddress());
        webServiceTemplate.setFaultMessageResolver(new FaultStringCheckingResolver());
        this.template = webServiceTemplate;

        this.apiKey = properties.getApiKey() == null ? "" : properties.getApiKey();
        this.documentBuilderFactory = secureDocumentBuilderFactory();
        this.transformerFactory = secureTransformerFactory();
    }

    /**
     * Calls operation {@code OrderTshirt} with the {@code AuthenticationHeader} SOAP header.
     *
     * @param request document whose root is the {@code OrderTshirt} body element
     * @return the response Body child ({@code OrderTshirtResponse}) as a document, or {@code null} when the
     *         service returns no response or an empty Body
     * @throws IllegalArgumentException when {@code request} is {@code null}
     * @throws IllegalStateException when {@code tshirt.api-key} is missing or blank; raised before any request
     *         is sent, so no empty {@code apiKey} reaches the service
     */
    public Document orderTshirt(Document request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (apiKey.isBlank()) {
            throw new IllegalStateException("T-shirt service API key (tshirt.api-key) is not configured");
        }
        Element header = authenticationHeader();
        return send(request, message -> {
            SoapMessage soapMessage = (SoapMessage) message;
            soapMessage.setSoapAction(ORDER_TSHIRT_ACTION);
            newTransformer().transform(new DOMSource(header), soapMessage.getSoapHeader().getResult());
        });
    }

    /**
     * Calls operation {@code ListInventory}. The WSDL binds no input header to this operation and the
     * original flow set none, so the request carries no {@code AuthenticationHeader}.
     *
     * @param requestOrNull document whose root is sent as the body element unchanged, or {@code null} to send
     *        the empty {@code ns0:ListInventory} element the WSDL defines (an empty sequence), which is what
     *        the original consumer sent for a request without a payload
     * @return the response Body child ({@code ListInventoryResponse}) as a document, or {@code null} when the
     *         service returns no response or an empty Body
     */
    public Document listInventory(Document requestOrNull) {
        Document request = requestOrNull == null ? emptyListInventory() : requestOrNull;
        return send(request, message -> ((SoapMessage) message).setSoapAction(LIST_INVENTORY_ACTION));
    }

    private Document send(Document request, WebServiceMessageCallback callback) {
        DOMResult result = new DOMResult();
        boolean received = template.sendSourceAndReceiveToResult(
                new DOMSource(request.getDocumentElement()), callback, result);
        if (!received) {
            return null;
        }
        // An envelope with an empty Body counts as received, but nothing is transformed into the result.
        Node node = result.getNode();
        if (node instanceof Document document && document.getDocumentElement() != null) {
            return document;
        }
        return null;
    }

    /** {@code <ns0:AuthenticationHeader xmlns:ns0="..."><apiKey>key</apiKey></ns0:AuthenticationHeader>}. */
    private Element authenticationHeader() {
        Document document = newDocument();
        Element header = document.createElementNS(NAMESPACE, PREFIX + ":AuthenticationHeader");
        header.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, PREFIX_DECLARATION, NAMESPACE);
        Element key = document.createElementNS(null, "apiKey");
        key.setTextContent(apiKey);
        header.appendChild(key);
        document.appendChild(header);
        return header;
    }

    /** {@code <ns0:ListInventory xmlns:ns0="..."/>}. */
    private Document emptyListInventory() {
        Document document = newDocument();
        Element listInventory = document.createElementNS(NAMESPACE, PREFIX + ":ListInventory");
        listInventory.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, PREFIX_DECLARATION, NAMESPACE);
        document.appendChild(listInventory);
        return document;
    }

    private Document newDocument() {
        synchronized (documentBuilderFactory) {
            try {
                return documentBuilderFactory.newDocumentBuilder().newDocument();
            }
            catch (ParserConfigurationException ex) {
                throw new IllegalStateException("Cannot create an XML document builder", ex);
            }
        }
    }

    private Transformer newTransformer() throws TransformerConfigurationException {
        synchronized (transformerFactory) {
            return transformerFactory.newTransformer();
        }
    }

    /**
     * {@link FaultMessageResolver} that raises the fault of a SOAP fault response, as Spring WS's
     * {@link org.springframework.ws.soap.client.core.SoapFaultMessageResolver} does, except that a fault whose
     * fault string cannot be read fails with a fixed message.
     *
     * <p>A fault whose fault string can be read raises the {@link SoapFaultClientException} that resolver raises,
     * so its message is the fault string the service sent. SAAJ reads a SOAP 1.1 {@code faultstring} without
     * checking that the element exists, so a {@code Fault} without one, such as an empty {@code <soap:Fault/>}
     * or one carrying only a {@code faultcode}, makes the exception's constructor throw a
     * {@link NullPointerException} whose JVM-generated message names a SAAJ interface and private field. That
     * failure, and any other runtime exception raised while the fault is read, is replaced by a
     * {@link WebServiceFaultException} with {@link #INCOMPLETE_FAULT_MESSAGE}, which names no Java type, member
     * or address, and keeps the SAAJ failure as its cause. Either way the call fails: a fault is never taken as
     * a response.
     */
    private static final class FaultStringCheckingResolver implements FaultMessageResolver {

        @Override
        public void resolveFault(WebServiceMessage message) {
            SoapFaultClientException fault;
            try {
                fault = new SoapFaultClientException((SoapMessage) message);
            }
            catch (RuntimeException unreadable) {
                WebServiceFaultException incomplete = new WebServiceFaultException(INCOMPLETE_FAULT_MESSAGE);
                incomplete.initCause(unreadable);
                throw incomplete;
            }
            throw fault;
        }
    }

    /**
     * {@link HttpUrlConnectionMessageSender} whose connections send every request exactly once.
     *
     * <p>A default {@link HttpURLConnection} buffers the POST body and, when the connection fails while the
     * response is read (a reset, an unexpected end of stream), silently re-sends the whole request once
     * ({@code sun.net.http.retryPost}, on by default). For {@code OrderTshirt} that would place the order twice
     * after the service had already received it, and the flows declare no retry of any kind. In fixed-length
     * streaming mode the JDK never re-sends, so each connection buffers the envelope Spring WS writes and then
     * streams it with the exact length of the bytes it sends; apart from supplementary characters, which
     * {@link #encodeSupplementaryCharacterReferences(byte[])} writes as UTF-8, the wire form is unchanged.
     */
    private static final class SingleAttemptMessageSender extends HttpUrlConnectionMessageSender {

        @Override
        public WebServiceConnection createConnection(URI uri) throws IOException {
            // The parent opens and prepares the HttpURLConnection without connecting it.
            HttpUrlConnection prepared = (HttpUrlConnection) super.createConnection(uri);
            return new FixedLengthHttpUrlConnection(prepared.getConnection());
        }
    }

    /**
     * Collects the request body, writes its supplementary characters as UTF-8
     * ({@link #encodeSupplementaryCharacterReferences(byte[])}), then sends it in fixed-length streaming mode
     * ({@link SingleAttemptMessageSender}).
     */
    private static final class FixedLengthHttpUrlConnection extends HttpUrlConnection {

        private final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();

        private FixedLengthHttpUrlConnection(HttpURLConnection connection) {
            super(connection);
        }

        @Override
        protected OutputStream getRequestOutputStream() {
            return requestBody;
        }

        @Override
        protected void onSendAfterWrite(WebServiceMessage message) throws IOException {
            byte[] body = encodeSupplementaryCharacterReferences(requestBody.toByteArray());
            HttpURLConnection connection = getConnection();
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            super.onSendAfterWrite(message);
        }
    }

    /**
     * Rewrites each numeric character reference to a supplementary character (U+10000 to U+10FFFF) in a
     * serialized UTF-8 envelope as that character's UTF-8 bytes, and leaves every other byte as it is.
     *
     * <p>SAAJ writes the envelope through the JDK's built-in XML serializer, which writes every character outside
     * the Basic Multilingual Plane as a decimal reference ({@code &#128512;} for U+1F600), in text and attribute
     * values alike, although UTF-8 encodes it; the CXF/Woodstox stack of the original {@code ws:consumer} wrote
     * such characters as UTF-8 bytes. Outside comments, CDATA sections and processing instructions the serializer
     * writes every {@code &} of the content as {@code &amp;}, so each {@code &#} there starts a reference it
     * generated. Inside them it writes the content verbatim, so a {@code &#} there is literal text and is kept.
     * References below U+10000, such as {@code &#13;} for a carriage return, are kept too. UTF-8 encodes no ASCII
     * byte inside a multi-byte sequence, so the markup is found by scanning bytes.
     *
     * <p>Package-private for its tests.
     *
     * @param xml the serialized envelope, UTF-8 encoded
     * @return {@code xml} itself when it holds no such reference, otherwise the rewritten bytes
     */
    static byte[] encodeSupplementaryCharacterReferences(byte[] xml) {
        ByteArrayOutputStream rewritten = null;
        int copied = 0;
        int i = 0;
        while (i < xml.length) {
            if (xml[i] == '<') {
                i = endOfVerbatimRegion(xml, i);
            }
            else if (xml[i] == '&' && i + 1 < xml.length && xml[i + 1] == '#') {
                // &#digits; or &#xhex; (XML allows only a lower-case x); the value is capped so it cannot overflow.
                boolean hex = i + 2 < xml.length && xml[i + 2] == 'x';
                int radix = hex ? 16 : 10;
                int digits = hex ? i + 3 : i + 2;
                int end = digits;
                int codePoint = 0;
                while (end < xml.length && Character.digit(xml[end], radix) >= 0) {
                    codePoint = Math.min(codePoint * radix + Character.digit(xml[end], radix),
                            Character.MAX_CODE_POINT + 1);
                    end++;
                }
                if (end > digits && end < xml.length && xml[end] == ';'
                        && codePoint >= Character.MIN_SUPPLEMENTARY_CODE_POINT
                        && codePoint <= Character.MAX_CODE_POINT) {
                    if (rewritten == null) {
                        rewritten = new ByteArrayOutputStream(xml.length);
                    }
                    rewritten.write(xml, copied, i - copied);
                    rewritten.writeBytes(Character.toString(codePoint).getBytes(StandardCharsets.UTF_8));
                    copied = end + 1;
                }
                i = end;
            }
            else {
                i++;
            }
        }
        if (rewritten == null) {
            return xml;
        }
        rewritten.write(xml, copied, xml.length - copied);
        return rewritten.toByteArray();
    }

    /**
     * Index just after the comment, CDATA section or processing instruction that starts at {@code start}, or the
     * length of {@code xml} when it is not closed; {@code start + 1} when none starts there.
     */
    private static int endOfVerbatimRegion(byte[] xml, int start) {
        for (int d = 0; d < VERBATIM_DELIMITERS.length; d += 2) {
            byte[] open = VERBATIM_DELIMITERS[d];
            byte[] close = VERBATIM_DELIMITERS[d + 1];
            if (startsWith(xml, start, open)) {
                for (int i = start + open.length; i <= xml.length - close.length; i++) {
                    if (startsWith(xml, i, close)) {
                        return i + close.length;
                    }
                }
                return xml.length;
            }
        }
        return start + 1;
    }

    private static boolean startsWith(byte[] xml, int offset, byte[] prefix) {
        return offset + prefix.length <= xml.length
                && Arrays.equals(xml, offset, offset + prefix.length, prefix, 0, prefix.length);
    }

    private static byte[] ascii(String delimiter) {
        return delimiter.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * The configured timeout, or {@link #DEFAULT_TIMEOUT} when it is not configured, checked against what
     * {@link HttpURLConnection} honours. Spring WS hands it {@link Duration#toMillis()} as an {@code int} on every
     * request: 0, which a zero or sub-millisecond value truncates to, waits forever, and a negative or
     * {@code int}-overflowing value fails each call. Such a value is rejected here instead. The bounds are compared
     * as durations, because {@code toMillis()} itself overflows for the longest ones.
     *
     * @param key the property the timeout is bound from, named in the failure
     * @param timeout the configured timeout, or {@code null}
     * @return {@code timeout}, or {@link #DEFAULT_TIMEOUT} when it is {@code null}
     * @throws IllegalArgumentException when the whole milliseconds of {@code timeout} are not between 1 and
     *         {@link Integer#MAX_VALUE}
     */
    private static Duration usableTimeout(String key, Duration timeout) {
        if (timeout == null) {
            return DEFAULT_TIMEOUT;
        }
        if (timeout.compareTo(MIN_TIMEOUT) < 0 || timeout.compareTo(OVERFLOWING_TIMEOUT) >= 0) {
            throw new IllegalArgumentException(
                    key + " must be between 1 ms and " + Integer.MAX_VALUE + " ms, was " + timeout);
        }
        return timeout;
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        }
        catch (ParserConfigurationException ex) {
            throw new IllegalStateException("The XML parser does not support secure processing", ex);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static TransformerFactory secureTransformerFactory() {
        TransformerFactory factory = TransformerFactory.newInstance();
        restrictExternalAccess(factory, XMLConstants.ACCESS_EXTERNAL_DTD);
        restrictExternalAccess(factory, XMLConstants.ACCESS_EXTERNAL_STYLESHEET);
        return factory;
    }

    private static void restrictExternalAccess(TransformerFactory factory, String attribute) {
        try {
            factory.setAttribute(attribute, "");
        }
        catch (IllegalArgumentException unsupported) {
            // Ignored on purpose: a factory that does not know the attribute is still safe here, because it
            // only ever runs identity transforms of in-memory DOM nodes, which load no stylesheet and
            // resolve no DTD.
        }
    }
}
