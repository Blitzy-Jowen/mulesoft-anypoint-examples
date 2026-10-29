/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.mapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;

/**
 * Hand-written reimplementation of the {@code %dw 1.0} script "JSON to XML" of flow {@code orderRequest}
 * ({@code service-orchestration-and-choice-routing/src/main/app/mule-config.xml}, header line 9), of which this
 * class is the sole owner:
 * <pre>
 * %dw 1.0
 * %output application/xml
 * ---
 * order: {
 *   orderId: payload.orderId,
 *   customer: payload.customer,
 *   orderItems: payload.orderItems.item
 * }
 * </pre>
 * The JSON order the Ajax page publishes on {@code /orders/request} becomes the {@code order} document that
 * {@code XmlToSoapEnvelope.xsl} wraps into the {@code processOrder} request.
 *
 * <p>Output rules, as the script applied them:
 * <ul>
 *   <li>{@code order} holds exactly {@code orderId}, {@code customer} and {@code orderItems}, in that order;
 *       other members of the body are dropped, and an absent or {@code null} member is the empty element
 *       {@code <key/>};</li>
 *   <li>{@code customer} and every nested object keep their members in body order; a JSON array repeats its
 *       key's element once per entry;</li>
 *   <li>{@code orderItems} is {@code payload.orderItems.item}: over an array, the {@code item} value of each
 *       object entry that has an {@code item} member (other entries are skipped), each written as its own
 *       {@code <orderItems>} element whose children are the item's members, with no {@code <item>} wrapper and
 *       no enclosing list element, so an empty selection writes no {@code orderItems} element at all; over a
 *       single object, its {@code item} value; over anything else, {@code null};</li>
 *   <li>strings are written as they are, numbers with their literal digits (integers without a fraction,
 *       decimals with their scale, so {@code 1.50} stays {@code 1.50}), booleans as {@code true}/{@code false}.</li>
 * </ul>
 * The repeated {@code <orderItems>} shape is what DataWeave 1.0 writes for a list under a key; it differs from the
 * {@code orderItems/item} binding of the SOAP {@code Order} model and is kept as the original produced it.
 * Bytes and layout are {@link DwXmlWriter}'s. For the original sample {@code json_1.json} the result is:
 * <pre>{@code
 * <?xml version='1.0' encoding='UTF-8'?>
 * <order>
 *     <orderId>123</orderId>
 *     <customer>
 *         <firstName>Nial</firstName>
 *         <lastName>Darbey</lastName>
 *         <address>Buenos Aires</address>
 *     </customer>
 *     <orderItems>
 *         <manufacturer>Samsung</manufacturer>
 *         <name>Galaxy</name>
 *         <productId>i9000</productId>
 *         <quantity>30</quantity>
 *     </orderItems>
 *     <orderItems>
 *         <manufacturer>Philips</manufacturer>
 *         ...
 *     </orderItems>
 * </order>
 * }</pre>
 * (indentation shown as spaces; the writer emits tabs, and nothing follows the root end tag).
 *
 * <p>The body is read as a Jackson tree, so a member name the body repeats keeps only its last value, where
 * DataWeave kept every occurrence; JSON writers, the order page's {@code JSON.stringify} among them, never emit
 * repeated names.
 *
 * <p>Every member name the document copies, at any depth of {@code orderId}, {@code customer} and each selected
 * {@code item}, becomes an element name, so it must be an XML NCName (an XML 1.0 {@code Name} without {@code ':'});
 * any other name throws {@link IllegalArgumentException} with a message that does not quote it. Names of the members
 * the script drops are not checked.
 *
 * <p>Transformation only: no I/O, no configuration, no logging. Stateless and thread-safe.
 */
@Component
public class AjaxOrderXmlMapper {

    /** Root element of the order document. */
    public static final String ROOT = "order";

    private static final String ORDER_ID = "orderId";
    private static final String CUSTOMER = "customer";
    private static final String ORDER_ITEMS = "orderItems";
    private static final String ITEM = "item";

    private static final String EMPTY_BODY = "Order request body is empty";

    private static final String INVALID_MEMBER_NAME = "Order request member name is not a valid XML element name";

    private final DwXmlWriter dwXmlWriter;

    private final ObjectReader reader;

    /**
     * Creates the mapper.
     *
     * @param dwXmlWriter the DataWeave XML writer that renders the order document
     * @param objectMapper the application's Jackson mapper; a reader is derived from it and the mapper itself is
     *        left unchanged
     */
    public AjaxOrderXmlMapper(DwXmlWriter dwXmlWriter, ObjectMapper objectMapper) {
        this.dwXmlWriter = dwXmlWriter;
        // Decimals are read as BigDecimal and, unlike Jackson's tree default, keep their trailing zeros, so a
        // number keeps the digits the body carried. Parse errors become exception messages, so the parser does not
        // quote the body back into them.
        this.reader = objectMapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .without(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION);
    }

    /**
     * Converts the JSON order to the {@code order} XML document the script produced.
     *
     * @param json the order published on the {@code /orders/request} channel
     * @return the DataWeave XML document, declaration included, without a trailing line feed
     * @throws IllegalArgumentException when the body is {@code null} or blank ("Order request body is empty"), is
     *         not well-formed JSON or holds anything after its value (the parser's message, with the parser
     *         exception as cause), is not a JSON object, or carries a member name that is not an XML NCName
     *         ("Order request member name is not a valid XML element name")
     */
    public String toXml(String json) {
        JsonNode payload = parse(json);
        Map<String, Object> order = new LinkedHashMap<>();
        order.put(ORDER_ID, toValue(payload.get(ORDER_ID)));
        order.put(CUSTOMER, toValue(payload.get(CUSTOMER)));
        order.put(ORDER_ITEMS, selectItems(payload.get(ORDER_ITEMS)));
        return dwXmlWriter.write(dwXmlWriter.fromObject(ROOT, order));
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(EMPTY_BODY);
        }
        JsonNode payload;
        try {
            payload = reader.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        // A top-level array or scalar is rejected rather than selected from element by element; what the
        // original returned for one is a Tier 2A verification point.
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("Order request must be a JSON object, but was "
                    + (payload == null ? "empty" : payload.getNodeType()));
        }
        return payload;
    }

    /**
     * {@code payload.orderItems.item}: a list over an array, the single value over an object, {@code null}
     * otherwise. A scalar has no {@code item} member, the same reason a scalar array entry is skipped.
     */
    private static Object selectItems(JsonNode orderItems) {
        if (orderItems == null) {
            return null;
        }
        if (orderItems.isArray()) {
            List<Object> items = new ArrayList<>();
            for (JsonNode entry : orderItems) {
                if (entry.isObject() && entry.has(ITEM)) {
                    items.add(toValue(entry.get(ITEM)));
                }
            }
            return items;
        }
        if (orderItems.isObject()) {
            return toValue(orderItems.get(ITEM));
        }
        return null;
    }

    /** The plain value tree {@link DwXmlWriter#fromObject(String, Object)} writes, in body order. */
    private static Object toValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> members = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext();) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!isNcName(field.getKey())) {
                    throw new IllegalArgumentException(INVALID_MEMBER_NAME);
                }
                members.put(field.getKey(), toValue(field.getValue()));
            }
            return members;
        }
        if (node.isArray()) {
            List<Object> entries = new ArrayList<>(node.size());
            for (JsonNode entry : node) {
                entries.add(toValue(entry));
            }
            return entries;
        }
        if (node.isIntegralNumber()) {
            return node.bigIntegerValue();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.asText();
    }

    /**
     * Whether {@code name} is an {@code NCName} of Namespaces in XML 1.0: the XML 1.0 (Fifth Edition) {@code Name}
     * production without {@code ':'}. The name is read by code point, so a supplementary character counts as one
     * character and a lone surrogate matches no range.
     */
    private static boolean isNcName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        int first = name.codePointAt(0);
        if (!isNameStartChar(first)) {
            return false;
        }
        for (int at = Character.charCount(first); at < name.length();) {
            int codePoint = name.codePointAt(at);
            if (!isNameChar(codePoint)) {
                return false;
            }
            at += Character.charCount(codePoint);
        }
        return true;
    }

    /** XML 1.0 (Fifth Edition) {@code NameStartChar} without {@code ':'}. */
    private static boolean isNameStartChar(int c) {
        return (c >= 'A' && c <= 'Z') || c == '_' || (c >= 'a' && c <= 'z')
                || (c >= 0xC0 && c <= 0xD6) || (c >= 0xD8 && c <= 0xF6) || (c >= 0xF8 && c <= 0x2FF)
                || (c >= 0x370 && c <= 0x37D) || (c >= 0x37F && c <= 0x1FFF) || (c >= 0x200C && c <= 0x200D)
                || (c >= 0x2070 && c <= 0x218F) || (c >= 0x2C00 && c <= 0x2FEF) || (c >= 0x3001 && c <= 0xD7FF)
                || (c >= 0xF900 && c <= 0xFDCF) || (c >= 0xFDF0 && c <= 0xFFFD) || (c >= 0x10000 && c <= 0xEFFFF);
    }

    /** XML 1.0 (Fifth Edition) {@code NameChar} without {@code ':'}. */
    private static boolean isNameChar(int c) {
        return isNameStartChar(c) || c == '-' || c == '.' || (c >= '0' && c <= '9') || c == 0xB7
                || (c >= 0x300 && c <= 0x36F) || (c >= 0x203F && c <= 0x2040);
    }
}
