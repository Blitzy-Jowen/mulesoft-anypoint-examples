package com.mulesoft.examples.legacy_modernization.config;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.MimeHeader;
import jakarta.xml.soap.MimeHeaders;
import jakarta.xml.soap.SOAPException;
import jakarta.xml.soap.SOAPMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.SoapVersion;
import org.springframework.ws.soap.saaj.SaajSoapEnvelopeException;
import org.springframework.ws.soap.saaj.SaajSoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapMessageException;
import org.springframework.ws.soap.saaj.SaajSoapMessageFactory;
import org.springframework.ws.transport.TransportConstants;
import org.springframework.ws.transport.TransportOutputStream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Rewrites outgoing SOAP 1.1 envelopes into the byte form the Apache CXF stack produced, so responses and faults
 * read exactly as the original services wrote them:
 * <pre>
 * &lt;soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"&gt;&lt;soap:Body&gt;&lt;ns2:...&gt;...&lt;/soap:Body&gt;&lt;/soap:Envelope&gt;
 * &lt;soap:Envelope xmlns:soap="..."&gt;&lt;soap:Body&gt;&lt;soap:Fault&gt;&lt;faultcode&gt;soap:Server&lt;/faultcode&gt;&lt;faultstring&gt;msg&lt;/faultstring&gt;&lt;/soap:Fault&gt;&lt;/soap:Body&gt;&lt;/soap:Envelope&gt;
 * </pre>
 * The envelope structure (Envelope, Header, Body, Fault) takes the {@code soap} prefix, an empty Header is dropped,
 * SOAP fault codes are written with that prefix, {@code faultstring} loses its {@code xml:lang}, and whitespace
 * between structural elements is removed. The body payload, its prefixes and its namespace declarations are never
 * touched, so a JAXB {@code ns2:} payload passes through byte for byte. No XML declaration is written for messages
 * the message factory creates, which every response is; SAAJ re-emits the declaration a message parsed from bytes
 * carried, and only its internal envelope class could clear it, so such a message keeps it. Characters outside the
 * Basic Multilingual Plane are written as raw UTF-8, as CXF wrote them, by the messages of
 * {@link CxfStyleMessageFactory}, the message factory each consumer's web-service configuration declares.
 * <p>
 * Spring WS does not auto-detect a plain {@link EndpointInterceptor} bean: the web-service configuration registers
 * this bean on its endpoint mappings, and the listener boundary applies {@link #restyle(SoapMessage)} to every message
 * it sends. The bean holds no state, so one instance serves all requests concurrently. Restyling must be the last
 * change made to a response, because Spring's cached header wrapper still points at a Header this class detaches.
 */
@Component
public class SoapEnvelopeStyleInterceptor implements EndpointInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SoapEnvelopeStyleInterceptor.class);

    /** Namespace of the SOAP 1.1 envelope. */
    public static final String SOAP11_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    /** Prefix CXF bound to {@link #SOAP11_NS}. */
    public static final String PREFIX = "soap";

    /** Namespace of the {@code xml:} prefix, which carries {@code xml:lang}. */
    public static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    /** Namespace of {@code xmlns} namespace-declaration attributes. */
    public static final String XMLNS_NS = "http://www.w3.org/2000/xmlns/";

    private static final String ENVELOPE = "Envelope";
    private static final String HEADER = "Header";
    private static final String BODY = "Body";
    private static final String FAULT = "Fault";
    private static final String FAULT_CODE = "faultcode";
    private static final String FAULT_STRING = "faultstring";
    private static final String XMLNS = "xmlns";
    private static final String DEFAULT_NAMESPACE_PREFIX = "";

    @Override
    public boolean handleRequest(MessageContext messageContext, Object endpoint) {
        return true;
    }

    @Override
    public boolean handleResponse(MessageContext messageContext, Object endpoint) {
        restyleResponse(messageContext);
        return true;
    }

    @Override
    public boolean handleFault(MessageContext messageContext, Object endpoint) {
        restyleResponse(messageContext);
        return true;
    }

    @Override
    public void afterCompletion(MessageContext messageContext, Object endpoint, Exception ex) {
        // Restyling completes in handleResponse/handleFault; nothing is held until completion.
    }

    /**
     * Restyles {@code message} in place. A {@code null} message, and an envelope that is not SOAP 1.1, are left
     * unchanged. Applying it again to a restyled message leaves the bytes unchanged.
     *
     * @param message the message to restyle, usually a response or fault about to be written
     * @throws SaajSoapEnvelopeException when the SAAJ message cannot expose its envelope or properties
     */
    public void restyle(SoapMessage message) {
        if (message == null) {
            return;
        }
        if (message instanceof SaajSoapMessage saajSoapMessage) {
            restyleSaaj(saajSoapMessage.getSaajMessage());
            return;
        }
        Document document = message.getDocument();
        if (document != null && restyleEnvelope(document.getDocumentElement())) {
            message.setDocument(document);
        }
    }

    private void restyleResponse(MessageContext messageContext) {
        if (messageContext.hasResponse() && messageContext.getResponse() instanceof SoapMessage response) {
            restyle(response);
        }
    }

    private static void restyleSaaj(SOAPMessage saajMessage) {
        if (saajMessage == null) {
            return;
        }
        try {
            if (!restyleEnvelope(saajMessage.getSOAPPart().getEnvelope())) {
                return;
            }
            Object writeXmlDeclaration = saajMessage.getProperty(SOAPMessage.WRITE_XML_DECLARATION);
            if (writeXmlDeclaration != null && "true".equalsIgnoreCase(writeXmlDeclaration.toString())) {
                saajMessage.setProperty(SOAPMessage.WRITE_XML_DECLARATION, "false");
            }
        }
        catch (SOAPException ex) {
            throw new SaajSoapEnvelopeException("Could not restyle the SOAP envelope: " + ex.getMessage(), ex);
        }
    }

    /**
     * Applies the CXF style to the structural elements under {@code envelope}; SAAJ elements are DOM elements, so
     * both message kinds share this routine.
     *
     * @return {@code true} when {@code envelope} is a SOAP 1.1 envelope and was restyled
     */
    private static boolean restyleEnvelope(Element envelope) {
        if (envelope == null || !isSoapElement(envelope, ENVELOPE)) {
            return false;
        }
        Element header = firstSoapChild(envelope, HEADER);
        Element body = firstSoapChild(envelope, BODY);
        Element fault = body == null ? null : firstSoapChild(body, FAULT);

        // Recorded before any change: the prefixes a fault code may use for the envelope namespace.
        Set<String> soapPrefixes = soapPrefixes(envelope, header, body, fault);

        if (header != null && firstChildElement(header) == null) {
            envelope.removeChild(header);
            header = null;
        }
        if (fault != null) {
            restyleFaultChildren(fault, soapPrefixes);
        }
        for (Element element : structuralElements(envelope, header, body, fault)) {
            removeEnvelopeDeclarations(element, soapPrefixes);
            // SAAJ refuses to rename Envelope, Header, Body and Fault (setElementQName throws), so only the prefix
            // of the underlying DOM node changes; the namespace, and therefore SAAJ's view of the element, stays.
            element.setPrefix(PREFIX);
            removeWhitespaceText(element);
        }
        envelope.setAttributeNS(XMLNS_NS, XMLNS + ":" + PREFIX, SOAP11_NS);
        return true;
    }

    private static void restyleFaultChildren(Element fault, Set<String> soapPrefixes) {
        for (Element child : childElements(fault)) {
            if (child.getNamespaceURI() != null) {
                continue;
            }
            String name = localName(child);
            if (FAULT_CODE.equals(name)) {
                String restyled = restyleFaultCode(child.getTextContent(), soapPrefixes);
                if (restyled != null) {
                    child.setTextContent(restyled);
                }
            }
            else if (FAULT_STRING.equals(name)) {
                child.removeAttributeNS(XML_NS, "lang");
                if (child.hasAttribute("xml:lang")) {
                    child.removeAttribute("xml:lang");
                }
            }
        }
    }

    /**
     * @return {@code soap:<localPart>} when the code's prefix is bound to the envelope namespace, otherwise
     *         {@code null}, meaning the code (one of another namespace, or not a QName) stays as written
     */
    private static String restyleFaultCode(String code, Set<String> soapPrefixes) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        int colon = trimmed.indexOf(':');
        if (trimmed.isEmpty() || colon == 0 || colon == trimmed.length() - 1) {
            return null;
        }
        String prefix = colon < 0 ? DEFAULT_NAMESPACE_PREFIX : trimmed.substring(0, colon);
        String localPart = colon < 0 ? trimmed : trimmed.substring(colon + 1);
        return soapPrefixes.contains(prefix) ? PREFIX + ":" + localPart : null;
    }

    /**
     * Collects every prefix bound to {@link #SOAP11_NS} on the structural elements: their own prefixes (a DOM built
     * without declaration attributes still binds them) and their {@code xmlns} declarations. The default namespace
     * is recorded as the empty prefix.
     */
    private static Set<String> soapPrefixes(Element envelope, Element header, Element body, Element fault) {
        Set<String> prefixes = new HashSet<>();
        for (Element element : structuralElements(envelope, header, body, fault)) {
            String ownPrefix = element.getPrefix();
            prefixes.add(ownPrefix == null ? DEFAULT_NAMESPACE_PREFIX : ownPrefix);
            NamedNodeMap attributes = element.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                Node attribute = attributes.item(i);
                if (isNamespaceDeclaration(attribute) && SOAP11_NS.equals(attribute.getNodeValue())) {
                    prefixes.add(declaredPrefix(attribute));
                }
            }
        }
        return prefixes;
    }

    /**
     * Removes the declarations of the recorded envelope prefixes, and any declaration of {@link #PREFIX} itself,
     * whatever it binds: the element is about to carry {@code soap:} for the envelope namespace, and a second binding
     * of that prefix on the same element would be serialized as a duplicate attribute. Payload nodes keep their own
     * namespaces, which the serializer declares wherever they are still used.
     */
    private static void removeEnvelopeDeclarations(Element element, Set<String> soapPrefixes) {
        List<String> obsolete = new ArrayList<>();
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (!isNamespaceDeclaration(attribute)) {
                continue;
            }
            String prefix = declaredPrefix(attribute);
            if (PREFIX.equals(prefix)
                    || (soapPrefixes.contains(prefix) && SOAP11_NS.equals(attribute.getNodeValue()))) {
                obsolete.add(attribute.getNodeName());
            }
        }
        for (String qualifiedName : obsolete) {
            element.removeAttribute(qualifiedName);
        }
    }

    /** Removes whitespace-only text nodes that are direct children of {@code element}; descendants are not visited. */
    private static void removeWhitespaceText(Element element) {
        Node child = element.getFirstChild();
        while (child != null) {
            Node next = child.getNextSibling();
            if (child.getNodeType() == Node.TEXT_NODE && isXmlWhitespace(child.getNodeValue())) {
                element.removeChild(child);
            }
            child = next;
        }
    }

    private static boolean isXmlWhitespace(String text) {
        if (text == null) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                return false;
            }
        }
        return true;
    }

    private static boolean isNamespaceDeclaration(Node attribute) {
        if (XMLNS_NS.equals(attribute.getNamespaceURI())) {
            return true;
        }
        String name = attribute.getNodeName();
        return XMLNS.equals(name) || name.startsWith(XMLNS + ":");
    }

    private static String declaredPrefix(Node declaration) {
        String name = declaration.getNodeName();
        return XMLNS.equals(name) ? DEFAULT_NAMESPACE_PREFIX : name.substring(XMLNS.length() + 1);
    }

    private static List<Element> structuralElements(Element envelope, Element header, Element body, Element fault) {
        List<Element> elements = new ArrayList<>(4);
        for (Element element : new Element[] { envelope, header, body, fault }) {
            if (element != null) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static Element firstSoapChild(Element parent, String localName) {
        for (Element child : childElements(parent)) {
            if (isSoapElement(child, localName)) {
                return child;
            }
        }
        return null;
    }

    private static Element firstChildElement(Element parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                return (Element) child;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                elements.add((Element) child);
            }
        }
        return elements;
    }

    private static boolean isSoapElement(Element element, String localName) {
        return SOAP11_NS.equals(element.getNamespaceURI()) && localName.equals(localName(element));
    }

    private static String localName(Node node) {
        String localName = node.getLocalName();
        if (localName != null) {
            return localName;
        }
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /**
     * Spring WS's {@link SaajSoapMessageFactory}, except that every message it creates (response, fault or outbound
     * request) writes characters outside the Basic Multilingual Plane as raw UTF-8, as CXF 2.7 wrote them through
     * Woodstox, where SAAJ writes a numeric character reference such as {@code &#128512;}. Messages read from a stream
     * are created as Spring WS creates them, because they are read, not written. Configured once at startup, one
     * instance then serves all requests concurrently, as Spring's does.
     */
    public static class CxfStyleMessageFactory extends SaajSoapMessageFactory {

        /** Spring's default, which it keeps in a private field. */
        private boolean langAttributeOnSoap11FaultString = true;

        /** Creates the factory without a SAAJ factory, which {@link #afterPropertiesSet()} creates as Spring's does. */
        public CxfStyleMessageFactory() {
        }

        /**
         * Creates the factory over a SAAJ factory.
         *
         * @param messageFactory the SAAJ message factory every message is created with
         */
        public CxfStyleMessageFactory(MessageFactory messageFactory) {
            super(messageFactory);
        }

        @Override
        public void setLangAttributeOnSoap11FaultString(boolean langAttributeOnSoap11FaultString) {
            super.setLangAttributeOnSoap11FaultString(langAttributeOnSoap11FaultString);
            this.langAttributeOnSoap11FaultString = langAttributeOnSoap11FaultString;
        }

        /** Spring's empty message, with its message properties, written as {@link CxfStyleSaajSoapMessage} writes. */
        @Override
        public SaajSoapMessage createWebServiceMessage() {
            SaajSoapMessage created = super.createWebServiceMessage();
            return new CxfStyleSaajSoapMessage(created.getSaajMessage(), langAttributeOnSoap11FaultString,
                    getMessageFactory());
        }
    }

    /**
     * A SAAJ message whose characters outside the Basic Multilingual Plane are written as their four UTF-8 bytes. CXF
     * 2.7 serialized through Woodstox, which wrote them so; SAAJ serializes through the JDK's identity transformer,
     * which writes each one in text and attribute values as a numeric character reference. When the SOAP part holds
     * such a character, the document SAAJ transforms is transformed by a transformer built as SAAJ builds its own, but
     * with the output encoding UTF-16, under which the JDK writes those characters as characters and every other one
     * exactly as for UTF-8; the result is encoded as UTF-8, behind the declaration SAAJ writes when
     * {@link SOAPMessage#WRITE_XML_DECLARATION} is {@code "true"}. Every other byte, and every header but a
     * {@code Content-Length} that counts these bytes, is the one Spring WS and SAAJ write. SAAJ's own write is used
     * unchanged for a message with attachments or an XOP package, one whose
     * {@link SOAPMessage#CHARACTER_SET_ENCODING} is not UTF-8, one without such a character, and one holding an
     * unpaired surrogate, which UTF-8 cannot encode.
     */
    private static final class CxfStyleSaajSoapMessage extends SaajSoapMessage {

        /** The charset SAAJ's envelope serializes in when {@link SOAPMessage#CHARACTER_SET_ENCODING} is not set. */
        private static final String SAAJ_DEFAULT_CHARSET = "utf-8";

        /** The system property under which SAAJ uses the JDK's built-in transformer factory. */
        private static final String SAAJ_DEFAULT_TRANSFORMER_FACTORY = "saaj.use.default.transformer.factory";

        CxfStyleSaajSoapMessage(SOAPMessage soapMessage, boolean langAttributeOnSoap11FaultString,
                MessageFactory messageFactory) {
            super(soapMessage, langAttributeOnSoap11FaultString, messageFactory);
        }

        /**
         * Writes the message as {@link SaajSoapMessage#writeTo(OutputStream)} does, with the bytes of
         * {@link #rawUtf8Envelope()}: the same MIME header steps, {@code Content-Length} counting the bytes written,
         * and afterwards the message left as SAAJ leaves a written one.
         */
        @Override
        public void writeTo(OutputStream outputStream) throws IOException {
            byte[] envelope = rawUtf8Envelope();
            if (envelope == null) {
                super.writeTo(outputStream);
                return;
            }
            SOAPMessage message = getSaajMessage();
            MimeHeaders mimeHeaders = message.getMimeHeaders();
            if (ObjectUtils.isEmpty(mimeHeaders.getHeader(TransportConstants.HEADER_ACCEPT))) {
                mimeHeaders.setHeader(TransportConstants.HEADER_ACCEPT, getVersion().getContentType());
            }
            try {
                message.saveChanges();
                TransportOutputStream transport = outputStream instanceof TransportOutputStream transportOutputStream
                        ? transportOutputStream
                        : null;
                if (transport != null
                        && ObjectUtils.isEmpty(mimeHeaders.getHeader(TransportConstants.HEADER_CONTENT_TYPE))) {
                    mimeHeaders.addHeader(TransportConstants.HEADER_CONTENT_TYPE, envelopeContentType(message));
                    message.saveChanges();
                }
                // SAAJ counted its own bytes, in which each such character took a whole character reference.
                if (!ObjectUtils.isEmpty(mimeHeaders.getHeader(TransportConstants.HEADER_CONTENT_LENGTH))) {
                    mimeHeaders.setHeader(TransportConstants.HEADER_CONTENT_LENGTH, Integer.toString(envelope.length));
                }
                if (transport != null) {
                    for (Iterator<MimeHeader> headers = mimeHeaders.getAllHeaders(); headers.hasNext();) {
                        MimeHeader header = headers.next();
                        transport.addHeader(header.getName(), header.getValue());
                    }
                }
                outputStream.write(envelope);
                outputStream.flush();
                // SAAJ's own write of the bytes it saved, discarded, leaves the message as SAAJ leaves every written
                // one: a SOAP 1.1 message without SOAPAction gets SOAPAction "", and the next write saves again.
                message.writeTo(OutputStream.nullOutputStream());
            }
            catch (SOAPException ex) {
                throw new SaajSoapMessageException("Could not write message to OutputStream: " + ex.getMessage(), ex);
            }
        }

        /**
         * The SOAP part as SAAJ writes it, with every character outside the Basic Multilingual Plane as raw UTF-8, or
         * {@code null} when SAAJ's own write is to be used.
         */
        private byte[] rawUtf8Envelope() {
            SOAPMessage message = getSaajMessage();
            String charset;
            Object writeXmlDeclaration;
            Document document;
            try {
                charset = serializationCharset(message);
                if (message.countAttachments() > 0 || isXopPackage()
                        || !StandardCharsets.UTF_8.name().equalsIgnoreCase(charset)) {
                    return null;
                }
                writeXmlDeclaration = message.getProperty(SOAPMessage.WRITE_XML_DECLARATION);
                document = message.getSOAPPart().getEnvelope().getOwnerDocument();
            }
            catch (SOAPException ex) {
                log.debug("SOAP envelope unavailable for the UTF-8 write; SAAJ writes the message and reports it");
                return null;
            }
            if (document == null || !containsSurrogate(document)) {
                return null;
            }
            StringWriter text = new StringWriter();
            if (writeXmlDeclaration != null && "true".equalsIgnoreCase(writeXmlDeclaration.toString())) {
                text.append("<?xml version=\"").append(document.getXmlVersion()).append("\" encoding=\"")
                        .append(charset).append("\" ?>");
            }
            try {
                newUtf16Transformer().transform(new DOMSource(document), new StreamResult(text));
                ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(CharBuffer.wrap(text.getBuffer()));
                byte[] bytes = new byte[encoded.remaining()];
                encoded.get(bytes);
                return bytes;
            }
            catch (TransformerException | CharacterCodingException ex) {
                log.debug("SOAP message not writable as raw UTF-8 ({}); SAAJ writes it unchanged",
                        ex.getClass().getSimpleName());
                return null;
            }
        }

        /** The charset SAAJ's envelope serializes in: {@link SOAPMessage#CHARACTER_SET_ENCODING}, else its default. */
        private static String serializationCharset(SOAPMessage message) throws SOAPException {
            Object charset = message.getProperty(SOAPMessage.CHARACTER_SET_ENCODING);
            return charset == null ? SAAJ_DEFAULT_CHARSET : charset.toString();
        }

        /** The Content-Type Spring WS adds for the envelope's SOAP version when SAAJ set none. */
        private static String envelopeContentType(SOAPMessage message) throws SOAPException {
            String namespace = message.getSOAPPart().getEnvelope().getElementQName().getNamespaceURI();
            return SoapVersion.SOAP_11.getEnvelopeNamespaceUri().equals(namespace)
                    ? SoapVersion.SOAP_11.getContentType()
                    : SoapVersion.SOAP_12.getContentType();
        }

        /**
         * Whether any node name or value under {@code document}, attributes included, holds a UTF-16 surrogate,
         * paired or not. The walk is iterative, so nesting depth costs no stack. Attributes are read on elements only,
         * because SAAJ's text nodes fail on {@code getAttributes()} instead of answering {@code null}.
         */
        private static boolean containsSurrogate(Document document) {
            Node node = document;
            while (node != null) {
                if (hasSurrogate(node.getNodeName()) || hasSurrogate(node.getNodeValue())) {
                    return true;
                }
                NamedNodeMap attributes = node.getNodeType() == Node.ELEMENT_NODE ? node.getAttributes() : null;
                if (attributes != null) {
                    for (int i = 0; i < attributes.getLength(); i++) {
                        Node attribute = attributes.item(i);
                        if (hasSurrogate(attribute.getNodeName()) || hasSurrogate(attribute.getNodeValue())) {
                            return true;
                        }
                    }
                }
                node = nextInDocumentOrder(node, document);
            }
            return false;
        }

        /** The node after {@code node} in document order within {@code root}, or {@code null} after the last. */
        private static Node nextInDocumentOrder(Node node, Node root) {
            Node firstChild = node.getFirstChild();
            if (firstChild != null) {
                return firstChild;
            }
            for (Node current = node; current != null && current != root; current = current.getParentNode()) {
                Node sibling = current.getNextSibling();
                if (sibling != null) {
                    return sibling;
                }
            }
            return null;
        }

        private static boolean hasSurrogate(String text) {
            if (text == null) {
                return false;
            }
            for (int i = 0; i < text.length(); i++) {
                if (Character.isSurrogate(text.charAt(i))) {
                    return true;
                }
            }
            return false;
        }

        /**
         * An identity transformer from a factory configured as SAAJ's {@code EfficientStreamingTransformer} configures
         * its own, writing no declaration, in UTF-16. The factory is created per write, because factories are not
         * safe for concurrent use.
         */
        private static Transformer newUtf16Transformer() throws TransformerConfigurationException {
            TransformerFactory factory = Boolean.getBoolean(SAAJ_DEFAULT_TRANSFORMER_FACTORY)
                    ? TransformerFactory.newDefaultInstance()
                    : TransformerFactory.newInstance();
            try {
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            }
            catch (TransformerConfigurationException ex) {
                log.debug("Transformer factory {} does not support secure processing; SAAJ continues without it",
                        factory.getClass().getName());
            }
            setEmptyAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_DTD);
            setEmptyAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_STYLESHEET);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_16.name());
            return transformer;
        }

        private static void setEmptyAttributeIfSupported(TransformerFactory factory, String attribute) {
            try {
                factory.setAttribute(attribute, "");
            }
            catch (IllegalArgumentException ex) {
                log.debug("Transformer factory {} does not support {}; SAAJ continues without it",
                        factory.getClass().getName(), attribute);
            }
        }
    }
}
