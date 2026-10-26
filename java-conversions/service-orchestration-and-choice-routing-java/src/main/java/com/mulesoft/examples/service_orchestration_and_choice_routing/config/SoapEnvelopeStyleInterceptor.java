package com.mulesoft.examples.service_orchestration_and_choice_routing.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.xml.soap.SOAPException;
import jakarta.xml.soap.SOAPMessage;

import org.springframework.stereotype.Component;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.saaj.SaajSoapEnvelopeException;
import org.springframework.ws.soap.saaj.SaajSoapMessage;
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
 * carried, and only its internal envelope class could clear it, so such a message keeps it.
 * <p>
 * Spring WS does not auto-detect a plain {@link EndpointInterceptor} bean: the web-service configuration registers
 * this bean on its endpoint mappings, and the listener boundary applies {@link #restyle(SoapMessage)} to every message
 * it sends. The bean holds no state, so one instance serves all requests concurrently. Restyling must be the last
 * change made to a response, because Spring's cached header wrapper still points at a Header this class detaches.
 */
@Component
public class SoapEnvelopeStyleInterceptor implements EndpointInterceptor {

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
}
