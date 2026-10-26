/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.mapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Reads XML the way DataWeave 1.0's XML reader did, producing the object tree its JSON writer then
 * serialized (shared component SC-4, "XML-to-JSON with repeated elements as repeated keys"). It backs scripts
 * such as {@code %output application/json --- payload} and {@code payload.ns0#ListInventoryResponse}.
 *
 * <p>SC-4 reference implementation (AAP 0.4.4); copies change only the package line. It therefore depends on
 * the JDK and {@code @Component} only; do not add project-specific logic.
 *
 * <p>Conversion rules:
 * <ul>
 *   <li>An element's key is its local name. Namespace prefixes, namespace URIs and attributes are dropped.</li>
 *   <li>An element with at least one element child becomes a {@link DwObject} holding one entry per element
 *       child, in document order. A repeated element is a repeated key, never an array, so
 *       {@code <r><item>1</item><item>2</item></r>} reads as {@code {item: "1", item: "2"}}. Text, comments and
 *       processing instructions between child elements, pretty-printing whitespace included, are ignored.</li>
 *   <li>Any other element is a leaf: the concatenation of its text and CDATA content, untrimmed, as a
 *       {@link String} (numbers stay strings, {@code <count>2</count>} reads as {@code "2"}). A leaf with no
 *       text or CDATA node at all, such as {@code <count/>}, reads as {@code null}.</li>
 * </ul>
 *
 * <p>Parsing is namespace-aware and rejects DOCTYPE declarations, so neither external entities nor entity
 * expansion can be used against the parser. Every parser diagnostic is raised as an exception rather than
 * printed. The class is stateless and thread-safe: each parse creates its own factory and builder, neither of
 * which is guaranteed to be thread-safe.
 */
@Component
public class DwXmlToJson {

    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private static final String EMPTY_PAYLOAD = "XML payload is empty";
    private static final String UNPARSEABLE_PAYLOAD = "XML payload could not be parsed";

    /** Raises every warning, error and fatal error, so the parser never writes diagnostics to stderr. */
    private static final ErrorHandler RETHROWING_ERROR_HANDLER = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };

    /** Creates a reader; it holds no state, so one instance serves every caller. */
    public DwXmlToJson() {
    }

    /**
     * Parses an XML text.
     *
     * @param xml the XML document
     * @return the namespace-aware DOM document
     * @throws IllegalArgumentException when {@code xml} is {@code null}, empty or whitespace only, is not
     *         well-formed, or declares a DOCTYPE
     */
    public Document parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException(EMPTY_PAYLOAD);
        }
        return parse(new InputSource(new StringReader(xml)));
    }

    /**
     * Parses XML bytes. The bytes reach the parser undecoded, so it honours the encoding the XML declaration
     * names (UTF-8 when none is declared).
     *
     * @param xml the XML document bytes
     * @return the namespace-aware DOM document
     * @throws IllegalArgumentException when {@code xml} is {@code null}, empty or whitespace only once decoded
     *         as UTF-8, is not well-formed in its declared encoding, or declares a DOCTYPE
     */
    public Document parse(byte[] xml) {
        if (xml == null || xml.length == 0 || new String(xml, StandardCharsets.UTF_8).isBlank()) {
            throw new IllegalArgumentException(EMPTY_PAYLOAD);
        }
        return parse(new InputSource(new ByteArrayInputStream(xml)));
    }

    /**
     * Converts a whole document: the result holds the single entry {@code root key -> content(root)}, which
     * is what a DataWeave {@code payload} of the document is.
     *
     * @param document the parsed document; may be {@code null}
     * @return the converted document, or {@code null} for a {@code null} document
     */
    public DwObject convert(Document document) {
        if (document == null) {
            return null;
        }
        Element root = document.getDocumentElement();
        return new DwObject().add(key(root), content(root));
    }

    /**
     * Selects the root element by qualified name, as DataWeave's {@code payload.ns0#Name} does, and returns
     * its content. A {@code null} and an empty namespace URI both mean "no namespace".
     *
     * @param document the parsed document; may be {@code null}
     * @param namespaceUri the namespace URI the root must have
     * @param localName the local name the root must have
     * @return {@link #content(Element)} of the root when both names match; {@code null} when either differs or
     *         the document is {@code null}, as DataWeave yields {@code null} for a selector that misses
     */
    public Object select(Document document, String namespaceUri, String localName) {
        if (document == null) {
            return null;
        }
        Element root = document.getDocumentElement();
        if (!key(root).equals(localName) || !namespaceOf(root.getNamespaceURI()).equals(namespaceOf(namespaceUri))) {
            return null;
        }
        return content(root);
    }

    /**
     * Converts the content of one element under the rules in the class description.
     *
     * @param element the element to convert
     * @return a {@link DwObject} when the element has element children; otherwise its text as a
     *         {@link String}, or {@code null} when it has no text or CDATA node
     * @throws NullPointerException when {@code element} is {@code null}
     */
    public Object content(Element element) {
        Objects.requireNonNull(element, "element");
        DwObject object = null;
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                if (object == null) {
                    object = new DwObject();
                }
                Element childElement = (Element) child;
                object.add(key(childElement), content(childElement));
            }
        }
        return object != null ? object : text(element);
    }

    private static String text(Element leaf) {
        StringBuilder text = null;
        for (Node child = leaf.getFirstChild(); child != null; child = child.getNextSibling()) {
            short type = child.getNodeType();
            if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
                if (text == null) {
                    text = new StringBuilder();
                }
                text.append(child.getNodeValue());
            }
        }
        return text != null ? text.toString() : null;
    }

    private static String key(Element element) {
        String localName = element.getLocalName();
        if (localName != null) {
            return localName;
        }
        // DOM Level 1 elements (built with createElement, not by a namespace-aware parser) have no local name.
        String nodeName = element.getNodeName();
        return nodeName.substring(nodeName.lastIndexOf(':') + 1);
    }

    private static String namespaceOf(String namespaceUri) {
        return namespaceUri == null ? "" : namespaceUri;
    }

    private static Document parse(InputSource source) {
        DocumentBuilder builder = newDocumentBuilder();
        try {
            return builder.parse(source);
        } catch (SAXException | IOException e) {
            String message = e.getMessage();
            throw new IllegalArgumentException(message != null ? message : UNPARSEABLE_PAYLOAD, e);
        }
    }

    private static DocumentBuilder newDocumentBuilder() {
        // The JDK's built-in implementation, so every feature below is recognized whatever parser the classpath
        // offers through the DocumentBuilderFactory service lookup.
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        try {
            factory.setNamespaceAware(true);
            factory.setFeature(DISALLOW_DOCTYPE, true);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(RETHROWING_ERROR_HANDLER);
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Cannot configure a secure XML parser: " + e.getMessage(), e);
        }
    }

    /**
     * A DataWeave object: an ordered list of key-value pairs in which a key may repeat. Each repeated element
     * of the source XML is its own entry, so {@link #entrySet()} and {@link #size()} count duplicates, and
     * {@code DwJsonWriter}, which iterates {@code entrySet()}, writes every one of them as a repeated key.
     * Values may be {@code null}.
     *
     * <p>{@link #get(Object)} and {@link #containsKey(Object)} see the first entry with the key;
     * {@link #getAll(String)} returns all of them. The map is built through {@link #add(String, Object)} only:
     * {@code put}, {@code putAll}, {@code remove}, {@code clear} and the entry-set view are read-only.
     *
     * <p>{@code equals}, {@code hashCode} and {@code toString} are {@link AbstractMap}'s. Because
     * {@code AbstractMap.equals} compares each entry with the other map's {@code get} of its key, two objects
     * with the same repeated keys compare equal only when every repeated value equals the first one; compare
     * {@link #getAll(String)} or the entry lists instead. Instances are not thread-safe while being built.
     */
    public static final class DwObject extends AbstractMap<String, Object> {

        private final List<Map.Entry<String, Object>> entries = new ArrayList<>();

        private final List<Map.Entry<String, Object>> readOnlyEntries = Collections.unmodifiableList(entries);

        private final Set<Map.Entry<String, Object>> entrySetView = new AbstractSet<>() {
            @Override
            public Iterator<Map.Entry<String, Object>> iterator() {
                return readOnlyEntries.iterator();
            }

            @Override
            public int size() {
                return entries.size();
            }
        };

        /** Creates an empty object. */
        public DwObject() {
        }

        /**
         * Appends an entry, keeping any earlier entry with the same key.
         *
         * @param key the entry key
         * @param value the entry value; may be {@code null}
         * @return this object
         * @throws NullPointerException when {@code key} is {@code null}
         */
        public DwObject add(String key, Object value) {
            entries.add(new AbstractMap.SimpleImmutableEntry<>(Objects.requireNonNull(key, "key"), value));
            return this;
        }

        /**
         * Returns every value stored under a key.
         *
         * @param key the key to look up
         * @return the values in insertion order, {@code null} values included; empty when the key is absent.
         *         The list is a read-only snapshot.
         */
        public List<Object> getAll(String key) {
            List<Object> values = new ArrayList<>();
            for (Map.Entry<String, Object> entry : entries) {
                if (entry.getKey().equals(key)) {
                    values.add(entry.getValue());
                }
            }
            return Collections.unmodifiableList(values);
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            return entrySetView;
        }

        @Override
        public int size() {
            return entries.size();
        }

        @Override
        public Object get(Object key) {
            for (Map.Entry<String, Object> entry : entries) {
                if (entry.getKey().equals(key)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        @Override
        public boolean containsKey(Object key) {
            for (Map.Entry<String, Object> entry : entries) {
                if (entry.getKey().equals(key)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Object put(String key, Object value) {
            throw readOnly();
        }

        @Override
        public void putAll(Map<? extends String, ?> map) {
            throw readOnly();
        }

        @Override
        public Object remove(Object key) {
            throw readOnly();
        }

        @Override
        public void clear() {
            throw readOnly();
        }

        private static UnsupportedOperationException readOnly() {
            return new UnsupportedOperationException("DwObject is read-only; build it with add(String, Object)");
        }
    }
}
