/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.web_service_consumer.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXParseException;

/**
 * Contract of {@link DwXmlToJson}: XML read as DataWeave 1.0 read it, an element with element children as a
 * {@link DwXmlToJson.DwObject} whose repeated elements are repeated keys in document order, any other element
 * as its untrimmed text or {@code null}, names reduced to local names, and a hardened parser that rejects empty,
 * malformed and DOCTYPE-bearing payloads with the parser's diagnostic as the cause.
 *
 * <p>Inputs are inline strings and bytes; no file or classpath resource is read, so the class is copied with
 * {@link DwXmlToJson} changing only the package line.
 */
class DwXmlToJsonTest {

    private static final String TSHIRT_NAMESPACE = "http://mulesoft.org/tshirt-service";

    /** An unqualified, 2-space indented {@code OrderTshirtResponse} with its XML declaration. */
    private static final String ORDER_RESPONSE = "<?xml version='1.0' encoding='UTF-8'?>\n"
            + "<OrderTshirtResponse>\n"
            + "  <orderId>1</orderId>\n"
            + "</OrderTshirtResponse>";

    /** The same response with an {@code ns2}-qualified root and no whitespace. */
    private static final String ORDER_RESPONSE_PREFIXED = "<ns2:OrderTshirtResponse"
            + " xmlns:ns2=\"http://mulesoft.org/tshirt-service\"><orderId>1</orderId></ns2:OrderTshirtResponse>";

    /** An {@code ns2}-qualified {@code ListInventoryResponse} of five unqualified items, 2-space indented. */
    private static final String INVENTORY = "<ns2:ListInventoryResponse"
            + " xmlns:ns2=\"http://mulesoft.org/tshirt-service\">\n"
            + "  <inventory>\n"
            + "    <productCode>4102</productCode>\n"
            + "    <size>L</size>\n"
            + "    <description>Prueba</description>\n"
            + "    <count>2</count>\n"
            + "  </inventory>\n"
            + "  <inventory>\n"
            + "    <productCode>1412</productCode>\n"
            + "    <size>L</size>\n"
            + "    <description>Foo</description>\n"
            + "    <count>9</count>\n"
            + "  </inventory>\n"
            + "  <inventory>\n"
            + "    <productCode>5656</productCode>\n"
            + "    <size>S</size>\n"
            + "    <description>Bar</description>\n"
            + "    <count>2</count>\n"
            + "  </inventory>\n"
            + "  <inventory>\n"
            + "    <productCode>5657</productCode>\n"
            + "    <size>M</size>\n"
            + "    <description>Prueba2</description>\n"
            + "    <count>3</count>\n"
            + "  </inventory>\n"
            + "  <inventory>\n"
            + "    <productCode>1411</productCode>\n"
            + "    <size>M</size>\n"
            + "    <description>Awesome Tshirt</description>\n"
            + "    <count>5</count>\n"
            + "  </inventory>\n"
            + "</ns2:ListInventoryResponse>";

    private final DwXmlToJson reader = new DwXmlToJson();

    @Test
    void selectedInventoryHoldsFiveItemsInDocumentOrderWithStringLeaves() {
        List<Object> items = selectInventory(reader.parse(INVENTORY)).getAll("inventory");

        assertThat(items).hasSize(5).allSatisfy(item -> assertThat(item).isInstanceOf(DwXmlToJson.DwObject.class));
        DwXmlToJson.DwObject first = (DwXmlToJson.DwObject) items.get(0);
        assertThat(first.get("productCode")).isEqualTo("4102");
        assertThat(first.get("size")).isEqualTo("L");
        assertThat(first.get("description")).isEqualTo("Prueba");
        assertThat(first.get("count")).isEqualTo("2");
        DwXmlToJson.DwObject last = (DwXmlToJson.DwObject) items.get(4);
        assertThat(last.get("description")).isEqualTo("Awesome Tshirt");
        assertThat(last.get("count")).isEqualTo("5");
        assertThat(items.stream().map(item -> ((DwXmlToJson.DwObject) item).get("productCode")).toList())
                .containsExactly("4102", "1412", "5656", "5657", "1411");
        for (Object item : items) {
            assertThat(values((DwXmlToJson.DwObject) item))
                    .hasSize(4)
                    .allSatisfy(leaf -> assertThat(leaf).isInstanceOf(String.class));
        }
    }

    @Test
    void selectedInventoryIsAnOrderedObjectWithoutWhitespaceEntries() {
        DwXmlToJson.DwObject response = selectInventory(reader.parse(INVENTORY));

        assertThat(response.get("inventory")).isSameAs(response.getAll("inventory").get(0));
        assertThat(((DwXmlToJson.DwObject) response.get("inventory")).get("productCode")).isEqualTo("4102");
        assertThat(response.size()).isEqualTo(5);
        assertThat(keys(response)).containsExactly("inventory", "inventory", "inventory", "inventory", "inventory");
        for (Object item : values(response)) {
            assertThat(item).isInstanceOf(DwXmlToJson.DwObject.class);
            DwXmlToJson.DwObject inventory = (DwXmlToJson.DwObject) item;
            assertThat(keys(inventory)).containsExactly("productCode", "size", "description", "count");
            assertThat(values(inventory)).allSatisfy(leaf -> assertThat(leaf)
                    .isInstanceOfSatisfying(String.class, text -> assertThat(text).isNotBlank()));
        }
    }

    @Test
    void convertedOrderResponseIsItsRootKeyOverItsOrderIdWhetherOrNotTheRootIsPrefixed() {
        for (String xml : List.of(ORDER_RESPONSE, ORDER_RESPONSE_PREFIXED)) {
            DwXmlToJson.DwObject converted = reader.convert(reader.parse(xml));

            assertThat(keys(converted)).as(xml).containsExactly("OrderTshirtResponse");
            assertThat(converted.get("OrderTshirtResponse")).as(xml).isInstanceOfSatisfying(
                    DwXmlToJson.DwObject.class,
                    response -> assertThat(response.entrySet()).containsExactly(entry("orderId", "1")));
            assertThat(converted).as(xml).hasToString("{OrderTshirtResponse={orderId=1}}");
        }
    }

    @Test
    void selectMissesInventoryUnderAnotherNamespaceAnotherLocalNameOrNoNamespace() {
        Document inventory = reader.parse(INVENTORY);
        assertThat(reader.select(inventory, "urn:other", "ListInventoryResponse")).isNull();
        assertThat(reader.select(inventory, TSHIRT_NAMESPACE, "OrderTshirtResponse")).isNull();

        Document unprefixed = reader.parse("<ListInventoryResponse><inventory><productCode>4102</productCode>"
                + "</inventory></ListInventoryResponse>");
        assertThat(reader.select(unprefixed, TSHIRT_NAMESPACE, "ListInventoryResponse")).isNull();
        // Without a namespace the same root is found, so the miss above is the namespace alone.
        assertThat(reader.select(unprefixed, null, "ListInventoryResponse")).isNotNull();
    }

    @Test
    void contentOfTheDocumentElementIsWhatSelectReturns() {
        Document document = reader.parse(INVENTORY);

        Object content = reader.content(document.getDocumentElement());
        Object selected = reader.select(document, TSHIRT_NAMESPACE, "ListInventoryResponse");

        assertThat(content).isInstanceOf(DwXmlToJson.DwObject.class);
        assertThat(selected).isInstanceOf(DwXmlToJson.DwObject.class);
        // DwObject equality is AbstractMap's, which compares a repeated key with its first value only, so the
        // entry lists are compared instead.
        assertThat(entries((DwXmlToJson.DwObject) content)).hasSize(5)
                .isEqualTo(entries((DwXmlToJson.DwObject) selected));
        assertThat(content).hasToString(selected.toString());
    }

    @Test
    void parsedBytesKeepTheRootNamespaceAndLocalNameAndDecodeUtf8Text() {
        Element root = reader.parse(INVENTORY.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
        assertThat(root.getNamespaceURI()).isEqualTo(TSHIRT_NAMESPACE);
        assertThat(root.getLocalName()).isEqualTo("ListInventoryResponse");

        byte[] nonAscii = INVENTORY.replace("Awesome Tshirt", "\u00d1and\u00fa").getBytes(StandardCharsets.UTF_8);
        List<Object> items = selectInventory(reader.parse(nonAscii)).getAll("inventory");
        assertThat(((DwXmlToJson.DwObject) items.get(4)).get("description")).isEqualTo("\u00d1and\u00fa");
    }

    @Test
    void repeatedElementsBecomeRepeatedKeysInDocumentOrder() {
        DwXmlToJson.DwObject document = reader.convert(reader.parse(
                "<inventory><item>1</item><item>2</item><count>2</count><item>3</item></inventory>"));

        assertThat(keys(document)).containsExactly("inventory");
        DwXmlToJson.DwObject inventory = (DwXmlToJson.DwObject) document.get("inventory");
        assertThat(keys(inventory)).containsExactly("item", "item", "count", "item");
        assertThat(values(inventory)).containsExactly("1", "2", "2", "3");
        assertThat(inventory.size()).isEqualTo(4);
        assertThat(inventory.entrySet()).hasSize(4);
        assertThat(inventory.get("item")).isEqualTo("1");
        assertThat(inventory.getAll("item")).containsExactly("1", "2", "3");
        assertThat(inventory.containsKey("count")).isTrue();
        assertThat(inventory.containsKey("missing")).isFalse();
        assertThat(inventory.get("missing")).isNull();
        assertThat(inventory.getAll("missing")).isEmpty();
    }

    @Test
    void leavesAreUntrimmedStringsAndLeavesWithoutTextAreNull() {
        Object content = reader.convert(reader.parse("<r>"
                + "<padded> 2 </padded>"
                + "<blank>   </blank>"
                + "<number>2</number>"
                + "<cdata>a<![CDATA[<b> & c]]>d</cdata>"
                + "<commented>x<!--note-->y</commented>"
                + "<entities>&amp;&lt;&gt;</entities>"
                + "<empty></empty>"
                + "<selfClosed/>"
                + "<onlyComment><!--c--></onlyComment>"
                + "<onlyInstruction><?target data?></onlyInstruction>"
                + "</r>")).get("r");

        DwXmlToJson.DwObject r = (DwXmlToJson.DwObject) content;
        assertThat(r.entrySet()).containsExactly(
                entry("padded", " 2 "),
                entry("blank", "   "),
                entry("number", "2"),
                entry("cdata", "a<b> & cd"),
                entry("commented", "xy"),
                entry("entities", "&<>"),
                entry("empty", null),
                entry("selfClosed", null),
                entry("onlyComment", null),
                entry("onlyInstruction", null));
    }

    @Test
    void namespacesPrefixesAndAttributesAreDropped() {
        DwXmlToJson.DwObject document = reader.convert(reader.parse(
                "<ns0:ListInventoryResponse xmlns:ns0=\"http://mulesoft.org/tshirt-service\" ns0:attr=\"x\">"
                        + "<inventory id=\"1\">"
                        + "<productCode>TS-1</productCode>"
                        + "<ns0:size xmlns:ns0=\"urn:other\" unit=\"letter\">M</ns0:size>"
                        + "</inventory>"
                        + "</ns0:ListInventoryResponse>"));

        assertThat(keys(document)).containsExactly("ListInventoryResponse");
        DwXmlToJson.DwObject response = (DwXmlToJson.DwObject) document.get("ListInventoryResponse");
        assertThat(keys(response)).containsExactly("inventory");
        DwXmlToJson.DwObject inventory = (DwXmlToJson.DwObject) response.get("inventory");
        assertThat(inventory.entrySet()).containsExactly(entry("productCode", "TS-1"), entry("size", "M"));
    }

    @Test
    void whitespaceCommentsInstructionsAndTextBetweenChildElementsAreIgnored() {
        DwXmlToJson.DwObject document = reader.convert(reader.parse("<?xml version='1.0' encoding='UTF-8'?>\n"
                + "<order>\n"
                + "\t<!-- comment -->\n"
                + "\t<email>customerID@gmail.com</email>\n"
                + "\t<?instruction data?>\n"
                + "\tstray text\n"
                + "\t<size>L</size>\n"
                + "</order>"));

        DwXmlToJson.DwObject order = (DwXmlToJson.DwObject) document.get("order");
        assertThat(order.entrySet()).containsExactly(entry("email", "customerID@gmail.com"), entry("size", "L"));
    }

    @Test
    void selectReturnsRootContentOnlyWhenNamespaceAndLocalNameMatch() {
        String tshirt = "http://mulesoft.org/tshirt-service";
        Document qualified = reader.parse("<ns0:OrderTshirtResponse xmlns:ns0=\"" + tshirt + "\">"
                + "<orderId>7</orderId></ns0:OrderTshirtResponse>");

        Object selected = reader.select(qualified, tshirt, "OrderTshirtResponse");
        assertThat(selected).isInstanceOf(DwXmlToJson.DwObject.class);
        assertThat(((DwXmlToJson.DwObject) selected).entrySet()).containsExactly(entry("orderId", "7"));
        assertThat(reader.select(qualified, "urn:other", "OrderTshirtResponse")).isNull();
        assertThat(reader.select(qualified, tshirt, "ListInventoryResponse")).isNull();
        assertThat(reader.select(qualified, null, "OrderTshirtResponse")).isNull();
        assertThat(reader.select(qualified, "", "OrderTshirtResponse")).isNull();
        assertThat(reader.select(null, tshirt, "OrderTshirtResponse")).isNull();

        Document unqualified = reader.parse("<status>OK</status>");
        assertThat(reader.select(unqualified, null, "status")).isEqualTo("OK");
        assertThat(reader.select(unqualified, "", "status")).isEqualTo("OK");
        assertThat(reader.select(unqualified, tshirt, "status")).isNull();
    }

    @Test
    void convertWrapsRootContentUnderItsLocalNameAndNullDocumentIsNull() {
        assertThat(reader.convert(null)).isNull();

        DwXmlToJson.DwObject leaf = reader.convert(reader.parse("<ns:leaf xmlns:ns=\"urn:n\">v</ns:leaf>"));
        assertThat(leaf.entrySet()).containsExactly(entry("leaf", "v"));

        DwXmlToJson.DwObject empty = reader.convert(reader.parse("<empty/>"));
        assertThat(empty.size()).isEqualTo(1);
        assertThat(empty.containsKey("empty")).isTrue();
        assertThat(empty.get("empty")).isNull();
    }

    @Test
    void parseRejectsEmptyAndBlankPayloads() {
        for (String xml : new String[] {null, "", " \t\r\n"}) {
            assertThatThrownBy(() -> reader.parse(xml))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("XML payload is empty")
                    .hasNoCause();
        }
        for (byte[] xml : new byte[][] {null, new byte[0], " \t\r\n".getBytes(StandardCharsets.UTF_8)}) {
            assertThatThrownBy(() -> reader.parse(xml))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("XML payload is empty")
                    .hasNoCause();
        }
    }

    @Test
    void parseRejectsMalformedXmlWithTheParserDiagnosticAsCause() {
        for (String xml : new String[] {"<r>", "<r></s>", "not xml", "<p:r/>", "<r/><r/>"}) {
            assertThatThrownBy(() -> reader.parse(xml))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasCauseInstanceOf(SAXParseException.class)
                    .satisfies(e -> assertThat(e).hasMessage(e.getCause().getMessage()));
            assertThatThrownBy(() -> reader.parse(xml.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasCauseInstanceOf(SAXParseException.class);
        }
    }

    @Test
    void parseRejectsDoctypeSoNoEntityIsResolvedOrExpanded() {
        String[] doctypes = {
            "<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>",
            "<!DOCTYPE r [<!ENTITY a \"aaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]><r>&b;</r>",
            "<!DOCTYPE r SYSTEM \"r.dtd\"><r/>",
            "<!DOCTYPE r><r/>"
        };
        for (String xml : doctypes) {
            assertThatThrownBy(() -> reader.parse(xml))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DOCTYPE")
                    .hasCauseInstanceOf(SAXParseException.class);
            assertThatThrownBy(() -> reader.parse(xml.getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DOCTYPE")
                    .hasCauseInstanceOf(SAXParseException.class);
        }
    }

    @Test
    void parsedBytesAreDecodedInTheDeclaredEncoding() {
        byte[] latin1 = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><city>Z\u00fcrich</city>"
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThat(reader.convert(reader.parse(latin1)).get("city")).isEqualTo("Z\u00fcrich");

        byte[] undeclared = "<city>Z\u00fcrich \u20ac</city>".getBytes(StandardCharsets.UTF_8);
        assertThat(reader.convert(reader.parse(undeclared)).get("city")).isEqualTo("Z\u00fcrich \u20ac");

        // Text is already decoded, so the declared encoding does not apply to it.
        String text = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><city>Z\u00fcrich \u20ac</city>";
        assertThat(reader.convert(reader.parse(text)).get("city")).isEqualTo("Z\u00fcrich \u20ac");

        byte[] unknown = "<?xml version=\"1.0\" encoding=\"x-unknown-charset\"?><r/>"
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader.parse(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasMessageContaining("x-unknown-charset");
    }

    @Test
    void contentNamesDomLevelOneElementsByTheirUnprefixedName() {
        Document document = reader.parse("<r/>");
        Element parent = document.createElement("ns0:inventory");
        Element item = document.createElement("ns0:item");
        item.appendChild(document.createTextNode("1"));
        parent.appendChild(item);
        parent.appendChild(document.createElement("plain"));

        assertThat(item.getLocalName()).isNull();
        Object content = reader.content(parent);
        assertThat(((DwXmlToJson.DwObject) content).entrySet())
                .containsExactly(entry("item", "1"), entry("plain", null));
    }

    @Test
    void nullElementAndNullKeyAreRejected() {
        assertThatThrownBy(() -> reader.content(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("element");
        assertThatThrownBy(() -> new DwXmlToJson.DwObject().add(null, "v"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("key");
    }

    @Test
    void dwObjectIsBuiltOnlyThroughAddAndExposesReadOnlyViews() {
        DwXmlToJson.DwObject object = new DwXmlToJson.DwObject().add("k", "v1").add("k", null).add("j", "x");

        assertThat(object.size()).isEqualTo(3);
        assertThat(keys(object)).containsExactly("k", "k", "j");
        assertThat(object.get("k")).isEqualTo("v1");
        assertThat(object.getAll("k")).containsExactly("v1", null);
        assertThat(object.containsKey("j")).isTrue();
        assertThat(object.containsKey(null)).isFalse();
        assertThat(object.get(null)).isNull();
        assertThat(object).hasToString("{k=v1, k=null, j=x}");

        String readOnly = "DwObject is read-only; build it with add(String, Object)";
        assertThatThrownBy(() -> object.put("n", "v"))
                .isInstanceOf(UnsupportedOperationException.class).hasMessage(readOnly);
        assertThatThrownBy(() -> object.putAll(Map.of()))
                .isInstanceOf(UnsupportedOperationException.class).hasMessage(readOnly);
        assertThatThrownBy(() -> object.remove("absent"))
                .isInstanceOf(UnsupportedOperationException.class).hasMessage(readOnly);
        assertThatThrownBy(() -> new DwXmlToJson.DwObject().clear())
                .isInstanceOf(UnsupportedOperationException.class).hasMessage(readOnly);
        assertThatThrownBy(() -> object.getAll("k").add("v2")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> object.entrySet().iterator().next().setValue("v2"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> {
            var iterator = object.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }).isInstanceOf(UnsupportedOperationException.class);
        assertThat(object.size()).isEqualTo(3);

        // AbstractMap equality compares each entry with the other map's first value for its key.
        assertThat(new DwXmlToJson.DwObject().add("k", "1").add("k", "1"))
                .isEqualTo(new DwXmlToJson.DwObject().add("k", "1").add("k", "1"));
        assertThat(new DwXmlToJson.DwObject().add("k", "1").add("k", "2"))
                .isNotEqualTo(new DwXmlToJson.DwObject().add("k", "1").add("k", "2"));
        assertThat(new DwXmlToJson.DwObject().add("a", "1")).isEqualTo(Map.of("a", "1"));
    }

    @Test
    void concurrentCallsParseIndependently() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Object>> results = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                String xml = "<r><n>" + i + "</n></r>";
                results.add(pool.submit(() -> reader.convert(reader.parse(xml)).get("r")));
            }
            for (int i = 0; i < results.size(); i++) {
                Object r = results.get(i).get(30, TimeUnit.SECONDS);
                assertThat(((DwXmlToJson.DwObject) r).entrySet()).containsExactly(entry("n", String.valueOf(i)));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** The content of a {@code ListInventoryResponse} root in the tshirt namespace. */
    private DwXmlToJson.DwObject selectInventory(Document document) {
        Object selected = reader.select(document, TSHIRT_NAMESPACE, "ListInventoryResponse");
        assertThat(selected).isInstanceOf(DwXmlToJson.DwObject.class);
        return (DwXmlToJson.DwObject) selected;
    }

    private static List<Map.Entry<String, Object>> entries(Map<String, Object> object) {
        return new ArrayList<>(object.entrySet());
    }

    private static List<String> keys(Map<String, Object> object) {
        return object.entrySet().stream().map(Map.Entry::getKey).toList();
    }

    private static List<Object> values(Map<String, Object> object) {
        List<Object> values = new ArrayList<>();
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            values.add(entry.getValue());
        }
        return values;
    }
}
