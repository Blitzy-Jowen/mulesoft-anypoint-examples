/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.mapper;

import java.io.IOException;
import java.io.StringReader;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;

/**
 * Hand-written reimplementation of the {@code %dw 1.0} script "JSON to XML and set API key"
 * ({@code web-service-consumer/src/main/app/tshirt-service-consumer.xml}, flow {@code orderTshirt}, header line 12),
 * of which this class is the sole owner:
 * <pre>
 * %dw 1.0
 * %output application/xml
 * %namespace ns0 http://mulesoft.org/tshirt-service
 * ---
 * ns0#OrderTshirt: payload
 * </pre>
 * The JSON body posted to {@code /orders} becomes the {@code ns0:OrderTshirt} element that the
 * {@code OrderTshirt} operation sends as the SOAP body. The sibling {@code soap.header} script of the same transform
 * ({@code AuthenticationHeader/apiKey}) is not part of this mapper; the T-shirt service client adds that header.
 *
 * <p>Output rules, as DataWeave applied them to {@code payload}:
 * <ul>
 *   <li>the root is {@code ns0:OrderTshirt}, declaring {@code xmlns:ns0}; every descendant is unqualified;</li>
 *   <li>each member of a JSON object becomes one child element named after its key, in body order (not the
 *       {@code xsd:sequence} order of {@code tshirt.wsdl}), and a key the body repeats is written once per
 *       occurrence;</li>
 *   <li>a JSON array repeats its key's element once per item; JSON {@code null} is an empty element
 *       {@code <key/>};</li>
 *   <li>strings are written as they are, numbers in their parsed form (decimals keep their scale, so {@code 1.50}
 *       stays {@code 1.50}), booleans as {@code true}/{@code false}.</li>
 * </ul>
 * Bytes and layout are {@link DwXmlWriter}'s. For the original sample {@code message.json} the result is:
 * <pre>{@code
 * <?xml version='1.0' encoding='UTF-8'?>
 * <ns0:OrderTshirt xmlns:ns0="http://mulesoft.org/tshirt-service">
 *     <email>customerID@gmail.com</email>
 *     <address1>Corrientes 316</address1>
 *     ...
 *     <stateOrProvince>CABA</stateOrProvince>
 * </ns0:OrderTshirt>
 * }</pre>
 * (indentation shown as spaces; the writer emits tabs, and nothing follows the root end tag).
 *
 * <p>Transformation only: no I/O, no configuration, no logging. Stateless and thread-safe.
 */
@Component
public class OrderTshirtRequestMapper {

    /** Namespace of the T-shirt service messages, the script's {@code ns0}. */
    public static final String NAMESPACE = "http://mulesoft.org/tshirt-service";

    /** Local name of the request element of the {@code OrderTshirt} operation. */
    public static final String ROOT = "OrderTshirt";

    private static final String PREFIX = "ns0";

    private static final String EMPTY_BODY = "Request body is empty";
    private static final String NOT_AN_OBJECT = "Request body must be a JSON object";

    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";

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

    private final DwXmlWriter dwXmlWriter;

    private final JsonFactory jsonFactory;

    /**
     * Creates the mapper.
     *
     * @param dwXmlWriter the DataWeave XML writer that renders the element tree
     */
    public OrderTshirtRequestMapper(DwXmlWriter dwXmlWriter) {
        this.dwXmlWriter = dwXmlWriter;
        // Parse errors become the message of the 500 the caller receives; a message naming its position is
        // enough, so the parser does not quote the request body back into it.
        this.jsonFactory = JsonFactory.builder()
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .build();
    }

    /**
     * Converts the JSON order to the {@code ns0:OrderTshirt} XML document the script produced.
     *
     * @param json the request body; its encoding (UTF-8, UTF-16 or UTF-32) is detected from the bytes
     * @return the DataWeave XML document, declaration included, without a trailing line feed
     * @throws IllegalArgumentException when the body is {@code null}, empty or JSON whitespace only
     *         ("Request body is empty"), is not well-formed JSON or holds anything after its value (the parser's
     *         message, with the parser exception as cause), or is not a JSON object
     *         ("Request body must be a JSON object")
     */
    public String toXml(byte[] json) {
        if (isBlank(json)) {
            throw new IllegalArgumentException(EMPTY_BODY);
        }
        Object tree = read(json);
        if (!(tree instanceof Map<?, ?>)) {
            // DataWeave writes exactly one root element, so an array or a scalar body has no XML form.
            throw new IllegalArgumentException(NOT_AN_OBJECT);
        }
        return dwXmlWriter.write(dwXmlWriter.fromObject(PREFIX, NAMESPACE, ROOT, tree));
    }

    /**
     * Converts the JSON order to the {@code ns0:OrderTshirt} XML and parses it into a namespace-aware DOM, the
     * form the SOAP client sends as the message body. Whitespace text nodes between elements are kept, as the
     * original sent the DataWeave output unchanged.
     *
     * @param json the request body
     * @return the document whose element is {@code OrderTshirt} in {@link #NAMESPACE}
     * @throws IllegalArgumentException under the conditions of {@link #toXml(byte[])}
     * @throws IllegalStateException when no secure XML parser can be configured, or the writer's output cannot
     *         be parsed
     */
    public Document toDocument(byte[] json) {
        String xml = toXml(json);
        DocumentBuilder builder = newDocumentBuilder();
        try {
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (SAXException | IOException e) {
            throw new IllegalStateException("DataWeave XML output could not be parsed: " + e.getMessage(), e);
        }
    }

    /** True for {@code null}, empty, or nothing but JSON's insignificant whitespace (RFC 8259 section 2). */
    private static boolean isBlank(byte[] json) {
        if (json == null) {
            return true;
        }
        for (byte b : json) {
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') {
                return false;
            }
        }
        return true;
    }

    /*
     * The body is read token by token rather than bound with ObjectMapper.readValue(json, Object.class): Jackson's
     * untyped Map binding keeps only the last of repeated keys, whereas a DataWeave object is a sequence of
     * key-value pairs that keeps every member, so a repeated key must still reach the writer once per occurrence.
     * Scalars get the types the untyped binding with USE_BIG_DECIMAL_FOR_FLOATS gives.
     */
    private Object read(byte[] json) {
        try (JsonParser parser = jsonFactory.createParser(json)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                // A byte-order mark followed by whitespace, or UTF-16/32 whitespace: there is no value at all.
                throw new IllegalArgumentException(EMPTY_BODY);
            }
            Object value = readValue(parser, first);
            JsonToken trailing = parser.nextToken();
            if (trailing != null) {
                throw new JsonParseException(parser,
                        "Trailing token (of type " + trailing + ") found after the JSON value");
            }
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static Object readValue(JsonParser parser, JsonToken token) throws IOException {
        if (token == null) {
            throw new JsonParseException(parser, "Unexpected end-of-input: expected a JSON value");
        }
        return switch (token) {
            case START_OBJECT -> readObject(parser);
            case START_ARRAY -> readArray(parser);
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> parser.getNumberValue();
            case VALUE_NUMBER_FLOAT -> parser.getDecimalValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw new JsonParseException(parser, "Unexpected token (" + token + "): expected a JSON value");
        };
    }

    private static Map<String, Object> readObject(JsonParser parser) throws IOException {
        JsonObject object = new JsonObject();
        for (String key = parser.nextFieldName(); key != null; key = parser.nextFieldName()) {
            object.add(key, readValue(parser, parser.nextToken()));
        }
        return object;
    }

    private static List<Object> readArray(JsonParser parser) throws IOException {
        List<Object> items = new ArrayList<>();
        for (JsonToken token = parser.nextToken(); token != JsonToken.END_ARRAY; token = parser.nextToken()) {
            items.add(readValue(parser, token));
        }
        return items;
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
     * A JSON object as DataWeave reads it: its members in body order, a repeated key kept once per occurrence.
     * {@link #entrySet()} therefore counts duplicates, and {@link #get(Object)} sees the first member with the key.
     * Read-only once built.
     */
    private static final class JsonObject extends AbstractMap<String, Object> {

        private final List<Map.Entry<String, Object>> members = new ArrayList<>();

        private final Set<Map.Entry<String, Object>> entries = new AbstractSet<>() {
            @Override
            public Iterator<Map.Entry<String, Object>> iterator() {
                return Collections.unmodifiableList(members).iterator();
            }

            @Override
            public int size() {
                return members.size();
            }
        };

        private void add(String key, Object value) {
            members.add(new AbstractMap.SimpleImmutableEntry<>(key, value));
        }

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            return entries;
        }
    }
}
